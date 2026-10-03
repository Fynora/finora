package com.finora.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Name masking before a narration is sent to the AI categorisation model. Every shape here was
 * measured on the real corpus; the values are synthetic.
 */
class PersonToPersonTransferDetectorMaskingTest {

    private static String mask(String narration) {
        return PersonToPersonTransferDetector.maskPersonNames(narration);
    }

    @Test
    void aShopQrRegisteredUnderItsOwnersName_isMasked() {
        assertThat(mask("UPI-PRIYA SHARMA-paytmqr12345@paytm-UTIB0XXXXXX-123456789012-UPI")) // synthetic-ok
                .isEqualTo("UPI-[name]-paytmqr12345@paytm-UTIB0XXXXXX-123456789012-UPI"); // synthetic-ok
    }

    @Test
    void aShopNamedForWhatItSells_andAKnownMerchant_stay() {
        assertThat(mask("UPI-HANUMAN TEA STALL-q123456@ybl-UPI")).isEqualTo("UPI-HANUMAN TEA STALL-q123456@ybl-UPI");
        assertThat(mask("UPI-ZOMATO-zomato@shopcobk-UPI")).isEqualTo("UPI-ZOMATO-zomato@shopcobk-UPI");
    }

    @Test
    void personPaymentsTheClassifierMistypes_areMasked() {
        // Typed UNKNOWN: a spaced payee before a bare reference.
        assertThat(mask("UPI RAVI SHANKAR KUMAR 412345678901")).isEqualTo("UPI [name] 412345678901"); // synthetic-ok
        // Typed FINANCIAL_INSTITUTION: the payer's own bank is named; the payee is stated twice.
        assertThat(mask("RAVI K UPI/RAVI K/ravik@okicici/Milk/ICICI Bank/1234/"))
                .isEqualTo("[name] UPI/[name]/ravik@okicici/Milk/ICICI Bank/1234/");
    }

    @Test
    void aSingleFirstNameInAPayeeSlot_isMasked() {
        assertThat(mask("UPIAR/1234/DR/ Deepak/YES /Payment")).isEqualTo("UPIAR/1234/DR/[name]/YES /Payment");
        assertThat(mask("UPI/ANKIT/ankit12@okaxis/UPI")).isEqualTo("UPI/[name]/ankit12@okaxis/UPI");
        assertThat(mask("UPI-1234567-SHIVA")).isEqualTo("UPI-1234567-[name]");
        assertThat(mask("UPI-Ramswaroop")).isEqualTo("UPI-[name]");
    }

    @Test
    void oneNameWithInitials_anHonorific_andAPayToRemark_areMasked() {
        assertThat(mask("NEFT CR-1234-ACME PVT LTD-PRIYA S M-ICIC1234")).isEqualTo("NEFT CR-1234-ACME PVT LTD-[name]-ICIC1234");
        assertThat(mask("UPI/1234/ ACME LLP 1/DR RAVI KUMAR/1/")).isEqualTo("UPI/1234/ ACME LLP 1/[name]/1/");
        assertThat(mask("UPI-ACME SHOP-acme@okaxis-UPI-PAY TO RAVI KUMAR")).isEqualTo("UPI-ACME SHOP-acme@okaxis-UPI-PAY TO [name]");
    }

    @Test
    void aNameOpeningAFieldBeforeAReference_isMasked() {
        assertThat(mask("NEFT*PUNB0XXXXXX*REF1*RAVI KUMAR S 1234 AT 0456 MAIN BAZAR"))
                .isEqualTo("NEFT*PUNB0XXXXXX*REF1*[name] 1234 AT 0456 MAIN BAZAR");
    }

    @Test
    void anOwnersNameAfterABusinessWord_isMasked_butACityAfterOneIsNot() {
        assertThat(mask("UPI/1234/ SHARMA ENTERPRISES PATIL RAVI ASHOK/q1@ybl/"))
                .isEqualTo("UPI/1234/ SHARMA ENTERPRISES [name]/q1@ybl/");
        assertThat(mask("ACME AIRTEL LTD GURGAON IN")).isEqualTo("ACME AIRTEL LTD GURGAON IN");
    }

    @Test
    void aGluedFullNameInAPayeeSlot_isMasked() {
        assertThat(mask("UPI-RAVISHANKARKUMARSINGH-ravi@okaxis-UPI")).isEqualTo("UPI-[name]-ravi@okaxis-UPI");
    }

    @Test
    void aWordTheStatementWrapped_isNotMistakenForAnInitialAndAName() {
        assertThat(mask("UPI/CR/1234/BLINKIT/HDFC/blinkit/R EFUND//1/"))
                .isEqualTo("UPI/CR/1234/BLINKIT/HDFC/blinkit/R EFUND//1/");
        assertThat(mask("1234- RD INSTALLMENT-JUL 2026")).isEqualTo("1234- RD INSTALLMENT-JUL 2026");
    }

    @Test
    void maskingIsIdempotent() {
        for (String narration : List.of(
                "UPI-PRIYA SHARMA-paytmqr12345@paytm-UTIB0XXXXXX-123456789012-UPI", // synthetic-ok
                "RAVI K UPI/RAVI K/ravik@okicici/Milk/ICICI Bank/1234/",
                "NEFT*PUNB0XXXXXX*REF1*RAVI KUMAR S 1234 AT 0456 MAIN BAZAR",
                "UPI/1234/ SHARMA ENTERPRISES PATIL RAVI ASHOK/q1@ybl/")) {
            String once = mask(narration);
            assertThat(mask(once)).as(narration).isEqualTo(once);
        }
    }

    @Test
    void theHoldersOwnName_isMaskedWholeCutShortOrGlued() {
        assertThat(PersonToPersonTransferDetector.maskHolderName("UPI/ZOMATO/x/THSHARMA114", "Tanvi Sharma"))
                .isEqualTo("UPI/ZOMATO/x/TH[name]114");
        assertThat(PersonToPersonTransferDetector.maskHolderName("UPI/CR/1/ TANVI SHAR/ptye/", "Tanvi Sharma"))
                .isEqualTo("UPI/CR/1/ [name] [name]/ptye/");
    }

    @Test
    void aThreeLetterHolderName_isMaskedOnlyAsAWholeWord() {
        assertThat(PersonToPersonTransferDetector.maskHolderName("UPI-RAMESH STORE-PAY RAM", "Ram Iyer"))
                .isEqualTo("UPI-RAMESH STORE-PAY [name]");
        assertThat(PersonToPersonTransferDetector.maskHolderName("UPI-ZOMATO", null)).isEqualTo("UPI-ZOMATO");
    }

    @Test
    void recognisableWords_ignorePlaceholdersAndBoilerplate() {
        assertThat(PersonToPersonTransferDetector.hasRecognisableWords("UPI-[name]-[redacted-id]-[redacted-number]-UPI")).isFalse();
        assertThat(PersonToPersonTransferDetector.hasRecognisableWords("UPI-ZOMATO-[redacted-id]")).isTrue();
        assertThat(PersonToPersonTransferDetector.hasRecognisableWords(null)).isFalse();
        // A payment app says how the money moved, not what it paid for.
        assertThat(PersonToPersonTransferDetector.hasRecognisableWords("UPI-[name]-GPAY-[redacted-id]-PAYTM")).isFalse();
    }
}
