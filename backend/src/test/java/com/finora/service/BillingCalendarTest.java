package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BillingCalendarTest {

    @Test
    void midnightInIndiaIsTheIndiaDayNotThePreviousUtcDay() {
        // 18:30 UTC on the 1st is exactly 00:00 on the 2nd in India -- where Razorpay periods end.
        Instant indiaMidnight = Instant.parse("2030-01-01T18:30:00Z");

        assertThat(BillingCalendar.dateOf(indiaMidnight)).isEqualTo(LocalDate.of(2030, 1, 2));
    }

    @Test
    void oneSecondBeforeIndiaMidnightIsStillThePreviousDay() {
        assertThat(BillingCalendar.dateOf(Instant.parse("2030-01-01T18:29:59Z")))
                .isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    void startOfADayIsIndiaMidnightAndRoundTripsThroughDateOf() {
        LocalDate day = LocalDate.of(2030, 1, 2);

        assertThat(BillingCalendar.startOf(day)).isEqualTo(Instant.parse("2030-01-01T18:30:00Z"));
        assertThat(BillingCalendar.dateOf(BillingCalendar.startOf(day))).isEqualTo(day);
    }
}
