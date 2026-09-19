package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the failed-import card can show the user after an admin resolves a held import: the
 * admin's own words, and only once the job has actually ended in FAILED.
 */
class ImportJobDtoTimelineTest {

    private ImportJob heldJob() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("damaged", "IMPORT_CORRUPT_PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        job.holdForReview("IMPORT_CORRUPT_PDF", Instant.now());
        return job;
    }

    @Test
    void aResolvedJobCarriesTheAdminsMessage() {
        ImportJob job = heldJob();
        job.resolveWithoutFix(Instant.now(), "Please download the statement again from your bank.");

        ImportJobDto.Timeline timeline = ImportJobDto.Timeline.of(job, List.of());

        assertThat(timeline.status()).isEqualTo("FAILED");
        assertThat(timeline.resolutionMessage()).isEqualTo("Please download the statement again from your bank.");
        assertThat(timeline.failureCode()).as("the curated code is still there beside it").isEqualTo("IMPORT_011");
    }

    /** A held job is not over: nothing an admin might be drafting, and no message yet, may leak. */
    @Test
    void aStillHeldJobCarriesNoMessage() {
        assertThat(ImportJobDto.Timeline.of(heldJob(), List.of()).resolutionMessage()).isNull();
    }

    @Test
    void aFailedJobNobodyResolvedCarriesNoMessage() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("no activity", "IMPORT_NO_ACTIVITY_IN_PERIOD", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());

        assertThat(ImportJobDto.Timeline.of(job, List.of()).resolutionMessage()).isNull();
    }
}
