package com.finora.inflow;

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

/** Plan 2's endpoints through the real security chain, controller advice and database. */
class InflowControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("inflow-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Inflow IT Test User");
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

    private ResponseEntity<String> get(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, User user) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    @Test
    void kindsRoundTrip() throws Exception {
        User user = createUser();
        JsonNode list = mapper.readTree(get("/api/v1/inflow-kinds", user).getBody()).get("data");
        assertThat(list).hasSize(5);

        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"Rent from tenant\",\"countsAsIncome\":true}", user);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = mapper.readTree(created.getBody()).get("data").get("id").asText();

        ResponseEntity<String> dup = send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"rent from TENANT\",\"countsAsIncome\":false}", user);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(dup.getBody()).get("errorCode").asText()).isEqualTo("TXN_006");

        ResponseEntity<String> renamed = send(HttpMethod.PATCH, "/api/v1/inflow-kinds/" + id,
                "{\"name\":\"Rent\",\"countsAsIncome\":false}", user);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(renamed.getBody()).get("data").get("countsAsIncome").asBoolean()).isFalse();

        assertThat(send(HttpMethod.DELETE, "/api/v1/inflow-kinds/" + id, null, user).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(get("/api/v1/inflow-kinds", user).getBody()).get("data")).hasSize(5);
    }

    @Test
    void aBlankNameIsRejected() {
        User user = createUser();
        assertThat(send(HttpMethod.POST, "/api/v1/inflow-kinds", "{\"name\":\"  \",\"countsAsIncome\":true}", user)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anotherUsersKindIsForbidden() throws Exception {
        User owner = createUser(), other = createUser();
        String id = mapper.readTree(send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"Mine\",\"countsAsIncome\":true}", owner).getBody()).get("data").get("id").asText();
        assertThat(send(HttpMethod.DELETE, "/api/v1/inflow-kinds/" + id, null, other).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unresolvedInflowsRequiresBothDates() {
        User user = createUser();
        assertThat(get("/api/v1/transactions/unresolved-inflows?startDate=2026-08-01", user).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/transactions/unresolved-inflows?startDate=2026-08-01&endDate=2026-08-31", user)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/sender-inflow-rules", user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void requiresASession() {
        assertThat(restTemplate.getForEntity("/api/v1/inflow-kinds", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
