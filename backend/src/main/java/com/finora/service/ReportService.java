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

    public ReportService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                          CategoryRepository categoryRepository, TransactionGraphService transactionGraphService) {
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
        FlowTotals.Context flow = FlowTotals.context(accounts, categoriesById.values());
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

        // Only flow-classified income: a credit from a person, a card credit, an investment
        // redemption or a loan disbursal is money in, not income. See FlowClassifier.
        BigDecimal income = txnsForTotals.stream().filter(t -> FlowTotals.countsAsIncome(t, flow))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = txnsForTotals.stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> byCategory = txns.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .collect(Collectors.groupingBy(
                        t -> categoriesById.containsKey(t.getCategoryId()) ? categoriesById.get(t.getCategoryId()).getName() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, refunds::reportableAmount, BigDecimal::add)));

        List<ReportDto.CategoryAmount> categories = byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.CategoryAmount(e.getKey(), e.getValue()))
                .toList();

        return new ReportDto(monthStr, income, expense, categories,
                FlowTotals.unresolvedInflow(txnsForTotals, flow));
    }

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
        FlowTotals.Context flow = FlowTotals.context(accounts, categoryRepository.findByUserId(userId));
        RefundNetting refunds = liveAccountIds.isEmpty() ? RefundNetting.from(List.of())
                : RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                        userId, List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL),
                        liveAccountIds));
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        List<Transaction> txns = RefundNetting.reportable(
                rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns));
        List<Transaction> txnsForTotals = RefundNetting.excludingInvestmentTransfers(txns);

        // Only flow-classified income: a credit from a person, a card credit, an investment
        // redemption or a loan disbursal is money in, not income. See FlowClassifier.
        BigDecimal income = txnsForTotals.stream().filter(t -> FlowTotals.countsAsIncome(t, flow))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = txnsForTotals.stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        FlowClassifier.FlowReason topReason = FlowTotals.unresolvedTopReason(txnsForTotals, flow);
        return new RangeTotals(income, expense, txnsForTotals.size(),
                FlowTotals.unresolvedInflow(txnsForTotals, flow),
                FlowTotals.unresolvedInflowCount(txnsForTotals, flow),
                topReason == null ? null : topReason.name());
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
