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

    private FynPricing() {}

    /** @throws IllegalArgumentException for a model with no known rate -- silently returning zero
     *          would make an unpriced model look free to {@code FynCostGovernanceService}, the
     *          exact failure mode cost governance exists to prevent. */
    public static BigDecimal cost(String model, int tokensIn, int tokensOut) {
        Rate rate = RATES.get(model);
        if (rate == null) {
            throw new IllegalArgumentException(
                    "No known price for model \"" + model + "\" -- add it to FynPricing.RATES "
                            + "before using it, so cost governance isn't silently blind to it.");
        }
        BigDecimal inputCost = rate.inputPerMillion()
                .multiply(BigDecimal.valueOf(tokensIn))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = rate.outputPerMillion()
                .multiply(BigDecimal.valueOf(tokensOut))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }
}
