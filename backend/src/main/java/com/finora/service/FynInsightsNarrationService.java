package com.finora.service;

import com.finora.dto.InsightsDto;
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
import java.util.List;
import java.util.UUID;

/**
 * Fyn Phase 3: insights narration -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
 * §6 Phase 3. Composes a short natural-language summary over {@link InsightsService}'s own
 * already-computed month-over-month category movers -- see that service's class doc: "This is
 * rule/statistics-based, NOT an LLM call... the seam where you'd swap in a real model call." This
 * is that seam.
 *
 * <p><b>Deliberately excludes {@link InsightsDto#topMerchant()} from what reaches Claude.</b>
 * Merchant names are Tier 2 in the plan's data-tier table (§4.1), and Phase 1-4 planned zero Tier 2
 * usage -- category names are a fixed taxonomy (Tier 1), a specific merchant a user shops at is
 * not. The existing app UI already shows the user their own top merchant directly (that's a
 * Finora-to-user question); this is about what leaves Finora's infrastructure to a third party,
 * which is a different question the plan already answered conservatively. If merchant-level
 * narration is wanted later, that is a new Tier 2 decision to make explicitly, not a default to
 * fall into here.
 *
 * <p>Never called from the page load path: this is its own endpoint precisely so a failure here
 * (guard refusal, Anthropic outage, cost cap) never blocks the numeric Insights page underneath,
 * which works identically with or without this ever having existed -- see the plan's own "never
 * block the existing numeric insights page on the AI call" line.
 */
@Service
public class FynInsightsNarrationService {

    private static final Logger log = LoggerFactory.getLogger(FynInsightsNarrationService.class);

    private static final String PROMPT_VERSION = "insights-narration-v2";
    private static final String TOOL_NAME = "NARRATE_INSIGHTS";
    // v1 was 200 -- a genuine 2-5 sentence summary (rather than the old 1-3 sentence gloss) needs
    // more headroom, or Claude's own output gets cut mid-sentence.
    private static final int MAX_TOKENS = 350;

    // v2: this used to gloss ONLY biggestCategory/movers on top of InsightsService's own
    // Java-templated sentences. Per the repo owner's explicit decision (this file's own doc
    // comment already flagged the topMerchant exclusion as deliberate), Fyn's narration now
    // REPLACES those Java sentences as the primary insight text wherever it's available -- the
    // page falls back to the Java sentences only when narration fails/is unavailable. Still Tier 1
    // only: coverageCaveat is a statement-completeness fact, not merchant/account identity, so it's
    // safe to add here alongside biggestCategory/movers. topMerchant, the "new category" sentence,
    // and the budget-recommendation sentence stay Java-only -- none of those three have a
    // structured, already-tested field this prompt can safely draw from without either sending a
    // merchant name (topMerchant) or re-deriving logic InsightsService already owns (the other two).
    private static final String SYSTEM_PROMPT = """
            You are Fyn, narrating a Finora user's own spending insights for this month. You are \
            given already-computed category-level numbers and a note about statement coverage --
            never raw transactions, merchant names, or account details. Write a short, natural \
            summary (2-5 plain sentences) covering what's worth knowing this month: the overall \
            pattern, the biggest category, any category that moved notably versus its recent \
            average, and the coverage note if one is given. Use only the numbers given, and never \
            invent a figure that isn't in the data. Never name a specific merchant, vendor, or \
            business -- you are only given category-level data, never a merchant name. Never \
            recommend a specific financial product, investment, or action beyond noticing a \
            pattern -- you are narrating history, not advising. If the data given doesn't support \
            saying anything meaningful, say spending looks steady rather than inventing a trend. \
            Reply in plain prose only -- no markdown formatting (no **bold**, no bullet points): \
            the page renders your reply as plain text.
            """;

    private final FynAvailabilityGuard availabilityGuard;
    private final InsightsService insightsService;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;

    public FynInsightsNarrationService(FynAvailabilityGuard availabilityGuard, InsightsService insightsService,
                                        LlmClient llmClient, AiAuditLogRepository aiAuditLogRepository) {
        this.availabilityGuard = availabilityGuard;
        this.insightsService = insightsService;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
    }

    /**
     * Not {@code @Transactional} -- same reasoning as {@code FynImportDiagnosisService
     * .suggestDiagnosis}, found the hard way in that class's own review: wrapping the Anthropic
     * HTTP call in a transaction holds a pooled DB connection open for its full duration. {@link
     * InsightsService#build} manages its own read-only transaction already.
     */
    public String narrate(UUID userId, String month) {
        if (!availabilityGuard.insightsAvailableFor(userId)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fyn's insights narration is not available right now.");
        }

        InsightsDto insights = insightsService.build(userId, month);
        String userPrompt = buildUserPrompt(insights);
        if (userPrompt == null) {
            // Nothing worth narrating (no movers, no biggest category -- e.g. the user's first
            // month, or a month with no reportable expenses). Not an error: a caller degrading to
            // "no narration" for a genuinely empty month is correct, not a failure to log or audit.
            throw new ApiException(HttpStatus.NOT_FOUND, "Nothing to narrate for this month yet.");
        }
        LlmRequest request = LlmRequest.singleTurn(SYSTEM_PROMPT, userPrompt, MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            writeAuditLog(userId, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), e.getMessage());
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn could not narrate your insights.");
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("Fyn insights narration call succeeded but has no known price for model {} -- "
                    + "add it to FynPricing.RATES", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(),
                cost, latencyMs, costError);

        if (completion.content() == null || completion.content().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Fyn returned an empty narration.");
        }
        return completion.content();
    }

    /** Tier 1 only (plan §4.1): category names, the numbers already computed for them, and
     *  statement-coverage completeness -- never {@code topMerchant} (a real merchant/vendor name).
     *  Returns {@code null} when there's nothing worth narrating -- an empty {@code movers} list,
     *  no {@code biggestCategory}, and no {@code coverageCaveat} together mean the month genuinely
     *  has no reportable pattern yet. */
    private String buildUserPrompt(InsightsDto insights) {
        List<String> lines = new java.util.ArrayList<>();
        if (insights.biggestCategory() != null) {
            lines.add("Biggest category this month: " + insights.biggestCategory().name()
                    + ", total " + insights.biggestCategory().amount());
        }
        for (InsightsDto.CategoryMover mover : insights.movers()) {
            lines.add("Category \"" + mover.category() + "\": current " + mover.current()
                    + ", prior average " + mover.priorAverage()
                    + ", change " + (mover.pctChange() == null ? "unknown" : mover.pctChange() + "%"));
        }
        // Statement coverage is import completeness, not merchant/account identity -- the same
        // Tier 1 safety class as movers/biggestCategory above, so it's fine to add here even
        // though it wasn't part of v1's prompt.
        if (insights.coverageCaveat() != null) {
            lines.add("Note: some transactions for " + insights.coverageCaveat().month()
                    + " may be missing because of a gap in imported statements -- mention this "
                    + "briefly if relevant.");
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
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
            log.warn("Fyn insights narration call issue (user {}): {}", userId, error);
        }
    }
}
