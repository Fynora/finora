package com.finora.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * The "Reading quality by bank" page (docs/superpowers/specs/2026-11-15-reading-quality-by-bank-design.md):
 * per bank, how many statements and rows the reader read, how many it failed on, how many were
 * held for review, and how many reader fixes landed, week by week. Bank names and counts only --
 * no file name, user id, job id or reference -- which is why reading it is not audited.
 */
public final class ReadingQualityDto {

    private ReadingQualityDto() {}

    /**
     * @param weeks         the period asked for, in weeks (this week and the ones before it)
     * @param minStatements the fewest statements tried (read plus failed) for which a rate is given
     * @param weekStarts    every week in the period, oldest first, each a Monday in India time
     * @param banks         one row per bank, most in need of work first
     * @param bankNotKnown  holds and fixes whose upload never had its bank named
     */
    @Schema(name = "ReadingQualityReport")
    public record Report(int weeks, int minStatements, List<LocalDate> weekStarts, List<BankRow> banks,
                         Unknown bankNotKnown) {}

    /**
     * @param failedReads uploads the reader failed on for a reason of its own, one per file
     * @param holds       statements held for review that are not already a failed read
     * @param problemRate (holds + failedReads) / (statementsRead + failedReads); null when fewer
     *                    than {@code minStatements} statements were tried, so a bank with 1 hold in
     *                    1 statement does not top the list
     * @param weeks       the same counts for each week in {@code weekStarts}, in the same order
     */
    @Schema(name = "ReadingQualityBankRow")
    public record BankRow(String bank, long statementsRead, long rowsRead, long failedReads, long holds,
                          Double problemRate, long fixes, List<Week> weeks) {}

    @Schema(name = "ReadingQualityWeek")
    public record Week(LocalDate weekStart, long statementsRead, long rowsRead, long failedReads, long holds,
                       long fixes) {}

    /**
     * @param importHolds imports held because the reader failed ({@code HELD_FOR_REVIEW}), with no
     *                    bank named by any upload record of theirs
     * @param trustHolds  statements held for trust review whose upload record names no bank
     * @param fixes       fixes on either of those
     */
    @Schema(name = "ReadingQualityUnknownBank")
    public record Unknown(long importHolds, long trustHolds, long fixes) {}
}
