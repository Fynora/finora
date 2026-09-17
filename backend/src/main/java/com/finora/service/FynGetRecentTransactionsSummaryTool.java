package com.finora.service;

import com.finora.config.CacheConfig;
import com.finora.dto.AnalyticsDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fyn chat tool (Phase 4, plan §6): "what did I spend on this month" / "give me an overview" --
 *  every category's count + total, not one named category (that's {@link
 *  FynGetSpendByCategoryTool}). Same underlying aggregate ({@link
 *  AnalyticsService#topCategories}) as that tool and Phase 3's insights narration; this tool just
 *  doesn't filter it down to one name. */
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
                        "description", "YYYY-MM; omit for the current reporting month.")));
    }

    /** {@code @Cacheable} (see {@link CacheConfig#FYN_TOOL_RESULT_CACHE}). Same raw-argument-key,
     *  30s-staleness-bound reasoning as {@link FynGetSpendByCategoryTool#execute}. */
    @Override
    @Cacheable(cacheNames = CacheConfig.FYN_TOOL_RESULT_CACHE,
            key = "'GET_RECENT_TRANSACTIONS_SUMMARY:' + #userId + ':' + #input.get('month')", sync = true)
    public String execute(UUID userId, Map<String, Object> input) {
        YearMonth month = FynGetSpendByCategoryTool.parseMonth(input.get("month"));
        List<AnalyticsDto.TopCategory> categories = analyticsService.topCategories(userId, month);

        if (categories.isEmpty()) {
            return "No categorized spending found for that period.";
        }
        return categories.stream()
                .map(c -> c.categoryName() + ": ₹" + c.totalSpend() + " (" + c.transactionCount() + " txns)")
                .reduce((a, b) -> a + "; " + b)
                .orElse("No categorized spending found for that period.");
    }
}
