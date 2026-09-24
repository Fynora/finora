package com.finora.service;

import com.finora.config.CacheConfig;
import com.finora.dto.AnalyticsDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Fyn chat tool (Phase 4, plan §6): "what did I spend on this month" / "give me an overview" --
 *  every category's count + total, not one named category (that's {@link
 *  FynGetSpendByCategoryTool}). Same underlying aggregate ({@link
 *  AnalyticsService#categoryBreakdown}, the dashboard's own spend-by-category grouping) as that
 *  tool; this tool just doesn't filter it down to one name. */
@Component
public class FynGetRecentTransactionsSummaryTool implements FynChatTool {

    private final AnalyticsService analyticsService;

    public FynGetRecentTransactionsSummaryTool(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Override
    public String name() {
        return "GET_RECENT_TRANSACTIONS_SUMMARY";
    }

    @Override
    public String description() {
        return "Returns an overview of the user's spending this month (or a given month): "
                + "every category's transaction count and total, not individual transactions.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "month", Map.of("type", "string",
                        "description", "YYYY-MM; omit for this month (the result says which month it covers).")));
    }

    /** {@code @Cacheable} (see {@link CacheConfig#FYN_TOOL_RESULT_CACHE}). Same raw-argument-key,
     *  30s-staleness-bound reasoning as {@link FynGetSpendByCategoryTool#execute}. */
    @Override
    @Cacheable(cacheNames = CacheConfig.FYN_TOOL_RESULT_CACHE,
            key = "'GET_RECENT_TRANSACTIONS_SUMMARY:' + #userId + ':' + #input.get('month')", sync = true)
    public String execute(UUID userId, Map<String, Object> input) {
        FynSpendPeriod period = FynSpendPeriod.resolve(analyticsService, userId, input.get("month"));
        List<AnalyticsDto.CategorySpend> categories = analyticsService.categoryBreakdown(userId, period.month());
        BigDecimal total = analyticsService.totalExpense(userId, period.month());

        if (categories.isEmpty() && total.signum() == 0) {
            return period.label() + " No spending found for that period.";
        }
        // The total is stated outright because the model otherwise adds up the categories, and
        // that sum is not the dashboard's Expenses figure: investment transfers stay a category
        // (as on the dashboard's donut) but are left out of the total.
        String byCategory = categories.stream()
                .map(c -> c.categoryName() + ": ₹" + c.totalSpend() + " (" + c.transactionCount() + " txns)")
                .collect(Collectors.joining("; "));
        return period.label() + " Total spend: ₹" + total
                + " (the dashboard's Expenses figure; investment transfers are excluded, so quote this "
                + "rather than adding up the categories). By category: " + byCategory;
    }
}
