package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

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

    // -- Grace: two days off each end before the month rule ----------------------------------------

    private static boolean withGrace(LocalDate start, LocalDate end) {
        return FreeStatementPeriod.withinFreeLimit(start, end);
    }

    @Test
    void twoDaysOfGraceAtEachEnd_theOwnersExample() {
        // 5 Jan to 5 Feb is one month, so 3 Jan to 7 Feb is allowed.
        assertThat(withGrace(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 7))).isTrue();
        // One more day at either end is not.
        assertThat(withGrace(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 7))).isFalse();
        assertThat(withGrace(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 8))).isFalse();
    }

    @Test
    void graceAppliesToEveryShape_theLimitIsTheMonthRuleWithFourMoreDays() {
        for (LocalDate start = LocalDate.of(2026, 1, 1); start.isBefore(LocalDate.of(2029, 1, 1)); start = start.plusDays(1)) {
            for (int days = 1; days <= 80; days++) {
                LocalDate end = start.plusDays(days - 1);
                boolean expected = days <= 4 || within(start.plusDays(2), end.minusDays(2));
                assertThat(withGrace(start, end)).as("%s +%d", start, days).isEqualTo(expected);
                assertThat(withGrace(end, start)).as("reversed %s +%d", start, days).isEqualTo(expected);
                if (days <= 35) assertThat(withGrace(start, end)).as("%s +%d", start, days).isTrue();
                if (days > 36) assertThat(withGrace(start, end)).as("%s +%d", start, days).isFalse();
            }
        }
    }

    @Test
    void graceNeverRefusesWhatTheOldRuleAllowed() {
        for (LocalDate start = LocalDate.of(2026, 1, 1); start.isBefore(LocalDate.of(2029, 1, 1)); start = start.plusDays(1)) {
            for (int days = 1; days <= 80; days++) {
                LocalDate end = start.plusDays(days - 1);
                if (within(start, end)) assertThat(withGrace(start, end)).as("%s +%d", start, days).isTrue();
            }
        }
    }

    // -- Printed period AND every transaction date --------------------------------------------------

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void aStatementWithNoPrintedPeriod_isJudgedByItsTransactions() {
        assertThat(FreeStatementPeriod.firstExcess(null, null, List.of(d(1, 5), d(2, 5)))).isNull();
        FreeStatementPeriod.Excess excess = FreeStatementPeriod.firstExcess(null, null, List.of(d(1, 5), d(3, 5)));
        assertThat(excess).isEqualTo(new FreeStatementPeriod.Excess(d(1, 5), d(3, 5), true));
    }

    @Test
    void theEarliestAndLatestDatesCount_notTheFirstAndLastRow() {
        // Rows out of order, the long span hidden in the middle: editing the last row's date to fit
        // must not pass.
        List<LocalDate> rows = List.of(d(1, 5), d(3, 5), d(1, 20), d(2, 5));
        assertThat(FreeStatementPeriod.firstExcess(null, null, rows))
                .isEqualTo(new FreeStatementPeriod.Excess(d(1, 5), d(3, 5), true));
    }

    @Test
    void aPrintedPeriodThatFits_doesNotExcuseTransactionsThatDoNot() {
        // The printed period edited to one month; the rows still cover two.
        assertThat(FreeStatementPeriod.firstExcess(d(1, 5), d(2, 5), List.of(d(1, 5), d(3, 5))))
                .isEqualTo(new FreeStatementPeriod.Excess(d(1, 5), d(3, 5), true));
    }

    @Test
    void aPrintedPeriodThatIsTooLong_isRefusedEvenWithFewTransactions() {
        // A quiet account: three months printed, one week of activity. The statement still covers
        // three months.
        assertThat(FreeStatementPeriod.firstExcess(d(3, 31), d(1, 1), List.of(d(2, 1), d(2, 7))))
                .isEqualTo(new FreeStatementPeriod.Excess(d(1, 1), d(3, 31), false));
    }

    @Test
    void transactionsJustOutsideTheirPrintedPeriod_stillFit() {
        // A card row dated two days before its cycle, as measured on real statements.
        assertThat(FreeStatementPeriod.firstExcess(d(1, 5), d(2, 5), List.of(d(1, 3), d(2, 5)))).isNull();
    }

    @Test
    void halfAPrintedPeriod_isNotJudged_butTheTransactionsAre() {
        assertThat(FreeStatementPeriod.firstExcess(d(1, 1), null, List.of(d(1, 5), d(1, 20)))).isNull();
        assertThat(FreeStatementPeriod.firstExcess(null, d(4, 1), List.of(d(1, 5), d(3, 5)))).isNotNull();
    }

    @Test
    void nothingToJudge_fits() {
        assertThat(FreeStatementPeriod.firstExcess(null, null, List.of())).isNull();
        assertThat(FreeStatementPeriod.firstExcess(null, null, null)).isNull();
        assertThat(FreeStatementPeriod.firstExcess(null, null, java.util.Arrays.asList(null, d(1, 1)))).isNull();
        assertThat(FreeStatementPeriod.firstExcess(null, null, List.of(d(5, 10)))).isNull();
    }
}
