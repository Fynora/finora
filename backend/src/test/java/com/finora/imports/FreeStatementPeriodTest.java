package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FreeStatementPeriodTest {

    private static boolean within(LocalDate start, LocalDate end) {
        return FreeStatementPeriod.coversAtMostOneMonth(start, end);
    }

    @Test
    void aCalendarMonthIsAllowed() {
        assertThat(within(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(within(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))).isTrue();
    }

    @Test
    void endingOnTheSameDateNextMonthIsAllowed() {
        // 32 days counted inclusively -- refused by the old flat 31-day count.
        assertThat(within(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))).isTrue();
        assertThat(within(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15))).isTrue();
    }

    @Test
    void oneDayPastTheSameDateNextMonthIsRefused() {
        assertThat(within(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 2))).isFalse();
        assertThat(within(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 16))).isFalse();
    }

    @Test
    void aPeriodOpeningOnAMonthsLastDay_runsToTheLastDayOfTheNextMonth() {
        // The shape that was refused: the period opens on the closing day of a 30-day month and
        // ends on the last day of the next, 31-day month.
        assertThat(within(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31))).isTrue();
        assertThat(within(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 8, 1))).isFalse();
        assertThat(within(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(within(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 4, 1))).isFalse();
    }

    @Test
    void aMonthEndStartIntoAShorterMonth_stopsAtThatMonthsLastDay() {
        assertThat(within(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(within(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29))).isTrue();
        // Past that month end, only the 31-day floor still lets a period through.
        assertThat(within(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 2))).isTrue();
        assertThat(within(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 3))).isFalse();
    }

    @Test
    void neverStricterThanThirtyOneDays_whenTheNextMonthIsShorter() {
        // A month after Jan 30 clamps to Feb 28, two days short of 31 -- the old count allowed
        // these, so they stay allowed.
        assertThat(within(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 3, 1))).isTrue();
        assertThat(within(LocalDate.of(2026, 1, 29), LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(within(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 3, 2))).isFalse();
    }

    @Test
    void aDayInTheMiddleOfAMonthDoesNotSnapToMonthEnd() {
        // Feb 28 is not the last day of February in a leap year, so its limit is Mar 28 (or the
        // 31-day floor, Mar 29), not Mar 31.
        assertThat(within(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 29))).isTrue();
        assertThat(within(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 30))).isFalse();
    }

    @Test
    void aYearEndPeriodRollsIntoTheNextYear() {
        assertThat(within(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31))).isTrue();
        assertThat(within(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 2, 1))).isFalse();
        assertThat(within(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 15))).isTrue();
    }

    @Test
    void multiMonthPeriodsAreRefused() {
        assertThat(within(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))).isFalse();
        assertThat(within(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))).isFalse();
    }

    @Test
    void everyStartDay_allowsUpTo31Days_andNeverMoreThan32() {
        for (LocalDate start = LocalDate.of(2026, 1, 1); start.isBefore(LocalDate.of(2030, 1, 1)); start = start.plusDays(1)) {
            for (int days = 1; days <= 70; days++) {
                LocalDate end = start.plusDays(days - 1);
                boolean allowed = within(start, end);
                assertThat(within(end, start)).as("reversed %s +%d", start, days).isEqualTo(allowed);
                if (days <= 31) assertThat(allowed).as("%s +%d", start, days).isTrue();
                if (days > 32) assertThat(allowed).as("%s +%d", start, days).isFalse();
            }
        }
    }

    @Test
    void aSingleDayIsAllowed() {
        assertThat(within(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10))).isTrue();
    }

    @Test
    void aReversedPeriodIsJudgedByItsRealLength() {
        assertThat(within(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1))).isFalse();
        assertThat(within(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))).isTrue();
    }
}
