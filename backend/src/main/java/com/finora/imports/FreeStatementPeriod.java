package com.finora.imports;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

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
 *
 * <p>The limit is applied to two spans, and both must fit (owner's decision, 2026-10-06):
 * <ul>
 *   <li>the period the statement prints, when it prints both ends; and</li>
 *   <li>the earliest to the latest date across EVERY staged transaction, unticked ones included.
 *       Without this a CSV or Excel export, which almost never prints a period, and a PDF whose
 *       period could not be read were never checked at all; and editing only the printed period of
 *       a three-month PDF would have passed.</li>
 * </ul>
 * Each span gets {@link #GRACE_DAYS} of slack at both ends before the month rule is applied, so a
 * statement for 5 Jan to 5 Feb may arrive as 3 Jan to 7 Feb. Measured over the real statement
 * corpus (2026-10-06), rows fell at most two days outside their own printed period, so the slack
 * keeps those one-month statements passing the transaction check.
 */
final class FreeStatementPeriod {

    /** The flat count the calendar rule replaced; any period this short is always allowed. */
    private static final long MIN_DAYS_ALWAYS_ALLOWED = 31;

    /** Days of slack at each end of a span before the one-month rule is applied. */
    static final int GRACE_DAYS = 2;

    private FreeStatementPeriod() {}

    /** A span that is over the Free limit, ordered earliest first. {@code fromTransactions} says
     *  whether it is the transactions' date range rather than the printed period. */
    record Excess(LocalDate from, LocalDate to, boolean fromTransactions) {}

    /**
     * The first span over the Free limit, or null when the statement fits: the printed period is
     * judged first, then the full range of transaction dates. A printed period with either end
     * missing is not judged (half a period says nothing about its length); the transactions still
     * are. No dates at all means nothing to judge.
     */
    static Excess firstExcess(LocalDate printedStart, LocalDate printedEnd, List<LocalDate> transactionDates) {
        if (printedStart != null && printedEnd != null && !withinFreeLimit(printedStart, printedEnd)) {
            return ordered(printedStart, printedEnd, false);
        }
        LocalDate earliest = null;
        LocalDate latest = null;
        for (LocalDate date : transactionDates == null ? List.<LocalDate>of() : transactionDates) {
            if (date == null) continue;
            if (earliest == null || date.isBefore(earliest)) earliest = date;
            if (latest == null || date.isAfter(latest)) latest = date;
        }
        if (earliest != null && !withinFreeLimit(earliest, latest)) {
            return new Excess(earliest, latest, true);
        }
        return null;
    }

    /** Whether {@code start}..{@code end} fits the Free limit once {@link #GRACE_DAYS} are taken off
     *  each end. A span no longer than the two grace allowances together always fits. */
    static boolean withinFreeLimit(LocalDate start, LocalDate end) {
        Excess span = ordered(start, end, false);
        LocalDate from = span.from().plusDays(GRACE_DAYS);
        LocalDate to = span.to().minusDays(GRACE_DAYS);
        return from.isAfter(to) || coversAtMostOneMonth(from, to);
    }

    /** Whether {@code start}..{@code end} fits the Free plan's one-month limit, with no grace. A
     *  reversed pair is judged by its real length: a naive comparison would let every reversed
     *  period through. */
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

    private static Excess ordered(LocalDate a, LocalDate b, boolean fromTransactions) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        return a.isAfter(b) ? new Excess(b, a, fromTransactions) : new Excess(a, b, fromTransactions);
    }
}
