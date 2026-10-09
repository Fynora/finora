package com.finora.notification.campaign;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Pure schedule arithmetic for campaigns: no clock, no database, so every edge case (the window
 * boundaries, the 2-hour late rule, the day after a send-now) is a plain unit test.
 *
 * <p>All times are IST. A scheduled send may only fall between 07:00 and 21:59 (a push at 3 AM is
 * worse than none), and a run that is late is still sent only while it is both within
 * {@link #LATE_GRACE} of its slot and inside that window; otherwise it is recorded as MISSED and the
 * next slot is scheduled. "Send now" is the one thing that ignores the window: an admin pressing the
 * button chose that moment.
 */
public final class ScheduleCalculator {

    /** First minute a scheduled send may fall on. */
    public static final LocalTime WINDOW_START = LocalTime.of(7, 0);
    /** First minute a scheduled send may NOT fall on (so 21:59 is the last allowed). */
    public static final LocalTime WINDOW_END_EXCLUSIVE = LocalTime.of(22, 0);
    /** How late a scheduled run may still be sent (outage recovery). Exactly this late is still sent. */
    public static final Duration LATE_GRACE = Duration.ofHours(2);

    private ScheduleCalculator() {
    }

    public static boolean isInsideWindow(LocalTime time) {
        return !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END_EXCLUSIVE);
    }

    /** The first occurrence of {@code sendTime} (IST) strictly after {@code after}. */
    public static Instant nextDailySlotAfter(LocalTime sendTime, Instant after) {
        LocalDate day = IstClock.dateOf(after);
        Instant candidate = IstClock.at(day, sendTime);
        return candidate.isAfter(after) ? candidate : IstClock.at(day.plusDays(1), sendTime);
    }

    /** The slot on the IST day after the one {@code scheduledFor} falls on -- used after a run, so
     *  a late run (slot 19:00, sent 20:30) still schedules tomorrow at 19:00, not later. */
    public static Instant slotAfterRunOf(Instant scheduledFor, LocalTime sendTime) {
        return IstClock.at(IstClock.dateOf(scheduledFor).plusDays(1), sendTime);
    }

    /** Whether a daily slot falls after the campaign's last allowed day. */
    public static boolean isPastEnd(Instant slot, LocalDate endsOn) {
        return endsOn != null && IstClock.dateOf(slot).isAfter(endsOn);
    }

    /** Outcome of the missed-run rule for a due scheduled slot. */
    public record Decision(boolean send, String reason) {
        static Decision run() {
            return new Decision(true, null);
        }

        static Decision missed(String reason) {
            return new Decision(false, reason);
        }
    }

    /**
     * Whether a slot that came due may be sent now. {@code scheduledFor} is the slot; {@code now} is
     * when the scheduler actually got to it.
     */
    public static Decision decide(Instant scheduledFor, Instant now) {
        if (Duration.between(scheduledFor, now).compareTo(LATE_GRACE) > 0) {
            return Decision.missed("More than 2 hours after the scheduled time, so it was not sent.");
        }
        if (!isInsideWindow(IstClock.timeOf(now))) {
            return Decision.missed("Came due outside 07:00-21:59 IST, so it was not sent.");
        }
        return Decision.run();
    }
}
