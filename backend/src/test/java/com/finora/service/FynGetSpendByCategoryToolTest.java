package com.finora.service;

import com.finora.dto.AnalyticsDto;
import com.finora.util.ReportingPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynGetSpendByCategoryToolTest {

    private final AnalyticsService analyticsService = mock(AnalyticsService.class);
    private final FynGetSpendByCategoryTool tool = new FynGetSpendByCategoryTool(analyticsService);
    private final UUID userId = UUID.randomUUID();
    /** The dashboard's reporting month, which an omitted month now resolves to. */
    private static final YearMonth REPORTING = YearMonth.of(2026, 9);

    @BeforeEach
    void reportingMonthIsTheCurrentMonth() {
        when(analyticsService.reportingPeriod(userId)).thenReturn(new ReportingPeriod("2026-09", true, "2026-09"));
    }

    @Test
    void reportsSpendForACaseInsensitiveCategoryMatch() {
        when(analyticsService.categoryBreakdown(any(), eq(REPORTING))).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Dining", new BigDecimal("4200"), 12)));

        String result = tool.execute(userId, Map.of("category", "dining"));

        assertThat(result).contains("Dining", "4200", "12 transactions");
    }

    @Test
    void reportsNoSpendFoundAtAllWhenTheUserHasNoCategorizedSpendThisPeriod() {
        when(analyticsService.categoryBreakdown(any(), eq(REPORTING))).thenReturn(List.of());

        String result = tool.execute(userId, Map.of("category", "Yachts"));

        assertThat(result).contains("No spending found for that period at all");
    }

    /** Categories are fully user-defined and renameable -- the model can only guess a category's
     *  exact name from how the user phrased the question. A mismatch here (e.g. "food" vs. the
     *  user's actual "Dining") must hand back the user's real category names rather than assert
     *  the false "you spent nothing" -- see FynGetSpendByCategoryTool#noMatchMessage's own doc. */
    @Test
    void aNameMismatchReturnsTheUsersActualCategoryNamesInsteadOfAFalseNegative() {
        when(analyticsService.categoryBreakdown(any(), eq(REPORTING))).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Dining", new BigDecimal("4200"), 12),
                new AnalyticsDto.CategorySpend("Groceries", new BigDecimal("3200"), 5)));

        String result = tool.execute(userId, Map.of("category", "food"));

        assertThat(result).doesNotContain("No spending found in a category matching");
        assertThat(result).contains("Dining", "Groceries");
    }

    @Test
    void asksForACategoryWhenNoneIsGiven() {
        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("No category was given");
    }

    @Test
    void parsesAValidMonthArgument() {
        when(analyticsService.categoryBreakdown(userId, YearMonth.of(2026, 8))).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Dining", new BigDecimal("1000"), 3)));

        String result = tool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));

        assertThat(result).contains("1000");
    }

    @Test
    void treatsAnUnparseableMonthAsTheDashboardsReportingMonth() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Dining", new BigDecimal("900"), 2)));

        // Must not throw -- an unparseable month from the model degrades to the reporting month,
        // not a failed tool call that would derail the whole chat turn.
        String result = tool.execute(userId, Map.of("category", "Dining", "month", "not-a-month"));

        assertThat(result).contains("Period: 2026-09", "900");
    }

    /** The bug this guards: an omitted month used to reach topCategories as null, i.e. all time. */
    @Test
    void anOmittedMonthNeverAsksForAllTime() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Rent", new BigDecimal("22000"), 1)));

        String result = tool.execute(userId, Map.of("category", "Rent"));

        assertThat(result).contains("Period: 2026-09 (the current month)", "22000", "1 transactions");
        org.mockito.Mockito.verify(analyticsService, org.mockito.Mockito.never())
                .categoryBreakdown(eq(userId), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void labelsAPastReportingMonthAsNotTheCurrentOne() {
        when(analyticsService.reportingPeriod(userId)).thenReturn(new ReportingPeriod("2026-08", false, "2026-09"));
        when(analyticsService.categoryBreakdown(userId, YearMonth.of(2026, 8))).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Rent", new BigDecimal("22000"), 1)));

        String result = tool.execute(userId, Map.of("category", "Rent"));

        assertThat(result).contains("Period: 2026-08", "no transactions for 2026-09", "22000");
    }

    @Test
    void aUserWithNoTransactionsAtAllGetsTheCalendarMonth() {
        when(analyticsService.reportingPeriod(userId)).thenReturn(new ReportingPeriod(null, true, "2026-09"));
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of());

        String result = tool.execute(userId, Map.of("category", "Rent"));

        assertThat(result).contains("Period: 2026-09", "No spending found for that period at all");
    }
}
