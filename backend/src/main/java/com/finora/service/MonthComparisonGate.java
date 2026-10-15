package com.finora.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * When a month-over-month comparison can be trusted. One definition for every reader, so the
 * Dashboard and the Reports page cannot disagree about which months are comparable.
 *
 * <p>The partial-boundary rule and the transaction floor were written for the Dashboard
 * ({@code DashboardService}); they live here now and the Dashboard delegates to them unchanged.
 *
 * <p><b>Per-account coverage.</b> The partial-boundary rule judges the user's newest and oldest
 * transaction across every account together, and the gap rule sees holes between two statements of
 * one account. Neither sees an account whose statements simply stop earlier than the others: a card
 * cycle ending on the 11th while a bank account runs to month-end makes September look complete
 * while the card's last weeks are missing. {@link #awaitingStatement} covers that, for statement
 * imported accounts only (an Account Aggregator account has no "statement reaches until" date).
 *
 * <p>An account the user has stopped using would, judged naively, block every comparison forever.
 * Two limits prevent it: only an account that was <i>active</i> (had a transaction) in the month or
 * the month before counts, and only the newest {@value #RECENT_MONTHS} months are judged at all. A
 * closed card therefore holds comparisons back for about two months after its last statement, then
 * stops mattering. The cost is the reverse: a month older than that is never flagged for a missed
 * import, which is the gap rule's job. The Dashboard does not apply this rule.
 */
final class MonthComparisonGate {

    private MonthComparisonGate() {}

    /** A month must carry at least this many transactions of its own to be a delta's denominator:
     *  low enough that a real, quiet month still compares, high enough that one or two stray rows
     *  cannot produce a triple-digit swing on their own. */
    static final int MIN_TRANSACTIONS = 3;

    static final String NO_TRANSACTION_HISTORY = "NO_TRANSACTION_HISTORY";
    static final String NO_TRANSACTIONS_IN_MONTH = "NO_TRANSACTIONS_IN_MONTH";
    static final String PRIOR_BEFORE_HISTORY = "PRIOR_BEFORE_HISTORY";
    static final String SELECTED_MONTH_PARTIAL = "SELECTED_MONTH_PARTIAL";
    static final String PARTIAL_PRIOR_MONTH = "PARTIAL_PRIOR_MONTH";
    static final String COVERAGE_GAP = "COVERAGE_GAP";
    static final String ACCOUNT_STATEMENT_BEHIND = "ACCOUNT_STATEMENT_BEHIND";
    static final String TOO_FEW_PRIOR_TRANSACTIONS = "TOO_FEW_PRIOR_TRANSACTIONS";

    /** How many of the newest months {@link #awaitingStatement} judges: the newest month with data
     *  and the one before it. */
    static final int RECENT_MONTHS = 2;

    /** True when {@code month} is the ragged near or far edge of the user's whole imported date
     *  range: the calendar-month bucket holding {@code earliest} but not starting on the 1st, or
     *  the bucket holding {@code latest} but not running through month-end. Such a bucket is a
     *  partial slice of a continuous statement window, not a genuine full month. */
    static boolean isPartialBoundaryMonth(String month, LocalDate earliest, LocalDate latest) {
        boolean isEarliestBucket = earliest != null && month.equals(YearMonth.from(earliest).toString());
        boolean isLatestBucket = latest != null && month.equals(YearMonth.from(latest).toString());
        if (isEarliestBucket && earliest.getDayOfMonth() != 1) return true;
        if (isLatestBucket && latest.getDayOfMonth() != YearMonth.from(latest).lengthOfMonth()) return true;
        return false;
    }

    /** Whether any coverage gap (a hole between two of the user's own imported statements)
     *  shares a calendar day with {@code month}. Gap bounds are inclusive. */
    static boolean overlapsGap(YearMonth month, List<AccountCoverageService.DateRange> gaps) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        return gaps.stream().anyMatch(g -> !g.end().isBefore(start) && !g.start().isAfter(end));
    }

    /**
     * Whether an account's statements stop short of {@code month}, so the month is missing that
     * account's last weeks (or all of it). True when, among the newest {@value #RECENT_MONTHS}
     * months, some account that was active in {@code month} or the month before has a latest
     * statement ending before {@code month} does.
     *
     * @param newestDataMonth   the month of the user's newest transaction
     * @param latestStatementEnd statement-backed accounts only, mapped to their newest period end
     * @param activeAccountIds  accounts with a transaction in {@code month} or the month before it
     */
    static boolean awaitingStatement(YearMonth month, YearMonth newestDataMonth,
                                     Map<UUID, LocalDate> latestStatementEnd, Set<UUID> activeAccountIds) {
        return !behindAccountIds(month, newestDataMonth, latestStatementEnd, activeAccountIds).isEmpty();
    }

    /** The accounts {@link #awaitingStatement} is about: active ones whose latest statement ends
     *  before {@code month} does, empty when the month is too old to be judged. */
    static List<UUID> behindAccountIds(YearMonth month, YearMonth newestDataMonth,
                                       Map<UUID, LocalDate> latestStatementEnd, Set<UUID> activeAccountIds) {
        if (month.isBefore(newestDataMonth.minusMonths(RECENT_MONTHS - 1L))) return List.of();
        LocalDate monthEnd = month.atEndOfMonth();
        return activeAccountIds.stream().filter(id -> {
            LocalDate statementEnd = latestStatementEnd.get(id);
            return statementEnd != null && statementEnd.isBefore(monthEnd);
        }).toList();
    }

    /**
     * Why {@code selected} cannot be compared with {@code prior}, or null when it can. Checked in
     * the order a reader would want explained: no data at all, then whether the months exist, then
     * whether either is a partial slice, then whether a statement is missing, then whether the
     * prior month is too thin to be a baseline.
     *
     * @param selectedTransactionCount transactions the selected month's totals were built from
     * @param selectedAwaitingStatement {@link #awaitingStatement} for the selected month
     * @param priorTransactionCount    the same count, for the prior month
     */
    static String reason(YearMonth selected, YearMonth prior, LocalDate earliest, LocalDate latest,
                         List<AccountCoverageService.DateRange> gaps, boolean selectedAwaitingStatement,
                         int selectedTransactionCount, int priorTransactionCount) {
        if (earliest == null || latest == null) return NO_TRANSACTION_HISTORY;
        if (selectedTransactionCount == 0) return NO_TRANSACTIONS_IN_MONTH;
        if (prior.atEndOfMonth().isBefore(earliest)) return PRIOR_BEFORE_HISTORY;
        if (isPartialBoundaryMonth(selected.toString(), earliest, latest)) return SELECTED_MONTH_PARTIAL;
        if (isPartialBoundaryMonth(prior.toString(), earliest, latest)) return PARTIAL_PRIOR_MONTH;
        if (overlapsGap(selected, gaps) || overlapsGap(prior, gaps)) return COVERAGE_GAP;
        if (selectedAwaitingStatement) return ACCOUNT_STATEMENT_BEHIND;
        if (priorTransactionCount < MIN_TRANSACTIONS) return TOO_FEW_PRIOR_TRANSACTIONS;
        return null;
    }
}
