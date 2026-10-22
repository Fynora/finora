package com.finora.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Which calendar day a billing moment falls on.
 *
 * <p>{@code subscriptions.renewal_date} is a plain date with no time zone, but the payment
 * providers send an exact instant: Razorpay's {@code current_end} (epoch seconds) and RevenueCat's
 * {@code expiration_at_ms}. Fynora bills in INR and its customers are in India, and Razorpay's
 * periods end at midnight India time, which is 18:30 UTC the day before. Converting that instant
 * with UTC stored the day before the real renewal: a subscription Razorpay charges on the 21st
 * showed a renewal date of the 20th.
 *
 * <p>So every billing date is an India calendar day, written and read through this class. Writers
 * use {@link #dateOf}, and anything that compares a stored renewal date with "now" uses
 * {@link #today} or {@link #startOf}, never the server's own zone.
 */
public final class BillingCalendar {

    /** India has no daylight saving, so this zone's offset never changes. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BillingCalendar() {}

    /** The India calendar day {@code instant} falls on. */
    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** The first instant of {@code date} in India: the moment a period ending that day ends. */
    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** Today's India calendar day. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
