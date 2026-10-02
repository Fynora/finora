package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The merchant category a card statement prints beside each purchase (Axis "MERCHANT CATEGORY",
 * IndusInd "Merchant Category"), read as one of our own categories. Every label below is one
 * measured on the real statement corpus.
 */
class PrintedMerchantCategoryTest {

    private static String of(String label) {
        return PrintedMerchantCategory.toCategory(label).orElse(null);
    }

    @Test
    void eatingOut_isDining() {
        assertThat(of("RESTAURANTS")).isEqualTo("Dining");
    }

    @Test
    void storesAndGoods_areShopping() {
        assertThat(of("DEPT STORES")).isEqualTo("Shopping");
        assertThat(of("DEPARTMENTAL STORES")).isEqualTo("Shopping");
        assertThat(of("RETAIL STORES")).isEqualTo("Shopping");
        assertThat(of("MISC STORE")).isEqualTo("Shopping");
        assertThat(of("MERCHANDISE")).isEqualTo("Shopping");
        assertThat(of("ELECTRONICS")).isEqualTo("Shopping");
        assertThat(of("COMPUTERS")).isEqualTo("Shopping");
    }

    @Test
    void foodBoughtToCook_isGroceries() {
        assertThat(of("GROCERY & SUPERMARKETS")).isEqualTo("Groceries");
        assertThat(of("FOOD PRODUCTS")).isEqualTo("Groceries");
    }

    @Test
    void fuelAndVehicles_areTransport() {
        assertThat(of("FUEL")).isEqualTo("Transport");
        assertThat(of("PETROL")).isEqualTo("Transport");
        assertThat(of("AUTO SERVICES")).isEqualTo("Transport");
        assertThat(of("MOTO")).isEqualTo("Transport");
    }

    @Test
    void theRestOfTheMeasuredLabels_mapToTheirOwnCategory() {
        assertThat(of("MEDICAL")).isEqualTo("Health");
        assertThat(of("UTILITIES")).isEqualTo("Utilities");
        assertThat(of("TELECOMMUNICATIONS")).isEqualTo("Utilities");
        assertThat(of("AIRLINES")).isEqualTo("Travel");
        assertThat(of("HOME FURNISHING")).isEqualTo("Home & Furnishing");
        assertThat(of("GST")).isEqualTo("Taxes");
    }

    /** Labels that say nothing about what was bought stay unmapped -- the row stays "Other". */
    @Test
    void labelsThatNameNoKindOfSpending_areNotMapped() {
        assertThat(of("SERVICES")).isNull();
        assertThat(of("MISCELLANEOUS")).isNull();
    }

    /** A credit row can carry its own narration in this column on a real card statement. */
    @Test
    void aNarrationInTheCategoryColumn_isNotMapped() {
        assertThat(of("BBPS PAYMENT RECEIVED - DP000000000000SAMPLE")).isNull();
    }

    @Test
    void caseAndSpacingDoNotMatter() {
        assertThat(of("  Restaurants ")).isEqualTo("Dining");
        assertThat(of("dept  stores")).isEqualTo("Shopping");
    }

    @Test
    void blankOrMissing_isNotMapped() {
        assertThat(of(null)).isNull();
        assertThat(of("  ")).isNull();
    }
}
