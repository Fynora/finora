package com.finora.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Cost per Fyn call, in USD -- feeds {@code AiAuditLog.cost} (plan §4.3), which
 * {@link FynCostGovernanceService} sums for the daily/monthly caps. A lookup by model id, not a
 * provider abstraction: only one model is in use ({@code claude-haiku-4-5-20251001}), and this
 * stays a one-line addition if a second is ever wired up -- see {@link
 * com.finora.integrations.anthropic.LlmClient}'s own doc for why a fuller abstraction was rejected
 * until then.
 */
public final class FynPricing {

    private record Rate(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}

    // From the implementation plan's own cost comparison (2026-09-13): Haiku 4.5 is $1/$5 per
    // million input/output tokens, straight off claude.com/pricing.
    private static final Map<String, Rate> RATES = Map.of(
            "claude-haiku-4-5-20251001", new Rate(new BigDecimal("1.00"), new BigDecimal("5.00")));

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    // Anthropic's own prompt-caching multipliers on the base input rate (verified against
    // platform.claude.com/docs/en/docs/build-with-claude/prompt-caching, 2026-09-17): a 5-minute
    // ephemeral cache write costs 1.25x a plain input token (more, not less -- writing the cache
    // entry is extra work on top of the call that would have happened anyway), a cache read costs
    // 0.1x (the actual saving). AnthropicClient only ever writes 5-minute entries (see its
    // CacheControl doc comment), so the 1-hour 2.0x tier has no rate here -- add one if a caller
    // ever requests it.
    private static final BigDecimal CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal CACHE_READ_MULTIPLIER = new BigDecimal("0.1");

    private FynPricing() {}

    /** @throws IllegalArgumentException for a model with no known rate -- silently returning zero
     *          would make an unpriced model look free to {@code FynCostGovernanceService}, the
     *          exact failure mode cost governance exists to prevent. */
    public static BigDecimal cost(String model, int tokensIn, int tokensOut) {
        return cost(model, tokensIn, 0, 0, tokensOut);
    }

    /** Same as {@link #cost(String, int, int)}, but priced with the cache write/read token counts
     *  Anthropic's usage object reports separately from plain {@code tokensIn} -- see {@link
     *  com.finora.integrations.anthropic.LlmClient.LlmCompletion}'s own doc comment for why those
     *  are never folded into one number before reaching here. Passing zero for both is exactly
     *  {@link #cost(String, int, int)}'s behavior, so that overload is a thin, still-useful
     *  convenience for every caller that hasn't wired up the cache-aware breakdown (or never will,
     *  because its own request is a single short call unlikely to ever exercise Anthropic's
     *  minimum cacheable length in the first place).
     *
     *  @throws IllegalArgumentException for a model with no known rate -- same reasoning as {@link
     *          #cost(String, int, int)}.
     */
    public static BigDecimal cost(String model, int tokensIn, int cacheCreationInputTokens,
                                   int cacheReadInputTokens, int tokensOut) {
        Rate rate = RATES.get(model);
        if (rate == null) {
            throw new IllegalArgumentException(
                    "No known price for model \"" + model + "\" -- add it to FynPricing.RATES "
                            + "before using it, so cost governance isn't silently blind to it.");
        }
        BigDecimal inputCost = rate.inputPerMillion()
                .multiply(BigDecimal.valueOf(tokensIn))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal cacheWriteCost = rate.inputPerMillion()
                .multiply(CACHE_WRITE_MULTIPLIER)
                .multiply(BigDecimal.valueOf(cacheCreationInputTokens))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal cacheReadCost = rate.inputPerMillion()
                .multiply(CACHE_READ_MULTIPLIER)
                .multiply(BigDecimal.valueOf(cacheReadInputTokens))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = rate.outputPerMillion()
                .multiply(BigDecimal.valueOf(tokensOut))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return inputCost.add(cacheWriteCost).add(cacheReadCost).add(outputCost);
    }
}
