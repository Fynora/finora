package com.finora.config;

import com.finora.entity.FeatureEntitlement;
import com.finora.service.FynDataTier;
import com.finora.service.FynToolDescriptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers each Fyn tool's governance metadata with {@code FynToolRegistry} (plan §4.1). One
 * {@code @Bean} method per tool -- Spring collects every {@code FynToolDescriptor} bean into the
 * {@code List<FynToolDescriptor>} the registry's constructor takes, so a new tool is added here,
 * not by editing the registry itself.
 */
@Configuration
public class FynToolConfig {

    /** Phase 2's import-diagnosis assist -- admin-only, so {@code requiredEntitlement} is
     *  {@code null} (no customer entitlement gates it; see {@code FeatureEntitlement
     *  .FYN_IMPORT_ASSIST}'s own doc). Still {@code auditEnabled = true}: an admin tool spending
     *  real Anthropic API cost is exactly what {@code ai_audit_log} exists to track, per plan
     *  §4.1's "every financial tool planned so far" -- admin-only doesn't mean cost-free. */
    @Bean
    public FynToolDescriptor suggestImportDiagnosisTool() {
        return new FynToolDescriptor(
                "SUGGEST_IMPORT_DIAGNOSIS",
                "Suggests a likely root cause for a held statement from structural parser signals.",
                null,
                FynDataTier.TIER_1_AGGREGATE,
                true);
    }

    /** Phase 3's insights narration -- customer-facing, gated on {@code FYN_INSIGHTS}. Tier 1
     *  only: deliberately excludes merchant names (Tier 2), see {@code
     *  FynInsightsNarrationService}'s own doc for why. */
    @Bean
    public FynToolDescriptor narrateInsightsTool() {
        return new FynToolDescriptor(
                "NARRATE_INSIGHTS",
                "Composes a short natural-language summary of a user's own month-over-month category insights.",
                FeatureEntitlement.FYN_INSIGHTS,
                FynDataTier.TIER_1_AGGREGATE,
                true);
    }
}
