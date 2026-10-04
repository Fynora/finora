package com.finora.dto;

import com.finora.entity.ImportJob;

import java.time.Instant;
import java.util.UUID;

/**
 * An import held for trust review whose review record was never written -- the worker's fail-closed
 * path when {@code HeldStatementService.createHold} throws. Nothing in the held-statements queue
 * stands for it, so this is the row an operator opens a review from.
 *
 * <p>Carries no statement content, the same rule the queue's own rows follow.
 *
 * @param jobId the held import job
 * @param fileName the name the user uploaded the statement under
 * @param heldAt when the worker held it
 * @param stagedRowsAvailable whether its staged session still exists; when it does not, the review
 *                            opens anyway and a parser re-run stages the statement again
 */
public record HoldWithoutReviewRecordDto(UUID jobId, String fileName, Instant heldAt,
                                         boolean stagedRowsAvailable) {

    public static HoldWithoutReviewRecordDto from(ImportJob job, boolean stagedRowsAvailable) {
        return new HoldWithoutReviewRecordDto(job.getId(), job.getFileName(), job.getFinishedAt(),
                stagedRowsAvailable);
    }
}
