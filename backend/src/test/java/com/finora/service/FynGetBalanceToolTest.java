package com.finora.service;

import com.finora.dto.DashboardSummaryDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynGetBalanceToolTest {

    @Test
    void reportsTheCurrentBalanceFromDashboardService() {
        // DashboardSummaryDto has dozens of fields (health score breakdown, sparklines, etc.) this
        // tool never touches -- mocking just the one accessor it reads, rather than constructing
        // the whole record positionally, is both more robust to that DTO growing and clearer about
        // what this tool actually depends on. Mockito's inline mock maker (already relied on
        // elsewhere in this suite) supports mocking a record the same as any other final class.
        DashboardService dashboardService = mock(DashboardService.class);
        DashboardSummaryDto summary = mock(DashboardSummaryDto.class);
        when(summary.currentBalance()).thenReturn(new BigDecimal("52340.00"));
        UUID userId = UUID.randomUUID();
        when(dashboardService.summarize(userId)).thenReturn(summary);

        String result = new FynGetBalanceTool(dashboardService).execute(userId, Map.of());

        assertThat(result).contains("52340.00");
    }

    @Test
    void takesNoInput() {
        FynGetBalanceTool tool = new FynGetBalanceTool(mock(DashboardService.class));

        assertThat(tool.inputSchema()).containsEntry("type", "object");
        assertThat(tool.name()).isEqualTo("GET_BALANCE");
        assertThat(tool.maxDataTier()).isEqualTo(FynDataTier.TIER_1_AGGREGATE);
    }
}
