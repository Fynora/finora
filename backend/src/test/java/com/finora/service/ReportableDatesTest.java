package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReportableDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void tomorrowCounts_theDayAfterDoesNot() {
        LocalDate latest = ReportableDates.latestAllowed(TODAY);

        assertThat(latest).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(ReportableDates.isAfter(TODAY, latest)).isFalse();
        assertThat(ReportableDates.isAfter(LocalDate.of(2026, 10, 26), latest)).isFalse();
        assertThat(ReportableDates.isAfter(LocalDate.of(2026, 10, 27), latest)).isTrue();
        assertThat(ReportableDates.isAfter(LocalDate.of(2099, 1, 5), latest)).isTrue();
    }

    @Test
    void theRuleCrossesAMonthAndAYearEnd() {
        assertThat(ReportableDates.latestAllowed(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(ReportableDates.latestAllowed(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void nothingIsJudgedWithoutBothDates() {
        assertThat(ReportableDates.isAfter(null, TODAY)).isFalse();
        assertThat(ReportableDates.isAfter(LocalDate.of(2099, 1, 5), null)).isFalse();
    }

    @Test
    void withNoUserLookup_theDefaultZoneIsUsed() {
        LocalDate latest = ReportableDates.withDefaultZone().latestFor(null);
        assertThat(latest).isEqualTo(LocalDate.now(com.finora.util.UserZone.DEFAULT).plusDays(1));
    }
}
