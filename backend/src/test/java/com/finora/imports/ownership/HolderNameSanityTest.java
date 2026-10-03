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
    void theHolderShapesTheExtractorsOwnPatternsAcceptAreNames() {
        // PdfMetadataExtractor's leading-line, panel, greeting and "Account Name" patterns all accept
        // the "M/S" title a business account is printed with; a "/" there is not a reference.
        assertThat(HolderNameSanity.isPlausible("M/S SAMPLE TRADERS")).isTrue();     // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("m/s. Sample Traders")).isTrue();    // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("RAVI KUMAR S/O RAM KUMAR")).isTrue(); // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("RAVI KUMAR (HUF)")).isTrue();       // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("ANITA D’SOUZA")).isTrue();          // synthetic-ok
        // A labelled holder can be a firm, and firms carry words a statement also uses.
        assertThat(HolderNameSanity.isPlausible("M/S SAMPLE TRAVEL SERVICES")).isTrue();   // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("SAMPLE CREDIT SOCIETY")).isTrue();        // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("SAMPLE CASH & CARRY")).isTrue();          // synthetic-ok
        assertThat(HolderNameSanity.isPlausible("SAMPLE CARD PRINTERS")).isTrue();         // synthetic-ok
        // The title alone, or a slash anywhere else, is still not a name.
        assertThat(HolderNameSanity.isPlausible("M/S")).isFalse();
        assertThat(HolderNameSanity.isPlausible("name/ Place of Supply")).isFalse();
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
