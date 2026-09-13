package com.finora.service;

import com.finora.imports.StatementCoverageAnalyzer.StatementPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialMemoryCompletenessTest {

    private static StatementPeriod period(LocalDate start, LocalDate end) {
        return new StatementPeriod(UUID.randomUUID(), start, end, null, null);
    }

    @Test
    void compute_withNoAccountsAtAll_returnsNullForBoth() {
        var result = FinancialMemoryCompleteness.compute(Map.of(), LocalDate.of(2026, 4, 1));

        assertThat(result.monthsOfHistory()).isNull();
        assertThat(result.completenessPercent()).isNull();
    }

    @Test
    void compute_accountWithNoPeriods_isExcludedRatherThanZeroingTheResult() {
        UUID emptyAccount = UUID.randomUUID();
        UUID realAccount = UUID.randomUUID();
        var periodsByAccount = Map.of(
                emptyAccount, List.<StatementPeriod>of(),
                realAccount, List.of(period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))));

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, LocalDate.of(2026, 4, 1));

        assertThat(result.completenessPercent()).isEqualTo(100);
        assertThat(result.monthsOfHistory()).isEqualTo(4L);
    }

    @Test
    void compute_singleAccountCoveredThroughToday_isFullyComplete() {
        UUID account = UUID.randomUUID();
        var periodsByAccount = Map.of(account,
                List.of(period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))));

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, LocalDate.of(2026, 4, 1));

        assertThat(result.completenessPercent()).isEqualTo(100);
        assertThat(result.monthsOfHistory()).isEqualTo(4L); // Jan, Feb, Mar, Apr inclusive
    }

    @Test
    void compute_lastStatementIsStale_freshnessGapReducesCompletenessEvenWithNoInternalGaps() {
        UUID account = UUID.randomUUID();
        // One clean segment, no internal gap -- but "today" is 60 days past its end.
        var periodsByAccount = Map.of(account,
                List.of(period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))));

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, LocalDate.of(2026, 4, 1));

        // covered=31, missing=60 (days between Jan 31 and Apr 1) -> round(100*31/91) = 34
        assertThat(result.completenessPercent()).isEqualTo(34);
        assertThat(result.monthsOfHistory()).isEqualTo(4L);
    }

    @Test
    void compute_internalGapBetweenStatements_reducesCompletenessPercent() {
        UUID account = UUID.randomUUID();
        var periodsByAccount = Map.of(account, List.of(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))));
        // Gap: Feb 1 - Feb 28 (28 days missing). Report as of the last statement's own end date,
        // so freshness contributes nothing here -- isolates the internal-gap effect.
        var today = LocalDate.of(2026, 3, 31);

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, today);

        // covered=31+31=62, missing=28 -> round(100*62/90) = 69
        assertThat(result.completenessPercent()).isEqualTo(69);
        assertThat(result.monthsOfHistory()).isEqualTo(3L); // Jan, Feb, Mar
    }

    @Test
    void compute_aggregatesCoveredAndMissingDaysAcrossMultipleAccountsIndependently() {
        UUID fullyFresh = UUID.randomUUID();
        UUID hasInternalGap = UUID.randomUUID();
        var today = LocalDate.of(2026, 4, 1);
        var periodsByAccount = Map.of(
                fullyFresh, List.of(period(LocalDate.of(2026, 1, 1), today)),
                hasInternalGap, List.of(
                        period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15)),
                        period(LocalDate.of(2026, 2, 1), today)));

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, today);

        // fullyFresh: covered=91, missing=0. hasInternalGap: covered=15+60=75, missing=16 (Jan16-Jan31).
        // Aggregate: covered=166, missing=16, total=182 -> round(100*166/182) = 91
        assertThat(result.completenessPercent()).isEqualTo(91);
        assertThat(result.monthsOfHistory()).isEqualTo(4L);
    }

    @Test
    void compute_monthsOfHistory_usesTheEarliestStartAcrossAllAccounts_notJustOne() {
        UUID recentAccount = UUID.randomUUID();
        UUID olderAccount = UUID.randomUUID();
        var today = LocalDate.of(2026, 4, 1);
        var periodsByAccount = Map.of(
                recentAccount, List.of(period(LocalDate.of(2026, 1, 1), today)),
                olderAccount, List.of(period(LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30))));

        var result = FinancialMemoryCompleteness.compute(periodsByAccount, today);

        // Earliest month is Nov 2025; today is Apr 2026 -> Nov, Dec, Jan, Feb, Mar, Apr = 6 months
        assertThat(result.monthsOfHistory()).isEqualTo(6L);
    }
}
