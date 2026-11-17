package com.finora.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * "Staged, but was it imported?" -- the weekly count beside Reading quality by bank. Customers'
 * statements that read cleanly, grouped by the week they were staged, and what became of each.
 * Counts only: no bank, file name, user id or reference.
 */
public final class StagedOutcomesDto {

    private StagedOutcomesDto() {}

    /**
     * @param weeks          the period asked for (this week and the ones before it)
     * @param recordedSince  the day (India time) V327 started recording imports (discards and the
     *                       Free plan limit are recorded since V326, deployed before it). Statements
     *                       staged before it mostly read "not recorded".
     *                       Null if the migration history cannot say.
     * @param weekly         one entry per week, oldest first
     * @param total          the whole period ({@code weekStart} null)
     */
    @Schema(name = "StagedOutcomesReport")
    public record Report(int weeks, LocalDate recordedSince, List<Week> weekly, Week total) {}

    /**
     * Each staged statement is counted once, in the first of these that applies:
     * {@code imported}, {@code discardedByCustomer}, {@code removedByPhone}, {@code expired},
     * {@code stillOpen}, else {@code notRecorded}. They add up to {@code staged}.
     *
     * @param staged               statements the reader staged for a customer's review
     * @param imported             imported (confirmed)
     * @param discardedByCustomer  the customer deleted it without importing
     * @param removedByPhone       the phone app deleted it: it cannot import a statement covering
     *                             more than one account
     * @param expired              left unconfirmed until the 48-hour sweep removed it
     * @param stillOpen            still waiting: not imported, deleted or expired yet (including any
     *                             held for the team's check)
     * @param notRecorded          none of the above was recorded -- staged before recording began,
     *                             or removed by the system (a re-read, a rejected hold, a stale copy
     *                             replaced on re-upload)
     * @param freeLimitShown       the review opened with the Free plan's one-month notice
     * @param freeLimitRefused     a confirm was refused for that limit
     * @param freeLimitThenImported shown the notice, and imported all the same: after an upgrade, or as
     *                             just its latest month (the Free plan's "import only the latest month")
     * @param discardReasons       the customer's optional reasons, of those {@code discardedByCustomer}
     * @param latestMonthOnly      imported as just its latest month -- the Free plan offer (V328); one
     *                             part of {@code imported}, and of {@code freeLimitThenImported}
     */
    @Schema(name = "StagedOutcomesWeek")
    public record Week(LocalDate weekStart, long staged, long imported, long discardedByCustomer,
                       long removedByPhone, long expired, long stillOpen, long notRecorded,
                       long freeLimitShown, long freeLimitRefused, long freeLimitThenImported,
                       DiscardReasons discardReasons, long latestMonthOnly) {}

    /**
     * Of the statements discarded by the customer, by the optional reason they tapped (V327).
     * {@code noReason}: none was given -- skipping is allowed, and older apps never ask. They add up
     * to {@code discardedByCustomer}.
     */
    @Schema(name = "StagedOutcomesDiscardReasons")
    public record DiscardReasons(long wrongFile, long tooManyMonths, long looksWrong, long later, long other,
                                 long noReason) {}
}
