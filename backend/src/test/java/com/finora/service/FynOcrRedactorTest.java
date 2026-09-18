package com.finora.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FynOcrRedactorTest {

    @Test
    void redactsAnAccountNumber() {
        String result = FynOcrRedactor.redact("A/c No: 123456789012"); // synthetic-ok

        assertThat(result).doesNotContain("123456789012"); // synthetic-ok
        assertThat(result).contains("[redacted-number]");
    }

    /** A masked account number glued directly to its masking prefix, no separating space --
     *  a common real bank-app display shape. Confirms LONG_NUMBER's boundary was deliberately
     *  loosened for exactly this case (see that pattern's own doc comment). */
    @Test
    void redactsAnAccountNumberGluedToAMaskingPrefix() {
        String result = FynOcrRedactor.redact("A/c No: XXXX1234567890"); // synthetic-ok

        assertThat(result).doesNotContain("1234567890"); // synthetic-ok
        assertThat(result).contains("[redacted-number]");
    }

    @Test
    void redactsASpaceGroupedCardNumber() {
        String result = FynOcrRedactor.redact("Card ending 4532 1188 2233 4455"); // synthetic-ok

        assertThat(result).doesNotContain("4532");
        assertThat(result).contains("[redacted-number]");
    }

    @Test
    void redactsAUpiTransactionReferenceNumber() {
        String result = FynOcrRedactor.redact("UPI Ref No 998877665544"); // synthetic-ok

        assertThat(result).doesNotContain("998877665544"); // synthetic-ok
        assertThat(result).contains("[redacted-number]");
    }

    @Test
    void redactsAPhoneNumber() {
        String result = FynOcrRedactor.redact("Contact: 9876543210"); // synthetic-ok

        assertThat(result).doesNotContain("9876543210"); // synthetic-ok
        assertThat(result).contains("[redacted-number]");
    }

    @Test
    void redactsAUpiVpa() {
        String result = FynOcrRedactor.redact("Paid to priya@okhdfcbank"); // synthetic-ok

        assertThat(result).doesNotContain("priya@okhdfcbank");
        assertThat(result).contains("[redacted-id]");
    }

    @Test
    void redactsAnEmailAddress() {
        String result = FynOcrRedactor.redact("Receipt sent to jane.doe@example.com");

        assertThat(result).doesNotContain("jane.doe@example.com");
        assertThat(result).contains("[redacted-id]");
    }

    @Test
    void redactsAnIfscCode() {
        String result = FynOcrRedactor.redact("IFSC: HDFC0001234"); // synthetic-ok

        assertThat(result).doesNotContain("HDFC0001234"); // synthetic-ok
        assertThat(result).contains("[redacted-ifsc]");
    }

    /**
     * Regression test for a real gap this class's own bugs-and-gaps pass caught: a real IFSC is
     * always printed uppercase, but tesseract does not reliably preserve case on every font/render
     * it OCRs. Before IFSC was made case-insensitive, an all-lowercase or mixed-case match skipped
     * that pattern entirely AND fell one digit short of LONG_NUMBER's 8-digit floor (the fixed '0'
     * plus a 6-digit tail is only 7 digits) -- confirmed to leak the whole code unredacted before
     * this fix, by running the exact patterns standalone, not assumed.
     */
    @Test
    void redactsAnIfscCodeRegardlessOfOcrCaseErrors() {
        assertThat(FynOcrRedactor.redact("IFSC: hdfc0001234")) // synthetic-ok
                .doesNotContain("hdfc0001234") // synthetic-ok
                .contains("[redacted-ifsc]");
        assertThat(FynOcrRedactor.redact("IFSC: Hdfc0001234")) // synthetic-ok
                .doesNotContain("Hdfc0001234") // synthetic-ok
                .contains("[redacted-ifsc]");
    }

    /** The whole reason this class preserves anything at all -- Fyn cannot answer "how much did I
     *  spend on this" if the amount itself gets swept up with the account/card/reference numbers
     *  it exists to redact. */
    @Test
    void leavesABareAmountUnderTheThresholdAlone() {
        String result = FynOcrRedactor.redact("Amount Paid: Rs 4500");

        assertThat(result).contains("4500");
        assertThat(result).doesNotContain("[redacted-number]");
    }

    @Test
    void leavesACommaFormattedIndianAmountAlone() {
        // 1,00,000 (one lakh) is 6 digits -- well under the 8-digit floor, and comma is
        // deliberately not a recognised separator (see LONG_NUMBER's own doc comment), so this
        // must survive even though its total character length exceeds a naive length-based check.
        String result = FynOcrRedactor.redact("Total: Rs 1,00,000"); // synthetic-ok

        assertThat(result).contains("1,00,000");
        assertThat(result).doesNotContain("[redacted-number]");
    }

    @Test
    void leavesMerchantNamesAndNarrationsAlone_documentedLimitation() {
        // Not a claim of full compliance -- see FynOcrRedactor's own class doc comment. Tier 2/3
        // free text has no reliable structural shape a deterministic regex can catch.
        String result = FynOcrRedactor.redact("SWIGGY BANGALORE dinner with Priya");

        assertThat(result).isEqualTo("SWIGGY BANGALORE dinner with Priya");
    }

    @Test
    void isNullSafe() {
        assertThat(FynOcrRedactor.redact(null)).isNull();
    }

    @Test
    void redactsMultipleShapesInOneRealisticScreenshot() {
        String ocrText = "HDFC Bank\n"
                + "A/c No: 1234567890\n" // synthetic-ok
                + "IFSC: HDFC0001234\n" // synthetic-ok
                + "UPI Ref: 445566778899\n" // synthetic-ok
                + "Paid to: swiggy@okaxis\n"
                + "Amount: Rs 499\n";

        String result = FynOcrRedactor.redact(ocrText);

        assertThat(result).doesNotContain("1234567890", "445566778899", "swiggy@okaxis", "HDFC0001234"); // synthetic-ok
        assertThat(result).contains("499");
        assertThat(result).contains("HDFC Bank");
    }
}
