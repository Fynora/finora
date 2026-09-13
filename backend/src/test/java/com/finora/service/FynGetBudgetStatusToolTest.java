package com.finora.service;

import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynGetBudgetStatusToolTest {

    private final BudgetService budgetService = mock(BudgetService.class);
    private final FynGetBudgetStatusTool tool = new FynGetBudgetStatusTool(budgetService);
    private final UUID userId = UUID.randomUUID();

    private static BudgetDto budget(String category, String limit, String spent) {
        return new BudgetDto(UUID.randomUUID(), UUID.randomUUID(), category,
                new BigDecimal(limit), new BigDecimal(spent));
    }

    @Test
    void reportsNoBudgetsSet() {
        when(budgetService.listForUser(userId)).thenReturn(List.of());

        assertThat(tool.execute(userId, Map.of())).contains("has not set any budgets");
    }

    @Test
    void summarizesEveryBudgetWhenNoCategoryGiven() {
        when(budgetService.listForUser(userId)).thenReturn(List.of(
                budget("Dining", "5000", "4200"), budget("Groceries", "3000", "3500")));

        String result = tool.execute(userId, Map.of());

        assertThat(result).contains("Dining", "remaining", "Groceries", "OVER");
    }

    @Test
    void reportsARemainingBudgetWhenUnderTheLimit() {
        when(budgetService.listForUser(userId)).thenReturn(List.of(budget("Dining", "5000", "4200")));

        String result = tool.execute(userId, Map.of("category", "dining"));

        assertThat(result).contains("800 remaining");
    }

    @Test
    void reportsAnOverBudgetAmountWhenOverTheLimit() {
        when(budgetService.listForUser(userId)).thenReturn(List.of(budget("Groceries", "3000", "3500")));

        String result = tool.execute(userId, Map.of("category", "Groceries"));

        assertThat(result).contains("OVER by ₹500");
    }

    /** Budget categories are the same fully user-defined names as spend categories -- a name
     *  mismatch must hand back the user's real budgeted categories so the model can retry with the
     *  right one instead of asserting the false "you have no budget for this." */
    @Test
    void reportsNoMatchingBudgetForAnUnknownCategoryButListsTheRealOnes() {
        when(budgetService.listForUser(userId)).thenReturn(List.of(
                budget("Dining", "5000", "4200"), budget("Groceries", "3000", "1000")));

        String result = tool.execute(userId, Map.of("category", "Travel"));

        assertThat(result).contains("No budget named \"Travel\" was found");
        assertThat(result).contains("Dining", "Groceries");
    }
}
