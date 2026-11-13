package com.finora.observability.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Instant;

/**
 * Records one run of a background job: opens its {@link BackgroundJobReport}, and on the way out
 * stores the outcome. Used by {@link BackgroundJobObservationHandler} for every scheduled run, and
 * directly, through {@link #recorded}, by a job whose work runs on another thread
 * ({@link RecordedWhereItRuns}).
 *
 * <p>Recording never breaks, slows or changes the job: every store call is caught and logged.
 */
@Component
public class BackgroundJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobRunner.class);

    private final BackgroundJobStore store;
    private final String instance;

    @Autowired
    public BackgroundJobRunner(BackgroundJobStore store) {
        this(store, instanceName());
    }

    BackgroundJobRunner(BackgroundJobStore store, String instance) {
        this.store = store;
        this.instance = instance;
    }

    /** {@code work}, recorded as a run of the job {@code key} on whichever thread runs it. */
    public Runnable recorded(String key, Runnable work) {
        return () -> {
            start(key);
            Throwable crash = null;
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                crash = e;
                throw e;
            } finally {
                finish(key, crash);
            }
        };
    }

    void start(String key) {
        BackgroundJobReport.begin();
        try {
            store.started(key, instance, Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not record the start of background job {}", key, e);
        }
    }

    void finish(String key, Throwable crash) {
        BackgroundJobReport.Run run = BackgroundJobReport.end();
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
