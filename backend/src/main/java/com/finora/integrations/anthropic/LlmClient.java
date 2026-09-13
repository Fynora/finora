package com.finora.integrations.anthropic;

import java.util.List;
import java.util.Map;

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
 *
 * <p><b>Tool-use (Phase 4, plan §6).</b> {@link LlmRequest#tools()} and {@link LlmMessage}'s
 * {@code toolUses}/{@code toolResults} are additive to the Phase 1/2/3 shape, not a replacement --
 * {@link LlmMessage#user} and {@link LlmMessage#assistant} still construct exactly the plain-text
 * messages Phase 2/3 always used, and {@link LlmCompletion#content()} is still the final text.
 * There is no separate method for a "tool round": the caller drives the loop itself by appending
 * {@link LlmMessage#assistantToolUse} (what the model asked for) and {@link
 * LlmMessage#toolResults} (what the tool actually returned) to the running message list and
 * calling {@link #complete} again -- the same method, a longer conversation.
 *
 * <p><b>This interface does not write to {@code ai_audit_log} (plan §4.3) itself, and nothing in
 * Phase 1 does either.</b> That is a deliberate gap, not an oversight: the right shape for that
 * write (once per user-visible turn? once per internal tool-call round?) depends on Phase 4's
 * tool-use loop design, which doesn't exist yet -- building an auditing wrapper now would be
 * guessing at that shape. But it means <b>every caller added in Phase 2-4 is individually
 * responsible for writing an {@code AiAuditLog} row for every {@link #complete} call it makes</b>
 * (userId, conversationId, promptVersion, toolName -- none of which this interface's {@link
 * LlmRequest}/{@link LlmCompletion} carry, since those are orchestration-level facts, not
 * LLM-call facts). Skipping it silently defeats {@code FynCostGovernanceService}'s daily/monthly
 * caps for that call path -- they only ever see what actually got written.
 */
public interface LlmClient {

    LlmCompletion complete(LlmRequest request);

    /**
     * @param systemPrompt enforces the product contract (plan §1) -- e.g. "decline advice
     *                      requests, only narrate the user's own historical data"
     * @param messages      conversation so far, oldest first
     * @param maxTokens     hard ceiling on the response -- Fyn's answers are short by design
     *                      (aggregate-and-compose, plan §3), so this should stay small
     * @param tools         empty for Phase 2/3's plain narration calls; Phase 4's chat tools
     *                      (plan §4.1) for the chat loop
     */
    record LlmRequest(String systemPrompt, List<LlmMessage> messages, int maxTokens, double temperature,
                       List<LlmTool> tools) {

        public static LlmRequest singleTurn(String systemPrompt, String userMessage, int maxTokens) {
            return new LlmRequest(systemPrompt, List.of(LlmMessage.user(userMessage)), maxTokens, 0.0, List.of());
        }

        /** Phase 4: a multi-message conversation with tools available. Temperature fixed at 0.0,
         *  same reasoning as {@link #singleTurn} -- Fyn narrates and answers from given facts, it
         *  doesn't need creative variance. */
        public static LlmRequest withTools(String systemPrompt, List<LlmMessage> messages, int maxTokens,
                                            List<LlmTool> tools) {
            return new LlmRequest(systemPrompt, messages, maxTokens, 0.0, tools);
        }
    }

    /** One tool Claude may call. {@code inputSchema} is a JSON Schema object (Anthropic's own
     *  format: {@code {"type": "object", "properties": {...}, "required": [...]}}) -- passed
     *  through as a plain map rather than a typed builder, since exactly four schemas exist (plan
     *  §4.1's tool list) and a schema-builder abstraction has no second use to justify it yet. */
    record LlmTool(String name, String description, Map<String, Object> inputSchema) {}

    /** A tool call the model asked for -- {@code id} must be echoed back verbatim in the matching
     *  {@link ToolResult} so Claude can match a result to the call that produced it. */
    record ToolUse(String id, String name, Map<String, Object> input) {}

    /** What a tool actually returned, keyed to the {@link ToolUse#id} it answers. {@code content}
     *  is a string, not structured JSON -- Anthropic's tool_result accepts either, and a string is
     *  simpler for the model to read back given these tools return small, already-narrow facts
     *  (plan §4.2, Financial Facts Layer), not large structured payloads worth a schema of their
     *  own. */
    record ToolResult(String toolUseId, String content) {}

    /**
     * One turn. Plain text (Phase 2/3, and Phase 4's final answer) carries {@code content} with
     * empty {@code toolUses}/{@code toolResults}. A turn where Claude wants to call a tool carries
     * {@code toolUses} instead, with {@code content} null -- {@link #requestsToolUse()} is the
     * caller's signal to execute those tools and continue the loop rather than treat this as the
     * final answer.
     */
    record LlmMessage(String role, String content, List<ToolUse> toolUses, List<ToolResult> toolResults) {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";

        public static LlmMessage user(String content) {
            return new LlmMessage(ROLE_USER, content, List.of(), List.of());
        }

        public static LlmMessage assistant(String content) {
            return new LlmMessage(ROLE_ASSISTANT, content, List.of(), List.of());
        }

        /** Replays what Claude itself asked for, back into the message history -- required by
         *  Anthropic's own protocol: a tool_result has to follow the assistant turn that requested
         *  it, in the same conversation. */
        public static LlmMessage assistantToolUse(List<ToolUse> toolUses) {
            return new LlmMessage(ROLE_ASSISTANT, null, toolUses, List.of());
        }

        /** The tool's actual output, sent back as the next user-role turn -- Anthropic's protocol
         *  requires tool results on a user-role message, not a new assistant one. */
        public static LlmMessage toolResults(List<ToolResult> results) {
            return new LlmMessage(ROLE_USER, null, List.of(), results);
        }
    }

    /** {@code tokensIn}/{@code tokensOut}/{@code model} feed {@code ai_audit_log} (plan §4.3)
     *  directly -- every field here exists because some caller needs to persist it, not for
     *  completeness. {@code content} is null when {@link #requestsToolUse()} is true -- Claude
     *  asked for a tool call instead of answering. */
    record LlmCompletion(String content, List<ToolUse> toolUses, String model, int tokensIn, int tokensOut,
                          String stopReason) {

        public boolean requestsToolUse() {
            return toolUses != null && !toolUses.isEmpty();
        }
    }
}
