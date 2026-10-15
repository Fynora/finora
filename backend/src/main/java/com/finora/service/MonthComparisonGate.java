package com.finora.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * When a month-over-month comparison can be trusted. One definition for every reader, so the
 * Dashboard and the Reports page cannot disagree about which months are comparable.
 *
 * <p>The partial-boundary rule and the transaction floor were written for the Dashboard
 * ({@code DashboardService}); they live here now and the Dashboard delegates to them unchanged.
 *
 * <p><b>Known limitation.</b> Coverage is judged at the USER level: the newest and oldest
 * transaction across every live account, plus holes between two statements of the same account.
 * An account whose statements simply stop earlier than the others (a card cycle ending on the
 * 11th while a bank account runs to month-end) is not detected, so a month can read as complete
 * while one account's last weeks are missing. The Dashboard has the same blind spot. Closing it
 * needs a per-account "covered until" date and a decision about accounts the user has stopped
 * using, which would otherwise block every comparison forever.
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
    static final String TOO_FEW_PRIOR_TRANSACTIONS = "TOO_FEW_PRIOR_TRANSACTIONS";

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
     * Why {@code selected} cannot be compared with {@code prior}, or null when it can. Checked in
     * the order a reader would want explained: no data at all, then whether the months exist, then
     * whether either is a partial slice, then whether a statement is missing, then whether the
     * prior month is too thin to be a baseline.
     *
     * @param selectedTransactionCount transactions the selected month's totals were built from
     * @param priorTransactionCount    the same, for the prior month
     */
    static String reason(YearMonth selected, YearMonth prior, LocalDate earliest, LocalDate latest,
                         List<AccountCoverageService.DateRange> gaps,
                         int selectedTransactionCount, int priorTransactionCount) {
        if (earliest == null || latest == null) return NO_TRANSACTION_HISTORY;
        if (selectedTransactionCount == 0) return NO_TRANSACTIONS_IN_MONTH;
        if (prior.atEndOfMonth().isBefore(earliest)) return PRIOR_BEFORE_HISTORY;
        if (isPartialBoundaryMonth(selected.toString(), earliest, latest)) return SELECTED_MONTH_PARTIAL;
        if (isPartialBoundaryMonth(prior.toString(), earliest, latest)) return PARTIAL_PRIOR_MONTH;
        if (overlapsGap(selected, gaps) || overlapsGap(prior, gaps)) return COVERAGE_GAP;
        if (priorTransactionCount < MIN_TRANSACTIONS) return TOO_FEW_PRIOR_TRANSACTIONS;
        return null;
    }
}
