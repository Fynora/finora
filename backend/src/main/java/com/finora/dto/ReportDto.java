package com.finora.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReportDto(
        String month,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryAmount> categories,
        /* Credits Fynora cannot yet call income (money from a person, an unexplained credit-card
         * credit). Excluded from income, reported beside it so it is never silently dropped. */
        BigDecimal unresolvedInflow,
        /* Income split by what it was: the user's kind (Family support, their own kinds) or the
         * automatic reading (Salary, Interest, ...). Sums to income. Largest first. */
        List<IncomeLine> incomeByKind,
        /* How many transactions the income and expense totals above were built from (refund-netted,
         * investment transfers excluded) -- not a count of every row the ledger lists for the month. */
        int transactionCount,
        /* How many credits make up unresolvedInflow. */
        int unresolvedInflowCount,
        /* This month against the month before, or null when the user has no live account. Always
         * present otherwise: when the comparison cannot be trusted, reliable is false and gateReason
         * says why, so a client can explain the absence instead of showing nothing. */
        MonthComparison comparison,
        /* False when this month is only part of a month: the ragged edge of the user's imported
         * history, a hole between two statements, or an account whose statements (or Account
         * Aggregator sync) stop short of it. The figures above are still real; a chart marks the
         * month rather than plotting it as an ordinary point. True for a user with no history. */
        boolean complete,
        /* The share of this month's income the user kept, (income - expense) / income as a percent,
         * by the same rule the Dashboard applies (SavingsRate). Null exactly when
         * savingsRateGateReason is not: a rate that unclassified money decides is withheld. */
        BigDecimal savingsRatePct,
        /* Why savingsRatePct is null (NO_INCOME, UNRESOLVED_EXCEEDS_INCOME), or null when it is not.
         * Same values and meaning as DashboardSummaryDto.savingsRateGateReason. */
        String savingsRateGateReason
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
    public record IncomeLine(String label, BigDecimal amount) {}

    /**
     * @param previousMonth    the calendar month before the report's month, "yyyy-MM"
     * @param reliable         false when the comparison must not be shown as a figure
     * @param gateReason       why it is not reliable (see MonthComparisonGate), null when reliable
     * @param previousIncome   the prior month's income; null unless reliable
     * @param previousExpense  the prior month's expense; null unless reliable
     * @param incomeDeltaPct   percent change in income; null unless reliable and the prior is non-zero
     * @param expenseDeltaPct  percent change in expense; same rule
     * @param netDelta         (income - expense) this month minus the same for the prior month, as an
     *                         amount, not a percent -- a percent of a negative or near-zero net misleads.
     *                         Null unless reliable.
     */
    public record MonthComparison(String previousMonth, boolean reliable, String gateReason,
                                  BigDecimal previousIncome, BigDecimal previousExpense,
                                  Double incomeDeltaPct, Double expenseDeltaPct, BigDecimal netDelta) {}
}
