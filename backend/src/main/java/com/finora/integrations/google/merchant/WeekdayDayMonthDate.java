package com.finora.integrations.google.merchant;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a date printed as weekday + day + month, with no year ("Mon, 13 Jul"), into a real date.
 *
 * <p>Myntra prints its order-confirmation date this way. The year is not guessed from the clock: the
 * weekday recovers it. A candidate year is accepted only if that day and month fall on the printed
 * weekday AND the date is within a few weeks of the day the message arrived, and the closest such
 * date wins. Both conditions matter. The weekday alone is not enough, because the same day and month
 * land on the same weekday again in later years (13 July is a Monday in 2026 and a Tuesday in 2027,
 * so a Tuesday only rules out 2026, it does not prove 2027). The distance alone is not enough either,
 * because at the turn of the year "Fri, 26 Dec" and "Fri, 02 Jan" need the neighbouring year, not
 * the reference year. A date neither condition can confirm is refused: a refusal surfaces as a parse
 * failure, whereas a wrong year would silently mis-date a transaction.
 *
 * <p>The reference is the day the message arrived. A confirmation prints the day it was sent, so the
 * printed date is at most a few days from arrival; that is what makes the December/January boundary
 * come out right, and it is why the bound can be tight.
 */
final class WeekdayDayMonthDate {

    private static final Pattern DATE = Pattern.compile(
            "\\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*\\.?,?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s+"
                    + "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\b",
            Pattern.CASE_INSENSITIVE);

    /** How far the printed date may be from the day the message arrived. A confirmation prints the
     *  day it was sent, so this is generous; it exists to refuse a same-weekday date a year off. */
    private static final long MAX_DAYS_FROM_ARRIVAL = 31;

    /** With no arrival day, "today" stands in for it, and the message may be as old as Gmail sync's
     *  look-back window, so the bound is wider. */
    private static final long MAX_DAYS_FROM_TODAY = 120;

    private WeekdayDayMonthDate() {
    }

    /**
     * @param text      text containing a weekday, day and month; only the first such date is read
     * @param reference the day the message arrived; today when {@code null}
     * @return the date, or empty when there is none, it is not a real calendar date, or no year puts
     *         it on the printed weekday within a few weeks of the reference
     */
    static Optional<LocalDate> resolve(String text, LocalDate reference) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = DATE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }

        DayOfWeek weekday = weekdayOf(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        Month month = monthOf(matcher.group(3));
        LocalDate anchor = reference != null ? reference : LocalDate.now();
        long maxDistance = reference != null ? MAX_DAYS_FROM_ARRIVAL : MAX_DAYS_FROM_TODAY;

        LocalDate best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int year = anchor.getYear() - 1; year <= anchor.getYear() + 1; year++) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(year, month, day);
            } catch (DateTimeException notARealDate) {
                continue;
            }
            if (candidate.getDayOfWeek() != weekday) {
                continue;
            }
            long distance = Math.abs(ChronoUnit.DAYS.between(anchor, candidate));
            if (distance <= maxDistance && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static DayOfWeek weekdayOf(String abbreviation) {
        return switch (abbreviation.toLowerCase(Locale.ENGLISH)) {
            case "mon" -> DayOfWeek.MONDAY;
            case "tue" -> DayOfWeek.TUESDAY;
            case "wed" -> DayOfWeek.WEDNESDAY;
            case "thu" -> DayOfWeek.THURSDAY;
            case "fri" -> DayOfWeek.FRIDAY;
            case "sat" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private static Month monthOf(String abbreviation) {
        return switch (abbreviation.toLowerCase(Locale.ENGLISH)) {
            case "jan" -> Month.JANUARY;
            case "feb" -> Month.FEBRUARY;
            case "mar" -> Month.MARCH;
            case "apr" -> Month.APRIL;
            case "may" -> Month.MAY;
            case "jun" -> Month.JUNE;
            case "jul" -> Month.JULY;
            case "aug" -> Month.AUGUST;
            case "sep" -> Month.SEPTEMBER;
            case "oct" -> Month.OCTOBER;
            case "nov" -> Month.NOVEMBER;
            default -> Month.DECEMBER;
        };
    }
}
