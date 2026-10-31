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

    /**
     * An optional {@code ?month=} or body month for the AI insight endpoints: null stays null, a
     * month that can exist comes back in canonical form, anything else is a 400 "month must be
     * yyyy-MM". Those endpoints only checked the shape, so "2026-13" and "2027-00" were answered 200
     * and their facts built (found 2026-10-31); GET /insights already refused them through
     * {@link #parse}.
     */
    public static String requestMonth(String month) {
        if (month == null) return null;
        try {
            if (!month.matches("^\\d{4}-\\d{2}$")) throw new DateTimeParseException("Not yyyy-MM", month, 0);
            return parse(month).toString();
        } catch (DateTimeParseException e) {
            throw new com.finora.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "month must be yyyy-MM");
        }
    }
}
