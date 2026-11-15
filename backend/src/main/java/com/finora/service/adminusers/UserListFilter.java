package com.finora.service.adminusers;

import com.finora.exception.ApiException;
import com.finora.util.LikePatterns;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * The admin Users list's filters, checked and normalised once at the edge. Every field is
 * optional: null means "don't filter on this". What each filter means is in
 * docs/superpowers/specs/2026-11-15-user-list-filters-design.md (Sid, 2026-11-15).
 *
 * @param q              search text, trimmed and LIKE-escaped ({@link LikePatterns#escape}): it is
 *                       bound into a LIKE, where % and _ are wildcards even inside a bound
 *                       parameter, so searching "50%" used to match every row containing "50"
 * @param status         {@code users.status}
 * @param plan           FREE, PLUS or PREMIUM: the plan the user gets now (a user with no plan is FREE)
 * @param bankId         a live account at this bank ({@code accounts.bank_id})
 * @param joinedFrom     signed up on or after this day (platform reporting zone)
 * @param joinedTo       signed up on or before this day (platform reporting zone)
 * @param lastActiveFrom latest active day on or after this day; a user never seen does not match
 * @param lastActiveTo   latest active day on or before this day; a user never seen matches
 * @param importsMin     at least this many statements imported
 * @param importsMax     at most this many statements imported
 */
public record UserListFilter(
        String q,
        String status,
        String plan,
        String bankId,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        LocalDate lastActiveFrom,
        LocalDate lastActiveTo,
        Integer importsMin,
        Integer importsMax) {

    public static final Set<String> PLANS = Set.of("FREE", "PLUS", "PREMIUM");

    private static final LocalDate EARLIEST_DAY = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST_DAY = LocalDate.of(9999, 12, 31);

    public static UserListFilter of(String q, String status, String plan, String bankId,
                                    LocalDate joinedFrom, LocalDate joinedTo,
                                    LocalDate lastActiveFrom, LocalDate lastActiveTo,
                                    Integer importsMin, Integer importsMax) {
        String normalizedQ = blankToNull(q);
        String normalizedPlan = blankToNull(plan);
        if (normalizedPlan != null) {
            normalizedPlan = normalizedPlan.toUpperCase(Locale.ROOT);
            if (!PLANS.contains(normalizedPlan)) {
                throw badRequest("Unknown plan. Choose Free, Plus or Premium.");
            }
        }
        // A date input never sends anything outside these years, but the API accepts any ISO date:
        // "+999999999-12-31" used to fail as a server error when the day after it was worked out.
        for (LocalDate day : new LocalDate[] {joinedFrom, joinedTo, lastActiveFrom, lastActiveTo}) {
            if (day != null && (day.isBefore(EARLIEST_DAY) || day.isAfter(LATEST_DAY))) {
                throw badRequest("Dates must be between the years 1900 and 9999.");
            }
        }
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw badRequest("Joined: the from date is after the to date.");
        }
        if (lastActiveFrom != null && lastActiveTo != null && lastActiveFrom.isAfter(lastActiveTo)) {
            throw badRequest("Last active: the from date is after the to date.");
        }
        if ((importsMin != null && importsMin < 0) || (importsMax != null && importsMax < 0)) {
            throw badRequest("Imports cannot be negative.");
        }
        if (importsMin != null && importsMax != null && importsMin > importsMax) {
            throw badRequest("Imports: the minimum is more than the maximum.");
        }
        return new UserListFilter(
                normalizedQ == null ? null : LikePatterns.escape(normalizedQ),
                blankToNull(status), normalizedPlan, blankToNull(bankId),
                joinedFrom, joinedTo, lastActiveFrom, lastActiveTo, importsMin, importsMax);
    }

    /** True when no filter at all is set. */
    public boolean isUnfiltered() {
        return q == null && status == null && plan == null && bankId == null
                && joinedFrom == null && joinedTo == null && lastActiveFrom == null && lastActiveTo == null
                && importsMin == null && importsMax == null;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
