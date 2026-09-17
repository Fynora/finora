package com.finora.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FynPricingTest {

    private static final String HAIKU = "claude-haiku-4-5-20251001";

    @Test
    void computesCostFromKnownHaikuRates() {
        // 1,000,000 input tokens at $1.00/MTok + 200,000 output tokens at $5.00/MTok
        // = $1.00 + $1.00 = $2.00
        BigDecimal cost = FynPricing.cost(HAIKU, 1_000_000, 200_000);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void computesCostForATypicalSmallCall() {
        // 800 input, 150 output -- roughly the plan's own cost-comparison estimate.
        BigDecimal cost = FynPricing.cost(HAIKU, 800, 150);

        // (800 * 1.00 / 1e6) + (150 * 5.00 / 1e6) = 0.0008 + 0.00075 = 0.00155
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.00155"));
    }

    @Test
    void zeroTokensCostsZero() {
        assertThat(FynPricing.cost(HAIKU, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void throwsRatherThanSilentlyPricingAnUnknownModelAtZero() {
        assertThatThrownBy(() -> FynPricing.cost("some-future-model", 100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("some-future-model");
    }

    @Test
    void theThreeArgOverloadIsExactlyTheFiveArgOneWithZeroCacheTokens() {
        BigDecimal threeArg = FynPricing.cost(HAIKU, 800, 150);
        BigDecimal fiveArgNoCache = FynPricing.cost(HAIKU, 800, 0, 0, 150);

        assertThat(threeArg).isEqualByComparingTo(fiveArgNoCache);
    }

    @Test
    void pricesACacheWriteAt1point25xTheBaseInputRate() {
        // 1,000,000 cache-creation tokens at $1.00/MTok * 1.25 = $1.25, no other tokens at all.
        BigDecimal cost = FynPricing.cost(HAIKU, 0, 1_000_000, 0, 0);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("1.25"));
    }

    @Test
    void pricesACacheReadAt0point1xTheBaseInputRate() {
        // 1,000,000 cache-read tokens at $1.00/MTok * 0.1 = $0.10, no other tokens at all.
        BigDecimal cost = FynPricing.cost(HAIKU, 0, 0, 1_000_000, 0);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.10"));
    }

    @Test
    void combinesBaseInputCacheWriteCacheReadAndOutputInOneCall() {
        // A realistic multi-round tool-call turn: round 1 wrote the cache (200 tokens), round 2
        // read it back (600 tokens) plus a small genuinely-new tail (40 tokens), and the model
        // produced 60 output tokens.
        // (40 * 1.00 + 200 * 1.00 * 1.25 + 600 * 1.00 * 0.1 + 60 * 5.00) / 1e6
        // = (40 + 250 + 60 + 300) / 1e6 = 650 / 1e6 = 0.00065
        BigDecimal cost = FynPricing.cost(HAIKU, 40, 200, 600, 60);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.00065"));
    }

    @Test
    void theFiveArgOverloadAlsoThrowsRatherThanSilentlyPricingAnUnknownModelAtZero() {
        assertThatThrownBy(() -> FynPricing.cost("some-future-model", 100, 10, 10, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("some-future-model");
    }
}
