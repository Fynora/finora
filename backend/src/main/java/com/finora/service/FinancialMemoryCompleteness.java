package com.finora.service;

import com.finora.imports.StatementCoverageAnalyzer;
import com.finora.imports.StatementCoverageAnalyzer.StatementPeriod;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Financial Memory Completeness Dashboard (issue #1450) -- "gap coverage + freshness", the
 * formula decided for this feature over the simpler alternatives (see the issue for the other
 * two considered). Aggregates {@link StatementCoverageAnalyzer}'s existing per-account gap
 * detection across every one of a user's accounts, then adds one thing that analyzer
 * deliberately doesn't do on its own: it never looks past the last statement it was given, so an
 * account that stopped importing six months ago reads as "fully covered" by coveragePercentage
 * alone. The freshness gap below is exactly that missing piece -- the span from each account's
 * last known statement to {@code today} counts as missing days, same as a gap in the middle
 * would.
 *
 * <p>An account with zero statement periods is excluded from the calculation entirely rather
 * than treated as 100% or 0% missing -- there is no timeline to measure completeness against
 * for it, the same "null when there's nothing to compute over" discipline
 * {@code WorkspaceDashboardService.summarize}'s {@code categorizationAccuracy} already follows.
 */
public final class FinancialMemoryCompleteness {

    private FinancialMemoryCompleteness() {}

    public record Result(Long monthsOfHistory, Integer completenessPercent) {}

    public static Result compute(Map<UUID, List<StatementPeriod>> periodsByAccount, LocalDate today) {
        long totalCoveredDays = 0;
        long totalMissingDays = 0;
        LocalDate earliestStart = null;
        boolean anyData = false;

        for (List<StatementPeriod> periods : periodsByAccount.values()) {
            if (periods.isEmpty()) continue;
            anyData = true;

            var report = StatementCoverageAnalyzer.analyze(periods);
            totalCoveredDays += report.coveredDays();
            totalMissingDays += report.missingDays();

            LocalDate accountStart = periods.stream().map(StatementPeriod::periodStart)
                    .min(LocalDate::compareTo).orElseThrow();
            if (earliestStart == null || accountStart.isBefore(earliestStart)) {
                earliestStart = accountStart;
            }

            LocalDate accountLastEnd = periods.stream().map(StatementPeriod::periodEnd)
                    .max(LocalDate::compareTo).orElseThrow();
            long freshnessGapDays = ChronoUnit.DAYS.between(accountLastEnd, today);
            if (freshnessGapDays > 0) {
                totalMissingDays += freshnessGapDays;
            }
        }

        if (!anyData) {
            return new Result(null, null);
        }

        long totalDays = totalCoveredDays + totalMissingDays;
        Integer completenessPercent = totalDays == 0 ? null
                : (int) Math.round((totalCoveredDays * 100.0) / totalDays);

        long monthsOfHistory = ChronoUnit.MONTHS.between(YearMonth.from(earliestStart), YearMonth.from(today)) + 1;

        return new Result(monthsOfHistory, completenessPercent);
    }
}
