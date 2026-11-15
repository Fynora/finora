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
    /**
     * How long after a scheduled workflow's UTC cron time GitHub may take to start it. Measured
     * 2026-11-15 over the last 60 scheduled runs of each daily workflow the page watches (e2e,
     * Maestro, gitleaks and image-scan nightlies; the private repository's traces and retention, 30
     * each: all they have) and the last 10 weekly CodeQL scans: GitHub started every one 3.9 to 8.7
     * hours late, median 6.4, none on time, no night without a run. The 3 hours this was before made
     * every nightly "needs attention" every morning and "working again" when it ran. 12 hours keeps a
     * margin of more than 3 hours over the latest start seen, and still says a nightly did not run
     * on the day it should have (by 15:00 UTC for a 03:00 cron). A run GitHub skips (the e2e and
     * Maestro nightlies skip their only job while the repository is private) is not counted by the
     * poller, so those nights are still Overdue: the check did not run.
     */
    static final Duration GITHUB_ALLOWANCE = Duration.ofHours(12);
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
            Instant owed = owedGitHubFire(schedule.cron(), status);
            return owed == null ? null : owed.plus(GITHUB_ALLOWANCE);
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
     * {@link #GITHUB_ALLOWANCE} (12 hours) after the first UTC fire time after its last run started,
     * and stays late until a run starts; one started by a push is never late. Counting from the first
     * fire it missed, not the latest one, keeps a workflow that stopped running Overdue: otherwise each
     * new fire would restart the clock and the row would read fine for 12 hours a day. No "before the
     * app started" exception: GitHub runs whether or not the backend is up. A workflow with no run read
     * yet is not late: right after a deploy every row is empty, and a broken token already shows on the
     * reader's own row, so eight "late" emails would only repeat it.
     */
    private static boolean githubOverdue(String cron, BackgroundJobStatus status, Instant now) {
        if (isRunning(status)) return now.isAfter(status.lastStartedAt().plus(GITHUB_RUNNING_LIMIT));
        Instant owed = owedGitHubFire(cron, status);
        return owed != null && now.isAfter(owed.plus(GITHUB_ALLOWANCE));
    }

    /**
     * The first fire of a GitHub 5-field cron (read in UTC) after the workflow's last run started: the
     * run GitHub owes it. Null when the workflow has no cron (push-started) or no run has been read
     * yet (just deployed, or the reader cannot reach GitHub, which its own row shows).
     */
    static Instant owedGitHubFire(String githubCron, BackgroundJobStatus status) {
        if (githubCron == null || status == null || status.lastStartedAt() == null) return null;
        ZonedDateTime next = CronExpression.parse("0 " + githubCron).next(status.lastStartedAt().atZone(UTC));
        return next == null ? null : next.toInstant();
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
