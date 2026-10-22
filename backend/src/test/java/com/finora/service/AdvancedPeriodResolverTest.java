package com.finora.service;

import com.finora.dto.AdvancedCompare;
import com.finora.dto.AdvancedRange;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdvancedPeriodResolverTest {

    private static final YearMonth FIRST = YearMonth.of(2024, 1);
    private static final YearMonth END = YearMonth.of(2026, 9);

    private AdvancedPeriodResolver.Resolved resolve(AdvancedRange range, AdvancedCompare compare) {
        return AdvancedPeriodResolver.resolve(range, null, null, compare, FIRST, END, END);
    }

    @Test
    void aRollingWindowEndsAtTheAnchorAndComparesWithTheSameLengthBefore() {
        var r = resolve(AdvancedRange.LAST_6_MONTHS, null);

        assertThat(r.start()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(r.end()).isEqualTo(END);
        assertThat(r.months()).isEqualTo(6);
        assertThat(r.compare()).isEqualTo(AdvancedCompare.PREVIOUS_PERIOD);
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2025, 10));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    void aShortWindowCanBeComparedWithTheSameMonthsAYearEarlier() {
        var r = resolve(AdvancedRange.LAST_3_MONTHS, AdvancedCompare.SAME_PERIOD_LAST_YEAR);

        assertThat(r.compare()).isEqualTo(AdvancedCompare.SAME_PERIOD_LAST_YEAR);
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2025, 7));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2025, 9));
        assertThat(r.compareOptions()).containsExactly(AdvancedCompare.PREVIOUS_PERIOD, AdvancedCompare.SAME_PERIOD_LAST_YEAR);
    }

    @Test
    void twelveMonthsHasOneWayToCompareBecauseTheTwoWindowsAreTheSame() {
        var r = resolve(AdvancedRange.LAST_12_MONTHS, AdvancedCompare.SAME_PERIOD_LAST_YEAR);

        assertThat(r.compareOptions()).containsExactly(AdvancedCompare.PREVIOUS_PERIOD);
        assertThat(r.compare()).isEqualTo(AdvancedCompare.PREVIOUS_PERIOD);
        assertThat(r.start()).isEqualTo(YearMonth.of(2025, 10));
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2024, 10));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2025, 9));
    }

    @Test
    void thisYearRunsFromJanuaryToTheAnchorAndComparesWithTheSameMonthsLastYear() {
        var r = resolve(AdvancedRange.THIS_YEAR, null);

        assertThat(r.start()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(r.end()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(r.months()).isEqualTo(9);
        assertThat(r.compare()).isEqualTo(AdvancedCompare.SAME_PERIOD_LAST_YEAR);
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2025, 9));
        assertThat(r.compareOptions()).containsExactly(AdvancedCompare.SAME_PERIOD_LAST_YEAR);
    }

    @Test
    void thisYearIsRelativeToTheAnchorNotToTheClock() {
        // Last whole month is December: "this year" is that year, not the year it is now.
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.THIS_YEAR, null, null, null,
                FIRST, YearMonth.of(2025, 12), YearMonth.of(2025, 12));

        assertThat(r.start()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(r.end()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(r.months()).isEqualTo(12);
    }

    @Test
    void lastYearIsTheCalendarYearBeforeTheAnchorsYearAndComparesWithTheOneBefore() {
        var r = resolve(AdvancedRange.LAST_YEAR, null);

        assertThat(r.start()).isEqualTo(YearMonth.of(2025, 1));
        assertThat(r.end()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(r.months()).isEqualTo(12);
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2024, 1));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2024, 12));
    }

    @Test
    void lastYearWithNoHistoryInThatYearHasNoMonths() {
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.LAST_YEAR, null, null, null,
                YearMonth.of(2026, 3), YearMonth.of(2026, 9), YearMonth.of(2026, 9));

        assertThat(r.months()).isZero();
    }

    @Test
    void allTimeStartsAtTheFirstMonthWithDataAndHasNothingToCompareWith() {
        var r = resolve(AdvancedRange.ALL_TIME, AdvancedCompare.PREVIOUS_PERIOD);

        assertThat(r.start()).isEqualTo(FIRST);
        assertThat(r.end()).isEqualTo(END);
        assertThat(r.months()).isEqualTo(33);
        assertThat(r.compare()).isNull();
        assertThat(r.previousStart()).isNull();
        assertThat(r.compareOptions()).isEmpty();
    }

    @Test
    void aWindowLongerThanTheHistoryStartsWhereTheHistoryDoes() {
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.LAST_12_MONTHS, null, null, null,
                YearMonth.of(2026, 5), END, END);

        assertThat(r.start()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(r.months()).isEqualTo(5);
        assertThat(r.requestedMonths()).isEqualTo(12);
        // The comparison window is the same length as the months that exist, and it reaches back
        // before the history: the service decides that makes it not comparable.
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2025, 12));
    }

    @Test
    void aCustomRunOfMonthsIsKeptAsAskedAndClampedToTheHistory() {
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, YearMonth.of(2023, 6), YearMonth.of(2026, 12),
                null, FIRST, END, END);

        assertThat(r.start()).isEqualTo(FIRST);
        assertThat(r.end()).isEqualTo(END);
    }

    @Test
    void aCustomRunMayEndInTheNewestMonthEvenWhenThatMonthIsNotWhole() {
        // anchor (last whole month) is August; the user asked for September explicitly.
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, YearMonth.of(2026, 7), YearMonth.of(2026, 9),
                null, FIRST, END, YearMonth.of(2026, 8));

        assertThat(r.end()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(r.months()).isEqualTo(3);
    }

    @Test
    void aCustomRunNeedsBothEndsInOrder() {
        assertThatThrownBy(() -> AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, null, END, null, FIRST, END, END))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, END, YearMonth.of(2026, 1), null, FIRST, END, END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCustomRunLongerThanTenYearsIsRefused() {
        assertThatThrownBy(() -> AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, YearMonth.of(2010, 1), END, null,
                YearMonth.of(2010, 1), END, END)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCustomRunOverAYearOnlyComparesWithTheRunBeforeIt() {
        var r = AdvancedPeriodResolver.resolve(AdvancedRange.CUSTOM, YearMonth.of(2025, 1), YearMonth.of(2026, 6),
                AdvancedCompare.SAME_PERIOD_LAST_YEAR, FIRST, END, END);

        assertThat(r.months()).isEqualTo(18);
        assertThat(r.compareOptions()).isEqualTo(List.of(AdvancedCompare.PREVIOUS_PERIOD));
        assertThat(r.previousEnd()).isEqualTo(YearMonth.of(2024, 12));
        assertThat(r.previousStart()).isEqualTo(YearMonth.of(2023, 7));
    }
}
