package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the user's own progress endpoint may say about why a job failed.
 *
 * <p>{@code last_error} is {@code ExceptionClass: message} -- written for engineers, and for an
 * infrastructure failure it can name an object key, a storage endpoint or a hash. It used to be
 * returned to the owner verbatim once the job FAILED. No app displays it, but an API is not
 * "whatever the apps happen to render": a person with the token can read it.
 */
class ImportJobDtoProgressTest {

    private ImportJob failedJob(String rawError, String failureCode) {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure(rawError, failureCode, ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        return job;
    }

    @Test
    void anInfrastructureFailureNeverLeaksItsRawErrorToTheUser() {
        ImportJob job = failedJob(
                "StatementStorageException: R2 unavailable at https://acct.r2.cloudflarestorage.com/finora-statements/objects/9f2c",
                "StatementStorageException");

        assertThat(ImportJobDto.Progress.of(job).error()).isNull();
    }

    @Test
    void anUnclassifiedFailureNeverLeaksItsRawError() {
        ImportJob job = failedJob("NullPointerException: Cannot invoke \"String.length()\" because \"s\" is null", "NullPointerException");

        assertThat(ImportJobDto.Progress.of(job).error()).isNull();
    }

    /** A curated code has curated wording; that, not the engineer's string, is what the user may read. */
    @Test
    void aCuratedFailureCarriesItsCuratedMessageNotTheRawOne() {
        ImportJob job = failedJob(
                "ApiException: This PDF could not be read -- Missing root object specification in trailer. (offset 48211)",
                "IMPORT_CORRUPT_PDF");

        String error = ImportJobDto.Progress.of(job).error();

        assertThat(error).isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage());
        assertThat(error).doesNotContain("offset").doesNotContain("trailer");
    }

    @Test
    void aJobThatHasNotFailedCarriesNoError() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "statement.pdf", "hash", "objects/key", "PDF");

        assertThat(ImportJobDto.Progress.of(job).error()).isNull();
    }
}
