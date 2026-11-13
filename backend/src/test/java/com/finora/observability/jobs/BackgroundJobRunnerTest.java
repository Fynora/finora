package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** A job that hands its work to another thread is recorded where the work runs, not at the hand-off. */
class BackgroundJobRunnerTest {

    static class HandsOff {
        @RecordedWhereItRuns
        public void scheduled() {}
    }

    @Test
    void theHandOffItselfIsNotRecorded() throws Exception {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobObservationHandler handler = new BackgroundJobObservationHandler(store, "i");
        ScheduledTaskObservationContext ctx =
                new ScheduledTaskObservationContext(new HandsOff(), HandsOff.class.getMethod("scheduled"));

        handler.onStart(ctx);
        handler.onStop(ctx);

        verifyNoInteractions(store);
    }

    @Test
    void theWorkIsRecordedUnderTheJobsKey_withItsReport() {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobRunner runner = new BackgroundJobRunner(store, "i");

        runner.recorded("FixJobFollowUp.scheduled", () -> {
            BackgroundJobReport.handled(4);
            BackgroundJobReport.itemFailed(new IllegalStateException("x"));
        }).run();

        verify(store).started(eq("FixJobFollowUp.scheduled"), eq("i"), any(Instant.class));
        verify(store).finished(eq("FixJobFollowUp.scheduled"), eq("i"), any(Instant.class),
                eq(JobOutcome.PARTLY_FAILED), eq(1), eq(4), startsWith("IllegalStateException at BackgroundJobRunnerTest:"));
    }

    @Test
    void aCrashIsRecordedAndStillThrown() {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobRunner runner = new BackgroundJobRunner(store, "i");

        assertThatThrownBy(() -> runner.recorded("A.b", () -> { throw new IllegalArgumentException("boom"); }).run())
                .isInstanceOf(IllegalArgumentException.class);

        verify(store).finished(eq("A.b"), eq("i"), any(Instant.class), eq(JobOutcome.CRASHED), eq(0), eq(0),
                startsWith("IllegalArgumentException"));
    }

    @Test
    void aCleanRunIsOk_andTheReportDoesNotLeakToTheThread() {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobRunner runner = new BackgroundJobRunner(store, "i");

        runner.recorded("A.b", () -> {}).run();

        verify(store).finished(eq("A.b"), eq("i"), any(Instant.class), eq(JobOutcome.OK), eq(0), eq(0), isNull());
        assertThat(BackgroundJobReport.end().failedItems()).isZero();
    }
}
