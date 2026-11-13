package com.finora.observability.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BackgroundJobObservationHandlerTest {

    static class SomeSweep {
        public void scheduledSweep() {}
    }

    @AfterEach
    void clear() {
        BackgroundJobReport.end();
    }

    private static ScheduledTaskObservationContext context() throws NoSuchMethodException {
        Method method = SomeSweep.class.getMethod("scheduledSweep");
        return new ScheduledTaskObservationContext(new SomeSweep(), method);
    }

    @Test
    void aStoreThatThrows_neverReachesTheJob() throws Exception {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        doThrow(new IllegalStateException("database down")).when(store).started(anyString(), anyString(), any());
        doThrow(new IllegalStateException("database down")).when(store)
                .finished(anyString(), anyString(), any(), any(), anyInt(), anyInt(), any());
        BackgroundJobObservationHandler handler = new BackgroundJobObservationHandler(store, "instance-a");
        ScheduledTaskObservationContext ctx = context();

        assertThatCode(() -> {
            handler.onStart(ctx);
            BackgroundJobReport.itemFailed(new RuntimeException("x"));
            handler.onStop(ctx);
        }).doesNotThrowAnyException();
    }

    @Test
    void recordsTheReportedOutcomeUnderTheJobsKey() throws Exception {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobObservationHandler handler = new BackgroundJobObservationHandler(store, "instance-a");
        ScheduledTaskObservationContext ctx = context();

        handler.onStart(ctx);
        BackgroundJobReport.handled(10);
        BackgroundJobReport.itemFailed(new IllegalStateException("x"));
        handler.onStop(ctx);

        verify(store).started(eq("SomeSweep.scheduledSweep"), eq("instance-a"), any(Instant.class));
        verify(store).finished(eq("SomeSweep.scheduledSweep"), eq("instance-a"), any(Instant.class),
                eq(JobOutcome.PARTLY_FAILED), eq(1), eq(10), any(String.class));
    }

    @Test
    void anErrorOnTheContextIsACrash() throws Exception {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobObservationHandler handler = new BackgroundJobObservationHandler(store, "instance-a");
        ScheduledTaskObservationContext ctx = context();

        handler.onStart(ctx);
        ctx.setError(new IllegalArgumentException("boom"));
        handler.onStop(ctx);

        verify(store).finished(eq("SomeSweep.scheduledSweep"), eq("instance-a"), any(Instant.class),
                eq(JobOutcome.CRASHED), eq(0), eq(0), any(String.class));
    }

    @Test
    void aCleanRunStoresNoErrorSummary() throws Exception {
        BackgroundJobStore store = mock(BackgroundJobStore.class);
        BackgroundJobObservationHandler handler = new BackgroundJobObservationHandler(store, "instance-a");
        ScheduledTaskObservationContext ctx = context();

        handler.onStart(ctx);
        handler.onStop(ctx);

        verify(store).finished(eq("SomeSweep.scheduledSweep"), eq("instance-a"), any(Instant.class),
                eq(JobOutcome.OK), eq(0), eq(0), isNull());
    }

    @Test
    void supportsOnlyScheduledTaskContexts() throws Exception {
        BackgroundJobObservationHandler handler =
                new BackgroundJobObservationHandler(mock(BackgroundJobStore.class), "instance-a");
        assertThat(handler.supportsContext(context())).isTrue();
        assertThat(handler.supportsContext(new io.micrometer.observation.Observation.Context())).isFalse();
    }
}
