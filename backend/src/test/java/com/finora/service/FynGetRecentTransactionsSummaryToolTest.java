package com.finora.service;

import com.finora.dto.AnalyticsDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynGetRecentTransactionsSummaryToolTest {

    private final AnalyticsService analyticsService = mock(AnalyticsService.class);
    private final FynGetRecentTransactionsSummaryTool tool =
            new FynGetRecentTransactionsSummaryTool(analyticsService);
    private final UUID userId = UUID.randomUUID();

    @Test
    void summarizesEveryCategory() {
        when(analyticsService.topCategories(any(), isNull())).thenReturn(List.of(
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Dining", new BigDecimal("4200"), 12),
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Groceries", new BigDecimal("6000"), 8)));

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Dining", "4200", "Groceries", "6000");
    }

    @Test
    void reportsNoDataForAnEmptyMonth() {
        when(analyticsService.topCategories(any(), isNull())).thenReturn(List.of());

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("No categorized spending found");
    }
}
