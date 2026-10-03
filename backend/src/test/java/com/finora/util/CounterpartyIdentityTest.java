package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CounterpartyIdentityTest {

    @Test
    void theVpaLocalPartIsTheKey_andTheHandleIsDroppedOnPurpose() {
        // One person collecting on two PSPs is ONE counterparty. Keeping the handle would split
        // them, which is the single most common way a naive key inflates the counterparty count and
        // makes the review queue look longer than it is.
        String onYbl = CounterpartyIdentity.keyOf("UPI-SUNIL VERMA-sampleuser@ybl-REF31");
        String onPaytm = CounterpartyIdentity.keyOf("UPI/SUNIL VERMA/sampleuser@paytm/REF32");
        assertThat(onYbl).isEqualTo(onPaytm);
        assertThat(onYbl).isEqualTo("vpa:sampleuser");
        assertThat(CounterpartyIdentity.isStrong(onYbl)).isTrue();
    }

    @Test
    void theVpaKeySurvivesTheNameBeingTruncatedDifferently() {
        // This is why the VPA beats the name. Banks truncate the payee differently per statement
        // layout; MerchantNormalizationEngine's first-significant-token grouping is at the mercy of
        // that, and the same payee becomes two merchants.
        assertThat(CounterpartyIdentity.keyOf("UPI-SUNIL VERMA-sampleuser@ybl-REF33"))
                .isEqualTo(CounterpartyIdentity.keyOf("UPI-SUNIL VER-sampleuser@ybl-REF34"));
    }

    @Test
    void aNarrationWithNoVpaFallsBackToAWeakNameKey() {
        String key = CounterpartyIdentity.keyOf("NEFT/ACME TECHNOLOGIES/REF35");
        assertThat(key).startsWith("name:");
        // Weak on purpose: a caller must be able to tell a derived guess from an identity, because
        // the two justify very different UI (auto-group vs ask the user to confirm).
        assertThat(CounterpartyIdentity.isStrong(key)).isFalse();
    }

    @Test
    void referenceHeavySegmentsAreSkippedRatherThanKeyedOn() {
        // Keying on an RRN would make every transaction its own counterparty -- the exact opposite
        // of the point. The reference segment must lose to the payee segment.
        String key = CounterpartyIdentity.keyOf("NEFT/ACME TECHNOLOGIES/CITIN12345678/REF36");
        assertThat(key).isEqualTo("name:acme technologies");
    }

    @Test
    void railWordsAloneAreNotAnIdentity() {
        assertThat(CounterpartyIdentity.keyOf("UPI/REF37/UPI")).isEmpty();
        assertThat(CounterpartyIdentity.keyOf("")).isEmpty();
        assertThat(CounterpartyIdentity.keyOf(null)).isEmpty();
        assertThat(CounterpartyIdentity.isStrong("")).isFalse();
        assertThat(CounterpartyIdentity.isStrong(null)).isFalse();
    }

    @Test
    void theKeyIsStableAcrossCasingAndSurroundingNoise() {
        assertThat(CounterpartyIdentity.keyOf("UPI-x-SampleUser@YBL-REF38"))
                .isEqualTo(CounterpartyIdentity.keyOf("upi-y-sampleuser@ybl-REF39"));
    }

    @Test
    void aKeyNeverExceedsTheColumnItIsStoredIn() {
        // Not a theoretical bound. transactions.description is VARCHAR(500) and this pipeline joins
        // wrapped continuation rows into one narration, so a long space-only narration is ordinary
        // input. SEGMENTS only splits on - / _ | : , so such a narration is a SINGLE segment and
        // meaningfulPart concatenates all of it: measured before the cap existed, this input keyed
        // to 505 characters against a VARCHAR(120) column. Uncapped, the INSERT fails -- and in
        // ImportService.confirm one such row fails the user's whole statement.
        String longNarration = ("ALPHA ".repeat(84)).substring(0, 500);
        assertThat(longNarration).hasSize(500);

        String key = CounterpartyIdentity.keyOf(longNarration);

        assertThat(key).hasSizeLessThanOrEqualTo(CounterpartyIdentity.MAX_KEY_LENGTH);
        assertThat(CounterpartyIdentity.MAX_KEY_LENGTH).isEqualTo(120); // == V142's VARCHAR(120)
    }

    @Test
    void aVpaKeyIsCappedToo_theLocalPartHasNoBoundOfItsOwn() {
        // "[A-Za-z0-9._]{2,}" is unbounded, so the VPA branch is no safer than the name branch and
        // must not be left to the assumption that handles are short.
        String key = CounterpartyIdentity.keyOf("UPI-" + "a".repeat(300) + "@ybl-REF40");

        assertThat(key).startsWith("vpa:");
        assertThat(key).hasSizeLessThanOrEqualTo(CounterpartyIdentity.MAX_KEY_LENGTH);
    }

    @Test
    void twoRowsCarryingTheSameOverLongNarrationStillGroupTogether() {
        // Why the cap truncates instead of returning "": grouping is the only thing this key is
        // for, and truncation preserves it. Returning "" would throw away a usable group.
        String narration = ("BETA ".repeat(101)).substring(0, 500);

        assertThat(CounterpartyIdentity.keyOf(narration))
                .isEqualTo(CounterpartyIdentity.keyOf(narration))
                .isNotEmpty();
    }

    @Test
    void recurringMandateBoilerplateDoesNotFragmentTheSamePayeeAcrossOccurrences() {
        // Measured on the real 29-statement corpus: one AMC's SIP mandate debit produced THREE
        // different keys across its own occurrences purely because the bank appends this
        // boilerplate inconsistently row to row -- bare, "...DEBIT CMP MANDATE DEBIT", and
        // "...Balance DEBIT CMP MANDATE DEBIT" all keyed differently before MANDATE/DEBIT/BALANCE/
        // CMP joined NOISE. Reproduced synthetically here (not the real corpus narration -- see
        // this repo's own "describe, don't quote, real evidence" practice).
        String bare = "SAMPLE ASSET MANAGEMENT LTD";
        String withDebitMandate = "SAMPLE ASSET MANAGEMENT LTD DEBIT CMP MANDATE DEBIT";
        String withBalanceDebitMandate = "SAMPLE ASSET MANAGEMENT LTD Balance DEBIT CMP MANDATE DEBIT";

        String key = CounterpartyIdentity.keyOf(bare);
        assertThat(CounterpartyIdentity.keyOf(withDebitMandate)).isEqualTo(key);
        assertThat(CounterpartyIdentity.keyOf(withBalanceDebitMandate)).isEqualTo(key);
        assertThat(key).isEqualTo("name:sample asset management ltd");
    }

    @Test
    void aRealLongPayeeNameIsNotTruncated() {
        // The regression guard for the fix that was NOT made. A first-N-words cap was proposed,
        // measured against the real corpus, and rejected: 96% of real name: keys are already under
        // 30 characters, and the long tail is dominated by real long payee names -- truncating them
        // would produce a WORSE, more collision-prone key than leaving them alone, the over-merge
        // failure mode this class's own doc says is worse than the status quo. Synthetic shape
        // (proprietor-plus-firm) rather than the real corpus narration -- see this class's own
        // "describe, don't quote" note above.
        String key = CounterpartyIdentity.keyOf(
                "UPI/SAMPLE ENTERPRISES SURNAME FIRSTNAME MIDDLENAME/Q/UPI/");

        assertThat(key).isEqualTo("name:sample enterprises surname firstname middlename");
    }

    // ---- over-merges measured on the real corpus: one key shared by different people ----

    @Test
    void aPaymentAppNameIsNotTheSender_twoPeopleOnPhonePeGetTwoKeys() {
        // "Payment from PhonePe_<NAME>": the app name outlasted a short payee name and every sender
        // on that app shared one key -- a choice for one of them reached all of them.
        String alpha = CounterpartyIdentity.keyOf("UPI/RRN 111111111111/Payment from PhonePe_ALPHA");
        String bravo = CounterpartyIdentity.keyOf("UPI/RRN 222222222222/Payment from PhonePe_BRAVO");
        assertThat(alpha).isEqualTo("name:alpha");
        assertThat(bravo).isEqualTo("name:bravo");
    }

    @Test
    void aNameSegmentCarryingAReferenceKeepsItsWords_notTheBankCodeBesideIt() {
        // A reference glued into the payee's segment used to drop the whole segment, leaving the
        // remitting bank's short code as the "sender" -- one key for everyone paying from that bank.
        String key = CounterpartyIdentity.keyOf("UPI/111111111111/CR/ALPHA BRAVO S11111111 CHO/SBI/UPI");
        assertThat(key).isEqualTo("name:alpha bravo cho");
    }

    @Test
    void aMaskedVpaIsNotAStrongIdentity_andKeepsItsHandle() {
        // Some statements print only the last characters of the payer's VPA ("**TAILX@OKICICI").
        // The tail alone is shared by strangers; with its handle it at least splits by PSP, and it
        // must never pass as a strong key.
        String one = CounterpartyIdentity.keyOf("UPI/CR/111111111111/ALPHA B/SBIN/**TAILX@OKICICI/UPI");
        String two = CounterpartyIdentity.keyOf("UPI/CR/222222222222/CHARLIE D/PUNB/**TAILX@OKAXIS/UPI");
        assertThat(one).isEqualTo("masked:tailx@okicici");
        assertThat(two).isEqualTo("masked:tailx@okaxis");
        assertThat(CounterpartyIdentity.isStrong(one)).isFalse();
    }

    @Test
    void aCardMerchantCreditWithItsReferenceStillGetsAKey() {
        // Space-only narration with the reference inside the one segment: it used to key to "" and
        // the user could not say "every payment from" this merchant.
        assertThat(CounterpartyIdentity.keyOf("UPI SHOPCO INSTAMART 111111111111"))
                .isEqualTo("name:shopco instamart");
    }

    @Test
    void theWordLevelFallbackNeverKeysOnStatementFurnitureOrABrokenVpaHandle() {
        // Guards for the fallback: "Value Dt ... Ref ..." and a wrapped VPA's PSP handle are on
        // every row of their statement, so keying on them would merge unrelated payees.
        assertThat(CounterpartyIdentity.keyOf("111111111-UPI-111111111111 Value Dt 10/07/2026 Ref 1111111111111111"))
                .isEmpty();
        // These three used to be unreadable (a wrapped id, a linked-account suffix) and were pinned
        // empty so the fallback could not key on their handle. Since 2026-10-02 the id itself is
        // read -- whole, and without the suffix -- which is the identity they always carried.
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/02:44:32/UPI/paytm.s11a11 p@pty/U"))
                .isEqualTo("vpa:paytm.s11a11p");
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/ALPHABRAVO1111-1@OKAXIS/UPI/111111111111/HDFC BANK/"))
                .isEqualTo("vpa:alphabravo1111");
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/17:29:53/UPI/9111111111-3@ybl/Pa"))
                .isEqualTo("vpa:9111111111");
    }

    @Test
    void aRowThatAlreadyHadAKeyKeepsIt_theFallbackOnlyFillsBlanks() {
        // Word-level keying on every row re-picked the longest segment here ("EXCL TAX").
        assertThat(CounterpartyIdentity.keyOf("FP EMI 06/12(EXCL TAX   49.40)")).isEqualTo("name:fp emi");
    }

    @Test
    void aBankOrAppNameIsSkippedOnlyWhenItIsTheWholeSegment_soTwoInsurersStayApart() {
        // As noise words, "HDFC" and "SBI" left both insurers keyed "life".
        assertThat(CounterpartyIdentity.keyOf("NACH/HDFC LIFE INSURANCE/11111"))
                .isNotEqualTo(CounterpartyIdentity.keyOf("NACH/SBI LIFE INSURANCE/22222"));
        // "VALUE" is a word in real payee names; only the "Value Dt" date label is furniture.
        assertThat(CounterpartyIdentity.keyOf("UPI/VALUE MART/REF")).isEqualTo("name:value mart");
    }

    // Measured on a real Kotak statement: the payee is glued to the reference, so every reference-
    // bearing segment was skipped and the key fell to the free-text note ("name:rent",
    // "name:savings"), splitting one payee into several groups.
    @Test
    void aKotakImpsDebitIsKeyedOnThePayeeGluedAfterItsReference() {
        assertThat(CounterpartyIdentity.keyOf("SentIMPS100000000001Asha Verma/HDFC0XXXXXX/IMPS"))
                .isEqualTo("name:asha verma");
        assertThat(CounterpartyIdentity.keyOf("SentIMPS100000000002Asha Verma/HDFC0XXXXXX/RENT"))
                .isEqualTo("name:asha verma");
    }

    // F-22, measured on the corpus: a line wrap left a space inside a VPA's local part
    // ("SAMPLEPAY EE@HDFCBANK"), and the key kept only the scrap after it, which joined strangers.
    @Test
    void aVpaWhoseLocalPartAWrapSplit_isKeyedOnTheWholeLocalPart() {
        assertThat(CounterpartyIdentity.keyOf("UPI-SAMPLE PAYEE-SAMPLEPAY EE@HDFCBANK-HDFC0XXXXXX-100000000001"))
                .isEqualTo("vpa:samplepayee");
        assertThat(CounterpartyIdentity.keyOf("UPI/100000000001/UPI/paytmqr6ab ur@ptys/"))
                .isEqualTo("vpa:paytmqr6abur");
    }

    @Test
    void aShortVpaLocalPartWithNothingBeforeItIsKeptAsItIs() {
        // Since 2026-10-02 the hyphenated id in a "/" field is read whole: "01" alone was shared by
        // every store whose id ends that way.
        assertThat(CounterpartyIdentity.keyOf("UPI/SAMPLE STORE/1111-01@JIOPAY/222")).isEqualTo("vpa:1111-01");
        assertThat(CounterpartyIdentity.keyOf("ab@ybl")).isEqualTo("vpa:ab");
    }

    // Measured on a real CBI statement: "UPI/RRN <ref>/Pay for Intent" keyed as "name:for".
    @Test
    void aUpiIntentBoilerplateNarrationHasNoKey() {
        assertThat(CounterpartyIdentity.keyOf("UPI/RRN 100000000001/Pay for Intent")).isEmpty();
    }

    // ---- 2026-10-02: one person, one key, across the shapes the corpus measured ----

    @Test
    void aSecondAccountSuffixIsTheSamePerson() {
        // A UPI app adds "-1", "-2" when the same person links another bank account. Measured on
        // nine corpus statements (HDFC, PNB, SC, Canara, ICICI, BOB): with the suffix the id was
        // not read at all and the row fell to a weak name key, splitting one person in two.
        String plain = CounterpartyIdentity.keyOf("UPI/CR/111111111111/AMAN KUM/SBIN/samplefriend47@oksbi/");
        String suffixed = CounterpartyIdentity.keyOf("UPI/DR/111111111112/Mr AMAN/CBIN/samplefriend47-1@oki/U");
        assertThat(plain).isEqualTo("vpa:samplefriend47");
        assertThat(suffixed).isEqualTo(plain);
        assertThat(CounterpartyIdentity.keyOf("UPI-SAMPLE NAME-9111111111-3@ybl-REF1")).isEqualTo("vpa:9111111111");
    }

    @Test
    void aMultiDigitSuffixIsPartOfTheIdNotASecondAccount() {
        // Only a single 1-9 digit is a linked-account suffix; anything else may be a different id.
        assertThat(CounterpartyIdentity.keyOf("UPI-SHOPCO-shopco.store-12@okbank-REF1")).isNotEqualTo("vpa:shopco.store");
    }

    @Test
    void aLineWrapInsideTheIdIsRejoinedWhole_neverCutToItsLastPiece() {
        // Measured: a wrap left "...MARKETPLAC EPRIVA.PAYU@..." and the key was the scrap after the
        // space, so different shops whose wrapped ids end alike shared one key (an over-merge).
        assertThat(CounterpartyIdentity.keyOf(
                "UPI-SHOPCO MARKETPLACE PR-SHOPCOMARKETPLAC EPRIVA.PAYU@MAIRTEL-AIRP0XXXXXX-111111111111-UPI"))
                .isEqualTo("vpa:shopcomarketplacepriva.payu");
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/03:50:05/UPI/samplejudoka 407@ok"))
                .isEqualTo("vpa:samplejudoka407");
        // Two different merchants whose wrapped ids end in the same four digits stay apart.
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/21:34:04/UPI/gpay-1111111 1801@ok"))
                .isNotEqualTo(CounterpartyIdentity.keyOf("UPI/111111111112/21:35:04/UPI/gpay-2222222 1801@ok"));
    }

    @Test
    void aHyphenInsideASlashSeparatedIdIsPartOfTheId() {
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/13:14:05/UPI/shop-payment s@zzbnk"))
                .isEqualTo("vpa:shop-payments");
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/21:34:04/UPI/gpay-1111111 1801@ok"))
                .isEqualTo("vpa:gpay-11111111801");
        // A hyphen-delimited narration still stops at the hyphen: there it separates fields.
        assertThat(CounterpartyIdentity.keyOf("UPI-SUNIL VERMA-sampleuser@ybl-REF1")).isEqualTo("vpa:sampleuser");
    }

    @Test
    void aWrappedPhoneNumberIdIsRejoinedWhole() {
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/Samplena/BDBL/911111111 1@ptye/"))
                .isEqualTo("vpa:9111111111");
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLENA/SBIN/911111 1111@ibl/U"))
                .isEqualTo("vpa:9111111111");
    }

    @Test
    void aNameWordBeforeTheIdIsNeverGluedOn() {
        // Only a piece that starts right after a field separator is a wrapped part of the id.
        assertThat(CounterpartyIdentity.keyOf("UPI-SUNIL VERMA sampleuser@ybl-REF1")).isEqualTo("vpa:sampleuser");
    }

    @Test
    void theStandardUpiLayoutsIdSlotIsReadEvenWhenTheBankCutItBeforeTheAt() {
        // "UPI/<DR|CR>/<ref>/<name>/<bank>/<id>/...": some banks print about 16 characters of the id,
        // so the "@" is often gone. The slot is still the id, cut at the same width every time --
        // but with no "@" its end is not proven, so it is the weak "cut:" key.
        assertThat(CounterpartyIdentity.keyOf("UPI/CR/111111111111/SAMPLENA/BARB/samplen ame.dadas/"))
                .isEqualTo("cut:samplename.dadas");
        assertThat(CounterpartyIdentity.keyOf("UPI/CR/111111111111/SAMPLE N/HDFC/samplename18/U"))
                .isEqualTo("cut:samplename18");
        assertThat(CounterpartyIdentity.keyOf("UPI/CR/111111111111/MR SAMPL/SCBL/samplefriend-1/"))
                .isEqualTo("cut:samplefriend");
        // The "@" survived, so the local part before it is whole.
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/CHAND DI/KJSB/samplefriend1831@/"))
                .isEqualTo("vpa:samplefriend1831");
    }

    @Test
    void anIdCutBeforeItsAt_groupsItsOwnRows_butIdentifiesNoOne() {
        // A bank that prints only the first characters of the id cuts every shop under one payment
        // brand's prefix to the same text: on the corpus one such prefix began five different shops' ids.
        String one = CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLE S/YESB/sampleqr1111111/");
        String two = CounterpartyIdentity.keyOf("UPI/DR/111111111112/OTHER SH/YESB/sampleqr1111111/");
        assertThat(one).isEqualTo("cut:sampleqr1111111").isEqualTo(two);
        assertThat(CounterpartyIdentity.isStrong(one)).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(one)).isFalse();
    }

    @Test
    void aPaymentGatewaysOwnId_identifiesNoOne_aShopsIdOnTheGatewayDoes() {
        // The gateway's own id settles refunds and payments for every shop on it; the shop is at
        // most in the free-text remark. The key stays, so the rows still group; it just names no one.
        String refund = CounterpartyIdentity.keyOf(
                "UPI-RAZORPAY-PG.RAZORPAY@SAMPLEBANK-SMPL0XXXXXX-111111111111-SAMPLESHOPREFUNDX1");
        assertThat(refund).isEqualTo("vpa:pg.razorpay");
        assertThat(CounterpartyIdentity.identifiesOnePayee(refund)).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf(
                "UPI-PHONEPE-PHONEPEMERCHANT@SAMPLEBANK-SMPL0XXXXXX-111111111111-R11 PHONEPE REVERS"))).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf(
                "UPI PAYMENT RECEIVED/GPAYREFUND-ONLINE@SAMPLEBANK"))).isFalse();
        // A shop's own id on a gateway names that shop.
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:sampleshop.rzp")).isTrue();
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:sampleshop.payu")).isTrue();
    }

    @Test
    void theIdSlotIsNotReadWhenItHoldsWordsRatherThanAnId() {
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLENA/SBIN/Payment for rent/"))
                .doesNotStartWith("vpa:");
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLENA/SBIN/Rent June/"))
                .doesNotStartWith("vpa:");
        // A masked id is still the weak masked key, not a slot read.
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLE N/CNRB/**1111-1@ybl//X"))
                .startsWith("masked:");
    }

    @Test
    void aCapitalisedNoteWithADigitInTheIdSlotIsNotAnId() {
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLENA/SBIN/Rent June2026/"))
                .doesNotStartWith("vpa:");
        assertThat(CounterpartyIdentity.keyOf("UPI/CR/111111111111/SAMPLECO/DEUT/DEUT2 111111111@/"))
                .isEqualTo("vpa:deut2111111111"); // an "@" vouches for it whatever the case
    }

    @Test
    void aTimeOrAReferenceBeforeTheIdIsNeverGluedOn() {
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/03:50:05 sampleuser@okbank/X"))
                .isEqualTo("vpa:sampleuser");
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111 sampleuser@okbank/X"))
                .isEqualTo("vpa:sampleuser");
        // A phone number split by a wrap is ten digits, and is still rejoined.
        assertThat(CounterpartyIdentity.keyOf("UPI/111111111111/12:00:00/UPI/911111111 1@ybl/"))
                .isEqualTo("vpa:9111111111");
    }

    @Test
    void theSameIdPrintedWholeAndCutGetsOneKey() {
        assertThat(CounterpartyIdentity.keyOf("UPI/DR/111111111111/SAMPLE S/HSBC/9111111111@ybl/UPI"))
                .isEqualTo(CounterpartyIdentity.keyOf("UPI/DR/111111111112/Samplena/BDBL/911111111 1@ptye/"));
    }

    // --- identifiesOnePayee: may a choice for one row be applied to the others with this key? ---

    @Test
    void aFullUpiIdOrARealName_identifiesOnePayee() {
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:metrorail.sample")).isTrue();
        assertThat(CounterpartyIdentity.identifiesOnePayee("name:sample cafe")).isTrue();
        // A gateway word alongside the merchant's own name still names the merchant.
        assertThat(CounterpartyIdentity.identifiesOnePayee("name:samplecanteen payu")).isTrue();
    }

    @Test
    void aMaskedUpiId_doesNotIdentifyOnePayee() {
        // The printed tail is shared by strangers: on the corpus one joined two different shops.
        assertThat(CounterpartyIdentity.identifiesOnePayee("masked:.payu@shopcobk")).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee("masked:5.rzp@shopcobk")).isFalse();
    }

    @Test
    void aNameMadeOnlyOfRailAndGatewayWords_doesNotIdentifyOnePayee() {
        // The keys these real narration shapes produce: the payee was never printed.
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf("UPI/RRN 111111111111/UPIIntent"))).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf("UPI/RRN 111111111111/Pay via Razorpay"))).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf("UPI/RRN 111111111111/Pay to BharatPe Merchant"))).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee(CounterpartyIdentity.keyOf("UPIRET-20250505-111111111111"))).isFalse();
    }

    @Test
    void noKey_identifiesNoOne() {
        assertThat(CounterpartyIdentity.identifiesOnePayee(null)).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee("")).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:")).isFalse();
        assertThat(CounterpartyIdentity.identifiesOnePayee("name:")).isFalse();
    }

    @Test
    void aPaymentBrandsPartnerPrefixAloneIdentifiesNoOne_itsFullMerchantIdDoes() {
        // Cut to the partner's digits, one key joins every shop under that partner.
        assertThat(CounterpartyIdentity.identifiesOnePayee(
                CounterpartyIdentity.keyOf("UPI/DR/100000000001/SAMPLE E/INDB/bajajpay.100000/"))).isFalse(); // synthetic-ok
        assertThat(CounterpartyIdentity.identifiesOnePayee(
                CounterpartyIdentity.keyOf("UPI/DR/100000000002/Ramesh/INDB/bajajpay.100 000/S"))).isFalse(); // synthetic-ok
        // Printed whole, the merchant part names one shop.
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:bajajpay.1000000.dep1000000")).isTrue();
        assertThat(CounterpartyIdentity.identifiesOnePayee("vpa:punemetroabcde")).isTrue();
    }

    @Test
    void payeeHandle_readsTheIdSlotEvenInCapitals_butNeverANote() {
        // keyOf leaves a capitalised id slot to the name fallback; as evidence of who was paid it counts.
        assertThat(CounterpartyIdentity.payeeHandle("UPI/DR/100000000001/BHARTI A/AIRP/airtelprepaidXY/"))  // synthetic-ok
                .isEqualTo("airtelprepaidxy");
        assertThat(CounterpartyIdentity.payeeHandle("UPI/DR/100000000002/SAMPLE E/INDB/bajajpay.100 000/S")) // synthetic-ok
                .isEqualTo("bajajpay.100000");
        assertThat(CounterpartyIdentity.payeeHandle("UPI/DR/100000000003/RAVI KUM/SBIN/ravi.k@okaxis/UPI"))   // synthetic-ok
                .isEqualTo("ravi.k");
        // A capitalised slot with a space is a note.
        assertThat(CounterpartyIdentity.payeeHandle("UPI/DR/100000000004/RAVI KUM/SBIN/Rent June/")).isEmpty(); // synthetic-ok
        // Other layouts: the VPA's local part, or nothing.
        assertThat(CounterpartyIdentity.payeeHandle("UPI-SUNIL VERMA-sunil.verma@icici-XXXX0001234-REF32-UPI"))  // synthetic-ok
                .isEqualTo("sunil.verma");
        assertThat(CounterpartyIdentity.payeeHandle("NEFT ACME TECHNOLOGIES PVT LTD REF23")).isEmpty();
        assertThat(CounterpartyIdentity.payeeHandle(null)).isEmpty();
    }
}
