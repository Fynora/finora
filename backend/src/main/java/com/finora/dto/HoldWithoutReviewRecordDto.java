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
 * @param coveredByHeldId the open review that already covers this import's rows -- a later upload
 *                        of the same statement held on the same session -- or null. Deciding that
 *                        review decides this import too, so no second review is opened for it.
 */
public record HoldWithoutReviewRecordDto(UUID jobId, String fileName, Instant heldAt,
                                         boolean stagedRowsAvailable, String coveredByHeldId) {

    public static HoldWithoutReviewRecordDto from(ImportJob job, boolean stagedRowsAvailable,
                                                  String coveredByHeldId) {
        return new HoldWithoutReviewRecordDto(job.getId(), job.getFileName(), job.getFinishedAt(),
                stagedRowsAvailable, coveredByHeldId);
    }
}
