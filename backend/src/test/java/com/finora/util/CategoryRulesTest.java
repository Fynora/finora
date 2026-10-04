package com.finora.util;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryRulesTest {

    @Test
    void normalize_lowercasesAndStripsPunctuation() {
        assertThat(CategoryRules.normalize("SWIGGY*ORDR9182 BLR")).isEqualTo("swiggy ordr9182 blr");
    }

    @Test
    void normalize_collapsesRepeatedWhitespace() {
        assertThat(CategoryRules.normalize("AMAZON.IN   PAYMTS")).isEqualTo("amazon in paymts");
    }

    @Test
    void normalize_handlesNullSafely() {
        assertThat(CategoryRules.normalize(null)).isEqualTo("");
    }

    @Test
    void extractMerchant_stripsNumericReferenceCodes() {
        assertThat(CategoryRules.extractMerchant("SWIGGY*ORDR9182 BANGALORE IN")).isEqualTo("swiggy bangalore in");
    }

    @Test
    void extractMerchant_limitsToFourTokens() {
        assertThat(CategoryRules.extractMerchant("ONE TWO THREE FOUR FIVE SIX")).isEqualTo("one two three four");
    }

    @Test
    void extractMerchant_fallsBackToUnknownForEmptyInput() {
        assertThat(CategoryRules.extractMerchant("")).isEqualTo("unknown");
    }

    @Test
    void extractMerchantLabel_isNullWhenNothingButARailTokenSurvives() {
        // "UPI-REF9182736" has no counterparty at all once the numeric reference is stripped --
        // extractMerchant() itself still returns "upi" (its raw reduction is reused elsewhere for
        // symmetric matching, see MerchantNormalizationEngine), but the per-transaction label the
        // UI shows -- and looks up on Logo.dev by name -- must not be a bare rail word: Logo.dev
        // resolves "upi"/"ach" to real, unrelated trademarked companies.
        assertThat(CategoryRules.extractMerchant("UPI-REF9182736")).isEqualTo("upi");
        assertThat(CategoryRules.extractMerchantLabel("UPI-REF9182736")).isNull();
    }

    @Test
    void extractMerchantLabel_isNullWhenEveryTokenIsARailWord() {
        // "ach" and "transfer" are both in PaymentRailTokens.RAIL_TOKENS -- no counterparty word
        // survives, so this must null out exactly like the single-rail-token case above.
        assertThat(CategoryRules.extractMerchantLabel("ACH TRANSFER")).isNull();
    }

    @Test
    void extractMerchantLabel_keepsTheRealMerchantWhenARailTokenLeadsIt() {
        // Plan 5: the rail word itself is no longer part of the name (it was "upi sunil verma").
        assertThat(CategoryRules.extractMerchantLabel("UPI-SUNIL VERMA-REF9182736"))
                .isEqualTo("sunil verma");
    }

    @Test
    void extractMerchantLabel_keepsUnknownAsIs() {
        // "unknown" is extractMerchant()'s own empty-input fallback (see the test above), not a
        // rail word -- extractMerchantLabel must not treat it as "nothing survived" and null it
        // out a second time.
        assertThat(CategoryRules.extractMerchantLabel("")).isEqualTo("unknown");
    }

    /** The narration carries the date the interest was earned for; the label must not. */
    @Test
    void extractMerchantLabel_givesEveryInterestCreditOneLabel_whateverDayItIsFor() {
        // Without a direction, the day survives -- what every interest credit used to be labelled.
        assertThat(CategoryRules.extractMerchantLabel("Interest Cr. for 03-Jan-2026")).isEqualTo("interest cr for 03");
        assertThat(CategoryRules.extractMerchantLabel("Interest Cr. for 03-Jan-2026", Transaction.Type.INCOME))
                .isEqualTo(CategoryRules.INTEREST_LABEL);
        assertThat(CategoryRules.extractMerchantLabel("Interest Cr. for 04-Jan-2026", Transaction.Type.INCOME))
                .isEqualTo("interest");
        assertThat(CategoryRules.extractMerchantLabel("INTEREST PAID TILL 31-MAR-2026", Transaction.Type.INCOME))
                .isEqualTo("interest");
        assertThat(CategoryRules.extractMerchantLabel("Int.Pd:01-05-2026 to 31-07-2026: 000000000000000",
                Transaction.Type.INCOME)).isEqualTo("interest");
        // Only the first four words are kept, which dropped "interest" from this one.
        assertThat(CategoryRules.extractMerchantLabel("SAVING A/C CREDIT INTEREST", Transaction.Type.INCOME))
                .isEqualTo("interest");
    }

    /** A debit keeps its own label, so refund matching never reads an interest credit as its refund. */
    @Test
    void extractMerchantLabel_leavesEveryOtherRowAsTheDirectionlessLabelHasIt() {
        for (String description : new String[]{"Interest Cr. for 03-Jan-2026", "INTEREST ON EMI",
                "EMI INTEREST - 1/6, REF# 00000000", "CASHBACK EARNED", "UPI-SUNIL VERMA-REF9182736",
                "SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", "UPI-REF9182736", ""}) {
            assertThat(CategoryRules.extractMerchantLabel(description, Transaction.Type.EXPENSE))
                    .as(description).isEqualTo(CategoryRules.extractMerchantLabel(description));
            assertThat(CategoryRules.extractMerchantLabel(description, null))
                    .as(description).isEqualTo(CategoryRules.extractMerchantLabel(description));
        }
        for (String description : new String[]{"CASHBACK EARNED", "UPI-SUNIL VERMA-REF9182736",
                "SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", "UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-INTEREST PAID"}) {
            assertThat(CategoryRules.extractMerchantLabel(description, Transaction.Type.INCOME))
                    .as(description).isEqualTo(CategoryRules.extractMerchantLabel(description));
        }
        assertThat(CategoryRules.extractMerchantLabel(null, Transaction.Type.INCOME))
                .isEqualTo(CategoryRules.extractMerchantLabel(null));
    }

    @Test
    void suggestCategory_matchesDiningKeyword() {
        assertThat(CategoryRules.suggestCategory("SWIGGY*ORDR9182 BLR")).isEqualTo("Dining");
    }

    @Test
    void suggestCategory_matchesTransportKeyword() {
        assertThat(CategoryRules.suggestCategory("UBER TRIP 19OCT")).isEqualTo("Transport");
    }

    @Test
    void suggestCategory_matchesTransferKeyword_forCreditCardPayments() {
        assertThat(CategoryRules.suggestCategory("CC PYMT AUTOPAY VISA")).isEqualTo("Transfer");
    }

    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "ASSPL" is how Amazon Seller Services actually appears on real Indian card statements
     * -- never the word "amazon" itself.
     */
    @Test
    void suggestCategory_matchesAsspl_amazonSellerServicesAbbreviation() {
        assertThat(CategoryRules.suggestCategory("ASSPL PAYTM 4471829")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: a BharatBillPay credit-card-bill narration abbreviated to "CC PAYMENT"
     * -- a near-miss of the already-seeded "credit card payment"/"card bill payment" phrases that
     * the existing CONTAINS-style keywords don't cover.
     */
    @Test
    void suggestCategory_matchesCcPayment_billerAbbreviation() {
        assertThat(CategoryRules.suggestCategory("BPPY CC PAYMENT REF882134")).isEqualTo("Transfer");
    }

    @Test
    void suggestCategory_matchesCinnabon() {
        assertThat(CategoryRules.suggestCategory("UPI-Cinnabon CP-REF7719923")).isEqualTo("Dining");
    }

    @Test
    void suggestCategory_fallsBackToOtherWhenNoRuleMatches() {
        assertThat(CategoryRules.suggestCategory("SOME RANDOM MERCHANT XYZ")).isEqualTo("Other");
    }

    @Test
    void suggestCategory_firstMatchingRuleWins() {
        // "credit card payment" contains no dining/shopping keywords, so it should hit
        // the Transfer rule specifically rather than falling through to Other.
        assertThat(CategoryRules.suggestCategory("credit card payment received")).isEqualTo("Transfer");
    }

    /**
     * Regression test for a substring-collision bug found during review: the Loan EMI rule
     * originally included a bare "emi" keyword, and contains()-based matching means that 3-letter
     * substring is also inside "premium" (p-r-EMI-um). An insurance premium payment would have
     * matched Loan EMI first — Loan EMI is earlier in RULES' insertion order than Insurance, and
     * suggestCategory returns on the first match — even though "lic premium" is a much more
     * specific and correct match sitting right there in the Insurance rule.
     */
    @Test
    void suggestCategory_insurancePremiumIsNotMisclassifiedAsLoanEmi() {
        assertThat(CategoryRules.suggestCategory("LIC PREMIUM PAYMENT ONLINE")).isEqualTo("Insurance");
    }

    /**
     * Same class of bug, different rule: Gifts & Donations originally included a bare "ngo"
     * keyword, which is also a substring of "mongo"/"flamingo"/"bingo"/"tango" — a MongoDB
     * hosting charge would have been misfiled as a donation.
     */
    @Test
    void suggestCategory_mongoDbChargeIsNotMisclassifiedAsGiftsAndDonations() {
        assertThat(CategoryRules.suggestCategory("MONGODB ATLAS CLOUD HOSTING")).isEqualTo("Other");
    }

    /** The compound phrases that replaced the bare "emi" keyword should still catch the real,
     *  common-case EMI deduction lines they were meant to cover. */
    @Test
    void suggestCategory_stillMatchesRealEmiDeductionLines() {
        assertThat(CategoryRules.suggestCategory("HDFC BANK LOAN EMI DEDUCTION")).isEqualTo("Loan EMI");
        assertThat(CategoryRules.suggestCategory("AUTO LOAN EMI PAYMENT NACH")).isEqualTo("Loan EMI");
    }

    /**
     * Regression test for another substring-collision bug found during a later review pass: the
     * Rent rule originally included a bare "rent" keyword, and contains()-based matching means
     * that 4-letter substring is also inside "current" -- a very common word on Indian bank
     * statements ("UPI-CURRENT A/C", "CURRENT ACCOUNT INT"). Fixed systemically by switching
     * suggestCategory's matching to word-boundary regex for every keyword, not just this one.
     */
    @Test
    void suggestCategory_currentAccountIsNotMisclassifiedAsRent() {
        assertThat(CategoryRules.suggestCategory("UPI-CURRENT ACCOUNT INT CREDIT")).isNotEqualTo("Rent");
    }

    /** The compound phrases that replaced the bare "rent" keyword should still catch the real,
     *  common-case rent payment lines they were meant to cover. */
    @Test
    void suggestCategory_stillMatchesRealRentPaymentLines() {
        assertThat(CategoryRules.suggestCategory("HOUSE RENT PAID TO LANDLORD")).isEqualTo("Rent");
        assertThat(CategoryRules.suggestCategory("MONTHLY RENT NEFT PAYMENT")).isEqualTo("Rent");
    }

    /**
     * Same class of bug, caught proactively while fixing the "rent"/"current" collision: the
     * Transport rule's bare "ola" keyword is also a substring of "cola" -- a Coca-Cola purchase
     * on a grocery or dining statement line would have misfired as a cab ride.
     */
    @Test
    void suggestCategory_cocaColaIsNotMisclassifiedAsTransport() {
        assertThat(CategoryRules.suggestCategory("COCA COLA PURCHASE DMART")).isNotEqualTo("Transport");
    }

    /** The word-boundary fix should still catch real Ola cab trips -- this isn't just about
     *  suppressing the false positive, the true positive has to keep working too. */
    @Test
    void suggestCategory_stillMatchesRealOlaCabTrips() {
        assertThat(CategoryRules.suggestCategory("OLA CAB RIDE TO AIRPORT")).isEqualTo("Transport");
    }

    /** Real narration from this project's own bank-statement corpus -- "NWD" (Non-Home-branch
     *  Withdrawal) was falling through every existing Cash Withdrawal keyword to "Other". */
    @Test
    void suggestCategory_matchesNwdAsCashWithdrawal() {
        assertThat(CategoryRules.suggestCategory("NWD-416021XXXXXX5853-14132291-HUZUR")).isEqualTo("Cash Withdrawal");
    }

    @Test
    void suggestCategory_stillMatchesRealInvestmentSips() {
        assertThat(CategoryRules.suggestCategory("UPI-GROWW INVEST TECH")).isEqualTo("Investments");
    }

    @Test
    void theUnspacedMutualFundsTokenMatches_whichTheSpacedKeywordCannotReach() {
        // normalize() replaces non-alphanumerics with spaces; it never splits a run-together word,
        // so "mutual fund" can never match "MUTUALFUNDS". Both spellings are needed, and this test
        // fails if either is removed.
        assertThat(CategoryRules.suggestCategory("SIP MUTUALFUNDS DEBIT")).isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("MUTUAL FUND PURCHASE")).isEqualTo("Investments");
    }

    @Test
    void gokhanaIsDining() {
        assertThat(CategoryRules.suggestCategory("UPI-GOKHANA-sample@ybl-REF19")).isEqualTo("Dining");
    }

    @Test
    void theNewKeywordsAreWordBoundedLikeEveryOther() {
        // Guards the same class of bug the word-boundary comment in CategoryRules describes: a new
        // keyword must not match inside a longer word.
        assertThat(CategoryRules.suggestCategory("GOKHANAPUR LAND TAX")).isEqualTo("Other");
    }

    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "Pureplay Skin Sciences" is a real D2C skincare/personal-care brand sold via
     * e-commerce, missing from the vocabulary the same way "asspl" and "cinnabon" were.
     */
    @Test
    void suggestCategory_matchesPureplay_skincareEcommerceBrand() {
        assertThat(CategoryRules.suggestCategory("UPI-PUREPLAY SKIN SCIENCES-REF881234")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: "PMJJBY" is the Government of India's Pradhan Mantri Jeevan Jyoti
     * Bima Yojana life-insurance scheme, appearing on real statements with a bank-specific
     * "JNS-" narration prefix.
     */
    @Test
    void suggestCategory_matchesPmjjby_governmentInsuranceScheme() {
        assertThat(CategoryRules.suggestCategory("JNS-PMJJBY PREMIUM DEDUCTION")).isEqualTo("Insurance");
    }

    /**
     * Real corpus finding: "PMSBY" is the same government's Pradhan Mantri Suraksha Bima Yojana
     * accident-insurance scheme, debited in the same "JNS-" narration shape as PMJJBY.
     */
    /** The payee field MerchantNormalizationEngine groups by: present when a structured narration
     *  names someone, null when it is not structured or names nobody. */
    @Test
    void structuredPayee_readsThePayeeFieldOnly() {
        assertThat(CategoryRules.structuredPayee("UPI/RRN 000000000001/Payment from PhonePe_ALICE")).isEqualTo("alice");
        assertThat(CategoryRules.structuredPayee("UPI-SUNIL VERMA-sampleuser@ybl-REF61")).isEqualTo("sunil verma");
        assertThat(CategoryRules.structuredPayee("SWIGGY BANGALORE")).isNull();
        assertThat(CategoryRules.structuredPayee("UPI/000000000001/00:41:30/UPI/q000000001@ybl/UPI")).isNull();
        assertThat(CategoryRules.structuredPayee(null)).isNull();
        assertThat(CategoryRules.structuredPayee("   ")).isNull();
    }

    @Test
    void isPaymentAppWord_namesAppsNotPayees() {
        assertThat(CategoryRules.isPaymentAppWord("ybl")).isTrue();
        assertThat(CategoryRules.isPaymentAppWord("razorpay")).isTrue();
        assertThat(CategoryRules.isPaymentAppWord("swiggy")).isFalse();
        assertThat(CategoryRules.isPaymentAppWord(null)).isFalse();
    }

    @Test
    void suggestCategory_matchesPmsby_governmentInsuranceScheme() {
        assertThat(CategoryRules.suggestCategory("JNS-PMSBY-26-27-00000000000-000_DAP")).isEqualTo("Insurance");
    }

    /**
     * Real corpus finding: "NSE MF" is the National Stock Exchange's mutual-fund investment
     * platform -- a real narration uses "MF" rather than the already-seeded "mutual fund"/
     * "mutualfunds" spellings.
     */
    @Test
    void suggestCategory_matchesNseMf_mutualFundPlatformAbbreviation() {
        assertThat(CategoryRules.suggestCategory("NET PAYIN TO NSE MF A/C 9182736")).isEqualTo("Investments");
    }

    /**
     * Real corpus finding (docs/superpowers/specs/2026-09-01-transaction-categorization-design.md
     * §1): "Housingcom Gurgaon" -- Housing.com printed as one contiguous word on the real
     * statement. Mapped to Rent on the assumption this is a rent-payment-facilitator narration
     * (Housing.com/NoBroker/CRED-RentPay-style products let a tenant pay a landlord through the
     * platform for a fee); confirm this category before relying on it (see Task 3's Context).
     */
    @Test
    void suggestCategory_matchesHousingcom_rentPaymentFacilitator() {
        assertThat(CategoryRules.suggestCategory("UPI-HOUSINGCOM GURGAON-REF773311")).isEqualTo("Rent");
    }

    /** Guards the same class of bug the file's other word-boundary tests describe (see
     *  theNewKeywordsAreWordBoundedLikeEveryOther): "housingcom" must not match inside a longer
     *  word it happens to be a prefix of. */
    @Test
    void suggestCategory_housingCommunityIsNotMisclassifiedAsRent() {
        assertThat(CategoryRules.suggestCategory("HOUSINGCOMMUNITY CENTRE FEE")).isNotEqualTo("Rent");
    }

    /**
     * Real corpus finding: "Tobox Ventures" is the registered corporate name behind "Gokhana"
     * (a real narration reads "TOBOX VENTURES PRIVATE LIMITED/GOKHANA."), appearing as the
     * merchant name on statements that print the corporate entity rather than the brand.
     */
    @Test
    void suggestCategory_matchesTobox_corporateNameBehindGokhana() {
        assertThat(CategoryRules.suggestCategory("UPI-TOBOX VENTURES-REF551209")).isEqualTo("Dining");
    }

    /** Some real statements truncate this narration to "TOBOX VENT" (a column-width truncation) --
     *  the keyword must still match on that shortened form, which is why "tobox" is kept as a bare
     *  single word rather than the two-word "tobox ventures". */
    @Test
    void suggestCategory_matchesTobox_evenWhenNarrationIsTruncated() {
        assertThat(CategoryRules.suggestCategory("UPI/TOBOX VENT/REF88213")).isEqualTo("Dining");
    }

    /** Word-boundary collision guard: "tobox" must not match as a prefix inside a longer,
     *  unrelated word. */
    @Test
    void suggestCategory_toboxicIsNotMisclassifiedAsDining() {
        assertThat(CategoryRules.suggestCategory("TOBOXIC LEATHERWORKS FEE")).isNotEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Indian Railways" is a distinct real narration form for the national
     * railway institution, naming it directly rather than through the "irctc" booking portal
     * already in this table.
     */
    @Test
    void suggestCategory_matchesIndianRailways_asDistinctFromIrctc() {
        assertThat(CategoryRules.suggestCategory("UPI-INDIAN RAILWAYS-REF662140")).isEqualTo("Transport");
    }

    /** Word-boundary/phrase collision guard: a bare "indian" would misfire on this real corpus
     *  narration ("INDIAN CLEARING CORP" settlement lines) -- kept as the full two-word phrase
     *  specifically to avoid it, the same choice already made for "nse mf" and "cc payment". */
    @Test
    void suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport() {
        assertThat(CategoryRules.suggestCategory("INDIAN CLEARING CORP SETTLEMENT")).isNotEqualTo("Transport");
    }

    /**
     * The clearing house's fund-transfer credits arrive wrapped mid-word: the bank's fixed-width
     * narration line ends after the "C" of "CLEARING", and the parser rejoins the two lines with a
     * space it has no evidence to remove. Without the split phrase these rows were "Other".
     */
    @Test
    void suggestCategory_indianClearingCorpSplitByALineWrapIsInvestments() {
        assertThat(CategoryRules.suggestCategory(
                "FT- 0000000000-00000000000000 - INDIAN C LEARING CORPORATION LIMITED -"))
                .isEqualTo("Investments");
    }

    /** The split phrase is word-boundary matched like every other keyword: a narration that only
     *  happens to contain the same letters run together, or "learing" without the leading "c",
     *  is not the clearing house. */
    @Test
    void suggestCategory_splitClearingPhraseNeedsTheWholeWrappedPhrase() {
        assertThat(CategoryRules.suggestCategory("UPI-INDIAN LEARING ACADEMY-REF991021")).isNotEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("UPI-INDIANC LEARINGTON STORES-REF991021")).isNotEqualTo("Investments");
    }

    /**
     * Real corpus finding (2026-09-14 mining pass against the current residual "Other" bucket):
     * "Chinese Factory" is a real Chinese-food restaurant name, appearing across 3 distinct
     * corpus documents.
     */
    @Test
    void suggestCategory_matchesChineseFactory_realRestaurant() {
        assertThat(CategoryRules.suggestCategory("UPI-THE CHINESE FACTORY-REF991021")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Cream House" is a real ice-cream/dessert parlor name, across 3
     * distinct documents.
     */
    @Test
    void suggestCategory_matchesCreamHouse_realDessertParlor() {
        assertThat(CategoryRules.suggestCategory("UPI-CREAM HOUSE-REF991022")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Lassi Wassi" is a real lassi/beverage shop name, across 3 distinct
     * documents.
     */
    @Test
    void suggestCategory_matchesLassiWassi_realBeverageShop() {
        assertThat(CategoryRules.suggestCategory("UPI-LASSI WASSI-PAYTM-REF991023")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Global Fashion" is a real clothing/apparel retailer name, across 3
     * distinct documents.
     */
    @Test
    void suggestCategory_matchesGlobalFashion_realApparelRetailer() {
        assertThat(CategoryRules.suggestCategory("UPI-GLOBAL FASHION OFFERS-REF991024")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: "Ekart" is Flipkart's own logistics/delivery arm -- flagged as a
     * candidate in the 2026-09-05 categorization-vocabulary-expansion plan's Task 1 and
     * explicitly deferred pending its own follow-up (this task).
     */
    @Test
    void suggestCategory_matchesEkart_flipkartLogisticsArm() {
        assertThat(CategoryRules.suggestCategory("UPI-EKART-EKART@YBL-REF991025")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding (2026-09-14 mining pass): "Kronos" (now part of UKG) is a real
     * workforce-management/payroll platform; narrations referencing it appear as NEFT CREDITS
     * (money received) across 2 distinct documents. Mapped to Salary on the inference that a
     * credit naming a payroll platform is a salary deposit; confirm this category before relying
     * on it (see Task 2's Context).
     */
    @Test
    void suggestCategory_matchesKronos_payrollPlatformCredit() {
        assertThat(CategoryRules.suggestCategory("NEFT CR HDFC0XXXXXX KRONOS REF991026")).isEqualTo("Salary");
    }

    // ---- Investments vocabulary, second pass (2026-09-21) ------------------------------------
    // Narrations below are synthetic in the shape the real corpus showed, never a real payee's text.

    /** The largest corpus finding: an ACH mandate debit whose only counterparty is the BSE clearing
     *  house that collects mutual-fund SIP instalments -- no "mutual fund" or "sip" word anywhere. */
    @Test
    void suggestCategory_indianClearingCorp_isInvestments() {
        assertThat(CategoryRules.suggestCategory("ACH D- INDIAN CLEARING CORP-ABCD1234")).isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("ACH/INDIAN CLEARING CORP/998877")).isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("UPI-INDIAN CLEARING CORPORATION LIMITED")).isEqualTo("Investments");
    }

    @Test
    void suggestCategory_brokerAndAmcNamesSeenOnTheCorpus_areInvestments() {
        assertThat(CategoryRules.suggestCategory("NEFT CR-XXXX0001-NEXTBILLION TECHNOLOGY PRIVATE LIMITED CLIENT ACCOUNT"))
                .isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("UPI/DR/123456/NSE ZEROD/HDFC/BRK@ZZVLD")).isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("BD-HSBC MF DEBIT CMP MANDATE DEBIT")).isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("RELIANCE NIPPON LIFE ASSET MANA DEBIT CMP MANDATE DEBIT"))
                .isEqualTo("Investments");
        assertThat(CategoryRules.suggestCategory("ACH C- NSDL FINDIV 12345-6")).isEqualTo("Investments");
    }

    @Test
    void suggestCategory_otherBrokerAndInvestmentAppBrands_areInvestments() {
        for (String narration : new String[] {
                "UPI-ANGEL ONE LIMITED-ANGELONE@ICICI", "UPI-5PAISA CAPITAL-5PAISA@YBL", "UPI-KUVERA-KUVERA@AXIS",
                "UPI-INDMONEY-INDMONEY@HDFCBANK", "UPI-SMALLCASE TECHNOLOGIES-SMALLCASE@YBL",
                "UPI-SHAREKHAN LIMITED-SHAREKHAN@ICICI", "UPI-PAYTM MONEY LIMITED-PAYTMMONEY@PTYES",
                "UPI-ETMONEY-ETMONEY@YBL", "UPI-ET MONEY-ETMONEY@YBL", "NEFT-MOTILAL OSWAL FINANCIAL SERVICES",
                "UPI-ICICI DIRECT-ICICIDIRECT@ICICI", "NEFT-HDFC SECURITIES LTD", "NEFT-ICICI SECURITIES LTD",
                "NEFT-KOTAK SECURITIES LTD" }) {
            assertThat(CategoryRules.suggestCategory(narration)).as(narration).isEqualTo("Investments");
        }
    }

    /**
     * The corpus reasons for NOT adding a short or generic keyword, pinned so widening the list
     * later has to confront them: each of these appeared as a real narration fragment and none is
     * an investment.
     */
    @Test
    void suggestCategory_aPersonalNameOrPlaceContainingAnInvestmentLookalike_staysOther() {
        assertThat(CategoryRules.suggestCategory("UPI-SUDHANSHU RAO-SUDHANSHU@YBL")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("UPI-VARDHAN TRADERS-VARDHAN@OKAXIS")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("UPI-MAHESH NAVI MUMBAI-MAHESH@YBL")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("UPI/DR/123456/A PERSON/IPO/Payment from phone")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("NSDL PAYMENTS BANK TOPUP")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("AXIS BK ATM CLEARING CHARGES")).isEqualTo("Other");
    }

    // Plan 4 (audit F-21 and the CBI "RRN" shape): a clock time and the literal RRN label name no
    // counterparty. Left in, they were the merchant -- "upi 02 44 32" on every Bank of Baroda UPI row,
    // "upi rrn upi ..." on every Central Bank one -- and the grouping key that pooled unrelated payees.

    @Test
    void extractMerchantLabel_aClockTimeIsNeverTheMerchant() {
        String label = CategoryRules.extractMerchantLabel("UPI/100000000001/02:44:32/UPI/samplestore@okaxis/UPI");
        assertThat(label).isNotNull().doesNotContainPattern("\\b\\d{2} \\d{2}\\b").contains("samplestore");
    }

    @Test
    void extractMerchantLabel_anRrnLabelWithNoPayee_isNoMerchant() {
        assertThat(CategoryRules.extractMerchantLabel("UPI/RRN 100000000001/UPI")).isNull();
    }

    @Test
    void extractMerchantLabel_anRrnLabelWithAPayee_isThePayee() {
        assertThat(CategoryRules.extractMerchantLabel("UPI/RRN 100000000001/UPI_SAMPLE PAYEE NAME"))
                .doesNotContain("rrn").contains("sample payee");
    }

    @Test
    void extractMerchant_aDescriptionWithNeitherShape_isUnchanged() {
        assertThat(CategoryRules.extractMerchant("SWIGGY*ORDR9182 BANGALORE")).isEqualTo("swiggy bangalore");
        assertThat(CategoryRules.extractMerchant("NEFT CR-SAMPLE PAYER-RENT FOR JULY")).isEqualTo("neft cr sample payer");
    }

    @Test
    void extractMerchant_aWordContainingRrnIsNotTheLabel() {
        assertThat(CategoryRules.extractMerchant("TERRNOVA SAMPLE STORE")).isEqualTo("terrnova sample store");
    }

    // --- a UPI handle names the payment app or bank, never the merchant ---

    @Test
    void suggestCategory_ignoresAKeywordThatIsTheWholeUpiHandle() {
        assertThat(CategoryRules.suggestCategory("UPI/900011112222/shopname@airtel/payment")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("UPI-SHOPNAME-shopname@JIO-ZZZZ0000000-900011112222")).isEqualTo("Other");
    }

    @Test
    void suggestCategory_stillMatchesTheKeywordOutsideTheHandle() {
        assertThat(CategoryRules.suggestCategory("UPI/AIRTEL/airtelbill@okzz/900011112222")).isEqualTo("Utilities");
        assertThat(CategoryRules.suggestCategory("JIO PREPAID RECHARGE")).isEqualTo("Utilities");
        // Payee AND handle both say airtel: the payee still counts.
        assertThat(CategoryRules.suggestCategory("UPI/AIRTEL/bill@airtel/900011112222")).isEqualTo("Utilities");
    }

    @Test
    void suggestCategory_handleEndsAtTheNextDelimiter() {
        assertThat(CategoryRules.suggestCategory("UPI/shopname@okzz/airtel recharge")).isEqualTo("Utilities");
        assertThat(CategoryRules.suggestCategory("UPI-shopname@okzz-ZEPTO")).isEqualTo("Groceries");
    }

    // --- a brand's UPI id, fused into one word and cut at fifteen characters by the bank ---
    // Shapes measured on a tester's savings statement (2026-10-03): the payee field is cut to eight
    // characters and the id before "@" to fifteen, so the brand's own words never stand apart.

    @Test
    void suggestCategory_aMetroIdFusedAndCutIsTransport() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112201/PUNE MET/HDFC/punemetroabcdef/")) // synthetic-ok
                .isEqualTo("Transport");
    }

    @Test
    void suggestCategory_aParkingAppIsTransport() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112202/PARKPLUS/HDFC/parkpl usxy.payu/")) // synthetic-ok
                .isEqualTo("Transport");
    }

    @Test
    void suggestCategory_aFuelPumpCutAtEightCharactersIsTransport() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112203/HP Petro/YESB/q900011112@ybl/S")) // synthetic-ok
                .isEqualTo("Transport");
    }

    @Test
    void suggestCategory_aTelecomsAutopayOrPrepaidIdIsUtilities() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112204/Www Airt/HDFC/airtelautopay.x/")) // synthetic-ok
                .isEqualTo("Utilities");
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112205/BHARTI A/AIRP/airtelprepaidXY/")) // synthetic-ok
                .isEqualTo("Utilities");
    }

    @Test
    void suggestCategory_aPaymentsAppBillPaymentIdIsUtilities() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112206/Google I/UTIB/gpay-utility@zz/")) // synthetic-ok
                .isEqualTo("Utilities");
    }

    @Test
    void suggestCategory_softwareAndCloudSubscriptionIdsAreSubscriptions() {
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112207/APPLE ME/HDFC/appleservices.x/")) // synthetic-ok
                .isEqualTo("Subscriptions");
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112208/Google W/UTIB/googleworkspace/")) // synthetic-ok
                .isEqualTo("Subscriptions");
        assertThat(CategoryRules.suggestCategory("UPI/DR/900011112209/AWS Indi/YESB/amazonaws@zzzz/A")) // synthetic-ok
                .isEqualTo("Subscriptions");
    }

    @Test
    void suggestCategory_theNewWordsNeedTheirOwnWordOrId() {
        // A person whose name merely begins like a brand, and a handle that is only the brand, stay Other.
        assertThat(CategoryRules.suggestCategory("UPI/PARKASH PLUSE/900011112210")).isEqualTo("Other"); // synthetic-ok
        assertThat(CategoryRules.suggestCategory("UPI/SHOPNAME/shopname@punemetro/900011112211")).isEqualTo("Other"); // synthetic-ok
        // A bare "utility" with no payments-app id is not a bill payment by itself.
        assertThat(CategoryRules.suggestCategory("UPI/UTILITY TRADERS/900011112212")).isEqualTo("Other"); // synthetic-ok
    }

    @Test
    void suggestCategory_nullAndBlankStillReturnOther() {
        assertThat(CategoryRules.suggestCategory(null)).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("@")).isEqualTo("Other");
        assertThat(CategoryRules.suggestCategory("")).isEqualTo("Other");
    }
}

