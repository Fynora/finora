package com.finora.observability.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Which email, if any, a job's current state calls for, given the last one sent.
 *
 * <p>At most: one BROKEN when it breaks, one STILL_BROKEN 24 hours later, one WORKING_AGAIN when it
 * recovers. A job that breaks again within 24 hours of its last BROKEN email sends nothing new, so a
 * job that flaps every few minutes cannot flood an admin's phone.
 */
final class BackgroundJobAlertDecision {

    static final Duration REMINDER_AFTER = Duration.ofHours(24);
    static final Duration BROKEN_EMAIL_GAP = Duration.ofHours(24);

    private BackgroundJobAlertDecision() {}

    static Optional<AlertKind> decide(JobState state, BackgroundJobStatus status, Instant now) {
        String last = status == null ? null : status.lastEmailKind();
        boolean brokenEmailOut = AlertKind.BROKEN.name().equals(last) || AlertKind.STILL_BROKEN.name().equals(last);

        if (!state.needsAttention()) {
            return brokenEmailOut ? Optional.of(AlertKind.WORKING_AGAIN) : Optional.empty();
        }
        if (AlertKind.BROKEN.name().equals(last)) {
            Instant sent = status.lastEmailAt();
            return sent != null && !now.isBefore(sent.plus(REMINDER_AFTER))
                    ? Optional.of(AlertKind.STILL_BROKEN) : Optional.empty();
        }
        if (AlertKind.STILL_BROKEN.name().equals(last)) {
            return Optional.empty();
        }
        Instant lastBroken = status == null ? null : status.lastBrokenEmailAt();
        if (lastBroken != null && now.isBefore(lastBroken.plus(BROKEN_EMAIL_GAP))) {
            return Optional.empty();
        }
        return Optional.of(AlertKind.BROKEN);
    }
}
