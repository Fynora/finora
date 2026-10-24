package com.finora.validation;

import java.math.BigDecimal;

/**
 * What the money columns can hold, and the outer bound on any number a request may carry.
 *
 * <p>Almost every money column is NUMERIC(14,2): at most 999,999,999,999.99 either side of zero.
 * A value past that used to reach the database, fail there, and come back as a 409 telling the
 * user to "refresh and try again", which can never work.
 *
 * <p>The request bound is separate and much wider, and exists because of the Postgres JDBC driver
 * (42.7.11, measured): it silently sends any BigDecimal of 1E+131072 or more as 0, and throws
 * ArithmeticException on one as small as 1E-999999999. A balance of 1e999999999 was saved as 0.00,
 * and a filter of amountMin=1e999999999 was ignored. 30 digits either side of the point is far
 * inside what the driver handles and far past any real amount.
 */
public final class MoneyLimits {

    /** As text, for annotation attributes. */
    public static final String MAX_AMOUNT_TEXT = "999999999999.99";
    public static final String MIN_SIGNED_AMOUNT_TEXT = "-999999999999.99";
    public static final BigDecimal MAX_AMOUNT = new BigDecimal(MAX_AMOUNT_TEXT);

    /** accounts.interest_rate is NUMERIC(6,2). */
    public static final String MAX_INTEREST_RATE_TEXT = "9999.99";

    public static final String TOO_LARGE = " can be at most 999,999,999,999.99";
    public static final String TOO_NEGATIVE = " can be at most 999,999,999,999.99 below zero";
    public static final String FINER_THAN_PAISA = " can have at most 2 decimal places";

    /** Digits allowed before and after the point in any number a request carries. */
    public static final int MAX_REQUEST_DIGITS = 30;

    private MoneyLimits() {}

    /** At most two decimal places once trailing zeros are ignored -- see {@link WholePaise}.
     *  stripTrailingZeros never expands the number, so a huge exponent is cheap here. */
    public static boolean hasWholePaise(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 2;
    }

    /** True when {@code value} has at most {@link #MAX_REQUEST_DIGITS} digits on each side of the
     *  point. Never expands the number, so a huge exponent is cheap to check. */
    public static boolean withinRequestBounds(BigDecimal value) {
        if (value == null || value.signum() == 0) return true;
        BigDecimal stripped = value.stripTrailingZeros();
        long integerDigits = (long) stripped.precision() - stripped.scale();
        return integerDigits <= MAX_REQUEST_DIGITS && stripped.scale() <= MAX_REQUEST_DIGITS;
    }
}
