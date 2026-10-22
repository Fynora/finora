package com.finora.dto;

/**
 * Which window of history an Advanced Reports request covers. Every window is made of whole
 * calendar months; see {@code AdvancedPeriodResolver} for where each one starts and ends.
 */
public enum AdvancedRange {
    LAST_3_MONTHS(3), LAST_6_MONTHS(6), LAST_12_MONTHS(12), THIS_YEAR(0), LAST_YEAR(0), ALL_TIME(0), CUSTOM(0);

    private final int months;

    AdvancedRange(int months) {
        this.months = months;
    }

    /** How many months a rolling window spans; 0 for the ranges that are not a fixed length. */
    public int months() {
        return months;
    }
}
