package com.finora.notification.campaign;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * The one place the campaign feature asks "what time is it" and "which IST day is it".
 *
 * <p>Every schedule, quiet-hours, one-per-day-cap and run-date calculation goes through here, so
 * there is no {@code LocalDate.now()} in one class and {@code ZoneId.of("Asia/Kolkata")} in another
 * quietly disagreeing about the day around midnight. India has no daylight saving, so the zone is
 * fixed on purpose; the reason for a single class is testability: a test builds one with
 * {@link #fixedAt(Instant)} and controls the clock completely.
 */
@Component
public class IstClock {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Clock clock;

    public IstClock() {
        this(Clock.systemUTC());
    }

    private IstClock(Clock clock) {
        this.clock = clock;
    }

    /** A clock frozen at {@code instant}, for tests. */
    public static IstClock fixedAt(Instant instant) {
        return new IstClock(Clock.fixed(instant, IST));
    }

    public Instant now() {
        return clock.instant();
    }

    /** The IST calendar day right now. */
    public LocalDate today() {
        return dateOf(now());
    }

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(IST).toLocalDate();
    }

    public static LocalTime timeOf(Instant instant) {
        return instant.atZone(IST).toLocalTime();
    }

    /** The instant at which {@code time} occurs on {@code date} in IST. */
    public static Instant at(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, IST).toInstant();
    }
}
