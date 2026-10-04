package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every format {@link ReceiptDateFormats} knows refuses a day the month does not have. Before these
 * were built STRICT, each of the five {@code ofPattern} formats moved such a day to the month's
 * last day ("Feb 30, 2026" read as 28 February), which stages a receipt a day or two off with
 * nothing to say the date was guessed.
 */
class ReceiptDateFormatsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Feb 30, 2026", "Apr 31, 2026",
            "February 30, 2026", "June 31, 2026",
            "2026-02-30", "2026-04-31",
            "30 February 2026", "31 September 2026",
            "31/04/2026", "29/02/2026",
            "31-06-2026", "30-02-2026"})
    @DisplayName("a day the month does not have is refused in every format, not moved to the month's end")
    void refusesAnImpossibleDayInEveryFormat(String impossible) {
        assertThat(ReceiptDateFormats.tryParse(impossible)).isNull();
    }

    @Test
    @DisplayName("the last real day of each month still parses in every format")
    void readsTheLastRealDayOfAMonth() {
        assertThat(ReceiptDateFormats.tryParse("Feb 28, 2026")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ReceiptDateFormats.tryParse("April 30, 2026")).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(ReceiptDateFormats.tryParse("2026-12-31")).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(ReceiptDateFormats.tryParse("31 January 2026")).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(ReceiptDateFormats.tryParse("30/04/2026")).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(ReceiptDateFormats.tryParse("30-06-2026")).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("29 February parses in a leap year and is refused in any other")
    void leapDayFollowsTheCalendar() {
        assertThat(ReceiptDateFormats.tryParse("29/02/2028")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ReceiptDateFormats.tryParse("Feb 29, 2028")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ReceiptDateFormats.tryParse("29 February 2027")).isNull();
        // 1900 is divisible by 4 but not a leap year.
        assertThat(ReceiptDateFormats.tryParse("29-02-1900")).isNull();
    }

    @Test
    @DisplayName("year 0 and a signed year are refused, as they were before")
    void refusesYearZeroAndSignedYears() {
        assertThat(ReceiptDateFormats.tryParse("01/01/0000")).isNull();
        assertThat(ReceiptDateFormats.tryParse("Jan 1, 0000")).isNull();
        assertThat(ReceiptDateFormats.tryParse("01/01/-2026")).isNull();
        assertThat(ReceiptDateFormats.tryParse("01/01/+2026")).isNull();
    }

    @Test
    @DisplayName("surrounding whitespace is still stripped and null is still null")
    void whitespaceAndNull() {
        assertThat(ReceiptDateFormats.tryParse("  12/08/2026 \n")).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(ReceiptDateFormats.tryParse(null)).isNull();
        assertThat(ReceiptDateFormats.tryParse("")).isNull();
    }
}
