package com.finora.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which rows share a remembered category. Every narration is invented. */
class NarrationCategoryMemoryTest {

    @Test
    void aNachLineThatPrintsOnlyItsMandateKeepsOneKeyMonthAfterMonth() {
        assertThat(NarrationCategoryMemory.keyFor("NACH CHQ 004471"))
                .isNotNull()
                .isEqualTo(NarrationCategoryMemory.keyFor("NACH CHQ 004471"))
                .isNotEqualTo(NarrationCategoryMemory.keyFor("NACH CHQ 004472"));
    }

    @Test
    void twoBareUpiReferencesNeverShareAKey_soUnrelatedPayeesAreNotPooled() {
        assertThat(NarrationCategoryMemory.keyFor("UPI/RRN 100000000001/UPI"))
                .isNotEqualTo(NarrationCategoryMemory.keyFor("UPI/RRN 100000000002/UPI"));
    }

    @Test
    void withdrawalsAtOnePlaceShareAKey_whateverTheirReference() {
        assertThat(NarrationCategoryMemory.keyFor("ATM WDL 100000000001 SAMPLETOWN"))
                .isEqualTo(NarrationCategoryMemory.keyFor("ATM WDL 100000000002 SAMPLETOWN"))
                .isNotEqualTo(NarrationCategoryMemory.keyFor("ATM WDL 100000000003 OTHERTOWN"));
    }

    @Test
    void aRowThatNamesAPayeeHasNoKey_itsMerchantRemembersInstead() {
        assertThat(NarrationCategoryMemory.keyFor("UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc")).isNull();
        assertThat(NarrationCategoryMemory.keyFor("ATM ELECTRONICS STORE")).isNull();
        assertThat(NarrationCategoryMemory.keyFor(null)).isNull();
    }

    @Test
    void theKeyIsAHash_neverTheNarration() {
        assertThat(NarrationCategoryMemory.keyFor("NACH CHQ 004471")).matches("[0-9a-f]{64}");
    }
}
