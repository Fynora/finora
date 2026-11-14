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
    /** GitHub starts scheduled runs late under load, sometimes by an hour or more. */
    static final Duration GITHUB_ALLOWANCE = Duration.ofHours(3);
    /** GitHub's own limit for one job; a run going longer than this is stuck. */
    static final Duration GITHUB_RUNNING_LIMIT = Duration.ofHours(6);
    private static final ZoneId UTC = ZoneId.of("UTC");

    private OverdueRule() {}

    static JobState state(JobSchedule schedule, BackgroundJobStatus status, Instant appStartedAt, Instant now) {
        if (schedule.offReason() != null) return JobState.OFF;
        if (isOverdue(schedule, status, appStartedAt, now)) return JobState.OVERDUE;
        if (status == null || status.lastOutcome() == null) return JobState.OK;
        return JobState.valueOf(status.lastOutcome().name());
    }

    /** Started and not finished since. */
    static boolean isRunning(BackgroundJobStatus status) {
        if (status == null || status.lastStartedAt() == null) return false;
        return status.lastFinishedAt() == null || status.lastStartedAt().isAfter(status.lastFinishedAt());
    }

    /** When the job becomes (or became) overdue on its current clock; for the "broken since" line. */
    static Instant overdueAt(JobSchedule schedule, BackgroundJobStatus status, Instant appStartedAt) {
        if (schedule.source() == JobSchedule.Source.GITHUB) {
            if (isRunning(status)) return status.lastStartedAt().plus(GITHUB_RUNNING_LIMIT);
            if (schedule.cron() == null) return null;
            Instant expected = mostRecentGitHubFire(schedule.cron(), Instant.now());
            return expected == null ? null : expected.plus(GITHUB_ALLOWANCE);
        }
        if (schedule.cron() != null) {
            if (isRunning(status)) return status.lastStartedAt().plus(CRON_ALLOWANCE);
            Instant expected = mostRecentFire(schedule.cron(), Instant.now());
            return expected == null ? null : expected.plus(CRON_ALLOWANCE);
        }
        Duration initialDelay = schedule.initialDelay() == null ? Duration.ZERO : schedule.initialDelay();
        Instant baseline = appStartedAt.plus(initialDelay);
        Instant lastActivity = latest(status == null ? null : status.lastStartedAt(),
                status == null ? null : status.lastFinishedAt());
        if (lastActivity != null && lastActivity.isAfter(baseline)) baseline = lastActivity;
        return baseline.plus(schedule.interval().multipliedBy(2)).plus(INTERVAL_SLACK);
    }

    static boolean isOverdue(JobSchedule schedule, BackgroundJobStatus status, Instant appStartedAt, Instant now) {
        if (schedule.source() == JobSchedule.Source.GITHUB) return githubOverdue(schedule.cron(), status, now);
        return schedule.cron() != null
                ? cronOverdue(schedule.cron(), status, appStartedAt, now)
                : intervalOverdue(schedule, status, appStartedAt, now);
    }

    private static boolean intervalOverdue(JobSchedule schedule, BackgroundJobStatus status,
                                           Instant appStartedAt, Instant now) {
        return now.isAfter(overdueAt(schedule, status, appStartedAt));
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

    /**
     * GitHub workflows: a run in progress for more than 6 hours is stuck; a scheduled workflow is late
     * 3 hours after a UTC fire time with no run started at or after it; one started by a push is never
     * late. No "before the app started" exception: GitHub runs whether or not the backend is up. A
     * workflow with no run read yet is not late: right after a deploy every row is empty, and a broken
     * token already shows on the reader's own row, so eight "late" emails would only repeat it.
     */
    private static boolean githubOverdue(String cron, BackgroundJobStatus status, Instant now) {
        if (isRunning(status)) return now.isAfter(status.lastStartedAt().plus(GITHUB_RUNNING_LIMIT));
        // No run read yet: just deployed, or the reader cannot reach GitHub, which its own row shows.
        if (cron == null || status == null || status.lastStartedAt() == null) return false;
        Instant expected = mostRecentGitHubFire(cron, now);
        if (expected == null) return false;
        Instant lastStart = status == null ? null : status.lastStartedAt();
        if (lastStart != null && !lastStart.isBefore(expected)) return false;
        return now.isAfter(expected.plus(GITHUB_ALLOWANCE));
    }

    /** A GitHub 5-field cron's latest fire at or before {@code now}, in UTC, looking back 8 days (weekly runs). */
    static Instant mostRecentGitHubFire(String githubCron, Instant now) {
        return mostRecentFire("0 " + githubCron, now, UTC, Duration.ofDays(8));
    }

    /** The latest time at or before {@code now} the cron fires, looking back up to 25 hours. */
    static Instant mostRecentFire(String cron, Instant now) {
        return mostRecentFire(cron, now, ZoneId.systemDefault(), Duration.ofHours(25));
    }

    private static Instant mostRecentFire(String cron, Instant now, ZoneId zone, Duration lookBack) {
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime cursor = now.minus(lookBack).atZone(zone);
        ZonedDateTime end = now.atZone(zone);
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
