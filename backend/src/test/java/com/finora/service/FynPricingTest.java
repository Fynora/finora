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
}
