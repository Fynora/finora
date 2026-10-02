package com.finora.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The savings rate both dashboards show, and when there is no honest one to show.
 *
 * <p>(income - expense) / income is exact arithmetic, but it is only a savings rate when income is
 * the money the user actually lives on. On a real test account (2026-10-02) it read a six-figure
 * negative percentage: counted income was a few hundred rupees because lakhs of credits from people
 * were still unclassified. A figure that unclassified money decides is withheld, with a reason,
 * rather than shown.
 */
public final class SavingsRate {

    private SavingsRate() {}

    /** No income was counted in the period, so there is nothing to take a share of. */
    public static final String NO_INCOME = "NO_INCOME";
    /** More money came in unclassified than was counted as income: classifying it would change
     *  the figure more than anything the user spent. */
    public static final String UNRESOLVED_EXCEEDS_INCOME = "UNRESOLVED_EXCEEDS_INCOME";

    /** @param pct null exactly when gateReason is not */
    public record Reading(BigDecimal pct, String gateReason) {}

    public static Reading of(BigDecimal income, BigDecimal expense, BigDecimal unresolvedInflow) {
        if (income == null || income.signum() <= 0) return new Reading(null, NO_INCOME);
        if (unresolvedInflow != null && unresolvedInflow.compareTo(income) > 0) {
            return new Reading(null, UNRESOLVED_EXCEEDS_INCOME);
        }
        BigDecimal net = income.subtract(expense == null ? BigDecimal.ZERO : expense);
        return new Reading(net.divide(income, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)), null);
    }
}
