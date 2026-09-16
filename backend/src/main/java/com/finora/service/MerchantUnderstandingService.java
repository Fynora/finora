package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.*;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.MerchantUnderstandingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 1 of the AI-category-creation design (spec §3/§4) -- a free-text, global understanding
 * of what kind of merchant a counterparty is, cached forever across every user. Never contains a
 * category name (that's Tier 2, UserMerchantCategoryResolutionService). Deliberately not
 * @Transactional, mirroring FynCategorizationFallbackService -- avoids holding a DB connection
 * across the outbound LLM HTTP call.
 */
@Service
public class MerchantUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(MerchantUnderstandingService.class);

    private static final String PROMPT_VERSION = "merchant-understanding-v1";
    private static final String TOOL_NAME = "UNDERSTAND_MERCHANT";
    private static final int MAX_TOKENS = 80;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, describing what kind of merchant a bank transaction narration belongs \
            to. You are given only the raw narration text -- never the amount, account, or any \
            other user-identifying detail. Call the understand_merchant tool with a short, \
            free-text description of the merchant's business (e.g. "Pet supplies retailer \
            selling food, toys, and grooming products"). Do not suggest a spending category --\
            describe the merchant, not how to file it.
            """;

    private static final LlmTool TOOL = new LlmTool(TOOL_NAME,
            "Record a free-text understanding of what kind of merchant this is.",
            Map.of("type", "object",
                    "properties", Map.of("understanding", Map.of("type", "string")),
                    "required", List.of("understanding")));

    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final MerchantUnderstandingRepository understandingRepository;

    public MerchantUnderstandingService(FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                         AiAuditLogRepository aiAuditLogRepository,
                                         MerchantUnderstandingRepository understandingRepository) {
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.understandingRepository = understandingRepository;
    }

    public Optional<String> understand(UUID userId, String counterpartyKey, Transaction.Type direction,
                                        String description) {
        var cached = understandingRepository.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        if (cached.isPresent()) {
            return Optional.of(cached.get().getUnderstanding());
        }
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        LlmRequest request = LlmRequest.withTools(SYSTEM_PROMPT, List.of(LlmMessage.user(description)),
                MAX_TOKENS, List.of(TOOL));
        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            writeAuditLog(userId, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), e.getMessage());
            return Optional.empty();
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("Fyn merchant-understanding call succeeded but has no known price for model {}", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(), cost, latencyMs, costError);

        if (!completion.requestsToolUse()) {
            return Optional.empty();
        }
        Object raw = completion.toolUses().get(0).input().get("understanding");
        if (!(raw instanceof String understanding) || understanding.isBlank()) {
            return Optional.empty();
        }

        understandingRepository.upsert(counterpartyKey, direction.name(), understanding, completion.model(), Instant.now());
        return Optional.of(understanding);
    }

    private void writeAuditLog(UUID userId, String model, int tokensIn, int tokensOut,
                                BigDecimal cost, int latencyMs, String error) {
        AiAuditLog auditLog = new AiAuditLog();
        auditLog.setUserId(userId);
        auditLog.setModel(model != null ? model : "unknown");
        auditLog.setPromptVersion(PROMPT_VERSION);
        auditLog.setToolName(TOOL_NAME);
        auditLog.setTokensIn(tokensIn);
        auditLog.setTokensOut(tokensOut);
        auditLog.setCost(cost);
        auditLog.setLatencyMs(latencyMs);
        auditLog.setError(error);
        aiAuditLogRepository.save(auditLog);
        if (error != null) {
            log.warn("Fyn merchant-understanding call issue (user {}): {}", userId, error);
        }
    }
}
