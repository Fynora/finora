package com.finora.dto;

import com.finora.imports.fix.ClaudeFixStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One open hold on the "Needs a decision" page. Carries no statement content -- no file name, no
 * error text, no customer name -- which is why browsing the list is not audited, like the two
 * hold lists it gathers.
 *
 * @param kind        {@code IMPORT} (held import), {@code STATEMENT} (trust hold with a review) or
 *                    {@code NO_RECORD} (trust hold the worker could not write a review for)
 * @param heldId      the review's id, for {@code STATEMENT} only
 * @param bank        the review's bank, for {@code STATEMENT} only
 * @param heldAt      when this hold began: {@code import_jobs.finished_at}, the overdue email's clock
 * @param promiseAt   {@code heldAt} plus the 48-hour promise
 * @param who         {@code ADMIN}, {@code CLAUDE} or {@code NOBODY}
 * @param whoName     the assigned admin's name, when {@code who} is {@code ADMIN} and the name is known
 * @param nextStep    a code; the page owns its words
 * @param prNumber    Claude's public PR, when it opened one
 * @param claudeFix   what the Claude fix did, as the hold pages show it; null while it is switched on
 *                    and has not recorded its decision yet
 */
@Schema(name = "DecisionQueueRow")
public record DecisionQueueRowDto(
        String kind,
        UUID jobId,
        String heldId,
        String bank,
        String sourceFormat,
        Instant heldAt,
        Instant promiseAt,
        String who,
        String whoName,
        String nextStep,
        Integer prNumber,
        ClaudeFixStatus claudeFix) {
}
