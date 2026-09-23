package com.finora.testsupport;

import com.finora.security.mfa.TotpGenerator;

import java.time.Instant;

/**
 * Keeps a TOTP test clear of a 30-second step boundary.
 *
 * <p>The admin-MFA integration tests compute a code for a chosen step ("one step ago", "now"),
 * then send it through several HTTP calls -- user save, password login, enrolment -- before the
 * server checks it against its own clock. {@link TotpGenerator#matchStep} accepts only the current
 * step and one either side, so if a step boundary passes between the test reading the clock and
 * the server reading it, a "one step ago" code is two steps old and is refused with 401. That was
 * {@code AdminMfaReplayIT.claimStepOnlyMovesForward}'s intermittent failure on main (2026-09-23),
 * reproduced on demand by starting the test 100 ms before a boundary.
 *
 * <p>Waiting until at least {@link #HEADROOM_MILLIS} remain in the current step before a test
 * starts makes that impossible: every test in these classes finishes its clock-sensitive calls
 * well inside that margin (measured: under one second).
 */
public final class TotpStepHeadroom {

    /** Minimum time left in the current step before a test may start. */
    public static final long HEADROOM_MILLIS = 5_000L;

    private TotpStepHeadroom() {}

    /** Blocks until at least {@link #HEADROOM_MILLIS} remain before the next step boundary. */
    public static void await() throws InterruptedException {
        long stepMillis = 30_000L;
        long now = Instant.now().toEpochMilli();
        long remaining = stepMillis - (now % stepMillis);
        if (remaining < HEADROOM_MILLIS) {
            // Sleep past the boundary, plus a small margin so the new step has definitely begun.
            Thread.sleep(remaining + 50L);
        }
    }
}
