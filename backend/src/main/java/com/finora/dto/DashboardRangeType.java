package com.finora.dto;

/**
 * Which window of history a Dashboard range-summary request covers.
 *
 * <p>Presets are calendar-MONTH aligned (see {@code DashboardRangeService}, which anchors them to
 * the newest month the user has transaction data for, the same "reporting month" philosophy
 * {@code ReportingPeriod} already established for the single-month KPIs). {@code CUSTOM} is an
 * arbitrary caller-supplied {@code [startDate, endDate]} with no calendar alignment at all.
 */
public enum DashboardRangeType {
    LAST_3_MONTHS(3), LAST_6_MONTHS(6), LAST_12_MONTHS(12), LAST_24_MONTHS(24), CUSTOM(0);

    private final int months;

    DashboardRangeType(int months) {
        this.months = months;
    }

    /** Meaningless for {@link #CUSTOM} -- callers must branch on the type before reading this. */
    public int months() {
        return months;
    }
}
