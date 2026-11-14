package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundJobAlertDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static BackgroundJobStatus status(String lastEmailKind, Instant lastEmailAt, Instant lastBrokenEmailAt) {
        return new BackgroundJobStatus("A.b", NOW.minusSeconds(60), NOW.minusSeconds(59), JobOutcome.CRASHED, 0, 0,
                null, 1, NOW.minusSeconds(59), lastEmailKind, lastEmailAt, lastBrokenEmailAt, "i", null, null);
    }

    @Test
    void firstBreak_sendsBroken() {
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED, status(null, null, null), NOW))
                .contains(AlertKind.BROKEN);
        assertThat(BackgroundJobAlertDecision.decide(JobState.PARTLY_FAILED, status(null, null, null), NOW))
                .contains(AlertKind.BROKEN);
        assertThat(BackgroundJobAlertDecision.decide(JobState.OVERDUE, null, NOW)).contains(AlertKind.BROKEN);
    }

    @Test
    void stillBrokenAfter24h_sendsStillBrokenOnce() {
        Instant sent = NOW.minus(Duration.ofHours(24));
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED, status("BROKEN", sent, sent), NOW))
                .contains(AlertKind.STILL_BROKEN);
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED,
                status("BROKEN", sent.plusSeconds(1), sent.plusSeconds(1)), NOW)).isEmpty();
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED,
                status("STILL_BROKEN", NOW.minus(Duration.ofDays(3)), sent), NOW)).isEmpty();
    }

    @Test
    void cleanRunAfterBrokenEmail_sendsWorkingAgain() {
        Instant sent = NOW.minus(Duration.ofHours(1));
        assertThat(BackgroundJobAlertDecision.decide(JobState.OK, status("BROKEN", sent, sent), NOW))
                .contains(AlertKind.WORKING_AGAIN);
        assertThat(BackgroundJobAlertDecision.decide(JobState.OK, status("STILL_BROKEN", sent, sent), NOW))
                .contains(AlertKind.WORKING_AGAIN);
        assertThat(BackgroundJobAlertDecision.decide(JobState.OFF, status("BROKEN", sent, sent), NOW))
                .contains(AlertKind.WORKING_AGAIN);
    }

    @Test
    void cleanWithNoBrokenEmail_sendsNothing() {
        assertThat(BackgroundJobAlertDecision.decide(JobState.OK, status(null, null, null), NOW)).isEmpty();
        assertThat(BackgroundJobAlertDecision.decide(JobState.OK, null, NOW)).isEmpty();
        Instant sent = NOW.minus(Duration.ofHours(1));
        assertThat(BackgroundJobAlertDecision.decide(JobState.OK, status("WORKING_AGAIN", sent, sent), NOW)).isEmpty();
    }

    @Test
    void secondBreakWithin24hOfLastBroken_sendsNothing_after24hSendsBroken() {
        Instant brokenSent = NOW.minus(Duration.ofHours(5));
        Instant recoveredSent = NOW.minus(Duration.ofHours(4));
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED,
                status("WORKING_AGAIN", recoveredSent, brokenSent), NOW)).isEmpty();

        Instant longAgo = NOW.minus(Duration.ofHours(25));
        assertThat(BackgroundJobAlertDecision.decide(JobState.CRASHED,
                status("WORKING_AGAIN", recoveredSent, longAgo), NOW)).contains(AlertKind.BROKEN);
    }

    @Test
    void offIsNeverBroken() {
        assertThat(BackgroundJobAlertDecision.decide(JobState.OFF, status(null, null, null), NOW)).isEmpty();
    }
}
