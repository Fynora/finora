package com.finora.service;

import com.finora.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Fyn chat tool (Phase 4, plan §6): "what's my balance". Wraps {@link DashboardService#summarize},
 *  the same aggregate the Dashboard page itself shows -- not a new balance computation. No input:
 *  there's exactly one "current total balance" to ask about. */
@Component
public class FynGetBalanceTool implements FynChatTool {

    private final DashboardService dashboardService;

    public FynGetBalanceTool(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Override
    public String name() {
        return "GET_BALANCE";
    }

    @Override
    public String description() {
        return "Returns the user's current total balance across all their accounts, in INR.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    /** {@code @Cacheable} (see {@link CacheConfig#FYN_TOOL_RESULT_CACHE}): the same balance gets
     *  re-fetched for a literal repeat question, or a different question later in the same
     *  conversation that happens to also need it -- no reason to hit Postgres again inside the
     *  cache's 30s window for a value that hasn't changed. */
    @Override
    @Cacheable(cacheNames = CacheConfig.FYN_TOOL_RESULT_CACHE, key = "'GET_BALANCE:' + #userId", sync = true)
    public String execute(UUID userId, Map<String, Object> input) {
        var summary = dashboardService.summarize(userId);
        return "Current total balance: ₹" + summary.currentBalance();
    }
}
