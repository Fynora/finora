package com.finora.service;

import com.finora.config.FynProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single checkpoint every Fyn call path checks before making an Anthropic call -- plan §4.5.
 * Two independent reasons a call can be refused, deliberately not collapsed into one boolean:
 * {@code enabled=false} (or a missing API key) is "Fyn is off," while a cost-governance limit is
 * "Fyn is on, but this specific call would cost money we've decided not to spend right now" --
 * different operator response to each.
 */
@Component
public class FynAvailabilityGuard {

    private final FynProperties properties;
    private final FynCostGovernanceService costGovernanceService;

    public FynAvailabilityGuard(FynProperties properties, FynCostGovernanceService costGovernanceService) {
        this.properties = properties;
        this.costGovernanceService = costGovernanceService;
    }

    /** Global kill switch + configured API key + org-wide monthly budget not yet stopped. Every
     *  per-feature check below implies this one. */
    public boolean available() {
        return properties.isEnabled()
                && properties.hasApiKey()
                && costGovernanceService.monthlyBudget().status() != FynCostGovernanceService.BudgetStatus.STOPPED;
    }

    public boolean chatAvailableFor(UUID userId) {
        return available() && properties.isChatEnabled() && !costGovernanceService.userDailyCapReached(userId);
    }

    public boolean insightsAvailableFor(UUID userId) {
        return available() && properties.isInsightsEnabled() && !costGovernanceService.userDailyCapReached(userId);
    }

    /** No per-user daily cap: Phase 2's import-diagnosis assist is admin-triggered, not tied to a
     *  single end user's usage pattern -- still subject to {@link #available()}'s org-wide budget. */
    public boolean importAssistAvailable() {
        return available() && properties.isImportAssistEnabled();
    }
}
