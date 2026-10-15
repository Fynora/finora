package com.finora.service;

import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;

import com.finora.dto.ReportDto;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private InflowKindRepository inflowKindRepository;
    private SenderInflowRuleRepository senderInflowRuleRepository;
    private AccountCoverageService accountCoverageService;
    private ReportService reportService;
    private final UUID userId = UUID.randomUUID();
    private Account liveAccount;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        inflowKindRepository = mock(InflowKindRepository.class);
        senderInflowRuleRepository = mock(SenderInflowRuleRepository.class);
        accountCoverageService = mock(AccountCoverageService.class);
        when(accountCoverageService.gapsForUser(any())).thenReturn(List.of());
        reportService = new ReportService(transactionRepository, accountRepository, categoryRepository, transactionGraphService,
                new InflowChoiceService(inflowKindRepository, senderInflowRuleRepository), accountCoverageService,
                new StatementReachService(accountCoverageService, accountRepository, transactionRepository));
    }

    private Transaction txn(BigDecimal amount, Transaction.Type type, Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(amount);
        t.setTxnType(type);
        t.setTxnDate(LocalDate.of(2026, 7, 15));
        t.setReconciliationStatus(status);
        return t;
    }

    @Test
    void forMonth_excludesRefundStatusIncomeFromTheIncomeTotal() {
        // A REFUND-status transaction is the INCOME leg of a reconciled refund (see
        // ReconciliationService's refund pass) -- money coming back from a return isn't real
        // income and must not inflate this report's income total.
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        Transaction refund = txn(new BigDecimal("999.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.REFUND);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, refund));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00"); // refund's 999 excluded
    }

    @Test
    void forMonth_excludesInvestmentTransferFromTheExpenseTotal_butKeepsItInTheCategoryBreakdown() {
        // Top-line-only exclusion (RefundNetting.excludingInvestmentTransfers): a SIP is not real
        // spend and must not inflate the month's expense total, but it's still a real, budgetable
        // category -- it must not vanish from the report's own category table.
        Transaction groceries = txn(new BigDecimal("2000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        Transaction sip = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(groceries, sip));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.expense()).isEqualByComparingTo("2000.00");
        BigDecimal categoryTotal = report.categories().stream()
                .map(ReportDto.CategoryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(categoryTotal).isEqualByComparingTo("5000.00");
    }

    @Test
    void forMonth_stillIncludesOrdinaryIncome() {
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00");
    }

    // --- Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so forMonth/availableMonths must
    // scope their transaction fetches to exactly the user's live account ids, not just their
    // userId. ---

    @Test
    void forMonth_scopesTransactionFetch_toExactlyTheLiveAccountIds() {
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(any(), any(), any()))
                .thenReturn(List.of());

        reportService.forMonth(userId, "2026-07");

        // Two fetches now: the selected month, and the month before it for the comparison. Both
        // must be scoped to the live accounts -- the prior month is exactly as exposed to the
        // deleted-account leak as the selected one.
        verify(transactionRepository).findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)), eq(List.of(liveAccount.getId())));
        verify(transactionRepository).findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(List.of(liveAccount.getId())));
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).findByUserIdAndReconciliationStatusInAndAccountIdIn(
                eq(userId), any(), eq(List.of(liveAccount.getId())));
    }

    @Test
    void forMonth_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(transactionRepository, never()).findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any());
        verify(transactionRepository, never()).findByUserIdAndReconciliationStatusInAndAccountIdIn(any(), any(), any());
    }

    // --- forRange: DashboardRangeService's dependency -- same refund-netted, transfer-excluded
    // computation as forMonth above, just parameterized by an explicit [from, to] instead of a
    // calendar month's own boundaries. ---

    @Test
    void forRange_appliesTheSameRefundAndInvestmentTransferNettingAsForMonth() {
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        Transaction refund = txn(new BigDecimal("999.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.REFUND);
        Transaction groceries = txn(new BigDecimal("2000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        Transaction sip = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, refund, groceries, sip));

        ReportService.RangeTotals totals = reportService.forRange(userId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 8, 31));

        assertThat(totals.income()).isEqualByComparingTo("50000.00");
        assertThat(totals.expense()).isEqualByComparingTo("2000.00");
        assertThat(totals.transactionCount()).isEqualTo(2); // salary + groceries -- the two totals were built from
    }

    @Test
    void forRange_queriesTheExactDatesPassedIn_notAMonthBoundary() {
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        LocalDate from = LocalDate.of(2026, 3, 15);
        LocalDate to = LocalDate.of(2026, 8, 20);
        reportService.forRange(userId, from, to);

        verify(transactionRepository).findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), eq(from), eq(to), eq(List.of(liveAccount.getId())));
    }

    @Test
    void forRange_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ReportService.RangeTotals totals = reportService.forRange(userId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(totals.income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.expense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.transactionCount()).isEqualTo(0);
        verify(transactionRepository, never()).findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any());
    }

    @Test
    void availableMonths_scopesDateFetch_toExactlyTheLiveAccountIds() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of());

        reportService.availableMonths(userId);

        verify(transactionRepository).findDistinctTransactionDates(userId, List.of(liveAccount.getId()));
    }

    @Test
    void availableMonths_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(reportService.availableMonths(userId)).isEmpty();
        verify(transactionRepository, never()).findDistinctTransactionDates(any(), any());
    }

    /**
     * BH-042. {@code availableMonths} had no test at all, and it was the clearest case of the
     * unbounded-history pattern: load every transaction the user has ever had, map each to a
     * YearMonth, discard all but the distinct values, to fill a dropdown.
     *
     * <p>What this pins is the projection the service still owns after the query moved to the
     * database -- many dates collapsing to one month, and the ordering the dropdown depends on.
     */
    @Test
    void availableMonths_collapsesDatesToDistinctSortedMonths() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of(
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 6, 11),   // same month as the first
                LocalDate.of(2026, 7, 1)));

        assertThat(reportService.availableMonths(userId))
                .containsExactly("2026-05", "2026-06", "2026-07");
    }

    @Test
    void availableMonths_isEmptyForAUserWithNoTransactions() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of());

        assertThat(reportService.availableMonths(userId)).isEmpty();
    }

    // --- incomeTrend: backs the mobile Insights screen's Income tab trend chart. Deliberately
    // built on top of availableMonths + forRange (both already tested above) rather than a fresh
    // query, so it inherits their refund-netting and deleted-account-safety for free.

    @Test
    void incomeTrend_returnsIncomePerMonth_forTheLastSixAvailableMonths_oldestFirst() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of(
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 15), LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 7, 15)));

        Transaction june = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        june.setTxnDate(LocalDate.of(2026, 6, 10));
        Transaction july = txn(new BigDecimal("60000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        july.setTxnDate(LocalDate.of(2026, 7, 10));

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(eq(userId), any(), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate from = inv.getArgument(1);
                    if (from.equals(LocalDate.of(2026, 6, 1))) return List.of(june);
                    if (from.equals(LocalDate.of(2026, 7, 1))) return List.of(july);
                    return List.of();
                });

        List<ReportService.IncomeTrendPoint> trend = reportService.incomeTrend(userId);

        // 7 months of history exist (Jan-Jul) -- only the last 6 (Feb-Jul) are returned, January
        // dropped, same "trailing window" shape as InsightsService's own priorMonths.
        assertThat(trend).extracting(ReportService.IncomeTrendPoint::month)
                .containsExactly("2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07");
        assertThat(trend.get(0).income()).isEqualByComparingTo("0"); // Feb: no transactions
        assertThat(trend.get(4).income()).isEqualByComparingTo("50000.00"); // Jun
        assertThat(trend.get(5).income()).isEqualByComparingTo("60000.00"); // Jul
    }

    @Test
    void incomeTrend_withFewerThanSixMonthsOfHistory_returnsAllOfThem_noZeroPadding() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any())).thenReturn(List.of(
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 15)));
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        List<ReportService.IncomeTrendPoint> trend = reportService.incomeTrend(userId);

        assertThat(trend).extracting(ReportService.IncomeTrendPoint::month)
                .containsExactly("2026-06", "2026-07");
    }

    @Test
    void incomeTrend_excludesRefundStatusIncome_sameAsForMonth() {
        when(transactionRepository.findDistinctTransactionDates(eq(userId), any()))
                .thenReturn(List.of(LocalDate.of(2026, 7, 15)));

        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        Transaction refund = txn(new BigDecimal("999.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.REFUND);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, refund));

        List<ReportService.IncomeTrendPoint> trend = reportService.incomeTrend(userId);

        assertThat(trend).hasSize(1);
        assertThat(trend.get(0).income()).isEqualByComparingTo("50000.00");
    }

    @Test
    void incomeTrend_withNoLiveAccounts_returnsEmpty_withoutQueryingTransactions() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(reportService.incomeTrend(userId)).isEmpty();
        verify(transactionRepository, never()).findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any());
    }

    // ---- the total is the sum of the category breakdown ----

    /** Measured on prod: a refund filed under Dining (no Dining spend that month) lowered Expenses
     *  to 1,285 while the breakdown -- which hides a negative category -- still summed to 2,776. */
    @Test
    void forMonth_expenseEqualsTheSumOfItsCategories() {
        com.finora.entity.Category dining = new com.finora.entity.Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setName("Dining");
        com.finora.entity.Category other = new com.finora.entity.Category();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setName("Other");
        when(categoryRepository.findByUserId(any())).thenReturn(List.of(dining, other));
        liveAccount.setAccountType(Account.Type.CREDIT_CARD);
        Transaction purchase = txn(new BigDecimal("1491.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        purchase.setAccountId(liveAccount.getId());
        purchase.setCategoryId(other.getId());
        purchase.setDescription("UPI PAYAPP 111111111111");
        Transaction groceries = txn(new BigDecimal("1285.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        groceries.setAccountId(liveAccount.getId());
        groceries.setCategoryId(other.getId());
        groceries.setDescription("GROCER 1");
        Transaction refund = txn(new BigDecimal("1491.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        refund.setAccountId(liveAccount.getId());
        refund.setCategoryId(dining.getId());
        refund.setDescription("REFUND FROM MERCHANTCO ORDER 1");
        refund.setSource(Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(purchase, groceries, refund));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        BigDecimal categorySum = report.categories().stream().map(ReportDto.CategoryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(report.expense()).isEqualByComparingTo(categorySum);
        assertThat(report.expense()).isEqualByComparingTo("2776.00");
        // The web dashboard's Expenses card reads the range path -- the figure seen on prod.
        assertThat(reportService.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)).expense())
                .isEqualByComparingTo("2776.00");
    }

    // ---- income by kind (Plan 2) ----

    @Test
    void incomeByKindListsFamilySupportOnItsOwnLine() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        salary.setSource(Transaction.Source.CSV_IMPORT);
        Transaction fromParent = txn(new BigDecimal("5000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromParent.setAccountId(liveAccount.getId());
        fromParent.setDescription("UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI");
        fromParent.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromParent.setCounterpartyKey("vpa:asha");
        fromParent.setSource(Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, fromParent));
        com.finora.entity.InflowKind family = new com.finora.entity.InflowKind();
        ReflectionTestUtils.setField(family, "id", UUID.randomUUID());
        family.setUserId(userId);
        family.setName("Family support");
        family.setCountsAsIncome(true);
        family.setBuiltIn(com.finora.entity.InflowKind.BuiltIn.FAMILY_SUPPORT);
        com.finora.entity.SenderInflowRule rule = new com.finora.entity.SenderInflowRule();
        rule.setUserId(userId);
        rule.setCounterpartyKey("vpa:asha");
        rule.setInflowKindId(family.getId());
        when(inflowKindRepository.findByUserId(userId)).thenReturn(List.of(family));
        when(senderInflowRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("55000.00");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("0");
        assertThat(report.incomeByKind()).containsExactly(
                new ReportDto.IncomeLine("Salary", new BigDecimal("50000.00")),
                new ReportDto.IncomeLine("Family support", new BigDecimal("5000.00")));
    }

    // ---- flow classification: only real income counts as income ----

    @Test
    void forMonth_moneyFromAPersonIsUnresolved_notIncome() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction fromPerson = txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(liveAccount.getId());
        fromPerson.setDescription("UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromPerson.setSource(Transaction.Source.CSV_IMPORT); // imported: a hand-entered credit is the user saying "income"
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, fromPerson));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("1000.00");
    }

    @Test
    void forMonth_creditCardCreditsNeverCountAsIncome() {
        Account card = new Account();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        card.setUserId(userId);
        card.setAccountType(Account.Type.CREDIT_CARD);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount, card));
        Transaction billPaid = txn(new BigDecimal("20000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        billPaid.setAccountId(card.getId());
        billPaid.setDescription("PAYMENT RECEIVED THANK YOU");
        Transaction merchantCredit = txn(new BigDecimal("1479.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        merchantCredit.setAccountId(card.getId());
        merchantCredit.setDescription("UPI MERCHANTCO 111111111111");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(billPaid, merchantCredit));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("0");
        // The bill payment is a transfer, so only the merchant credit is unresolved.
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("1479.00");
    }

    // ---- savings rate: the Dashboard's rule, carried on the report ----

    @Test
    void savingsRate_isTheShareOfIncomeKept() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction spend = txn(new BigDecimal("12500.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        spend.setAccountId(liveAccount.getId());
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, spend));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.savingsRatePct()).isEqualByComparingTo("75");
        assertThat(report.savingsRateGateReason()).isNull();
    }

    @Test
    void savingsRate_isWithheldWithAReason_whenNoIncomeWasCounted() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction spend = txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        spend.setAccountId(liveAccount.getId());
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(spend));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.savingsRatePct()).isNull();
        assertThat(report.savingsRateGateReason()).isEqualTo(SavingsRate.NO_INCOME);
    }

    @Test
    void savingsRate_isWithheldWithAReason_whenUnclassifiedCreditsExceedCountedIncome() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("500.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction fromPerson = txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(liveAccount.getId());
        fromPerson.setDescription("UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromPerson.setSource(Transaction.Source.CSV_IMPORT);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, fromPerson));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("500.00");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("1000.00");
        assertThat(report.savingsRatePct()).isNull();
        assertThat(report.savingsRateGateReason()).isEqualTo(SavingsRate.UNRESOLVED_EXCEEDS_INCOME);
    }

    @Test
    void savingsRate_isNegativeNotWithheld_whenSpendingExceedsRealIncome() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction spend = txn(new BigDecimal("1500.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        spend.setAccountId(liveAccount.getId());
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, spend));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        // Overspending with real income is a real (negative) rate, not a reason to hide the figure.
        assertThat(report.savingsRatePct()).isEqualByComparingTo("-50");
        assertThat(report.savingsRateGateReason()).isNull();
    }

    @Test
    void forRange_incomeUsesTheSameFlowRules() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction fdClosure = txn(new BigDecimal("100000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fdClosure.setAccountId(liveAccount.getId());
        fdClosure.setDescription("FD CLOSURE PROCEEDS 000123");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(fdClosure));

        var totals = reportService.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(totals.income()).isEqualByComparingTo("0");
        // An FD closure is an investment withdrawal, not unresolved -- nothing for the banner.
        assertThat(totals.unresolvedInflow()).isEqualByComparingTo("0");
        assertThat(totals.unresolvedInflowCount()).isZero();
        assertThat(totals.unresolvedTopReason()).isNull();
    }

    @Test
    void unresolvedInflowsMatchForRangeCount() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction fromPerson = txn(new BigDecimal("2500.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(liveAccount.getId());
        fromPerson.setDescription("UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromPerson.setSource(Transaction.Source.CSV_IMPORT);
        Transaction fromAnother = txn(new BigDecimal("700.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromAnother.setAccountId(liveAccount.getId());
        fromAnother.setDescription("UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI");
        fromAnother.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromAnother.setSource(Transaction.Source.CSV_IMPORT);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction rent = txn(new BigDecimal("15000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        rent.setAccountId(liveAccount.getId());
        rent.setDescription("UPI-LANDLORD ONE-landlord@okbank-111111111111-UPI");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(fromPerson, fromAnother, salary, rent));
        LocalDate from = LocalDate.of(2026, 7, 1), to = LocalDate.of(2026, 7, 31);

        ReportService.RangeTotals totals = reportService.forRange(userId, from, to);
        ReportService.UnresolvedRows rows = reportService.unresolvedInflows(userId, from, to);

        assertThat(rows.rows()).containsExactlyInAnyOrder(fromPerson, fromAnother);
        assertThat(rows.rows()).hasSize(totals.unresolvedInflowCount());
        assertThat(rows.rows().stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(totals.unresolvedInflow());
        assertThat(rows.accounts()).containsExactly(liveAccount);
    }

    @Test
    void forRange_reportsUnresolvedInflowWithItsTopReason() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction fromPerson = txn(new BigDecimal("2500.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(liveAccount.getId());
        fromPerson.setDescription("UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromPerson.setSource(Transaction.Source.CSV_IMPORT); // imported: a hand-entered credit is the user saying "income"
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(fromPerson, salary));

        var totals = reportService.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(totals.income()).isEqualByComparingTo("50000.00");
        assertThat(totals.unresolvedInflow()).isEqualByComparingTo("2500.00");
        assertThat(totals.unresolvedInflowCount()).isEqualTo(1);
        assertThat(totals.unresolvedTopReason()).isEqualTo("PERSON_INFLOW");
    }

    // ---- unlinked refunds give spend back instead of vanishing ----

    private Transaction unlinkedRefund(String amount) {
        Transaction t = txn(new BigDecimal(amount), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        t.setAccountId(liveAccount.getId());
        t.setDescription("REFUND FROM MERCHANTCO ORDER 1");
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }

    // ---- biggest purchases and spend per day: the category list's own rows and netting ----

    private Transaction purchase(String amount, LocalDate date, String merchant, String description) {
        Transaction t = txn(new BigDecimal(amount), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        t.setAccountId(liveAccount.getId());
        t.setTxnDate(date);
        t.setMerchant(merchant);
        t.setDescription(description);
        return t;
    }

    private ReportDto reportOf(Transaction... rows) {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(rows));
        return reportService.forMonth(userId, "2026-07");
    }

    @Test
    void biggestSpends_areTheThreeLargestPurchases_biggestFirst_labelledByMerchantElseDescription() {
        ReportDto report = reportOf(
                purchase("500.00", LocalDate.of(2026, 7, 2), "Small Shop", "POS SMALL"),
                purchase("9000.00", LocalDate.of(2026, 7, 5), "Big Mart", "POS BIG"),
                purchase("2500.00", LocalDate.of(2026, 7, 9), null, "ELECTRICITY BILL JULY"),
                purchase("4000.00", LocalDate.of(2026, 7, 12), "  ", "  RENT PAYMENT  "),
                purchase("100.00", LocalDate.of(2026, 7, 20), "Tiny", "POS TINY"));

        assertThat(report.biggestSpends()).extracting(ReportDto.BiggestSpend::label)
                .containsExactly("Big Mart", "RENT PAYMENT", "ELECTRICITY BILL JULY");
        assertThat(report.biggestSpends()).extracting(s -> s.amount().intValue()).containsExactly(9000, 4000, 2500);
        assertThat(report.biggestSpends().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(report.biggestSpends().get(0).category()).isEqualTo("Uncategorized");
        assertThat(report.biggestSpends().get(0).id()).isNotNull();
    }

    @Test
    void biggestSpends_neverListsATransferOrIncome_andFallsBackToAPlainLabel() {
        Transaction transfer = purchase("50000.00", LocalDate.of(2026, 7, 3), "Own account", "TRANSFER TO SELF");
        transfer.setTransfer(true);
        Transaction salary = txn(new BigDecimal("80000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction unnamed = purchase("700.00", LocalDate.of(2026, 7, 8), null, null);

        ReportDto report = reportOf(transfer, salary, unnamed);

        assertThat(report.biggestSpends()).singleElement().satisfies(s -> {
            assertThat(s.label()).isEqualTo("Transaction");
            assertThat(s.amount()).isEqualByComparingTo("700.00");
        });
        // The category list leaves the transfer out too: the two agree on what counts as spend.
        assertThat(report.categories()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("700.00"));
        assertThat(report.dailySpend()).singleElement().satisfies(d -> assertThat(d.amount()).isEqualByComparingTo("700.00"));
    }

    @Test
    void biggestSpends_breakTiesByTheLaterDate_soTheOrderNeverShuffles() {
        ReportDto report = reportOf(
                purchase("1000.00", LocalDate.of(2026, 7, 4), "Earlier", null),
                purchase("1000.00", LocalDate.of(2026, 7, 18), "Later", null));

        assertThat(report.biggestSpends()).extracting(ReportDto.BiggestSpend::label).containsExactly("Later", "Earlier");
    }

    @Test
    void biggestSpends_areFewerThanThreeWhenThereAreFewer_andEmptyWhenNothingWasSpent() {
        assertThat(reportOf(purchase("10.00", LocalDate.of(2026, 7, 1), "A", null)).biggestSpends()).hasSize(1);
        assertThat(reportOf().biggestSpends()).isEmpty();
    }

    @Test
    void dailySpend_sumsEachDay_oldestFirst_andSkipsDaysWithNoSpend() {
        ReportDto report = reportOf(
                purchase("300.00", LocalDate.of(2026, 7, 20), "A", null),
                purchase("200.00", LocalDate.of(2026, 7, 4), "B", null),
                purchase("50.00", LocalDate.of(2026, 7, 4), "C", null));

        assertThat(report.dailySpend()).extracting(ReportDto.DailySpend::date)
                .containsExactly(LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 20));
        assertThat(report.dailySpend().get(0).amount()).isEqualByComparingTo("250.00");
        assertThat(report.dailySpend().get(1).amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void dailySpend_addsUpToTheCategoryTotal() {
        ReportDto report = reportOf(
                purchase("300.00", LocalDate.of(2026, 7, 20), "A", null),
                purchase("200.00", LocalDate.of(2026, 7, 4), "B", null),
                purchase("125.50", LocalDate.of(2026, 7, 4), "C", null));

        BigDecimal days = report.dailySpend().stream().map(ReportDto.DailySpend::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal categories = report.categories().stream().map(ReportDto.CategoryAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(days).isEqualByComparingTo(categories);
        assertThat(days).isEqualByComparingTo("625.50");
    }

    @Test
    void dailySpend_aDayWhoseRefundCancelsItsPurchases_hasNoEntry_andTheRefundIsNeverABiggestSpend() {
        Transaction bought = purchase("300.00", LocalDate.of(2026, 7, 15), "Shop", null);
        Transaction refund = unlinkedRefund("300.00");
        refund.setTxnDate(LocalDate.of(2026, 7, 15));

        ReportDto report = reportOf(bought, refund);

        assertThat(report.dailySpend()).isEmpty();
        assertThat(report.biggestSpends()).extracting(ReportDto.BiggestSpend::label).containsExactly("Shop");
    }

    @Test
    void dailySpend_isEmptyForAMonthWithNoSpend() {
        assertThat(reportOf().dailySpend()).isEmpty();
    }

    @Test
    void forMonth_unlinkedRefund_reducesExpenseAndItsCategory_notIncome() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction purchase = txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        purchase.setAccountId(liveAccount.getId());
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(purchase, unlinkedRefund("300.00")));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.expense()).isEqualByComparingTo("700.00");
        assertThat(report.income()).isEqualByComparingTo("0");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("0");
        assertThat(report.categories()).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("700.00"));
    }

    @Test
    void forMonth_refundLargerThanTheMonthsSpend_floorsAtZeroAndDropsTheCategory() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(unlinkedRefund("300.00")));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.expense()).isEqualByComparingTo("0");
        assertThat(report.categories()).isEmpty();
    }

    @Test
    void forRange_unlinkedRefund_reducesExpense() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction purchase = txn(new BigDecimal("1000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        purchase.setAccountId(liveAccount.getId());
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(purchase, unlinkedRefund("300.00")));

        var totals = reportService.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(totals.expense()).isEqualByComparingTo("700.00");
        assertThat(totals.income()).isEqualByComparingTo("0");
    }

    // --- Comparison with the previous month, and the per-month trend. ---

    private Transaction txnOn(LocalDate date, String amount, Transaction.Type type) {
        Transaction t = txn(new BigDecimal(amount), type, Transaction.ReconciliationStatus.OK);
        t.setTxnDate(date);
        return t;
    }

    /** Whatever the repository is asked about, answer empty unless a month is stubbed below. */
    private void stubNothingByDefault() {
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(any(), any(), any()))
                .thenReturn(List.of());
    }

    private void stubMonth(int year, int month, Transaction... txns) {
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), eq(ym.atDay(1)), eq(ym.atEndOfMonth()), any())).thenReturn(List.of(txns));
    }

    private void stubHistory(LocalDate earliest, LocalDate latest) {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any())).thenReturn(earliest);
        when(transactionRepository.findLatestTxnDate(eq(userId), any())).thenReturn(latest);
    }

    /** History Jun 1 - Sep 30, July (prior) with 3 transactions, August (selected) with 2. */
    private void stubTwoComparableMonths() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 7, 10), "300.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 7, 20), "100.00", Transaction.Type.EXPENSE));
        stubMonth(2026, 8,
                txnOn(LocalDate.of(2026, 8, 2), "1500.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 8, 12), "500.00", Transaction.Type.EXPENSE));
    }

    @Test
    void comparison_isReliable_andReportsPercentChangesAndANetAmountChange() {
        stubTwoComparableMonths();

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isTrue();
        assertThat(c.gateReason()).isNull();
        assertThat(c.previousMonth()).isEqualTo("2026-07");
        assertThat(c.previousIncome()).isEqualByComparingTo("1000.00");
        assertThat(c.previousExpense()).isEqualByComparingTo("400.00");
        assertThat(c.incomeDeltaPct()).isEqualTo(50.0);   // 1000 -> 1500
        assertThat(c.expenseDeltaPct()).isEqualTo(25.0);  // 400 -> 500
        // net 600 last month, 1000 this month: reported as an amount, not a percent of a net.
        assertThat(c.netDelta()).isEqualByComparingTo("400.00");
    }

    @Test
    void comparison_withAZeroPriorIncome_hasNoIncomePercent_butIsStillReliable() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "100.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 7, 3), "100.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 7, 4), "100.00", Transaction.Type.EXPENSE));
        stubMonth(2026, 8, txnOn(LocalDate.of(2026, 8, 2), "1500.00", Transaction.Type.INCOME));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isTrue();
        assertThat(c.incomeDeltaPct()).isNull();           // a change from nothing has no percentage
        assertThat(c.expenseDeltaPct()).isEqualTo(-100.0); // 300 -> 0 is a real, defined change
    }

    @Test
    void comparison_forTheNewestMonth_whenItIsStillInProgress_isNotMadeAgainstAFullMonth() {
        stubTwoComparableMonths();
        // Data ends on the 26th: September is a partial slice of a continuous window.
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 26));
        stubMonth(2026, 9, txnOn(LocalDate.of(2026, 9, 5), "900.00", Transaction.Type.EXPENSE));
        stubMonth(2026, 8,
                txnOn(LocalDate.of(2026, 8, 2), "1500.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 8, 12), "500.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 8, 13), "500.00", Transaction.Type.EXPENSE));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-09").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("SELECTED_MONTH_PARTIAL");
        assertThat(c.previousIncome()).isNull();
        assertThat(c.expenseDeltaPct()).isNull();
        assertThat(c.netDelta()).isNull();
    }

    // --- complete: the month's own "is this a whole month" flag, for charts ---

    @Test
    void complete_isTrueForAWholeMonthInTheMiddleOfHistory() {
        stubTwoComparableMonths();

        assertThat(reportService.forMonth(userId, "2026-08").complete()).isTrue();
    }

    @Test
    void complete_isFalseForTheRaggedNewestMonth() {
        stubTwoComparableMonths();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 26)); // data ends on the 26th
        stubMonth(2026, 9, txnOn(LocalDate.of(2026, 9, 5), "900.00", Transaction.Type.EXPENSE));

        assertThat(reportService.forMonth(userId, "2026-09").complete()).isFalse();
    }

    @Test
    void complete_isFalseForAMonthSharingADayWithAMissingStatement() {
        stubTwoComparableMonths();
        when(accountCoverageService.gapsForUser(userId)).thenReturn(List.of(
                new AccountCoverageService.DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20))));

        assertThat(reportService.forMonth(userId, "2026-08").complete()).isFalse();
    }

    @Test
    void complete_isFalseWhenAnActiveAccountsDataStopsShortOfTheMonth() {
        stubTwoComparableMonths();
        givenTheAccountsLatestStatementEnds(LocalDate.of(2026, 8, 11));

        assertThat(reportService.forMonth(userId, "2026-08").complete()).isFalse();
    }

    @Test
    void complete_doesNotDependOnWhetherTheComparisonCouldBeMade() {
        // July is a whole month with only two transactions, so its COMPARISON is withheld (too few
        // in the month before it) -- but the month itself is complete, and a chart must not hatch it.
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 7, 10), "300.00", Transaction.Type.EXPENSE));

        ReportDto july = reportService.forMonth(userId, "2026-07");

        assertThat(july.comparison().reliable()).isFalse();
        assertThat(july.complete()).isTrue();
    }

    @Test
    void complete_isTrueForAUserWithNoLiveAccount() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(reportService.forMonth(userId, "2026-07").complete()).isTrue();
    }

    @Test
    void comparison_forTheFirstMonthOfHistory_hasNothingToCompareWith() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 8, txnOn(LocalDate.of(2026, 8, 2), "1500.00", Transaction.Type.INCOME));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("PRIOR_BEFORE_HISTORY");
    }

    @Test
    void comparison_whenThePriorMonthIsTheRaggedStartOfHistory_isNotUsedAsABaseline() {
        stubTwoComparableMonths();
        stubHistory(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 9, 30)); // July starts on the 20th

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("PARTIAL_PRIOR_MONTH");
    }

    @Test
    void comparison_whenAStatementIsMissingInEitherMonth_isNotMade() {
        stubTwoComparableMonths();
        when(accountCoverageService.gapsForUser(userId)).thenReturn(List.of(
                new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 20))));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("COVERAGE_GAP");
    }

    @Test
    void comparison_whenThePriorMonthHasFewerThanThreeTransactions_isNotMade() {
        stubTwoComparableMonths();
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 7, 10), "300.00", Transaction.Type.EXPENSE));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("TOO_FEW_PRIOR_TRANSACTIONS");
    }

    @Test
    void comparison_whenThePriorMonthHasExactlyThreeTransactions_isMade() {
        stubTwoComparableMonths(); // July carries exactly 3

        assertThat(reportService.forMonth(userId, "2026-08").comparison().reliable()).isTrue();
    }

    @Test
    void comparison_forAMonthWithNoTransactions_isNotMadeAsAHundredPercentDrop() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 7, 10), "300.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 7, 20), "100.00", Transaction.Type.EXPENSE));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("NO_TRANSACTIONS_IN_MONTH");
    }

    @Test
    void comparison_isNullForAUserWithNoLiveAccount_andTheCountsAreZero() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.comparison()).isNull();
        assertThat(report.transactionCount()).isZero();
        assertThat(report.unresolvedInflowCount()).isZero();
    }

    @Test
    void transactionCount_isTheRowsTheTotalsWereBuiltFrom_notEveryRowInTheMonth() {
        Transaction groceries = txn(new BigDecimal("2000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.OK);
        Transaction sip = txn(new BigDecimal("3000.00"), Transaction.Type.EXPENSE, Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(groceries, sip));

        assertThat(reportService.forMonth(userId, "2026-07").transactionCount()).isEqualTo(1);
    }

    @Test
    void comparison_priorSideIsBuiltTheSameWayAsTheSelectedSide_refundsAndInvestmentTransfersIncluded() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        Transaction sip = txnOn(LocalDate.of(2026, 7, 5), "3000.00", Transaction.Type.EXPENSE);
        sip.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        Transaction refundLeg = txnOn(LocalDate.of(2026, 7, 6), "999.00", Transaction.Type.INCOME);
        refundLeg.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        stubMonth(2026, 7,
                txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME),
                txnOn(LocalDate.of(2026, 7, 10), "300.00", Transaction.Type.EXPENSE),
                txnOn(LocalDate.of(2026, 7, 11), "100.00", Transaction.Type.EXPENSE),
                sip, refundLeg);
        stubMonth(2026, 8, txnOn(LocalDate.of(2026, 8, 2), "1500.00", Transaction.Type.INCOME));

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        // The SIP is not spend and the refund leg is not income, on the prior side exactly as on
        // the selected side -- otherwise the percentage compares two differently-built numbers.
        assertThat(c.previousIncome()).isEqualByComparingTo("1000.00");
        assertThat(c.previousExpense()).isEqualByComparingTo("400.00");
        assertThat(c.previousIncome()).isEqualByComparingTo(
                reportService.forMonth(userId, "2026-07").income());
    }

    // --- trend ---

    @Test
    void trend_returnsOneEqualToForMonthPointPerMonth_oldestFirst_withNoPaddingBeforeHistory() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        stubMonth(2026, 7, txnOn(LocalDate.of(2026, 7, 2), "1000.00", Transaction.Type.INCOME));
        stubMonth(2026, 8, txnOn(LocalDate.of(2026, 8, 2), "200.00", Transaction.Type.EXPENSE));
        stubMonth(2026, 9, txnOn(LocalDate.of(2026, 9, 2), "300.00", Transaction.Type.EXPENSE));

        List<ReportService.ReportTrendPoint> points = reportService.trend(userId, null, 6);

        assertThat(points).extracting(ReportService.ReportTrendPoint::month).containsExactly("2026-07", "2026-08", "2026-09");
        ReportDto august = reportService.forMonth(userId, "2026-08");
        ReportService.ReportTrendPoint augustPoint = points.get(1);
        assertThat(augustPoint.income()).isEqualByComparingTo(august.income());
        assertThat(augustPoint.expense()).isEqualByComparingTo(august.expense());
        assertThat(augustPoint.transactionCount()).isEqualTo(august.transactionCount());
    }

    @Test
    void trend_endsAtTheAnchorMonth_andIsClampedToTheNewestMonthWithData() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30));

        assertThat(reportService.trend(userId, java.time.YearMonth.of(2026, 5), 2))
                .extracting(ReportService.ReportTrendPoint::month).containsExactly("2026-04", "2026-05");
        assertThat(reportService.trend(userId, java.time.YearMonth.of(2027, 3), 1))
                .extracting(ReportService.ReportTrendPoint::month).containsExactly("2026-09");
    }

    @Test
    void trend_clampsTheRequestedWindowToBetweenOneAndTwelveMonths() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 30));

        assertThat(reportService.trend(userId, null, 0)).hasSize(1);
        assertThat(reportService.trend(userId, null, -5)).hasSize(1);
        assertThat(reportService.trend(userId, null, 99)).hasSize(12);
    }

    @Test
    void trend_marksARaggedEdgeMonthAndAGapMonthIncomplete() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 9, 30)); // June starts on the 15th
        when(accountCoverageService.gapsForUser(userId)).thenReturn(List.of(
                new AccountCoverageService.DateRange(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 9))));

        List<ReportService.ReportTrendPoint> points = reportService.trend(userId, null, 6);

        assertThat(points).extracting(ReportService.ReportTrendPoint::month, ReportService.ReportTrendPoint::complete)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("2026-06", false),  // ragged start
                        org.assertj.core.api.Assertions.tuple("2026-07", true),
                        org.assertj.core.api.Assertions.tuple("2026-08", false),  // statement gap
                        org.assertj.core.api.Assertions.tuple("2026-09", true));
    }

    @Test
    void trend_forAnAnchorBeforeHistoryBegan_isEmpty_notAListOfFabricatedZeroMonths() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));

        assertThat(reportService.trend(userId, java.time.YearMonth.of(2025, 1), 6)).isEmpty();
    }

    @Test
    void historyBounds_areScopedToTheLiveAccounts_soADeletedAccountCannotMoveThem() {
        // The earliest/latest dates decide both whether a month has a prior to compare with and
        // where the trend starts. A deleted account's transactions keep deleted_at unset, so if
        // these were scoped by user only, an old deleted account would push the history back
        // years and every comparison would read as "has a prior month" against empty space.
        stubTwoComparableMonths();

        reportService.forMonth(userId, "2026-08");
        reportService.trend(userId, null, 3);

        verify(transactionRepository, org.mockito.Mockito.atLeastOnce())
                .findEarliestTxnDate(eq(userId), eq(List.of(liveAccount.getId())));
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce())
                .findLatestTxnDate(eq(userId), eq(List.of(liveAccount.getId())));
    }

    // --- an account whose latest statement stops short of the month ---

    private void givenTheAccountsLatestStatementEnds(LocalDate end) {
        when(accountCoverageService.latestCoverageEnds(userId)).thenReturn(java.util.Map.of(liveAccount.getId(), end));
        when(transactionRepository.findAccountIdsWithTransactionsBetween(eq(userId), any(), any(), any()))
                .thenReturn(List.of(liveAccount.getId()));
    }

    @Test
    void comparison_whenAnActiveAccountsStatementEndsBeforeTheMonthDoes_isHeldBack() {
        stubTwoComparableMonths();
        givenTheAccountsLatestStatementEnds(LocalDate.of(2026, 8, 11)); // August ends on the 31st

        ReportDto.MonthComparison c = reportService.forMonth(userId, "2026-08").comparison();

        assertThat(c.reliable()).isFalse();
        assertThat(c.gateReason()).isEqualTo("ACCOUNT_STATEMENT_BEHIND");
    }

    @Test
    void comparison_whenTheStatementReachesPastTheMonth_isMade() {
        stubTwoComparableMonths();
        givenTheAccountsLatestStatementEnds(LocalDate.of(2026, 9, 11)); // the next cycle covers August

        assertThat(reportService.forMonth(userId, "2026-08").comparison().reliable()).isTrue();
    }

    @Test
    void comparison_forAMonthOlderThanTheTwoNewest_doesNotEvenAskWhichAccountsWereActive() {
        stubTwoComparableMonths(); // newest month with data is September, so July is not recent
        givenTheAccountsLatestStatementEnds(LocalDate.of(2026, 6, 1));

        reportService.forMonth(userId, "2026-07");

        verify(transactionRepository, never()).findAccountIdsWithTransactionsBetween(any(), any(), any(), any());
    }

    @Test
    void comparison_withNoStatementBackedAccount_doesNotRunTheActivityQuery() {
        stubTwoComparableMonths(); // latestCoverageEnds is empty by default

        reportService.forMonth(userId, "2026-08");

        verify(transactionRepository, never()).findAccountIdsWithTransactionsBetween(any(), any(), any(), any());
    }

    @Test
    void trend_marksTheRecentMonthsAnAccountsStatementDoesNotReachAsIncomplete() {
        stubNothingByDefault();
        stubHistory(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 30));
        givenTheAccountsLatestStatementEnds(LocalDate.of(2026, 8, 11));

        List<ReportService.ReportTrendPoint> points = reportService.trend(userId, null, 6);

        assertThat(points).extracting(ReportService.ReportTrendPoint::month, ReportService.ReportTrendPoint::complete)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("2026-06", true),
                        org.assertj.core.api.Assertions.tuple("2026-07", true),   // too old to be judged
                        org.assertj.core.api.Assertions.tuple("2026-08", false),
                        org.assertj.core.api.Assertions.tuple("2026-09", false));
    }

    @Test
    void trend_isEmptyWithNoLiveAccount_andWithNoTransactionHistory() {
        stubHistory(null, null);
        assertThat(reportService.trend(userId, null, 6)).isEmpty();

        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        assertThat(reportService.trend(userId, null, 6)).isEmpty();
        verify(transactionRepository, never()).findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any());
    }
}
