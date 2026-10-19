package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2a spec §6: an admin marks a USER-scope account as internal (Sid's own, a tester's) so the
 * activation page leaves it out of every count. Set from the admin user page, audited.
 */
class AdminUserInternalAccountIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void setsAndClearsTheFlag() throws Exception {
        User admin = staff("ADMIN");
        User target = customer();

        assertThat(setInternal(admin, target, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isInternalAccount()).isTrue();
        assertThat(detail(admin, target).get("internalAccount").asBoolean()).isTrue();

        assertThat(setInternal(admin, target, false).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isInternalAccount()).isFalse();
        assertThat(detail(admin, target).get("internalAccount").asBoolean()).isFalse();
    }

    @Test
    void newAccountsAreNotInternal() throws Exception {
        JsonNode detail = detail(staff("ADMIN"), customer());
        assertThat(detail.get("internalAccount").asBoolean()).isFalse();
        assertThat(detail.get("accountScope").asText()).as("the page offers the flag for USER accounts only").isEqualTo("USER");
    }

    @Test
    void auditsEachChange() {
        User admin = staff("ADMIN");
        User target = customer();

        setInternal(admin, target, true);
        setInternal(admin, target, false);

        List<String> metadata = jdbc.queryForList(
                "SELECT metadata::text FROM audit_logs WHERE action = 'USER_INTERNAL_ACCOUNT_SET' AND entity_id = ? "
                        + "ORDER BY created_at", String.class, target.getId());
        assertThat(metadata).hasSize(2);
        assertThat(metadata.get(0)).contains("\"internal\": true").contains(admin.getId().toString());
        assertThat(metadata.get(1)).contains("\"internal\": false");
    }

    /** Setting the value it already has changes nothing, so it writes no audit entry either. */
    @Test
    void settingTheSameValueAgainIsNotAudited() {
        User admin = staff("ADMIN");
        User target = customer();

        setInternal(admin, target, false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'USER_INTERNAL_ACCOUNT_SET' "
                + "AND entity_id = ?", Long.class, target.getId())).isZero();
    }

    /** Admin accounts are already left out of the activation counts; the flag is for USER accounts. */
    @Test
    void refusesAdminScopeAccount() {
        User admin = staff("ADMIN");
        User otherAdmin = staff("ADMIN");

        assertThat(setInternal(admin, otherAdmin, true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findById(otherAdmin.getId()).orElseThrow().isInternalAccount()).isFalse();
    }

    @Test
    void needsUserUpdate() {
        // READ_ONLY can view users (USER_VIEW) but holds no USER_UPDATE.
        User viewer = staff("READ_ONLY");
        User target = customer();

        assertThat(setInternal(viewer, target, true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userRepository.findById(target.getId()).orElseThrow().isInternalAccount()).isFalse();
    }

    @Test
    void aMissingValueIsRefused() {
        User admin = staff("ADMIN");
        User target = customer();

        assertThat(put(admin, target, "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anUnknownAccountIsNotFound() {
        User admin = staff("ADMIN");
        User ghost = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(ghost, "id", UUID.randomUUID());

        assertThat(setInternal(admin, ghost, true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ResponseEntity<String> setInternal(User actor, User target, boolean internal) {
        return put(actor, target, "{\"internal\": " + internal + "}");
    }

    private ResponseEntity<String> put(User actor, User target, String body) {
        HttpHeaders headers = bearerFor(actor);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/admin/users/" + target.getId() + "/internal", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode detail(User actor, User target) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/users/" + target.getId(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(actor)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private User customer() {
        User user = new User();
        user.setEmail("internal-flag-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Internal Flag Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** An admin-portal account with one seeded role (V16/V31). */
    private User staff(String role) {
        User user = new User();
        user.setEmail("internal-flag-it-staff-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Internal Flag Test Staff");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }
}
