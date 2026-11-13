package com.finora.observability.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BackgroundJobReportTest {

    @AfterEach
    void clear() {
        BackgroundJobReport.end();
    }

    @Test
    void outcomeOrder_crashBeatsFailedItemsBeatsOffBeatsOk() {
        assertThat(BackgroundJobReport.outcomeOf(true, 3, true)).isEqualTo(JobOutcome.CRASHED);
        assertThat(BackgroundJobReport.outcomeOf(false, 3, true)).isEqualTo(JobOutcome.PARTLY_FAILED);
        assertThat(BackgroundJobReport.outcomeOf(false, 0, true)).isEqualTo(JobOutcome.OFF);
        assertThat(BackgroundJobReport.outcomeOf(false, 0, false)).isEqualTo(JobOutcome.OK);
    }

    @Test
    void aRunCollectsFailedItemsHandledCountAndOff() {
        BackgroundJobReport.begin();
        IllegalStateException first = new IllegalStateException("first");
        BackgroundJobReport.itemFailed(first);
        BackgroundJobReport.itemFailed(new IllegalArgumentException("second"));
        BackgroundJobReport.handled(10);
        BackgroundJobReport.switchedOff();

        BackgroundJobReport.Run run = BackgroundJobReport.end();

        assertThat(run.failedItems()).isEqualTo(2);
        assertThat(run.firstError()).isSameAs(first);
        assertThat(run.handled()).isEqualTo(10);
        assertThat(run.switchedOff()).isTrue();
    }

    @Test
    void callsOutsideARun_doNothing() {
        assertThatCode(() -> {
            BackgroundJobReport.itemFailed(new RuntimeException("x"));
            BackgroundJobReport.handled(4);
            BackgroundJobReport.switchedOff();
        }).doesNotThrowAnyException();

        BackgroundJobReport.begin();
        BackgroundJobReport.Run run = BackgroundJobReport.end();
        assertThat(run.failedItems()).isZero();
        assertThat(run.handled()).isZero();
        assertThat(run.switchedOff()).isFalse();
    }

    @Test
    void stateDoesNotLeakIntoTheNextRunOnTheSameThread() {
        BackgroundJobReport.begin();
        BackgroundJobReport.itemFailed(new RuntimeException("x"));
        BackgroundJobReport.switchedOff();
        BackgroundJobReport.end();

        BackgroundJobReport.begin();
        BackgroundJobReport.Run second = BackgroundJobReport.end();

        assertThat(second.failedItems()).isZero();
        assertThat(second.firstError()).isNull();
        assertThat(second.switchedOff()).isFalse();
    }

    @Test
    void endWithNoRun_returnsAnEmptyRun() {
        BackgroundJobReport.Run run = BackgroundJobReport.end();
        assertThat(run.failedItems()).isZero();
    }

    @Test
    void anItemFailedWithNoCauseStillCounts() {
        BackgroundJobReport.begin();
        BackgroundJobReport.itemFailed(null);
        assertThat(BackgroundJobReport.end().failedItems()).isEqualTo(1);
    }
}
