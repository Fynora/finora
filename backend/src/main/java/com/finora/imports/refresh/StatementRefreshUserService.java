package com.finora.imports.refresh;

import com.finora.dto.StatementRefreshDtos.RefreshAllResult;
import com.finora.dto.StatementRefreshDtos.RefreshChangedRow;
import com.finora.dto.StatementRefreshDtos.RefreshFieldChange;
import com.finora.dto.StatementRefreshDtos.RefreshOverview;
import com.finora.dto.StatementRefreshDtos.RefreshPendingStatement;
import com.finora.dto.StatementRefreshDtos.RefreshRemovedRow;
import com.finora.dto.StatementRefreshDtos.RefreshRowView;
import com.finora.dto.StatementRefreshDtos.RefreshRunDetail;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshRun;
import com.finora.exception.ApiException;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import com.finora.repository.StatementRefreshPreviewRepository.PendingStatement;
import com.finora.repository.StatementRefreshRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statement refresh, step 5: what the app offers and shows. {@link #overview} is the banner (the
 * statements an improved parser would change, and those it could not check without a password);
 * {@link #refreshAll} is the one tap that updates them; {@link #run} is the "what changed" summary
 * of one refresh.
 *
 * <p>A statement whose last refresh could not be applied (NEEDS_REVIEW, FAILED) keeps its "changes
 * available" check, because nothing was applied. It is left out of both until the next check: offering
 * it again would fail the same way, and "update all" would retry it forever.
 */
@Service
public class StatementRefreshUserService {

    /** Each refresh parses a whole file again; the client calls again while {@code remaining > 0}. */
    static final int MAX_PER_CALL = 10;

    private final StatementRefreshPreviewRepository previewRepository;
    private final StatementRefreshRunRepository runRepository;
    private final StatementImportRepository statementImportRepository;
    private final AccountRepository accountRepository;
    private final StatementRefreshService refreshService;
    private final StatementPasswordService statementPasswordService;

    public StatementRefreshUserService(StatementRefreshPreviewRepository previewRepository,
                                       StatementRefreshRunRepository runRepository,
                                       StatementImportRepository statementImportRepository,
                                       AccountRepository accountRepository,
                                       StatementRefreshService refreshService,
                                       StatementPasswordService statementPasswordService) {
        this.previewRepository = previewRepository;
        this.runRepository = runRepository;
        this.statementImportRepository = statementImportRepository;
        this.accountRepository = accountRepository;
        this.refreshService = refreshService;
        this.statementPasswordService = statementPasswordService;
    }

    public RefreshOverview overview(UUID userId) {
        if (!refreshService.enabled()) return new RefreshOverview(false, false, List.of(), List.of());
        List<RefreshPendingStatement> updatable = new ArrayList<>();
        List<RefreshPendingStatement> needsPassword = new ArrayList<>();
        for (PendingStatement p : offerable(userId)) {
            if (canUpdateWithoutAsking(p)) updatable.add(view(p));
            else needsPassword.add(view(p));
        }
        return new RefreshOverview(true, statementPasswordService.enabled(), updatable, needsPassword);
    }

    /**
     * Updates up to {@link #MAX_PER_CALL} of the statements the banner offers, one after another,
     * and returns what each refresh did. Statements that need a password the user has not saved
     * are left for the password prompt.
     */
    public RefreshAllResult refreshAll(UUID userId) {
        if (!refreshService.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        List<PendingStatement> todo = offerable(userId).stream().filter(this::canUpdateWithoutAsking).toList();
        List<RefreshRunDetail> results = new ArrayList<>();
        for (PendingStatement p : todo.subList(0, Math.min(MAX_PER_CALL, todo.size()))) {
            try {
                results.add(detailOf(refreshService.refresh(userId, p.getStatementImportId(), null), p));
            } catch (ApiException e) {
                // Deleted, replaced or its account deleted since the list was read: nothing to update.
                results.add(new RefreshRunDetail(null, p.getStatementImportId(), p.getFileName(), p.getAccountName(),
                        p.getPeriodStart(), p.getPeriodEnd(), "FAILED", null, 0, 0, 0, 0, null,
                        "NO_LONGER_AVAILABLE", List.of(), List.of(), List.of(), List.of(), List.of()));
            }
        }
        int remaining = (int) offerable(userId).stream().filter(this::canUpdateWithoutAsking).count();
        return new RefreshAllResult(results, remaining);
    }

    /** One recorded refresh, for its owner only. */
    public RefreshRunDetail run(UUID userId, UUID runId) {
        StatementRefreshRun run = runRepository.findById(runId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found"));
        StatementImport statement = statementImportRepository.findById(run.getStatementImportId()).orElse(null);
        String accountName = statement == null ? null
                : accountRepository.findById(statement.getAccountId()).map(a -> a.getName()).orElse(null);
        return detail(run, statement == null ? null : statement.getFileName(), accountName,
                statement == null ? null : statement.getStatementPeriodStart(),
                statement == null ? null : statement.getStatementPeriodEnd());
    }

    // ---- internals ---------------------------------------------------------------------------

    private List<PendingStatement> offerable(UUID userId) {
        return previewRepository.pendingForUser(userId).stream().filter(this::notAlreadyTried).toList();
    }

    /** False when a refresh since this check could not be applied -- see the class doc. */
    private boolean notAlreadyTried(PendingStatement p) {
        return runRepository.findFirstByStatementImportIdOrderByCreatedAtDesc(p.getStatementImportId())
                .map(r -> !(r.getCreatedAt().isAfter(p.getComputedAt())
                        && (r.getStatus() == StatementRefreshRun.Status.NEEDS_REVIEW
                            || r.getStatus() == StatementRefreshRun.Status.FAILED)))
                .orElse(true);
    }

    /** CHANGES, or a statement that needed a password and has one saved since. */
    private boolean canUpdateWithoutAsking(PendingStatement p) {
        return "CHANGES".equals(p.getStatus()) || p.getPasswordSaved();
    }

    private static RefreshPendingStatement view(PendingStatement p) {
        return new RefreshPendingStatement(p.getStatementImportId(), p.getStatus(), p.getFileName(), p.getAccountName(),
                p.getPeriodStart(), p.getPeriodEnd(), p.getRowsChanged(), p.getRowsAdded(),
                p.getRowsRemoved() + p.getRowsConflicting(), p.getFactsChanged(), p.getPasswordSaved());
    }

    private RefreshRunDetail detailOf(StatementRefreshOutcome outcome, PendingStatement p) {
        if (outcome.runId() == null) {
            return new RefreshRunDetail(null, p.getStatementImportId(), p.getFileName(), p.getAccountName(),
                    p.getPeriodStart(), p.getPeriodEnd(), outcome.status().name(), null, 0, 0, 0, 0, null,
                    outcome.reason(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        StatementRefreshRun run = runRepository.findById(outcome.runId()).orElseThrow();
        return detail(run, p.getFileName(), p.getAccountName(), p.getPeriodStart(), p.getPeriodEnd());
    }

    @SuppressWarnings("unchecked")
    static RefreshRunDetail detail(StatementRefreshRun run, String fileName, String accountName,
                                   java.time.LocalDate periodStart, java.time.LocalDate periodEnd) {
        Map<String, Object> d = run.getDetail() == null ? Map.of() : run.getDetail();
        List<Map<String, Object>> changed = list(d.get("changed"));
        List<Map<String, Object>> facts = list(d.get("facts"));
        Object reason = d.get("reason");
        return new RefreshRunDetail(run.getId(), run.getStatementImportId(), fileName, accountName, periodStart, periodEnd,
                run.getStatus().name(), run.getCreatedAt(), run.getRowsChanged(), run.getRowsAdded(),
                run.getRowsRemoved(), run.getFactsChanged(), run.getBalanceChange(),
                reason == null ? null : reason.toString(),
                changed.stream().map(m -> new RefreshChangedRow(uuid(m.get("transactionId")), str(m.get("date")),
                        str(m.get("description")), str(m.get("amount")), str(m.get("type")),
                        list(m.get("changes")).stream().map(StatementRefreshUserService::field).toList())).toList(),
                list(d.get("added")).stream().map(StatementRefreshUserService::row).toList(),
                list(d.get("removed")).stream().map(m -> new RefreshRemovedRow(uuid(m.get("transactionId")),
                        str(m.get("date")), str(m.get("description")), str(m.get("amount")), str(m.get("type")),
                        Boolean.TRUE.equals(m.get("userEdited")))).toList(),
                list(d.get("skippedAsDuplicate")).stream().map(StatementRefreshUserService::row).toList(),
                facts.stream().map(StatementRefreshUserService::field).toList());
    }

    private static RefreshRowView row(Map<String, Object> m) {
        return new RefreshRowView(uuid(m.get("transactionId")), str(m.get("date")), str(m.get("description")),
                str(m.get("amount")), str(m.get("type")));
    }

    private static RefreshFieldChange field(Map<String, Object> m) {
        return new RefreshFieldChange(str(m.get("field")), str(m.get("before")), str(m.get("after")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(o.toString());
    }
}
