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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FynGetRecentTransactionsSummaryToolTest {

    private final AnalyticsService analyticsService = mock(AnalyticsService.class);
    private final FynGetRecentTransactionsSummaryTool tool =
            new FynGetRecentTransactionsSummaryTool(analyticsService);
    private final UUID userId = UUID.randomUUID();
    private static final YearMonth REPORTING = YearMonth.of(2026, 9);

    @BeforeEach
    void reportingMonthIsTheCurrentMonth() {
        when(analyticsService.reportingPeriod(userId)).thenReturn(new ReportingPeriod("2026-09", true, "2026-09"));
        when(analyticsService.totalExpense(eq(userId), eq(REPORTING))).thenReturn(new BigDecimal("10200"));
    }

    @Test
    void summarizesEveryCategoryForTheReportingMonth() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Dining", new BigDecimal("4200"), 12),
                new AnalyticsDto.CategorySpend("Groceries", new BigDecimal("6000"), 8)));

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Period: 2026-09 (the current month)", "Total spend: ₹10200",
                "Dining: ₹4200 (12 txns)", "Groceries: ₹6000 (8 txns)");
        verify(analyticsService, never()).categoryBreakdown(eq(userId), isNull());
    }

    @Test
    void reportsNoDataForAnEmptyMonth() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of());
        when(analyticsService.totalExpense(userId, REPORTING)).thenReturn(BigDecimal.ZERO);

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Period: 2026-09", "No spending found");
    }

    /** Uncategorized spend is a slice on the dashboard's donut, so it is listed like any category. */
    @Test
    void uncategorizedSpendIsListedAndCounted() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Uncategorized", new BigDecimal("500"), 2)));
        when(analyticsService.totalExpense(userId, REPORTING)).thenReturn(new BigDecimal("500"));

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Total spend: ₹500", "Uncategorized: ₹500 (2 txns)");
    }

    /** A month holding only a SIP: the dashboard's Expenses is 0 but its donut still shows the
     *  Investments slice. Must not be reported as "no spending". */
    @Test
    void aMonthOfOnlyInvestmentTransfersReportsZeroTotalButListsTheCategory() {
        when(analyticsService.categoryBreakdown(userId, REPORTING)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Investments", new BigDecimal("10000"), 1)));
        when(analyticsService.totalExpense(userId, REPORTING)).thenReturn(BigDecimal.ZERO);

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Total spend: ₹0", "Investments: ₹10000 (1 txns)")
                .doesNotContain("No spending found");
    }

    @Test
    void anExplicitMonthIsUsedAsGiven() {
        YearMonth august = YearMonth.of(2026, 8);
        when(analyticsService.categoryBreakdown(userId, august)).thenReturn(List.of(
                new AnalyticsDto.CategorySpend("Travel", new BigDecimal("7840"), 1)));
        when(analyticsService.totalExpense(userId, august)).thenReturn(new BigDecimal("7840"));

        String result = tool.execute(userId, Map.of("month", "2026-08"));

        assertThat(result).contains("Period: 2026-08.", "Travel: ₹7840 (1 txns)");
        verify(analyticsService, never()).reportingPeriod(userId);
    }
}
