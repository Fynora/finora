package com.finora.service;

import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The latest date a transaction can carry and still count: tomorrow, in the user's own zone.
 *
 * <p>Every money screen reports on "the newest month with data" (see {@code ReportingPeriod}), so
 * one row typed with a wrong year -- 2099 for 2026 -- used to take over all of them: the Dashboard
 * reported on that month, the Reports trend and Advanced Reports showed only empty months before
 * it, Insights described it, and every subscription on the account left the Recurring list as
 * ended. Imports already hold a statement whose period lies in the future, and a Gmail receipt
 * dated more than a day ahead is refused; manual entry and edit had no such rule.
 *
 * <p>So the same date is used twice. Entry refuses a date after it ({@code TransactionService}),
 * and every reader leaves out a row already saved after it ({@code ReportRows.reportable} and the
 * newest-date queries the screens make), which covers rows saved before entry refused them.
 *
 * <p>One day of leeway, as Gmail receipts get: a device a timezone ahead of the user's saved one is
 * not a typo, and a row a day early does no lasting harm.
 */
@Component
public class ReportableDates {

    private final UserRepository userRepository;

    public ReportableDates(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** For services built by hand in unit tests: the default zone, with no user lookup. */
    public static ReportableDates withDefaultZone() {
        return new ReportableDates(null);
    }

    /** The latest date that counts for this user. */
    public LocalDate latestFor(UUID userId) {
        ZoneId zone = userRepository == null || userId == null ? UserZone.DEFAULT : UserZone.forUser(userRepository, userId);
        return latestAllowed(LocalDate.now(zone));
    }

    /** The rule itself, from a given "today". */
    public static LocalDate latestAllowed(LocalDate today) {
        return today.plusDays(1);
    }

    /** True for a date later than {@code latest}. A null date, or no latest date (a collaborator
     *  mocked in a unit test), is not judged here. */
    public static boolean isAfter(LocalDate date, LocalDate latest) {
        return date != null && latest != null && date.isAfter(latest);
    }
}
