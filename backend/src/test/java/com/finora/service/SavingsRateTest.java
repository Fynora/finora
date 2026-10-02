package com.finora.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SavingsRateTest {

    private static SavingsRate.Reading of(String income, String expense, String unresolved) {
        return SavingsRate.of(new BigDecimal(income), new BigDecimal(expense), new BigDecimal(unresolved));
    }

    @Test void ordinaryMonth_isIncomeKeptAsAPercentage() {
        SavingsRate.Reading r = of("50000.00", "30000.00", "0.00");
        assertThat(r.pct()).isEqualByComparingTo("40.00");
        assertThat(r.gateReason()).isNull();
    }

    @Test void overspending_isANegativeRateWhenIncomeIsReal() {
        assertThat(of("50000.00", "60000.00", "1000.00").pct()).isEqualByComparingTo("-20.00");
    }

    @Test void noIncome_isWithheld() {
        SavingsRate.Reading r = of("0.00", "500.00", "0.00");
        assertThat(r.pct()).isNull();
        assertThat(r.gateReason()).isEqualTo(SavingsRate.NO_INCOME);
    }

    @Test void unclassifiedMoneyDwarfingIncome_isWithheld() {
        SavingsRate.Reading r = of("200.00", "400000.00", "450000.00");
        assertThat(r.pct()).isNull();
        assertThat(r.gateReason()).isEqualTo(SavingsRate.UNRESOLVED_EXCEEDS_INCOME);
    }

    @Test void unresolvedEqualToIncome_stillShowsTheRate() {
        // The boundary: only MORE unclassified money than income withholds the figure.
        assertThat(of("1000.00", "500.00", "1000.00").pct()).isEqualByComparingTo("50.00");
        assertThat(of("1000.00", "500.00", "1000.01").pct()).isNull();
    }

    @Test void nullInputs_areTreatedAsAbsent() {
        assertThat(SavingsRate.of(null, BigDecimal.ONE, null).gateReason()).isEqualTo(SavingsRate.NO_INCOME);
        assertThat(SavingsRate.of(BigDecimal.TEN, null, null).pct()).isEqualByComparingTo("100.00");
    }
}
