package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SignupAttributionRepository;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard's "sign-ups by source" list (GET /api/v1/admin/stats/signup-sources) against the
 * real query. The IT suite shares one database, so a tag no other test uses gives an exact row,
 * and the shared lines ("direct", "unknown (before tracking)") are measured as the change these
 * users make.
 */
class AdminSignupSourcesIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Instant AFTER_TRACKING = Instant.parse("2034-01-04T06:00:00Z");
    private static final Instant BEFORE_TRACKING = Instant.parse("2020-02-05T06:00:00Z");

    @Test
    void countsByTagAndLeavesOutInternalAndDeletedAccounts() throws Exception {
        String tag = tag();
        attributed(user(AFTER_TRACKING), tag);
        attributed(user(BEFORE_TRACKING), tag); // a row says where from, whenever they signed up
        User internal = user(AFTER_TRACKING);
        attributed(internal, tag);
        jdbc.update("UPDATE users SET internal_account = true WHERE id = ?", internal.getId());
        User deleted = user(AFTER_TRACKING);
        attributed(deleted, tag);
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deleted.getId());
        User pending = user(AFTER_TRACKING);
        attributed(pending, tag);
        jdbc.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", pending.getId());

        assertThat(count(sources(), tag)).isEqualTo(2);
    }

    @Test
    void nothingRecordedIsDirectAfterTrackingAndUnknownBefore() throws Exception {
        JsonNode before = sources();

        user(AFTER_TRACKING);
        user(BEFORE_TRACKING);
        user(BEFORE_TRACKING);

        JsonNode after = sources();
        assertThat(count(after, "direct") - count(before, "direct")).isEqualTo(1);
        assertThat(count(after, SignupAttributionRepository.UNKNOWN_BEFORE_TRACKING)
                - count(before, SignupAttributionRepository.UNKNOWN_BEFORE_TRACKING)).isEqualTo(2);
    }

    @Test
    void aReferralBeforeTrackingIsStillAReferral() throws Exception {
        JsonNode before = sources();
        User friend = user(AFTER_TRACKING);
        User referred = user(BEFORE_TRACKING);
        jdbc.update("INSERT INTO referrals (referrer_user_id, referred_user_id, status) VALUES (?, ?, 'PENDING')",
                friend.getId(), referred.getId());

        JsonNode after = sources();
        assertThat(count(after, "referral") - count(before, "referral")).isEqualTo(1);
    }

    @Test
    void aTagThatLooksLikeAnEmailIsWithheld() throws Exception {
        String email = "it-" + tag() + "@example.com";
        attributed(user(AFTER_TRACKING), email);

        ResponseEntity<String> response = get();
        assertThat(response.getBody()).doesNotContain(email);
        assertThat(count(mapper.readTree(response.getBody()).get("data"), "(withheld: looks like contact details)"))
                .isGreaterThanOrEqualTo(1);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private User user(Instant createdAt) {
        User user = new User();
        user.setEmail("signup-sources-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Signup Sources IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private void attributed(User user, String utmSource) {
        jdbc.update("INSERT INTO signup_attributions (user_id, utm_source) VALUES (?, ?)", user.getId(), utmSource);
    }

    /** Letters only: a run of digits would read as a phone number and be withheld. */
    private static String tag() {
        StringBuilder out = new StringBuilder("it-src-");
        for (int i = 0; i < 12; i++) out.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return out.toString();
    }

    private ResponseEntity<String> get() {
        User admin = new User();
        admin.setEmail("signup-sources-it-admin-" + UUID.randomUUID() + "@example.com");
        admin.setPasswordHash("irrelevant-for-this-test");
        admin.setFullName("Signup Sources Admin");
        admin.setRole("ADMIN");
        admin.setAccountScope(User.SCOPE_ADMIN);
        admin.setPhoneVerified(true);
        admin = userRepository.save(admin);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, admin));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/stats/signup-sources",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private JsonNode sources() throws Exception {
        return mapper.readTree(get().getBody()).get("data");
    }

    private static long count(JsonNode rows, String source) {
        for (JsonNode row : rows) {
            if (source.equals(row.get("source").asText())) return row.get("users").asLong();
        }
        return 0;
    }
}
