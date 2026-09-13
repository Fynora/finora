package com.finora.service;

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

    @Override
    public String execute(UUID userId, Map<String, Object> input) {
        var summary = dashboardService.summarize(userId);
        return "Current total balance: ₹" + summary.currentBalance();
    }
}
