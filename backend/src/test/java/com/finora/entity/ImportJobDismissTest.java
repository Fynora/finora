package com.finora.entity;

import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dismissing a finished import from the owner's recent-imports list (V264): display state only,
 * allowed only once the import is over.
 */
class ImportJobDismissTest {

    private static ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
    }

    private static ImportJob failed() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: boom", "IllegalStateException",
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        return job;
    }

    private static ImportJob rejectedInTrustReview() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), Instant.now());
        return job;
    }

    @Test
    void onlyFailedAndCancelledAreDismissable() {
        assertThat(ImportJob.DISMISSABLE)
                .isEqualTo(EnumSet.of(ImportJob.Status.FAILED, ImportJob.Status.CANCELLED));
    }

    @Test
    void aFailedJobIsDismissedWithoutTouchingWhatItRecorded() {
        ImportJob job = failed();
        Instant at = Instant.parse("2026-10-09T10:00:00Z");

        job.dismiss(at);

        assertThat(job.getDismissedAt()).isEqualTo(at);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getLastError()).isEqualTo("IllegalStateException: boom");
        assertThat(job.getFailureCode()).isEqualTo("IllegalStateException");
    }

    @Test
    void aCancelledJobCanBeDismissed() {
        ImportJob job = job();
        job.cancel(Instant.now());

        job.dismiss(Instant.now());

        assertThat(job.getDismissedAt()).isNotNull();
    }

    @Test
    void dismissingAgainKeepsTheFirstTime() {
        ImportJob job = failed();
        Instant first = Instant.parse("2026-10-09T10:00:00Z");
        job.dismiss(first);

        job.dismiss(first.plusSeconds(60));

        assertThat(job.getDismissedAt()).isEqualTo(first);
    }

    @Test
    void aQueuedJobCannotBeDismissed() {
        ImportJob job = job();

        assertThatThrownBy(() -> job.dismiss(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(job.getDismissedAt()).isNull();
    }

    @Test
    void aHeldJobCannotBeDismissed() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> job.dismiss(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCompletedJobCannotBeDismissed() {
        ImportJob job = job();
        job.markClaimed("worker", Instant.now());
        job.complete(UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> job.dismiss(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Reopening a rejected review makes the import live again. Left dismissed, the hold -- and
     * whatever the review decides next -- would never appear in the owner's list.
     */
    @Test
    void reopeningADismissedRejectionListsItAgain() {
        ImportJob job = rejectedInTrustReview();
        job.dismiss(Instant.now());

        job.reopenTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(job.getDismissedAt()).isNull();
    }
}
