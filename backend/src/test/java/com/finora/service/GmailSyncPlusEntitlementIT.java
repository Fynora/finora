package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gmail sync moved from Premium-only (V163) to Plus and Premium (owner decision 2026-09-21: the
 * public page shows Free and Plus only, and Gmail receipts is one of Plus's real benefits). Checked
 * against the real seeded plan rows, not mocks. Free must stay without it: EntitlementService fails
 * closed, so "no row" means "not entitled".
 */
class GmailSyncPlusEntitlementIT extends AbstractIntegrationTest {

    @Autowired private PlanRepository planRepository;
    @Autowired private FeatureEntitlementRepository featureEntitlementRepository;

    private boolean granted(String planCode) {
        Plan plan = planRepository.findByCode(planCode).orElseThrow();
        return featureEntitlementRepository.findByPlanIdAndFeatureKey(plan.getId(), FeatureEntitlement.GMAIL_SYNC)
                .map(FeatureEntitlement::isEnabled)
                .orElse(false);
    }

    @Test
    void plusAndPremiumHaveGmailSyncButFreeDoesNot() {
        assertThat(granted("FREE")).isFalse();
        assertThat(granted("PLUS")).isTrue();
        assertThat(granted("PREMIUM")).isTrue();
    }
}
