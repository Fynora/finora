package com.finora.imports.refresh;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshPreview;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.refresh.StatementRefreshDiff.FreshRow;
import com.finora.imports.refresh.StatementRefreshDiff.KnownRow;
import com.finora.imports.storage.StatementContentService;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statement refresh, step 2b: the dry run. After each deploy, re-reads every statement an older
 * build parsed, compares the result with what the statement has now ({@link StatementRefreshDiff}),
 * and records what a refresh would change (V236). Never writes to anyone's transactions.
 *
 * <p>"After each deploy" falls out of the version: it is the build's commit, so every deploy makes
 * every earlier statement a candidate (Sid's decision, 2026-09-27: automatic on every deploy, not
 * admin-triggered). The worker drains that backlog a small batch at a time, one statement at a
 * time, so it never competes hard with users' own imports.
 *
 * <p>Three phases per statement, each in the right transaction state: the stored file is read in a
 * short read-only transaction (a legacy database copy loads lazily); parsing runs outside any
 * transaction, so a parse failure cannot poison one; the comparison and the preview are written in
 * a transaction of their own.
 */
@Service
public class StatementRefreshDryRunService {

    private static final Logger log = LoggerFactory.getLogger(StatementRefreshDryRunService.class);

    private final StatementImportRepository statementImportRepository;
    private final StatementRefreshInputs inputs;
    private final StatementRefreshPreviewRepository previewRepository;
    private final StatementContentService statementContentService;
    private final ImportService importService;
    private final BuildVersionResolver buildVersionResolver;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final java.util.concurrent.Executor executor;

    @Value("${app.statement-refresh.dry-run.enabled:true}")
    private boolean enabled;

    @Value("${app.statement-refresh.dry-run.batch-size:20}")
    private int batchSize;

    public StatementRefreshDryRunService(StatementImportRepository statementImportRepository,
                                         StatementRefreshInputs inputs,
                                         StatementRefreshPreviewRepository previewRepository,
                                         StatementContentService statementContentService,
                                         ImportService importService,
                                         BuildVersionResolver buildVersionResolver,
                                         PlatformTransactionManager transactionManager,
                                         @org.springframework.beans.factory.annotation.Qualifier("statementRefreshDryRunExecutor")
                                         java.util.concurrent.Executor executor) {
        this.executor = executor;
        this.statementImportRepository = statementImportRepository;
        this.inputs = inputs;
        this.previewRepository = previewRepository;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.buildVersionResolver = buildVersionResolver;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public record BatchResult(String parserVersion, int checked) {}

    @Scheduled(fixedDelayString = "${app.statement-refresh.dry-run.interval-ms:60000}",
            initialDelayString = "${app.statement-refresh.dry-run.initial-delay-ms:120000}")
    public void scheduledRun() {
        if (!enabled) return;
        // Handed off, never run here: see BackgroundWorkConfig.statementRefreshDryRunExecutor. A tick
        // arriving while a batch is still running is dropped by that executor.
        executor.execute(() -> {
            BatchResult result = runBatch(batchSize);
            if (result.checked() > 0) {
                log.info("Statement refresh dry run: checked {} statement(s) under build {}.",
                        result.checked(), result.parserVersion());
            }
        });
    }

    /** Checks up to {@code limit} statements not yet checked under the running build. */
    public BatchResult runBatch(int limit) {
        String version = buildVersionResolver.currentCommit();
        if (version == null) {
            // No way to tell one build's parse from another's, so nothing can be "older".
            return new BatchResult(null, 0);
        }
        // A statement deleted or superseded in the moment between a check's re-read and its commit
        // can still end up with a preview; this removes any such leftover within one tick, so a
        // deleted statement's narrations never linger in a preview.
        writeTransaction.executeWithoutResult(tx -> previewRepository.deleteOrphaned());
        int checked = 0;
        for (UUID statementId : statementImportRepository.findIdsAwaitingRefreshCheck(version, limit)) {
            try {
                check(statementId, version);
                checked++;
            } catch (DataIntegrityViolationException e) {
                // Another worker wrote this statement's preview first: the unique index is the guard.
            } catch (RuntimeException e) {
                // Never let one statement stop the batch. The class name only: a message from a
                // parser can quote the statement's own text.
                log.warn("Statement refresh dry run could not check statement {}: {}", statementId,
                        e.getClass().getSimpleName());
                // Recorded, so it is not picked up again on every tick: with no preview for this
                // build, the same statement -- or twenty of them, a whole batch -- would be
                // re-parsed every minute and the rest of the backlog never reached.
                recordUnexpectedFailure(statementId, version, e);
            }
        }
        return new BatchResult(version, checked);
    }

    private void recordUnexpectedFailure(UUID statementId, String parserVersion, RuntimeException cause) {
        try {
            statementImportRepository.findById(statementId).ifPresent(statement ->
                    save(failed(statement, parserVersion, "UNEXPECTED_" + cause.getClass().getSimpleName())));
        } catch (RuntimeException ignored) {
            // Nothing more to do: the statement stays a candidate and is retried next tick.
        }
    }

    /** Checks one statement now; visible for tests. */
    public void check(UUID statementId, String parserVersion) {
        StatementImport statement = statementImportRepository.findById(statementId).orElse(null);
        if (statement == null || statement.getSupersededBy() != null) return;

        byte[] content;
        try {
            content = readTransaction.execute(tx -> statementContentService.read(
                    statementImportRepository.findById(statementId).orElseThrow()));
        } catch (RuntimeException e) {
            save(failed(statement, parserVersion, "STORED_FILE_UNREADABLE"));
            return;
        }

        StagingResponse staging;
        try {
            staging = importService.parseAndStageAnyFormat(statement.getUserId(), statement.getSourceFormat(),
                    statement.getFileName(), content, statement.getSourceSectionIndex(), null);
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED || e.getCode() == ErrorCode.IMPORT_PDF_PASSWORD_INVALID) {
                save(new StatementRefreshPreview(statementId, statement.getUserId(), parserVersion,
                        StatementRefreshPreview.Status.NEEDS_PASSWORD));
            } else {
                save(failed(statement, parserVersion, e.getCode() == null ? "PARSE_REJECTED" : e.getCode().name()));
            }
            return;
        } catch (Exception e) {
            save(failed(statement, parserVersion, e.getClass().getSimpleName()));
            return;
        }

        writeTransaction.executeWithoutResult(tx -> {
            if (!stillCurrent(statementId)) return;
            StatementRefreshPreview preview = compare(statement, parserVersion, staging);
            previewRepository.deleteOlderThan(statementId, parserVersion);
            previewRepository.save(preview);
        });
    }

    private void save(StatementRefreshPreview preview) {
        writeTransaction.executeWithoutResult(tx -> {
            if (!stillCurrent(preview.getStatementImportId())) return;
            previewRepository.deleteOlderThan(preview.getStatementImportId(), preview.getParserVersion());
            previewRepository.save(preview);
        });
    }

    /**
     * Re-read in the writing transaction: the user may have deleted the statement, or a re-upload
     * superseded it, while it was being parsed. A preview written then would outlive a statement
     * whose narrations the user asked to be deleted, or offer to refresh one that no longer counts.
     */
    private boolean stillCurrent(UUID statementId) {
        return statementImportRepository.findById(statementId)
                .map(s -> s.getSupersededBy() == null)
                .orElse(false);
    }

    private static StatementRefreshPreview failed(StatementImport statement, String parserVersion, String reason) {
        StatementRefreshPreview preview = new StatementRefreshPreview(statement.getId(), statement.getUserId(),
                parserVersion, StatementRefreshPreview.Status.FAILED);
        preview.setDetail(Map.of("reason", reason));
        return preview;
    }

    private StatementRefreshPreview compare(StatementImport statement, String parserVersion, StagingResponse staging) {
        List<KnownRow> known = inputs.knownRows(statement);
        List<FreshRow> fresh = StatementRefreshInputs.freshRows(staging).rows();

        String refusal = inputs.refusal(statement, staging.detectedAccount(), known, fresh);
        if (refusal != null) return failed(statement, parserVersion, refusal);

        StatementRefreshDiff.Result diff = StatementRefreshDiff.compute(known, fresh);
        List<StatementRefreshInputs.FactChange> facts = StatementRefreshInputs.factChanges(statement, staging.detectedAccount());

        StatementRefreshPreview.Status status = !diff.hasChanges() && facts.isEmpty()
                ? StatementRefreshPreview.Status.NO_CHANGES
                : StatementRefreshInputs.removesTooMuch(diff, StatementRefreshInputs.liveRows(known))
                        ? StatementRefreshPreview.Status.NEEDS_REVIEW
                        : StatementRefreshPreview.Status.CHANGES;
        StatementRefreshPreview preview = new StatementRefreshPreview(statement.getId(), statement.getUserId(),
                parserVersion, status);
        preview.setCounts(diff.changed().size(), diff.added().size(), diff.removed().size(),
                diff.conflicts().size(), diff.unchanged(), facts.size());
        if (status != StatementRefreshPreview.Status.NO_CHANGES) {
            preview.setDetail(detail(diff, facts.stream().map(StatementRefreshInputs::factJson).toList()));
        }
        return preview;
    }

    private static Map<String, Object> detail(StatementRefreshDiff.Result diff, List<Map<String, Object>> facts) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changed", diff.changed().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transactionId", c.transactionId().toString());
            m.put("changes", c.changes().stream().map(fc -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("field", fc.field().name());
                f.put("before", fc.before());
                f.put("after", fc.after());
                return f;
            }).toList());
            return m;
        }).toList());
        detail.put("added", diff.added().stream().map(StatementRefreshDryRunService::freshRow).toList());
        detail.put("removed", diff.removed().stream().map(k -> k.transactionId().toString()).toList());
        detail.put("conflicts", diff.conflicts().stream().map(k -> k.transactionId().toString()).toList());
        detail.put("facts", facts);
        return detail;
    }

    private static Map<String, Object> freshRow(FreshRow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("position", f.position());
        m.put("date", f.date() == null ? null : f.date().toString());
        m.put("description", f.description());
        m.put("amount", f.amount() == null ? null : f.amount().toPlainString());
        m.put("type", f.type());
        return m;
    }
}
