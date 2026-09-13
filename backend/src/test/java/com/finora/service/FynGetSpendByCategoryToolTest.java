package com.finora.service;

import com.finora.dto.AnalyticsDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynGetSpendByCategoryToolTest {

    private final AnalyticsService analyticsService = mock(AnalyticsService.class);
    private final FynGetSpendByCategoryTool tool = new FynGetSpendByCategoryTool(analyticsService);
    private final UUID userId = UUID.randomUUID();

    @Test
    void reportsSpendForACaseInsensitiveCategoryMatch() {
        when(analyticsService.topCategories(any(), isNull())).thenReturn(List.of(
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Dining", new BigDecimal("4200"), 12)));

        String result = tool.execute(userId, Map.of("category", "dining"));

        assertThat(result).contains("Dining", "4200", "12 transactions");
    }

    @Test
    void reportsNoSpendFoundAtAllWhenTheUserHasNoCategorizedSpendThisPeriod() {
        when(analyticsService.topCategories(any(), isNull())).thenReturn(List.of());

        String result = tool.execute(userId, Map.of("category", "Yachts"));

        assertThat(result).contains("No categorized spending found");
    }

    /** Categories are fully user-defined and renameable -- the model can only guess a category's
     *  exact name from how the user phrased the question. A mismatch here (e.g. "food" vs. the
     *  user's actual "Dining") must hand back the user's real category names rather than assert
     *  the false "you spent nothing" -- see FynGetSpendByCategoryTool#noMatchMessage's own doc. */
    @Test
    void aNameMismatchReturnsTheUsersActualCategoryNamesInsteadOfAFalseNegative() {
        when(analyticsService.topCategories(any(), isNull())).thenReturn(List.of(
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Dining", new BigDecimal("4200"), 12),
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Groceries", new BigDecimal("3200"), 5)));

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
        when(analyticsService.topCategories(userId, YearMonth.of(2026, 8))).thenReturn(List.of(
                new AnalyticsDto.TopCategory(UUID.randomUUID(), "Dining", new BigDecimal("1000"), 3)));

        String result = tool.execute(userId, Map.of("category", "Dining", "month", "2026-08"));

        assertThat(result).contains("1000");
    }

    @Test
    void treatsAnUnparseableMonthAsTheDefaultCurrentMonth() {
        when(analyticsService.topCategories(userId, null)).thenReturn(List.of());

        // Must not throw -- an unparseable month from the model degrades to "current month",
        // not a failed tool call that would derail the whole chat turn.
        String result = tool.execute(userId, Map.of("category", "Dining", "month", "not-a-month"));

        assertThat(result).contains("No categorized spending found");
    }
}
