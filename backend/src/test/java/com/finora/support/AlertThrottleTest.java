package com.finora.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertThrottleTest {

    private MutableClock clock;
    private AlertThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"));
        throttle = new AlertThrottle(clock, 3, Duration.ofMinutes(10));
    }

    @Test
    void admitsUpToTheCapThenHoldsBackTheRest() {
        assertThat(throttle.admit("ticket").send()).isTrue();
        assertThat(throttle.admit("ticket").send()).isTrue();
        assertThat(throttle.admit("ticket").send()).isTrue();

        assertThat(throttle.admit("ticket").send()).isFalse();
        assertThat(throttle.admit("ticket").send()).isFalse();
    }

    @Test
    void onlyTheFirstHeldBackEventInAWindowIsFlaggedSoTheLogIsNotFloodedToo() {
        for (int i = 0; i < 3; i++) throttle.admit("ticket");

        assertThat(throttle.admit("ticket").firstHeldBackInWindow()).isTrue();
        assertThat(throttle.admit("ticket").firstHeldBackInWindow()).isFalse();
        assertThat(throttle.admit("ticket").firstHeldBackInWindow()).isFalse();
    }

    @Test
    void theNextWindowAdmitsAgain_andReportsHowManyWereHeldBack() {
        for (int i = 0; i < 3; i++) throttle.admit("ticket");
        throttle.admit("ticket");
        throttle.admit("ticket");

        clock.advance(Duration.ofMinutes(10));
        AlertThrottle.Admission next = throttle.admit("ticket");

        assertThat(next.send()).isTrue();
        assertThat(next.heldBackBefore()).isEqualTo(2);
        // The count is reported once, not on every later email.
        assertThat(throttle.admit("ticket").heldBackBefore()).isZero();
    }

    @Test
    void justInsideTheWindowStillCounts_exactlyAtItsEndStartsANewOne() {
        for (int i = 0; i < 3; i++) throttle.admit("ticket");

        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        assertThat(throttle.admit("ticket").send()).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(throttle.admit("ticket").send()).isTrue();
    }

    @Test
    void kindsAreCountedSeparately_aTicketFloodNeverSilencesFeedback() {
        for (int i = 0; i < 5; i++) throttle.admit("ticket");

        assertThat(throttle.admit("ticket").send()).isFalse();
        assertThat(throttle.admit("feedback").send()).isTrue();
    }

    @Test
    void ordinaryTrafficIsNeverHeldBack() {
        // A ticket every few minutes for a day never reaches a cap of 3 per 10 minutes.
        for (int i = 0; i < 300; i++) {
            assertThat(throttle.admit("ticket").send()).as("event %d", i).isTrue();
            clock.advance(Duration.ofMinutes(5));
        }
    }
}
