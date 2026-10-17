package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.InsightAcknowledgementRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Got it" on an Insights row: the app hides that row for good, on every device, so the choice is
 * stored on the server. Design: docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md.
 */
class InsightAcknowledgementsIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/insights/acknowledgements";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private InsightAcknowledgementRepository acknowledgements;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("insight-ack-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Insight Ack IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(User user, String kind, String itemKey) {
        String body = "{\"kind\":" + (kind == null ? "null" : "\"" + kind + "\"")
                + ",\"itemKey\":" + (itemKey == null ? "null" : "\"" + itemKey + "\"") + "}";
        return restTemplate.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    private JsonNode list(User user) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void postThenGet_returnsTheKey() throws Exception {
        User user = createUser();

        assertThat(post(user, "CATEGORY_HIGH", "Food|2026-09").getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = list(user);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("kind").asText()).isEqualTo("CATEGORY_HIGH");
        assertThat(data.get(0).get("itemKey").asText()).isEqualTo("Food|2026-09");
    }

    @Test
    void postTwice_isIdempotent() throws Exception {
        User user = createUser();

        assertThat(post(user, "CARD_STATEMENT_MISSING", "2026-09").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(user, "CARD_STATEMENT_MISSING", "2026-09").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(list(user)).hasSize(1);
        assertThat(acknowledgements.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void getReturnsOnlyOwnKeys() throws Exception {
        User owner = createUser();
        User someoneElse = createUser();
        post(owner, "AMOUNT_CHANGED", "sample merchant|2026-09-12");

        assertThat(list(someoneElse)).isEmpty();
    }

    @Test
    void unknownKind_is400() {
        assertThat(post(createUser(), "SOMETHING_ELSE", "x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankOrMissingKey_is400() {
        User user = createUser();
        assertThat(post(user, "CATEGORY_HIGH", "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(user, "CATEGORY_HIGH", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(user, null, "Food|2026-09").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooLongKey_is400() {
        assertThat(post(createUser(), "CATEGORY_HIGH", "x".repeat(301)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticated_is401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
