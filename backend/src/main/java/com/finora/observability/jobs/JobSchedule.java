package com.finora.observability.jobs;

import java.time.Duration;

/**
 * When a job is meant to run: exactly one of {@code interval} (fixed delay or rate, with its
 * {@code initialDelay}) or {@code cron} is set, except for a GitHub workflow started by a push,
 * which has neither. {@code description} is the schedule in words.
 *
 * <p>{@code source} says whose rules apply: a backend job's cron is read in the JVM's zone, a GitHub
 * workflow's in UTC with GitHub's own allowances (see {@link OverdueRule}). {@code offReason} marks an
 * entry shown as Off on purpose, whatever its stored row says.
 */
public record JobSchedule(String key, Duration interval, Duration initialDelay, String cron, String description,
                          Source source, String offReason) {

    public enum Source { BACKEND, GITHUB }
}
