package com.finora.service;

import com.finora.util.ReportingPeriod;

import java.time.YearMonth;
import java.util.UUID;

/**
 * The month a Fyn spend tool answers for, and the sentence that names it to the model.
 *
 * <p>With no {@code month} argument the tools used to pass {@code null} to {@link
 * AnalyticsService#topCategories}, which means all time, so "What did I spend this month?" was
 * answered over the user's whole history. The model has no calendar of its own (nothing in Fyn's
 * system prompt gives it today's date), so it cannot be relied on to supply the month -- the tool
 * has to resolve it, and it resolves it exactly as the dashboard does ({@link
 * AnalyticsService#reportingPeriod}), so the two screens agree.
 *
 * <p>That rule reports on the newest month with data, which is not always the calendar month (see
 * {@link ReportingPeriod}). When it is not, the label says so, so the model can tell the user
 * which month the figures are for instead of calling them "this month".
 */
record FynSpendPeriod(YearMonth month, String label) {

    static FynSpendPeriod resolve(AnalyticsService analyticsService, UUID userId, Object monthArg) {
        YearMonth requested = FynGetSpendByCategoryTool.parseMonth(monthArg);
        if (requested != null) {
            return new FynSpendPeriod(requested, "Period: " + requested + ".");
        }
        ReportingPeriod period = analyticsService.reportingPeriod(userId);
        // month() is null only for a user with no transactions at all; the calendar month then
        // yields an honest "nothing found" rather than a period that does not exist.
        YearMonth month = YearMonth.parse(period.month() != null ? period.month() : period.calendarMonth());
        String label = period.isCurrent()
                ? "Period: " + month + " (the current month)."
                : "Period: " + month + " (the user's latest month with data; there are no transactions for "
                        + period.calendarMonth() + " yet, so tell the user which month these figures are for).";
        return new FynSpendPeriod(month, label);
    }
}
