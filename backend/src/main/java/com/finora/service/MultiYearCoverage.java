package com.finora.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure calendar-month completeness math for Multi-Year Comparison (issue #1455;
 * docs/superpowers/specs/2026-09-14-multi-year-comparison-design.md §3/§4.2). Takes the two facts
 * that actually determine whether a month counts as real, comparable data -- when the user's
 * history began, and which months some live account has a genuine statement gap in -- and turns
 * them into per-year coverage badges and the "This Year So Far" comparable window. No repository
 * access, no side effect: {@link AnalyticsService} supplies both inputs (the first via
 * TransactionRepository.findEarliestTxnDate, the second via
 * {@link AccountCoverageService#gapsForUser}).
 */
public final class MultiYearCoverage {

    private MultiYearCoverage() {}

    public record YearCoverage(int year, int coverageMonths, boolean isComplete) {}

    /** windowStart/windowEnd inclusive, both YearMonths within the current calendar year. */
    public record ThisYearWindow(YearMonth windowStart, YearMonth windowEnd) {}

    /** A month is complete iff it is on/after the user's first data month, strictly before the
     *  current month (a month that hasn't finished is never "complete" -- spec §4.2), and not
     *  inside any live account's coverage gap. */
    public static boolean isComplete(YearMonth month, YearMonth firstDataMonth, YearMonth currentMonth,
                                      List<AccountCoverageService.DateRange> gaps) {
        if (month.isBefore(firstDataMonth) || !month.isBefore(currentMonth)) return false;
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        return gaps.stream().noneMatch(g -> !g.end().isBefore(monthStart) && !g.start().isAfter(monthEnd));
    }

    /** One entry per calendar year from firstDataMonth's year through currentMonth's year, in
     *  order. */
    public static List<YearCoverage> yearCoverages(YearMonth firstDataMonth, YearMonth currentMonth,
                                                     List<AccountCoverageService.DateRange> gaps) {
        List<YearCoverage> result = new ArrayList<>();
        for (int year = firstDataMonth.getYear(); year <= currentMonth.getYear(); year++) {
            int coveredMonths = 0;
            for (int m = 1; m <= 12; m++) {
                if (isComplete(YearMonth.of(year, m), firstDataMonth, currentMonth, gaps)) coveredMonths++;
            }
            result.add(new YearCoverage(year, coveredMonths, coveredMonths == 12));
        }
        return result;
    }

    /** The current year's comparable window (spec §4.2): the later of January this year or the
     *  user's first data month, through the longest unbroken run of complete months from there.
     *  Empty when the first eligible month is not itself complete yet. */
    public static Optional<ThisYearWindow> thisYearWindow(YearMonth firstDataMonth, YearMonth currentMonth,
                                                            List<AccountCoverageService.DateRange> gaps) {
        YearMonth januaryThisYear = YearMonth.of(currentMonth.getYear(), 1);
        YearMonth windowStart = firstDataMonth.isAfter(januaryThisYear) ? firstDataMonth : januaryThisYear;

        if (!isComplete(windowStart, firstDataMonth, currentMonth, gaps)) return Optional.empty();

        YearMonth windowEnd = windowStart;
        YearMonth next = windowEnd.plusMonths(1);
        while (isComplete(next, firstDataMonth, currentMonth, gaps)) {
            windowEnd = next;
            next = next.plusMonths(1);
        }
        return Optional.of(new ThisYearWindow(windowStart, windowEnd));
    }

    /** Whether calendar year {@code year} has full, complete coverage for the SAME relative
     *  month-of-year range as {@code window} (e.g. window = June-July means checking that year's
     *  own June-July) -- spec §4.2's rule for which prior years may join the "This Year So Far"
     *  comparison. */
    public static boolean coversSameRelativeWindow(int year, ThisYearWindow window, YearMonth firstDataMonth,
                                                     YearMonth currentMonth, List<AccountCoverageService.DateRange> gaps) {
        YearMonth start = YearMonth.of(year, window.windowStart().getMonthValue());
        YearMonth end = YearMonth.of(year, window.windowEnd().getMonthValue());
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            if (!isComplete(m, firstDataMonth, currentMonth, gaps)) return false;
        }
        return true;
    }
}
