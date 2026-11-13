package com.finora.security;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * What every admin GET route discloses, and so what {@link AdminUserDataReadAuditInterceptor}
 * records for it. Privacy workstream 9: the Privacy Policy says every administrative access to a
 * user's data is logged, but only {@code /api/v1/admin/users/{userId}/...} was; a held import, a
 * statement's rows, a transaction trace or a page of a statement's original could be read under
 * other paths with no row naming the person.
 *
 * <p>One table rather than an annotation on each handler, so the whole classification can be read
 * and reviewed in one place. {@code AdminReadAuditPolicyCoverageTest} fails when an admin GET route
 * exists that this table does not name, so a new endpoint cannot ship unclassified.
 *
 * <ul>
 *   <li>{@link Kind#ONE_USER}: the response is about one person. The row names that person, found
 *       from the path variable or request parameter by {@link AdminReadOwnerResolver}.</li>
 *   <li>{@link Kind#MANY_USERS}: a listing that shows several people's personal fields (emails,
 *       names, file names, user ids). One row per request, with the filters used.</li>
 *   <li>{@link Kind#NO_PERSONAL_DATA}: counts, configuration, health, layouts. Nothing recorded.</li>
 * </ul>
 */
public final class AdminReadAuditPolicy {

    public enum Kind { ONE_USER, MANY_USERS, NO_PERSONAL_DATA }

    /** How the owner of a {@link Kind#ONE_USER} response is found. */
    public enum Owner {
        /** The variable is the user id. */
        USER,
        /** A request parameter holding the customer's email, looked up in the user portal. */
        USER_EMAIL_PARAM,
        ACCOUNT, IMPORT_JOB, HELD_STATEMENT, STATEMENT_IMPORT, ANALYSIS_REFERENCE, TRANSACTION,
        NOTIFICATION, ORIGINAL_VIEW, LEARNING_EVENT, SUPPORT_TICKET
    }

    /** @param variable the path variable or request parameter the owner is found from */
    public record Rule(Kind kind, Owner owner, String variable) {
        static Rule oneUser(Owner owner, String variable) { return new Rule(Kind.ONE_USER, owner, variable); }
        static Rule manyUsers() { return new Rule(Kind.MANY_USERS, null, null); }
        static Rule none() { return new Rule(Kind.NO_PERSONAL_DATA, null, null); }
    }

    private static final String A = "/api/v1/admin";

    /** Every {@code /api/v1/admin/users/{userId}/...} sub-resource, and the user detail page. */
    private static final Pattern USER_SUB_RESOURCE = Pattern.compile("^/api/v1/admin/users/\\{userId}(/.*)?$");

    static final Map<String, Rule> ROUTES = Map.ofEntries(
            entry(A + "/users/{id}", Rule.oneUser(Owner.USER, "id")),

            // One person's data under another path.
            entry(A + "/accounts/{accountId}/coverage", Rule.oneUser(Owner.ACCOUNT, "accountId")),
            entry(A + "/held-imports/{jobId}", Rule.oneUser(Owner.IMPORT_JOB, "jobId")),
            entry(A + "/held-statements/{heldId}", Rule.oneUser(Owner.HELD_STATEMENT, "heldId")),
            entry(A + "/imports/row-trace/{statementImportId}", Rule.oneUser(Owner.STATEMENT_IMPORT, "statementImportId")),
            entry(A + "/imports/traces/by-analysis/{reference}", Rule.oneUser(Owner.ANALYSIS_REFERENCE, "reference")),
            entry(A + "/imports/traces/by-job/{jobId}", Rule.oneUser(Owner.IMPORT_JOB, "jobId")),
            entry(A + "/imports/analyses/{reference}", Rule.oneUser(Owner.ANALYSIS_REFERENCE, "reference")),
            entry(A + "/imports/analyses/failures/by-user", Rule.oneUser(Owner.USER_EMAIL_PARAM, "email")),
            entry(A + "/insights/explorer/{userId}", Rule.oneUser(Owner.USER, "userId")),
            entry(A + "/learning-queue/{eventId}", Rule.oneUser(Owner.LEARNING_EVENT, "eventId")),
            entry(A + "/merchant-review/users/{userId}/merchants/{merchantId}/merge-candidates", Rule.oneUser(Owner.USER, "userId")),
            entry(A + "/notifications/{id}", Rule.oneUser(Owner.NOTIFICATION, "id")),
            entry(A + "/original-views/{viewId}/pages/{pageNumber}", Rule.oneUser(Owner.ORIGINAL_VIEW, "viewId")),
            entry(A + "/original-views/{viewId}/csv", Rule.oneUser(Owner.ORIGINAL_VIEW, "viewId")),
            entry(A + "/reconciliation/explorer/{transactionId}", Rule.oneUser(Owner.TRANSACTION, "transactionId")),
            entry(A + "/statement-corrections/users/{userId}", Rule.oneUser(Owner.USER, "userId")),
            entry(A + "/support/tickets/{id}/notes", Rule.oneUser(Owner.SUPPORT_TICKET, "id")),
            // The admin view of one ticket (2026-11-12): the customer's description, name and email.
            entry(A + "/support/tickets/{id}", Rule.oneUser(Owner.SUPPORT_TICKET, "id")),

            // Listings that show several people's personal fields.
            entry(A + "/users", Rule.manyUsers()),                       // email, name, phone
            entry(A + "/audit-logs", Rule.manyUsers()),                  // user ids and event details
            entry(A + "/dashboard/overview", Rule.manyUsers()),          // recent audit rows
            entry(A + "/diagnostics", Rule.manyUsers()),                 // recent imports: email, file name
            entry(A + "/system/recent-imports", Rule.manyUsers()),       // email, file name
            entry(A + "/held-imports", Rule.manyUsers()),                // user id, file name
            entry(A + "/held-statements", Rule.manyUsers()),             // user id, bank, notes
            entry(A + "/held-statements/without-review-record", Rule.manyUsers()), // file name
            entry(A + "/learning-queue", Rule.manyUsers()),              // email, merchant, file name
            entry(A + "/merchant-review", Rule.manyUsers()),             // email, merchant name
            entry(A + "/notifications", Rule.manyUsers()),               // user id, title
            entry(A + "/referrals", Rule.manyUsers()),                   // both people's email and name
            entry(A + "/search", Rule.manyUsers()),                      // names and emails matching a query
            entry(A + "/subscriptions", Rule.manyUsers()),               // email, name, plan
            entry(A + "/support/tickets", Rule.manyUsers()),             // user id, subject
            entry(A + "/feedback", Rule.manyUsers()),                    // name, email, message

            // Counts, configuration, health and layout data: no one person's details.
            entry(A + "/app-icon/current", Rule.none()),
            entry(A + "/app-icon/icons", Rule.none()),
            entry(A + "/app-icon/schedule", Rule.none()),               // admins' own edits
            // Job names, schedules, counts and error type + code location only (no messages).
            entry(A + "/background-jobs", Rule.none()),
            entry(A + "/background-jobs/{key}/failures", Rule.none()),
            entry(A + "/banks", Rule.none()),
            entry(A + "/banks/{id}/audit", Rule.none()),                // admins' edits to a bank
            entry(A + "/billing-offers", Rule.none()),
            entry(A + "/billing-offers/plans", Rule.none()),
            entry(A + "/blanked-copy/status", Rule.none()),
            entry(A + "/dashboard/activation-funnel", Rule.none()),
            entry(A + "/dashboard/activity-trend", Rule.none()),
            entry(A + "/feature-flags", Rule.none()),
            entry(A + "/held-imports/summary", Rule.none()),
            entry(A + "/held-statements/telemetry", Rule.none()),
            entry(A + "/imports/analyses", Rule.none()),                // no file name or user id, by design
            entry(A + "/imports/analyses/paged", Rule.none()),          // same
            entry(A + "/imports/analyses/summary", Rule.none()),
            entry(A + "/imports/analyses/failures/summary", Rule.none()),
            entry(A + "/imports/layout-registry", Rule.none()),
            entry(A + "/imports/layout-registry/bank-choices", Rule.none()),
            entry(A + "/imports/layout-registry/bank-origins", Rule.none()),
            entry(A + "/imports/layout-registry/profiles", Rule.none()),
            entry(A + "/imports/layout-registry/review-queue", Rule.none()),
            entry(A + "/imports/layouts", Rule.none()),
            entry(A + "/imports/layouts/drifting", Rule.none()),
            entry(A + "/imports/layouts/evidence", Rule.none()),
            entry(A + "/imports/layouts/unknown-headers", Rule.none()),  // column names, never values
            entry(A + "/imports/layouts/{fingerprint}/timeline", Rule.none()),
            entry(A + "/imports/telemetry/summary", Rule.none()),
            entry(A + "/integrations", Rule.none()),
            entry(A + "/learning-queue/summary", Rule.none()),
            entry(A + "/learning/stats", Rule.none()),
            entry(A + "/merchant-review/count", Rule.none()),
            entry(A + "/merchant-templates", Rule.none()),
            entry(A + "/merchants/gmail-parser-stats", Rule.none()),
            entry(A + "/merchants/stats", Rule.none()),                 // merchant names across users, counts
            entry(A + "/notifications/summary", Rule.none()),
            entry(A + "/analytics/platform", Rule.none()),
            entry(A + "/analytics/activation", Rule.none()),            // counts; deletion reasons are unlinked
            entry(A + "/feedback/breakdown", Rule.none()),
            entry(A + "/analytics/spending-tracking", Rule.none()),
            entry(A + "/permissions", Rule.none()),
            entry(A + "/push-campaigns", Rule.none()),
            entry(A + "/push-campaigns/audience-count", Rule.none()),
            entry(A + "/push-campaigns/settings", Rule.none()),
            entry(A + "/push-campaigns/{id}", Rule.none()),
            entry(A + "/reconciliation/stats", Rule.none()),
            entry(A + "/roles", Rule.none()),
            entry(A + "/rules", Rule.none()),                           // global rules only
            entry(A + "/settings", Rule.none()),
            entry(A + "/statement-refresh/summary", Rule.none()),
            entry(A + "/stats/overview", Rule.none()),
            entry(A + "/stats/signup-sources", Rule.none()),
            entry(A + "/subscriptions/health", Rule.none()),
            entry(A + "/system/health", Rule.none()),
            entry(A + "/trusted-senders", Rule.none())
    );

    private AdminReadAuditPolicy() {}

    /** The rule for a matched route pattern, or empty when this table does not name it. */
    public static Optional<Rule> forPattern(String pattern) {
        if (pattern == null) return Optional.empty();
        if (USER_SUB_RESOURCE.matcher(pattern).matches()) return Optional.of(Rule.oneUser(Owner.USER, "userId"));
        return Optional.ofNullable(ROUTES.get(pattern));
    }
}
