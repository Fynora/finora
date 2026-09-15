package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.SharedMerchantCategoryAiSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Fyn categorization fallback -- spec §8. Consulted only when the shared corpus and every
 * deterministic layer have nothing for a key. Mirrors FynImportDiagnosisService's shape exactly:
 * narrow PII-scrubbed prompt, audit-logged on success and failure, deliberately not
 * @Transactional (same HikariCP pool-exhaustion reasoning -- this also makes an outbound HTTP
 * call).
 */
@Service
public class FynCategorizationFallbackService {

    private static final Logger log = LoggerFactory.getLogger(FynCategorizationFallbackService.class);

    private static final String PROMPT_VERSION = "categorization-fallback-v1";
    private static final String TOOL_NAME = "SUGGEST_CATEGORY";
    private static final int MAX_TOKENS = 20;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, suggesting a spending category for a bank transaction narration. You are \
            given only the raw narration text -- never the amount, account, or any other \
            user-identifying detail. Reply with a short category name only (e.g. "Dining", \
            "Shopping", "Investments"), nothing else. If the narration gives you nothing to go on, \
            reply with exactly "Other".
            """;

    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository;

    public FynCategorizationFallbackService(FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                             AiAuditLogRepository aiAuditLogRepository,
                                             SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository) {
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.aiSuggestionRepository = aiSuggestionRepository;
    }

    public Optional<String> suggest(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        // Spec §4: "a model guessed; don't re-ask until this is stale" -- the cache table existed
        // to avoid re-paying an LLM call for a counterparty key already answered (a real repeat
        // pattern within one statement import), but nothing ever read it before this fix, making
        // every call a fresh LLM call and defeating both the cost-governance intent and the point
        // of upserting rather than appending.
        Optional<com.finora.entity.SharedMerchantCategoryAiSuggestion> cached =
                aiSuggestionRepository.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        if (cached.isPresent()) {
            return Optional.of(cached.get().getCategory());
        }

        LlmRequest request = LlmRequest.singleTurn(SYSTEM_PROMPT, description, MAX_TOKENS);
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
            log.error("Fyn categorization-fallback call succeeded but has no known price for model {}",
                    completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(),
                cost, latencyMs, costError);

        if (completion.content() == null || completion.content().isBlank()) {
            return Optional.empty();
        }

        String category = completion.content().trim();
        aiSuggestionRepository.upsert(counterpartyKey, direction.name(), category, completion.model(), Instant.now());
        return Optional.of(category);
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
            log.warn("Fyn categorization-fallback call issue (user {}): {}", userId, error);
        }
    }
}
