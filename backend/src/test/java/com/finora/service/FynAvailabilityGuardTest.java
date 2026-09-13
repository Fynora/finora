package com.finora.service;

import com.finora.config.FynProperties;
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
        guard = new FynAvailabilityGuard(properties, costGovernanceService);
    }

    @Test
    void availableByDefaultWithApiKeyConfigured() {
        assertThat(guard.available()).isTrue();
        assertThat(guard.chatAvailableFor(userId)).isTrue();
        assertThat(guard.insightsAvailableFor(userId)).isTrue();
        assertThat(guard.importAssistAvailable()).isTrue();
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
    }

    @Test
    void perFeatureKillSwitchOnlyAffectsThatFeature() {
        properties.setChatEnabled(false);

        assertThat(guard.available()).isTrue();
        assertThat(guard.chatAvailableFor(userId)).isFalse();
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
    }

    @Test
    void userDailyCapOnlyBlocksPerUserFeaturesNotImportAssist() {
        when(costGovernanceService.userDailyCapReached(userId)).thenReturn(true);

        assertThat(guard.chatAvailableFor(userId)).isFalse();
        assertThat(guard.insightsAvailableFor(userId)).isFalse();
        assertThat(guard.importAssistAvailable()).isTrue();
    }
}
