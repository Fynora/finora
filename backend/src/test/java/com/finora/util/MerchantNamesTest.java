package com.finora.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name a merchant is stored under and the name a customer is shown for it. Every narration and
 * name below is invented; the layouts are ones the statement corpus prints.
 */
class MerchantNamesTest {

    // ---- the name a new merchant is stored under ----

    @Test
    void aStructuredNarrationIsNamedAfterItsPayeeField() {
        assertThat(MerchantNames.forNarration("UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc"))
                .isEqualTo("Samplepa");
        assertThat(MerchantNames.forNarration("UPI-SAMPLE KIRANA-samplekirana@okaxis-UTIB0XXXXXX-100000000004-groceries"))
                .isEqualTo("Sample Kirana");
    }

    @Test
    void anUnstructuredNarrationLosesItsLeadingRailWords() {
        assertThat(MerchantNames.forNarration("ACH D- SAMPLE FINANCE LTD-0000000001")).isEqualTo("Sample Finance");
        assertThat(MerchantNames.forNarration("UPI SAMPLE STORE 100000000001")).isEqualTo("Sample Store");
        assertThat(MerchantNames.forNarration("NEFT TRANSFER SAMPLE TRADERS")).isEqualTo("Sample Traders");
    }

    @Test
    void aNarrationThatNamesNoPayeeIsNamedAfterItsRail() {
        assertThat(MerchantNames.forNarration("UPI/RRN 100000000001/UPI")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forNarration("UPI 100000000001")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forNarration("NACH CHQ 004471")).isEqualTo("NACH payment");
    }

    /**
     * A QR payment can print only a generated handle ("q&lt;digits&gt;@ybl"). Once its digits are
     * gone the words left are the rail and the handle's suffix, which say how the money moved and
     * not who received it: measured on the corpus, 10 rows were named "Ybl Upi", "Ybl", "Axl Upi" or
     * "Axl" this way.
     */
    @Test
    void aNarrationLeftWithOnlyRailAndHandleSuffixWordsNamesNobody() {
        assertThat(MerchantNames.forNarration("UPI/100000000001/12:30:11/UPI/q12345678@ybl/UPI")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forNarration("UPI/100000000001/12:30:11/UPI/q12345678@axl/")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Upi Upi Ybl Upi")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Upi Upi Axl")).isEqualTo("UPI payment");
        // A payment app's own name can be the payee (a wallet top-up), so it is kept.
        assertThat(MerchantNames.forNarration("UPI PAYTM 100000000001")).isEqualTo("Paytm");
        assertThat(MerchantNames.forDisplay("Upi Dr Paytm Ybl")).isEqualTo("Paytm Ybl");
    }

    /**
     * A direction word left on its own ("dr") names nobody either, but the grouping key reads it,
     * so the stored name cannot become the rail's payment without losing that key. The stored name
     * is left whole -- never cut down to "Dr" -- and {@link MerchantNames#forDisplay} shows it as
     * the rail's payment.
     */
    @Test
    void aNarrationLeftWithOnlyADirectionWordKeepsItsStoredNameWhole() {
        assertThat(MerchantNames.forNarration("UPI/DR/100000000001")).isEqualTo("Upi Dr");
        assertThat(MerchantNames.forDisplay(MerchantNames.forNarration("UPI/DR/100000000001"))).isEqualTo("UPI payment");
    }

    @Test
    void aNarrationThatDoesNotStartWithARailKeepsEveryWord() {
        assertThat(MerchantNames.forNarration("SAMPLE CAFE 4471")).isEqualTo("Sample Cafe");
        assertThat(MerchantNames.forNarration("DR SAMPLE CLINIC")).isEqualTo("Dr Sample Clinic");
        // "payment" is a rail word, but not one a narration opens with: the name is left alone.
        assertThat(MerchantNames.forNarration("PAYMENT SAMPLE GATEWAY")).isEqualTo("Payment Sample Gateway");
    }

    /**
     * "ib" and "mob" open a narration too, but MerchantNormalizationEngine's grouping key reads them
     * as part of the payee ("mob" as a word, "ib" when nothing longer follows). Dropping them from
     * the stored name would move the key away from the one later rows are matched by, so the stored
     * name keeps them and only {@link MerchantNames#forDisplay} hides them.
     */
    @Test
    void aRailWordTheGroupingKeyCountsStaysInTheStoredName() {
        assertThat(MerchantNames.forNarration("IB SAMPLE BILLPAY 100000000001")).isEqualTo("Ib Sample Billpay");
        assertThat(MerchantNames.forNarration("MOB SAMPLE FRIEND 100000000001")).isEqualTo("Mob Sample Friend");
    }

    /**
     * "MMT" opens one bank's IMPS narrations, and is also how a card statement abbreviates a travel
     * site. It is read as a rail only when another rail or direction word follows it; on its own it
     * may be the merchant, and is kept.
     */
    @Test
    void mmtIsARailOnlyWhenAnotherRailWordFollows() {
        assertThat(MerchantNames.forNarration("MMT HOTEL BOOKING 100000000001")).isEqualTo("Mmt Hotel Booking");
        assertThat(MerchantNames.forNarration("MMT IMPS 100000000001 SAMPLE PERSON")).isEqualTo("Sample Person");
        assertThat(MerchantNames.forDisplay("Mmt Hotel Booking")).isEqualTo("Mmt Hotel Booking");
        assertThat(MerchantNames.forDisplay("Mmt")).isEqualTo("Mmt");
        assertThat(MerchantNames.forDisplay("Mmt Imps")).isEqualTo("MMT payment");
        assertThat(MerchantNames.forDisplay("Mmt Hotel Booking", List.of("MMT HOTEL BOOKING 100000000001")))
                .isEqualTo("Mmt Hotel Booking");
    }

    @Test
    void anEmptyNarrationIsStillNamed() {
        assertThat(MerchantNames.forNarration(null)).isEqualTo("Unknown");
        assertThat(MerchantNames.forNarration("   ")).isEqualTo("Unknown");
    }

    // ---- the name a customer is shown, from the stored name alone ----

    @Test
    void aStoredNameThatOpensWithARailIsShownWithoutIt() {
        assertThat(MerchantNames.forDisplay("Upi Dr Samplepa Hdfc")).isEqualTo("Samplepa Hdfc");
        assertThat(MerchantNames.forDisplay("Neft Sample Traders Netbank")).isEqualTo("Sample Traders Netbank");
        assertThat(MerchantNames.forDisplay("Mmt Imps Monthly Samplepa")).isEqualTo("Monthly Samplepa");
        assertThat(MerchantNames.forDisplay("Ib Sample School Fees")).isEqualTo("Sample School Fees");
        assertThat(MerchantNames.forDisplay("Mob Tpft Sample Friend")).isEqualTo("Tpft Sample Friend");
    }

    @Test
    void aStoredNameOfNothingButRailWordsIsShownAsThatRailsPayment() {
        assertThat(MerchantNames.forDisplay("Upi Upi")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Upi")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Upi Dr")).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Nach Chq")).isEqualTo("NACH payment");
        assertThat(MerchantNames.forDisplay("Mob")).isEqualTo("Mobile banking payment");
        assertThat(MerchantNames.forDisplay("Ib")).isEqualTo("Internet banking payment");
    }

    @Test
    void theShownNameOfARailsPaymentIsItself() {
        for (String rail : List.of("Upi", "Imps", "Neft", "Rtgs", "Mmt", "Ach", "Nach", "Mob", "Ib")) {
            String shown = MerchantNames.forDisplay(rail);
            assertThat(MerchantNames.forDisplay(shown)).as(rail).isEqualTo(shown);
        }
    }

    @Test
    void aStoredNameThatDoesNotOpenWithARailIsShownAsStored() {
        for (String name : List.of("Sample Store", "Dr Sample Clinic", "Amazon", "Payment Sample Gateway",
                "Pos Sample Mart", "From Sample Sender", "HP", "sample UPI store")) {
            assertThat(MerchantNames.forDisplay(name)).isEqualTo(name);
        }
        assertThat(MerchantNames.forDisplay(null)).isNull();
        assertThat(MerchantNames.forDisplay("")).isEmpty();
        assertThat(MerchantNames.forDisplay("  ")).isEqualTo("  ");
    }

    // ---- the name a customer is shown, when the merchant's own narrations are at hand ----

    @Test
    void aRailFirstNameIsShownAsThePayeeItsNarrationsName() {
        assertThat(MerchantNames.forDisplay("Upi Testpaye Testpayee7 Oksbi",
                List.of("UPI-TESTPAYE-testpayee7@oksbi-SBIN0XXXXXX-100000000005-UPI"))).isEqualTo("Testpaye");
        assertThat(MerchantNames.forDisplay("Upi Dr Samplepa Hdfc",
                List.of("UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc",
                        "UPI/DR/200000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc"))).isEqualTo("Samplepa");
    }

    @Test
    void thePayeeMostNarrationsNameWins() {
        assertThat(MerchantNames.forDisplay("Upi Dr Sample Store",
                List.of("UPI/DR/100000000001/SAMPLE STORE ONE/YESB/samplestore1@ybl",
                        "UPI/DR/100000000002/SAMPLE STORE TWO/YESB/samplestore2@ybl",
                        "UPI/DR/100000000003/SAMPLE STORE TWO/YESB/samplestore2@ybl"))).isEqualTo("Sample Store Two");
    }

    @Test
    void anEvenSplitIsSettledTheSameWayWhateverTheOrder() {
        String one = "UPI/DR/100000000001/SAMPLE STORE ONE/YESB/samplestore1@ybl";
        String two = "UPI/DR/100000000002/SAMPLE STORE TWO/YESB/samplestore2@ybl";
        assertThat(MerchantNames.forDisplay("Upi Dr Sample Store", List.of(one, two)))
                .isEqualTo(MerchantNames.forDisplay("Upi Dr Sample Store", List.of(two, one)))
                .isEqualTo("Sample Store One");
    }

    @Test
    void narrationsThatNameNoPayeeLeaveTheStoredNameToSpeak() {
        assertThat(MerchantNames.forDisplay("Upi Upi", List.of("UPI/RRN 100000000001/UPI"))).isEqualTo("UPI payment");
        assertThat(MerchantNames.forDisplay("Upi Dr Samplepa Hdfc", List.of())).isEqualTo("Samplepa Hdfc");
        assertThat(MerchantNames.forDisplay("Upi Dr Samplepa Hdfc", null)).isEqualTo("Samplepa Hdfc");
        assertThat(MerchantNames.forDisplay("Upi Dr Samplepa Hdfc", Arrays.asList(null, " "))).isEqualTo("Samplepa Hdfc");
        // A narration whose own name still opens with a rail is no better than the stored one.
        assertThat(MerchantNames.forDisplay("Ib Sample Billpay", List.of("IB SAMPLE BILLPAY 100000000001")))
                .isEqualTo("Sample Billpay");
    }

    @Test
    void aNameThatDoesNotOpenWithARailIgnoresTheNarrations() {
        assertThat(MerchantNames.forDisplay("Sample Store",
                List.of("UPI/DR/100000000001/SOMEONE ELSE/HDFC/someoneelse@okhdfc"))).isEqualTo("Sample Store");
        assertThat(MerchantNames.forDisplay(null, List.of("UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc")))
                .isNull();
    }
}
