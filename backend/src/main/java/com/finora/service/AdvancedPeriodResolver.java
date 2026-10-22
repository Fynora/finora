package com.finora.service;

import com.finora.dto.AdvancedCompare;
import com.finora.dto.AdvancedRange;

import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Where an Advanced Reports period starts and ends, and what it is compared with. Pure: it knows
 * nothing about transactions, only about months, so every rule here is a plain test.
 *
 * <p>Every period is whole calendar months. The caller passes the <i>anchor</i>, the last month
 * the history can be called whole (see {@code AdvancedReportService}); rolling windows, "this year"
 * and "all time" end there, so "this year" means the year of the last whole month rather than the
 * year it is on the clock. A custom run may end in the newest month with data even when that month
 * is not whole: the user asked for it by name.
 */
final class AdvancedPeriodResolver {

    private AdvancedPeriodResolver() {}

    /** A longer custom run is refused rather than loaded: ten years is more history than any user has. */
    static final int MAX_CUSTOM_MONTHS = 120;

    /**
     * @param start          first month of the period, never before the first month with data
     * @param end            last month of the period
     * @param months         how many months {@code start..end} covers; 0 when none lies in the history
     * @param requestedMonths how many months the range asked for (a rolling window longer than the
     *                       history is shortened to the history, and this says by how much)
     * @param compare        what the period is compared with, null for none
     * @param previousStart  first month of the comparison window, null when there is none
     * @param previousEnd    last month of the comparison window
     * @param compareOptions the ways this period can be compared; empty for "all time"
     */
    record Resolved(YearMonth start, YearMonth end, int months, int requestedMonths,
                    AdvancedCompare compare, YearMonth previousStart, YearMonth previousEnd,
                    List<AdvancedCompare> compareOptions) {}

    /** A custom run needs both ends, in order, and at most {@link #MAX_CUSTOM_MONTHS} months. Checked
     *  before any data is read, so a bad request is refused for a user with no history too. */
    static void validateCustom(YearMonth customStart, YearMonth customEnd) {
        if (customStart == null || customEnd == null) {
            throw new IllegalArgumentException("startMonth and endMonth are required for a CUSTOM range");
        }
        if (customStart.isAfter(customEnd)) {
            throw new IllegalArgumentException("startMonth must not be after endMonth");
        }
        if (ChronoUnit.MONTHS.between(customStart, customEnd) + 1 > MAX_CUSTOM_MONTHS) {
            throw new IllegalArgumentException("A custom range can span at most " + MAX_CUSTOM_MONTHS + " months");
        }
    }

    static Resolved resolve(AdvancedRange range, YearMonth customStart, YearMonth customEnd,
                            AdvancedCompare requestedCompare, YearMonth firstData, YearMonth newestData,
                            YearMonth anchor) {
        YearMonth start;
        YearMonth end = anchor;
        int requested;
        boolean sameMonthsLastYear = false;
        switch (range) {
            case LAST_3_MONTHS, LAST_6_MONTHS, LAST_12_MONTHS -> {
                requested = range.months();
                start = anchor.minusMonths(requested - 1L);
            }
            case THIS_YEAR -> {
                start = YearMonth.of(anchor.getYear(), Month.JANUARY);
                requested = anchor.getMonthValue();
                sameMonthsLastYear = true;
            }
            case LAST_YEAR -> {
                start = YearMonth.of(anchor.getYear() - 1, Month.JANUARY);
                end = YearMonth.of(anchor.getYear() - 1, Month.DECEMBER);
                requested = 12;
                sameMonthsLastYear = true;
            }
            case ALL_TIME -> {
                start = firstData;
                requested = (int) ChronoUnit.MONTHS.between(firstData, anchor) + 1;
            }
            case CUSTOM -> {
                validateCustom(customStart, customEnd);
                requested = (int) ChronoUnit.MONTHS.between(customStart, customEnd) + 1;
                start = customStart;
                end = customEnd.isAfter(newestData) ? newestData : customEnd;
            }
            default -> throw new IllegalStateException("Unhandled range " + range);
        }

        if (start.isBefore(firstData)) start = firstData;
        int months = end.isBefore(start) ? 0 : (int) ChronoUnit.MONTHS.between(start, end) + 1;
        if (months == 0) {
            return new Resolved(start, end, 0, requested, null, null, null, List.of());
        }

        if (range == AdvancedRange.ALL_TIME) {
            return new Resolved(start, end, months, requested, null, null, null, List.of());
        }

        List<AdvancedCompare> options;
        if (sameMonthsLastYear) {
            options = List.of(AdvancedCompare.SAME_PERIOD_LAST_YEAR);
        } else if (months < 12) {
            options = List.of(AdvancedCompare.PREVIOUS_PERIOD, AdvancedCompare.SAME_PERIOD_LAST_YEAR);
        } else {
            // Twelve months: the previous twelve and the same twelve a year earlier are one window.
            // More than twelve: shifting by a year would overlap the period itself.
            options = List.of(AdvancedCompare.PREVIOUS_PERIOD);
        }
        AdvancedCompare compare = requestedCompare != null && options.contains(requestedCompare)
                ? requestedCompare : options.get(0);

        YearMonth previousStart;
        YearMonth previousEnd;
        if (compare == AdvancedCompare.SAME_PERIOD_LAST_YEAR || months == 12) {
            previousStart = start.minusYears(1);
            previousEnd = end.minusYears(1);
        } else {
            previousEnd = start.minusMonths(1);
            previousStart = previousEnd.minusMonths(months - 1L);
        }
        // For a window that reaches the exact year shift, report the option that was actually used.
        if (months == 12 && !sameMonthsLastYear) compare = AdvancedCompare.PREVIOUS_PERIOD;
        return new Resolved(start, end, months, requested, compare, previousStart, previousEnd, options);
    }
}
