package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Range-based counterpart to {@code DashboardSummaryDto}'s single-reporting-month KPIs -- backs
 * the Dashboard's unified date-range picker (3/6/12/24 months, or a custom range).
 *
 * <p>Deliberately structured rather than pre-labelled: the client renders "Last 6 Months" from
 * {@code rangeType} and "Mar 1 - Aug 31" from {@code startDate}/{@code endDate} itself, rather
 * than receiving a formatted string it could not localize or restyle. Mirrors how
 * {@code reportingMonth} on {@code DashboardSummaryDto} hands the client a raw value to label, not
 * a pre-composed one.
 */
public record DashboardRangeSummaryDto(
        String rangeType,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate previousStartDate,
        LocalDate previousEndDate,

        BigDecimal incomeTotal,
        BigDecimal expenseTotal,
        BigDecimal netSavingsTotal,
        BigDecimal savingsRatePct,

        Double incomeDeltaPct,
        Double expenseDeltaPct,
        Double netDeltaPct,

        /*
         * Why incomeDeltaPct/expenseDeltaPct/netDeltaPct came back null, or null when they didn't:
         * "NO_TRANSACTION_HISTORY" (the account has no transactions at all), "PRIOR_PERIOD_BEFORE_
         * HISTORY" (the previous period reaches back before the account's own history began, so
         * it isn't a genuine like-for-like window), or "TOO_FEW_PRIOR_TRANSACTIONS" (a real prior
         * period, but with fewer than comparisonGateMinTransactions of its own -- a stray row or
         * two could otherwise dominate the ratio). Mirrors DashboardSummaryDto.comparisonGateReason
         * -- same reasoning, generalized from one calendar month to an arbitrary range.
         */
        String comparisonGateReason,
        int comparisonGateMinTransactions,

        /*
         * Ending balance as of `endDate` -- a SNAPSHOT, never summed across the range (balance
         * isn't additive across months the way income/expense are). currentBalanceAsOf is the
         * actual date the figure is from: the nearest snapshot at or before endDate, or -- only
         * when no snapshot exists yet and endDate is today or later -- today's live account
         * balance, in which case currentBalanceAsOf is today. Never asserts a date the underlying
         * data doesn't actually support.
         */
        BigDecimal currentBalance,
        LocalDate currentBalanceAsOf,

        /*
         * Same idea for the previous period's end -- null (with previousBalanceAsOf also null,
         * and balanceGateReason set) when no net-worth snapshot exists at or before
         * previousEndDate. Deliberately gated separately from comparisonGateReason above: a
         * snapshot can be missing for a period whose income/expense history is otherwise perfectly
         * adequate for comparison (NetWorthSnapshot rows only exist from whenever this user first
         * saved one, or the sweep started covering them), so tying the two gates together would
         * either hide a good income/expense comparison behind a missing snapshot or the reverse.
         */
        BigDecimal previousBalance,
        LocalDate previousBalanceAsOf,
        Double balanceDeltaPct,
        String balanceGateReason
) {}
