package com.finora.goals;

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

/**
 * The goal endpoints through the real security chain, controller advice and database, fed the
 * exact inputs that broke them in live testing (2026-10-24): values the goals table cannot hold
 * came back as a 409 "conflicts with a record that already exists -- refresh and try again", and a
 * huge-exponent contribution came back as a 500.
 */
class GoalControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private GoalContributionRepository contributionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("goal-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Goal IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> post(String path, String body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private UUID createGoal(User user, String body) throws Exception {
        ResponseEntity<String> created = post("/api/v1/goals", body, user);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json(created).get("data").get("id").asText());
    }

    @Test
    void aTargetTheColumnCannotHold_isA400NamingTheField_notA409() throws Exception {
        ResponseEntity<String> response = post("/api/v1/goals",
                "{\"name\":\"Big\",\"targetAmount\":1000000000000}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json(response).get("message").asText()).startsWith("targetAmount:");
    }

    @Test
    void aNameTheColumnCannotHold_isA400NamingTheField_notA409() throws Exception {
        ResponseEntity<String> response = post("/api/v1/goals",
                "{\"name\":\"" + "a".repeat(121) + "\",\"targetAmount\":100}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).startsWith("name:");
    }

    @Test
    void theLargestStorableTargetAndALongestName_stillSave() throws Exception {
        User user = createUser();
        UUID id = createGoal(user, "{\"name\":\"" + "a".repeat(120) + "\",\"targetAmount\":999999999999.99}");

        assertThat(goalRepository.findById(id).orElseThrow().getTargetAmount())
                .isEqualByComparingTo(GoalDto.MAX_AMOUNT);
    }

    @Test
    void aHugeExponentContribution_isA400_notA500() throws Exception {
        User user = createUser();
        UUID id = createGoal(user, "{\"name\":\"Trip\",\"targetAmount\":50000}");

        ResponseEntity<String> response = post("/api/v1/goals/" + id + "/contributions",
                "{\"amount\":1e999999999}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).startsWith("amount:");
    }

    @Test
    void aContributionFinerThanOnePaisa_isRejected_soTheResponseCanNeverDisagreeWithWhatIsStored() throws Exception {
        User user = createUser();
        UUID id = createGoal(user, "{\"name\":\"Frac\",\"targetAmount\":1}");

        ResponseEntity<String> response = post("/api/v1/goals/" + id + "/contributions",
                "{\"amount\":0.015}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(contributionRepository.findByGoalIdOrderByContributedAtDesc(id)).isEmpty();
    }

    @Test
    void aContributionThatWouldOverflowTheTotal_isA400WithItsOwnCode_andChangesNothing() throws Exception {
        User user = createUser();
        UUID id = createGoal(user,
                "{\"name\":\"NearMax\",\"targetAmount\":999999999999.99,\"currentAmount\":999999999999.00}");

        ResponseEntity<String> response = post("/api/v1/goals/" + id + "/contributions",
                "{\"amount\":1.00}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("GOAL_001");
        assertThat(goalRepository.findById(id).orElseThrow().getCurrentAmount())
                .isEqualByComparingTo("999999999999.00");
        // Only the starting-amount contribution create() records.
        assertThat(contributionRepository.findByGoalIdOrderByContributedAtDesc(id)).hasSize(1);
    }

    @Test
    void aNameWithANulCharacter_isA400_notA409() throws Exception {
        ResponseEntity<String> response = post("/api/v1/goals",
                "{\"name\":\"Trip\\u0000x\",\"targetAmount\":100}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).startsWith("name:");
    }

    @Test
    void aTargetDateTheDatabaseCannotHold_isA400_notA409() throws Exception {
        ResponseEntity<String> response = post("/api/v1/goals",
                "{\"name\":\"Far\",\"targetAmount\":100,\"targetDate\":\"+999999999-12-31\"}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).startsWith("targetDate:");
    }

    @Test
    void aFiveDigitTargetYear_isRefused_ratherThanSavedInAFormClientsMisread() throws Exception {
        ResponseEntity<String> response = post("/api/v1/goals",
                "{\"name\":\"Far\",\"targetAmount\":100,\"targetDate\":\"+10000-01-01\"}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
