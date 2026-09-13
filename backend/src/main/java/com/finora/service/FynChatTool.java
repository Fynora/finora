package com.finora.service;

import com.finora.entity.FeatureEntitlement;
import com.finora.integrations.anthropic.LlmClient;

import java.util.Map;
import java.util.UUID;

/**
 * One tool Fyn's chat (Phase 4, plan §4.1/§6) can call. Every implementation wraps an existing
 * service call with the request's own {@code @CurrentUser} -- no new authz surface, per plan §2 --
 * and returns a small string fact, never raw transactions/account details (plan §3, Financial
 * Facts Layer: the fact is resolved to a real value here, before anything reaches Claude).
 *
 * <p>{@link #toDescriptor()} is the single source of truth for this tool's {@link
 * FynToolDescriptor} -- {@link FynToolRegistry} derives it automatically from every {@code
 * FynChatTool} bean, so a tool's governance metadata (entitlement, data tier) can never drift from
 * what it actually is, the way two independent declarations of the same tool could.
 */
public interface FynChatTool {

    String name();

    String description();

    /** JSON Schema object (Anthropic's own format), e.g. {@code {"type": "object", "properties":
     *  {...}, "required": [...]}}. */
    Map<String, Object> inputSchema();

    /** All tools planned for Phase 4 are Tier 1 (plan §4.1) -- aggregates only, never a merchant
     *  name, transaction description, or account identifier. */
    default FynDataTier maxDataTier() {
        return FynDataTier.TIER_1_AGGREGATE;
    }

    /** @return the fact, as a short string ready to send back to Claude as a tool_result. Never
     *          throws for "no data found" -- that's a legitimate, narratable answer ("no spending
     *          in that category this month"), not a tool failure. */
    String execute(UUID userId, Map<String, Object> input);

    default LlmClient.LlmTool toLlmTool() {
        return new LlmClient.LlmTool(name(), description(), inputSchema());
    }

    default FynToolDescriptor toDescriptor() {
        return new FynToolDescriptor(name(), description(), FeatureEntitlement.FYN_CHAT, maxDataTier(), true);
    }
}
