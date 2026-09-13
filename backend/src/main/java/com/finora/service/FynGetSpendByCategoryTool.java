package com.finora.service;

import com.finora.dto.AnalyticsDto;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fyn chat tool (Phase 4, plan §6): "how much did I spend on X". Wraps {@link
 *  AnalyticsService#topCategories} (the same aggregate Phase 3's insights narration already uses,
 *  and the customer-facing Analytics page), filtered case-insensitively to the named category --
 *  no new query, no new computation. Limited to the top 10 categories by spend (same limit the
 *  underlying service already applies): a category outside that range reports as no spend found,
 *  which is the honest answer for "not among what you actually spent meaningfully on," not a bug. */
@Component
public class FynGetSpendByCategoryTool implements FynChatTool {

    private final AnalyticsService analyticsService;

    public FynGetSpendByCategoryTool(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Override
    public String name() {
        return "GET_SPEND_BY_CATEGORY";
    }

    @Override
    public String description() {
        return "Returns how much the user spent in one named category (e.g. \"Dining\", \"Groceries\") "
                + "this month, or a given month.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "category", Map.of("type", "string", "description", "The category name to look up."),
                "month", Map.of("type", "string",
                        "description", "YYYY-MM; omit for the current reporting month.")),
                "required", List.of("category"));
    }

    @Override
    public String execute(UUID userId, Map<String, Object> input) {
        Object categoryArg = input.get("category");
        if (!(categoryArg instanceof String category) || category.isBlank()) {
            return "No category was given -- ask the user which category they mean.";
        }
        YearMonth month = parseMonth(input.get("month"));

        List<AnalyticsDto.TopCategory> categories = analyticsService.topCategories(userId, month);
        return categories.stream()
                .filter(c -> c.categoryName().equalsIgnoreCase(category))
                .findFirst()
                .map(c -> "Category \"" + c.categoryName() + "\": ₹" + c.totalSpend()
                        + " across " + c.transactionCount() + " transactions.")
                .orElse("No spending found in a category matching \"" + category
                        + "\" for that period (only the top 10 categories by spend are tracked).");
    }

    /** Defaults to the current reporting month on anything unparseable, rather than failing the
     *  whole tool call over a formatting slip -- Claude generates this argument itself and should
     *  reliably produce YYYY-MM, but a wrong guess here is cheap to recover from silently. */
    static YearMonth parseMonth(Object monthArg) {
        if (!(monthArg instanceof String monthStr) || monthStr.isBlank()) return null;
        try {
            return YearMonth.parse(monthStr);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
