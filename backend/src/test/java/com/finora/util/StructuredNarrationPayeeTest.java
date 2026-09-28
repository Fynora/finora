package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 5, task 1. The merchant name a row shows (Transaction.merchant) used to be the first four
 * words of the narration, so on 1,671 of 1,940 corpus rows it began with a payment-rail word, 274
 * carried a payment app's or bank's handle and 179 carried digits. A structured narration names its
 * payee in one of its fields; the name is now taken from that field. Every value below is invented;
 * the layouts are the corpus's own.
 */
class StructuredNarrationPayeeTest {

    private static String label(String narration) {
        return CategoryRules.extractMerchantLabel(narration);
    }

    @Test
    void hyphenSeparatedUpiTakesThePayeeFieldNotTheHandleOrBankCode() {
        // UPI-NAME-HANDLE-IFSC-REF-NOTE
        assertThat(label("UPI-SAMPLE STORES PVT-samplestores@okaxis-UTIB0XXXXXX-100000000001-PAYMENT"))
                .isEqualTo("sample stores pvt");
    }

    @Test
    void slashSeparatedUpiWithDirectionAndReferenceFirst() {
        // UPI/DR/REF/NAME/HANDLE/NOTE
        assertThat(label("UPI/DR/100000000001/SAMPLE PAYEE/samplepayee@ybl/UPI")).isEqualTo("sample payee");
        // UPI/CR/CODE/ NAME/ handle-domain/HANDLE/NA/ CODE
        assertThat(label("UPI/CR/C100000000001/ SAMPLE PERSON/ ptye/sample@ptyes/NA/ PTM1A2B3C4D5E6"))
                .isEqualTo("sample person");
    }

    @Test
    void slashSeparatedUpiWithTheNameBeforeTheReference() {
        // UPI/NAME/REF/NOTE/BANK/HANDLE
        assertThat(label("UPI/SAMPLE PERSON/100000000001/SENT USING/SBIN/sample@oksbi"))
                .isEqualTo("sample person");
    }

    @Test
    void aBankCodeRailPrefixLikeUnionsIsARail() {
        assertThat(label("UPIAB/100000000001/DR/SAMPLE SHOP/YESB/sampleshop@ybl")).isEqualTo("sample shop");
    }

    @Test
    void anImpsNarrationTakesThePayee() {
        assertThat(label("MOB-IMPS-CR/SAMPLE PERSON/KKBK /1000000001/IMPS/1000000002")).isEqualTo("sample person");
        assertThat(label("NEFT CR-SBIN0XXXXXX-SAMPLE EMPLOYER LTD-SAMPLE PERSON-SBINN00000000001"))
                .isEqualTo("sample employer ltd");
    }

    @Test
    void whenNoFieldNamesAPayeeTheHandleNameIsUsed() {
        // UPI/REF/TIME/UPI/HANDLE/UPI -- the handle's own name part is the only name printed.
        assertThat(label("UPI/100000000001/02:44:32/UPI/samplestore@okaxis/UPI")).isEqualTo("samplestore");
    }

    @Test
    void aHandleThatIsOnlyDigitsOrACodeIsNeverTheName() {
        assertThat(label("UPI/100000000001/UPI/9999999999@ybl/UPI")).isNull();
        assertThat(label("UPI/100000000001/UPI/q1a2b3c4d5@ybl/UPI")).isNull();
    }

    @Test
    void aHandleWrappedAcrossALineOrEndingInDigitsStillNamesThePayee() {
        assertThat(label("UPI/100000000001/14:21:12/UPI/samplepoun d@okicici/UPI")).isEqualTo("samplepound");
        assertThat(label("UPI/100000000001/03:50:05/UPI/samplejudoka 407@okaxis/UPI")).isEqualTo("samplejudoka");
    }

    @Test
    void aPaymentAppsOwnHandleNamesNobody() {
        assertThat(label("UPI/100000000001/02:44:32/UPI/paytm.s25j48@pty/UPI")).isNull();
        assertThat(label("UPI/100000000001/18:39:34/UPI/paytmqr6nu5ur@ptys/UPI")).isNull();
        assertThat(label("UPI/100000000001/19:42:43/UPI/bharatpe.9006@yesbank/UPI")).isNull();
    }

    @Test
    void aNameFieldThatAlsoCarriesACodeKeepsItsWords() {
        assertThat(label("UPI/100000000001/CR/SAMPLE PERSON S1234567 CHO/SBI/UPI")).isEqualTo("sample person cho");
    }

    @Test
    void aPaymentFromAPaymentAppNamesThePersonNotTheApp() {
        assertThat(label("UPI/RRN 100000000001/Payment from PhonePe_SAMPLEPERSON")).isEqualTo("sampleperson");
    }

    @Test
    void aPaymentAppOnItsOwnIsKeptAsTheName() {
        // A refund from the app itself: the app is the counterparty.
        assertThat(label("UPI-PHONEPE-PHONEPEMERCHANT@SAMPLEBK-YESB0XXXXXX-100000000001-REVERSAL")).isEqualTo("phonepe");
    }

    @Test
    void aNarrationThatPrintsTheNameBeforeUpiIsStructured() {
        // NAME UPI/NAME/HANDLE/NOTE/BANK/REF/CODE
        assertThat(label("Sample Shop UPI/Sample Shop/sampleshop@ybl/Milk/YES BANK L/100000000001/IBL1a2b3c4d5e"))
                .isEqualTo("sample shop");
    }

    @Test
    void aBrandWithAFewDigitsKeepsThem() {
        assertThat(label("UPI/CR/100000000001/SAMPLE97 CO/UTIB/sample.pay@axl")).isEqualTo("sample97 co");
    }

    @Test
    void aDirectionWordInsideTheNameIsPartOfTheName() {
        assertThat(label("UPI-SAMPLE CITY METRO DR IO-samplemetro@hdfcbank-HDFC0XXXXXX-100000000001-UPI"))
                .isEqualTo("sample city metro dr");
    }

    @Test
    void aShortAllCapitalsPayeeIsAName() {
        // "JIO", "LIC", "BSNL" are payees, not bank codes; only a real bank's code is skipped.
        assertThat(label("UPI/JIO/100000000001/Pay")).isEqualTo("jio");
        assertThat(label("UPI/DR/100000000001/LIC/UTIB/sample@okaxis")).isEqualTo("lic");
    }

    @Test
    void aKnownBankCodeBeforeTheHandleIsNotTheName() {
        assertThat(label("UPI/DR/100000000001/ /YESB/samplestore@ybl")).isEqualTo("samplestore");
    }

    @Test
    void nothingAfterTheHandleIsTheName() {
        // Every corpus layout prints the payee before the handle; what follows is a note, a bank or
        // an IFSC. A wrapped IFSC ("IOB A0001...") or a cut-off note ("UP") must never become it.
        assertThat(label("UPI/100000000001/SAMPLEPAYEE@OKAXIS/SAMPLEPAYEE@OKAXIS/IOB A0001 100000000001/FIRST TRANSFER"))
                .isEqualTo("samplepayee");
        assertThat(label("UPI/100000000001/SAMPLE035PAYEE@OKAXIS/SAMPLE035PAYEE@OKAXIS/U TIB 100000000001/UPI"))
                .isEqualTo("sample");
        assertThat(label("UPI/100000000001/18:10:02/UPI/1000000001-3@ybl/UP")).isNull();
    }

    @Test
    void aNameDirectlyAfterTheHandleIsTheName() {
        // A Standard Chartered layout prints REF/REF/HANDLE/NAME/IFSC/...
        assertThat(label("UPI/100000000001/100000000002/SAMPLEPAYEE@OKAXIS/ SAMPLE FULL PAYEE/IOBA0XXXXXX/UPI"))
                .isEqualTo("sample full payee");
    }

    @Test
    void aHyphenInsideAHandleDoesNotSplitIt() {
        assertThat(label("UPI/100000000001/SAMPLEPAYEE30-1@OKAXIS/SAMPLEPAYEE30 100000000001/UPI"))
                .isEqualTo("samplepayee");
    }

    @Test
    void aHyphenLayoutWithASlashInItsNoteStaysAHyphenLayout() {
        assertThat(label("UPI-SAMPLE TRADERS-sampletraders@ptys-YESB0XXXXXX-100000000001-UPI Value Dt 22/06/2026 Ref 100000000001"))
                .isEqualTo("sample traders");
    }

    @Test
    void aRailWordJoinedToTheNameByAnUnderscoreIsDropped() {
        assertThat(label("UPI/RRN 100000000001/UPI_SAMPLE PAYEE NAME")).isEqualTo("sample payee name");
    }

    @Test
    void anUnstructuredNarrationKeepsItsLeadingWordsButNeverALeadingRailWord() {
        assertThat(label("SWIGGY*ORDR9182 BANGALORE")).isEqualTo("swiggy bangalore");
        assertThat(label("UPI SAMPLE PERSON 100000000001")).isEqualTo("sample person");
    }

    @Test
    void aNarrationWithNoPayeeAtAllHasNoName() {
        assertThat(label("UPI-REF9182736")).isNull();
        assertThat(label("ACH TRANSFER")).isNull();
    }

    // Measured on a real Kotak statement: an IMPS debit prints its payee glued to the rail word and
    // the reference, "SentIMPS<12 digits><name>/<IFSC>/<note>", and the name came out as a word of
    // the payee plus the note ("chat imps").
    @Test
    void aKotakImpsDebitNamesThePayeeGluedAfterItsReference() {
        assertThat(label("SentIMPS100000000001Asha Verma/HDFC0XXXXXX/IMPS")).isEqualTo("asha verma");
        assertThat(label("SentIMPS100000000001Asha Verma/HDFC0XXXXXX/RENT")).isEqualTo("asha verma");
    }

    // Measured on a real CBI statement: "UPI/RRN <ref>/Pay for Intent" names no payee.
    @Test
    void aUpiIntentBoilerplateFieldIsNotAName() {
        assertThat(label("UPI/RRN 100000000001/Pay for Intent")).isNull();
    }
}
