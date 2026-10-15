package com.finora.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.finora.entity.ImportJob;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the admin held-imports triage queue.
 *
 * <p><b>Deliberately carries no {@code lastError}.</b> That field is {@code
 * ImportJobWorker.describe(Exception)} -- a class name plus a raw exception message -- and a parser
 * failure's message routinely quotes the input that defeated it ("no header row in: ..."). On a
 * bank statement, that input is a real person's financial data. The list view is a page an operator
 * leaves open; it gets the curated {@code failureCode} only, which is an {@code ErrorCode} name or
 * an exception's simple class name and carries no customer content.
 *
 * <p>{@link Detail} carries the raw text, and every read of it is audited. That split is the whole
 * privacy design: browsing the queue is cheap and anonymous, opening one statement's diagnostics is
 * recorded against the admin who did it.
 */
public record HeldImportDto(
        UUID id,
        UUID userId,
        String fileName,
        String sourceFormat,
        String failureCode,
        int attemptCount,
        int recoveryCount,
        Instant createdAt,
        Instant heldAt) {

    public static HeldImportDto from(ImportJob job) {
        return new HeldImportDto(
                job.getId(),
                job.getUserId(),
                job.getFileName(),
                job.getSourceFormat(),
                job.getFailureCode(),
                job.getAttemptCount(),
                job.getRecoveryCount(),
                job.getCreatedAt(),
                job.getFinishedAt());
    }

    /**
     * A held import plus the diagnostic text an engineer actually needs to fix the parser.
     *
     * <p>Every construction of this is audited by {@code AdminHeldImportService.detail} -- see this
     * class's own doc for why the row form withholds {@code lastError} and this one does not.
     */
    // Named for the contract: springdoc names a schema after the simple class name, and
    // SupportTicketDto.Detail is also "Detail", so without this the held-import detail endpoint was
    // described as a support ticket. The name changes only the contract, not the JSON.
    @io.swagger.v3.oas.annotations.media.Schema(name = "HeldImportDetail")
    public record Detail(HeldImportDto job, String lastError, String correlationId, String objectKey,
                         // Who opened the real statement, and when, oldest first -- never the reason,
                         // which is on the audit entry only.
                         List<Opening> originalOpenings,
                         // What the automatic Claude fix did: a state, a reason and PR numbers only.
                         // Null while the switch is on and nothing is recorded yet.
                         com.finora.imports.fix.ClaudeFixStatus claudeFix) {

        public static Detail from(ImportJob job, List<Opening> originalOpenings,
                                  com.finora.imports.fix.ClaudeFixStatus claudeFix) {
            return new Detail(
                    HeldImportDto.from(job),
                    job.getLastError(),
                    job.getCorrelationId(),
                    job.getObjectKey(),
                    List.copyOf(originalOpenings),
                    claudeFix);
        }
    }

    /** One opening of the held import's real statement: when, and by whom (their email). */
    @io.swagger.v3.oas.annotations.media.Schema(name = "HeldImportOriginalOpening")
    public record Opening(Instant openedAt, String openedBy) {}

    /**
     * The queue's counts.
     *
     * <p>{@code held} answers "is there anything to do", and gates whether the queue offers
     * Reprocess All at all.
     * {@code reprocessing} counts jobs an admin has already sent back to the queue that have not
     * finished yet, so a second admin does not reprocess the same thing again; it is deliberately
     * a count of QUEUED jobs that were once held, not of all QUEUED jobs.
     */
    @Schema(name = "HeldImportSummary")
    public record Summary(long held, long reprocessing) {}
}
