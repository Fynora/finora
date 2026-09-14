package com.finora.service;

import com.finora.dto.BillingDtos.EntitlementsDto;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D-28 PR4-A. The fail-CLOSED entitlement lookup (proposal §3.2, Correction #3) -- deliberately a
 * different code path from {@code FeatureFlagRepository.isEnabled} (fail-open), not a
 * parameterized variant of it, since the two have opposite failure-mode requirements: an unknown
 * platform toggle should default to "on" (nothing breaks), an unknown paid feature must default to
 * "off" (a mistyped key must never become a revenue leak).
 *
 * No caching -- a live query per check, matching {@code PlatformSettingsService}'s own "no cache"
 * reasoning: an admin's manual plan change should take effect on the next request, not wait out a
 * TTL. Revisit only with evidence of actual load (proposal §3.2's own note), not preemptively.
 */
@Service
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;
    private final FeatureEntitlementRepository featureEntitlementRepository;
    private final PlanRepository planRepository;
    private final ReferralGrantRepository referralGrantRepository;

    public EntitlementService(SubscriptionRepository subscriptionRepository,
                               FeatureEntitlementRepository featureEntitlementRepository,
                               PlanRepository planRepository,
                               ReferralGrantRepository referralGrantRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.featureEntitlementRepository = featureEntitlementRepository;
        this.planRepository = planRepository;
        this.referralGrantRepository = referralGrantRepository;
    }

    /**
     * max(real subscription tier, any ACTIVE referral grant's tier) -- design spec at
     * docs/superpowers/specs/2026-09-14-referral-milestone-rewards-design.md, section 4. This is
     * the single place a referral-earned Plus/Premium becomes real access: every other paywall
     * gate in the codebase reads through hasEntitlement/planCodeFor/entitlementsFor below, so none
     * of them need any change.
     */
    private String effectivePlanCodeFor(UUID userId) {
        String realPlanCode = subscriptionRepository.findActiveOrTrial(userId)
                .flatMap(sub -> planRepository.findById(sub.getPlanId()))
                .map(Plan::getCode)
                .orElse(null);
        String grantTier = referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)
                .map(ReferralGrant::getTier)
                .orElse(null);
        if (grantTier == null) return realPlanCode;
        if (realPlanCode == null) return grantTier;
        return ReferralGrant.tierRank(grantTier) > ReferralGrant.tierRank(realPlanCode) ? grantTier : realPlanCode;
    }

    /** @return false if the user has no active/trial subscription or referral grant, the
     *  effective plan has no row for this feature key, or the row is explicitly disabled -- every
     *  failure mode resolves to "no access", never "everyone gets it free." */
    @Transactional(readOnly = true)
    public boolean hasEntitlement(UUID userId, String featureKey) {
        String effectivePlanCode = effectivePlanCodeFor(userId);
        if (effectivePlanCode == null) return false;
        return planRepository.findByCode(effectivePlanCode)
                .flatMap(plan -> featureEntitlementRepository.findByPlanIdAndFeatureKey(plan.getId(), featureKey))
                .map(FeatureEntitlement::isEnabled)
                .orElse(false);
    }

    /** @return the user's current effective plan code (e.g. "FREE", "PLUS", "PREMIUM") -- the
     *  higher of their real subscription's tier and any ACTIVE referral grant's tier -- or
     *  {@code null} if they have neither. Callers that gate on plan tier rather than a single
     *  feature key (e.g. FynChatOrchestrationService's Free-tier daily question cap) should treat
     *  {@code null} the same as an unrecognized code -- fail toward the more restrictive tier, not
     *  toward "unlimited." */
    @Transactional(readOnly = true)
    public String planCodeFor(UUID userId) {
        return effectivePlanCodeFor(userId);
    }

    @Transactional(readOnly = true)
    public EntitlementsDto entitlementsFor(UUID userId) {
        String effectivePlanCode = effectivePlanCodeFor(userId);
        if (effectivePlanCode == null) {
            return new EntitlementsDto(null, null, Map.of());
        }
        Plan plan = planRepository.findByCode(effectivePlanCode).orElse(null);
        if (plan == null) {
            return new EntitlementsDto(null, null, Map.of());
        }
        Map<String, Boolean> features = featureEntitlementRepository.findByPlanId(plan.getId()).stream()
                .collect(Collectors.toMap(FeatureEntitlement::getFeatureKey, FeatureEntitlement::isEnabled));
        return new EntitlementsDto(plan.getCode(), plan.getName(), features);
    }
}
