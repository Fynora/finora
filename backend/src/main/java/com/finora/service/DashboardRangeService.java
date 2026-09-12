package com.finora.service;

import com.finora.dto.DashboardRangeSummaryDto;
import com.finora.dto.DashboardRangeType;
import com.finora.entity.Account;
import com.finora.entity.NetWorthSnapshot;
import com.finora.repository.AccountRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Range-based counterpart to {@link DashboardService}'s single-reporting-month KPIs -- backs the
 * Dashboard's unified date-range picker (3/6/12/24 months, or a custom {@code [start, end]}).
 *
 * <p>Deliberately a separate service rather than folded into {@code DashboardService.summarize()}:
 * the Financial Health Score, category-review warning, categorization confidence and
 * notifications all stay anchored to the single reporting month (see {@code ReportingPeriod}) --
 * none of those were part of this range feature, and mixing two different period models into one
 * method is exactly the kind of "two screens disagreeing about the same number" ReportingPeriod's
 * own doc comment already catalogs once (Bug 05/06). Keeping this additive means
 * {@code summarize()} and every figure it returns is completely unchanged by this class existing.
 */
@Service
public class DashboardRangeService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final ReportService reportService;

    public DashboardRangeService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                                  UserRepository userRepository, NetWorthSnapshotRepository netWorthSnapshotRepository,
                                  ReportService reportService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.reportService = reportService;
    }

    // Mirrors DashboardService.MIN_TRANSACTIONS_FOR_DELTA_COMPARISON's own reasoning: low enough
    // that a real, quiet previous period still compares, high enough that one or two stray rows
    // can't produce a triple-digit swing on their own. Kept as its own constant (not a shared
    // import) since the two floors are allowed to diverge independently -- one governs a single
    // calendar month, the other an arbitrary range.
    static final int MIN_TRANSACTIONS_FOR_RANGE_COMPARISON = 3;

    @Transactional(readOnly = true)
    public DashboardRangeSummaryDto summarize(UUID userId, DashboardRangeType rangeType,
                                               LocalDate customStart, LocalDate customEnd) {
        ZoneId zone = com.finora.util.UserZone.forUser(userRepository, userId);
        List<Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(Account::getId).toList();
        LocalDate earliestTxnDate = liveAccountIds.isEmpty() ? null
                : transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        LocalDate latestTxnDate = liveAccountIds.isEmpty() ? null
                : transactionRepository.findLatestTxnDate(userId, liveAccountIds);

        LocalDate start;
        LocalDate end;
        LocalDate prevStart;
        LocalDate prevEnd;

        if (rangeType == DashboardRangeType.CUSTOM) {
            if (customStart == null || customEnd == null) {
                throw new IllegalArgumentException("startDate and endDate are required for a CUSTOM range");
            }
            if (customStart.isAfter(customEnd)) {
                throw new IllegalArgumentException("startDate must not be after endDate");
            }
            start = customStart;
            end = customEnd;
            // Previous period: the same DAY COUNT immediately preceding `start` -- not a
            // calendar-aligned shift. A custom range has no natural "month" to shift by; day
            // count is the only unit an arbitrary [start, end] can be compared against
            // consistently at any length, which is what makes a preset's own previous-period math
            // (a calendar-month shift) the wrong model to reuse here.
            long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
            prevEnd = start.minusDays(1);
            prevStart = prevEnd.minusDays(dayCount - 1);
        } else {
            // Anchor = the newest month with data, mirroring ReportingPeriod's reporting-month
            // philosophy for the single-month KPIs: an empty "last 6 months ending today" is a
            // worse answer than the same window ending at the user's last real import. Falls back
            // to the real calendar month when the account has no data at all, same as
            // ReportingPeriod.resolve's own empty-account case.
            YearMonth anchorMonth = latestTxnDate != null ? YearMonth.from(latestTxnDate) : YearMonth.now(zone);
            YearMonth startMonth = anchorMonth.minusMonths(rangeType.months() - 1L);
            start = startMonth.atDay(1);
            end = anchorMonth.atEndOfMonth();
            YearMonth prevEndMonth = startMonth.minusMonths(1);
            YearMonth prevStartMonth = prevEndMonth.minusMonths(rangeType.months() - 1L);
            prevStart = prevStartMonth.atDay(1);
            prevEnd = prevEndMonth.atEndOfMonth();
        }

        ReportService.RangeTotals current = reportService.forRange(userId, start, end);
        ReportService.RangeTotals previous = reportService.forRange(userId, prevStart, prevEnd);

        BigDecimal incomeTotal = current.income();
        BigDecimal expenseTotal = current.expense();
        BigDecimal netSavingsTotal = incomeTotal.subtract(expenseTotal);
        BigDecimal savingsRatePct = incomeTotal.compareTo(BigDecimal.ZERO) > 0
                ? netSavingsTotal.divide(incomeTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        BigDecimal netPrevious = previous.income().subtract(previous.expense());

        String comparisonGateReason = rangeComparisonGateReason(earliestTxnDate, prevStart, previous.transactionCount());
        boolean comparisonReliable = comparisonGateReason == null;
        Double incomeDeltaPct = pct(incomeTotal, previous.income(), comparisonReliable);
        Double expenseDeltaPct = pct(expenseTotal, previous.expense(), comparisonReliable);
        Double netDeltaPct = pct(netSavingsTotal, netPrevious, comparisonReliable);

        LocalDate today = LocalDate.now(zone);
        Optional<NetWorthSnapshot> currentSnapshot =
                netWorthSnapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(userId, end);
        BigDecimal currentBalance;
        LocalDate currentBalanceAsOf;
        String currentBalanceGateReason;
        if (currentSnapshot.isPresent()) {
            currentBalance = currentSnapshot.get().getNetWorth();
            currentBalanceAsOf = currentSnapshot.get().getSnapshotDate();
            currentBalanceGateReason = null;
        } else if (!end.isBefore(today)) {
            // No snapshot yet (sweep hasn't run for this user, or they've never saved one) and the
            // range's end is today or later -- the LIVE account balance IS the right answer here,
            // not a missing figure. Only ever a fallback for the CURRENT period; a genuinely
            // historical previousEnd with no snapshot has no live equivalent to fall back to.
            currentBalance = netWorthOf(accounts);
            currentBalanceAsOf = today;
            currentBalanceGateReason = null;
        } else {
            // endDate is in the past and no snapshot reaches back that far -- there is no real
            // figure to report. Bug fix: this used to default to BigDecimal.ZERO here, which reads
            // as "the balance genuinely was zero on that date" -- a fabricated number, not an
            // honest "we don't know." Null (with a gate reason, same pattern as previousBalance
            // below) is the correct answer, not a guessed one.
            currentBalance = null;
            currentBalanceAsOf = null;
            currentBalanceGateReason = "NO_SNAPSHOT_AT_OR_BEFORE_DATE";
        }

        Optional<NetWorthSnapshot> previousSnapshot =
                netWorthSnapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(userId, prevEnd);
        BigDecimal previousBalance = previousSnapshot.map(NetWorthSnapshot::getNetWorth).orElse(null);
        LocalDate previousBalanceAsOf = previousSnapshot.map(NetWorthSnapshot::getSnapshotDate).orElse(null);
        // Balance's own gate, independent of comparisonGateReason above: a snapshot can be missing
        // for a period whose transaction history is otherwise perfectly adequate for comparison
        // (NetWorthSnapshot rows only exist from whenever this user first saved one, or the sweep
        // started covering them) -- tying the two gates together would either hide a good
        // income/expense comparison behind a missing snapshot, or the reverse.
        String balanceGateReason = previousBalance == null ? "NO_SNAPSHOT_AT_PRIOR_DATE" : null;
        // pct() itself only guards a null/zero PRIOR -- currentBalance can now also be null (see
        // above), which pct() was never written to expect, so that case is short-circuited here
        // rather than risking a NullPointerException inside BigDecimal.subtract.
        Double balanceDeltaPct = currentBalance == null ? null : pct(currentBalance, previousBalance, true);

        return new DashboardRangeSummaryDto(
                rangeType.name(), start, end, prevStart, prevEnd,
                incomeTotal, expenseTotal, netSavingsTotal, savingsRatePct,
                incomeDeltaPct, expenseDeltaPct, netDeltaPct,
                comparisonGateReason, MIN_TRANSACTIONS_FOR_RANGE_COMPARISON,
                currentBalance, currentBalanceAsOf, currentBalanceGateReason,
                previousBalance, previousBalanceAsOf, balanceDeltaPct, balanceGateReason
        );
    }

    /** Why the previous period isn't trustworthy as a comparison baseline, or null when it is --
     *  mirrors {@code DashboardService.priorMonthGateReason}'s two checks, generalized from a
     *  single calendar month to an arbitrary range: the previous window must lie entirely within
     *  the account's own transaction history (not reach back before it existed), and must carry
     *  at least {@link #MIN_TRANSACTIONS_FOR_RANGE_COMPARISON} transactions of its own. */
    private String rangeComparisonGateReason(LocalDate earliestTxnDate, LocalDate prevStart, int prevTransactionCount) {
        if (earliestTxnDate == null) return "NO_TRANSACTION_HISTORY";
        if (prevStart.isBefore(earliestTxnDate)) return "PRIOR_PERIOD_BEFORE_HISTORY";
        return prevTransactionCount < MIN_TRANSACTIONS_FOR_RANGE_COMPARISON ? "TOO_FEW_PRIOR_TRANSACTIONS" : null;
    }

    private Double pct(BigDecimal current, BigDecimal prior, boolean reliable) {
        if (!reliable || prior == null || prior.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(prior).divide(prior.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    /** Same definition {@code NetWorthService}/{@code DashboardService} already use -- see
     *  {@code AccountBalanceConvention}. */
    private BigDecimal netWorthOf(List<Account> accounts) {
        return accounts.stream()
                .map(a -> com.finora.accounts.AccountBalanceConvention.netWorthContribution(a.getAccountType(), a.getBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
