package com.finora.integrations.anthropic;

import java.util.List;

/**
 * The seam between Fyn's orchestration logic and whichever LLM actually answers it -- see
 * docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md §2 (second review round, §11
 * item 1). Exactly one implementation exists ({@link AnthropicClient}): this is dependency
 * inversion at the cost of one interface, not a provider hierarchy built for providers that don't
 * exist yet -- that fuller abstraction (§2 also, first review round) was explicitly rejected until
 * there's a real second caller.
 *
 * <p>Deliberately provider-agnostic in shape: no Anthropic-specific field (model id strings,
 * Anthropic's JSON wire format) crosses this boundary. {@link AnthropicClient} owns that mapping
 * entirely.
 */
public interface LlmClient {

    LlmCompletion complete(LlmRequest request);

    /**
     * @param systemPrompt enforces the product contract (plan §1) -- e.g. "decline advice
     *                      requests, only narrate the user's own historical data"
     * @param messages      conversation so far, oldest first
     * @param maxTokens     hard ceiling on the response -- Fyn's answers are short by design
     *                      (aggregate-and-compose, plan §3), so this should stay small
     */
    record LlmRequest(String systemPrompt, List<LlmMessage> messages, int maxTokens, double temperature) {

        public static LlmRequest singleTurn(String systemPrompt, String userMessage, int maxTokens) {
            return new LlmRequest(systemPrompt, List.of(LlmMessage.user(userMessage)), maxTokens, 0.0);
        }
    }

    record LlmMessage(String role, String content) {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";

        public static LlmMessage user(String content) { return new LlmMessage(ROLE_USER, content); }
        public static LlmMessage assistant(String content) { return new LlmMessage(ROLE_ASSISTANT, content); }
    }

    /** {@code tokensIn}/{@code tokensOut}/{@code model} feed {@code ai_audit_log} (plan §4.3)
     *  directly -- every field here exists because some caller needs to persist it, not for
     *  completeness. */
    record LlmCompletion(String content, String model, int tokensIn, int tokensOut, String stopReason) {}
}
