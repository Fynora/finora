package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantIdentityLookupTest {

    @Test
    void aNamedMerchantIsRecognised() {
        assertThat(MerchantIdentityLookup.namesKnownMerchant("UPI-AMAZON SELLER-REF41")).isTrue();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SWIGGY ORDER REF42")).isTrue();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("NETFLIX SUBSCRIPTION")).isTrue();
    }

    @Test
    void aMechanismOrPurposeWordIsNotAMerchant() {
        // The bug this guard exists to prevent: a salary credit has no merchant on the other side,
        // and typing one as a business would be a new error introduced by the fix, not a fix.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SALARY CREDIT JUL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("ATM WITHDRAWAL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("EMI PAYMENT DEDUCTION")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("MUTUAL FUND PURCHASE")).isFalse();
    }

    @Test
    void aGenericTradeNounIsNotAnIdentityEitherEvenThoughItImpliesABusiness() {
        // "restaurant" does mean a business, but it is not an entity. It is already covered by
        // PersonToPersonTransferDetector.hasBusinessToken; letting it in here would quietly turn
        // this class into a second business-token vocabulary, which is the duplication the seam
        // exists to avoid.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("SOME RESTAURANT BILL")).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("LOCAL PHARMACY")).isFalse();
    }

    @Test
    void aStoreWhoseLettersBeginPeoplesUpiIds_isNotReadFromAPersonsId() {
        // "MR DIY" as a UPI-id prefix is "mrdiy" -- the start of "mr.diya@..." and "mrdiyanshu@...".
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("mr.diya")).isFalse();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("mrdiyanshu")).isFalse();
        assertThat(CounterpartyClassifier.classify("UPI/DR/100000000001/DIYA SHARMA/HDFC/mr.diya@okhdfc/"))
                .isEqualTo(CounterpartyType.PERSON);
        // The store still files under Shopping, and its own payments carry a merchant-UPI marker.
        assertThat(CategoryRules.suggestCategory("UPI-MR DIY-mrdiy.00000001@hdfcbank-XXXX0MERUPI-100000000001-UPI")) // synthetic-ok
                .isEqualTo("Shopping");
        assertThat(CounterpartyClassifier.classify("UPI-MR DIY-mrdiy.00000001@hdfcbank-XXXX0MERUPI-100000000001-UPI")) // synthetic-ok
                .isEqualTo(CounterpartyType.BUSINESS);
    }

    @Test
    void everyExcludedTermStillExistsUpstream_soARenameCannotLeaveAStaleExclusion() {
        // The entity set is "everything in CategoryRules minus these". If a keyword upstream is
        // renamed or dropped, the matching exclusion here becomes dead -- and worse, silently
        // widens what counts as a merchant identity. This is the only thing that catches that.
        assertThat(CategoryRules.allKeywords())
                .as("stale exclusions in MerchantIdentityLookup.NON_ENTITY_TERMS")
                .containsAll(MerchantIdentityLookup.NON_ENTITY_TERMS);
    }

    @Test
    void theEntitySetIsDerivedFromTheOneVocabulary_notCopiedFromIt() {
        // Coupling test, same intent as the marker-set one on CounterpartyClassifierTest: a second
        // copy of the merchant names would drift the moment either side gained a brand.
        assertThat(MerchantIdentityLookup.knownEntityTerms())
                .isSubsetOf(CategoryRules.allKeywords())
                .contains("amazon", "swiggy", "zerodha")
                .doesNotContain("salary", "atm withdrawal");
    }

    @Test
    void matchingIsWordBounded() {
        // CategoryRules documents "ola" matching inside "cola" as a real false positive. The same
        // discipline has to hold here or this becomes a new source of it.
        assertThat(MerchantIdentityLookup.namesKnownMerchant("COLA AND SNACKS")).isFalse();
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(MerchantIdentityLookup.namesKnownMerchant(null)).isFalse();
        assertThat(MerchantIdentityLookup.namesKnownMerchant("")).isFalse();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant(null)).isFalse();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("")).isFalse();
    }

    @Test
    void aUpiIdThatBeginsWithAKnownBrandNamesThatBrand() {
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("airtelprepaidxy")).isTrue();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("punemetroabcde")).isTrue();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("apple.services.x")).isTrue();
        // A prefix only: a brand somewhere inside an id is not the payee's name.
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("ravi.airtel")).isFalse();
        // Too short to tell from the start of a name ("ola", "uber").
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("olafsen12")).isFalse();
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("uberto.x")).isFalse();
        // A mechanism word is not an identity, here as in the narration.
        assertThat(MerchantIdentityLookup.handleNamesKnownMerchant("salaryacct1")).isFalse();
    }
}
