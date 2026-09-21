package com.finora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.mfa.TotpGenerator;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA 3.3.1 through the real Spring Security chain, over real HTTP with real rows: an admin who has
 * not enrolled in MFA is refused everywhere except the enrolment screens, can enrol, and is through
 * on the very next request using the SAME session token they held before enrolling. The unit test
 * proves the filter in isolation; this proves it is wired in, in the right place, with the real
 * authority set and the real repository.
 */
@TestPropertySource(properties = {"app.admin-mfa.enabled=true", "app.admin-mfa.enforced=true"})
class AdminMfaEnforcementIT extends AbstractIntegrationTest {

    /** Any endpoint an admin reaches with SYSTEM_SETTINGS; what matters is that it is not on the allow-list. */
    private static final String ADMIN_ENDPOINT = "/api/v1/admin/trusted-senders";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private final ObjectMapper mapper = new ObjectMapper();

    private User newUser(String scope, String role) {
        User user = new User();
        user.setEmail("mfa-enforce-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("MFA Enforcement Fixture");
        user.setRole(role);
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders headersFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> call(HttpHeaders headers, HttpMethod method, String path, String body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("an admin who has not enrolled is refused an admin endpoint with MFA_ENROLLMENT_REQUIRED")
    void anUnenrolledAdminIsRefused() throws Exception {
        HttpHeaders admin = headersFor(newUser(User.SCOPE_ADMIN, "ADMIN"));

        ResponseEntity<String> response = call(admin, HttpMethod.GET, ADMIN_ENDPOINT, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("errorCode").asText()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
    }

    @Test
    @DisplayName("the enrolment screens stay reachable, and enrolling lets the SAME session token through")
    void enrollingClearsTheGateForTheExistingSession() throws Exception {
        HttpHeaders admin = headersFor(newUser(User.SCOPE_ADMIN, "ADMIN"));
        assertThat(call(admin, HttpMethod.GET, ADMIN_ENDPOINT, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> status = call(admin, HttpMethod.GET, "/api/v1/admin-mfa/status", null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(status.getBody()).at("/data/enabled").asBoolean()).isFalse();

        ResponseEntity<String> enroll = call(admin, HttpMethod.POST, "/api/v1/admin-mfa/enroll", null);
        assertThat(enroll.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret = mapper.readTree(enroll.getBody()).at("/data/secret").asText();
        assertThat(secret).isNotBlank();

        // Still gated until the code is proven: a scanned-but-unconfirmed secret does not count.
        assertThat(call(admin, HttpMethod.GET, ADMIN_ENDPOINT, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> wrong = call(admin, HttpMethod.POST, "/api/v1/admin-mfa/confirm", "{\"code\":\"000000\"}");
        assertThat(wrong.getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(call(admin, HttpMethod.GET, ADMIN_ENDPOINT, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> confirm = call(admin, HttpMethod.POST, "/api/v1/admin-mfa/confirm",
                "{\"code\":\"" + TotpGenerator.currentCode(secret) + "\"}");
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(confirm.getBody()).at("/data/recoveryCodes").size()).isEqualTo(10);

        // Same headers, same token, now through.
        assertThat(call(admin, HttpMethod.GET, ADMIN_ENDPOINT, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an ordinary user account is never gated")
    void anOrdinaryUserIsNotAffected() {
        HttpHeaders user = headersFor(newUser(User.SCOPE_USER, "USER"));

        ResponseEntity<String> response = call(user, HttpMethod.GET, "/api/v1/accounts", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("signing out stays reachable for a gated admin, so they can always end the session")
    void aGatedAdminCanStillSignOut() {
        HttpHeaders admin = headersFor(newUser(User.SCOPE_ADMIN, "ADMIN"));

        ResponseEntity<String> response = call(admin, HttpMethod.POST, "/api/v1/auth/logout", "{}");

        // Whatever logout answers, it must not be the MFA gate's 403.
        assertThat(response.getBody() == null ? "" : response.getBody()).doesNotContain("MFA_ENROLLMENT_REQUIRED");
    }
}
