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
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Smart Insights usage tile end to end -- POSTing a view against a real (empty)
 *  feature_view_counts table, then reading the real count back. */
class FeatureUsageControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("feature-usage-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Feature Usage IT Test User");
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

    @Test
    void viewCount_isZero_forAUserWhoHasNeverViewedTheFeature() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/usage/insights/view-count", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("viewCount").asInt()).isZero();
    }

    @Test
    void recordView_incrementsTheCount_onEachRealCall() throws Exception {
        User user = createUser();
        HttpHeaders headers = bearerFor(user);

        restTemplate.exchange("/api/v1/usage/insights/view", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        restTemplate.exchange("/api/v1/usage/insights/view", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/usage/insights/view-count", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("viewCount").asInt()).isEqualTo(2);
    }

    @Test
    void recordView_rejectsAnUnrecognizedFeature() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/usage/not-a-real-feature/view", HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void viewCount_isolatesCountsPerUser() throws Exception {
        User user1 = createUser();
        User user2 = createUser();

        restTemplate.exchange("/api/v1/usage/insights/view", HttpMethod.POST, new HttpEntity<>(bearerFor(user1)), String.class);

        ResponseEntity<String> user2Response = restTemplate.exchange(
                "/api/v1/usage/insights/view-count", HttpMethod.GET, new HttpEntity<>(bearerFor(user2)), String.class);

        JsonNode data = mapper.readTree(user2Response.getBody()).get("data");
        assertThat(data.get("viewCount").asInt()).isZero();
    }
}
