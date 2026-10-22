package com.finora.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RupeesTest {

    @Test
    void groupsInLakhsAndCroresAsTheAppsShowThem() {
        assertThat(Rupees.format(new BigDecimal("0"))).isEqualTo("₹0");
        assertThat(Rupees.format(new BigDecimal("999"))).isEqualTo("₹999");
        assertThat(Rupees.format(new BigDecimal("1000"))).isEqualTo("₹1,000");
        assertThat(Rupees.format(new BigDecimal("120000"))).isEqualTo("₹1,20,000");
        assertThat(Rupees.format(new BigDecimal("12345678"))).isEqualTo("₹1,23,45,678");
    }

    @Test
    void showsWholeRupeesRoundedHalfUpAndNeverPaise() {
        assertThat(Rupees.format(new BigDecimal("1234.50"))).isEqualTo("₹1,235");
        assertThat(Rupees.format(new BigDecimal("1234.49"))).isEqualTo("₹1,234");
    }

    @Test
    void aNegativeAmountReadsAsItsSize() {
        assertThat(Rupees.format(new BigDecimal("-74200"))).isEqualTo("₹74,200");
    }
}
