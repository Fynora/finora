package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingDayTest {

    @Test
    void todayUsesReportingZoneNotUtc() {
        // 18:30 UTC is midnight in India: a minute either side lands on different days.
        assertThat(new ReportingDay(Clock.fixed(Instant.parse("2026-10-16T18:29:00Z"), ZoneId.of("Asia/Kolkata"))).today())
                .isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(new ReportingDay(Clock.fixed(Instant.parse("2026-10-16T18:31:00Z"), ZoneId.of("Asia/Kolkata"))).today())
                .isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void configuredZoneIsUsed() {
        ReportingDay utc = new ReportingDay("UTC");
        assertThat(utc.zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void anUnknownZoneFallsBackToIndia() {
        assertThat(new ReportingDay("Not/AZone").zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }
}
