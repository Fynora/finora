package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmMessage;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.integrations.anthropic.LlmClient.ToolResult;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.ChatConversationRepository;
import com.finora.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Fyn Phase 4: chat Q&A -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6
 * Phase 4. The tool-calling loop itself: {@link com.finora.integrations.anthropic.AnthropicClient}
 * translates one request/response pair; this class drives however many round-trips one user
 * message actually needs, executing each requested tool in-process (plan §2 -- no new authz
 * surface, the tool call runs inside this same authenticated request) and feeding the real result
 * back to Claude until it answers in plain text.
 *
 * <p><b>Only the user-visible turns are persisted.</b> The intermediate tool_use/tool_result
 * exchanges within one turn exist only in this method's local {@code history} list and are
 * discarded once the turn resolves -- a future turn doesn't need to re-see how an old balance
 * figure was fetched, only what Fyn concluded. {@code chat_messages.tool_calls_json} records which
 * tools were used on the persisted assistant row, for observability.
 */
@Service
public class FynChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(FynChatOrchestrationService.class);

    private static final String PROMPT_VERSION = "chat-v1";
    private static final int MAX_TOKENS = 400;
    // Hard bound on real Anthropic calls per single user message -- plan §4.4's per-user daily cap
    // bounds spend across a session, this bounds the worst case for ONE message, so a model stuck
    // requesting tools in a loop can't spend a user's entire daily budget on a single question.
    private static final int MAX_TOOL_ROUNDS = 5;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, a personal finance copilot inside Finora. You help the user understand \
            their own spending, balance, and budgets by calling the tools available to you -- \
            never state a number you did not get from a tool call, and never guess or estimate a \
            figure. You are not a financial advisor: if asked for investment advice, whether to buy \
            or sell something, or any recommendation about future financial decisions, politely \
            decline and suggest they talk to a licensed advisor. You only narrate and explain the \
            user's own historical data. Keep answers short -- a few sentences, not a report.
            """;

    private final FynAvailabilityGuard availabilityGuard;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final LlmClient llmClient;
    private final Map<String, FynChatTool> toolsByName;
    private final List<LlmClient.LlmTool> toolDefinitions;

    public FynChatOrchestrationService(FynAvailabilityGuard availabilityGuard,
                                        ChatConversationRepository conversationRepository,
                                        ChatMessageRepository messageRepository,
                                        AiAuditLogRepository aiAuditLogRepository,
                                        LlmClient llmClient, List<FynChatTool> tools) {
        this.availabilityGuard = availabilityGuard;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.llmClient = llmClient;
        this.toolsByName = tools.stream().collect(Collectors.toMap(FynChatTool::name, Function.identity()));
        this.toolDefinitions = tools.stream().map(FynChatTool::toLlmTool).toList();
    }

    public record ChatTurnResult(UUID conversationId, String reply) {}

    /**
     * Not {@code @Transactional} for the same reason {@code FynImportDiagnosisService
     * .suggestDiagnosis} and {@code FynInsightsNarrationService.narrate} aren't: this makes
     * several external HTTP calls to Anthropic (up to {@link #MAX_TOOL_ROUNDS} of them), and
     * holding a pooled DB connection open across all of them would be the exact HikariCP
     * pool-exhaustion risk found in Phase 2's review. Each persistence step below manages its own
     * transaction via the repositories' own Spring Data proxies.
     *
     * @param conversationId null to start a new conversation, otherwise must belong to {@code
     *                        userId} -- see {@link #loadOwnedConversation}
     */
    public ChatTurnResult sendMessage(UUID userId, UUID conversationId, String userMessage) {
        if (!availabilityGuard.chatAvailableFor(userId)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fyn chat is not available right now -- it may be disabled, unconfigured, or "
                            + "over its cost budget.");
        }

        ChatConversation conversation = conversationId != null
                ? loadOwnedConversation(userId, conversationId)
                : startConversation(userId);

        ChatMessage userRow = new ChatMessage();
        userRow.setConversationId(conversation.getId());
        userRow.setRole(ChatMessage.ROLE_USER);
        userRow.setContent(userMessage);
        messageRepository.save(userRow);

        List<LlmMessage> history = new ArrayList<>(loadHistory(conversation.getId()));
        List<String> toolsUsedThisTurn = new ArrayList<>();

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            LlmCompletion completion = callModel(userId, conversation.getId(), history);

            if (!completion.requestsToolUse()) {
                return finish(conversation, completion.content(), toolsUsedThisTurn);
            }

            history.add(LlmMessage.assistantToolUse(completion.toolUses()));
            List<ToolResult> results = new ArrayList<>();
            for (ToolUse toolUse : completion.toolUses()) {
                toolsUsedThisTurn.add(toolUse.name());
                results.add(new ToolResult(toolUse.id(), executeToolSafely(userId, toolUse)));
            }
            history.add(LlmMessage.toolResults(results));
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "Fyn could not finish answering this -- it needed too many tool calls.");
    }

    private LlmCompletion callModel(UUID userId, UUID conversationId, List<LlmMessage> history) {
        long startedAt = System.currentTimeMillis();
        try {
            LlmCompletion completion = llmClient.complete(
                    LlmRequest.withTools(SYSTEM_PROMPT, history, MAX_TOKENS, toolDefinitions));
            int latencyMs = (int) (System.currentTimeMillis() - startedAt);
            writeAuditLog(userId, conversationId, completion, latencyMs, null);
            return completion;
        } catch (RuntimeException e) {
            writeAuditLog(userId, conversationId, null, (int) (System.currentTimeMillis() - startedAt),
                    e.getMessage());
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn could not answer that right now.");
        }
    }

    /** A tool failure (an unrecognized name, or the wrapped service throwing) becomes a tool_result
     *  Claude can read and work around -- "that lookup failed" -- rather than an uncaught exception
     *  that would kill the whole turn over one bad tool call. */
    private String executeToolSafely(UUID userId, ToolUse toolUse) {
        FynChatTool tool = toolsByName.get(toolUse.name());
        if (tool == null) {
            log.warn("Fyn chat: model requested an unknown tool {}", toolUse.name());
            return "Tool \"" + toolUse.name() + "\" does not exist.";
        }
        try {
            return tool.execute(userId, toolUse.input() != null ? toolUse.input() : Map.of());
        } catch (RuntimeException e) {
            log.warn("Fyn chat: tool {} failed: {}", toolUse.name(), e.getClass().getSimpleName());
            return "This lookup failed and could not be completed.";
        }
    }

    private ChatTurnResult finish(ChatConversation conversation, String replyText, List<String> toolsUsed) {
        if (replyText == null || replyText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn returned an empty reply.");
        }
        ChatMessage assistantRow = new ChatMessage();
        assistantRow.setConversationId(conversation.getId());
        assistantRow.setRole(ChatMessage.ROLE_ASSISTANT);
        assistantRow.setContent(replyText);
        if (!toolsUsed.isEmpty()) {
            assistantRow.setToolCallsJson(Map.of("tools", toolsUsed));
        }
        messageRepository.save(assistantRow);

        conversation.setUpdatedAt(Instant.now());
        if (conversation.getTitle() == null) {
            conversation.setTitle(deriveTitle(replyText));
        }
        conversationRepository.save(conversation);

        return new ChatTurnResult(conversation.getId(), replyText);
    }

    /** First line, truncated -- a cheap, good-enough conversation title, same idea as most chat
     *  products; not derived from the user's own message to avoid echoing back anything that could
     *  itself carry sensitive phrasing into a list view. */
    private String deriveTitle(String replyText) {
        String firstLine = replyText.lines().findFirst().orElse(replyText);
        return firstLine.length() > 60 ? firstLine.substring(0, 60) + "…" : firstLine;
    }

    private ChatConversation startConversation(UUID userId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        return conversationRepository.save(conversation);
    }

    /** 404, not 403, for a conversation that either doesn't exist or belongs to someone else --
     *  same reasoning as every other owned-resource lookup in this codebase: confirming "that id
     *  belongs to another user" is itself a small information leak a generic 404 avoids. */
    private ChatConversation loadOwnedConversation(UUID userId, UUID conversationId) {
        ChatConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such conversation."));
        if (!conversation.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such conversation.");
        }
        return conversation;
    }

    private List<LlmMessage> loadHistory(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(m -> ChatMessage.ROLE_USER.equals(m.getRole())
                        ? LlmMessage.user(m.getContent())
                        : LlmMessage.assistant(m.getContent()))
                .toList();
    }

    private void writeAuditLog(UUID userId, UUID conversationId, LlmCompletion completion,
                                int latencyMs, String error) {
        AiAuditLog auditLog = new AiAuditLog();
        auditLog.setUserId(userId);
        auditLog.setConversationId(conversationId);
        auditLog.setModel(completion != null ? completion.model() : "unknown");
        auditLog.setPromptVersion(PROMPT_VERSION);
        if (completion != null && completion.requestsToolUse()) {
            auditLog.setToolName(completion.toolUses().stream().map(ToolUse::name)
                    .collect(Collectors.joining(",")));
        }
        auditLog.setTokensIn(completion != null ? completion.tokensIn() : 0);
        auditLog.setTokensOut(completion != null ? completion.tokensOut() : 0);

        BigDecimal cost = BigDecimal.ZERO;
        String costError = error;
        if (completion != null) {
            try {
                cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
            } catch (IllegalArgumentException e) {
                log.error("Fyn chat call succeeded but has no known price for model {} -- add it to "
                        + "FynPricing.RATES", completion.model());
                costError = "Cost unknown: " + e.getMessage();
            }
        }
        auditLog.setCost(cost);
        auditLog.setLatencyMs(latencyMs);
        auditLog.setError(costError);
        aiAuditLogRepository.save(auditLog);
    }
}
