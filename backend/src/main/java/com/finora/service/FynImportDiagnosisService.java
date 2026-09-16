package com.finora.service;

import com.finora.dto.HeldStatementDetailDto;
import com.finora.entity.AiAuditLog;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AiAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fyn Phase 2: import/parsing assist -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
 * §6 Phase 2. Suggests a likely root cause for a held statement from structural signals alone
 * (parser version, rule categories, verification outcomes) -- never the statement's actual content.
 * Admin-only, not gated on a customer entitlement (see {@code FeatureEntitlement.FYN_IMPORT_ASSIST}'s
 * own doc for why).
 *
 * <p>Per {@link LlmClient}'s documented contract, this class is the one responsible for writing
 * {@code ai_audit_log} for every call it makes -- on success and on failure alike, so a failed
 * attempt still counts against cost governance and is visible for debugging, not silently absent
 * from the ledger of what Fyn actually did.
 */
@Service
public class FynImportDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(FynImportDiagnosisService.class);

    /** Bumped whenever the prompt text changes -- see {@code ai_audit_log.prompt_version}'s own
     *  doc (plan §4.3) for why this needs to be trackable independent of the model id. */
    private static final String PROMPT_VERSION = "import-diagnosis-v1";
    private static final String TOOL_NAME = "SUGGEST_IMPORT_DIAGNOSIS";
    private static final int MAX_TOKENS = 500;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, helping a Fynora engineer diagnose why an automated bank-statement import \
            was held for review. You are given only structural signals about the failure -- parser \
            version, which trust-predicate rule categories fired, and a short machine-generated \
            summary -- never the customer's actual transactions, balances, or account details. \
            Suggest the most likely root cause and which part of the import pipeline is worth \
            checking first. If the signals given are not enough to say anything useful, say so \
            plainly rather than guessing -- a wrong guess costs the engineer more time than an \
            honest "not enough information here."
            """;

    private final FynAvailabilityGuard availabilityGuard;
    private final HeldStatementService heldStatementService;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;

    public FynImportDiagnosisService(FynAvailabilityGuard availabilityGuard,
                                      HeldStatementService heldStatementService,
                                      LlmClient llmClient,
                                      AiAuditLogRepository aiAuditLogRepository) {
        this.availabilityGuard = availabilityGuard;
        this.heldStatementService = heldStatementService;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
    }

    /**
     * Deliberately NOT {@code @Transactional}. {@link HeldStatementService#detail} and {@link
     * HeldStatementService#recordAiSuggestion} each already manage their own transaction; wrapping
     * this whole method in one would hold a pooled DB connection open for the full duration of the
     * Anthropic HTTP call in between (up to {@link com.finora.integrations.anthropic.AnthropicClient}'s
     * 30s read timeout) -- exactly the class of HikariCP pool-exhaustion risk this codebase has
     * already had to fix elsewhere. Found in review, not by a failure: no test exercises real
     * connection-pool pressure, so this would only have shown up as a production incident under
     * concurrent admin usage.
     */
    public HeldStatementDetailDto suggestDiagnosis(UUID actingAdminId, String heldId) {
        if (!availabilityGuard.importAssistAvailable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fyn's import diagnosis assist is not available right now -- it may be "
                            + "disabled, unconfigured, or over its cost budget.");
        }

        HeldStatementDetailDto detail = heldStatementService.detail(heldId);
        String userPrompt = buildUserPrompt(detail);
        LlmRequest request = LlmRequest.singleTurn(SYSTEM_PROMPT, userPrompt, MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            // Broadened from `catch (ApiException e)`, found in review: AnthropicClient's own
            // defensive "no API key configured" check throws a bare IllegalStateException, not an
            // ApiException -- that path would otherwise skip the audit-log-on-failure write LlmClient's
            // own contract requires, even though it's a defensive path the availability guard above
            // should already prevent in practice.
            writeAuditLog(actingAdminId, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), e.getMessage());
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn could not generate a diagnosis suggestion.");
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);

        // The call already happened and already cost real money by this point. FynPricing.cost()
        // throws rather than silently pricing an unknown model at zero (its own doc explains why),
        // but that must not mean losing the audit row entirely for a call that already spent
        // budget -- found in review: the original version let this propagate uncaught, skipping
        // both the audit write below AND heldStatementService.recordAiSuggestion, discarding a
        // suggestion Anthropic was already paid to generate.
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("Fyn import-diagnosis call succeeded but has no known price for model {} -- "
                    + "add it to FynPricing.RATES", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(actingAdminId, completion.model(), completion.tokensIn(), completion.tokensOut(),
                cost, latencyMs, costError);

        if (completion.content() == null || completion.content().isBlank()) {
            // Not expected today (Phase 2 requests no tool-use, so Claude always returns at least
            // one text block for a plain completion), but the content-block filter in
            // AnthropicClient could theoretically yield an empty string without content() itself
            // being empty -- found in review. Better a clear error than silently persisting a
            // blank suggestion an admin would otherwise stare at with no explanation.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn returned an empty suggestion.");
        }

        heldStatementService.recordAiSuggestion(actingAdminId, heldId, completion.content());
        return heldStatementService.detail(heldId);
    }

    /** Every field here is Tier 0/1 (plan §4.1) -- verified against the actual trust-predicate
     *  code, not assumed: {@code TrustPredicate}'s reason sentences are fixed templates with, at
     *  most, an internal cause-code constant interpolated (e.g. "DIRECTION"), never a statement's
     *  actual amounts or dates. */
    private String buildUserPrompt(HeldStatementDetailDto detail) {
        var summary = detail.summary();
        List<String> lines = List.of(
                "Parser version: " + summary.parserVersion(),
                "Reliability status: " + summary.reliabilityStatus(),
                "Text source: " + summary.textSource(),
                "Header reconstruction uncertain: " + summary.headerReconstructionUncertain(),
                "Hold reason categories: " + summary.holdReasonCategories(),
                "Trigger summary: " + summary.triggerSummary());
        return String.join("\n", lines);
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
            // "issue," not "failed" -- found in review: this also fires for the cost-unknown-model
            // path above, where the call itself succeeded and only its price couldn't be recorded.
            log.warn("Fyn import-diagnosis call issue (admin {}): {}", userId, error);
        }
    }
}
