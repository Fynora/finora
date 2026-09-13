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

    @Test
    void reportsNoMatchingBudgetForAnUnknownCategory() {
        when(budgetService.listForUser(userId)).thenReturn(List.of(budget("Dining", "5000", "4200")));

        String result = tool.execute(userId, Map.of("category", "Travel"));

        assertThat(result).contains("no budget set for a category matching \"Travel\"");
    }
}
