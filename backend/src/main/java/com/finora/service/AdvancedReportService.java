package com.finora.service;

import com.finora.dto.AdvancedCompare;
import com.finora.dto.AdvancedRange;
import com.finora.dto.AdvancedReportDto;
import com.finora.dto.AdvancedReportDto.AdvancedCategories;
import com.finora.dto.AdvancedReportDto.AdvancedCategory;
import com.finora.dto.AdvancedReportDto.AdvancedComparison;
import com.finora.dto.AdvancedReportDto.AdvancedDrill;
import com.finora.dto.AdvancedReportDto.AdvancedIncome;
import com.finora.dto.AdvancedReportDto.AdvancedIncomeSource;
import com.finora.dto.AdvancedReportDto.AdvancedInsight;
import com.finora.dto.AdvancedReportDto.AdvancedMerchant;
import com.finora.dto.AdvancedReportDto.AdvancedMerchants;
import com.finora.dto.AdvancedReportDto.AdvancedMonth;
import com.finora.dto.AdvancedReportDto.AdvancedPeriodInfo;
import com.finora.dto.AdvancedReportDto.AdvancedTotals;
import com.finora.dto.AdvancedReportDto.AdvancedUnusualSpend;
import com.finora.dto.AnalyticsDto;
import com.finora.dto.ReportDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.MerchantNames;
import com.finora.util.Rupees;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Advanced Reports page. One period of whole months, the period it is compared with, and what
 * the page draws from them (see docs/superpowers/specs/2026-10-22-advanced-reports-redesign-design.md).
 *
 * <p>Nothing here decides what counts as income or spending. It reads the rows through
 * {@code ReportRows}, totals them with {@link ReportService#totalsOf} (the code behind Reports and
 * the Dashboard range summary), and splits them with the same {@link RefundNetting} and
 * {@link FlowTotals} the monthly report uses, so a figure on this page is the figure those screens
 * give for the same months. Investment transfers are left out of every figure on the page, as they
 * are from Expenses, which is what lets the breakdowns add up to the headline.
 *
 * <p>Read-only; never mutates anything.
 */
@Service
public class AdvancedReportService {

    static final int TOP_ROWS = 8;
    static final int MAX_INSIGHTS = 5;
    /** An insight about a change is only made when the figure moved at least this much (percent). */
    static final double CHANGE_MIN_PCT = 5;
    /** A category's change is only called out when it is at least this many percent... */
    static final double CATEGORY_CHANGE_MIN_PCT = 20;
    /** ...and moved at least this share of the period's spending (percent). */
    static final double CATEGORY_CHANGE_MIN_SHARE_PCT = 2;
    /** A purchase is "larger than usual" at this multiple of its category's median... */
    static final BigDecimal UNUSUAL_MULTIPLE = BigDecimal.valueOf(3);
    /** ...with at least this many other purchases in the category to take the median over... */
    static final int UNUSUAL_MIN_SAMPLES = 5;
    /** ...and at least this share of the period's spending (percent), so small spends are never flagged. */
    static final double UNUSUAL_MIN_SHARE_PCT = 5;
    static final int UNUSUAL_HISTORY_MONTHS = 12;
    static final int MAX_UNUSUAL = 5;
    /** The costliest month is called out when it is this multiple of the monthly average. */
    static final double HIGHEST_MONTH_MIN_MULTIPLE = 1.2;
    /** Merchant concentration is only called out with this many merchants to be concentrated among... */
    static final int CONCENTRATION_MIN_MERCHANTS = 6;
    /** ...when the top five hold at least this share of spending (percent)... */
    static final double CONCENTRATION_MIN_TOP_FIVE_PCT = 40;
    /** ...and at least this share of spending has a merchant at all (percent). */
    static final double CONCENTRATION_MIN_ATTRIBUTED_PCT = 50;

    private static final String UNCATEGORIZED = AnalyticsService.UNCATEGORIZED;
    private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final InflowChoiceService inflowChoices;
    private final AccountCoverageService accountCoverageService;
    private final StatementReachService statementReach;
    /** Shares and loans reach every figure through this (ReportRowsGuardTest); the default is for
     *  unit tests that build this service by hand, Spring injects the real one. */
    private com.finora.people.ReportRows reportRows;

    @Autowired
    void setReportRows(com.finora.people.ReportRows reportRows) { this.reportRows = reportRows; }

    public AdvancedReportService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                                 CategoryRepository categoryRepository, MerchantRepository merchantRepository,
                                 InflowChoiceService inflowChoices, AccountCoverageService accountCoverageService,
                                 StatementReachService statementReach,
                                 TransactionGraphService transactionGraphService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.inflowChoices = inflowChoices;
        this.accountCoverageService = accountCoverageService;
        this.statementReach = statementReach;
        this.reportRows = com.finora.people.ReportRows.withoutPeople(transactionGraphService);
    }

    @Transactional(readOnly = true)
    public AdvancedReportDto build(UUID userId, AdvancedRange range, YearMonth customStart, YearMonth customEnd,
                                   AdvancedCompare requestedCompare) {
        if (range == AdvancedRange.CUSTOM) AdvancedPeriodResolver.validateCustom(customStart, customEnd);
        List<Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(Account::getId).toList();
        LocalDate earliest = liveAccountIds.isEmpty() ? null : transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        LocalDate latest = liveAccountIds.isEmpty() ? null : transactionRepository.findLatestTxnDate(userId, liveAccountIds);
        if (earliest == null || latest == null) {
            return empty(range, "NO_TRANSACTIONS", null, null, null, null, null);
        }

        YearMonth first = YearMonth.from(earliest);
        YearMonth newest = YearMonth.from(latest);
        List<AccountCoverageService.DateRange> gaps = accountCoverageService.gapsForUser(userId);
        Map<UUID, LocalDate> statementEnds = accountCoverageService.latestCoverageEnds(userId);

        // The newest month is left out of a long-term period unless it is whole: the statements run
        // to its last day and no account's latest statement stops short of it. (The first month being
        // a partial slice is a different thing: it is the start of the history, and is only marked.)
        boolean latestDayPartial = latest.getDayOfMonth() != newest.lengthOfMonth();
        boolean newestBehind = statementReach.behindFor(userId, newest, newest, statementEnds).isPresent();
        boolean newestWhole = !latestDayPartial && !newestBehind;
        YearMonth anchor = newestWhole ? newest : newest.minusMonths(1);
        String leftOutMonth = newestWhole ? null : newest.toString();
        String leftOutReason = newestWhole ? null : (latestDayPartial ? "PARTIAL_MONTH" : "STATEMENT_BEHIND");
        if (anchor.isBefore(first) && range != AdvancedRange.CUSTOM) {
            return empty(range, "ONLY_PARTIAL_MONTH", first, newest, leftOutMonth, leftOutReason, null);
        }

        // A custom run names its own months, so it is still honoured when only the newest month exists.
        AdvancedPeriodResolver.Resolved period = AdvancedPeriodResolver.resolve(
                range, customStart, customEnd, requestedCompare, first, newest, anchor.isBefore(first) ? first : anchor);
        if (period.months() == 0) {
            return empty(range, "NO_MONTHS_IN_PERIOD", first, newest, leftOutMonth, leftOutReason, period);
        }

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a));
        FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoriesById.values());
        RefundNetting refunds = reportRows.netting(userId, transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                userId, List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL),
                liveAccountIds));
        Function<Transaction, String> categoryName = t -> categoriesById.containsKey(t.getCategoryId())
                ? categoriesById.get(t.getCategoryId()).getName() : UNCATEGORIZED;
        Map<UUID, Account.Type> accountTypes = new HashMap<>();
        for (Account a : accounts) accountTypes.put(a.getId(), a.getAccountType());

        LocalDate from = period.start().atDay(1);
        LocalDate to = period.end().atEndOfMonth();
        List<Transaction> rows = reportableRows(userId, liveAccountIds, from, to);
        View now = view(rows, refunds, flow);

        // ---- months
        Map<YearMonth, List<Transaction>> rowsByMonth = rows.stream()
                .collect(Collectors.groupingBy(t -> YearMonth.from(t.getTxnDate())));
        List<YearMonth> monthKeys = new ArrayList<>();
        for (YearMonth m = period.start(); !m.isAfter(period.end()); m = m.plusMonths(1)) monthKeys.add(m);
        List<AdvancedMonth> months = new ArrayList<>();
        List<String> incompleteMonths = new ArrayList<>();
        for (YearMonth m : monthKeys) {
            ReportService.RangeTotals t = ReportService.totalsOf(rowsByMonth.getOrDefault(m, List.of()), refunds, flow);
            boolean complete = isWhole(userId, m, earliest, latest, newest, gaps, statementEnds);
            if (!complete) incompleteMonths.add(m.toString());
            months.add(new AdvancedMonth(m.toString(), t.income(), t.expense(), t.income().subtract(t.expense()),
                    SavingsRate.of(t.income(), t.expense(), t.unresolvedInflow()).pct(), complete, t.transactionCount()));
        }

        // ---- totals
        ReportService.RangeTotals totals = now.totals();
        SavingsRate.Reading savings = SavingsRate.of(totals.income(), totals.expense(), totals.unresolvedInflow());
        int monthsWithData = (int) months.stream().filter(m -> m.transactionCount() > 0).count();
        BigDecimal avgMonthly = monthsWithData == 0 ? null
                : totals.expense().divide(BigDecimal.valueOf(monthsWithData), 2, RoundingMode.HALF_UP);
        List<AdvancedMonth> wholeSpendMonths = months.stream()
                .filter(m -> m.complete() && m.transactionCount() > 0 && m.expense().signum() > 0).toList();
        String highest = null;
        String lowest = null;
        if (wholeSpendMonths.size() >= 2) {
            highest = wholeSpendMonths.stream().max(Comparator.comparing(AdvancedMonth::expense)).orElseThrow().month();
            lowest = wholeSpendMonths.stream().min(Comparator.comparing(AdvancedMonth::expense)).orElseThrow().month();
        }
        AdvancedTotals advancedTotals = new AdvancedTotals(totals.income(), totals.expense(),
                totals.income().subtract(totals.expense()), avgMonthly, monthsWithData, savings.pct(),
                savings.gateReason(), totals.unresolvedInflow(), totals.unresolvedInflowCount(),
                totals.transactionCount(), highest, lowest);

        // ---- the comparison period
        View before = null;
        if (period.previousStart() != null) {
            before = view(reportableRows(userId, liveAccountIds, period.previousStart().atDay(1),
                    period.previousEnd().atEndOfMonth()), refunds, flow);
        }
        String gate = period.compare() == null ? "ALL_TIME"
                : comparisonGate(userId, period, before, earliest, latest, newest, gaps, statementEnds);
        boolean reliable = gate == null;
        AdvancedComparison comparison = comparison(reliable, gate, advancedTotals, before, monthsWithData(before));

        // ---- categories
        Map<String, BigDecimal> categoryNow = categorySpend(now, categoryName);
        Map<String, BigDecimal> categoryBefore = reliable ? categorySpend(before, categoryName) : null;
        Map<String, Integer> categoryCount = now.spendRows().stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .collect(Collectors.groupingBy(categoryName, Collectors.summingInt(t -> 1)));
        BigDecimal categoryTotal = categoryNow.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AdvancedCategory> allCategories = categoryNow.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> {
                    BigDecimal prev = reliable ? categoryBefore.getOrDefault(e.getKey(), BigDecimal.ZERO) : null;
                    return new AdvancedCategory(e.getKey(), e.getValue(), pct(e.getValue(), categoryTotal),
                            categoryCount.getOrDefault(e.getKey(), 0), prev,
                            prev == null ? null : e.getValue().subtract(prev),
                            prev == null ? null : changePct(e.getValue(), prev),
                            monthlyByKey(rowsByMonth, monthKeys, refunds, flow, e.getKey(), categoryName));
                }).toList();
        List<AdvancedCategory> shown = allCategories.stream().limit(TOP_ROWS).toList();
        BigDecimal shownSum = shown.stream().map(AdvancedCategory::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherAmount = categoryTotal.subtract(shownSum);
        AdvancedCategories categories = new AdvancedCategories(shown, otherAmount, pct(otherAmount, categoryTotal),
                allCategories.size());

        // ---- merchants
        AdvancedMerchants merchants = merchants(userId, now, before, reliable, refunds, rowsByMonth, monthKeys, flow,
                totals.expense());

        // ---- payment methods
        List<ReportDto.PaymentMethodAmount> paymentMethods =
                ReportService.paymentMethodsOf(now.spendRows(), now.spend(), accountTypes);

        // ---- income
        AdvancedIncome income = income(now, before, reliable, refunds, flow, months);

        // ---- unusual purchases
        List<AdvancedUnusualSpend> unusual = unusualSpends(userId, liveAccountIds, period, now, refunds, flow,
                categoryName, totals.expense());

        // ---- international
        AnalyticsDto.InternationalSpend international = AnalyticsService.internationalSpendOf(
                now.forTotals().stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE).toList(), refunds);

        // The newest month is only worth naming when it is the month this period would otherwise have ended
        // with. A period that ends earlier (a custom run in 2021, last year) never reached for it, and a
        // note about it would be about something the reader did not ask for.
        boolean leftOutApplies = leftOutMonth != null && !period.end().isBefore(anchor);
        AdvancedPeriodInfo info = new AdvancedPeriodInfo(true, null, range.name(), period.start().toString(),
                period.end().toString(), period.months(), period.requestedMonths(), first.toString(), newest.toString(),
                leftOutApplies ? leftOutMonth : null, leftOutApplies ? leftOutReason : null, incompleteMonths,
                period.compare() == null ? null : period.compare().name(),
                period.compareOptions().stream().map(Enum::name).toList(),
                period.previousStart() == null ? null : period.previousStart().toString(),
                period.previousEnd() == null ? null : period.previousEnd().toString());

        List<AdvancedInsight> insights = insights(period, advancedTotals, comparison, months, allCategories, merchants,
                unusual);

        return new AdvancedReportDto(info, advancedTotals, comparison, months, categories, merchants, paymentMethods,
                income, unusual, international, insights);
    }

    // ------------------------------------------------------------------ rows

    private List<Transaction> reportableRows(UUID userId, List<UUID> liveAccountIds, LocalDate from, LocalDate to) {
        if (liveAccountIds.isEmpty()) return List.of();
        return reportRows.reportable(userId,
                transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds));
    }

    /** One window's rows, and what they are worth: the totals, the rows spending is read from, and the
     *  netting that prices them. Investment transfers are in {@code rows} but in nothing derived. */
    private record View(List<Transaction> rows, List<Transaction> forTotals, RefundNetting spend,
                        List<Transaction> spendRows, ReportService.RangeTotals totals) {}

    private View view(List<Transaction> reportable, RefundNetting refunds, FlowTotals.Context flow) {
        List<Transaction> forTotals = RefundNetting.excludingInvestmentTransfers(reportable);
        RefundNetting spend = refunds.withUnlinkedOffsets(reportable, flow);
        return new View(reportable, forTotals, spend, forTotals.stream().filter(spend::countsAsSpend).toList(),
                ReportService.totalsOf(reportable, refunds, flow));
    }

    /** Whether a month is a whole month: the three tests Reports applies (see MonthComparisonGate). */
    private boolean isWhole(UUID userId, YearMonth m, LocalDate earliest, LocalDate latest, YearMonth newest,
                            List<AccountCoverageService.DateRange> gaps, Map<UUID, LocalDate> statementEnds) {
        return !MonthComparisonGate.isPartialBoundaryMonth(m.toString(), earliest, latest)
                && !MonthComparisonGate.overlapsGap(m, gaps)
                && statementReach.behindFor(userId, m, newest, statementEnds).isEmpty();
    }

    // ------------------------------------------------------------------ comparison

    /** Why the earlier period cannot be set beside this one, or null when it can. */
    private String comparisonGate(UUID userId, AdvancedPeriodResolver.Resolved period, View before, LocalDate earliest,
                                  LocalDate latest, YearMonth newest, List<AccountCoverageService.DateRange> gaps,
                                  Map<UUID, LocalDate> statementEnds) {
        String reason = DashboardRangeService.rangeComparisonGateReason(earliest, period.previousStart().atDay(1),
                before.totals().transactionCount());
        if (reason != null) return reason;
        List<YearMonth> compared = new ArrayList<>();
        for (YearMonth m = period.previousStart(); !m.isAfter(period.previousEnd()); m = m.plusMonths(1)) compared.add(m);
        for (YearMonth m = period.start(); !m.isAfter(period.end()); m = m.plusMonths(1)) compared.add(m);
        if (compared.stream().anyMatch(m -> MonthComparisonGate.overlapsGap(m, gaps))) return "COVERAGE_GAP";
        for (YearMonth m : compared) {
            if (MonthComparisonGate.isPartialBoundaryMonth(m.toString(), earliest, latest)
                    || statementReach.behindFor(userId, m, newest, statementEnds).isPresent()) {
                return "INCOMPLETE_MONTHS";
            }
        }
        return null;
    }

    /** Months of a window holding at least one counted transaction, as {@code AdvancedTotals} counts them. */
    private static int monthsWithData(View v) {
        if (v == null) return 0;
        return (int) v.forTotals().stream().map(t -> YearMonth.from(t.getTxnDate())).distinct().count();
    }

    private AdvancedComparison comparison(boolean reliable, String gate, AdvancedTotals now, View before,
                                          int previousMonthsWithData) {
        if (!reliable) {
            return new AdvancedComparison(false, gate, null, null, null, null, null, null, null, null, null, null);
        }
        ReportService.RangeTotals p = before.totals();
        BigDecimal previousNet = p.income().subtract(p.expense());
        BigDecimal previousAvg = previousMonthsWithData == 0 ? null
                : p.expense().divide(BigDecimal.valueOf(previousMonthsWithData), 2, RoundingMode.HALF_UP);
        BigDecimal previousRate = SavingsRate.of(p.income(), p.expense(), p.unresolvedInflow()).pct();
        Double avgDelta = (previousAvg == null || now.avgMonthlyExpense() == null) ? null
                : deltaPct(now.avgMonthlyExpense(), previousAvg);
        Double rateDelta = (previousRate == null || now.savingsRatePct() == null) ? null
                : now.savingsRatePct().subtract(previousRate).doubleValue();
        return new AdvancedComparison(true, null, p.income(), p.expense(), previousNet, previousAvg, previousRate,
                deltaPct(now.income(), p.income()), deltaPct(now.expense(), p.expense()),
                now.net().subtract(previousNet), avgDelta, rateDelta);
    }

    // ------------------------------------------------------------------ breakdowns

    private Map<String, BigDecimal> categorySpend(View v, Function<Transaction, String> nameOf) {
        return RefundNetting.withoutNegativeSpend(v.spendRows().stream().collect(Collectors.groupingBy(nameOf,
                Collectors.reducing(BigDecimal.ZERO, v.spend()::spendAmount, BigDecimal::add))));
    }

    /** One category's amount in each month, floored at zero as the category list is. */
    private List<BigDecimal> monthlyByKey(Map<YearMonth, List<Transaction>> rowsByMonth, List<YearMonth> monthKeys,
                                          RefundNetting refunds, FlowTotals.Context flow, String key,
                                          Function<Transaction, String> nameOf) {
        List<BigDecimal> out = new ArrayList<>();
        for (YearMonth m : monthKeys) {
            List<Transaction> monthRows = rowsByMonth.getOrDefault(m, List.of());
            RefundNetting spend = refunds.withUnlinkedOffsets(monthRows, flow);
            BigDecimal sum = RefundNetting.excludingInvestmentTransfers(monthRows).stream()
                    .filter(spend::countsAsSpend).filter(t -> nameOf.apply(t).equals(key))
                    .map(spend::spendAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(RefundNetting.floorAtZero(sum));
        }
        return out;
    }

    private AdvancedMerchants merchants(UUID userId, View now, View before, boolean reliable, RefundNetting refunds,
                                        Map<YearMonth, List<Transaction>> rowsByMonth, List<YearMonth> monthKeys,
                                        FlowTotals.Context flow, BigDecimal expense) {
        Map<UUID, String> storedNames = new HashMap<>();
        merchantRepository.findByUserId(userId).forEach(m -> storedNames.put(m.getId(), m.getCanonicalName()));

        Map<UUID, List<Transaction>> byMerchant = purchasesWithMerchant(now).stream()
                .collect(Collectors.groupingBy(Transaction::getMerchantId));
        Map<UUID, BigDecimal> amounts = new HashMap<>();
        byMerchant.forEach((id, list) -> amounts.put(id,
                list.stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        Map<UUID, BigDecimal> previous = new HashMap<>();
        if (reliable) {
            purchasesWithMerchant(before).stream().collect(Collectors.groupingBy(Transaction::getMerchantId))
                    .forEach((id, list) -> previous.put(id,
                            list.stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }

        List<UUID> ranked = amounts.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed().thenComparing(e -> e.getKey().toString()))
                .map(Map.Entry::getKey).toList();
        BigDecimal attributed = ranked.stream().map(amounts::get).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal topFive = ranked.stream().limit(5).map(amounts::get).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AdvancedMerchant> rows = ranked.stream().limit(TOP_ROWS).map(id -> {
            BigDecimal amount = amounts.get(id);
            BigDecimal prev = reliable ? previous.getOrDefault(id, BigDecimal.ZERO) : null;
            List<BigDecimal> monthly = new ArrayList<>();
            for (YearMonth m : monthKeys) {
                monthly.add(rowsByMonth.getOrDefault(m, List.of()).stream()
                        .filter(t -> id.equals(t.getMerchantId())
                                && t.getTxnType() == Transaction.Type.EXPENSE
                                && t.getReconciliationStatus() != Transaction.ReconciliationStatus.INVESTMENT_TRANSFER)
                        .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            return new AdvancedMerchant(id,
                    MerchantNames.forDisplay(storedNames.getOrDefault(id, "Unknown"),
                            byMerchant.get(id).stream().map(Transaction::getDescription).toList()),
                    amount, pct(amount, expense), byMerchant.get(id).size(), prev,
                    prev == null ? null : amount.subtract(prev), prev == null ? null : changePct(amount, prev), monthly);
        }).toList();
        return new AdvancedMerchants(rows, Math.min(100.0, pct(attributed, expense)), Math.min(100.0, pct(topFive, expense)),
                ranked.size());
    }

    /** Purchases (not refunds) that name a merchant, in the rows spending is read from. */
    private static List<Transaction> purchasesWithMerchant(View v) {
        return v.forTotals().stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && t.getMerchantId() != null).toList();
    }

    private AdvancedIncome income(View now, View before, boolean reliable, RefundNetting refunds,
                                  FlowTotals.Context flow, List<AdvancedMonth> months) {
        Map<String, BigDecimal> bySource = incomeBySource(now, refunds, flow);
        Map<String, BigDecimal> previous = reliable ? incomeBySource(before, refunds, flow) : null;
        BigDecimal total = bySource.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AdvancedIncomeSource> sources = bySource.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new AdvancedIncomeSource(e.getKey(), e.getValue(), pct(e.getValue(), total),
                        previous == null ? null : previous.getOrDefault(e.getKey(), BigDecimal.ZERO)))
                .toList();
        List<AdvancedMonth> withIncome = months.stream().filter(m -> m.income().signum() > 0).toList();
        List<AdvancedMonth> wholeWithIncome = withIncome.stream().filter(AdvancedMonth::complete).toList();
        AdvancedMonth high = null;
        AdvancedMonth low = null;
        if (wholeWithIncome.size() >= 2) {
            high = wholeWithIncome.stream().max(Comparator.comparing(AdvancedMonth::income)).orElseThrow();
            low = wholeWithIncome.stream().min(Comparator.comparing(AdvancedMonth::income)).orElseThrow();
        }
        return new AdvancedIncome(sources, withIncome.size(),
                high == null ? null : high.month(), high == null ? null : high.income(),
                low == null ? null : low.month(), low == null ? null : low.income(),
                high == null ? null : pct(low.income(), high.income()));
    }

    private Map<String, BigDecimal> incomeBySource(View v, RefundNetting refunds, FlowTotals.Context flow) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Transaction t : v.forTotals()) {
            if (!FlowTotals.countsAsIncome(t, flow)) continue;
            out.merge(FlowTotals.incomeLabel(t, flow), refunds.reportableAmount(t), BigDecimal::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ unusual purchases

    private List<AdvancedUnusualSpend> unusualSpends(UUID userId, List<UUID> liveAccountIds,
                                                     AdvancedPeriodResolver.Resolved period, View now,
                                                     RefundNetting refunds, FlowTotals.Context flow,
                                                     Function<Transaction, String> categoryName, BigDecimal expense) {
        if (expense.signum() <= 0) return List.of();
        YearMonth historyStart = period.start().minusMonths(UNUSUAL_HISTORY_MONTHS);
        List<Transaction> history = RefundNetting.excludingInvestmentTransfers(
                reportableRows(userId, liveAccountIds, historyStart.atDay(1), period.start().atDay(1).minusDays(1)));

        Map<String, List<Transaction>> pool = new HashMap<>();
        for (Transaction t : history) if (isPurchase(t, refunds)) pool.computeIfAbsent(categoryName.apply(t), k -> new ArrayList<>()).add(t);
        for (Transaction t : now.forTotals()) if (isPurchase(t, refunds)) pool.computeIfAbsent(categoryName.apply(t), k -> new ArrayList<>()).add(t);

        BigDecimal floor = expense.multiply(BigDecimal.valueOf(UNUSUAL_MIN_SHARE_PCT / 100.0));
        List<AdvancedUnusualSpend> out = new ArrayList<>();
        for (Transaction t : now.forTotals()) {
            if (!isPurchase(t, refunds)) continue;
            String category = categoryName.apply(t);
            if (UNCATEGORIZED.equals(category)) continue;
            BigDecimal amount = refunds.reportableAmount(t);
            if (amount.compareTo(floor) < 0) continue;
            List<BigDecimal> others = pool.getOrDefault(category, List.of()).stream()
                    .filter(o -> o != t).map(refunds::reportableAmount).sorted().toList();
            if (others.size() < UNUSUAL_MIN_SAMPLES) continue;
            BigDecimal typical = median(others);
            if (typical.signum() <= 0 || amount.compareTo(typical.multiply(UNUSUAL_MULTIPLE)) < 0) continue;
            out.add(new AdvancedUnusualSpend(t.getId(), t.getTxnDate(), ReportService.spendLabel(t), category, amount,
                    typical, amount.divide(typical, 1, RoundingMode.HALF_UP), others.size()));
        }
        return out.stream()
                .sorted(Comparator.comparing(AdvancedUnusualSpend::amount).reversed()
                        .thenComparing(AdvancedUnusualSpend::date, Comparator.reverseOrder()))
                .limit(MAX_UNUSUAL).toList();
    }

    private static boolean isPurchase(Transaction t, RefundNetting refunds) {
        return t.getTxnType() == Transaction.Type.EXPENSE && refunds.reportableAmount(t).signum() > 0;
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ insights

    private List<AdvancedInsight> insights(AdvancedPeriodResolver.Resolved period, AdvancedTotals totals,
                                           AdvancedComparison cmp, List<AdvancedMonth> months,
                                           List<AdvancedCategory> categories, AdvancedMerchants merchants,
                                           List<AdvancedUnusualSpend> unusual) {
        List<AdvancedInsight> out = new ArrayList<>();
        AdvancedDrill whole = new AdvancedDrill(period.start().atDay(1), period.end().atEndOfMonth(), null, null);
        String against = againstLabel(period);

        if (cmp.reliable() && cmp.expenseDeltaPct() != null && Math.abs(cmp.expenseDeltaPct()) >= CHANGE_MIN_PCT) {
            boolean up = cmp.expenseDeltaPct() > 0;
            BigDecimal diff = totals.expense().subtract(cmp.previousExpense());
            AdvancedCategory driver = categories.stream()
                    .filter(c -> c.changeAmount() != null && (up ? c.changeAmount().signum() > 0 : c.changeAmount().signum() < 0))
                    .max(Comparator.comparing(c -> c.changeAmount().abs())).orElse(null);
            String detail = "You spent " + Rupees.format(diff) + (up ? " more" : " less") + " than in " + against
                    + ": " + Rupees.format(totals.expense()) + " against " + Rupees.format(cmp.previousExpense()) + "."
                    + (driver == null ? "" : " The largest move was " + driver.name() + " ("
                    + (up ? "+" : "-") + Rupees.format(driver.changeAmount()) + ").");
            out.add(new AdvancedInsight("SPENDING_CHANGE", "SPENDING_CHANGE", up ? "UP" : "DOWN",
                    "Your spending is " + Math.round(Math.abs(cmp.expenseDeltaPct())) + "% " + (up ? "higher" : "lower"),
                    detail, whole));
        }

        if (cmp.reliable() && cmp.incomeDeltaPct() != null && Math.abs(cmp.incomeDeltaPct()) >= CHANGE_MIN_PCT) {
            boolean up = cmp.incomeDeltaPct() > 0;
            out.add(new AdvancedInsight("INCOME_CHANGE", "INCOME_CHANGE", up ? "UP" : "DOWN",
                    "Your income is " + Math.round(Math.abs(cmp.incomeDeltaPct())) + "% " + (up ? "higher" : "lower"),
                    "Income counted was " + Rupees.format(totals.income()) + " against " + Rupees.format(cmp.previousIncome())
                            + " in " + against + "."
                            + (totals.unresolvedInflow().signum() > 0
                            ? " " + Rupees.format(totals.unresolvedInflow()) + " of credits is not counted as income yet." : ""),
                    whole));
        }

        if (totals.savingsRatePct() != null) {
            int rate = (int) Math.round(totals.savingsRatePct().doubleValue());
            boolean negative = totals.savingsRatePct().signum() < 0;
            String tone = "NEUTRAL";
            String detail = "Income was " + Rupees.format(totals.income()) + " and spending " + Rupees.format(totals.expense()) + ".";
            if (cmp.reliable() && cmp.savingsRateDeltaPp() != null && Math.round(Math.abs(cmp.savingsRateDeltaPp())) >= 1) {
                boolean up = cmp.savingsRateDeltaPp() > 0;
                tone = up ? "UP" : "DOWN";
                detail += " That is " + Math.round(Math.abs(cmp.savingsRateDeltaPp())) + " points " + (up ? "higher" : "lower")
                        + " than in " + against + " (" + Math.round(cmp.previousSavingsRatePct().doubleValue()) + "%).";
            }
            out.add(new AdvancedInsight("SAVINGS_RATE", "SAVINGS_RATE", tone,
                    negative ? "You spent more than your income" : "You kept " + rate + "% of your income", detail, whole));
        }

        double expense = totals.expense().doubleValue();
        AdvancedCategory up = null;
        AdvancedCategory down = null;
        for (AdvancedCategory c : categories) {
            if (c.changeAmount() == null || expense <= 0) continue;
            double share = Math.abs(c.changeAmount().doubleValue()) / expense * 100;
            if (share < CATEGORY_CHANGE_MIN_SHARE_PCT) continue;
            boolean isNew = c.previousAmount().signum() == 0;
            boolean enoughMove = isNew || (c.changePct() != null && Math.abs(c.changePct()) >= CATEGORY_CHANGE_MIN_PCT);
            if (!enoughMove) continue;
            if (c.changeAmount().signum() > 0 && (up == null || c.changeAmount().compareTo(up.changeAmount()) > 0)) up = c;
            if (c.changeAmount().signum() < 0 && (down == null || c.changeAmount().compareTo(down.changeAmount()) < 0)) down = c;
        }
        if (up != null) out.add(categoryInsight(up, true, period, against));
        if (down != null) out.add(categoryInsight(down, false, period, against));

        List<AdvancedMonth> whole12 = months.stream().filter(m -> m.complete() && m.transactionCount() > 0 && m.expense().signum() > 0).toList();
        if (whole12.size() >= 3) {
            AdvancedMonth top = whole12.stream().max(Comparator.comparing(AdvancedMonth::expense)).orElseThrow();
            BigDecimal avg = whole12.stream().map(AdvancedMonth::expense).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(whole12.size()), 2, RoundingMode.HALF_UP);
            if (avg.signum() > 0 && top.expense().doubleValue() >= avg.doubleValue() * HIGHEST_MONTH_MIN_MULTIPLE) {
                YearMonth m = YearMonth.parse(top.month());
                long above = Math.round((top.expense().doubleValue() / avg.doubleValue() - 1) * 100);
                out.add(new AdvancedInsight("HIGHEST_MONTH", "HIGHEST_MONTH", "NEUTRAL",
                        m.format(MONTH_NAME) + " was your costliest month",
                        Rupees.format(top.expense()) + " was spent, " + above + "% above your monthly average of "
                                + Rupees.format(avg) + " over the whole months in this period.",
                        new AdvancedDrill(m.atDay(1), m.atEndOfMonth(), null, null)));
            }
        }

        if (merchants.merchantCount() >= CONCENTRATION_MIN_MERCHANTS
                && merchants.topFiveSharePct() >= CONCENTRATION_MIN_TOP_FIVE_PCT
                && merchants.attributedSharePct() >= CONCENTRATION_MIN_ATTRIBUTED_PCT) {
            AdvancedMerchant lead = merchants.rows().get(0);
            out.add(new AdvancedInsight("CONCENTRATION", "CONCENTRATION", "NEUTRAL",
                    "Five merchants take " + Math.round(merchants.topFiveSharePct()) + "% of your spending",
                    lead.name() + " is the largest at " + Rupees.format(lead.amount()) + ". "
                            + Math.round(merchants.attributedSharePct()) + "% of your spending is tied to a named merchant.",
                    new AdvancedDrill(period.start().atDay(1), period.end().atEndOfMonth(), null, lead.name())));
        }

        if (!unusual.isEmpty()) {
            AdvancedUnusualSpend top = unusual.get(0);
            out.add(new AdvancedInsight("UNUSUAL_SPEND", "UNUSUAL_SPEND", "NEUTRAL",
                    unusual.size() == 1 ? "1 purchase was larger than usual" : unusual.size() + " purchases were larger than usual",
                    top.label() + " was " + Rupees.format(top.amount()) + ", about " + top.timesTypical().stripTrailingZeros().toPlainString()
                            + " times the usual " + Rupees.format(top.typicalAmount()) + " for " + top.category() + ".",
                    new AdvancedDrill(period.start().atDay(1), period.end().atEndOfMonth(), top.category(), null)));
        }
        return out.size() > MAX_INSIGHTS ? List.copyOf(out.subList(0, MAX_INSIGHTS)) : out;
    }

    private AdvancedInsight categoryInsight(AdvancedCategory c, boolean up, AdvancedPeriodResolver.Resolved period,
                                            String against) {
        boolean isNew = c.previousAmount().signum() == 0;
        String headline = isNew ? c.name() + " is new" : c.name() + " is " + (up ? "higher" : "lower");
        String detail = isNew
                ? "You spent " + Rupees.format(c.amount()) + " on " + c.name() + ", with nothing in " + against + "."
                : up
                ? "You spent " + Rupees.format(c.amount()) + " on " + c.name() + ", " + Rupees.format(c.changeAmount()) + " ("
                    + Math.round(Math.abs(c.changePct())) + "%) more than in " + against + "."
                : "You spent " + Rupees.format(c.amount()) + " on " + c.name() + ", " + Rupees.format(c.changeAmount()) + " ("
                    + Math.round(Math.abs(c.changePct())) + "%) less than in " + against + ".";
        String kind = up ? "CATEGORY_UP" : "CATEGORY_DOWN";
        return new AdvancedInsight(kind, kind, up ? "UP" : "DOWN", headline, detail,
                new AdvancedDrill(period.start().atDay(1), period.end().atEndOfMonth(), c.name(), null));
    }

    private static String againstLabel(AdvancedPeriodResolver.Resolved period) {
        if (period.compare() == AdvancedCompare.SAME_PERIOD_LAST_YEAR) return "the same months a year earlier";
        return period.months() == 1 ? "the month before" : "the previous " + period.months() + " months";
    }

    // ------------------------------------------------------------------ small arithmetic

    private static double pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0) return 0;
        return part.doubleValue() / whole.doubleValue() * 100;
    }

    /** Percent change from {@code prior}; null when the prior is zero (a change from nothing has none). */
    private static Double changePct(BigDecimal current, BigDecimal prior) {
        return prior.signum() == 0 ? null : deltaPct(current, prior);
    }

    private static Double deltaPct(BigDecimal current, BigDecimal prior) {
        if (prior.signum() == 0) return null;
        return current.subtract(prior).divide(prior.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    // ------------------------------------------------------------------ nothing to show

    private AdvancedReportDto empty(AdvancedRange range, String reason, YearMonth first, YearMonth newest,
                                    String leftOutMonth, String leftOutReason, AdvancedPeriodResolver.Resolved period) {
        AdvancedPeriodInfo info = new AdvancedPeriodInfo(false, reason, range.name(),
                period == null ? null : period.start().toString(), period == null ? null : period.end().toString(), 0,
                period == null ? 0 : period.requestedMonths(), first == null ? null : first.toString(),
                newest == null ? null : newest.toString(), leftOutMonth, leftOutReason, List.of(), null, List.of(), null, null);
        AdvancedTotals totals = new AdvancedTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, null,
                SavingsRate.NO_INCOME, BigDecimal.ZERO, 0, 0, null, null);
        AdvancedComparison comparison = new AdvancedComparison(false, "NO_TRANSACTION_HISTORY", null, null, null, null,
                null, null, null, null, null, null);
        return new AdvancedReportDto(info, totals, comparison, List.of(),
                new AdvancedCategories(List.of(), BigDecimal.ZERO, 0, 0),
                new AdvancedMerchants(List.of(), 0, 0, 0), List.of(),
                new AdvancedIncome(List.of(), 0, null, null, null, null, null), List.of(),
                new AnalyticsDto.InternationalSpend(BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of()),
                List.of());
    }
}
