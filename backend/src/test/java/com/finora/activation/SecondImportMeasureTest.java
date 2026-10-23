package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecondImportMeasureTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @Test
    void fewerThanTwoFilesHasNoSecond() {
        assertThat(ActivationFunnelService.daysToSecond(List.of())).isNull();
        assertThat(ActivationFunnelService.daysToSecond(List.of(at("2030-06-06T06:00:00Z")))).isNull();
        assertThat(ActivationFunnelService.cameBackLaterDay(List.of(at("2030-06-06T06:00:00Z")), IST)).isFalse();
    }

    @Test
    void secondIsTheSecondEarliestWhateverTheOrder() {
        List<OffsetDateTime> files = List.of(at("2030-06-15T06:00:00Z"), at("2030-06-06T06:00:00Z"),
                at("2030-06-08T18:00:00Z"));
        assertThat(ActivationFunnelService.daysToSecond(files)).isEqualTo(2.5);
    }

    @Test
    void laterDayIsTheReportingZoneDay() {
        // 23:00 and 01:00 IST: two days. 06:00 and 18:00 IST on one date: one day.
        assertThat(ActivationFunnelService.cameBackLaterDay(
                List.of(at("2030-06-06T17:30:00Z"), at("2030-06-06T19:30:00Z")), IST)).isTrue();
        assertThat(ActivationFunnelService.cameBackLaterDay(
                List.of(at("2030-06-06T00:30:00Z"), at("2030-06-06T12:30:00Z")), IST)).isFalse();
    }
}
