package com.finora.imports;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * plans.ts's "Statement length: One month" Free-plan limit, as a calendar rule rather than a flat
 * day count. A statement may end no later than the same date one month after it starts; a period
 * that opens on the last day of a month may run to the last day of the next month.
 *
 * <p>Replaces a flat 31-day count (start and end inclusive), which refused ordinary one-month
 * statements: one that runs to the same date next month counts 32 days, and so does one that opens
 * on the closing day of a 30-day month and ends on the last day of the next. A period of 31 days
 * or fewer is always allowed, so the calendar rule only ever loosens the old limit. Two- and
 * three-month statements stay refused.
 */
final class FreeStatementPeriod {

    /** The flat count the calendar rule replaced; any period this short is always allowed. */
    private static final long MIN_DAYS_ALWAYS_ALLOWED = 31;

    private FreeStatementPeriod() {}

    /** Whether {@code start}..{@code end} fits the Free plan's one-month limit. A reversed pair is
     *  judged by its real length: a naive comparison would let every reversed period through. */
    static boolean coversAtMostOneMonth(LocalDate start, LocalDate end) {
        LocalDate from = start.isAfter(end) ? end : start;
        LocalDate to = start.isAfter(end) ? start : end;
        // Never stricter than the flat count it replaced: a month after Jan 30 clamps to Feb 28,
        // which alone would refuse a 31-day period the old rule let through.
        if (ChronoUnit.DAYS.between(from, to) + 1 <= MIN_DAYS_ALWAYS_ALLOWED) return true;
        LocalDate limit = from.plusMonths(1);
        if (from.getDayOfMonth() == from.lengthOfMonth()) {
            limit = limit.withDayOfMonth(limit.lengthOfMonth());
        }
        return !to.isAfter(limit);
    }
}
