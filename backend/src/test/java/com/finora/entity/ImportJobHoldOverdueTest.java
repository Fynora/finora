package com.finora.entity;

import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 48-hour hold promise (Gate 1 spec §4): when a held job counts as overdue, and that every way
 * into a hold starts a fresh clock and a fresh escalation.
 */
class ImportJobHoldOverdueTest {

    private static final Instant HELD = Instant.parse("2026-10-01T10:00:00Z");
    private static final String REJECTED = ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name();

    private static ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    private static ImportJob heldForReview() {
        ImportJob job = job();
        job.holdForReview("IMPORT_NO_HEADER_DETECTED", HELD);
        return job;
    }

    private static ImportJob heldForTrustReview() {
        ImportJob job = job();
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), HELD);
        return job;
    }

    @Test
    void notOverdueAtExactly48Hours() {
        assertThat(heldForReview().isHoldOverdue(HELD.plus(ImportJob.HOLD_PROMISE))).isFalse();
        assertThat(heldForTrustReview().isHoldOverdue(HELD.plus(ImportJob.HOLD_PROMISE))).isFalse();
    }

    @Test
    void overdueOneSecondPast48Hours() {
        Instant past = HELD.plus(ImportJob.HOLD_PROMISE).plusSeconds(1);
        assertThat(heldForReview().isHoldOverdue(past)).isTrue();
        assertThat(heldForTrustReview().isHoldOverdue(past)).isTrue();
    }

    @Test
    void neverOverdueWhenNotHeld() {
        Instant late = HELD.plus(ImportJob.HOLD_PROMISE).plusSeconds(3600);
        assertThat(job().isHoldOverdue(late)).isFalse();

        ImportJob rejected = heldForTrustReview();
        rejected.rejectAfterTrustReview(REJECTED, HELD.plusSeconds(60));
        assertThat(rejected.isHoldOverdue(late)).isFalse();
    }

    @Test
    void heldOnStagingStartsItsClockAtCreation() {
        ImportJob job = ImportJob.heldOnStaging(UUID.randomUUID(), "statement.pdf", "hash", "objects/key",
                "PDF", null, UUID.randomUUID(), HELD);
        assertThat(job.getOverdueAlertedAt()).isNull();
        assertThat(job.isHoldOverdue(HELD.plus(ImportJob.HOLD_PROMISE))).isFalse();
        assertThat(job.isHoldOverdue(HELD.plus(ImportJob.HOLD_PROMISE).plusSeconds(1))).isTrue();
    }

    @Test
    void reEnteringAHoldAfterAReRunClearsTheMarkerAndRestartsTheClock() {
        ImportJob job = heldForReview();
        job.markOverdueAlerted(HELD.plusSeconds(200_000));
        job.returnToQueueForReprocess(HELD.plusSeconds(200_100));
        Instant heldAgain = HELD.plusSeconds(200_200);
        job.holdForReview("IMPORT_NO_HEADER_DETECTED", heldAgain);

        assertThat(job.getOverdueAlertedAt()).isNull();
        assertThat(job.isHoldOverdue(heldAgain.plus(ImportJob.HOLD_PROMISE))).isFalse();
    }

    @Test
    void aReRunHeldForTrustReviewClearsTheMarkerAndRestartsTheClock() {
        ImportJob job = heldForReview();
        job.markOverdueAlerted(HELD.plusSeconds(200_000));
        job.returnToQueueForReprocess(HELD.plusSeconds(200_100));
        Instant heldAgain = HELD.plusSeconds(200_200);
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), heldAgain);

        assertThat(job.getOverdueAlertedAt()).isNull();
        assertThat(job.isHoldOverdue(heldAgain.plus(ImportJob.HOLD_PROMISE))).isFalse();
    }

    @Test
    void reopeningARejectedReviewClearsTheMarkerAndRestartsTheClock() {
        // Rejected days after the original hold, and reopened days after that: the user is waiting
        // again from the moment of reopening, not from the hold the rejection ended.
        ImportJob job = heldForTrustReview();
        job.markOverdueAlerted(HELD.plusSeconds(200_000));
        job.rejectAfterTrustReview(REJECTED, HELD.plusSeconds(300_000));
        Instant reopened = HELD.plusSeconds(600_000);
        job.reopenTrustReview(REJECTED, reopened);

        assertThat(job.getOverdueAlertedAt()).isNull();
        assertThat(job.getFinishedAt()).isEqualTo(reopened);
        assertThat(job.isHoldOverdue(reopened.plus(ImportJob.HOLD_PROMISE))).isFalse();
        assertThat(job.isHoldOverdue(reopened.plus(ImportJob.HOLD_PROMISE).plusSeconds(1))).isTrue();
    }
}
