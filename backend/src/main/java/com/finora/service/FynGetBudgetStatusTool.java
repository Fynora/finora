package com.finora.service;

import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fyn chat tool (Phase 4, plan §6): "am I over budget" / "how's my Dining budget doing". Wraps
 *  {@link BudgetService#listForUser} -- the same rows Budgets.tsx already shows, not a new
 *  computation. Optional category filter; without one, summarizes every budget the user has set. */
@Component
public class FynGetBudgetStatusTool implements FynChatTool {

    private final BudgetService budgetService;

    public FynGetBudgetStatusTool(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @Override
    public String name() {
        return "GET_BUDGET_STATUS";
    }

    @Override
    public String description() {
        return "Returns the user's budget status: monthly limit vs. spent so far this month, for "
                + "one named category or, if omitted, every budget the user has set.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "category", Map.of("type", "string",
                        "description", "The budget's category name; omit to summarize every budget.")));
    }

    @Override
    public String execute(UUID userId, Map<String, Object> input) {
        List<BudgetDto> budgets = budgetService.listForUser(userId);
        if (budgets.isEmpty()) {
            return "The user has not set any budgets yet.";
        }

        Object categoryArg = input.get("category");
        if (categoryArg instanceof String category && !category.isBlank()) {
            return budgets.stream()
                    .filter(b -> b.categoryName().equalsIgnoreCase(category))
                    .findFirst()
                    .map(this::describe)
                    .orElse("The user has no budget set for a category matching \"" + category + "\".");
        }
        return budgets.stream().map(this::describe).reduce((a, b) -> a + "; " + b).orElse("");
    }

    private String describe(BudgetDto b) {
        BigDecimal remaining = b.monthlyLimit().subtract(b.spentThisMonth());
        String status = remaining.signum() < 0 ? "OVER by ₹" + remaining.abs() : "₹" + remaining + " remaining";
        return b.categoryName() + ": ₹" + b.spentThisMonth() + " of ₹" + b.monthlyLimit() + " (" + status + ")";
    }
}
