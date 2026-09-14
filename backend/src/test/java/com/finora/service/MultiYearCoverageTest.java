package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiYearCoverageTest {

    private static AccountCoverageService.DateRange gap(String start, String end) {
        return new AccountCoverageService.DateRange(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void isComplete_isFalse_forTheCurrentMonth_evenWithNoGap() {
        YearMonth current = YearMonth.of(2026, 3);
        assertThat(MultiYearCoverage.isComplete(current, YearMonth.of(2020, 1), current, List.of())).isFalse();
    }

    @Test
    void isComplete_isFalse_beforeTheUsersFirstDataMonth() {
        YearMonth firstData = YearMonth.of(2025, 6);
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2025, 5), firstData, YearMonth.of(2026, 3), List.of()))
                .isFalse();
    }

    @Test
    void isComplete_isFalse_whenAGapOverlapsTheMonth() {
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-02-10", "2026-02-20"));
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2026, 2), YearMonth.of(2020, 1), YearMonth.of(2026, 3), gaps))
                .isFalse();
    }

    @Test
    void isComplete_isTrue_forAnElapsedMonthWithNoGapAfterFirstData() {
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2026, 1), YearMonth.of(2020, 1), YearMonth.of(2026, 3), List.of()))
                .isTrue();
    }

    @Test
    void yearCoverages_marksAFullYearComplete_andAPartialYearWithItsRealCount() {
        // First data January 2025; current month March 2026 (so 2026 is inherently partial: only
        // Jan/Feb 2026 can even be "elapsed", and 2025 is a fully-elapsed candidate for 12/12).
        List<MultiYearCoverage.YearCoverage> years = MultiYearCoverage.yearCoverages(
                YearMonth.of(2025, 1), YearMonth.of(2026, 3), List.of());

        assertThat(years).containsExactly(
                new MultiYearCoverage.YearCoverage(2025, 12, true),
                new MultiYearCoverage.YearCoverage(2026, 2, false));
    }

    @Test
    void yearCoverages_countsAMidYearGapAgainstThatYearsCoverage() {
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2025-07-01", "2025-07-31"));
        List<MultiYearCoverage.YearCoverage> years = MultiYearCoverage.yearCoverages(
                YearMonth.of(2025, 1), YearMonth.of(2026, 1), gaps);

        assertThat(years).contains(new MultiYearCoverage.YearCoverage(2025, 11, false));
    }

    @Test
    void thisYearWindow_startsAtJanuary_whenTheUserHasPriorYearHistory() {
        // Current month is March 2026 (Jan/Feb complete, March itself never complete).
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2024, 6), YearMonth.of(2026, 3), List.of());

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2)));
    }

    @Test
    void thisYearWindow_startsAtTheUsersFirstDataMonth_forAMidYearJoiner() {
        // User's very first transaction was June 2026; it is now August 2026 (June, July elapsed).
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2026, 6), YearMonth.of(2026, 8), List.of());

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 6), YearMonth.of(2026, 7)));
    }

    @Test
    void thisYearWindow_stopsAtAGap_ratherThanSkippingPastIt() {
        // February's statement is missing; the window must stop at January, not skip to March.
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-02-01", "2026-02-28"));
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2020, 1), YearMonth.of(2026, 4), gaps);

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 1)));
    }

    @Test
    void thisYearWindow_isEmpty_whenNotEvenTheFirstEligibleMonthIsComplete() {
        // It's early January and January itself hasn't been imported/completed yet.
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-01-01", "2026-01-31"));
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2020, 1), YearMonth.of(2026, 2), gaps);

        assertThat(window).isEmpty();
    }

    @Test
    void coversSameRelativeWindow_isTrue_whenThePriorYearFullyCoversTheSameMonths() {
        MultiYearCoverage.ThisYearWindow window = new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(MultiYearCoverage.coversSameRelativeWindow(2025, window, YearMonth.of(2020, 1), YearMonth.of(2026, 3), List.of()))
                .isTrue();
    }

    @Test
    void coversSameRelativeWindow_isFalse_whenThePriorYearHasNoDataForThatWindowAtAll() {
        // User's history only starts April 2025 -- 2025's Jan-Feb never existed.
        MultiYearCoverage.ThisYearWindow window = new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(MultiYearCoverage.coversSameRelativeWindow(2025, window, YearMonth.of(2025, 4), YearMonth.of(2026, 3), List.of()))
                .isFalse();
    }
}
