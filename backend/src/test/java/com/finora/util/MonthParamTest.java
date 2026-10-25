package com.finora.util;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthParamTest {

    @Test
    void aRealMonthParses_atBothEndsOfTheRange() {
        assertThat(MonthParam.parse("2026-09")).isEqualTo(YearMonth.of(2026, 9));
        assertThat(MonthParam.parse("0001-01")).isEqualTo(YearMonth.of(1, 1));
        assertThat(MonthParam.parse("9999-12")).isEqualTo(YearMonth.of(9999, 12));
    }

    @Test
    void aYearOutsideOneTo9999_isRefusedAsAParseError() {
        for (String month : new String[] {"+10000-01", "0000-12", "-0001-01"}) {
            assertThatThrownBy(() -> MonthParam.parse(month)).as(month).isInstanceOf(DateTimeParseException.class);
        }
    }

    @Test
    void aMalformedMonth_isStillAParseError() {
        for (String month : new String[] {"2026-13", "2026-9", "abc", ""}) {
            assertThatThrownBy(() -> MonthParam.parse(month)).as(month).isInstanceOf(DateTimeParseException.class);
        }
    }
}
