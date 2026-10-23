package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.entity.AiAuditLog;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AiAuditLogRepository;
import com.finora.service.AiInsightFacts.FactSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * One model call for an AI insight (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md
 * §5): the fact sheet's model JSON in, a template out, checked by {@link AiInsightChecker}. Never
 * throws for a model failure -- every ending is an {@link AiInsightReason}, and every call that reached
 * the model writes one ai_audit_log row (tokens, cost, timing, plan, reason; never the prompt, the
 * reply or the sheet).
 *
 * <p>At most {@code app.fyn.insights-max-concurrent} calls run at once on an instance (§5.4): a call
 * beyond that gets {@link AiInsightReason#CONCURRENCY_LIMIT} at once, without waiting, so a burst can
 * never pile slow model calls onto request threads. The client's own timeouts (5 s connect, 30 s read)
 * and its no-retry policy apply.
 */
@Component
public class AiInsightGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiInsightGenerator.class);

    /** v2 (2026-10-24): the checker's rules the v1 replies broke most -- multiples such as "double",
     *  days of the month, "one" as a count, a figure before its subject, "usual" beside a this-month
     *  figure, mixed directions, "going to" -- as a numbered list with the form to write instead; a
     *  story with no rows is not asked to rank; each change fact carries its direction. Measured by
     *  AiInsightsEvaluationIT on its 30 histories, before and after (the PR has the numbers). */
    public static final String STORY_PROMPT_VERSION = "ai-story-v2";
    public static final String WHY_PROMPT_VERSION = "ai-why-v2";

    /** A story written with a ranking asked for is a different prompt, so a different stored text:
     *  turning ranking on regenerates each story once rather than leaving it without an order. */
    public static String storyPromptVersion(boolean ranked) {
        return ranked ? STORY_PROMPT_VERSION + "-ranked" : STORY_PROMPT_VERSION;
    }
    static final int STORY_MAX_TOKENS = 400;
    static final int WHY_MAX_TOKENS = 200;

    public record Result(AiInsightReason reason, AiInsightTemplate template, String model) {}

    private static final String COMMON_RULES = """
            You write short insights for a person reading their own money app, from facts the app \
            gives you as JSON. The person's own numbers are the only thing you may describe.

            Reply with JSON only, no other text, in exactly this shape:
            {"sentences":[{"type":"OBSERVATION","template":"..."}]%s}
            Each sentence's type is OBSERVATION (something that happened), COMPARISON (this month \
            against the usual month) or EXPLANATION (why a figure moved, from the facts given).

            Write every figure and every name as a placeholder, never as text:
            - {subject} for a subject's name, using a subject id from "subjects", for example {food};
            - {fact} for a figure, using a fact id from "facts", for example {food.this_month}.
            Each placeholder is replaced with the exact name or figure the app shows, so never write \
            a digit, a number word, a currency, a percent, a month or day name, or a category name \
            yourself. Never work out a figure yourself, such as the difference between two facts: use \
            only the facts given.

            An automatic check rejects the whole reply if any sentence breaks one of these rules, and \
            then the person sees none of it:
            1. No multiple or fraction: never double, doubled, twice, triple, three times or half. To \
            say how much a figure changed, use its change_pct fact: "{food} came to \
            {food.this_month}, up {food.change_pct} from {food.usual}".
            2. No "one" as a count: write "a payment" or "the largest payment", or use a count fact \
            such as {food.payments_this_month}.
            3. No day of the month such as "the 6th": use its day fact, as in "on the \
            {food.largest_1_day}".
            4. A subject's name comes first, then its own figures, before any other subject's name: \
            "{fuel} came to {fuel.this_month}", never "{fuel.this_month} went on {fuel}", and never \
            "{food} and {fuel} rose {food.change_pct} and {fuel.change_pct}" (write "{food} rose \
            {food.change_pct} and {fuel} rose {fuel.change_pct}").
            5. A fact whose period is USUAL already ends with "in a usual month" ({food.usual} reads \
            "₹4,000 in a usual month"), and a count fact already holds its noun \
            ({food.payments_this_month} reads "6 payments"): never write a noun or "in a usual month" \
            right after such a figure. Write "{food} had {food.payments_this_month}, against \
            {food.payments_usual}", which reads "Food had 6 payments, against 2 payments in a usual \
            month".
            6. Never write the words usual, usually, normally, typically or average yourself, except \
            as "from a usual month" at the very end of a sentence, after a change_pct figure with no \
            usual figure to name. Write "this month" or "so far" only before a sentence's first \
            figure. Never call a USUAL figure this month's, nor a this-month figure usual.
            7. Say a figure went up or down only as its change fact's "direction" (UP, DOWN or SAME) \
            says; SAME means the change rounds to nothing, so never give it a change_pct or a \
            direction (leave it out, or say it stayed level in a sentence of its own); with no change \
            fact for a subject, say nothing about its direction. A change fact \
            goes only with its own figure: {month.money_out_change_pct} with {month.money_out}, never \
            with {month.total}. In a sentence that says up, down, more, less, fewer, higher, lower, \
            above, below, rose or fell, every subject must have moved that way: never "{food} rose \
            {food.change_pct}, while {fuel} stayed level" -- put subjects that moved the other way, \
            or stayed level, in a sentence of their own. Use those words only for a change, never in \
            another sense ("fell on a weekend").
            8. Never write "going to" or "going on", even about the past ("with {rent.this_month} \
            going to {rent}"): the check reads it as a prediction. Write "with {rent} at \
            {rent.this_month}".

            Describe what already happened. Never give advice or an instruction, never suggest an \
            action, a budget or a plan, never mention an investment, fund, SIP, stock, insurance, \
            loan, deposit, gold or any other financial product, and never say what will happen next. \
            Use plain, everyday words. If the facts show nothing worth saying, say spending looks \
            steady.
            """;

    private static final String STORY_TASK = """
            Task: write 2 to 4 sentences about this month, choosing what matters most to this person \
            from the facts. Give every category its own figures: never one figure for two categories.
            """;

    private static final String RANKING_TASK = """
            Also rank the rows in "rows" by what this person most needs to see, as "order": a list of \
            row ids, most important first, each once. In order of weight: something they need to do \
            (a statement to upload, a question to answer); a repeating payment whose amount changed; \
            how far a category rose above its usual month, in rupees and in percent; how unusual it is \
            for them.
            """;

    private static final String WHY_TASK = """
            Task: in 1 or 2 sentences, explain why spending in this category rose this month, using \
            only the facts given (how many payments, their size, the largest ones, weekends, \
            new payees), written like these, each ending at its last figure: "The largest payment was \
            {food.largest_1}, on the {food.largest_1_day}, while a payment averaged \
            {food.avg_payment_usual}." (it reads "... while a payment averaged ₹1,500 in a usual \
            month."); "{food} had {food.payments_this_month}, against {food.payments_usual}."; \
            "{food.new_payees_total} went to {food.new_payees_count}."
            """;

    private final LlmClient llmClient;
    private final FynNameShields nameShields;
    private final AiAuditLogRepository auditLogRepository;
    private final Semaphore permits;

    public AiInsightGenerator(LlmClient llmClient, FynNameShields nameShields, AiAuditLogRepository auditLogRepository,
                              FynProperties properties) {
        this.llmClient = llmClient;
        this.nameShields = nameShields;
        this.auditLogRepository = auditLogRepository;
        this.permits = new Semaphore(Math.max(1, properties.getInsightsMaxConcurrent()));
    }

    static String systemPrompt(String kind, boolean wantOrder) {
        boolean story = com.finora.entity.AiInsightText.KIND_STORY.equals(kind);
        String orderShape = story && wantOrder ? ",\"order\":[\"row_1\",\"row_2\"]" : "";
        return COMMON_RULES.formatted(orderShape) + "\n" + (story ? STORY_TASK + (wantOrder ? RANKING_TASK : "") : WHY_TASK);
    }

    /** Not transactional: a model call never holds a database connection (the HikariCP lesson from
     *  Fyn's Phase 2 review). Each audit row is its own write. */
    public Result generate(UUID userId, String kind, FactSheet sheet, boolean wantOrder, String planCode) {
        if (!permits.tryAcquire()) {
            log.info("AI insight call refused for user {}: the most calls allowed at once are already running", userId);
            return new Result(AiInsightReason.CONCURRENCY_LIMIT, null, null);
        }
        try {
            return call(userId, kind, sheet, wantOrder, planCode);
        } finally {
            permits.release();
        }
    }

    private Result call(UUID userId, String kind, FactSheet sheet, boolean wantOrder, String planCode) {
        boolean story = com.finora.entity.AiInsightText.KIND_STORY.equals(kind);
        // With no rows there is nothing to rank: asked anyway, the model copies the example ids from
        // the prompt and the reply fails BAD_ORDER (6 of 30 evaluation histories on 2026-10-24).
        boolean askOrder = story && wantOrder && !sheet.rows().isEmpty();
        FynNameShield shield = nameShields.forUser(userId);
        LlmRequest request = LlmRequest.singleTurn(systemPrompt(kind, askOrder), shield.shield(sheet.modelJson()),
                story ? STORY_MAX_TOKENS : WHY_MAX_TOKENS);
        String promptVersion = story ? storyPromptVersion(wantOrder) : WHY_PROMPT_VERSION;
        String toolName = story ? "AI_INSIGHT_STORY" : "AI_INSIGHT_WHY";

        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            AiInsightReason reason = reasonFor(e);
            audit(userId, toolName, promptVersion, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), planCode, reason, e.getMessage());
            return new Result(reason, null, null);
        }
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("AI insight call has no known price for model {} -- add it to FynPricing.RATES", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }

        AiInsightTemplate.Parsed parsed = AiInsightTemplate.parse(completion.content());
        AiInsightReason reason = parsed.reason() != null ? parsed.reason()
                : AiInsightChecker.check(parsed.template(), sheet, askOrder);
        audit(userId, toolName, promptVersion, completion.model(), completion.tokensIn(), completion.tokensOut(), cost,
                latencyMs, planCode, reason, costError);
        return reason == AiInsightReason.OK
                ? new Result(reason, parsed.template(), completion.model())
                : new Result(reason, null, completion.model());
    }

    private static AiInsightReason reasonFor(RuntimeException e) {
        if (e instanceof ApiException api) {
            if (api.getStatus() == HttpStatus.GATEWAY_TIMEOUT) return AiInsightReason.PROVIDER_TIMEOUT;
            if (api.getStatus() == HttpStatus.TOO_MANY_REQUESTS) return AiInsightReason.RATE_LIMITED;
        }
        return AiInsightReason.PROVIDER_ERROR;
    }

    private void audit(UUID userId, String toolName, String promptVersion, String model, int tokensIn, int tokensOut,
                       BigDecimal cost, int latencyMs, String planCode, AiInsightReason reason, String error) {
        AiAuditLog row = new AiAuditLog();
        row.setUserId(userId);
        row.setModel(model != null ? model : "unknown");
        row.setPromptVersion(promptVersion);
        row.setToolName(toolName);
        row.setTokensIn(tokensIn);
        row.setTokensOut(tokensOut);
        row.setCost(cost);
        row.setLatencyMs(latencyMs);
        row.setPlanCode(planCode);
        row.setReasonCode(reason.name());
        row.setError(error);
        auditLogRepository.save(row);
        if (reason != AiInsightReason.OK) {
            log.info("AI insight {} for user {} ended {}", toolName, userId, reason);
        }
    }
}
