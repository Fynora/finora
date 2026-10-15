package com.finora.service;

import com.finora.dto.ReportDto;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionGraphService transactionGraphService;

    /** Built into every FlowTotals.Context here -- the user's inflow kinds (Plan 2). */
    private final InflowChoiceService inflowChoices;

    /** Which months overlap a hole between two imported statements -- see MonthComparisonGate. */
    private final AccountCoverageService accountCoverageService;

    public ReportService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                          CategoryRepository categoryRepository, TransactionGraphService transactionGraphService,
                         InflowChoiceService inflowChoices, AccountCoverageService accountCoverageService) {
        this.inflowChoices = inflowChoices;
        this.accountCoverageService = accountCoverageService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.transactionGraphService = transactionGraphService;
    }

    @Transactional(readOnly = true)
    public ReportDto forMonth(UUID userId, String monthStr) {
        YearMonth month = YearMonth.parse(monthStr);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // BH-005. This was a hand-written copy of DashboardService's filter, and both copies were
        // one-sided: the refund's INCOME leg was dropped and the EXPENSE it reverses was left
        // counted in full, so a refunded purchase reported as a pure loss. RefundNetting owns the
        // rule for both readers now -- it drops the income leg AND nets the refund off the
        // purchase, which is the only treatment also correct for a partial refund.
        //
        // The refund legs come from a SEPARATE query, not from the month window. A refund
        // routinely arrives in a later month than its purchase, so the rows that offset this
        // month's expenses are frequently outside the range this month was queried with -- netting
        // against only the in-window ones would leave every cross-month refund uncorrected, which
        // is most of them.
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so findByUserId-rooted queries
        // alone would keep feeding this report a deleted account's rows forever, not just during
        // StatementImportService's 7-day grace window.
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoriesById.values());
        RefundNetting refunds = liveAccountIds.isEmpty() ? RefundNetting.from(List.of())
                : RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                        userId, java.util.List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL),
                        liveAccountIds));
        List<Transaction> monthTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        List<Transaction> txns = RefundNetting.reportable(
                monthTxns, transactionGraphService.ccPaymentFromTransactionIds(monthTxns));
        // Narrower than `txns` -- see RefundNetting.excludingInvestmentTransfers's own comment.
        // Only the cross-category income/expense totals below use this; `byCategory` keeps reading
        // `txns` so an Investments line still shows up in the report's own category table.
        List<Transaction> txnsForTotals = RefundNetting.excludingInvestmentTransfers(txns);
        // Spend netting: linked refunds against their purchases, unlinked ones in this period.
        RefundNetting spend = refunds.withUnlinkedOffsets(txns, flow);

        // Only flow-classified income: a credit from a person, a card credit, an investment
        // redemption or a loan disbursal is money in, not income. See FlowClassifier.
        BigDecimal income = txnsForTotals.stream().filter(t -> FlowTotals.countsAsIncome(t, flow))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Sum of the categories, each floored -- see RefundNetting.spendTotal.
        BigDecimal expense = spend.spendTotal(txnsForTotals);

        Map<String, BigDecimal> byCategory = RefundNetting.withoutNegativeSpend(txns.stream()
                .filter(spend::countsAsSpend)
                .collect(Collectors.groupingBy(
                        t -> categoriesById.containsKey(t.getCategoryId()) ? categoriesById.get(t.getCategoryId()).getName() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, spend::spendAmount, BigDecimal::add))));

        List<ReportDto.CategoryAmount> categories = byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.CategoryAmount(e.getKey(), e.getValue()))
                .toList();

        Map<String, BigDecimal> incomeByLabel = new java.util.LinkedHashMap<>();
        for (Transaction t : txnsForTotals) {
            if (!FlowTotals.countsAsIncome(t, flow)) continue;
            incomeByLabel.merge(FlowTotals.incomeLabel(t, flow), refunds.reportableAmount(t), BigDecimal::add);
        }
        List<ReportDto.IncomeLine> incomeByKind = incomeByLabel.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.IncomeLine(e.getKey(), e.getValue()))
                .toList();

        ReportDto.MonthComparison comparison = liveAccountIds.isEmpty() ? null
                : comparisonFor(userId, month, income, expense, txnsForTotals.size(), liveAccountIds);

        return new ReportDto(monthStr, income, expense, categories,
                FlowTotals.unresolvedInflow(txnsForTotals, flow), incomeByKind,
                txnsForTotals.size(), FlowTotals.unresolvedInflowCount(txnsForTotals, flow), comparison);
    }

    /**
     * This month against the calendar month before it. The prior month's figures come from
     * {@link #forRange}, the same refund-netted, deleted-account-safe computation the selected
     * month's own totals use, so the two sides of the comparison are built identically. Whether the
     * comparison is trustworthy is decided by {@link MonthComparisonGate}, the same rule the
     * Dashboard applies -- this method only gathers its inputs and does the arithmetic.
     */
    private ReportDto.MonthComparison comparisonFor(UUID userId, YearMonth month, BigDecimal income, BigDecimal expense,
                                               int transactionCount, List<UUID> liveAccountIds) {
        YearMonth prior = month.minusMonths(1);
        RangeTotals previous = forRange(userId, prior.atDay(1), prior.atEndOfMonth());
        LocalDate earliest = transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        LocalDate latest = transactionRepository.findLatestTxnDate(userId, liveAccountIds);
        boolean awaiting = latest != null && awaitingStatement(userId, month, YearMonth.from(latest),
                accountCoverageService.latestStatementEnds(userId));
        String reason = MonthComparisonGate.reason(month, prior, earliest, latest,
                accountCoverageService.gapsForUser(userId), awaiting,
                transactionCount, previous.transactionCount());
        if (reason != null) {
            return new ReportDto.MonthComparison(prior.toString(), false, reason, null, null, null, null, null);
        }
        BigDecimal net = income.subtract(expense);
        BigDecimal previousNet = previous.income().subtract(previous.expense());
        return new ReportDto.MonthComparison(prior.toString(), true, null, previous.income(), previous.expense(),
                deltaPct(income, previous.income()), deltaPct(expense, previous.expense()),
                net.subtract(previousNet));
    }

    /**
     * See {@link MonthComparisonGate#awaitingStatement}. The queries run only when there is a
     * statement-backed account to judge and the month is recent enough to be judged at all, so a
     * user with no statement imports, or looking at an old month, pays for neither.
     */
    private boolean awaitingStatement(UUID userId, YearMonth month, YearMonth newestDataMonth,
                                      Map<UUID, LocalDate> latestStatementEnds) {
        if (latestStatementEnds.isEmpty()
                || month.isBefore(newestDataMonth.minusMonths(MonthComparisonGate.RECENT_MONTHS - 1L))) {
            return false;
        }
        java.util.Set<UUID> active = new java.util.HashSet<>(transactionRepository.findAccountIdsWithTransactionsBetween(
                userId, latestStatementEnds.keySet(), month.minusMonths(1).atDay(1), month.atEndOfMonth()));
        return MonthComparisonGate.awaitingStatement(month, newestDataMonth, latestStatementEnds, active);
    }

    /** Percent change from {@code prior} to {@code current}, null when the prior is zero (a change
     *  from nothing has no percentage). Same arithmetic as DashboardRangeService's pct. */
    private static Double deltaPct(BigDecimal current, BigDecimal prior) {
        if (prior.signum() == 0) return null;
        return current.subtract(prior).divide(prior.abs(), 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private static final int TREND_MAX_MONTHS = 12;

    /**
     * Income and expense for each of the last {@code months} calendar months up to {@code anchor}
     * (the newest month with data when null), oldest first. Every point is {@link #forRange} for
     * that month, so a point equals the totals the report itself shows for the same month.
     *
     * <p>No months before the user's history began, and none after their last transaction: a new
     * account with two months of data gets two points, never fabricated zeros. A month inside the
     * history with no transactions is a real zero, but is marked incomplete when it, or a statement
     * gap, makes that figure a partial picture -- see {@link ReportTrendPoint#complete}.
     */
    @Transactional(readOnly = true)
    public List<ReportTrendPoint> trend(UUID userId, YearMonth anchor, int months) {
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        if (liveAccountIds.isEmpty()) return List.of();
        LocalDate earliest = transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        LocalDate latest = transactionRepository.findLatestTxnDate(userId, liveAccountIds);
        if (earliest == null || latest == null) return List.of();

        int window = Math.max(1, Math.min(months, TREND_MAX_MONTHS));
        YearMonth newest = YearMonth.from(latest);
        YearMonth end = anchor == null || anchor.isAfter(newest) ? newest : anchor;
        YearMonth first = YearMonth.from(earliest);
        YearMonth start = end.minusMonths(window - 1L);
        if (start.isBefore(first)) start = first;
        if (start.isAfter(end)) return List.of();

        List<AccountCoverageService.DateRange> gaps = accountCoverageService.gapsForUser(userId);
        Map<UUID, LocalDate> statementEnds = accountCoverageService.latestStatementEnds(userId);
        java.util.ArrayList<ReportTrendPoint> points = new java.util.ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            RangeTotals totals = forRange(userId, m.atDay(1), m.atEndOfMonth());
            boolean complete = !MonthComparisonGate.isPartialBoundaryMonth(m.toString(), earliest, latest)
                    && !MonthComparisonGate.overlapsGap(m, gaps)
                    && !awaitingStatement(userId, m, newest, statementEnds);
            points.add(new ReportTrendPoint(m.toString(), totals.income(), totals.expense(),
                    totals.transactionCount(), complete));
        }
        return points;
    }

    /** Named for the report, not just "TrendPoint": Analytics already has a record of that name, and
     *  OpenAPI schemas are keyed by simple name, so a second one silently replaces the first in the
     *  generated spec and the client types.
     *
     *  @param complete false when the month is the ragged edge of the user's imported history,
     *                  shares a day with a missing-statement gap, or is a recent month an account's
     *                  latest statement does not yet reach -- its figures describe only part of the
     *                  month. */
    public record ReportTrendPoint(String month, BigDecimal income, BigDecimal expense,
                                   int transactionCount, boolean complete) {}

    /**
     * Income/expense totals for an arbitrary date range -- the same refund-netted,
     * deleted-account-safe computation {@link #forMonth} uses, just parameterized by an explicit
     * {@code [from, to]} rather than a calendar month's own boundaries. Backs
     * {@code DashboardRangeService}'s range-based KPI cards; deliberately does not also return a
     * category breakdown the way {@link #forMonth} does -- category-level figures stay tied to
     * the single reporting month (see {@code ReportingPeriod}), which this method's callers never
     * asked to change.
     */
    @Transactional(readOnly = true)
    public RangeTotals forRange(UUID userId, LocalDate from, LocalDate to) {
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoryRepository.findByUserId(userId));
        RefundNetting refunds = liveAccountIds.isEmpty() ? RefundNetting.from(List.of())
                : RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                        userId, List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL),
                        liveAccountIds));
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        List<Transaction> txns = RefundNetting.reportable(
                rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns));
        List<Transaction> txnsForTotals = RefundNetting.excludingInvestmentTransfers(txns);
        // Spend netting: linked refunds against their purchases, unlinked ones in this period.
        RefundNetting spend = refunds.withUnlinkedOffsets(txns, flow);

        // Only flow-classified income: a credit from a person, a card credit, an investment
        // redemption or a loan disbursal is money in, not income. See FlowClassifier.
        BigDecimal income = txnsForTotals.stream().filter(t -> FlowTotals.countsAsIncome(t, flow))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Sum of the categories, each floored -- see RefundNetting.spendTotal.
        BigDecimal expense = spend.spendTotal(txnsForTotals);
        FlowClassifier.FlowReason topReason = FlowTotals.unresolvedTopReason(txnsForTotals, flow);
        return new RangeTotals(income, expense, txnsForTotals.size(),
                FlowTotals.unresolvedInflow(txnsForTotals, flow),
                FlowTotals.unresolvedInflowCount(txnsForTotals, flow),
                topReason == null ? null : topReason.name());
    }

    /** The rows forRange counts as unresolved, built the same way, so the review list and the
     *  banner can never disagree. {@code accounts} is the user's live accounts, for display. */
    public record UnresolvedRows(List<Transaction> rows, List<com.finora.entity.Account> accounts) {}

    @Transactional(readOnly = true)
    public UnresolvedRows unresolvedInflows(UUID userId, LocalDate from, LocalDate to) {
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoryRepository.findByUserId(userId));
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        List<Transaction> txnsForTotals = RefundNetting.excludingInvestmentTransfers(RefundNetting.reportable(
                rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns)));
        return new UnresolvedRows(txnsForTotals.stream().filter(t -> FlowTotals.isUnresolvedInflow(t, flow)).toList(), accounts);
    }

    /** @param transactionCount how many (refund-netted, transfer-excluded) transactions the totals
     *                          above were built from -- DashboardRangeService's comparison gating
     *                          needs this to decide whether a period is thin enough that a stray
     *                          row or two could dominate its own delta.
     *  @param unresolvedInflow credits in the range Fynora cannot yet call income (see FlowTotals);
     *                          never part of {@code income}. {@code unresolvedTopReason} is the
     *                          FlowReason name carrying most of it, null when there is none. */
    public record RangeTotals(BigDecimal income, BigDecimal expense, int transactionCount,
                              BigDecimal unresolvedInflow, int unresolvedInflowCount, String unresolvedTopReason) {

        /** Totals with nothing unresolved -- for callers that only have income and expense. */
        public RangeTotals(BigDecimal income, BigDecimal expense, int transactionCount) {
            this(income, expense, transactionCount, BigDecimal.ZERO, 0, null);
        }
    }

    /**
     * Which months have at least one transaction — backs the Reports page's month dropdown.
     *
     * <p>BH-042: this used to load the user's ENTIRE transaction history as JPA entities, map each
     * row to a {@code YearMonth}, and throw away everything but the distinct values. The result is
     * a dozen strings; the query was proportional to the whole ledger, on a page load. Distinct
     * dates come from the database now, and only the month projection happens here.
     *
     * <p>Distinct DATES rather than distinct months, because {@code date_trunc} has no portable
     * JPQL form and pushing a native query down for this would trade one small cost for a
     * dialect lock-in. The row count is bounded by days-with-activity, not by transactions.
     */
    @Transactional(readOnly = true)
    public List<String> availableMonths(UUID userId) {
        // Deleted-account leak: see forMonth() above for why findByUserId-rooted queries alone are
        // wrong here -- a deleted account's transaction dates would otherwise keep populating this
        // dropdown forever.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        if (liveAccountIds.isEmpty()) return List.of();
        return transactionRepository.findDistinctTransactionDates(userId, liveAccountIds).stream()
                .map(date -> YearMonth.from(date).toString())
                .distinct().sorted().toList();
    }

    private static final int INCOME_TREND_MONTHS = 6;

    /**
     * The mobile Insights screen's Income tab trend chart -- one point per month for the last
     * {@value #INCOME_TREND_MONTHS} months that actually have transaction history. Built on
     * {@link #availableMonths} and {@link #forRange} rather than a fresh query: both already carry
     * the refund-netting and deleted-account-safety this needs, so there's nothing left to
     * reimplement here beyond picking the window and reshaping the result.
     *
     * <p>No zero-padding when fewer than {@value #INCOME_TREND_MONTHS} months of history exist --
     * a new account with 2 months of data gets a 2-point chart, not 4 fabricated zero months
     * before it existed.
     */
    @Transactional(readOnly = true)
    public List<IncomeTrendPoint> incomeTrend(UUID userId) {
        List<String> months = availableMonths(userId);
        List<String> window = months.size() > INCOME_TREND_MONTHS
                ? months.subList(months.size() - INCOME_TREND_MONTHS, months.size())
                : months;

        return window.stream()
                .map(month -> {
                    YearMonth ym = YearMonth.parse(month);
                    RangeTotals totals = forRange(userId, ym.atDay(1), ym.atEndOfMonth());
                    return new IncomeTrendPoint(month, totals.income());
                })
                .toList();
    }

    public record IncomeTrendPoint(String month, BigDecimal income) {}
}
