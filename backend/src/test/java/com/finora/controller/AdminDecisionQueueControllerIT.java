package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Needs a decision" list at the HTTP layer: the real permission gate, the per-kind split by
 * the caller's own permissions, the real queries against Postgres, and no statement content on the
 * wire. Other ITs leave held jobs behind (the tables are shared), so every assertion looks only at
 * the jobs this class made.
 */
class AdminDecisionQueueControllerIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/admin/decision-queue";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("decision-queue-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Decision Queue IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User userWithOnly(String permission) {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals(permission)).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("DECISION_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Role for the decision queue IT");
        role.getPermissions().add(p);
        roleRepository.save(role);
        return createUser(role.getName());
    }

    private ResponseEntity<String> get(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return restTemplate.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ImportJob importHold(Duration heldFor) {
        User owner = createUser("USER");
        ImportJob job = new ImportJob(owner.getId(), "sample-june.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row found near \"Txn Date  Narration\"",
                "IllegalStateException", ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now().minus(heldFor));
        return importJobRepository.save(job);
    }

    private HeldStatement statementHold(Duration heldFor) {
        User owner = createUser("USER");
        ImportJob job = new ImportJob(owner.getId(), "sample-july.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(null, null, Instant.now().minus(heldFor));
        importJobRepository.save(job);
        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                "HLD-IT-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(null, held.getId(), Instant.now().minus(heldFor));
        importJobRepository.save(job);
        return held;
    }

    private ImportJob holdWithoutRecord(Duration heldFor) {
        User owner = createUser("USER");
        ImportJob job = new ImportJob(owner.getId(), "sample-august.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(null, null, Instant.now().minus(heldFor));
        return importJobRepository.save(job);
    }

    /** The rows for the given jobs, in the order the server returned them. */
    private List<JsonNode> rowsFor(ResponseEntity<String> response, UUID... jobIds) throws Exception {
        Set<String> wanted = new java.util.HashSet<>();
        for (UUID id : jobIds) wanted.add(id.toString());
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : mapper.readTree(response.getBody()).path("data")) {
            if (wanted.contains(row.path("jobId").asText())) rows.add(row);
        }
        return rows;
    }

    @Test
    void superAdminSeesEveryKindOverdueFirst() throws Exception {
        ImportJob fresh = importHold(Duration.ofHours(1));
        HeldStatement overdue = statementHold(Duration.ofHours(50));
        ImportJob orphan = holdWithoutRecord(Duration.ofHours(10));

        ResponseEntity<String> response = get(createUser("SUPER_ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> rows = rowsFor(response, fresh.getId(), overdue.getImportJobId(), orphan.getId());
        assertThat(rows).extracting(r -> r.path("kind").asText()).containsExactly("STATEMENT", "NO_RECORD", "IMPORT");
        assertThat(rows.get(0).path("heldId").asText()).isEqualTo(overdue.getHeldId());
        assertThat(rows.get(1).path("nextStep").asText()).isEqualTo("OPEN_REVIEW");
        Instant heldAt = Instant.parse(rows.get(2).path("heldAt").asText());
        Instant promiseAt = Instant.parse(rows.get(2).path("promiseAt").asText());
        assertThat(Duration.between(heldAt, promiseAt)).isEqualTo(Duration.ofHours(48));
    }

    @Test
    void importTriageOnlySeesImports() throws Exception {
        ImportJob imp = importHold(Duration.ofHours(1));
        HeldStatement stmt = statementHold(Duration.ofHours(1));

        ResponseEntity<String> response = get(userWithOnly("IMPORT_TRIAGE_MANAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsFor(response, imp.getId(), stmt.getImportJobId()))
                .extracting(r -> r.path("kind").asText()).containsExactly("IMPORT");
    }

    @Test
    void trustReviewOnlySeesTrustHolds() throws Exception {
        ImportJob imp = importHold(Duration.ofHours(1));
        HeldStatement stmt = statementHold(Duration.ofHours(1));

        ResponseEntity<String> response = get(userWithOnly("TRUST_REVIEW_MANAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsFor(response, imp.getId(), stmt.getImportJobId()))
                .extracting(r -> r.path("kind").asText()).containsExactly("STATEMENT");
    }

    @Test
    void neitherPermissionIsForbidden() {
        assertThat(get(createUser("USER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(userWithOnly("PLATFORM_DIAGNOSTICS_VIEW")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The list is classified as carrying no personal data (AdminReadAuditPolicy); this holds it to that. */
    @Test
    void rowsCarryNoFileNameErrorTextOrUserId() throws Exception {
        ImportJob imp = importHold(Duration.ofHours(1));

        ResponseEntity<String> response = get(createUser("SUPER_ADMIN"));

        String body = response.getBody();
        assertThat(rowsFor(response, imp.getId())).hasSize(1);
        assertThat(body).doesNotContain("sample-june.pdf", "no header row found", imp.getUserId().toString());
        assertThat(rowsFor(response, imp.getId()).get(0).has("userId")).isFalse();
        assertThat(rowsFor(response, imp.getId()).get(0).has("fileName")).isFalse();
    }
}
