package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Background jobs endpoints. Uses a daily-cron job's key for the crashed row so the real job
 * cannot overwrite it mid-test (it only runs at 03:30 server time).
 */
class AdminBackgroundJobsControllerIT extends AbstractIntegrationTest {

    private static final String DAILY_JOB = "SharedCorpusRetentionSweepService.sweep";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM background_job_failures WHERE job_key IN (?, ?)", DAILY_JOB, "Deleted.longAgo");
        jdbc.update("DELETE FROM background_job_status WHERE job_key IN (?, ?)", DAILY_JOB, "Deleted.longAgo");
    }

    private User admin(String role, String scope) {
        User user = new User();
        user.setEmail("background-jobs-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Background Jobs IT User");
        user.setRole(role);
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> get(String path, User caller) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, caller));
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void crashedRow(String key) {
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update("""
                INSERT INTO background_job_status (job_key, last_started_at, last_finished_at, last_outcome,
                    consecutive_failures, broken_since, last_instance)
                VALUES (?, ?, ?, 'CRASHED', 2, ?, 'test')
                """, key, at, at, at);
    }

    private void failureRow(String key, Instant at, String summary) {
        jdbc.update("""
                INSERT INTO background_job_failures (job_key, occurred_at, outcome, failed_items, error_summary)
                VALUES (?, ?, 'CRASHED', 0, ?)
                """, key, Timestamp.from(at), summary);
    }

    @Test
    void listRequiresDiagnosticsPermission() {
        User consumer = admin("USER", User.SCOPE_USER);
        assertThat(get("/api/v1/admin/background-jobs", consumer).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(get("/api/v1/admin/background-jobs/" + DAILY_JOB + "/failures", consumer)
                .getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void listsOnlyLiveJobs_brokenFirst() throws Exception {
        crashedRow(DAILY_JOB);
        crashedRow("Deleted.longAgo");

        ResponseEntity<String> response = get("/api/v1/admin/background-jobs", admin("ADMIN", User.SCOPE_ADMIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode jobs = mapper.readTree(response.getBody()).get("data");
        List<String> keys = new ArrayList<>();
        List<String> states = new ArrayList<>();
        jobs.forEach(j -> {
            keys.add(j.get("key").asText());
            states.add(j.get("state").asText());
        });
        assertThat(keys).contains(DAILY_JOB).doesNotContain("Deleted.longAgo");
        int crashed = keys.indexOf(DAILY_JOB);
        assertThat(states.get(crashed)).isEqualTo("CRASHED");
        assertThat(states.subList(0, crashed)).allMatch(s -> List.of("CRASHED", "PARTLY_FAILED", "OVERDUE").contains(s));
        int firstOk = states.indexOf("OK");
        if (firstOk >= 0) assertThat(crashed).isLessThan(firstOk);

        JsonNode job = jobs.get(crashed);
        assertThat(job.get("name").asText()).isEqualTo("Shared category evidence cleanup");
        assertThat(job.get("purpose").asText()).isNotBlank();
        assertThat(job.get("schedule").asText()).startsWith("daily at");
        assertThat(job.get("consecutiveFailures").asInt()).isEqualTo(2);
        assertThat(job.get("brokenSince").isNull()).isFalse();
        assertThat(job.get("running").asBoolean()).isFalse();
    }

    @Test
    void failuresAreTypeAndLocationOnly_newestFirst_last30Days() throws Exception {
        crashedRow(DAILY_JOB);
        Instant now = Instant.now();
        failureRow(DAILY_JOB, now.minus(Duration.ofDays(31)), "OldException at Old:1");
        failureRow(DAILY_JOB, now.minus(Duration.ofHours(2)), "IllegalStateException at A:2");
        failureRow(DAILY_JOB, now.minus(Duration.ofMinutes(5)), "IllegalArgumentException at B:3");

        ResponseEntity<String> response = get("/api/v1/admin/background-jobs/" + DAILY_JOB + "/failures",
                admin("ADMIN", User.SCOPE_ADMIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = mapper.readTree(response.getBody()).get("data");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("errorSummary").asText()).isEqualTo("IllegalArgumentException at B:3");
        assertThat(rows.get(1).get("errorSummary").asText()).isEqualTo("IllegalStateException at A:2");
        assertThat(rows.get(0).get("outcome").asText()).isEqualTo("CRASHED");
    }

    @Test
    void unknownKeyIs404() {
        ResponseEntity<String> response = get("/api/v1/admin/background-jobs/NoSuch.job/failures",
                admin("ADMIN", User.SCOPE_ADMIN));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void dashboardCountsJobsNeedingAttention() throws Exception {
        crashedRow(DAILY_JOB);

        ResponseEntity<String> response = get("/api/v1/admin/dashboard/overview", admin("ADMIN", User.SCOPE_ADMIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode attention = mapper.readTree(response.getBody()).get("data").get("needsAttention");
        assertThat(attention.get("backgroundJobsNeedingAttention").asLong()).isGreaterThanOrEqualTo(1);
    }
}
