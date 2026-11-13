package com.finora.observability.jobs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;

/**
 * Records every run of every {@code @Scheduled} method, with no change to the jobs themselves.
 *
 * <p>Spring Boot wraps each scheduled run in a Micrometer observation
 * ({@code ScheduledTasksObservabilityAutoConfiguration}); this handler receives its start and stop on
 * the scheduler thread that runs the job. It opens a {@link BackgroundJobReport} for the job to
 * report into, and on stop records the outcome: an error on the observation is a crash; otherwise the
 * job's own report decides.
 *
 * <p>Recording must never break, slow or change a job: every store call is caught and logged.
 */
@Component
public class BackgroundJobObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobObservationHandler.class);

    private final BackgroundJobStore store;
    private final String instance;

    @Autowired
    public BackgroundJobObservationHandler(BackgroundJobStore store) {
        this(store, instanceName());
    }

    BackgroundJobObservationHandler(BackgroundJobStore store, String instance) {
        this.store = store;
        this.instance = instance;
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
        BackgroundJobReport.begin();
        String key = keyOf(context);
        try {
            store.started(key, instance, Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not record the start of background job {}", key, e);
        }
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        BackgroundJobReport.Run run = BackgroundJobReport.end();
        String key = keyOf(context);
        Throwable crash = context.getError();
        JobOutcome outcome = BackgroundJobReport.outcomeOf(crash != null, run.failedItems(), run.switchedOff());
        Throwable shown = crash != null ? crash : run.firstError();
        try {
            store.finished(key, instance, Instant.now(), outcome, run.failedItems(), run.handled(),
                    ErrorSummary.of(shown));
        } catch (RuntimeException e) {
            log.warn("Could not record the end of background job {} ({})", key, outcome, e);
        }
    }

    private static String instanceName() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) return host;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
