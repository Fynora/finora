package com.finora.activation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate 2a spec §3: each user's furthest step is the highest one whose fact holds. */
class FunnelStepsTest {

    private static FunnelSteps.UserFacts facts(boolean openedImport, boolean choseFile, boolean refused,
                                               boolean readFailed, boolean held, boolean read,
                                               boolean reviewOpened, boolean imported) {
        return new FunnelSteps.UserFacts(openedImport, choseFile, refused, readFailed, held, read, reviewOpened, imported);
    }

    @Test
    void noFactsIsSignedUp() {
        assertThat(FunnelSteps.furthest(facts(false, false, false, false, false, false, false, false)))
                .isEqualTo(FunnelStep.SIGNED_UP);
    }

    @Test
    void importedWinsOverEverything() {
        assertThat(FunnelSteps.furthest(facts(true, true, true, true, true, true, true, true)))
                .isEqualTo(FunnelStep.GOT_DATA_IN);
        assertThat(FunnelSteps.furthest(facts(false, false, false, false, false, false, false, true)))
                .as("data in without any recorded earlier step").isEqualTo(FunnelStep.GOT_DATA_IN);
    }

    @Test
    void heldRanksAboveReadFailed() {
        assertThat(FunnelSteps.furthest(facts(true, true, false, true, true, false, false, false)))
                .isEqualTo(FunnelStep.HELD);
    }

    @Test
    void reviewOpenedRanksAboveRead() {
        assertThat(FunnelSteps.furthest(facts(true, true, false, false, false, true, true, false)))
                .isEqualTo(FunnelStep.REVIEWED_NOT_CONFIRMED);
    }

    @Test
    void eachFactAloneLandsOnItsOwnStep() {
        assertThat(FunnelSteps.furthest(facts(true, false, false, false, false, false, false, false))).isEqualTo(FunnelStep.OPENED_IMPORT);
        assertThat(FunnelSteps.furthest(facts(false, true, false, false, false, false, false, false))).isEqualTo(FunnelStep.CHOSE_FILE);
        assertThat(FunnelSteps.furthest(facts(false, false, true, false, false, false, false, false))).isEqualTo(FunnelStep.REFUSED);
        assertThat(FunnelSteps.furthest(facts(false, false, false, true, false, false, false, false))).isEqualTo(FunnelStep.READ_FAILED);
        assertThat(FunnelSteps.furthest(facts(false, false, false, false, true, false, false, false))).isEqualTo(FunnelStep.HELD);
        assertThat(FunnelSteps.furthest(facts(false, false, false, false, false, true, false, false))).isEqualTo(FunnelStep.READ_NOT_REVIEWED);
        assertThat(FunnelSteps.furthest(facts(false, false, false, false, false, false, true, false))).isEqualTo(FunnelStep.REVIEWED_NOT_CONFIRMED);
    }

    /** The declaration order is the rank: the page shows the columns in it, left to right. */
    @Test
    void declarationOrderIsTheSpecOrder() {
        assertThat(FunnelStep.values()).containsExactly(
                FunnelStep.SIGNED_UP, FunnelStep.OPENED_IMPORT, FunnelStep.CHOSE_FILE, FunnelStep.REFUSED,
                FunnelStep.READ_FAILED, FunnelStep.HELD, FunnelStep.READ_NOT_REVIEWED,
                FunnelStep.REVIEWED_NOT_CONFIRMED, FunnelStep.GOT_DATA_IN);
    }
}
