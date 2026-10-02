package com.finora.imports.ownership;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HolderNameSanityTest {

    @Test
    void theShapesRealStatementsPrintAreNames() {
        // Each shape the real corpus extracts, with invented names: titles with and without a full
        // stop, initials, title case, doubled spaces.
        for (String name : new String[] {
                "RAVI KUMAR", "MR RAVI KUMAR", "MR. RAVI KUMAR", "Mr. RAVI  KUMAR", "MRS ASHA RANI GUPTA",   // synthetic-ok
                "Ravi Kumar", "RAVI   KUMAR", "T S RAO", "KUMAR R", "D'SOUZA ANITA", "RAVI KUMAR-SINGH",  // synthetic-ok
                "RAVI KUMAR & ASHA KUMAR"}) {                                                         // synthetic-ok
            assertThat(HolderNameSanity.isPlausible(name)).as(name).isTrue();
        }
    }

    @Test
    void whatTesterStatementsStoredAsAHolderIsNot() {
        assertThat(HolderNameSanity.isPlausible(".")).isFalse();
        assertThat(HolderNameSanity.isPlausible("Previous Balance")).isFalse();
        assertThat(HolderNameSanity.isPlausible("/ Place of Supply and GST code details visit example.bank/gst")).isFalse();
    }

    @Test
    void referencesAddressesAndStatementWordsAreNotNames() {
        assertThat(HolderNameSanity.isPlausible("RAVI KUMAR 400001")).isFalse();       // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("ravi@example")).isFalse();
        assertThat(HolderNameSanity.isPlausible("Total Payment Due")).isFalse();
        assertThat(HolderNameSanity.isPlausible("Statement Period")).isFalse();
        assertThat(HolderNameSanity.isPlausible("A")).isFalse();
        assertThat(HolderNameSanity.isPlausible("   ")).isFalse();
        assertThat(HolderNameSanity.isPlausible(null)).isFalse();
    }

    @Test
    void orNullTrimsANameAndDropsTheRest() {
        assertThat(HolderNameSanity.orNull("  RAVI KUMAR ")).isEqualTo("RAVI KUMAR"); // synthetic-ok
        assertThat(HolderNameSanity.orNull(".")).isNull();
    }
}
