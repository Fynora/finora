package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        String savingsRateGateReason,
        /* The month's largest single purchases, biggest first, at most three. They come from the
         * same rows and refund netting as the category list, so each is something the report
         * counted as spend (a transfer or a card-bill payment the report leaves out is never here).
         * Empty when nothing was spent. */
        List<BiggestSpend> biggestSpends,
        /* Spending per day, oldest first, for the days that have any. Same rows and refund netting
         * as the category list. A day whose refunds cancel its purchases has no entry. A category
         * whose refunds exceed its purchases is floored at zero in the category list but not here,
         * so in that one case the days can add to slightly less than the category total. */
        List<DailySpend> dailySpend,
        /* The month's spending by how it was paid, largest first, from the same rows and refund
         * netting as the category list. The method is worked out from the account type and the
         * words at the front of the statement narration (PaymentMethodClassifier), never stored.
         * UNIDENTIFIED is the spending whose wording names no method; it is listed, not hidden, so
         * a client can say how much of the month the other rows cover. Empty when nothing was spent.
         * A method whose refunds exceed its purchases is dropped, as a category is. */
        List<PaymentMethodAmount> paymentMethods
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
    /** @param method a PaymentMethod name: UPI, CARD, BANK_TRANSFER, CASH, CHEQUE, AUTO_DEBIT, WALLET
     *                or UNIDENTIFIED. A client shows a code it does not know under a generic label. */
    public record PaymentMethodAmount(String method, BigDecimal amount) {}
    /** @param label the merchant, or the description when there is none */
    public record BiggestSpend(UUID id, LocalDate date, String label, String category, BigDecimal amount) {}
    public record DailySpend(LocalDate date, BigDecimal amount) {}
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
