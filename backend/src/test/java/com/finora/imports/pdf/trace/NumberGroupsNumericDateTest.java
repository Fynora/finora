package com.finora.imports.pdf.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NumberGroups.isRealNumericDate} decides whether a run of digits with separators is a date
 * the redactor keeps. Its groups are read as one- or two-digit numbers without Integer.parseInt: the
 * pattern's digits are ASCII only and at most two, so nothing can overflow or fail to parse. These
 * pin the answers at every boundary, so the hand-written conversion is held to what parsing gave.
 */
class NumberGroupsNumericDateTest {

    @Test
    void dayMonthYearAndMonthDayYearBothCount_whenTheyCanBeADate() {
        assertThat(NumberGroups.isRealNumericDate("31/12/2026")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("12/31/2026")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("1/1/26")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("01-02-2026")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("07.06.2026")).isTrue();
    }

    @Test
    void neitherPartCanBeZeroOrPastItsRange() {
        assertThat(NumberGroups.isRealNumericDate("00/05/2026")).as("day zero").isFalse();
        assertThat(NumberGroups.isRealNumericDate("05/00/2026")).as("month zero").isFalse();
        assertThat(NumberGroups.isRealNumericDate("13/13/2026")).as("no month is 13").isFalse();
        assertThat(NumberGroups.isRealNumericDate("32/01/2026")).as("no day is 32").isFalse();
        assertThat(NumberGroups.isRealNumericDate("99/99/2026")).isFalse();
    }

    @Test
    void yearFirstNeedsARealMonthAndDay() {
        assertThat(NumberGroups.isRealNumericDate("2026-12-31")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("2026/1/9")).isTrue();
        assertThat(NumberGroups.isRealNumericDate("2026-13-01")).as("month 13").isFalse();
        assertThat(NumberGroups.isRealNumericDate("2026-00-10")).as("month zero").isFalse();
        assertThat(NumberGroups.isRealNumericDate("2026-12-32")).as("day 32").isFalse();
        assertThat(NumberGroups.isRealNumericDate("2026-12-00")).as("day zero").isFalse();
    }

    @Test
    void digitsOfAnotherScriptNeverMatch_soNothingReachesTheConversion() {
        // Devanagari 12/12/2026. Character.isDigit is true for these, the pattern's \d is not.
        assertThat(NumberGroups.isRealNumericDate("१२/१२/२०२६")).isFalse();
        assertThat(NumberGroups.isRealNumericDate("")).isFalse();
        assertThat(NumberGroups.isRealNumericDate("not a date")).isFalse();
    }
}
