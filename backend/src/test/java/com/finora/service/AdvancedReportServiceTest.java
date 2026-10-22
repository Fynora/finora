package com.finora.service;

import com.finora.dto.AdvancedCompare;
import com.finora.dto.AdvancedRange;
import com.finora.dto.AdvancedReportDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvancedReportServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final List<Transaction> world = new ArrayList<>();
    private final Map<String, Category> categories = new java.util.HashMap<>();
    private final List<Merchant> merchants = new ArrayList<>();
    private Account account;
    private AdvancedReportService service;
    private ReportService reports;
    private List<AccountCoverageService.DateRange> gaps = List.of();

    @BeforeEach
    void setUp() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        AccountCoverageService coverage = mock(AccountCoverageService.class);
        StatementReachService reach = mock(StatementReachService.class);
        TransactionGraphService graph = mock(TransactionGraphService.class);

        account = new Account();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        when(accounts.findByUserId(userId)).thenReturn(List.of(account));
        when(graph.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());
        when(coverage.gapsForUser(any())).thenAnswer(i -> gaps);
        when(coverage.latestCoverageEnds(any())).thenReturn(Map.of());
        when(reach.behindFor(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(categoryRepository.findByUserId(userId)).thenAnswer(i -> List.copyOf(categories.values()));
        when(merchantRepository.findByUserId(userId)).thenAnswer(i -> List.copyOf(merchants));
        when(transactions.findEarliestTxnDate(eq(userId), any())).thenAnswer(i -> world.stream()
                .map(Transaction::getTxnDate).min(LocalDate::compareTo).orElse(null));
        when(transactions.findLatestTxnDate(eq(userId), any())).thenAnswer(i -> world.stream()
                .map(Transaction::getTxnDate).max(LocalDate::compareTo).orElse(null));
        when(transactions.findByUserIdAndTxnDateBetweenAndAccountIdIn(eq(userId), any(), any(), any()))
                .thenAnswer(i -> {
                    LocalDate from = i.getArgument(1);
                    LocalDate to = i.getArgument(2);
                    return world.stream().filter(t -> !t.getTxnDate().isBefore(from) && !t.getTxnDate().isAfter(to)).toList();
                });
        when(transactions.findByUserIdAndReconciliationStatusInAndAccountIdIn(eq(userId), any(), any()))
                .thenAnswer(i -> world.stream().filter(t -> t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND).toList());

        InflowChoiceService inflow = new InflowChoiceService(mock(InflowKindRepository.class), mock(SenderInflowRuleRepository.class));
        service = new AdvancedReportService(transactions, accounts, categoryRepository, merchantRepository,
                inflow, coverage, reach, graph);
        reports = new ReportService(transactions, accounts, categoryRepository, graph, inflow, coverage, reach);
        ReflectionTestUtils.setField(service, "reportRows", com.finora.people.ReportRows.withoutPeople(graph));
    }

    // ---- fixtures

    private Category category(String name) {
        return categories.computeIfAbsent(name, n -> {
            Category c = new Category();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setUserId(userId);
            c.setName(n);
            return c;
        });
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setUserId(userId);
        m.setCanonicalName(name);
        merchants.add(m);
        return m;
    }

    private Transaction row(Transaction.Type type, LocalDate date, String amount) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setTxnDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        world.add(t);
        return t;
    }

    private Transaction spend(String date, String amount, String category) {
        Transaction t = row(Transaction.Type.EXPENSE, LocalDate.parse(date), amount);
        if (category != null) t.setCategoryId(category(category).getId());
        t.setDescription("UPI/" + category);
        return t;
    }

    private Transaction earn(String date, String amount) {
        return row(Transaction.Type.INCOME, LocalDate.parse(date), amount);
    }

    /** Every month from {@code from} to {@code to} inclusive: income 100,000, rent 20,000, food 5,000, on the 1st and 2nd. */
    private void steadyMonths(YearMonth from, YearMonth to) {
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            earn(m.atDay(1).toString(), "100000");
            spend(m.atDay(2).toString(), "20000", "Rent");
            spend(m.atDay(3).toString(), "5000", "Food");
            // The month's last day carries a small row, so the statements run to month-end.
            spend(m.atEndOfMonth().toString(), "100", "Misc");
        }
    }

    private AdvancedReportDto build(AdvancedRange range) {
        return service.build(userId, range, null, null, null);
    }

    // ---- tests

    @Test
    void aUserWithNoTransactionsHasNothingToShowAndSaysWhy() {
        AdvancedReportDto dto = build(AdvancedRange.LAST_12_MONTHS);

        assertThat(dto.period().hasData()).isFalse();
        assertThat(dto.period().noDataReason()).isEqualTo("NO_TRANSACTIONS");
        assertThat(dto.months()).isEmpty();
        assertThat(dto.insights()).isEmpty();
        assertThat(dto.comparison().reliable()).isFalse();
    }

    @Test
    void aSixMonthPeriodSumsItsMonthsAndEndsAtTheNewestWholeMonth() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));

        AdvancedReportDto dto = build(AdvancedRange.LAST_6_MONTHS);

        assertThat(dto.period().startMonth()).isEqualTo("2026-04");
        assertThat(dto.period().endMonth()).isEqualTo("2026-09");
        assertThat(dto.period().monthCount()).isEqualTo(6);
        assertThat(dto.months()).hasSize(6);
        assertThat(dto.totals().income()).isEqualByComparingTo("600000");
        assertThat(dto.totals().expense()).isEqualByComparingTo("150600");
        assertThat(dto.totals().net()).isEqualByComparingTo("449400");
        assertThat(dto.totals().avgMonthlyExpense()).isEqualByComparingTo("25100");
        assertThat(dto.totals().monthsWithData()).isEqualTo(6);
        // (600000 - 150600) / 600000 = 74.9%
        assertThat(dto.totals().savingsRatePct()).isEqualByComparingTo("74.9");
        assertThat(dto.totals().savingsRateGateReason()).isNull();
    }

    @Test
    void everyMonthAndThePeriodAreTheFiguresReportsGivesForTheSameDates() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        spend("2026-08-09", "7000", "Shopping");
        Transaction invest = spend("2026-08-10", "50000", "Investments");
        invest.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        for (AdvancedReportDto.AdvancedMonth m : dto.months()) {
            YearMonth ym = YearMonth.parse(m.month());
            ReportService.RangeTotals expected = reports.forRange(userId, ym.atDay(1), ym.atEndOfMonth());
            assertThat(m.income()).as(m.month() + " income").isEqualByComparingTo(expected.income());
            assertThat(m.expense()).as(m.month() + " expense").isEqualByComparingTo(expected.expense());
            assertThat(m.transactionCount()).as(m.month() + " count").isEqualTo(expected.transactionCount());
        }
        ReportService.RangeTotals whole = reports.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        assertThat(dto.totals().income()).isEqualByComparingTo(whole.income());
        assertThat(dto.totals().expense()).isEqualByComparingTo(whole.expense());
    }

    @Test
    void aNewestMonthThatIsNotWholeIsLeftOutAndSaid() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 8));
        spend("2026-09-10", "700", "Food");
        spend("2026-09-17", "300", "Food");

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.period().endMonth()).isEqualTo("2026-08");
        assertThat(dto.period().leftOutMonth()).isEqualTo("2026-09");
        assertThat(dto.period().leftOutReason()).isEqualTo("PARTIAL_MONTH");
        assertThat(dto.months()).extracting(AdvancedReportDto.AdvancedMonth::month)
                .containsExactly("2026-06", "2026-07", "2026-08");
    }

    @Test
    void aHistoryThatIsOnlyAPartialMonthHasNothingToCompare() {
        spend("2026-09-10", "700", "Food");
        spend("2026-09-17", "300", "Food");

        AdvancedReportDto dto = build(AdvancedRange.LAST_12_MONTHS);

        assertThat(dto.period().hasData()).isFalse();
        assertThat(dto.period().noDataReason()).isEqualTo("ONLY_PARTIAL_MONTH");
    }

    @Test
    void anInvestmentTransferIsInNoFigureOnThePage() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        Transaction invest = spend("2026-09-05", "50000", "Investments");
        invest.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.totals().expense()).isEqualByComparingTo("75300");
        assertThat(dto.categories().rows()).extracting(AdvancedReportDto.AdvancedCategory::name)
                .doesNotContain("Investments");
    }

    @Test
    void categoriesAddUpToExpensesAndAreComparedWithThePreviousPeriod() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        spend("2026-09-10", "15000", "Shopping");
        spend("2026-09-11", "900", null);

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        BigDecimal sum = dto.categories().rows().stream().map(AdvancedReportDto.AdvancedCategory::amount)
                .reduce(dto.categories().otherAmount(), BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(dto.totals().expense());
        AdvancedReportDto.AdvancedCategory shopping = dto.categories().rows().stream()
                .filter(c -> c.name().equals("Shopping")).findFirst().orElseThrow();
        assertThat(shopping.amount()).isEqualByComparingTo("15000");
        assertThat(shopping.previousAmount()).isEqualByComparingTo("0");
        assertThat(shopping.changePct()).isNull();
        assertThat(dto.categories().rows()).extracting(AdvancedReportDto.AdvancedCategory::name).contains("Uncategorized");
        AdvancedReportDto.AdvancedCategory rent = dto.categories().rows().stream()
                .filter(c -> c.name().equals("Rent")).findFirst().orElseThrow();
        assertThat(rent.amount()).isEqualByComparingTo("60000");
        assertThat(rent.previousAmount()).isEqualByComparingTo("60000");
        assertThat(rent.changePct()).isZero();
        assertThat(rent.monthly()).hasSize(3).allSatisfy(a -> assertThat(a).isEqualByComparingTo("20000"));
        assertThat(rent.sharePct()).isBetween(60.0, 80.0);
    }

    @Test
    void theComparisonSetsThePeriodBesideTheOneBeforeItWithRealDeltas() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 3));
        // Last three months spend 10,000 more a month.
        for (YearMonth m = YearMonth.of(2026, 7); !m.isAfter(YearMonth.of(2026, 9)); m = m.plusMonths(1)) {
            earn(m.atDay(1).toString(), "100000");
            spend(m.atDay(2).toString(), "30000", "Rent");
            spend(m.atDay(3).toString(), "5000", "Food");
            spend(m.atEndOfMonth().toString(), "100", "Misc");
        }
        for (YearMonth m = YearMonth.of(2026, 4); !m.isAfter(YearMonth.of(2026, 6)); m = m.plusMonths(1)) {
            earn(m.atDay(1).toString(), "100000");
            spend(m.atDay(2).toString(), "20000", "Rent");
            spend(m.atDay(3).toString(), "5000", "Food");
            spend(m.atEndOfMonth().toString(), "100", "Misc");
        }

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.comparison().reliable()).isTrue();
        assertThat(dto.comparison().previousExpense()).isEqualByComparingTo("75300");
        assertThat(dto.totals().expense()).isEqualByComparingTo("105300");
        assertThat(dto.comparison().expenseDeltaPct()).isCloseTo(39.84, org.assertj.core.data.Offset.offset(0.01));
        assertThat(dto.comparison().incomeDeltaPct()).isZero();
        assertThat(dto.comparison().netDelta()).isEqualByComparingTo("-30000");
        assertThat(dto.insights().get(0).kind()).isEqualTo("SPENDING_CHANGE");
        assertThat(dto.insights().get(0).headline()).isEqualTo("Your spending is 40% higher");
        assertThat(dto.insights().get(0).detail()).startsWith("You spent ₹30,000 more than in the previous 3 months: ₹1,05,300 against ₹75,300.");
        assertThat(dto.insights().get(0).detail()).contains("The largest move was Rent (+₹30,000).");
    }

    @Test
    void aComparisonThatReachesBeforeTheHistoryIsWithheldWithItsReason() {
        steadyMonths(YearMonth.of(2026, 5), YearMonth.of(2026, 9));

        AdvancedReportDto dto = build(AdvancedRange.LAST_12_MONTHS);

        assertThat(dto.period().monthCount()).isEqualTo(5);
        assertThat(dto.period().requestedMonths()).isEqualTo(12);
        assertThat(dto.comparison().reliable()).isFalse();
        assertThat(dto.comparison().gateReason()).isEqualTo("PRIOR_PERIOD_BEFORE_HISTORY");
        assertThat(dto.comparison().previousExpense()).isNull();
        assertThat(dto.comparison().expenseDeltaPct()).isNull();
        dto.categories().rows().forEach(c -> assertThat(c.previousAmount()).isNull());
        assertThat(dto.insights()).extracting(AdvancedReportDto.AdvancedInsight::kind).doesNotContain("SPENDING_CHANGE");
    }

    @Test
    void aGapBetweenStatementsInEitherPeriodWithholdsTheComparison() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        gaps = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 20)));

        AdvancedReportDto dto = build(AdvancedRange.LAST_6_MONTHS);

        assertThat(dto.comparison().reliable()).isFalse();
        assertThat(dto.comparison().gateReason()).isEqualTo("COVERAGE_GAP");
        // The gap is in the PREVIOUS period (Oct 2025 - Mar 2026); the current months are whole.
        assertThat(dto.period().incompleteMonths()).isEmpty();
    }

    @Test
    void aGapInsideThePeriodMarksThatMonthIncomplete() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        gaps = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20)));

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.period().incompleteMonths()).containsExactly("2026-08");
        assertThat(dto.months().stream().filter(m -> m.month().equals("2026-08")).findFirst().orElseThrow().complete()).isFalse();
        assertThat(dto.months().stream().filter(m -> m.month().equals("2026-09")).findFirst().orElseThrow().complete()).isTrue();
    }

    @Test
    void allTimeHasNothingBeforeItToCompareWith() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));

        AdvancedReportDto dto = build(AdvancedRange.ALL_TIME);

        assertThat(dto.period().startMonth()).isEqualTo("2025-01");
        assertThat(dto.period().monthCount()).isEqualTo(21);
        assertThat(dto.comparison().reliable()).isFalse();
        assertThat(dto.comparison().gateReason()).isEqualTo("ALL_TIME");
        assertThat(dto.period().compareOptions()).isEmpty();
    }

    @Test
    void theSamePeriodAYearEarlierIsAnOptionForAShortPeriod() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));

        AdvancedReportDto dto = service.build(userId, AdvancedRange.LAST_3_MONTHS, null, null, AdvancedCompare.SAME_PERIOD_LAST_YEAR);

        assertThat(dto.period().compare()).isEqualTo("SAME_PERIOD_LAST_YEAR");
        assertThat(dto.period().previousStartMonth()).isEqualTo("2025-07");
        assertThat(dto.period().previousEndMonth()).isEqualTo("2025-09");
        assertThat(dto.comparison().reliable()).isTrue();
    }

    @Test
    void aSavingsRateIsWithheldWhenThereIsNoIncome() {
        for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2026, 9)); m = m.plusMonths(1)) {
            spend(m.atDay(2).toString(), "1000", "Food");
            spend(m.atEndOfMonth().toString(), "10", "Food");
        }

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.totals().savingsRatePct()).isNull();
        assertThat(dto.totals().savingsRateGateReason()).isEqualTo("NO_INCOME");
        assertThat(dto.insights()).extracting(AdvancedReportDto.AdvancedInsight::kind).doesNotContain("SAVINGS_RATE");
    }

    @Test
    void merchantsAreRankedAndSaySoWhenMostSpendingHasNoMerchant() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        Merchant amazon = merchant("Amazon");
        Transaction big = spend("2026-09-10", "9000", "Shopping");
        big.setMerchantId(amazon.getId());
        Transaction small = spend("2026-08-10", "1000", "Shopping");
        small.setMerchantId(amazon.getId());

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.merchants().rows()).hasSize(1);
        AdvancedReportDto.AdvancedMerchant top = dto.merchants().rows().get(0);
        assertThat(top.name()).isEqualTo("Amazon");
        assertThat(top.amount()).isEqualByComparingTo("10000");
        assertThat(top.transactionCount()).isEqualTo(2);
        assertThat(top.previousAmount()).isEqualByComparingTo("0");
        // 10,000 of 85,300: the rest of the spending names no merchant, and the page says so.
        assertThat(dto.merchants().attributedSharePct()).isBetween(11.0, 12.5);
        assertThat(dto.merchants().merchantCount()).isEqualTo(1);
        assertThat(top.monthly()).hasSize(3);
    }

    @Test
    void aLinkedRefundReducesWhatTheCategoryCost() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        Transaction purchase = spend("2026-09-10", "10000", "Shopping");
        Transaction refund = row(Transaction.Type.INCOME, LocalDate.of(2026, 9, 12), "4000");
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        refund.setRefundOfTransactionId(purchase.getId());

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.categories().rows().stream().filter(c -> c.name().equals("Shopping")).findFirst().orElseThrow()
                .amount()).isEqualByComparingTo("6000");
        assertThat(dto.totals().income()).isEqualByComparingTo("300000");
    }

    @Test
    void incomeIsSplitBySourceAndSaysHowSteadyItWas() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        earn("2026-08-15", "20000");

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.income().sources().stream().map(AdvancedReportDto.AdvancedIncomeSource::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("320000");
        assertThat(dto.income().monthsWithIncome()).isEqualTo(3);
        assertThat(dto.income().highestMonth()).isEqualTo("2026-08");
        assertThat(dto.income().highestMonthAmount()).isEqualByComparingTo("120000");
        assertThat(dto.income().lowestAsPctOfHighest()).isCloseTo(83.33, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void aMuchLargerThanUsualPurchaseIsFlaggedOnlyWithEnoughToCompareItWith() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        for (int i = 0; i < 6; i++) spend("2026-0" + (3 + i) + "-15", "1000", "Travel");
        Transaction big = spend("2026-09-20", "9000", "Travel");
        spend("2026-09-21", "9000", "Gifts"); // a category with nothing to compare against

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        assertThat(dto.unusualSpends()).hasSize(1);
        AdvancedReportDto.AdvancedUnusualSpend u = dto.unusualSpends().get(0);
        assertThat(u.transactionId()).isEqualTo(big.getId());
        assertThat(u.category()).isEqualTo("Travel");
        assertThat(u.typicalAmount()).isEqualByComparingTo("1000");
        assertThat(u.timesTypical()).isEqualByComparingTo("9.0");
        assertThat(u.sampleSize()).isEqualTo(6);
        assertThat(dto.insights()).extracting(AdvancedReportDto.AdvancedInsight::kind).contains("UNUSUAL_SPEND");
    }

    @Test
    void aPurchaseAtThreeTimesTheUsualIsFlaggedAndOneJustUnderIsNot() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        for (int i = 0; i < 6; i++) spend("2026-0" + (3 + i) + "-15", "2000", "Travel");
        spend("2026-09-20", "5999", "Travel");
        assertThat(build(AdvancedRange.LAST_3_MONTHS).unusualSpends()).isEmpty();

        spend("2026-09-21", "6000", "Travel");
        assertThat(build(AdvancedRange.LAST_3_MONTHS).unusualSpends()).hasSize(1);
        assertThat(build(AdvancedRange.LAST_3_MONTHS).unusualSpends().get(0).amount()).isEqualByComparingTo("6000");
    }

    @Test
    void aSmallPurchaseIsNeverFlaggedHoweverUnusualForItsCategory() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        for (int i = 0; i < 6; i++) spend("2026-0" + (3 + i) + "-15", "100", "Tips");
        spend("2026-09-20", "900", "Tips"); // 9x the usual, but under 5% of the period's spending

        assertThat(build(AdvancedRange.LAST_3_MONTHS).unusualSpends()).isEmpty();
    }

    @Test
    void thePaymentMethodsSplitTheSameSpending() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));

        AdvancedReportDto dto = build(AdvancedRange.LAST_3_MONTHS);

        BigDecimal sum = dto.paymentMethods().stream().map(com.finora.dto.ReportDto.PaymentMethodAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(dto.totals().expense());
    }

    @Test
    void theCostliestWholeMonthIsNamedAndNeverMoreThanFiveInsightsAreMade() {
        steadyMonths(YearMonth.of(2025, 1), YearMonth.of(2026, 9));
        spend("2026-07-10", "40000", "Shopping");

        AdvancedReportDto dto = build(AdvancedRange.LAST_6_MONTHS);

        assertThat(dto.totals().highestSpendMonth()).isEqualTo("2026-07");
        assertThat(dto.totals().lowestSpendMonth()).isNotEqualTo("2026-07");
        assertThat(dto.insights()).hasSizeLessThanOrEqualTo(AdvancedReportService.MAX_INSIGHTS);
        assertThat(dto.insights()).extracting(AdvancedReportDto.AdvancedInsight::kind).contains("HIGHEST_MONTH");
        AdvancedReportDto.AdvancedInsight costliest = dto.insights().stream()
                .filter(i -> i.kind().equals("HIGHEST_MONTH")).findFirst().orElseThrow();
        assertThat(costliest.headline()).isEqualTo("July 2026 was your costliest month");
        assertThat(costliest.drill().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(costliest.drill().to()).isEqualTo(LocalDate.of(2026, 7, 31));
    }
}
