package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin portal's global (cross-user) audit feed -- AdminController.globalAuditLogs, distinct
 * from the existing per-user auditLogsForUser endpoint AdminRbacIT already covers. Registering a
 * user itself writes a USER_REGISTERED audit entry (AuthService.register), which is enough to
 * prove this endpoint surfaces real, paginated, platform-wide activity rather than being scoped
 * to one account.
 */
class GlobalAuditLogIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("global-audit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Global Audit IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    /** Writes a real AuditLog row directly rather than triggering one via a side-effecting flow
     *  (registration, login, ...) -- lets these Phase 5 filter tests control createdAt precisely
     *  (via reflection, since AuditLog has no public setter for it -- entries are meant to be
     *  timestamped at write time, not backdated in production code) without depending on some
     *  other endpoint's audit-writing behavior staying the same. */
    private void saveAuditEntry(UUID userId, String action, java.time.Instant createdAt) {
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setEntityType("TestEntity");
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "createdAt", createdAt);
        auditLogRepository.save(entry);
    }

    @Test
    void plainUser_isForbiddenFromGlobalAuditFeed() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesPaginatedGlobalFeed() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?page=0&size=5",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalElements\"", "\"content\"");
    }

    @Test
    void search_filtersByQueryAcrossActionAndEntityType() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_ACTION_" + UUID.randomUUID();
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("action").asText()).isEqualTo(uniqueAction);
    }

    @Test
    void search_filtersByDateRange_excludingAnEntryOutsideIt() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_DATE_" + UUID.randomUUID();
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now());
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.DAYS));

        // The filter's days are the reporting zone's (IST in tests too, by default).
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction + "&dateFrom=" + today + "&dateTo=" + today,
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        // Both entries share the unique action (so q alone would return 2) -- only the one dated
        // today should survive the dateFrom/dateTo=today range, proving the filter actually
        // excludes the 10-day-old entry rather than being ignored.
        assertThat(content).hasSize(1);
    }

    @Test
    void search_sortDirAsc_returnsOldestMatchingEntryFirst() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_SORT_" + UUID.randomUUID();
        java.time.Instant older = java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS);
        java.time.Instant newer = java.time.Instant.now();
        saveAuditEntry(admin.getId(), uniqueAction, newer);
        saveAuditEntry(admin.getId(), uniqueAction, older);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction + "&sortDir=asc",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("createdAt").asText()).isLessThan(content.get(1).get("createdAt").asText());
    }

    // ---- Audit Log drill-down (2026-11-11) ----

    private User createNamedUser(String role, String fullName) {
        User user = createUser(role);
        user.setFullName(fullName);
        return userRepository.save(user);
    }

    /** An entry about {@code subject}, optionally recording {@code actor} the way admin-side
     *  services do (metadata.actorId), with a request id. */
    private com.finora.entity.AuditLog saveEntry(UUID subject, String action, UUID actor, String requestId) {
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(subject);
        entry.setAction(action);
        entry.setEntityType("TestEntity");
        entry.setRequestId(requestId);
        if (actor != null) entry.setMetadata(java.util.Map.of("actorId", actor.toString(), "reason", "test"));
        return auditLogRepository.save(entry);
    }

    private JsonNode feed(User caller, String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?" + query, HttpMethod.GET, new HttpEntity<>(bearerFor(caller)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data").get("content");
    }

    @Test
    void filtersByAccount_admin_action_andRequest_exactly() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Some Customer");
        User other = createNamedUser("USER", "Other Customer");
        String action = "ZTEST_DRILL_" + UUID.randomUUID();
        String request = "req-" + UUID.randomUUID();
        saveEntry(customer.getId(), action, admin.getId(), request);
        saveEntry(customer.getId(), action, null, null);
        saveEntry(other.getId(), action, admin.getId(), null);

        assertThat(feed(admin, "action=" + action)).hasSize(3);
        assertThat(feed(admin, "action=" + action + "&userId=" + customer.getId())).hasSize(2);
        assertThat(feed(admin, "action=" + action + "&actorId=" + admin.getId())).hasSize(2);
        assertThat(feed(admin, "requestId=" + request)).hasSize(1);
        // action is exact, not a substring: the q search still covers "contains".
        assertThat(feed(admin, "action=" + action.substring(0, action.length() - 4))).isEmpty();
    }

    @Test
    void namesTheCustomerAndTheActingAdmin_forACallerWithUserView() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Some Customer");
        String action = "ZTEST_NAMES_" + UUID.randomUUID();
        saveEntry(customer.getId(), action, admin.getId(), null);

        JsonNode row = feed(admin, "action=" + action).get(0);

        assertThat(row.get("userName").asText()).isEqualTo("Some Customer");
        assertThat(row.get("userEmail").asText()).isEqualTo(customer.getEmail());
        assertThat(row.get("actorId").asText()).isEqualTo(admin.getId().toString());
        assertThat(row.get("actorName").asText()).isEqualTo("Acting Admin");
        assertThat(row.get("actorEmail").asText()).isEqualTo(admin.getEmail());
    }

    /** FINANCE holds AUDIT_VIEW but not USER_VIEW (V31): it could never see who a customer is,
     *  and naming people in the feed must not change that. Staff stay named. */
    @Test
    void neverNamesACustomer_toACallerWithoutUserView_butStillNamesStaff() throws Exception {
        User finance = createNamedUser("FINANCE", "Finance Viewer");
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Some Customer");
        String action = "ZTEST_PRIVACY_" + UUID.randomUUID();
        saveEntry(customer.getId(), action, admin.getId(), null);

        JsonNode row = feed(finance, "action=" + action).get(0);

        assertThat(row.get("userId").asText()).isEqualTo(customer.getId().toString());
        assertThat(row.get("userName").isNull()).isTrue();
        assertThat(row.get("userEmail").isNull()).isTrue();
        assertThat(row.get("actorName").asText()).isEqualTo("Acting Admin");
    }

    @Test
    void anUnreadableActorId_isTreatedAsNoActor_notAnError() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_BADACTOR_" + UUID.randomUUID();
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(admin.getId());
        entry.setAction(action);
        entry.setMetadata(java.util.Map.of("actorId", "not-a-uuid"));
        auditLogRepository.save(entry);

        JsonNode row = feed(admin, "action=" + action).get(0);

        assertThat(row.get("actorId").isNull()).isTrue();
        assertThat(row.get("actorName").isNull()).isTrue();
    }

    @Test
    void reportsWhenRetentionRemovedAnEntrysDetail() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_REDACTED_" + UUID.randomUUID();
        com.finora.entity.AuditLog entry = saveEntry(admin.getId(), action, null, null);
        entry.setMetadata(java.util.Map.of("redacted", true));
        entry.setRedactedAt(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        auditLogRepository.save(entry);

        JsonNode row = feed(admin, "action=" + action).get(0);

        assertThat(row.get("redactedAt").asText()).isEqualTo("2026-10-01T00:00:00Z");
    }

    /** 00:30 IST yesterday is 19:00 UTC the day before. With UTC day bounds, filtering on
     *  yesterday missed it and filtering on the day before showed it -- the opposite of the day the
     *  page's own heading puts it under. Dated yesterday, not at a fixed past date: these tests share
     *  one database, and an old row is swept up by AuditServiceRedactionIT's 100-day retention run,
     *  which then counts one row too many. */
    @Test
    void dateFilter_usesIndianDays_notUtcDays() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_ISTDAY_" + UUID.randomUUID();
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        LocalDate yesterday = LocalDate.now(ist).minusDays(1);
        java.time.Instant halfPastMidnight = yesterday.atTime(0, 30).atZone(ist).toInstant();
        saveAuditEntry(admin.getId(), action, halfPastMidnight);

        assertThat(feed(admin, "action=" + action + "&dateFrom=" + yesterday + "&dateTo=" + yesterday)).hasSize(1);
        LocalDate dayBefore = yesterday.minusDays(1);
        assertThat(feed(admin, "action=" + action + "&dateFrom=" + dayBefore + "&dateTo=" + dayBefore)).isEmpty();
    }

    /** AdminUserService records the acting admin as suspendedBy, reactivatedBy, recordedBy, ...
     *  rather than actorId (found by suspending a real account in a local run). Those rows must
     *  still say who did it and answer the admin filter. */
    @Test
    void readsTheActingAdmin_fromEveryKeyTheServicesUse() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Some Customer");
        String action = "ZTEST_ALTKEY_" + UUID.randomUUID();
        for (String key : AuditLogRepository.ADMIN_ACTOR_KEYS) {
            com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
            entry.setUserId(customer.getId());
            entry.setAction(action);
            entry.setMetadata(java.util.Map.of(key, admin.getId().toString()));
            auditLogRepository.save(entry);
        }

        JsonNode rows = feed(admin, "action=" + action + "&size=50");
        assertThat(rows).hasSize(AuditLogRepository.ADMIN_ACTOR_KEYS.size());
        rows.forEach(row -> assertThat(row.get("actorName").asText()).isEqualTo("Acting Admin"));
        assertThat(feed(admin, "action=" + action + "&size=50&actorId=" + admin.getId()))
                .hasSize(AuditLogRepository.ADMIN_ACTOR_KEYS.size());
    }

    @Test
    void namesTheBackgroundJob_whenOneActed() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_SYSTEM_" + UUID.randomUUID();
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(admin.getId());
        entry.setAction(action);
        entry.setMetadata(java.util.Map.of("actor", "merchant-rail-name-recheck"));
        auditLogRepository.save(entry);

        JsonNode row = feed(admin, "action=" + action).get(0);

        assertThat(row.get("systemActor").asText()).isEqualTo("merchant-rail-name-recheck");
        assertThat(row.get("actorId").isNull()).isTrue();
    }

    // ---- admin actions recorded with the admin as the row's account (break-test 2026-11-14) ----
    // 56 writes in 18 files (trust review, layouts, rules, banks, roles, settings, offers, campaigns,
    // release markers, the read audit) pass the acting admin as the account and name no actor. The
    // feed read them as "about Ops Admin, by nobody", and "Only this admin" never listed them.

    @Test
    void aStaffAccountOnARowNamingNoActor_isWhoActed_andIsListedUnderThatAdmin_notTheirAccount() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_ADMIN_AS_ACCOUNT_" + UUID.randomUUID();
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(admin.getId());
        entry.setAction(action);
        entry.setEntityType("HeldStatement");
        entry.setEntityId(UUID.randomUUID());
        entry.setMetadata(java.util.Map.of("note", "approved"));
        auditLogRepository.save(entry);

        JsonNode row = feed(admin, "action=" + action).get(0);
        assertThat(row.get("actorId").asText()).isEqualTo(admin.getId().toString());
        assertThat(row.get("actorName").asText()).isEqualTo("Acting Admin");
        assertThat(row.get("userId").isNull()).as("about the held statement, not the admin").isTrue();

        assertThat(feed(admin, "action=" + action + "&actorId=" + admin.getId())).hasSize(1);
        assertThat(feed(admin, "action=" + action + "&userId=" + admin.getId())).isEmpty();
    }

    @Test
    void openingACustomersProfile_isListedUnderThatCustomer_andUnderTheAdminWhoOpenedIt() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Asha Rao");
        ResponseEntity<String> opened = restTemplate.exchange("/api/v1/admin/users/" + customer.getId(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode aboutCustomer = feed(admin, "action=ADMIN_USER_DATA_VIEWED&userId=" + customer.getId());
        assertThat(aboutCustomer).hasSize(1);
        JsonNode row = aboutCustomer.get(0);
        assertThat(row.get("userId").asText()).isEqualTo(customer.getId().toString());
        assertThat(row.get("userName").asText()).isEqualTo("Asha Rao");
        assertThat(row.get("actorId").asText()).isEqualTo(admin.getId().toString());
        assertThat(row.get("actorName").asText()).isEqualTo("Acting Admin");

        JsonNode byAdmin = feed(admin, "action=ADMIN_USER_DATA_VIEWED&actorId=" + admin.getId());
        assertThat(byAdmin).anySatisfy(r -> assertThat(r.get("userId").asText()).isEqualTo(customer.getId().toString()));
        assertThat(feed(admin, "action=ADMIN_USER_DATA_VIEWED&userId=" + admin.getId())).isEmpty();
    }

    /** A row about a staff member that names who acted on them stays about them: only a row naming
     *  no actor and no job is re-read as the account's own act. */
    @Test
    void aRowAboutAStaffMember_byAnotherAdminOrAJob_staysAboutThem() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User other = createNamedUser("ADMIN", "Second Admin");
        String action = "ZTEST_ABOUT_STAFF_" + UUID.randomUUID();
        saveEntry(admin.getId(), action, other.getId(), null);
        com.finora.entity.AuditLog byJob = new com.finora.entity.AuditLog();
        byJob.setUserId(admin.getId());
        byJob.setAction(action);
        byJob.setMetadata(java.util.Map.of("actor", "account-purge-sweep"));
        auditLogRepository.save(byJob);

        assertThat(feed(admin, "action=" + action + "&userId=" + admin.getId())).hasSize(2);
        assertThat(feed(admin, "action=" + action + "&actorId=" + admin.getId())).isEmpty();
        assertThat(feed(admin, "action=" + action + "&actorId=" + other.getId())).hasSize(1);
    }

    /** Trust review and held imports put the admin in the account slot and as actorId, and the
     *  customer as subjectUserId; the Claude fix job puts no account at all. Each is about the
     *  customer: listed under them, and the admin's rows under the admin (re-check of 2026-11-14). */
    @Test
    void aTrustReviewOrJobRowNamingACustomer_isListedUnderThatCustomer() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Asha Rao");
        String action = "ZTEST_TRUST_REVIEW_" + UUID.randomUUID();
        com.finora.entity.AuditLog review = new com.finora.entity.AuditLog();
        review.setUserId(admin.getId());
        review.setAction(action);
        review.setEntityType("HeldStatement");
        review.setEntityId(UUID.randomUUID());
        review.setMetadata(java.util.Map.of("actorId", admin.getId().toString(),
                "subjectUserId", customer.getId().toString(), "heldId", "HS-1"));
        auditLogRepository.save(review);
        com.finora.entity.AuditLog job = new com.finora.entity.AuditLog();
        job.setAction(action);
        job.setEntityType("HeldStatement");
        job.setEntityId(UUID.randomUUID());
        job.setMetadata(java.util.Map.of("actor", "claude-fix", "subjectUserId", customer.getId().toString()));
        auditLogRepository.save(job);

        JsonNode aboutCustomer = feed(admin, "action=" + action + "&userId=" + customer.getId());
        assertThat(aboutCustomer).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("userId").asText()).isEqualTo(customer.getId().toString());
            assertThat(r.get("userName").asText()).isEqualTo("Asha Rao");
        });
        JsonNode byAdmin = feed(admin, "action=" + action + "&actorId=" + admin.getId());
        assertThat(byAdmin).hasSize(1);
        assertThat(byAdmin.get(0).get("actorName").asText()).isEqualTo("Acting Admin");
        assertThat(feed(admin, "action=" + action + "&userId=" + admin.getId()))
                .as("not about the admin who did it").isEmpty();
    }

    /** Layout curation stamps actorId with the same admin it puts in the account slot, and names no
     *  customer: by that admin, about the layout -- not under the admin's account. */
    @Test
    void anActorIdThatIsTheAccountItself_isThatAdminsAct_notAboutThem() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_LAYOUT_" + UUID.randomUUID();
        com.finora.entity.AuditLog layout = new com.finora.entity.AuditLog();
        layout.setUserId(admin.getId());
        layout.setAction(action);
        layout.setEntityType("LayoutProfile");
        layout.setEntityId(UUID.randomUUID());
        layout.setMetadata(java.util.Map.of("actorId", admin.getId().toString(), "name", "HDFC v3"));
        auditLogRepository.save(layout);

        JsonNode row = feed(admin, "action=" + action).get(0);
        assertThat(row.get("actorId").asText()).isEqualTo(admin.getId().toString());
        assertThat(row.get("userId").isNull()).isTrue();
        assertThat(feed(admin, "action=" + action + "&actorId=" + admin.getId())).hasSize(1);
        assertThat(feed(admin, "action=" + action + "&userId=" + admin.getId())).isEmpty();
    }

    // ---- the user page's own history (GET /admin/users/{id}/audit-logs) ----

    private JsonNode history(User caller, UUID userId) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/users/" + userId + "/audit-logs",
                HttpMethod.GET, new HttpEntity<>(bearerFor(caller)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static java.util.Set<String> actions(JsonNode rows) {
        java.util.Set<String> out = new java.util.HashSet<>();
        rows.forEach(r -> out.add(r.get("action").asText()));
        return out;
    }

    /** A customer's page never showed who approved or rejected their statement: those rows are
     *  recorded under the admin. It does now; nothing it showed goes. Opening the page itself is not
     *  listed there -- each open writes read audits that would crowd out the account's own activity. */
    @Test
    void aCustomersPage_listsTheReviewOfTheirStatement_butNotEachOpeningOfThePage() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Asha Rao");
        String own = "ZTEST_OWN_" + UUID.randomUUID();
        saveEntry(customer.getId(), own, null, null);
        String review = "ZTEST_REVIEW_" + UUID.randomUUID();
        com.finora.entity.AuditLog r = new com.finora.entity.AuditLog();
        r.setUserId(admin.getId());
        r.setAction(review);
        r.setEntityType("HeldStatement");
        r.setEntityId(UUID.randomUUID());
        r.setMetadata(java.util.Map.of("actorId", admin.getId().toString(), "subjectUserId", customer.getId().toString()));
        auditLogRepository.save(r);
        restTemplate.exchange("/api/v1/admin/users/" + customer.getId(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        java.util.Set<String> page = actions(history(admin, customer.getId()));
        assertThat(page).contains(own, review).doesNotContain("ADMIN_USER_DATA_VIEWED");
        // The admin's own page keeps their own acts, as before.
        assertThat(actions(history(admin, admin.getId()))).contains(review);
    }

    /** The user page's history needs only AUDIT_VIEW, and the feed shows every account's id: a
     *  customer's Google email in the details reached a caller who may not know who they are. */
    @Test
    void aCustomersGoogleEmail_onTheirPage_isHiddenWithoutUserView() throws Exception {
        User finance = createNamedUser("FINANCE", "Finance Viewer");
        User admin = createNamedUser("ADMIN", "Acting Admin");
        User customer = createNamedUser("USER", "Asha Rao");
        com.finora.entity.AuditLog g = new com.finora.entity.AuditLog();
        g.setUserId(customer.getId());
        g.setAction("ZTEST_GMAIL_" + UUID.randomUUID());
        g.setMetadata(java.util.Map.of("googleEmail", "asha.personal@example.test"));
        auditLogRepository.save(g);

        assertThat(history(finance, customer.getId()).toString()).doesNotContain("asha.personal@example.test")
                .contains("hidden (needs USER_VIEW)");
        assertThat(history(admin, customer.getId()).toString()).contains("asha.personal@example.test");
        assertThat(feed(finance, "action=" + g.getAction()).toString()).doesNotContain("asha.personal@example.test");
    }

    /** A row whose entity is the staff account itself happened TO them (an email sent to them, a
     *  failed sign-in on their account): about them, and never listed as their own act. */
    @Test
    void aRowAboutTheStaffAccountItself_isAboutThem_notTheirAct() throws Exception {
        User admin = createNamedUser("ADMIN", "Acting Admin");
        String action = "ZTEST_TO_STAFF_" + UUID.randomUUID();
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(admin.getId());
        entry.setAction(action);
        entry.setEntityType("User");
        entry.setEntityId(admin.getId());
        entry.setMetadata(java.util.Map.of("type", "verify"));
        auditLogRepository.save(entry);

        JsonNode row = feed(admin, "action=" + action).get(0);
        assertThat(row.get("actorId").isNull()).isTrue();
        assertThat(row.get("userId").asText()).isEqualTo(admin.getId().toString());
        assertThat(feed(admin, "action=" + action + "&userId=" + admin.getId())).hasSize(1);
        assertThat(feed(admin, "action=" + action + "&actorId=" + admin.getId())).isEmpty();
    }
}
