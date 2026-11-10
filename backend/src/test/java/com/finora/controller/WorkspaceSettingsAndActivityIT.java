package com.finora.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserSettingsRepository;
import com.finora.security.JwtService;
import com.finora.service.AuditService;
import com.finora.testsupport.TestSessions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Workspace break-test fixes (2026-11-10), over real HTTP:
 * <ul>
 *   <li>the auto-apply threshold accepts only a whole number from 0 to 100 -- a missing value used
 *       to be stored as 0, which takes every uncategorized guess out of the review queue;</li>
 *   <li>a user's first saves landing at once all succeed (they used to collide: one 200, seven 409s);</li>
 *   <li>the customer's own dashboard no longer carries audit rows, where staff actions on their
 *       account (an admin claiming their ticket, adding an internal note) are filed with the
 *       admin's id -- while the admin portal's view of the same user still does.</li>
 * </ul>
 */
class WorkspaceSettingsAndActivityIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSettingsRepository userSettingsRepository;
    @Autowired private AuditService auditService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("workspace-fixes-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Workspace Fixes IT User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> putSettings(User user, String body) {
        return restTemplate.exchange("/api/v1/workspace/settings", HttpMethod.PUT,
                new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    private int storedThreshold(User user) throws Exception {
        ResponseEntity<String> got = restTemplate.exchange("/api/v1/workspace/settings", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        return mapper.readTree(got.getBody()).get("data").get("autoApplyConfidenceThreshold").asInt();
    }

    @Test
    void aSaveWithoutAWholeNumberFrom0To100_isRefused_andTheStoredValueStays() throws Exception {
        User user = createUser("USER");
        assertThat(putSettings(user, "{\"autoApplyConfidenceThreshold\": 75}").getStatusCode()).isEqualTo(HttpStatus.OK);

        for (String body : List.of("{}", "{\"autoApplyConfidenceThreshold\": null}", "{\"autoApplyConfidenceThreshold\": 50.9}",
                "{\"autoApplyConfidenceThreshold\": \"60\"}", "{\"autoApplyConfidenceThreshold\": " + (Integer.MAX_VALUE + 1L) + "}",
                "{\"autoApplyConfidenceThreshold\": 101}", "{\"autoApplyConfidenceThreshold\": -1}")) {
            ResponseEntity<String> response = putSettings(user, body);
            assertThat(response.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(storedThreshold(user)).as("stored after " + body).isEqualTo(75);
        }

        String fraction = putSettings(user, "{\"autoApplyConfidenceThreshold\": 50.9}").getBody();
        assertThat(mapper.readTree(fraction).get("message").asText())
                .isEqualTo("autoApplyConfidenceThreshold: must be a whole number");
        String missing = putSettings(user, "{}").getBody();
        assertThat(mapper.readTree(missing).get("message").asText()).contains("Choose a confidence threshold");

        assertThat(putSettings(user, "{\"autoApplyConfidenceThreshold\": 0}").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storedThreshold(user)).isZero();
    }

    @Test
    void firstSavesLandingAtOnce_allSucceed_andLeaveOneRow() throws Exception {
        // Several fresh users, a burst each: one burst can happen to serialize, five in a row did not
        // (before the upsert, the local probe saw one 200 and seven 409s from a single burst of 8).
        int calls = 12;
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            for (int round = 0; round < 5; round++) {
                User user = createUser("USER");
                HttpHeaders headers = bearerFor(user);
                CountDownLatch start = new CountDownLatch(1);
                List<CompletableFuture<HttpStatus>> results = new java.util.ArrayList<>();
                for (int i = 0; i < calls; i++) {
                    String body = "{\"autoApplyConfidenceThreshold\": " + (40 + i) + "}";
                    results.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return (HttpStatus) restTemplate.exchange("/api/v1/workspace/settings", HttpMethod.PUT,
                                new HttpEntity<>(body, headers), String.class).getStatusCode();
                    }, pool));
                }
                start.countDown();
                for (CompletableFuture<HttpStatus> result : results) {
                    assertThat(result.get(30, TimeUnit.SECONDS)).as("round " + round).isEqualTo(HttpStatus.OK);
                }
                assertThat(userSettingsRepository.findByUserId(user.getId())).isPresent();
                assertThat(storedThreshold(user)).isBetween(40, 40 + calls - 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theCustomersOwnDashboardCarriesNoAuditRows_butTheAdminPortalsViewStillDoes() throws Exception {
        User customer = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = UUID.randomUUID();
        auditService.record(customer.getId(), "SUPPORT_TICKET_NOTE_ADDED", "SupportTicket", ticketId,
                Map.of("actorId", admin.getId().toString(), "ticketNumber", "SUP-999999"));

        ResponseEntity<String> own = restTemplate.exchange("/api/v1/workspace/dashboard", HttpMethod.GET,
                new HttpEntity<>(bearerFor(customer)), String.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode ownData = mapper.readTree(own.getBody()).get("data");
        assertThat(ownData.get("recentActivity")).isEmpty();
        assertThat(own.getBody()).doesNotContain(admin.getId().toString()).doesNotContain("SUPPORT_TICKET_NOTE_ADDED");
        // The health check still reads the real rows: there is activity, so logging reads healthy.
        assertThat(ownData.get("health")).isNotNull();

        ResponseEntity<String> adminView = restTemplate.exchange("/api/v1/admin/users/" + customer.getId() + "/workspace",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(adminView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminView.getBody()).contains("SUPPORT_TICKET_NOTE_ADDED").contains(admin.getId().toString());
    }
}
