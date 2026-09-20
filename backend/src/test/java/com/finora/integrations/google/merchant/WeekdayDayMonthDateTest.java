package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Some merchants print an order date as weekday + day + month with no year ("Mon, 13 Jul"). The
 * weekday is what makes the year recoverable: only one year in any three-year window puts that day
 * and month on that weekday. These tests pin that the year comes from the weekday, not from the
 * clock, and that a date the weekday cannot confirm is refused rather than guessed.
 */
class WeekdayDayMonthDateTest {

    @Test
    @DisplayName("the weekday picks the year, so an email received on the day resolves to that year")
    void resolvesTheYearFromTheWeekday() {
        // 13 July 2026 is a Monday.
        Optional<LocalDate> date = WeekdayDayMonthDate.resolve("Mon, 13 Jul", LocalDate.of(2026, 7, 13));

        assertThat(date).contains(LocalDate.of(2026, 7, 13));
    }

    @Test
    @DisplayName("a December date read in early January belongs to the previous year")
    void aDecemberDateSeenInJanuaryBelongsToLastYear() {
        // 26 December 2025 is a Friday; the email is processed on 2 January 2026.
        Optional<LocalDate> date = WeekdayDayMonthDate.resolve("Fri, 26 Dec", LocalDate.of(2026, 1, 2));

        assertThat(date).contains(LocalDate.of(2025, 12, 26));
    }

    @Test
    @DisplayName("a January date read in late December belongs to the next year")
    void aJanuaryDateSeenInDecemberBelongsToNextYear() {
        // 2 January 2026 is a Friday.
        Optional<LocalDate> date = WeekdayDayMonthDate.resolve("Fri, 02 Jan", LocalDate.of(2025, 12, 30));

        assertThat(date).contains(LocalDate.of(2026, 1, 2));
    }

    @Test
    @DisplayName("ordinal suffixes, full weekday names and full month names are all read")
    void readsOrdinalsAndFullNames() {
        assertThat(WeekdayDayMonthDate.resolve("Monday, 13th July", LocalDate.of(2026, 7, 14)))
                .contains(LocalDate.of(2026, 7, 13));
        assertThat(WeekdayDayMonthDate.resolve("Sun, 19th Jul", LocalDate.of(2026, 7, 14)))
                .contains(LocalDate.of(2026, 7, 19));
    }

    @Test
    @DisplayName("a weekday that does not fit the year of arrival is refused, not guessed")
    void refusesADateTheWeekdayCannotConfirm() {
        // 13 July 2026 is a Monday, so "Tue, 13 Jul" is not 2026. It IS a Tuesday in 2027, which
        // is a year from arrival, so it is refused for being too far away -- see the next test.
        assertThat(WeekdayDayMonthDate.resolve("Tue, 13 Jul", LocalDate.of(2026, 7, 13))).isEmpty();
        // No year near this arrival day puts 14 July on a Sunday at all.
        assertThat(WeekdayDayMonthDate.resolve("Sun, 14 Jul", LocalDate.of(2026, 7, 14))).isEmpty();
    }

    @Test
    @DisplayName("a date that fits the weekday but is a year from arrival is refused")
    void refusesASameWeekdayDateAYearAway() {
        // 13 July 2027 is a Tuesday, so the weekday fits; a confirmation is never dated a year out.
        assertThat(WeekdayDayMonthDate.resolve("Tue, 13 Jul", LocalDate.of(2026, 7, 13))).isEmpty();
        // The same date IS accepted when the email arrived near it.
        assertThat(WeekdayDayMonthDate.resolve("Tue, 13 Jul", LocalDate.of(2027, 7, 12)))
                .contains(LocalDate.of(2027, 7, 13));
    }

    @Test
    @DisplayName("a date thirty-one days from arrival is accepted and thirty-two is refused")
    void theDistanceBoundIsExact() {
        // 13 July 2026 is a Monday. Arrival 31 days later is 13 August; 32 days later is 14 August.
        assertThat(WeekdayDayMonthDate.resolve("Mon, 13 Jul", LocalDate.of(2026, 8, 13)))
                .contains(LocalDate.of(2026, 7, 13));
        assertThat(WeekdayDayMonthDate.resolve("Mon, 13 Jul", LocalDate.of(2026, 8, 14))).isEmpty();
    }

    @Test
    @DisplayName("an impossible calendar date is refused")
    void refusesAnImpossibleDate() {
        assertThat(WeekdayDayMonthDate.resolve("Mon, 31 Feb", LocalDate.of(2026, 2, 28))).isEmpty();
    }

    @Test
    @DisplayName("text with no weekday-day-month is refused")
    void refusesTextWithNoDate() {
        assertThat(WeekdayDayMonthDate.resolve("Your order is confirmed", LocalDate.of(2026, 7, 13))).isEmpty();
        assertThat(WeekdayDayMonthDate.resolve(null, LocalDate.of(2026, 7, 13))).isEmpty();
    }

    @Test
    @DisplayName("with no reference date the current date is used")
    void usesTodayWhenThereIsNoReferenceDate() {
        LocalDate today = LocalDate.now();
        String text = today.getDayOfWeek().name().substring(0, 3) + ", " + today.getDayOfMonth() + " "
                + today.getMonth().name().substring(0, 3);

        assertThat(WeekdayDayMonthDate.resolve(text, null)).contains(today);
    }
}
