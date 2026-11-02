package com.finora.budgets;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing a budget, and saving one, over HTTP against real Postgres (found 2026-11-02): there was
 * no way to remove a budget at all, and four simultaneous saves of one new budget left one row and
 * three 409s, because budgets' UNIQUE(user_id, category_id) also covers soft-deleted rows and the
 * save was a find-then-insert.
 */
class BudgetRemoveAndRaceIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("budget-remove-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Budget Remove IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private UUID setBudget(User user, String category, String limit) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.PUT, "/api/v1/budgets",
                "{\"categoryName\":\"" + category + "\",\"monthlyLimit\":" + limit + "}", user);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json(r).get("data").get("id").asText());
    }

    private JsonNode list(User user) throws Exception {
        return json(call(HttpMethod.GET, "/api/v1/budgets", null, user)).get("data");
    }

    @Test
    void removingABudget_takesItOffTheList_andRecordsIt() throws Exception {
        User user = createUser();
        UUID dining = setBudget(user, "Dining", "5000");
        setBudget(user, "Fuel", "2000");

        ResponseEntity<String> removed = call(HttpMethod.DELETE, "/api/v1/budgets/" + dining, null, user);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode left = list(user);
        assertThat(left).hasSize(1);
        assertThat(left.get(0).get("categoryName").asText()).isEqualTo("Fuel");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'BUDGET_DELETED'",
                Long.class, dining)).isEqualTo(1L);
    }

    @Test
    void aRemovedBudget_canBeSetAgainForTheSameCategory() throws Exception {
        User user = createUser();
        UUID first = setBudget(user, "Dining", "5000");
        call(HttpMethod.DELETE, "/api/v1/budgets/" + first, null, user);

        UUID again = setBudget(user, "dining", "3000");

        JsonNode left = list(user);
        assertThat(left).hasSize(1);
        assertThat(left.get(0).get("monthlyLimit").decimalValue()).isEqualByComparingTo("3000");
        assertThat(again).isEqualTo(first);
    }

    @Test
    void someoneElsesBudget_orOneThatDoesNotExist_isNotFound_andStays() throws Exception {
        User owner = createUser();
        User other = createUser();
        UUID dining = setBudget(owner, "Dining", "5000");

        assertThat(call(HttpMethod.DELETE, "/api/v1/budgets/" + dining, null, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/v1/budgets/" + UUID.randomUUID(), null, owner).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/api/v1/budgets/not-a-uuid", null, owner).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(list(owner)).hasSize(1);
    }

    @Test
    void removingTheSameBudgetTwice_isNotFoundTheSecondTime() throws Exception {
        User user = createUser();
        UUID dining = setBudget(user, "Dining", "5000");

        assertThat(call(HttpMethod.DELETE, "/api/v1/budgets/" + dining, null, user).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.DELETE, "/api/v1/budgets/" + dining, null, user).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void simultaneousSavesOfOneNewBudget_allSucceed_andLeaveOneRow() throws Exception {
        User user = createUser();
        setBudget(user, "Groceries", "100");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ResponseEntity<String>>> saves = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                saves.add(pool.submit(() -> call(HttpMethod.PUT, "/api/v1/budgets",
                        "{\"categoryName\":\"Fuel\",\"monthlyLimit\":2000}", user)));
            }
            for (Future<ResponseEntity<String>> save : saves) {
                assertThat(save.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budgets b JOIN categories c ON c.id = b.category_id "
                + "WHERE b.user_id = ? AND c.name = 'Fuel'", Long.class, user.getId())).isEqualTo(1L);
    }
}
