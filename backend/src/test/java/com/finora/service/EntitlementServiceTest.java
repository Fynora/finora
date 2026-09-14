package com.finora.service;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.entity.Subscription;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private FeatureEntitlementRepository featureEntitlementRepository;
    private PlanRepository planRepository;
    private ReferralGrantRepository referralGrantRepository;
    private EntitlementService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID freePlanId = UUID.randomUUID();
    private final UUID plusPlanId = UUID.randomUUID();
    private final UUID premiumPlanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        featureEntitlementRepository = mock(FeatureEntitlementRepository.class);
        planRepository = mock(PlanRepository.class);
        referralGrantRepository = mock(ReferralGrantRepository.class);
        service = new EntitlementService(subscriptionRepository, featureEntitlementRepository, planRepository,
                referralGrantRepository);

        Plan free = plan(freePlanId, "FREE");
        Plan plus = plan(plusPlanId, "PLUS");
        Plan premium = plan(premiumPlanId, "PREMIUM");
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));
        when(planRepository.findById(freePlanId)).thenReturn(Optional.of(free));
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));
    }

    private Plan plan(UUID id, String code) {
        Plan p = new Plan();
        ReflectionTestUtils.setField(p, "id", id);
        p.setCode(code);
        return p;
    }

    @Test
    void planCodeFor_noActiveGrant_returnsRealPlanCode() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.empty());

        assertThat(service.planCodeFor(userId)).isEqualTo("FREE");
    }

    @Test
    void planCodeFor_activeGrantHigherThanRealPlan_returnsGrantTier() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PREMIUM);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        assertThat(service.planCodeFor(userId)).isEqualTo("PREMIUM");
    }

    @Test
    void hasEntitlement_activeGrantUnlocksFeatureRealPlanDoesNotHave() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PREMIUM);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        FeatureEntitlement fe = new FeatureEntitlement();
        fe.setEnabled(true);
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(premiumPlanId, FeatureEntitlement.FINO_AI))
                .thenReturn(Optional.of(fe));

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.FINO_AI)).isTrue();
    }

    @Test
    void hasEntitlement_grantLowerThanRealPlan_usesRealPlan() {
        Subscription sub = new Subscription();
        sub.setPlanId(premiumPlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PLUS);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        FeatureEntitlement fe = new FeatureEntitlement();
        fe.setEnabled(true);
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(premiumPlanId, FeatureEntitlement.FINO_AI))
                .thenReturn(Optional.of(fe));

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.FINO_AI)).isTrue();
        assertThat(service.planCodeFor(userId)).isEqualTo("PREMIUM");
    }
}
