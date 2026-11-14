package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interval jobs: overdue {@code 2 x interval + 5 min} after the later of their last activity and the
 * app's start plus initial delay. Cron jobs: overdue 30 min after a fire time they did not start for.
 */
class OverdueRuleTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Instant APP_START = Instant.parse("2026-11-13T00:00:00Z");
    private static final JobSchedule EVERY_30S =
            new JobSchedule("A.b", Duration.ofSeconds(30), Duration.ZERO, null, "every 30 seconds", JobSchedule.Source.BACKEND, null);
    /** 2 x 30 s + 5 min. */
    private static final Duration ALLOWANCE = Duration.ofSeconds(60).plusMinutes(5);
    private static final JobSchedule DAILY_0345 = new JobSchedule("C.d", null, null, "0 45 3 * * *", "daily at 03:45", JobSchedule.Source.BACKEND, null);

    private static BackgroundJobStatus finished(Instant started, Instant finished, JobOutcome outcome) {
        return new BackgroundJobStatus("A.b", started, finished, outcome, 0, 0,
                outcome == JobOutcome.OK ? finished : null, 0, null, null, null, null, "i", null, null);
    }

    private static Instant local(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(ZONE).toInstant();
    }

    @Test
    void fixedDelay_exactlyAtTheLimit_isNotOverdue() {
        Instant lastFinish = APP_START.plus(Duration.ofHours(1));
        BackgroundJobStatus s = finished(lastFinish.minusSeconds(1), lastFinish, JobOutcome.OK);
        assertThat(OverdueRule.state(EVERY_30S, s, APP_START, lastFinish.plus(ALLOWANCE))).isEqualTo(JobState.OK);
    }

    @Test
    void fixedDelay_oneSecondPast_isOverdue() {
        Instant lastFinish = APP_START.plus(Duration.ofHours(1));
        BackgroundJobStatus s = finished(lastFinish.minusSeconds(1), lastFinish, JobOutcome.OK);
        assertThat(OverdueRule.state(EVERY_30S, s, APP_START, lastFinish.plus(ALLOWANCE).plusSeconds(1)))
                .isEqualTo(JobState.OVERDUE);
    }

    @Test
    void startedNeverFinished_isOverdueOnTheSameClock() {
        Instant started = APP_START.plus(Duration.ofHours(1));
        BackgroundJobStatus running = new BackgroundJobStatus("A.b", started, started.minusSeconds(40),
                JobOutcome.OK, 0, 0, started.minusSeconds(40), 0, null, null, null, null, "i", null, null);
        assertThat(OverdueRule.state(EVERY_30S, running, APP_START, started.plus(ALLOWANCE))).isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(EVERY_30S, running, APP_START, started.plus(ALLOWANCE).plusSeconds(1)))
                .isEqualTo(JobState.OVERDUE);
        assertThat(OverdueRule.isRunning(running)).isTrue();
    }

    @Test
    void freshStart_noRowYet_isNotOverdueBeforeInitialDelayPlusAllowance() {
        JobSchedule delayed = new JobSchedule("A.b", Duration.ofSeconds(30), Duration.ofMinutes(3), null, "x", JobSchedule.Source.BACKEND, null);
        Instant limit = APP_START.plus(Duration.ofMinutes(3)).plus(ALLOWANCE);
        assertThat(OverdueRule.state(delayed, null, APP_START, limit)).isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(delayed, null, APP_START, limit.plusSeconds(1))).isEqualTo(JobState.OVERDUE);
    }

    @Test
    void aRunFromBeforeARestart_countsFromTheRestart() {
        Instant oldFinish = APP_START.minus(Duration.ofDays(2));
        BackgroundJobStatus s = finished(oldFinish.minusSeconds(1), oldFinish, JobOutcome.OK);
        assertThat(OverdueRule.state(EVERY_30S, s, APP_START, APP_START.plusSeconds(30))).isEqualTo(JobState.OK);
    }

    @Test
    void lastOutcomeWins_whenNotOverdue() {
        Instant lastFinish = APP_START.plus(Duration.ofHours(1));
        Instant now = lastFinish.plusSeconds(10);
        for (JobOutcome outcome : JobOutcome.values()) {
            BackgroundJobStatus s = finished(lastFinish.minusSeconds(1), lastFinish, outcome);
            assertThat(OverdueRule.state(EVERY_30S, s, APP_START, now)).isEqualTo(JobState.valueOf(outcome.name()));
        }
    }

    @Test
    void overdueBeatsAStaleOkAndAStaleOff() {
        Instant lastFinish = APP_START.plus(Duration.ofHours(1));
        Instant late = lastFinish.plus(Duration.ofHours(1));
        assertThat(OverdueRule.state(EVERY_30S, finished(lastFinish, lastFinish, JobOutcome.OK), APP_START, late))
                .isEqualTo(JobState.OVERDUE);
        assertThat(OverdueRule.state(EVERY_30S, finished(lastFinish, lastFinish, JobOutcome.OFF), APP_START, late))
                .isEqualTo(JobState.OVERDUE);
    }

    @Test
    void cron_daily0345_30MinutesAfterAMissedFire_isOverdue_29MinutesIsNot() {
        Instant appStart = local(10, 12, 0);
        Instant ranYesterday = local(12, 3, 45);
        BackgroundJobStatus s = finished(ranYesterday, ranYesterday.plusSeconds(5), JobOutcome.OK);

        assertThat(OverdueRule.state(DAILY_0345, s, appStart, local(13, 4, 14))).isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(DAILY_0345, s, appStart, local(13, 4, 16))).isEqualTo(JobState.OVERDUE);
    }

    @Test
    void cron_ranAtItsFire_isNotOverdueAllDay() {
        Instant appStart = local(10, 12, 0);
        Instant ranToday = local(13, 3, 45);
        BackgroundJobStatus s = finished(ranToday, ranToday.plusSeconds(5), JobOutcome.OK);
        assertThat(OverdueRule.state(DAILY_0345, s, appStart, local(13, 23, 59))).isEqualTo(JobState.OK);
    }

    @Test
    void cron_aFireBeforeTheAppStarted_isNotCountedAsMissed() {
        Instant appStart = local(13, 3, 50);
        assertThat(OverdueRule.state(DAILY_0345, null, appStart, local(13, 10, 0))).isEqualTo(JobState.OK);
    }

    @Test
    void cron_startedNeverFinished_isOverdue30MinutesAfterItsStart() {
        Instant appStart = local(10, 12, 0);
        Instant started = local(13, 3, 45);
        BackgroundJobStatus running = new BackgroundJobStatus("C.d", started, local(12, 3, 46), JobOutcome.OK,
                0, 0, local(12, 3, 46), 0, null, null, null, null, "i", null, null);
        assertThat(OverdueRule.state(DAILY_0345, running, appStart, started.plus(Duration.ofMinutes(30))))
                .isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(DAILY_0345, running, appStart, started.plus(Duration.ofMinutes(31))))
                .isEqualTo(JobState.OVERDUE);
    }

    @Test
    void neverRunAndNotYetDue_isOk() {
        assertThat(OverdueRule.state(EVERY_30S, null, APP_START, APP_START.plusSeconds(5))).isEqualTo(JobState.OK);
    }

    // ---- Part 2: GitHub workflows (UTC crons, 3 h allowance, 6 h running limit, no app-start exception)

    private static JobSchedule github(String cron) {
        return new JobSchedule("github:Fynora/finora/x.yml", null, null, cron, "x", JobSchedule.Source.GITHUB, null);
    }

    private static BackgroundJobStatus githubRun(Instant started, Instant finished) {
        return new BackgroundJobStatus("github:Fynora/finora/x.yml", started, finished, finished == null ? null : JobOutcome.OK,
                0, 0, finished, 0, null, null, null, null, "github", 1L, "u");
    }

    @Test
    void github_nightlyDelayed2h_isNotOverdue() {
        Instant fire = Instant.parse("2026-11-14T03:00:00Z");
        BackgroundJobStatus yesterday = githubRun(fire.minus(Duration.ofDays(1)), fire.minus(Duration.ofDays(1)).plusSeconds(600));
        assertThat(OverdueRule.state(github("0 3 * * *"), yesterday, APP_START, fire.plus(Duration.ofHours(2))))
                .isEqualTo(JobState.OK);
    }

    @Test
    void github_3h1mAfterTheFireWithNoRun_isOverdue_inUtcWhateverTheJvmZone() {
        Instant fire = Instant.parse("2026-11-14T03:00:00Z");
        BackgroundJobStatus yesterday = githubRun(fire.minus(Duration.ofDays(1)), fire.minus(Duration.ofDays(1)).plusSeconds(600));
        assertThat(OverdueRule.state(github("0 3 * * *"), yesterday, APP_START, fire.plus(Duration.ofMinutes(179))))
                .isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(github("0 3 * * *"), yesterday, APP_START, fire.plus(Duration.ofMinutes(181))))
                .isEqualTo(JobState.OVERDUE);
    }

    @Test
    void github_aRunStartedAfterTheFire_isNotOverdue() {
        Instant fire = Instant.parse("2026-11-14T03:00:00Z");
        BackgroundJobStatus today = githubRun(fire.plusSeconds(300), fire.plusSeconds(900));
        assertThat(OverdueRule.state(github("0 3 * * *"), today, APP_START, fire.plus(Duration.ofHours(10))))
                .isEqualTo(JobState.OK);
    }

    @Test
    void github_runningOver6h_isOverdue() {
        Instant started = Instant.parse("2026-11-14T03:00:00Z");
        BackgroundJobStatus running = githubRun(started, null);
        assertThat(OverdueRule.state(github("0 3 * * *"), running, APP_START, started.plus(Duration.ofHours(6))))
                .isEqualTo(JobState.OK);
        assertThat(OverdueRule.state(github(null), running, APP_START, started.plus(Duration.ofHours(6)).plusSeconds(1)))
                .isEqualTo(JobState.OVERDUE);
    }

    @Test
    void github_pushTriggered_isNeverOverdue() {
        assertThat(OverdueRule.state(github(null), null, APP_START, APP_START.plus(Duration.ofDays(30))))
                .isEqualTo(JobState.OK);
    }

    @Test
    void github_fireBeforeTheAppStarted_stillCounts() {
        Instant fire = Instant.parse("2026-11-14T03:00:00Z");
        Instant appStart = fire.plus(Duration.ofHours(1));
        BackgroundJobStatus twoDaysAgo = githubRun(fire.minus(Duration.ofDays(2)), fire.minus(Duration.ofDays(2)).plusSeconds(60));
        assertThat(OverdueRule.state(github("0 3 * * *"), twoDaysAgo, appStart, fire.plus(Duration.ofHours(4))))
                .isEqualTo(JobState.OVERDUE);
    }

    /** No run read yet (just deployed, or the token is broken: the reader's own row says so). */
    @Test
    void github_noRunReadYet_isNotOverdue() {
        Instant fire = Instant.parse("2026-11-14T03:00:00Z");
        assertThat(OverdueRule.state(github("0 3 * * *"), null, APP_START, fire.plus(Duration.ofHours(10))))
                .isEqualTo(JobState.OK);
    }

    @Test
    void anOffEntryIsOff_whateverItsRowSays() {
        JobSchedule off = new JobSchedule("github:Fynora/finora/t.yml", null, null, "0 */6 * * *", "x",
                JobSchedule.Source.GITHUB, "Needs a GitHub token from Sid");
        BackgroundJobStatus crashed = new BackgroundJobStatus("k", APP_START, APP_START, JobOutcome.CRASHED, 0, 0,
                null, 3, APP_START, null, null, null, "github", 5L, "u");
        assertThat(OverdueRule.state(off, crashed, APP_START, APP_START.plus(Duration.ofDays(3)))).isEqualTo(JobState.OFF);
    }

    @Test
    void githubCronsReadInWords() {
        assertThat(JobScheduleRegistry.describeGitHubCron("30 4 * * *")).isEqualTo("daily at 04:30 UTC");
        assertThat(JobScheduleRegistry.describeGitHubCron("0 4 * * 1")).isEqualTo("Mondays at 04:00 UTC");
        assertThat(JobScheduleRegistry.describeGitHubCron("0 */6 * * *")).isEqualTo("every 6 hours");
        assertThat(JobScheduleRegistry.describeGitHubCron(null)).isEqualTo("when started by a push");
    }
}
