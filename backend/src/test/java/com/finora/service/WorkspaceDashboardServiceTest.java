package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.Relationship;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Financial Intelligence Workspace, Module 1 (Dashboard). Mocked-repository unit tests, same
 *  pattern as AnalyticsServiceTest. */
class WorkspaceDashboardServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private MerchantRepository merchantRepository;
    private MerchantCategoryLearningRepository learningRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RelationshipRepository relationshipRepository;
    private StatementImportRepository statementImportRepository;
    private AuditLogRepository auditLogRepository;
    private UserRepository userRepository;
    private WorkspaceDashboardService service;

    private final UUID userId = UUID.randomUUID();
    private Account liveAccount;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        relationshipRepository = mock(RelationshipRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        userRepository = mock(UserRepository.class);
        service = new WorkspaceDashboardService(transactionRepository, accountRepository, merchantRepository,
                learningRepository, categoryRuleRepository, relationshipRepository, statementImportRepository,
                auditLogRepository, userRepository, new ConfidenceEngine());

        // Deleted-account leak (see DashboardService.summarize for the original fix): summarize()
        // scopes its transaction/statement queries to the user's live account ids, so the default
        // fixture needs at least one live account for every pre-existing test's transaction/
        // statement stubs to actually be reached.
        liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());
        when(learningRepository.findByUserId(userId)).thenReturn(List.of());
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)).thenReturn(List.of());
        when(relationshipRepository.findByUserId(userId)).thenReturn(List.of());
        when(statementImportRepository.countByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(0L);
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of());
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(any(), any())).thenReturn(List.of());
        // No timezone stubbed by default -> UserZone.forUser falls back to UserZone.DEFAULT
        // (Asia/Kolkata), same fallback convention as DashboardServiceTest.
        when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    private StatementImportRepository.StatementMetadata statementMetadataWithPeriod(LocalDate start, LocalDate end) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getId()).thenReturn(UUID.randomUUID());
        when(m.getStatementPeriodStart()).thenReturn(start);
        when(m.getStatementPeriodEnd()).thenReturn(end);
        return m;
    }

    private Transaction transaction(Transaction.ReconciliationStatus status, boolean manuallySet, boolean recurring) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setReconciliationStatus(status);
        t.setCategoryManuallySet(manuallySet);
        t.setRecurring(recurring);
        return t;
    }

    private Merchant merchant() {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setUserId(userId);
        m.setCanonicalName("Amazon");
        return m;
    }

    private MerchantCategoryLearning pair(UUID merchantId, int confidence) {
        MerchantCategoryLearning p = new MerchantCategoryLearning();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setMerchantId(merchantId);
        p.setCategoryId(UUID.randomUUID());
        p.setConfirmationCount(10);
        p.setConfidence(confidence);
        return p;
    }

    @Test
    void summarize_withNoTransactions_returnsNullCategorizationAccuracy_notDivideByZero() {
        var summary = service.summarize(userId);

        assertThat(summary.totalTransactions()).isZero();
        assertThat(summary.categorizationAccuracy()).isNull();
    }

    @Test
    void summarize_computesAutomationRate_asShareOfTransactionsNotManuallyCorrected() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, true, false)));

        var summary = service.summarize(userId);

        // 3 of 4 transactions were never manually corrected -> 75.0%
        assertThat(summary.categorizationAccuracy()).isEqualTo(75.0);
    }

    // Issue #1452: an ungated count for the Financial Memory page -- the only existing
    // corrections-trend data source (AnalyticsService.learningGrowth) is gated behind
    // FeatureEntitlement.ADVANCED_REPORTS, which this page (governed by "never monetize
    // completeness") must never depend on. Reuses the same categoryManuallySet count
    // automationRate already computes, exposed as its own field rather than only folded into a
    // percentage.
    @Test
    void summarize_countsManualCorrections_regardlessOfEntitlement() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.OK, false, false),
                transaction(Transaction.ReconciliationStatus.OK, true, false),
                transaction(Transaction.ReconciliationStatus.OK, true, false)));

        var summary = service.summarize(userId);

        assertThat(summary.totalManualCorrections()).isEqualTo(2);
    }

    @Test
    void summarize_withNoTransactions_totalManualCorrectionsIsZero_notNull() {
        // Unlike categorizationAccuracy (null when there's nothing to compute a rate over), a
        // correction COUNT has an honest zero even with no transactions at all -- no need for the
        // "nothing to compute over" null convention here.
        var summary = service.summarize(userId);

        assertThat(summary.totalManualCorrections()).isZero();
    }

    @Test
    void summarize_countsReconciliationStatusesIndependently() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false),
                transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false),
                transaction(Transaction.ReconciliationStatus.TRANSFER, false, false),
                transaction(Transaction.ReconciliationStatus.REFUND, false, false),
                transaction(Transaction.ReconciliationStatus.OK, false, true)));

        var summary = service.summarize(userId);

        assertThat(summary.duplicateMatches()).isEqualTo(2);
        assertThat(summary.transferMatches()).isEqualTo(1);
        assertThat(summary.refundMatches()).isEqualTo(1);
        assertThat(summary.recurringTransactions()).isEqualTo(1);
    }

    @Test
    void summarize_bucketsMerchantsByTopCategoryConfidence_usingMerchantsTsxsExistingThresholds() {
        Merchant highConfidence = merchant();
        Merchant mediumConfidence = merchant();
        Merchant lowConfidence = merchant();
        Merchant unconfirmed = merchant();
        when(merchantRepository.findByUserId(userId)).thenReturn(
                List.of(highConfidence, mediumConfidence, lowConfidence, unconfirmed));
        when(learningRepository.findByUserId(userId)).thenReturn(List.of(
                pair(highConfidence.getId(), 95),
                pair(mediumConfidence.getId(), 75),
                pair(lowConfidence.getId(), 40)));

        var summary = service.summarize(userId);

        assertThat(summary.confidenceDistribution())
                .containsEntry("HIGH", 1L)
                .containsEntry("MEDIUM", 1L)
                .containsEntry("LOW", 1L)
                .containsEntry("UNCONFIRMED", 1L);
        assertThat(summary.totalMerchants()).isEqualTo(4);
        assertThat(summary.learnedMerchants()).isEqualTo(3); // unconfirmed has no learning pair at all
    }

    @Test
    void summarize_recentActivity_reusesTheTop5AuditLogQuery_notTheUnboundedOne() {
        AuditLog log = new AuditLog();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        log.setUserId(userId);
        log.setAction("RULE_CREATED");
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(log));

        var summary = service.summarize(userId);

        assertThat(summary.recentActivity()).hasSize(1);
        assertThat(summary.recentActivity().get(0).action()).isEqualTo("RULE_CREATED");
    }

    @Test
    void summarize_activeRulesOnlyCountsEnabledOnes() {
        CategoryRule enabled = new CategoryRule();
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId)).thenReturn(List.of(enabled));

        var summary = service.summarize(userId);

        assertThat(summary.activeRules()).isEqualTo(1);
    }

    @Test
    void summarize_countsAccountsRelationshipsAndStatementImports() {
        Account account1 = new Account();
        ReflectionTestUtils.setField(account1, "id", UUID.randomUUID());
        Account account2 = new Account();
        ReflectionTestUtils.setField(account2, "id", UUID.randomUUID());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account1, account2));
        when(relationshipRepository.findByUserId(userId)).thenReturn(List.of(new Relationship()));
        when(statementImportRepository.countByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(3L);

        var summary = service.summarize(userId);

        assertThat(summary.totalAccounts()).isEqualTo(2);
        assertThat(summary.relationships()).isEqualTo(1);
        assertThat(summary.statementsImported()).isEqualTo(3);
    }

    // --- Financial Memory Completeness (issue #1450) ---

    @Test
    void summarize_computesFinancialMemoryCompleteness_fromTheLiveAccountsOwnStatementPeriods() {
        // No timezone stubbed -> the service resolves "today" against UserZone.DEFAULT
        // (Asia/Kolkata) -- match that here rather than the bare, JVM-zone LocalDate.now(), or
        // this test would be flaky depending on which zone happens to run it.
        LocalDate today = LocalDate.now(UserZone.DEFAULT);
        LocalDate start = today.minusMonths(3);
        var metadata = statementMetadataWithPeriod(start, today);
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, liveAccount.getId()))
                .thenReturn(List.of(metadata));

        var summary = service.summarize(userId);

        // Period end == today -> zero freshness gap, single segment -> zero internal gap ->
        // 100% complete. FinancialMemoryCompletenessTest owns the formula's arithmetic in
        // detail; this test only proves the wiring reaches WorkspaceSummaryDto.
        assertThat(summary.completenessPercent()).isEqualTo(100);
        long expectedMonths = java.time.temporal.ChronoUnit.MONTHS.between(
                java.time.YearMonth.from(start), java.time.YearMonth.from(today)) + 1;
        assertThat(summary.monthsOfHistory()).isEqualTo(expectedMonths);
    }

    @Test
    void summarize_withNoStatementPeriodsOnAnyLiveAccount_leavesCompletenessNull() {
        var summary = service.summarize(userId);

        assertThat(summary.completenessPercent()).isNull();
        assertThat(summary.monthsOfHistory()).isNull();
    }

    @Test
    void summarize_financialMemoryCompleteness_isScopedToLiveAccountsOnly_sameAsEveryOtherTile() {
        // A statement on a since-deleted account must not inflate a still-live account's
        // completeness picture -- same live-account scoping discipline as
        // summarize_scopesTransactionAndStatementQueries_toLiveAccountIdsOnly above.
        UUID deletedAccountId = UUID.randomUUID();
        var deletedAccountMetadata = statementMetadataWithPeriod(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31));
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(eq(userId), eq(deletedAccountId)))
                .thenReturn(List.of(deletedAccountMetadata));

        var summary = service.summarize(userId);

        org.mockito.Mockito.verify(statementImportRepository, org.mockito.Mockito.never())
                .findMetadataWithPeriodByUserIdAndAccountId(userId, deletedAccountId);
        assertThat(summary.completenessPercent()).isNull();
    }

    // Bug fix: this used to resolve "today" via a bare LocalDate.now() -- the server's JVM
    // timezone, not the user's own -- the exact bug class NetWorthService/DashboardService
    // already hit and fixed for the same underlying reason (see UserZone's own class doc, which
    // names this as recurring). A freshness gap or months-of-history figure computed against the
    // wrong calendar day, for a user meaningfully east or west of wherever the server runs, is
    // exactly the kind of quietly-wrong number this page exists to be honest about.
    @Test
    void summarize_resolvesFinancialMemoryCompletenessTodayThroughUserZone_notTheServersBareLocalDateNow() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setTimezone("America/Los_Angeles");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.summarize(userId);

        // The only way this call happens at all is inside financialMemoryCompleteness() --
        // summarize() had zero reason to touch userRepository before this feature existed.
        org.mockito.Mockito.verify(userRepository).findById(userId);
    }

    // Deleted-account leak (see DashboardService.summarize for the original fix): summarize()
    // must scope its transaction/statement queries to exactly the live account ids, not just
    // userId.
    @Test
    void summarize_scopesTransactionAndStatementQueries_toLiveAccountIdsOnly() {
        service.summarize(userId);

        org.mockito.Mockito.verify(transactionRepository)
                .findByUserIdAndAccountIdIn(userId, List.of(liveAccount.getId()));
        org.mockito.Mockito.verify(statementImportRepository)
                .countByUserIdAndAccountIdIn(userId, List.of(liveAccount.getId()));
    }

    @Test
    void summarize_withNoLiveAccounts_shortCircuitsToZero_withoutQueryingTransactionsOrStatements() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        var summary = service.summarize(userId);

        assertThat(summary.totalAccounts()).isZero();
        assertThat(summary.statementsImported()).isZero();
        assertThat(summary.totalTransactions()).isZero();
        org.mockito.Mockito.verifyNoInteractions(transactionRepository);
        org.mockito.Mockito.verify(statementImportRepository, org.mockito.Mockito.never())
                .countByUserIdAndAccountIdIn(any(), any());
    }

    // --- Workspace Health ---

    @Test
    void health_rulesEnabledAndMerchantLearningActive_reflectRealCounts() {
        when(categoryRuleRepository.findByUserIdAndEnabledTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(new CategoryRule()));
        Merchant m = merchant();
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(m));
        when(learningRepository.findByUserId(userId)).thenReturn(List.of(pair(m.getId(), 90)));

        var health = service.summarize(userId).health();

        assertThat(health.rulesEnabled()).isTrue();
        assertThat(health.merchantLearningActive()).isTrue();
    }

    @Test
    void health_noRulesOrLearning_bothFalse() {
        var health = service.summarize(userId).health();

        assertThat(health.rulesEnabled()).isFalse();
        assertThat(health.merchantLearningActive()).isFalse();
    }

    @Test
    void health_reconciliationHealthy_whenEveryPointerResolvesToARealTransaction() {
        Transaction original = transaction(Transaction.ReconciliationStatus.OK, false, false);
        Transaction duplicate = transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false);
        duplicate.setIsDuplicateOf(original.getId());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(original, duplicate));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isTrue();
    }

    @Test
    void health_reconciliationUnhealthy_whenADuplicatePointerDanglesAtADeletedTransaction() {
        // Regression test for exactly the bug class found and fixed this session --
        // TransactionService.clearReconciliationPointersTo()/StatementImportService.delete()
        // reset these pointers on delete specifically so this can never happen; this check exists
        // to catch it live if that ever regresses, or a future write path introduces a new gap.
        Transaction duplicate = transaction(Transaction.ReconciliationStatus.DUPLICATE, false, false);
        duplicate.setIsDuplicateOf(UUID.randomUUID()); // points at a transaction that isn't in the list at all
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(duplicate));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isFalse();
    }

    @Test
    void health_reconciliationUnhealthy_whenARefundPointerDangles() {
        Transaction refundIncome = transaction(Transaction.ReconciliationStatus.REFUND, false, false);
        refundIncome.setRefundOfTransactionId(UUID.randomUUID());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(refundIncome));

        var health = service.summarize(userId).health();

        assertThat(health.reconciliationHealthy()).isFalse();
    }

    @Test
    void health_auditLoggingHealthy_whenThereIsNothingToHaveLoggedYet() {
        // Brand-new user, no transactions, no rules -- an empty activity feed here is expected,
        // not suspicious, so this must read healthy rather than false-alarm.
        var health = service.summarize(userId).health();

        assertThat(health.auditLoggingHealthy()).isTrue();
    }

    @Test
    void health_auditLoggingUnhealthy_whenThereIsRealDataButNoActivityEverLogged() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any()))
                .thenReturn(List.of(transaction(Transaction.ReconciliationStatus.OK, false, false)));
        when(auditLogRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        var health = service.summarize(userId).health();

        assertThat(health.auditLoggingHealthy()).isFalse();
    }

    @Test
    void health_recurringDetectionHealthy_isADocumentedPlaceholder_alwaysTrueForNow() {
        var health = service.summarize(userId).health();

        assertThat(health.recurringDetectionHealthy()).isTrue();
    }
}
