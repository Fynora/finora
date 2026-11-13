package com.finora.observability.jobs;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Whether a job has missed its schedule (design: "Overdue").
 *
 * <ul>
 *   <li>Interval job: overdue once {@code 2 x interval + 5 min} have passed since the later of its
 *       last start or finish and the app's start plus its initial delay. Counting from the app's
 *       start means a deploy never makes a job look late, and a run that started and never finished
 *       is on the same clock.</li>
 *   <li>Cron job: overdue 30 minutes after a fire time it did not start for, or 30 minutes after a
 *       start that never finished. A fire time before the app started is not counted: the app was not
 *       up to run it.</li>
 * </ul>
 *
 * <p>Cron times are read in the JVM's zone, as Spring's scheduler reads them (no job sets a zone).
 */
final class OverdueRule {

    static final Duration INTERVAL_SLACK = Duration.ofMinutes(5);
    static final Duration CRON_ALLOWANCE = Duration.ofMinutes(30);

    private OverdueRule() {}

    static JobState state(JobSchedule schedule, BackgroundJobStatus status, Instant appStartedAt, Instant now) {
        if (isOverdue(schedule, status, appStartedAt, now)) return JobState.OVERDUE;
        if (status == null || status.lastOutcome() == null) return JobState.OK;
        return JobState.valueOf(status.lastOutcome().name());
    }

    /** Started and not finished since. */
    static boolean isRunning(BackgroundJobStatus status) {
        if (status == null || status.lastStartedAt() == null) return false;
        return status.lastFinishedAt() == null || status.lastStartedAt().isAfter(status.lastFinishedAt());
    }

    static boolean isOverdue(JobSchedule schedule, BackgroundJobStatus status, Instant appStartedAt, Instant now) {
        return schedule.cron() != null
                ? cronOverdue(schedule.cron(), status, appStartedAt, now)
                : intervalOverdue(schedule, status, appStartedAt, now);
    }

    private static boolean intervalOverdue(JobSchedule schedule, BackgroundJobStatus status,
                                           Instant appStartedAt, Instant now) {
        Duration initialDelay = schedule.initialDelay() == null ? Duration.ZERO : schedule.initialDelay();
        Instant baseline = appStartedAt.plus(initialDelay);
        Instant lastActivity = latest(status == null ? null : status.lastStartedAt(),
                status == null ? null : status.lastFinishedAt());
        if (lastActivity != null && lastActivity.isAfter(baseline)) baseline = lastActivity;
        Instant limit = baseline.plus(schedule.interval().multipliedBy(2)).plus(INTERVAL_SLACK);
        return now.isAfter(limit);
    }

    private static boolean cronOverdue(String cron, BackgroundJobStatus status, Instant appStartedAt, Instant now) {
        if (isRunning(status)) {
            return now.isAfter(status.lastStartedAt().plus(CRON_ALLOWANCE));
        }
        Instant expected = mostRecentFire(cron, now);
        if (expected == null || expected.isBefore(appStartedAt)) return false;
        Instant lastStart = status == null ? null : status.lastStartedAt();
        if (lastStart != null && !lastStart.isBefore(expected)) return false;
        return now.isAfter(expected.plus(CRON_ALLOWANCE));
    }

    /** The latest time at or before {@code now} the cron fires, looking back up to 25 hours. */
    static Instant mostRecentFire(String cron, Instant now) {
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime cursor = now.minus(Duration.ofHours(25)).atZone(ZoneId.systemDefault());
        ZonedDateTime end = now.atZone(ZoneId.systemDefault());
        Instant last = null;
        for (ZonedDateTime next = expression.next(cursor); next != null && !next.isAfter(end); next = expression.next(next)) {
            last = next.toInstant();
        }
        return last;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
