package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin Users list's filters (docs/superpowers/specs/2026-11-15-user-list-filters-design.md),
 * end to end against real Postgres. Every test tags its users with a unique search term and passes
 * it as {@code q}, so users other ITs leave behind never reach its assertions.
 */
class AdminUserListFiltersIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private static String tag() {
        return "ulf" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private User user(String tag, String role) {
        User user = new User();
        user.setEmail(tag + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User List Filters IT");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User customer(String tag) {
        return user(tag, "USER");
    }

    private User admin() {
        return user("ulf-admin", "ADMIN");
    }

    private void joinedAt(User user, Instant at) {
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(at), user.getId());
    }

    private void subscription(User user, String planCode, String status) {
        jdbc.update("INSERT INTO subscriptions (user_id, plan_id, status, start_date) "
                + "SELECT ?, id, ?, CURRENT_DATE FROM plans WHERE code = ?", user.getId(), status, planCode);
    }

    private void grant(User user, String tier, Instant expiresAt) {
        jdbc.update("INSERT INTO referral_grants (user_id, tier, status, activated_at, expires_at) "
                + "VALUES (?, ?, 'ACTIVE', now(), ?)", user.getId(), tier,
                expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    private void activeOn(User user, LocalDate day) {
        jdbc.update("INSERT INTO user_activity_days (user_id, activity_date) VALUES (?, ?)", user.getId(), day);
    }

    private UUID account(User user, String bankId) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("User List Filters IT");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setBankId(bankId);
        return accountRepository.save(account).getId();
    }

    private UUID statement(User user, UUID accountId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO statement_imports (id, user_id, account_id, file_name, file_content) VALUES (?, ?, ?, ?, ?)",
                id, user.getId(), accountId, "statement.csv", "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private void deleteAccount(UUID accountId) {
        jdbc.update("UPDATE accounts SET deleted_at = now() WHERE id = ?", accountId);
    }

    private void deleteStatement(UUID statementId) {
        jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE id = ?", statementId);
    }

    private ResponseEntity<String> get(User admin, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, admin));
        return restTemplate.exchange("/api/v1/admin/users?" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode list(User admin, String query) throws Exception {
        ResponseEntity<String> response = get(admin, query);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).get("data");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("content").forEach(row -> ids.add(row.get("id").asText()));
        return ids;
    }

    private static JsonNode row(JsonNode page, User user) {
        for (JsonNode row : page.get("content")) {
            if (row.get("id").asText().equals(user.getId().toString())) return row;
        }
        throw new AssertionError("no row for " + user.getId());
    }

    @Test
    void plan_PLUS_matchesPaidTrialAndReferralPlus_notAnExpiredGrant() throws Exception {
        String tag = tag();
        User paid = customer(tag);
        subscription(paid, "PLUS", "ACTIVE");
        User trial = customer(tag);
        subscription(trial, "PLUS", "TRIAL");
        User referral = customer(tag);
        subscription(referral, "FREE", "ACTIVE");
        grant(referral, "PLUS", Instant.now().plus(30, ChronoUnit.DAYS));
        User expiredGrant = customer(tag);
        subscription(expiredGrant, "FREE", "ACTIVE");
        grant(expiredGrant, "PLUS", Instant.now().minus(1, ChronoUnit.DAYS));
        User cancelledPlus = customer(tag);
        subscription(cancelledPlus, "PLUS", "CANCELLED");
        User premium = customer(tag);
        subscription(premium, "PREMIUM", "ACTIVE");
        User admin = admin();

        JsonNode plus = list(admin, "q=" + tag + "&plan=plus");
        assertThat(ids(plus)).containsExactlyInAnyOrder(
                paid.getId().toString(), trial.getId().toString(), referral.getId().toString());
        assertThat(row(plus, referral).get("planCode").asText()).isEqualTo("PLUS");

        JsonNode free = list(admin, "q=" + tag + "&plan=FREE");
        assertThat(ids(free)).containsExactlyInAnyOrder(expiredGrant.getId().toString(), cancelledPlus.getId().toString());
        assertThat(row(free, expiredGrant).get("planCode").asText()).isEqualTo("FREE");

        assertThat(ids(list(admin, "q=" + tag + "&plan=PREMIUM"))).containsExactly(premium.getId().toString());
    }

    @Test
    void plan_FREE_includesAUserWithNoPlanAtAll() throws Exception {
        String tag = tag();
        User none = customer(tag);
        User admin = admin();

        JsonNode free = list(admin, "q=" + tag + "&plan=FREE");
        assertThat(ids(free)).containsExactly(none.getId().toString());
        assertThat(row(free, none).get("planCode").isNull()).isTrue();
    }

    @Test
    void joined_isWholeDaysInIndiaTime_bothEndsIncluded() throws Exception {
        String tag = tag();
        User lateEvening = customer(tag);
        joinedAt(lateEvening, Instant.parse("2026-11-10T19:00:00Z")); // 00:30 on 11 Nov in India
        User endOfDay = customer(tag);
        joinedAt(endOfDay, Instant.parse("2026-11-11T18:29:59Z")); // 23:59:59 on 11 Nov in India
        User nextDay = customer(tag);
        joinedAt(nextDay, Instant.parse("2026-11-11T18:30:00Z")); // 00:00 on 12 Nov in India
        User admin = admin();

        assertThat(ids(list(admin, "q=" + tag + "&joinedFrom=2026-11-11&joinedTo=2026-11-11")))
                .containsExactlyInAnyOrder(lateEvening.getId().toString(), endOfDay.getId().toString());
        assertThat(ids(list(admin, "q=" + tag + "&joinedTo=2026-11-10"))).isEmpty();
        assertThat(ids(list(admin, "q=" + tag + "&joinedFrom=2026-11-12"))).containsExactly(nextDay.getId().toString());
    }

    @Test
    void lastActiveTo_includesUsersNeverSeen_lastActiveFrom_leavesThemOut() throws Exception {
        String tag = tag();
        User neverSeen = customer(tag);
        User seenEarly = customer(tag);
        activeOn(seenEarly, LocalDate.of(2026, 10, 20));
        User admin = admin();

        JsonNode inactive = list(admin, "q=" + tag + "&lastActiveTo=2026-11-01");
        assertThat(ids(inactive)).containsExactlyInAnyOrder(neverSeen.getId().toString(), seenEarly.getId().toString());
        assertThat(row(inactive, neverSeen).get("lastActiveDate").isNull()).isTrue();
        assertThat(row(inactive, seenEarly).get("lastActiveDate").asText()).isEqualTo("2026-10-20");

        assertThat(ids(list(admin, "q=" + tag + "&lastActiveFrom=2026-10-01")))
                .containsExactly(seenEarly.getId().toString());
    }

    @Test
    void lastActive_goesByTheLatestDay() throws Exception {
        String tag = tag();
        User user = customer(tag);
        activeOn(user, LocalDate.of(2026, 11, 1));
        activeOn(user, LocalDate.of(2026, 11, 12));
        User admin = admin();

        assertThat(ids(list(admin, "q=" + tag + "&lastActiveTo=2026-11-05"))).isEmpty();
        assertThat(ids(list(admin, "q=" + tag + "&lastActiveFrom=2026-11-01&lastActiveTo=2026-11-05"))).isEmpty();
        assertThat(ids(list(admin, "q=" + tag + "&lastActiveFrom=2026-11-12&lastActiveTo=2026-11-12")))
                .containsExactly(user.getId().toString());
    }

    @Test
    void bank_matchesALiveAccountOnly() throws Exception {
        String tag = tag();
        User live = customer(tag);
        account(live, "HDFC");
        User deleted = customer(tag);
        deleteAccount(account(deleted, "HDFC"));
        User other = customer(tag);
        account(other, "ICICI");
        User admin = admin();

        assertThat(ids(list(admin, "q=" + tag + "&bankId=HDFC"))).containsExactly(live.getId().toString());
        assertThat(ids(list(admin, "q=" + tag + "&bankId=OTHER"))).isEmpty();
    }

    @Test
    void imports_leaveOutDeletedStatementsAndStatementsInDeletedAccounts() throws Exception {
        String tag = tag();
        User user = customer(tag);
        UUID live = account(user, "HDFC");
        statement(user, live);
        statement(user, live);
        deleteStatement(statement(user, live));
        UUID gone = account(user, "ICICI");
        statement(user, gone);
        deleteAccount(gone);
        User none = customer(tag);
        User admin = admin();

        JsonNode two = list(admin, "q=" + tag + "&importsMin=2&importsMax=2");
        assertThat(ids(two)).containsExactly(user.getId().toString());
        assertThat(row(two, user).get("importCount").asLong()).isEqualTo(2);

        JsonNode zero = list(admin, "q=" + tag + "&importsMax=0");
        assertThat(ids(zero)).containsExactly(none.getId().toString());
        assertThat(row(zero, none).get("importCount").asLong()).isZero();

        assertThat(ids(list(admin, "q=" + tag + "&importsMin=3"))).isEmpty();
        assertThat(ids(list(admin, "q=" + tag + "&importsMin=1"))).containsExactly(user.getId().toString());
    }

    @Test
    void filtersCombineWithSearchStatusAndPaging() throws Exception {
        String tag = tag();
        List<User> matching = new ArrayList<>();
        Instant base = Instant.parse("2026-11-01T06:00:00Z");
        for (int i = 0; i < 25; i++) {
            User user = customer(tag);
            joinedAt(user, base.plus(i, ChronoUnit.MINUTES));
            subscription(user, "PLUS", "ACTIVE");
            account(user, "SBI");
            matching.add(user);
        }
        User wrongPlan = customer(tag);
        account(wrongPlan, "SBI");
        User wrongStatus = customer(tag);
        subscription(wrongStatus, "PLUS", "ACTIVE");
        account(wrongStatus, "SBI");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", wrongStatus.getId());
        User admin = admin();

        String query = "q=" + tag + "&status=ACTIVE&plan=PLUS&bankId=SBI&joinedFrom=2026-11-01&size=20";
        JsonNode first = list(admin, query + "&page=0");
        JsonNode second = list(admin, query + "&page=1");

        assertThat(first.get("totalElements").asLong()).isEqualTo(25);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("content")).hasSize(20);
        assertThat(second.get("content")).hasSize(5);
        List<String> newestFirst = new ArrayList<>(matching.stream().map(u -> u.getId().toString()).toList());
        java.util.Collections.reverse(newestFirst);
        List<String> both = new ArrayList<>(ids(first));
        both.addAll(ids(second));
        assertThat(both).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void badFilterValues_are400WithAPlainMessage() {
        User admin = admin();
        for (String query : List.of("plan=GOLD", "importsMin=5&importsMax=2", "importsMin=-1",
                "joinedFrom=2026-11-12&joinedTo=2026-11-01", "lastActiveFrom=2026-11-12&lastActiveTo=2026-11-01",
                "joinedFrom=not-a-date", "joinedFrom=0001-01-01")) {
            ResponseEntity<String> response = get(admin, query);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void yearsFarOutsideAnyRealDate_are400_notAServerError() {
        User admin = admin();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, admin));
        // %2B is a literal +: ISO dates past year 9999 carry a sign. Sent as a URI so it is not re-encoded.
        for (String query : List.of("joinedTo=%2B999999999-12-31", "joinedFrom=%2B275760-09-13",
                "lastActiveTo=%2B999999999-12-31", "lastActiveFrom=%2B275760-09-13")) {
            java.net.URI uri = java.net.URI.create(restTemplate.getRootUri() + "/api/v1/admin/users?" + query);
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode()).as(query + " " + response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void searchText_withPercent_matchesLiterally() throws Exception {
        String tag = tag();
        User percent = user(tag + "50%x", "USER");
        User plain = user(tag + "50ax", "USER");
        User admin = admin();

        // RestTemplate's URI template encoding turns this % into %25 on the wire.
        assertThat(ids(list(admin, "q=" + tag + "50%x"))).containsExactly(percent.getId().toString());
        assertThat(ids(list(admin, "q=" + tag + "50"))).containsExactlyInAnyOrder(
                percent.getId().toString(), plain.getId().toString());
    }

    @Test
    void plainUser_isStillForbidden() {
        User user = customer(tag());
        assertThat(get(user, "plan=PLUS").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
