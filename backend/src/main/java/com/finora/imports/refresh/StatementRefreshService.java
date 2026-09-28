package com.finora.imports.refresh;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementImportExcludedRow;
import com.finora.entity.StatementRefreshRun;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.imports.refresh.StatementRefreshDiff.FieldChange;
import com.finora.imports.refresh.StatementRefreshDiff.FreshRow;
import com.finora.imports.refresh.StatementRefreshDiff.KnownRow;
import com.finora.imports.storage.StatementContentService;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportExcludedRowRepository;
import com.finora.repository.StatementRefreshRunRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.CategorizationService;
import com.finora.util.CategoryRules;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import com.finora.transactions.TransactionService;
import com.finora.util.EnumParsing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Statement refresh, step 3: re-reads one stored statement with today's parser and patches its
 * transactions in place. Transaction ids survive -- notes, tags, reconciliation links, budgets and
 * the user's own decisions hang off them -- so nothing is deleted and re-imported.
 *
 * <p>Per statement, as {@link StatementRefreshDiff} decides:
 * <ul>
 *   <li>a row read differently is corrected, except for any field the user edited by hand;</li>
 *   <li>a row an older parser missed is added, unless the user left it out at import or deleted it;</li>
 *   <li>a row the statement no longer contains is removed and listed in the summary -- also when the
 *       user had edited it (Sid, 2026-09-27: "delete it and list it in the summary");</li>
 *   <li>the statement's own facts (period, balances, total due, due date) are updated.</li>
 * </ul>
 * Every row change moves the account balance the way the matching user action would
 * ({@link TransactionService#correctFromStatement} and siblings). Reconciliation and recurring
 * detection then run once for the user, and the outcome is recorded as a {@link StatementRefreshRun}.
 *
 * <p>Nothing is applied when the re-read cannot be trusted: the checks are the dry run's
 * ({@link StatementRefreshInputs#refusal}, {@link StatementRefreshInputs#removesTooMuch}).
 *
 * <p>Three phases, as in the dry run: the stored file is read in a short read-only transaction;
 * parsing runs outside any transaction; then one write transaction locks the statement, compares
 * against its rows as they are at that moment, and applies. A statement deleted, replaced or
 * already refreshed while its file was being parsed is found out under the lock, never patched
 * from a stale comparison.
 */
@Service
public class StatementRefreshService {

    private static final Logger log = LoggerFactory.getLogger(StatementRefreshService.class);

    private final StatementImportRepository statementImportRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementRefreshRunRepository runRepository;
    private final StatementImportExcludedRowRepository excludedRowRepository;
    private final StatementRefreshInputs inputs;
    private final StatementContentService statementContentService;
    private final ImportService importService;
    private final TransactionService transactionService;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final RecurringService recurringService;
    private final AuditService auditService;
    private final BuildVersionResolver buildVersionResolver;
    private final StatementPasswordService statementPasswordService;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    @Value("${app.statement-refresh.apply.enabled:false}")
    private boolean enabled;

    public StatementRefreshService(StatementImportRepository statementImportRepository,
                                   AccountRepository accountRepository,
                                   TransactionRepository transactionRepository,
                                   StatementRefreshRunRepository runRepository,
                                   StatementImportExcludedRowRepository excludedRowRepository,
                                   StatementRefreshInputs inputs,
                                   StatementContentService statementContentService,
                                   ImportService importService,
                                   TransactionService transactionService,
                                   CategorizationService categorizationService,
                                   ReconciliationService reconciliationService,
                                   RecurringService recurringService,
                                   AuditService auditService,
                                   BuildVersionResolver buildVersionResolver,
                                   PlatformTransactionManager transactionManager,
                                   StatementPasswordService statementPasswordService) {
        this.statementPasswordService = statementPasswordService;
        this.statementImportRepository = statementImportRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.runRepository = runRepository;
        this.excludedRowRepository = excludedRowRepository;
        this.inputs = inputs;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.transactionService = transactionService;
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.auditService = auditService;
        this.buildVersionResolver = buildVersionResolver;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** Refreshes one of the user's statements without saving a password -- see the four-argument form. */
    public StatementRefreshOutcome refresh(UUID userId, UUID statementId, String password) {
        return refresh(userId, statementId, password, false);
    }

    /**
     * Refreshes one of the user's statements. A protected PDF is opened with {@code password} when
     * one is given, otherwise with the password saved for it (step 4), if any. With
     * {@code savePassword} -- the user's consent -- a given password that opened the file is saved
     * encrypted for next time; without it the password is used for this re-read only. Never logged.
     */
    public StatementRefreshOutcome refresh(UUID userId, UUID statementId, String password, boolean savePassword) {
        requireEnabled();
        if (savePassword && !statementPasswordService.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "Saving statement passwords is not available yet.");
        }
        StatementImport statement = statementImportRepository.findById(statementId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Statement not found"));
        if (statement.getSupersededBy() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This statement was replaced by a newer upload, so there is nothing to refresh.");
        }
        if (statementImportRepository.isGmailReceipt(statementId)) {
            // Its stored "file" is a provenance marker, not a document: nothing to re-read.
            throw new ApiException(HttpStatus.CONFLICT, "This came from an email receipt, so there is no statement to re-read.");
        }
        if (accountRepository.findById(statement.getAccountId()).isEmpty()) {
            // Same rule as the dry run: a deleted account's statements are never offered or patched.
            throw new ApiException(HttpStatus.CONFLICT, "This statement's account was deleted.");
        }
        String version = buildVersionResolver.currentCommit();

        byte[] content;
        try {
            content = readTransaction.execute(tx -> statementContentService.read(
                    statementImportRepository.findById(statementId).orElseThrow()));
        } catch (RuntimeException e) {
            return StatementRefreshOutcome.of(record(statement, version, StatementRefreshRun.Status.FAILED, "STORED_FILE_UNREADABLE"));
        }

        boolean givenPassword = password != null && !password.isEmpty();
        String opener = givenPassword ? password : statementPasswordService.forStatement(userId, statementId).orElse(null);
        StagingResponse staging;
        try {
            staging = importService.parseAndStageAnyFormat(userId, statement.getSourceFormat(),
                    statement.getFileName(), content, statement.getSourceSectionIndex(), opener);
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED || e.getCode() == ErrorCode.IMPORT_PDF_PASSWORD_INVALID) {
                // Not recorded as a run: nothing was attempted yet, and the user is about to be
                // asked for the password.
                return new StatementRefreshOutcome(statementId, StatementRefreshRun.Status.NEEDS_PASSWORD, 0, 0, 0, 0, null,
                        e.getCode().name(), null);
            }
            return StatementRefreshOutcome.of(record(statement, version, StatementRefreshRun.Status.FAILED,
                    e.getCode() == null ? "PARSE_REJECTED" : e.getCode().name()));
        } catch (Exception e) {
            // The class name only: a parser's message can quote the statement's own text.
            log.warn("Statement refresh could not re-read statement {}: {}", statementId, e.getClass().getSimpleName());
            return StatementRefreshOutcome.of(record(statement, version, StatementRefreshRun.Status.FAILED, e.getClass().getSimpleName()));
        }

        // The file opened, so the password is right. Saved whatever the refresh then decides: the
        // user agreed to keep it for this statement, not for this outcome. Only a locked PDF has one
        // to keep -- PDFBox ignores a password given for an unlocked file, so it would open anyway.
        if (savePassword && givenPassword && "PDF".equalsIgnoreCase(statement.getSourceFormat())
                && com.finora.imports.pdf.PdfTextExtractor.needsPassword(new java.io.ByteArrayInputStream(content))) {
            statementPasswordService.saveForStatement(userId, statementId, password);
        }

        StatementRefreshRun run = writeTransaction.execute(tx -> apply(userId, statementId, version, staging));
        return StatementRefreshOutcome.of(run);
    }

    /** The refresh proper, under the statement's lock. */
    private StatementRefreshRun apply(UUID userId, UUID statementId, String version, StagingResponse staging) {
        StatementImport statement = statementImportRepository.findByIdForUpdate(statementId).orElse(null);
        if (statement == null || statement.getSupersededBy() != null) {
            // Deleted or replaced while its file was being read: nothing left to patch, and a run
            // for a deleted statement would outlive the narrations the user asked to delete.
            throw new ApiException(HttpStatus.CONFLICT,
                    "This statement was deleted or replaced while it was being refreshed.");
        }

        List<KnownRow> known = inputs.knownRows(statement);
        StatementRefreshInputs.Fresh fresh = StatementRefreshInputs.freshRows(staging);
        String refusal = inputs.refusal(statement, staging.detectedAccount(), known, fresh.rows());
        if (refusal != null) {
            return save(new RunBuilder(statement, version, StatementRefreshRun.Status.FAILED).reason(refusal));
        }

        StatementRefreshDiff.Result diff = StatementRefreshDiff.compute(known, fresh.rows());
        List<StatementRefreshInputs.FactChange> facts =
                StatementRefreshInputs.factChanges(statement, staging.detectedAccount());
        if (StatementRefreshInputs.removesTooMuch(diff, StatementRefreshInputs.liveRows(known))) {
            RunBuilder held = new RunBuilder(statement, version, StatementRefreshRun.Status.NEEDS_REVIEW);
            held.counts(diff, facts);
            return save(held);
        }
        if (!diff.hasChanges() && facts.isEmpty()) {
            stamp(statement, version);
            return save(new RunBuilder(statement, version, StatementRefreshRun.Status.NO_CHANGES));
        }

        BigDecimal balanceBefore = accountBalance(statement.getAccountId());
        RunBuilder run = new RunBuilder(statement, version, StatementRefreshRun.Status.APPLIED);
        run.counts(diff, facts);

        // Links decided against a misread amount, type or date are undone first; the reconciliation
        // at the end decides them again from the corrected values.
        transactionService.releaseReconciliationForRefresh(userId, diff.changed().stream()
                .filter(c -> c.changes().stream().anyMatch(f -> f.field() == StatementRefreshDiff.Field.AMOUNT
                        || f.field() == StatementRefreshDiff.Field.TYPE || f.field() == StatementRefreshDiff.Field.DATE))
                .map(StatementRefreshDiff.Changed::transactionId)
                .toList());
        for (StatementRefreshDiff.Changed changed : diff.changed()) {
            Transaction t = transactionRepository.findById(changed.transactionId()).orElse(null);
            if (t == null) continue;
            StagedRow staged = fresh.staged().get(changed.fresh());
            Transaction saved = transactionService.correctFromStatement(t,
                    row -> applyCorrections(userId, row, changed.changes(), changed.fresh(), staged));
            run.changed(saved, changed.changes());
        }

        int nextOrdinal = nextRowOrdinal(statement);
        List<com.finora.entity.CategoryRule> rules = categorizationService.ruleSetFor(userId);
        Transaction.Source source = importSourceOf(statement);
        Set<UUID> ownRows = new java.util.HashSet<>();
        for (KnownRow k : known) if (k.transactionId() != null) ownRows.add(k.transactionId());
        int inserted = 0;
        for (FreshRow added : diff.added()) {
            StagedRow staged = fresh.staged().get(added);
            if (duplicatesAnotherStatement(staged, ownRows)) {
                // What an import does by default: a row matching a transaction the user already has
                // from another statement (overlapping periods) is left out, not counted twice. Kept
                // as a left-out row, so the next refresh recognises it instead of offering it again.
                excludedRowRepository.save(new StatementImportExcludedRow(statement.getId(), userId,
                        staged.rowPosition(), staged.date(), staged.description(), staged.amount(), staged.type(), true));
                run.skippedAsDuplicate(staged);
                continue;
            }
            Transaction t = newRow(userId, statement, staged, source, rules);
            t.setRowOrdinal(nextOrdinal++);
            run.added(transactionService.insertFromStatement(t, statement));
            inserted++;
        }
        run.addedCount(inserted);

        List<KnownRow> gone = new ArrayList<>(diff.removed());
        gone.addAll(diff.conflicts());
        // Read before removal: the summary must say which removed rows carried the user's own work
        // (an edited field, a category they chose, notes, tags), because that work goes with them.
        Map<UUID, Transaction> goneRows = new java.util.HashMap<>();
        transactionRepository.findAllById(gone.stream().map(KnownRow::transactionId).toList())
                .forEach(t -> goneRows.put(t.getId(), t));
        transactionService.removeFromStatement(userId, gone.stream().map(KnownRow::transactionId).toList());
        for (KnownRow k : gone) run.removed(k, carriesUserWork(k, goneRows.get(k.transactionId())));

        // Rows on this statement now: what its history shows as imported.
        statement.setTransactionsImported(Math.max(0,
                statement.getTransactionsImported() + inserted - gone.size()));
        applyFacts(statement, facts, run);
        stamp(statement, version);

        // The same order every write path uses: rows first, then settle transfers, refunds,
        // duplicates and card payments against them, then recurring.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);

        BigDecimal balanceAfter = accountBalance(statement.getAccountId());
        if (balanceBefore != null && balanceAfter != null && balanceAfter.compareTo(balanceBefore) != 0) {
            run.balanceChange(balanceAfter.subtract(balanceBefore));
        }
        StatementRefreshRun saved = save(run);
        auditService.record(userId, "STATEMENT_REFRESHED", "StatementImport", statementId, Map.of(
                "parserVersion", String.valueOf(version),
                "rowsChanged", saved.getRowsChanged(), "rowsAdded", saved.getRowsAdded(),
                "rowsRemoved", saved.getRowsRemoved(), "factsChanged", saved.getFactsChanged()));
        return saved;
    }

    /** The corrected fields, onto the row. Only fields the diff reported -- it already left out any the user edited. */
    private void applyCorrections(UUID userId, Transaction t, List<FieldChange> changes, FreshRow fresh, StagedRow staged) {
        Set<Transaction.EditableField> edited = t.getUserEditedFields();
        boolean recategorize = false;
        for (FieldChange change : changes) {
            switch (change.field()) {
                case DATE -> t.setTxnDate(fresh.date());
                case AMOUNT -> {
                    t.setAmount(fresh.amount());
                    recategorize = true;
                }
                case TYPE -> {
                    t.setTxnType(EnumParsing.parse(Transaction.Type.class, fresh.type(), "type"));
                    recategorize = true;
                }
                case BALANCE_AFTER -> t.setBalanceAfter(fresh.balanceAfter());
                case REFERENCE_NUMBER -> t.setReferenceNumber(fresh.referenceNumber());
                case DESCRIPTION -> {
                    t.setDescription(fresh.description());
                    t.applyCounterpartyTyping(fresh.description());
                    if (!edited.contains(Transaction.EditableField.MERCHANT)) {
                        t.setMerchant(CategoryRules.extractMerchantLabel(fresh.description()));
                        t.setMerchantId(categorizationService.resolveMerchantId(userId, fresh.description()));
                    }
                    recategorize = true;
                }
            }
        }
        // The category and its review flag were decided from the old reading; a corrected
        // narration, amount or type is decided again from the new one, as a clean import of the
        // file would -- unless the user chose this row's category themselves.
        if (recategorize && !t.isCategoryManuallySet() && staged != null) applyCategoryDecision(userId, t, staged);
    }

    /** Category, decision record and review flag for a row read from {@code staged} and not reviewed by the user. */
    private void applyCategoryDecision(UUID userId, Transaction t, StagedRow staged) {
        String name = staged.suggestedCategory() == null || staged.suggestedCategory().isBlank()
                ? "Other" : staged.suggestedCategory();
        Category category = categorizationService.resolveOrCreateCategory(userId, name);
        t.setCategoryId(category.getId());
        t.setDecisionSource(CategorizationService.decisionSourceFor(staged.categorySource()));
        t.setDecisionRuleId(staged.ruleId());
        t.setDecisionConfidence(staged.categoryConfidence());
        t.setNeedsCategoryReview(categorizationService.needsCategoryReview(userId, unresolvedGuess(staged),
                staged.categoryConfidence()));
    }

    /**
     * The import's own rule ({@code RuleLearningService.recordDecision}) for a row the user did not
     * review: a corpus or AI suggestion is still a guess, and so is a default or unconfirmed source.
     */
    private static boolean unresolvedGuess(StagedRow staged) {
        String source = staged.categorySource();
        if (CategorizationService.SHARED_CORPUS_SOURCE.equals(source)
                || CategorizationService.AI_FALLBACK_SOURCE.equals(source)) {
            return true;
        }
        return CategorizationService.isUnconfirmedGuess(source, staged.suggestedCategory());
    }

    /**
     * A row an older parser missed, built the way a confirmed import builds one, minus what only a
     * user's review decides: nothing is learned from it (the user never saw it), and it is not a
     * "not a duplicate" decision.
     */
    private Transaction newRow(UUID userId, StatementImport statement, StagedRow row, Transaction.Source source,
                               List<com.finora.entity.CategoryRule> rules) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(statement.getAccountId());
        t.setStatementImportId(statement.getId());
        applyCategoryDecision(userId, t, row);
        t.setMerchantId(categorizationService.resolveMerchantId(userId, row.description()));
        t.applyCounterpartyTyping(row.description());
        t.setTxnDate(row.date());
        t.setDescription(row.description());
        t.setMerchant(CategoryRules.extractMerchantLabel(row.description()));
        t.setAmount(row.amount());
        t.setTxnType(EnumParsing.parse(Transaction.Type.class, row.type(), "type"));
        t.setSource(source);
        t.setReferenceNumber(row.referenceNumber());
        t.setBalanceAfter(row.balanceAfter());
        t.setSourceRowPosition(row.rowPosition());
        t.setInternational(row.international());
        t.setForeignAmount(row.foreignCurrency(), row.foreignAmount());
        Category sideEffect = categorizationService.applySideEffectRules(userId, t, rules);
        if (sideEffect != null) t.setCategoryId(sideEffect.getId());
        return t;
    }

    /**
     * The statement's facts as read now. A closing balance that set the account's balance, and still
     * does (this statement is the account's latest absolute set), moves the balance by the correction:
     * the bank's figure is what the balance was set to.
     */
    private void applyFacts(StatementImport statement, List<StatementRefreshInputs.FactChange> facts, RunBuilder run) {
        for (StatementRefreshInputs.FactChange fact : facts) {
            switch (fact.field()) {
                case "STATEMENT_PERIOD_START" -> statement.setStatementPeriodStart((LocalDate) fact.after());
                case "STATEMENT_PERIOD_END" -> statement.setStatementPeriodEnd((LocalDate) fact.after());
                case "OPENING_BALANCE" -> statement.setOpeningBalance((BigDecimal) fact.after());
                case "TOTAL_AMOUNT_DUE" -> statement.setTotalAmountDue((BigDecimal) fact.after());
                case "PAYMENT_DUE_DATE" -> statement.setPaymentDueDate((LocalDate) fact.after());
                case "CLOSING_BALANCE" -> {
                    BigDecimal before = statement.getClosingBalance();
                    BigDecimal after = (BigDecimal) fact.after();
                    statement.setClosingBalance(after);
                    if (before != null && statement.getBalanceApplicationMode() == StatementImport.BalanceApplicationMode.ABSOLUTE) {
                        accountRepository.findById(statement.getAccountId())
                                .filter(a -> statement.getId().equals(a.getLastAbsoluteSetStatementId()))
                                .ifPresent(a -> {
                                    a.setBalance(a.getBalance().add(after.subtract(before)));
                                    accountRepository.save(a);
                                });
                    }
                }
                default -> { }
            }
            run.fact(fact);
        }
        statementImportRepository.save(statement);
    }

    /** Recorded as read by this build, so the dry run no longer offers it and a refresh is not repeated. */
    private void stamp(StatementImport statement, String version) {
        if (version != null) statement.setParserVersion(version);
        statementImportRepository.save(statement);
        statementImportRepository.deleteRefreshPreviewsOfStatement(statement.getUserId(), statement.getId());
    }

    private static boolean carriesUserWork(KnownRow k, Transaction t) {
        if (k.userEdited() != null && !k.userEdited().isEmpty()) return true;
        if (t == null) return false;
        return t.isCategoryManuallySet()
                || (t.getNotes() != null && !t.getNotes().isBlank())
                || (t.getTags() != null && !t.getTags().isEmpty());
    }

    /** A likely duplicate of a transaction that is not one of this statement's own rows. */
    private static boolean duplicatesAnotherStatement(StagedRow staged, Set<UUID> ownRows) {
        if (staged == null || !staged.likelyDuplicate()) return false;
        UUID match = staged.duplicateMatch() == null ? null : staged.duplicateMatch().existingTransactionId();
        return match == null || !ownRows.contains(match);
    }

    /** After every ordinal the statement has used, deleted rows included: (statement, ordinal) is unique (V67). */
    private int nextRowOrdinal(StatementImport statement) {
        return transactionRepository.findStatementRowsIncludingDeleted(statement.getUserId(), statement.getId()).stream()
                .map(TransactionRepository.StatementRowView::getRowOrdinal)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .map(max -> max + 1)
                .orElse(0);
    }

    /** The source the statement's own rows carry, so an added row reads as one of them. */
    private Transaction.Source importSourceOf(StatementImport statement) {
        return transactionRepository.findByStatementImportId(statement.getId()).stream()
                .map(Transaction::getSource)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Transaction.Source.CSV_IMPORT);
    }

    private BigDecimal accountBalance(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::getBalance).orElse(null);
    }

    /** A run for a refresh that stopped before the write transaction. */
    private StatementRefreshRun record(StatementImport statement, String version, StatementRefreshRun.Status status,
                                       String reason) {
        return writeTransaction.execute(tx -> {
            if (statementImportRepository.findById(statement.getId()).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "This statement was deleted while it was being refreshed.");
            }
            return save(new RunBuilder(statement, version, status).reason(reason));
        });
    }

    private StatementRefreshRun save(RunBuilder builder) {
        return runRepository.save(builder.build());
    }

    /** Whether applying a refresh is switched on ({@code app.statement-refresh.apply.enabled}). */
    public boolean enabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
    }

    /** Collects what a refresh did, for the run's detail -- the "what changed" summary. */
    private static final class RunBuilder {
        private final StatementRefreshRun run;
        private final List<Map<String, Object>> changed = new ArrayList<>();
        private final List<Map<String, Object>> added = new ArrayList<>();
        private final List<Map<String, Object>> removed = new ArrayList<>();
        private final List<Map<String, Object>> facts = new ArrayList<>();
        private final List<Map<String, Object>> skipped = new ArrayList<>();
        private String reason;

        RunBuilder(StatementImport statement, String version, StatementRefreshRun.Status status) {
            this.run = new StatementRefreshRun(statement.getId(), statement.getUserId(), version, status);
        }

        RunBuilder reason(String reason) { this.reason = reason; return this; }

        void counts(StatementRefreshDiff.Result diff, List<StatementRefreshInputs.FactChange> factChanges) {
            run.setCounts(diff.changed().size(), diff.added().size(),
                    diff.removed().size() + diff.conflicts().size(), factChanges.size());
        }

        void balanceChange(BigDecimal change) { run.setBalanceChange(change); }

        void addedCount(int inserted) {
            run.setCounts(run.getRowsChanged(), inserted, run.getRowsRemoved(), run.getFactsChanged());
        }

        void changed(Transaction t, List<FieldChange> changes) {
            Map<String, Object> m = row(t);
            m.put("changes", changes.stream().map(fc -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("field", fc.field().name());
                f.put("before", fc.before());
                f.put("after", fc.after());
                return f;
            }).toList());
            changed.add(m);
        }

        void added(Transaction t) { added.add(row(t)); }

        void skippedAsDuplicate(StagedRow r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.date() == null ? null : r.date().toString());
            m.put("description", r.description());
            m.put("amount", r.amount() == null ? null : r.amount().toPlainString());
            m.put("type", r.type());
            skipped.add(m);
        }

        void removed(KnownRow k, boolean userEdited) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transactionId", k.transactionId().toString());
            m.put("date", k.date() == null ? null : k.date().toString());
            m.put("description", k.description());
            m.put("amount", k.amount() == null ? null : k.amount().toPlainString());
            m.put("type", k.type());
            m.put("userEdited", userEdited);
            removed.add(m);
        }

        void fact(StatementRefreshInputs.FactChange f) { facts.add(StatementRefreshInputs.factJson(f)); }

        private static Map<String, Object> row(Transaction t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transactionId", t.getId().toString());
            m.put("date", t.getTxnDate() == null ? null : t.getTxnDate().toString());
            m.put("description", t.getDescription());
            m.put("amount", t.getAmount() == null ? null : t.getAmount().toPlainString());
            m.put("type", t.getTxnType() == null ? null : t.getTxnType().name());
            return m;
        }

        StatementRefreshRun build() {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (reason != null) detail.put("reason", reason);
            if (!changed.isEmpty()) detail.put("changed", changed);
            if (!added.isEmpty()) detail.put("added", added);
            if (!removed.isEmpty()) detail.put("removed", removed);
            if (!skipped.isEmpty()) detail.put("skippedAsDuplicate", skipped);
            if (!facts.isEmpty()) detail.put("facts", facts);
            run.setDetail(detail.isEmpty() ? null : detail);
            return run;
        }
    }
}
