package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AnalyticsDto;
import com.finora.dto.DashboardSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
 *
 * <p>{@link FynGetBalanceTool}'s key has no {@code input} argument at all ({@code #userId} only);
 * {@link FynGetSpendByCategoryTool}'s key concatenates two {@code #input.get(...)} lookups against
 * a {@code Map<String, Object>} -- a materially different SpEL shape (a null category, in
 * particular, must stringify to the literal {@code "null"} rather than throw, which SpEL's `+`
 * operator is not guaranteed to do the same way Java's does for every operand type). Proving the
 * simpler tool's cache works is not evidence the multi-argument key on the other three does --
 * this class covers one of those three for real, rather than assuming the same annotation shape
 * behaves identically by analogy.
 */
class FynToolResultCacheIT extends AbstractIntegrationTest {

    @Autowired private FynGetBalanceTool balanceTool;
    @Autowired private FynGetSpendByCategoryTool spendByCategoryTool;
    @MockitoBean private DashboardService dashboardService;
    @MockitoBean private AnalyticsService analyticsService;

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

    private static AnalyticsDto.TopCategory category(String name, String amount) {
        return new AnalyticsDto.TopCategory(UUID.randomUUID(), name, new BigDecimal(amount), 3);
    }

    @Test
    void cachesTheMultiArgumentKeyToo() {
        UUID userId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 8);
        when(analyticsService.topCategories(eq(userId), eq(month)))
                .thenReturn(List.of(category("Dining", "4200.00")));

        String first = spendByCategoryTool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));
        String second = spendByCategoryTool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));

        assertThat(first).isEqualTo(second).contains("4200.00");
        verify(analyticsService, times(1)).topCategories(userId, month);
    }

    @Test
    void aDifferentCategoryIsNotServedFromTheFirstCategorysCacheEntry() {
        UUID userId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 8);
        when(analyticsService.topCategories(eq(userId), eq(month))).thenReturn(
                List.of(category("Dining", "4200.00"), category("Groceries", "1500.00")));

        String dining = spendByCategoryTool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));
        String groceries = spendByCategoryTool.execute(userId, Map.of("category", "Groceries", "month", "2026-08"));

        assertThat(dining).contains("4200.00");
        assertThat(groceries).contains("1500.00");
        // Both calls hit the real service -- proves the key genuinely varies with the category
        // argument rather than collapsing every category for this user onto one cache entry.
        verify(analyticsService, times(2)).topCategories(userId, month);
    }

    @Test
    void anOmittedMonthDoesNotBreakTheCacheKeyAndIsCachedSeparatelyFromAnExplicitMonth() {
        // SpEL's `+` operator concatenating a null #input.get('month') must stringify to "null"
        // rather than throw -- verified for real here, not assumed from the analogous
        // FynGetBudgetStatusTool key ever having been exercised through this same pipeline.
        UUID userId = UUID.randomUUID();
        when(analyticsService.topCategories(eq(userId), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(category("Dining", "999.00")));
        when(analyticsService.topCategories(eq(userId), eq(YearMonth.of(2026, 8))))
                .thenReturn(List.of(category("Dining", "4200.00")));

        String currentMonth = spendByCategoryTool.execute(userId, Map.of("category", "Dining"));
        String currentMonthAgain = spendByCategoryTool.execute(userId, Map.of("category", "Dining"));
        String explicitMonth = spendByCategoryTool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));

        assertThat(currentMonth).isEqualTo(currentMonthAgain).contains("999.00");
        assertThat(explicitMonth).contains("4200.00");
        verify(analyticsService, times(1)).topCategories(eq(userId), org.mockito.ArgumentMatchers.isNull());
        verify(analyticsService, times(1)).topCategories(userId, YearMonth.of(2026, 8));
    }
}
