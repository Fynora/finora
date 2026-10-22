package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The Advanced Reports page: one period of whole months, its totals, the same figures for the
 * period it is compared with, and the breakdowns the page draws. Every figure is built from the
 * rows and rules Reports and the Dashboard use (see {@code AdvancedReportService}); this DTO only
 * carries them.
 *
 * <p>Nested records carry an {@code Advanced} prefix because OpenAPI schemas are keyed by simple
 * name and {@code Totals}, {@code Comparison} and {@code Period} are names other DTOs could take.
 */
public record AdvancedReportDto(
        AdvancedPeriodInfo period,
        AdvancedTotals totals,
        AdvancedComparison comparison,
        List<AdvancedMonth> months,
        AdvancedCategories categories,
        AdvancedMerchants merchants,
        List<ReportDto.PaymentMethodAmount> paymentMethods,
        AdvancedIncome income,
        List<AdvancedUnusualSpend> unusualSpends,
        AnalyticsDto.InternationalSpend international,
        List<AdvancedInsight> insights
) {

    /**
     * @param hasData         false when no month of the history lies in the period; every list is then empty
     * @param noDataReason    why there is nothing to show (NO_TRANSACTIONS, ONLY_PARTIAL_MONTH,
     *                        NO_MONTHS_IN_PERIOD), null when there is data
     * @param range           the {@link AdvancedRange} asked for
     * @param startMonth      first month of the period, "yyyy-MM"
     * @param endMonth        last month of the period
     * @param monthCount      months from start to end, whether or not each has transactions
     * @param requestedMonths months the range asked for; more than {@code monthCount} when the history
     *                        is shorter than the range
     * @param firstDataMonth  the first month with any transaction
     * @param newestDataMonth the newest month with any transaction
     * @param leftOutMonth    the newest month, when it was left out because it is not whole yet
     * @param leftOutReason   PARTIAL_MONTH (the statements stop before month-end) or STATEMENT_BEHIND (an
     *                        account's latest statement stops short of it), null when nothing was left out
     * @param incompleteMonths months inside the period that are only part of a month: the first month
     *                        when the history starts mid-month, or one a statement gap touches
     * @param compare         the {@link AdvancedCompare} used, null when the period has no comparison
     * @param compareOptions  the ways this period can be compared (empty for all time)
     * @param previousStartMonth first month of the comparison period, null when there is none
     * @param previousEndMonth   last month of the comparison period
     */
    public record AdvancedPeriodInfo(boolean hasData, String noDataReason, String range, String startMonth,
                                     String endMonth, int monthCount, int requestedMonths, String firstDataMonth,
                                     String newestDataMonth, String leftOutMonth, String leftOutReason,
                                     List<String> incompleteMonths, String compare, List<String> compareOptions,
                                     String previousStartMonth, String previousEndMonth) {}

    /**
     * @param avgMonthlyExpense  expense / monthsWithData; null when no month has transactions
     * @param monthsWithData     months of the period holding at least one transaction
     * @param savingsRatePct     (income - expense) / income, by the rule the Dashboard uses; null exactly
     *                           when {@code savingsRateGateReason} is not
     * @param savingsRateGateReason NO_INCOME or UNRESOLVED_EXCEEDS_INCOME, null when a rate is given
     * @param unresolvedInflow   credits in the period that are not yet counted as income
     * @param highestSpendMonth  the whole month with the most spending; null unless there are at least
     *                           two whole months with spending to choose between
     * @param lowestSpendMonth   likewise, the whole month with the least
     */
    public record AdvancedTotals(BigDecimal income, BigDecimal expense, BigDecimal net,
                                 BigDecimal avgMonthlyExpense, int monthsWithData, BigDecimal savingsRatePct,
                                 String savingsRateGateReason, BigDecimal unresolvedInflow,
                                 int unresolvedInflowCount, int transactionCount,
                                 String highestSpendMonth, String lowestSpendMonth) {}

    /**
     * The same measures for the comparison period, and how this period moved against it. Every field
     * after {@code gateReason} is null unless {@code reliable}.
     *
     * @param reliable   false when the comparison must not be shown as figures
     * @param gateReason why it is not reliable: NO_TRANSACTION_HISTORY, PRIOR_PERIOD_BEFORE_HISTORY,
     *                   TOO_FEW_PRIOR_TRANSACTIONS, COVERAGE_GAP (a hole between two statements touches either
     *                   period), INCOMPLETE_MONTHS (a month in either period is only part of a month), or
     *                   ALL_TIME (nothing comes before it)
     * @param expenseDeltaPct percent change, null when the earlier figure is zero
     * @param netDelta        (income - expense) now minus then, an amount, never a percent
     * @param savingsRateDeltaPp change in the savings rate in percentage points; null unless both
     *                        periods have a rate
     */
    public record AdvancedComparison(boolean reliable, String gateReason, BigDecimal previousIncome,
                                     BigDecimal previousExpense, BigDecimal previousNet,
                                     BigDecimal previousAvgMonthlyExpense, BigDecimal previousSavingsRatePct,
                                     Double incomeDeltaPct, Double expenseDeltaPct, BigDecimal netDelta,
                                     Double avgMonthlyExpenseDeltaPct, Double savingsRateDeltaPp) {}

    /**
     * One month of the period. {@code savingsRatePct} is null when that month has no honest rate.
     * {@code complete} is false for a month that is only part of a month.
     */
    public record AdvancedMonth(String month, BigDecimal income, BigDecimal expense, BigDecimal net,
                                BigDecimal savingsRatePct, boolean complete, int transactionCount) {}

    /**
     * @param rows               the largest categories by spending, at most eight
     * @param otherAmount        what the categories not listed add up to; zero when all are listed
     * @param otherSharePct      their share of the spending
     * @param categoryCount      how many categories had spending in the period
     */
    public record AdvancedCategories(List<AdvancedCategory> rows, BigDecimal otherAmount, double otherSharePct,
                                     int categoryCount) {}

    /**
     * @param name            the category's name; spending with no category is "Uncategorized"
     * @param sharePct        percent of the period's spending (the sum of its categories)
     * @param previousAmount  the same category in the comparison period; null unless the comparison
     *                        is reliable (zero when it had no spending then)
     * @param changeAmount    amount minus previousAmount
     * @param changePct       percent change; null when previousAmount is zero
     * @param monthly         one amount per month of the period, aligned with {@code months}
     */
    public record AdvancedCategory(String name, BigDecimal amount, double sharePct, int transactionCount,
                                   BigDecimal previousAmount, BigDecimal changeAmount, Double changePct,
                                   List<BigDecimal> monthly) {}

    /**
     * @param rows                the largest merchants by spending, at most eight
     * @param attributedSharePct  percent of the period's spending that has a merchant at all
     * @param topFiveSharePct     percent of the period's spending that the five largest merchants took
     * @param merchantCount       how many merchants had spending in the period
     */
    public record AdvancedMerchants(List<AdvancedMerchant> rows, double attributedSharePct, double topFiveSharePct,
                                    int merchantCount) {}

    /** {@code sharePct} is of the period's total spending. The comparison fields follow
     *  {@link AdvancedCategory}. */
    public record AdvancedMerchant(UUID merchantId, String name, BigDecimal amount, double sharePct,
                                   int transactionCount, BigDecimal previousAmount, BigDecimal changeAmount,
                                   Double changePct, List<BigDecimal> monthly) {}

    /**
     * @param sources            income by what it was (the user's kind, or the automatic reading), largest first
     * @param monthsWithIncome   months of the period in which any income was counted
     * @param highestMonth       the month with the most income, null when no month had any
     * @param lowestMonth        the month with the least among months that had some
     * @param lowestAsPctOfHighest the least month's income as a percent of the most
     */
    public record AdvancedIncome(List<AdvancedIncomeSource> sources, int monthsWithIncome, String highestMonth,
                                 BigDecimal highestMonthAmount, String lowestMonth, BigDecimal lowestMonthAmount,
                                 Double lowestAsPctOfHighest) {}

    /** {@code previousAmount} follows {@link AdvancedCategory}. */
    public record AdvancedIncomeSource(String label, BigDecimal amount, double sharePct, BigDecimal previousAmount) {}

    /**
     * A purchase larger than is usual for its category.
     *
     * @param typicalAmount  the median of the category's other purchases in the period and the twelve
     *                       months before it
     * @param timesTypical   amount / typicalAmount, one decimal
     * @param sampleSize     how many other purchases the median is over
     */
    public record AdvancedUnusualSpend(UUID transactionId, LocalDate date, String label, String category,
                                       BigDecimal amount, BigDecimal typicalAmount, BigDecimal timesTypical,
                                       int sampleSize) {}

    /**
     * One observation, worded on the server so web and mobile say the same thing.
     *
     * @param kind     SPENDING_CHANGE, INCOME_CHANGE, SAVINGS_RATE, CATEGORY_UP, CATEGORY_DOWN, CONCENTRATION,
     *                 HIGHEST_MONTH or UNUSUAL_SPEND
     * @param tone     UP, DOWN or NEUTRAL: the direction of the thing described, not a judgement of it
     * @param headline the one-line statement
     * @param detail   the figures and the comparison period behind it
     * @param drill    the filter that opens the transactions behind it, null when none applies
     */
    public record AdvancedInsight(String id, String kind, String tone, String headline, String detail,
                                  AdvancedDrill drill) {}

    /** A link into the Ledger: transactions in {@code [from, to]}, optionally in one category or
     *  matching a search word. */
    public record AdvancedDrill(LocalDate from, LocalDate to, String categoryName, String keyword) {}
}
