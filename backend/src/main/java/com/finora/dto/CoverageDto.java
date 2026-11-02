package com.finora.dto;

import com.finora.imports.StatementCoverageAnalyzer.CoverageReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The wire shape for {@code GET /api/v1/accounts/{accountId}/coverage} and its admin sibling --
 * see docs/proposals/statement-continuity-and-coverage-integrity-proposal.md §9/§0.9/§0.24.
 * {@code coverageStatus} is a display convenience only; a real consumer branches on the boolean
 * flags, which are the authoritative contract.
 */
public record CoverageDto(UUID accountId, String coverageStatus, long coveredDays, long missingDays,
                           Double coveragePercentage, boolean hasGaps, boolean hasOverlaps,
                           boolean hasNonStandardPeriods, boolean hasDuplicatePeriods,
                           List<Segment> segments, List<Gap> gaps, List<Overlap> overlaps) {

    /** {@code approximate}: the statement printed no period, so this range is the first and last
     *  date of its own transactions -- the user-facing view only (AccountCoverageService.forAccount).
     *  False for every printed period, and always false on the admin view. */
    public record Segment(UUID statementImportId, LocalDate periodStart, LocalDate periodEnd, String classification,
                          boolean approximate) {}

    /** {@code delta} is null when either bounding statement's balance is unknown -- never guessed. */
    public record Gap(LocalDate gapStart, LocalDate gapEnd, long daysMissing, BigDecimal delta) {}

    public record Overlap(UUID segmentAId, UUID segmentBId, LocalDate overlapStart, LocalDate overlapEnd, String type) {}

    public static CoverageDto from(UUID accountId, CoverageReport report) {
        return from(accountId, report, Set.of());
    }

    /** @param approximateIds statements whose range came from their transactions, not a printed period. */
    public static CoverageDto from(UUID accountId, CoverageReport report, Set<UUID> approximateIds) {
        return new CoverageDto(
                accountId,
                report.coverageStatus(),
                report.coveredDays(),
                report.missingDays(),
                report.coveragePercentage(),
                report.hasGaps(),
                report.hasOverlaps(),
                report.hasNonStandardPeriods(),
                report.hasDuplicatePeriods(),
                report.segments().stream()
                        .map(s -> new Segment(s.statementImportId(), s.periodStart(), s.periodEnd(),
                                s.classification().name(), approximateIds.contains(s.statementImportId())))
                        .toList(),
                report.gaps().stream()
                        .map(g -> new Gap(g.gapStart(), g.gapEnd(), g.daysMissing(), g.delta()))
                        .toList(),
                report.overlaps().stream()
                        .map(o -> new Overlap(o.segmentAId(), o.segmentBId(), o.overlapStart(), o.overlapEnd(),
                                o.type().name()))
                        .toList());
    }
}
