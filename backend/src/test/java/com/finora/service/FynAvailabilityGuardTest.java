package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynAvailabilityGuardTest {

    private FynProperties properties;
    private FynCostGovernanceService costGovernanceService;
    private UserRepository userRepository;
    private FynAvailabilityGuard guard;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new FynProperties();
        properties.setAnthropicApiKey("test-key");
        costGovernanceService = mock(FynCostGovernanceService.class);
        when(costGovernanceService.monthlyBudget()).thenReturn(
                new FynCostGovernanceService.MonthlyBudget(BigDecimal.ZERO, new BigDecimal("50"),
                        FynCostGovernanceService.BudgetStatus.OK));
        when(costGovernanceService.userDailyCapReached(any())).thenReturn(false);
        userRepository = mock(UserRepository.class);
        // Most tests here are about the operator switches, so the user has said yes; the consent
        // rule has its own tests at the bottom.
        when(userRepository.existsByIdAndAiProcessingConsentTrue(userId)).thenReturn(true);
        guard = new FynAvailabilityGuard(properties, costGovernanceService, userRepository);
    }

    @Test
    void availableByDefaultWithApiKeyConfigured() {
        assertThat(guard.available()).isTrue();
        assertThat(guard.chatAvailableFor(userId)).isTrue();
        assertThat(guard.insightsAvailableFor(userId)).isTrue();
        assertThat(guard.importAssistAvailable()).isTrue();
        assertThat(guard.categorizationAvailableFor(userId)).isTrue();
    }

    @Test
    void unavailableWithNoApiKeyConfigured() {
        properties.setAnthropicApiKey("");

        assertThat(guard.available()).isFalse();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
    }

    @Test
    void globalKillSwitchOverridesEverything() {
        properties.setEnabled(false);

        assertThat(guard.available()).isFalse();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.insightsAvailableFor(userId)).isFalse();
        assertThat(guard.importAssistAvailable()).isFalse();
        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
    }

    @Test
    void perFeatureKillSwitchOnlyAffectsThatFeature() {
        properties.setChatEnabled(false);

        assertThat(guard.available()).isTrue();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.insightsAvailableFor(userId)).isTrue();
        assertThat(guard.importAssistAvailable()).isTrue();
        assertThat(guard.categorizationAvailableFor(userId)).isTrue();
    }

    @Test
    void perFeatureKillSwitchOnlyAffectsCategorization() {
        properties.setCategorizationEnabled(false);

        assertThat(guard.available()).isTrue();
        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
        assertThat(guard.chatAvailableFor(userId)).isTrue();
        assertThat(guard.insightsAvailableFor(userId)).isTrue();
        assertThat(guard.importAssistAvailable()).isTrue();
    }

    @Test
    void monthlyBudgetStoppedBlocksEveryFeature() {
        when(costGovernanceService.monthlyBudget()).thenReturn(
                new FynCostGovernanceService.MonthlyBudget(new BigDecimal("60"), new BigDecimal("50"),
                        FynCostGovernanceService.BudgetStatus.STOPPED));

        assertThat(guard.available()).isFalse();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.importAssistAvailable()).isFalse();
        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
    }

    @Test
    void userDailyCapOnlyBlocksPerUserFeaturesNotImportAssist() {
        when(costGovernanceService.userDailyCapReached(userId)).thenReturn(true);

        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.insightsAvailableFor(userId)).isFalse();
        assertThat(guard.importAssistAvailable()).isTrue();
        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
    }

    /** V302: no yes, no AI -- whether the user said no or was never asked (the query only matches TRUE). */
    @Test
    void everyPerUserFeatureNeedsTheUsersOwnYes() {
        when(userRepository.existsByIdAndAiProcessingConsentTrue(userId)).thenReturn(false);

        assertThat(guard.aiConsentGiven(userId)).isFalse();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.insightsAvailableFor(userId)).isFalse();
        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
        // Admin-triggered and tied to no single user's consent (see the spec).
        assertThat(guard.importAssistAvailable()).isTrue();
        assertThat(guard.available()).isTrue();
    }

    @Test
    void oneUsersYesDoesNotCoverAnother() {
        UUID other = UUID.randomUUID();

        assertThat(guard.chatAvailableFor(userId)).isTrue();
        assertThat(guard.chatAvailableFor(other)).isFalse();
        assertThat(guard.aiConsentGiven(null)).isFalse();
    }
}
