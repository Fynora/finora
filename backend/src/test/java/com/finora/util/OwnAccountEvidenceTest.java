package com.finora.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OwnAccountEvidenceTest {

    private static final List<List<String>> ASHA = OwnAccountEvidence.ownerNames(List.of("MRS ASHA VERMA"));

    // ---- references ----

    @Test void referencesAreTwelveDigitRunsEvenWhenGluedToLetters() {
        assertThat(OwnAccountEvidence.references("UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI"))
                .containsExactly("111111111111");
        assertThat(OwnAccountEvidence.references("SentIMPS111111111111ASHA VERMA/IFSC0000001/IMPS"))
                .containsExactly("111111111111");
        assertThat(OwnAccountEvidence.references("UPI/CR/C111111111111/ ASHA VERMA/ ptye/x@ptyes/NA/"))
                .containsExactly("111111111111");
    }

    @Test void phoneCardAndLongAccountNumbersAreNotReferences() {
        assertThat(OwnAccountEvidence.references("UPI-ASHA VERMA-9999999999@ybl")).isEmpty();          // 10 digits
        assertThat(OwnAccountEvidence.references("CARD 4111111111111111 PURCHASE")).isEmpty();          // 16 digits
        assertThat(OwnAccountEvidence.references("NEFT TO 1111111111111 ASHA")).isEmpty();             // 13 digits
        assertThat(OwnAccountEvidence.references(null)).isEmpty();
    }

    // ---- slots ----

    @Test void readsTheSlotOfEveryObservedShape() {
        assertThat(OwnAccountEvidence.counterpartySlot("UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("BANKCO LIMITED UPI-MR ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI")).contains("MR ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/CR/111111111111/ASHA VERMA/BANK/asha@okbank/")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/DR/111111111111/ASHA VERMA/BANK/asha@okbank/")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPIAB/111111111111/CR/ASHA VERMA/BANK/asha@okbank")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPIAR/111111111111/DR/ASHA VERMA/BANK/asha@okbank")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/RRN 111111111111/UPI_ASHA VERMA ASHA VER")).contains("ASHA VERMA ASHA VER");
        assertThat(OwnAccountEvidence.counterpartySlot("MOB-IMPS-CR/ASHA VER/BANK /111111/IMPS/222/")).contains("ASHA VER");
        assertThat(OwnAccountEvidence.counterpartySlot("SentIMPS111111111111Asha Verma/IFSC0000001/IMPS")).contains("Asha Verma");
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT CR-IFSC0000001-ASHA VERMA S O SH R VERMA-ASHA VERMA-REF1")).contains("ASHA VERMA S O SH R VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT*IFSC0000001*REF1 2*ASHA VERMA S 1111111 AT BRANCH")).contains("ASHA VERMA S 1111111 AT BRANCH");
    }

    @Test void neverReadsTheNeftBeneficiarySlot() {
        // Salary: the remitter is the employer, the beneficiary is the account holder.
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT CR-IFSC0000001-EMPLOYERCO PVT LTD-ASHA VERMA-REF1 SALARY FOR JUL"))
                .contains("EMPLOYERCO PVT LTD");
    }

    @Test void freeTextHasNoSlot() {
        assertThat(OwnAccountEvidence.counterpartySlot("IGST DB @ 18.00% TRANSACTIONS FOR ASHA VERMA")).isEmpty();
        assertThat(OwnAccountEvidence.counterpartySlot(null)).isEmpty();
    }

    // ---- owner names ----

    @Test void ownerNamesDropTitlesInitialsAndOneWordNames() {
        assertThat(OwnAccountEvidence.ownerNames(List.of("MRS ASHA K VERMA", "ASHA", " ", "Asha Verma")))
                .containsExactly(List.of("asha", "verma"));
    }

    // ---- matching ----

    @Test void matchesTheOwnerIncludingTruncationTitlesAndRepetition() {
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("MR ASHA VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VER", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner(" ASHA VERM", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA ASHA VER", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA S O SH R VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA S 1111111 AT BRANCH", ASHA)).isTrue();
    }

    @Test void anySpellingOfTheOwnerCounts() {
        List<List<String>> owner = OwnAccountEvidence.ownerNames(List.of("ASHA RAMCHANDRA VERMA", "ASHA RAM VARMA"));
        assertThat(OwnAccountEvidence.namesOwner("ASHA RAM VARM", owner)).isTrue();
    }

    @Test void aSingleWordOrADifferentPersonIsNotTheOwner() {
        assertThat(OwnAccountEvidence.namesOwner("Asha", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("ASHA PATEL", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("EMPLOYERCO PVT LTD", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner(null, ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA", List.of())).isFalse();
    }

    @Test void aLongerNameThatStartsWithTheOwnersIsSomeoneElse() {
        List<List<String>> ravi = OwnAccountEvidence.ownerNames(List.of("RAVI KUMAR"));
        assertThat(OwnAccountEvidence.namesOwner("RAVI KUMAR SINGH", ravi)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("RAVI KUMAR RAVI KUM", ravi)).isTrue();
    }
}
