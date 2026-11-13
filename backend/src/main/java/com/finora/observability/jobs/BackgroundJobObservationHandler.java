package com.finora.observability.jobs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

/**
 * Records every run of every {@code @Scheduled} method, with no change to the jobs themselves.
 *
 * <p>Spring Boot wraps each scheduled run in a Micrometer observation
 * ({@code ScheduledTasksObservabilityAutoConfiguration}); this handler receives its start and stop on
 * the scheduler thread that runs the job, and records the run through {@link BackgroundJobRunner}: an
 * error on the observation is a crash; otherwise the job's own {@link BackgroundJobReport} decides.
 * A method marked {@link RecordedWhereItRuns} is skipped: it records its work where the work runs.
 */
@Component
public class BackgroundJobObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private final BackgroundJobRunner runner;

    @Autowired
    public BackgroundJobObservationHandler(BackgroundJobRunner runner) {
        this.runner = runner;
    }

    BackgroundJobObservationHandler(BackgroundJobStore store, String instance) {
        this(new BackgroundJobRunner(store, instance));
    }

    /** The job's key: {@code SimpleClassName.methodName}, as {@link BackgroundJobCatalog} lists it. */
    static String keyOf(ScheduledTaskObservationContext context) {
        return context.getTargetClass().getSimpleName() + "." + context.getMethod().getName();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        if (handsOff(context)) return;
        runner.start(keyOf(context));
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        if (handsOff(context)) return;
        runner.finish(keyOf(context), context.getError());
    }

    private static boolean handsOff(ScheduledTaskObservationContext context) {
        return context.getMethod().isAnnotationPresent(RecordedWhereItRuns.class);
    }
}
