package com.finora.service;

import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import com.finora.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
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

    /** {@code @Cacheable} (see {@link CacheConfig#FYN_TOOL_RESULT_CACHE}). Keyed on the raw {@code
     *  category} argument, not a normalized form -- a cache miss on differing case/whitespace just
     *  re-runs the same cheap lookup, which is harmless, versus a normalizer here silently drifting
     *  from whatever normalization (or lack of it) {@link #execute} itself applies. */
    @Override
    @Cacheable(cacheNames = CacheConfig.FYN_TOOL_RESULT_CACHE,
            key = "'GET_BUDGET_STATUS:' + #userId + ':' + #input.get('category')", sync = true)
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
                    .orElse(noMatchMessage(category, budgets));
        }
        return budgets.stream().map(this::describe).reduce((a, b) -> a + "; " + b).orElse("");
    }

    /** Same reasoning as {@link FynGetSpendByCategoryTool#noMatchMessage}: category names are
     *  fully user-defined, so a bare miss reads as "the user has no budget for this," which is
     *  often just a name mismatch, not the truth. Hand back the user's actual budgeted category
     *  names so the model can retry with the right one instead of asserting a wrong negative. */
    private String noMatchMessage(String category, List<BudgetDto> budgets) {
        String actualNames = budgets.stream()
                .map(BudgetDto::categoryName)
                .collect(java.util.stream.Collectors.joining(", "));
        return "No budget named \"" + category + "\" was found. The user's actual budgeted categories "
                + "are: " + actualNames + ". If one of these is clearly what the user meant, call this "
                + "tool again with that exact name.";
    }

    private String describe(BudgetDto b) {
        BigDecimal remaining = b.monthlyLimit().subtract(b.spentThisMonth());
        String status = remaining.signum() < 0 ? "OVER by ₹" + remaining.abs() : "₹" + remaining + " remaining";
        return b.categoryName() + ": ₹" + b.spentThisMonth() + " of ₹" + b.monthlyLimit() + " (" + status + ")";
    }
}
