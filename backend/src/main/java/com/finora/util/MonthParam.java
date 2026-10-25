package com.finora.util;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * A {@code YYYY-MM} month from a request, with a year of 1 through 9999 -- the month form of
 * {@link com.finora.validation.FourDigitYear}.
 *
 * <p>{@code YearMonth.parse} alone accepts {@code +10000-01} and {@code 0000-12}: Reports answered
 * them with an empty report labelled with the odd year, and Analytics with an empty list, where a
 * month that cannot exist is a 400. Insights did not parse its month at all, so {@code 2026-13}
 * came back as "In 2026-13, total spend was ₹0".
 *
 * <p>Throws {@link DateTimeParseException} for anything else, which GlobalExceptionHandler already
 * answers as a 400 naming the format; callers with their own message catch it as before.
 */
public final class MonthParam {

    private MonthParam() {}

    public static YearMonth parse(String month) {
        YearMonth parsed = YearMonth.parse(month);
        if (parsed.getYear() < 1 || parsed.getYear() > 9999) {
            throw new DateTimeParseException("Year outside 1-9999", month, 0);
        }
        return parsed;
    }
}
