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

    /** AI insights' month story (AiInsightGenerator) -- customer-facing, gated on {@code FYN_INSIGHTS}.
     *  Tier 1 only: an allowlisted fact sheet, never a payee, person, account or narration
     *  (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §4.1). */
    @Bean
    public FynToolDescriptor aiInsightStoryTool() {
        return new FynToolDescriptor(
                "AI_INSIGHT_STORY",
                "Chooses what matters in a user's own month from server-computed facts and words it, figures as placeholders.",
                FeatureEntitlement.FYN_INSIGHTS,
                FynDataTier.TIER_1_AGGREGATE,
                true);
    }

    /** AI insights' "Why?" on a running-high category -- same entitlement and tier as the story. */
    @Bean
    public FynToolDescriptor aiInsightWhyTool() {
        return new FynToolDescriptor(
                "AI_INSIGHT_WHY",
                "Explains from server-computed facts why one of a user's categories is above its usual month.",
                FeatureEntitlement.FYN_INSIGHTS,
                FynDataTier.TIER_1_AGGREGATE,
                true);
    }
}
