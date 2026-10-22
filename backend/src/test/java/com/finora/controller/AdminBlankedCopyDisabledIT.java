package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch's default: with {@code app.blanked-copy.enabled} unset, the copy endpoints answer 404
 * on both queues, read nothing and audit nothing, and the status endpoint says so.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage"
})
class AdminBlankedCopyDisabledIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("blanked-copy-off-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Blanked Copy Off IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
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

    private ResponseEntity<String> post(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);
    }

    private ImportJob heldJob() throws Exception {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PdfFixtureBuilder.buildSampleWithPlantedCustomerData());
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private HeldStatement seedHold(String heldId) throws Exception {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PdfFixtureBuilder.buildSampleWithPlantedCustomerData());
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);
        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    @Test
    void offMeansNotFoundAndStatusFalse() throws Exception {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");
        assertThat(post("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/admin/blanked-copy/status", admin).getBody()).contains("\"enabled\":false");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
    }

    @Test
    void offMeansNotFoundOnTheTrustQueueToo() throws Exception {
        HeldStatement held = seedHold("HLD-2026-610101");
        User admin = createUser("ADMIN");
        assertThat(post("/api/v1/admin/held-statements/HLD-2026-610101/blanked-copy", admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
    }
}
