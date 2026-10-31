package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single checkpoint every Fyn call path checks before making an Anthropic call -- plan §4.5.
 * Two independent reasons a call can be refused, deliberately not collapsed into one boolean:
 * {@code enabled=false} (or a missing API key) is "Fyn is off," while a cost-governance limit is
 * "Fyn is on, but this specific call would cost money we've decided not to spend right now" --
 * different operator response to each.
 *
 * <p>A third reason applies to every per-user check: the user has not said yes to "AI features"
 * ({@code users.ai_processing_consent}, V302). Null -- never asked -- counts as no. See
 * docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md.
 */
@Component
public class FynAvailabilityGuard {

    private final FynProperties properties;
    private final FynCostGovernanceService costGovernanceService;
    private final UserRepository userRepository;

    public FynAvailabilityGuard(FynProperties properties, FynCostGovernanceService costGovernanceService,
                                UserRepository userRepository) {
        this.properties = properties;
        this.costGovernanceService = costGovernanceService;
        this.userRepository = userRepository;
    }

    /** The user's own "AI features" answer is yes. Checked last in each per-user method below, so
     *  an AI path that is off for everyone costs no query. */
    public boolean aiConsentGiven(UUID userId) {
        return userId != null && userRepository.existsByIdAndAiProcessingConsentTrue(userId);
    }

    /** Global kill switch + configured API key + org-wide monthly budget not yet stopped. Every
     *  per-feature check below implies this one. */
    public boolean available() {
        return properties.isEnabled()
                && properties.hasApiKey()
                && costGovernanceService.monthlyBudget().status() != FynCostGovernanceService.BudgetStatus.STOPPED;
    }

    public boolean chatAvailableFor(UUID userId) {
        return available() && properties.isChatEnabled() && !costGovernanceService.userDailyCapReached(userId)
                && aiConsentGiven(userId);
    }

    public boolean insightsAvailableFor(UUID userId) {
        return available() && properties.isInsightsEnabled() && !costGovernanceService.userDailyCapReached(userId)
                && aiConsentGiven(userId);
    }

    /** No per-user daily cap: Phase 2's import-diagnosis assist is admin-triggered, not tied to a
     *  single end user's usage pattern -- still subject to {@link #available()}'s org-wide budget. */
    public boolean importAssistAvailable() {
        return available() && properties.isImportAssistEnabled();
    }

    /** Per-user, like chat/insights -- a categorization call is tied to one user's own
     *  transaction. See docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md §8. */
    public boolean categorizationAvailableFor(UUID userId) {
        return available() && properties.isCategorizationEnabled() && !costGovernanceService.userDailyCapReached(userId)
                && aiConsentGiven(userId);
    }
}
