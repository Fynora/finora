package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.DashboardSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @Cacheable} only does anything through a real Spring proxy backed by a real cache --
 * {@code FynGetBalanceToolTest} (a plain unit test, {@code new FynGetBalanceTool(mock)}) proves
 * the tool's own logic but exercises no proxy at all, so it cannot prove caching actually happens.
 * This runs against the real Spring context and the real Redis Testcontainer {@link
 * AbstractIntegrationTest} wires up -- the same standard {@code testing-with-real-redis-testcontainer}
 * (project memory) requires for any {@code @Cacheable} claim.
 */
class FynToolResultCacheIT extends AbstractIntegrationTest {

    @Autowired private FynGetBalanceTool balanceTool;
    @MockitoBean private DashboardService dashboardService;

    @Test
    void repeatingTheSameQuestionDoesNotHitTheServiceTwice() {
        UUID userId = UUID.randomUUID();
        DashboardSummaryDto summary = mock(DashboardSummaryDto.class);
        when(summary.currentBalance()).thenReturn(new BigDecimal("52340.00"));
        when(dashboardService.summarize(userId)).thenReturn(summary);

        String first = balanceTool.execute(userId, Map.of());
        String second = balanceTool.execute(userId, Map.of());

        assertThat(first).isEqualTo(second).contains("52340.00");
        verify(dashboardService, times(1)).summarize(userId);
    }

    @Test
    void doesNotServeOneUsersCachedBalanceToAnother() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        DashboardSummaryDto summaryA = mock(DashboardSummaryDto.class);
        when(summaryA.currentBalance()).thenReturn(new BigDecimal("100.00"));
        DashboardSummaryDto summaryB = mock(DashboardSummaryDto.class);
        when(summaryB.currentBalance()).thenReturn(new BigDecimal("200.00"));
        when(dashboardService.summarize(userA)).thenReturn(summaryA);
        when(dashboardService.summarize(userB)).thenReturn(summaryB);

        String resultA = balanceTool.execute(userA, Map.of());
        String resultB = balanceTool.execute(userB, Map.of());

        assertThat(resultA).contains("100.00");
        assertThat(resultB).contains("200.00");
        verify(dashboardService, times(1)).summarize(userA);
        verify(dashboardService, times(1)).summarize(userB);
    }
}
