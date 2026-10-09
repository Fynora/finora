package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interesting assertions here are the ORDERING ones. Any single type is easy to get right in
 * isolation; the failure mode that matters is a row carrying two signals being typed by the weaker
 * one, and that is what most of these pin.
 */
class CounterpartyClassifierTest {

    @Test
    void aMerchantAcquiringRailMakesItABusiness_whateverNameIsOnThePayeeLine() {
        // The case the whole layer exists for: a small merchant collecting on what looks exactly
        // like a person. Measured at 543 rows on the real corpus.
        assertThat(CounterpartyClassifier.classify("UPI-RAJESH KUMAR-Q710750321@ybl-REF21"))  // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
        assertThat(CounterpartyClassifier.classify("UPI-SUNIL VERMA-payu@sample-REF22"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void aBrandCollectingThroughAMerchantPseudoBranchIsABusiness_notAPersonReadOffItsRemark() {
        // A real corpus shape, with the brand and product words invented: HDFC writes UPI rows as
        // UPI-<payee>-<handle>-<IFSC>-<ref>-<remark>. The payee here is one brand word, which the
        // person check rightly declines, but the remark the brand's own app writes is three words
        // and an initial, and the person check reads every segment -- so the remark alone typed the
        // row PERSON, and a refund on the same narration was never linked to its payment. The IFSC
        // is what settles it: DC0099 is a merchant pseudo-branch, and every corpus row routed
        // through it is a business or an institution.
        String brand = "UPI-ACMETRIP-ACMETRIP.RAIL@ICICI-XXXX0DC0099-REF31-ACMETRIP RAIL TRIP I"; // synthetic-ok
        assertThat(PersonToPersonTransferDetector.hasMerchantAcquirerMarker(brand)).isTrue();
        assertThat(CounterpartyClassifier.classify(brand)).isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void aPersonsDottedHandleOnAnOrdinaryBranchIsStillAPerson() {
        // The counterweight to the test above: <first>.<last>@<bank> is also how people name their
        // own handles, so the handle's shape is not what makes that row a business. Only the
        // pseudo-branch does, and an ordinary branch code must leave a person a person.
        String person = "UPI-SUNIL VERMA-sunil.verma@icici-XXXX0001234-REF32-UPI"; // synthetic-ok
        assertThat(PersonToPersonTransferDetector.hasMerchantAcquirerMarker(person)).isFalse();
        assertThat(CounterpartyClassifier.classify(person)).isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void aCorporateSuffixMakesItABusinessWithoutAnyRailMarker() {
        assertThat(CounterpartyClassifier.classify("NEFT ACME TECHNOLOGIES PVT LTD REF23"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void anIndividualWithNoBusinessSignalIsAPerson() {
        assertThat(CounterpartyClassifier.classify("UPI-SUNIL VERMA-sampleuser@ybl-REF24"))
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void bankGeneratedActivityIsTheInstitution_notAGenericBusiness() {
        // Ordering guard. "BANK" is in the detector's business-token vocabulary -- correct for
        // vetoing a person, wrong as a final type. If FINANCIAL_MECHANISM/FINANCIAL_ENTITY stopped
        // running first, every interest credit and charge on the corpus would type as BUSINESS.
        assertThat(CounterpartyClassifier.classify("SB INT CREDIT"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("ATM WDL CHARGES"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("NEFT SAMPLE BANK LIMITED REF25"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    @Test
    void aFinancialInstitutionOutranksACorporateSuffixOnTheSameRow() {
        // "SAMPLE SECURITIES PVT LTD" carries both an institution word and a corporate suffix.
        // A broker is not a merchant, and answering BUSINESS here would lose that distinction for
        // anything that later reasons about investment flows.
        assertThat(CounterpartyClassifier.classify("NEFT SAMPLE SECURITIES PVT LTD REF26"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    @Test
    void aTaxBodyIsGovernment_notABusiness() {
        assertThat(CounterpartyClassifier.classify("GST PAYMENT CHALLAN REF27"))
                .isEqualTo(CounterpartyType.GOVERNMENT);
    }

    @Test
    void aFeePaidToThePassportPortalOrTheExamBodyIsGovernment_notAPersonsTransfer() {
        // The portal's payee line is three name-shaped words, so the HDFC slot rule read it as a
        // person and the refund pass would never have linked a returned fee.
        String passport = "UPI-PASSPORT SEVA PROJEC-passportseva.sample@sbi-XXXX0001234-REF61-FEE"; // synthetic-ok
        assertThat(CounterpartyClassifier.classify(passport)).isEqualTo(CounterpartyType.GOVERNMENT);
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(passport)).isFalse();
        assertThat(CounterpartyClassifier.classify("UPIAR/REF62/DR/Passport/SBIN/passportseva.sample"))
                .isEqualTo(CounterpartyType.GOVERNMENT);
        assertThat(CounterpartyClassifier.classify("UPI/REF63/UPI/upsc.sample@sbi"))
                .isEqualTo(CounterpartyType.GOVERNMENT);
    }

    @Test
    void aRemarkOrABankBranchIsNotEvidenceAboutTheCounterparty() {
        // A payer's remark with "AND" in it typed a person BUSINESS; a branch called "... BANK"
        // printed after the payee typed a person FINANCIAL_INSTITUTION.
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA RAO-sampleuser-4@okaxis-XXXX0001234-100000000001-MAY LIGHT BILL AND WATER")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify(
                "UPI/100000000001/ SUNITA RAO/sampleuser@okhdfcbank/1000/UPI/100000000001/SAMPLE BANK/")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        // And a business stays one on its own words once the branch stops speaking for it.
        assertThat(CounterpartyClassifier.classify(
                "UPI/100000000001/ ACME HILL HOTEL/sampleuser@ybl/XXXX0001234 1000/UPI/100000000001/SAMPLE BANK/")) // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
        // A resort is never a person, but "resort" is not a business veto (a payer can write it in a
        // remark), so with nothing else to go on it reads UNKNOWN -- not the bank the branch said.
        assertThat(CounterpartyClassifier.classify(
                "UPI/100000000001/ ACME HILL RESORT/sampleuser@ybl/XXXX0001234 1000/UPI/100000000001/SAMPLE BANK/")) // synthetic-ok
                .isEqualTo(CounterpartyType.UNKNOWN);
    }

    @Test
    void aTradeWordInAPayersRemarkDoesNotMakeAFriendABusiness() {
        assertThat(CounterpartyClassifier.classify("UPI/SUNITA RAO/REF5/RESORT SHARE")).isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify("UPI/SUNITA RAO/REF6/SHOPEE ORDER")).isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void aPersonPaidUnderACareOfNameIsAPerson_andACompanyEndingInCoIsStillABusiness() {
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA KHAN CO RAO-sampleuser-1@oksbi-XXXX0001234-100000000001-UPI")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify("NEFT-RAMESH CO-REF71"))
                .isEqualTo(CounterpartyType.BUSINESS);
        // One word before CO is a company with its town after it, not care of.
        assertThat(CounterpartyClassifier.classify("NEFT-SHARMA CO PUNE-REF72"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void inTheStandardSlashLayout_aPayersNoteAfterTheUpiIdCannotMakeAFriendABank() {
        // UPI/<DR|CR>/<ref>/<name>/<bank>/<id>/<note>: the note was read as the payee's own words,
        // so a friend who wrote "cashback" (or "interest", "reward") was typed a bank. The same
        // narration in the hyphen layout already reads PERSON.
        for (String note : new String[] {"cashback", "interest", "reward", "atm"}) {
            assertThat(CounterpartyClassifier.classify(
                    "UPI/CR/600011112222/SUNITA RAO/HDFC/sunita.rao@okhdfc/" + note)) // synthetic-ok
                    .as(note).isEqualTo(CounterpartyType.PERSON);
        }
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA RAO-sunita.rao@okhdfc-HDFC0XXXXXX-600011112222-cashback")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void inTheStandardSlashLayout_theNoteStillDecidesWhenThePayeeSaysNothing() {
        // A bare number for a name says nothing about who paid (a one-word name in this layout
        // already reads as a person), so the note's mechanism word decides, as in the hyphen layout.
        assertThat(CounterpartyClassifier.classify(
                "UPI/CR/600011112222/9999999999/YESB/9999999999@ybl/cashback")) // synthetic-ok
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("UPI/CR/600011112222/9999999999/YESB/9999999999@ybl")) // synthetic-ok
                .isEqualTo(CounterpartyType.UNKNOWN);
    }

    @Test
    void aRemarkSpeaksOnlyWhenThePayeesOwnWordsSayNothing_andNeverOverAPerson() {
        // Payee says nothing: the remark's strong signals decide.
        assertThat(CounterpartyClassifier.classify(
                "UPI-SAMPLEAPP-sampleapp@ybl-XXXX0001234-100000000001-CASHBACK")) // synthetic-ok
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify(
                "UPI-SAMPLEAPP-sampleapp@ybl-XXXX0001234-100000000001-GST CHALLAN")) // synthetic-ok
                .isEqualTo(CounterpartyType.GOVERNMENT);
        assertThat(CounterpartyClassifier.classify(
                "UPI-SAMPLEAPP-sampleapp@ybl-XXXX0001234-100000000001-PAY TO BHARATPE MERCHANT")) // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
        assertThat(CounterpartyClassifier.classify(
                "UPI/100000000001/sample@ybl/sample@ybl/XXXX0001234 1000/CASHBACK/100000000001/SAMPLE BANK/")) // synthetic-ok
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        // A weak word (a bank's name) in the remark, or the printed branch, still says nothing.
        assertThat(CounterpartyClassifier.classify(
                "UPI-SAMPLEAPP-sampleapp@ybl-XXXX0001234-100000000001-HDFC BANK")) // synthetic-ok
                .isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(CounterpartyClassifier.classify(
                "UPI/100000000001/sample@ybl/sample@ybl/XXXX0001234 1000/UPI/100000000001/SAMPLE BANK/")) // synthetic-ok
                .isEqualTo(CounterpartyType.UNKNOWN);
        // A friend's remark never makes them a bank, whether named in full or by a first name.
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA RAO-sampleuser@ybl-XXXX0001234-100000000001-LOAN INTEREST")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA-9999999999@ybl-XXXX0001234-100000000001-INTEREST")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void aHandleSpelledLikeAWebDomainIsNotACompany() {
        assertThat(CounterpartyClassifier.classify(
                "UPI-SUNITA RAO-sunita.co.in@ybl-XXXX0001234-100000000001-UPI")) // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify(
                "UPI-ACME TRADERS-acme.co.in@ybl-XXXX0001234-100000000001-UPI")) // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void aTwoWordCafeChainIsABusiness_onceItIsAKnownMerchant() {
        // Structurally a two-word brand is indistinguishable from a person's name (the detector's
        // documented limitation); the merchant vocabulary is what separates them.
        String cafe = "UPI-TEA POST-sampleoutlet@ybl-XXXX0YBLUPI-REF64-PAYMENT FOR ORDER"; // synthetic-ok
        assertThat(CounterpartyClassifier.classify(cafe)).isEqualTo(CounterpartyType.BUSINESS);
        assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(cafe)).isFalse();
        assertThat(CategoryRules.suggestCategory(cafe)).isEqualTo("Dining");
    }

    @Test
    void nothingIdentifiableIsUnknownRatherThanAGuess() {
        // ~530 corpus rows land here. UNKNOWN is the honest answer, and the codebase prefers it to
        // a confident wrong one -- this test exists so a future "improvement" that assigns a
        // default type has to delete an assertion that says not to.
        assertThat(CounterpartyClassifier.classify("UPI/REF28/UPI")).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(CounterpartyClassifier.classify("")).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(CounterpartyClassifier.classify(null)).isEqualTo(CounterpartyType.UNKNOWN);
    }

    @Test
    void theClassifierReusesTheDetectorsOwnMarkerSet_soTheTwoCannotDrift() {
        // Not a behaviour test -- a coupling test. The marker set has already grown twice; a second
        // copy inside this class would have missed the second wave and typed 232 corpus rows
        // UNKNOWN while the detector correctly treated them as businesses.
        String secondWaveMarker = "UPI-ANITA DESAI-PAYTMQR5130070702@paytm-REF29";  // synthetic-ok
        assertThat(PersonToPersonTransferDetector.hasMerchantAcquirerMarker(secondWaveMarker)).isTrue();
        assertThat(CounterpartyClassifier.classify(secondWaveMarker)).isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void theStatementIssuersOwnNameDoesNotOverrideTheActualCounterparty() {
        // Real HDFC/Kotak/SBI statements prefix EVERY narration with the issuer's own name
        // ("HDFC BANK LIMITED UPI-..."). FINANCIAL_ENTITY's "bank" token and CORPORATE_SUFFIX's
        // "limited" token both live in that boilerplate prefix, not in the actual payee -- so
        // matching them unconditionally against the whole narration flips a real person's name to
        // FINANCIAL_INSTITUTION regardless of who the money actually went to. Same fixture already
        // proven correct for PersonToPersonTransferDetector.isNamedIndividualTransfer in
        // PersonToPersonTransferDetectorTest#ignoresTheStatementOwnBankNamePrecedingTheTransferMarker.
        assertThat(CounterpartyClassifier.classify(
                "HDFC BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF773821"))
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify(
                "KOTAK MAHINDRA BANK LIMITED UPI-SUNITA RAO-sampleuser2@oksbi-REF7"))
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify(
                "STATE BANK OF INDIA UPI-SUNITA RAO-sampleuser2@oksbi-REF7"))
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void theStatementIssuersOwnNameStillLosesToARealBusinessSignalAfterTheMarker() {
        // The discount only applies BEFORE the transfer marker. A real institution or business
        // named AFTER it is still typed normally -- this is the existing
        // bankGeneratedActivityIsTheInstitution_notAGenericBusiness / aCorporateSuffixMakes... cases,
        // pinned again here so the issuer-prefix fix cannot widen into ignoring "bank"/"limited"
        // everywhere.
        assertThat(CounterpartyClassifier.classify("NEFT SAMPLE BANK LIMITED REF25"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("NEFT ACME TECHNOLOGIES PVT LTD REF23"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void aRealFinancialEntityWordAfterTheMarkerStillCountsEvenWhenTheSameWordWasDiscountedBefore() {
        // matchesOutsideIssuerPrefix scans EVERY match, not just the first -- a narration can carry
        // the issuer's own "BANK"/"LIMITED" before the marker (discounted) and the identical word
        // again after it, naming the real counterparty (not discounted). An implementation that
        // stopped at the first match -- the easy mistake here -- would wrongly return false and
        // lose this second, real occurrence.
        assertThat(CounterpartyClassifier.classify(
                "HDFC BANK LIMITED UPI-SAMPLE BANK LTD-REF001"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify(
                "HDFC BANK LIMITED UPI-XYZ LTD-REF002"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void theIssuerDiscountNeverAppliesWithoutATransferMarkerToAnchorIt() {
        // No UPI/NEFT/IMPS/RTGS marker means no boundary between "issuer boilerplate" and "actual
        // counterparty" -- markerStart is -1, and matchesOutsideIssuerPrefix must count every match
        // rather than silently discounting "BANK"/"LIMITED" everywhere. A genuine bank-charged fee
        // narration has no counterparty to protect, so this must still answer FINANCIAL_INSTITUTION.
        assertThat(CounterpartyClassifier.classify("HDFC BANK LIMITED ANNUAL FEE REF001"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    @Test
    void cashbackAndRewardCreditsAreTheInstitution_notAMerchantAndNotAPerson() {
        // 18 of the 40 inbound rows in the rail-less residue were these -- the largest single group
        // there by count, though near-zero by value, which is why a value-weighted view never
        // surfaced them. The counterparty is the card issuer running the programme.
        assertThat(CounterpartyClassifier.classify("CASHBACK EARNED JUL"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
        assertThat(CounterpartyClassifier.classify("REWARD POINTS CREDIT"))
                .isEqualTo(CounterpartyType.FINANCIAL_INSTITUTION);
    }

    // Measured on a real Kotak statement: an IMPS debit prints its payee glued to the reference,
    // "SentIMPS<ref><payee>/<IFSC>/<note>". The same payee was typed UNKNOWN on 13 rows (the name
    // hidden behind the digits) and PERSON on one, read off a note that looked like a name.
    @Test
    void aKotakImpsDebitIsTypedFromItsPayee_neverFromItsNote() {
        assertThat(CounterpartyClassifier.classify("SentIMPS100000000001Asha Verma/HDFC0XXXXXX/IMPS"))
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(CounterpartyClassifier.classify("SentIMPS100000000001Asha Verma/HDFC0XXXXXX/RENT"))
                .isEqualTo(CounterpartyType.PERSON);
        // A note that reads like a name decides nothing about the payee.
        assertThat(CounterpartyClassifier.classify("SentIMPS100000000001SAMPLE TRADERS PVT LTD/HDFC0XXXXXX/Last Thin"))
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    // Measured on a tester's statement (2026-10-02): one bank prints "UPI/<DR|CR>/<ref>/<name>/<bank>/<id>/"
    // with the name cut to eight characters and the id cut before its "@". A metro operator, an app
    // store and a phone company then carry no merchant evidence but a cut name that reads as two
    // words, and typed PERSON. The values below are invented in the same shapes.
    @Test
    void aShopWhoseNameTheBankCutIsReadFromItsUpiId() {
        for (String shop : new String[] {
                "UPI/DR/100000000001/PUNE MET/HDFC/punemetroabcde/",      // synthetic-ok
                "UPI/CR/100000000002/PUNE MET/HDFC/punemetroabcde/",      // synthetic-ok
                "UPI/DR/100000000003/APPLE ME/HDFC/appleservices.x/",     // synthetic-ok
                "UPI/DR/100000000004/Www Airt/HDFC/airtelautopay.x/",     // synthetic-ok
                "UPI/DR/100000000005/BHARTI A/AIRP/airtelprepaidXY/"}) {  // synthetic-ok
            assertThat(CounterpartyClassifier.classify(shop)).as(shop).isEqualTo(CounterpartyType.BUSINESS);
            assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(shop)).as(shop).isFalse();
        }
    }

    @Test
    void aPersonWhoseUpiIdSpellsTheirOwnNameIsStillAPerson() {
        // The counterweight: people name their ids after themselves, so the id only counts when it
        // begins with a known brand -- never because it repeats the name beside it.
        assertThat(CounterpartyClassifier.classify("UPI/CR/100000000006/RAVI KUM/SBIN/ravikumar.x/"))  // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
        // A brand shorter than five letters is too short to tell from the start of a name.
        assertThat(CounterpartyClassifier.classify("UPI/DR/100000000007/OLAF SEN/SBIN/olafsen12/"))    // synthetic-ok
                .isEqualTo(CounterpartyType.PERSON);
    }

    @Test
    void paymentBrandMerchantIdsAreABusiness_whoeverTheBankNamesAsThePayee() {
        for (String shop : new String[] {
                // A payments brand printed as the one-word payee of its own numbered id, both ways.
                "UPI/DR/100000000009/autope/INDB/autope-10000001/",                          // synthetic-ok
                "UPI/CR/100000000010/autope/INDB/autope-10000001/PA",                        // synthetic-ok
                // One partner prefix, paid under a trade's cut name and under a first name.
                "UPI/DR/100000000011/SAMPLE E/INDB/bajajpay.100000/",                        // synthetic-ok
                "UPI/DR/100000000012/Ramesh/INDB/bajajpay.100 000/S",                        // synthetic-ok
                // The same family in HDFC's layout, through a merchant pseudo-branch.
                "UPI-ASHA RANI GUPTA-BAJAJPAY.1000000.DEP1000000@INDUS-XXXX0MERCHA-REF41-UPI", // synthetic-ok
                // A card-machine provider's terminal id beside the shop owner's full name.
                "UPI/ASHA RANI GUPTA/10000000001.PAYSWIFF@SAMPLE/REF42"}) {                  // synthetic-ok
            assertThat(CounterpartyClassifier.classify(shop)).as(shop).isEqualTo(CounterpartyType.BUSINESS);
            assertThat(PersonToPersonTransferDetector.isNamedIndividualTransfer(shop)).as(shop).isFalse();
        }
        // The brand word alone, without a numbered id, is not a rail.
        assertThat(PersonToPersonTransferDetector.hasMerchantAcquirerMarker("UPI/DR/100000000013/RAVI KUM/SBIN/bajajpay/"))  // synthetic-ok
                .isFalse();
    }
}
