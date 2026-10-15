package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthComparisonGateTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 9, 30);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth JUL = YearMonth.of(2026, 7);

    private static String reason(LocalDate earliest, LocalDate latest, List<AccountCoverageService.DateRange> gaps,
                                 int selectedCount, int priorCount) {
        return MonthComparisonGate.reason(AUG, JUL, earliest, latest, gaps, selectedCount, priorCount);
    }

    // --- the partial-boundary rule (moved here from DashboardService unchanged) ---

    @Test
    void earliestBucketIsPartialUnlessItStartsOnTheFirst() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-01", LocalDate.of(2026, 1, 2), LAST)).isTrue();
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-01", LocalDate.of(2026, 1, 1), LAST)).isFalse();
    }

    @Test
    void latestBucketIsPartialUnlessItRunsToMonthEnd() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-09", FIRST, LocalDate.of(2026, 9, 29))).isTrue();
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-09", FIRST, LocalDate.of(2026, 9, 30))).isFalse();
        // February in a non-leap year ends on the 28th, not the 30th or 31st.
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-02", FIRST, LocalDate.of(2026, 2, 28))).isFalse();
    }

    @Test
    void aMonthInTheMiddleOfHistoryIsNeverABoundaryMonth() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-05", LocalDate.of(2026, 1, 17), LocalDate.of(2026, 9, 3)))
                .isFalse();
    }

    // --- statement gaps: inclusive bounds ---

    @Test
    void aGapTouchingAMonthsFirstOrLastDayOverlapsIt_aGapEndingTheDayBeforeDoesNot() {
        var endsOnAugFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 8, 1)));
        var endsJulThirtyFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 31)));
        var startsOnAugLast = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 5)));
        var startsSepFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5)));

        assertThat(MonthComparisonGate.overlapsGap(AUG, endsOnAugFirst)).isTrue();
        assertThat(MonthComparisonGate.overlapsGap(AUG, endsJulThirtyFirst)).isFalse();
        assertThat(MonthComparisonGate.overlapsGap(AUG, startsOnAugLast)).isTrue();
        assertThat(MonthComparisonGate.overlapsGap(AUG, startsSepFirst)).isFalse();
    }

    // --- the combined decision ---

    @Test
    void comparableMonthsHaveNoReason() {
        assertThat(reason(FIRST, LAST, List.of(), 5, 5)).isNull();
    }

    @Test
    void theThreeTransactionFloorIsInclusive() {
        assertThat(reason(FIRST, LAST, List.of(), 5, 3)).isNull();
        assertThat(reason(FIRST, LAST, List.of(), 5, 2)).isEqualTo("TOO_FEW_PRIOR_TRANSACTIONS");
    }

    @Test
    void noHistoryAtAllIsTheFirstThingSaid() {
        assertThat(reason(null, null, List.of(), 0, 0)).isEqualTo("NO_TRANSACTION_HISTORY");
    }

    @Test
    void aSelectedMonthWithNoTransactionsIsNeverCompared() {
        assertThat(reason(FIRST, LAST, List.of(), 0, 9)).isEqualTo("NO_TRANSACTIONS_IN_MONTH");
    }

    @Test
    void aPriorMonthEndingBeforeHistoryBeganIsReportedAsThat_notAsTooFewTransactions() {
        assertThat(reason(LocalDate.of(2026, 8, 1), LAST, List.of(), 5, 0)).isEqualTo("PRIOR_BEFORE_HISTORY");
    }

    @Test
    void whenSeveralThingsAreWrongTheMostFundamentalIsReported() {
        // Selected month is the ragged latest bucket AND the prior is thin AND there is a gap:
        // the partial selected month is what a reader needs to hear first.
        var gap = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 9)));
        assertThat(MonthComparisonGate.reason(YearMonth.of(2026, 9), AUG, FIRST, LocalDate.of(2026, 9, 12), gap, 4, 1))
                .isEqualTo("SELECTED_MONTH_PARTIAL");
    }
}
