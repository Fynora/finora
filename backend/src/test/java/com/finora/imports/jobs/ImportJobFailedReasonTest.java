package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way an import job reaches FAILED leaves a reason on the row for engineers ({@code
 * last_error}) and a reason the user may read ({@code Progress.error}).
 *
 * <p>Written after a production investigation found a FAILED job with its rows counted and both
 * {@code last_error} and {@code resolution_message} empty. It had been rejected in trust review:
 * {@link ImportJob#holdForTrustReview} clears {@code last_error}, and the rejection wrote only a
 * failure code, so the row looked like a failure nobody had explained. One test per transition
 * into FAILED, so a new one added without a reason has an obvious place to be caught.
 */
class ImportJobFailedReasonTest {

    private static ImportJob claimed() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        return job;
    }

    private static void assertExplained(ImportJob job) {
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getLastError()).as("the row explains itself to an engineer").isNotBlank();
        String reason = ImportJobDto.Progress.of(job).error();
        assertThat(reason).as("the user is told why").isNotBlank();
        assertThat(reason).as("never the engineer's text").isNotEqualTo(job.getLastError());
    }

    /** The production row: held with its rows staged, then rejected by a reviewer. */
    @Test
    void aJobRejectedInTrustReviewCarriesAReasonOnTheRowAndForTheUser() {
        ImportJob job = claimed();
        job.recordProgress(191, 191);
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), Instant.now());

        assertExplained(job);
        assertThat(job.getRowsTotal()).isEqualTo(191);
        assertThat(job.getResolutionMessage()).isNull();
        assertThat(job.getLastError()).isEqualTo(ImportJob.REJECTED_IN_TRUST_REVIEW);
        assertThat(ImportJobDto.Progress.of(job).error())
                .isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.defaultMessage());
    }

    /** The worker's own path for an upload riding a review that was already rejected. */
    @Test
    void aJobRidingARejectedReviewIsExplainedTheSameWay() {
        ImportJob job = claimed();
        job.recordProgress(12, 12);
        Instant now = Instant.now();
        job.holdForTrustReview(UUID.randomUUID(), null, now);
        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now);

        assertExplained(job);
    }

    @Test
    void reopeningARejectionClearsTheRejectionsReason() {
        ImportJob job = claimed();
        job.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), Instant.now());

        job.reopenTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(job.getLastError()).as("held again is not a failure").isNull();
        assertThat(ImportJobDto.Progress.of(job).error()).isNull();
    }

    @Test
    void aCuratedFailFastFailureIsExplained() {
        ImportJob job = claimed();
        job.recordFailure("ApiException: damaged trailer", ErrorCode.IMPORT_CORRUPT_PDF.name(),
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertExplained(job);
        assertThat(ImportJobDto.Progress.of(job).error())
                .isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage());
    }

    @Test
    void anUnclassifiedFailureIsExplainedWithTheFallback() {
        ImportJob job = claimed();
        job.recordFailure("NullPointerException: x is null", "NullPointerException",
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertExplained(job);
        assertThat(ImportJobDto.Progress.of(job).error())
                .isEqualTo(ImportJobDto.FAILED_WITHOUT_CURATED_REASON);
    }

    @Test
    void aFailureWithNoCodeAtAllIsExplainedWithTheFallback() {
        ImportJob job = claimed();
        job.recordFailure("ApiException: no code", null, ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertExplained(job);
        assertThat(ImportJobDto.Progress.of(job).error())
                .isEqualTo(ImportJobDto.FAILED_WITHOUT_CURATED_REASON);
    }

    @Test
    void retriesThatRunOutAreExplained() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        ImportJob.FailureOutcome outcome = null;
        for (int i = 0; i < ImportJob.MAX_ATTEMPTS; i++) {
            job.markClaimed("worker", Instant.now());
            outcome = job.recordFailure("SocketTimeoutException: read timed out", "SocketTimeoutException",
                    ErrorCode.RetryPolicy.RETRY, Instant.now());
        }
        assertThat(outcome).as("fixture must reach the dead letter").isEqualTo(ImportJob.FailureOutcome.DEAD_LETTERED);

        assertExplained(job);
    }

    @Test
    void aJobThatKeepsKillingItsWorkerIsExplained() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        boolean failed = false;
        for (int i = 0; i <= ImportJob.MAX_RECOVERIES; i++) {
            job.markClaimed("worker", Instant.now());
            failed = job.returnToQueue("Abandoned in PARSING for longer than PT10M", Instant.now());
        }
        assertThat(failed).as("fixture must exhaust the recovery budget").isTrue();

        assertExplained(job);
        assertThat(ImportJobDto.Progress.of(job).error())
                .isEqualTo(ImportJobDto.FAILED_WITHOUT_CURATED_REASON);
    }

    @Test
    void aHeldImportResolvedWithoutAFixShowsTheAdminsMessage() {
        ImportJob job = claimed();
        job.recordFailure("IllegalStateException: no header row found", "IllegalStateException",
                ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());

        job.resolveWithoutFix(Instant.now(), "  We can't read this bank's layout yet.  ");

        assertExplained(job);
        assertThat(ImportJobDto.Progress.of(job).error())
                .as("the admin's message is the most specific thing to say, and wins over the code")
                .isEqualTo("We can't read this bank's layout yet.");
    }

    @Test
    void noOtherStatusCarriesAReason() {
        for (Supplier<ImportJob> make : java.util.List.<Supplier<ImportJob>>of(
                ImportJobFailedReasonTest::claimed,
                () -> {
                    ImportJob j = claimed();
                    j.holdForTrustReview(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
                    return j;
                },
                () -> {
                    ImportJob j = claimed();
                    j.complete(UUID.randomUUID(), Instant.now());
                    return j;
                })) {
            assertThat(ImportJobDto.Progress.of(make.get()).error()).isNull();
        }
    }
}
