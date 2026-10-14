package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blanked-copy endpoints on both held queues, switched on. Every statement here is the
 * synthetic one with planted customer data ({@link PdfFixtureBuilder#buildSampleWithPlantedCustomerData}),
 * under a customer-shaped file name, so "nothing from the statement leaves" can be checked against
 * the response, the audit rows and the captured log output, on the success path and on the refused
 * and failed paths.
 */
@TestPropertySource(properties = {
        "app.blanked-copy.enabled=true",
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-blanked-copy-it"
})
@ExtendWith(OutputCaptureExtension.class)
class AdminBlankedCopyIT extends AbstractIntegrationTest {

    /** The customer's own file name. It must never reach a response, an audit row or a log line. */
    private static final String CUSTOMER_FILE_NAME = "Asha Verma statement.pdf";

    private static final List<String> MUST_NOT_LEAVE = List.of(PdfFixtureBuilder.PLANTED_NAME,
            PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
            PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, "Asha Verma statement");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;

    private static byte[] plantedPdf() {
        try {
            return PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("blanked-copy-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Blanked Copy IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /**
     * A support-portal account holding one queue permission through a real RBAC role, and no ADMIN
     * role: the legacy {@code role} column names the new role, which {@code AuthorizationService}
     * resolves by name, so the account carries {@code ROLE_SUPPORT_IT_*} and the permission, never
     * {@code ROLE_ADMIN}.
     */
    private User createSupportUser(String permission) {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals(permission)).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("SUPPORT_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Support role for the blanked-copy IT");
        role.getPermissions().add(p);
        roleRepository.save(role);
        return createUser(role.getName());
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

    /** A job in the state the parser-gap queue holds, as {@code AdminHeldImportDownloadIT} builds it. */
    private ImportJob heldJob(byte[] content, String fileName, String sourceFormat) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner.getId(), fileName, address.hash(), address.key(), sourceFormat);
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ImportJob heldJob() {
        return heldJob(plantedPdf(), CUSTOMER_FILE_NAME, "PDF");
    }

    /** Stored, then the object deleted from the storage root: the read fails after the audit. */
    private ImportJob heldJobWithMissingObject() {
        ImportJob job = heldJob();
        storage.delete(job.getObjectKey());
        return job;
    }

    /** A job in the state the trust gate leaves one in, as {@code AdminHeldStatementDownloadIT} builds it. */
    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(plantedPdf());
        ImportJob job = new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), "PDF");
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

    private String auditOf(UUID entityId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId).stream()
                .map(e -> e.getAction() + " " + e.getMetadata())
                .toList().toString();
    }

    private static void assertNothingLeft(String body, String audit, CapturedOutput output) {
        for (String planted : MUST_NOT_LEAVE) {
            assertThat(body).as("response").doesNotContain(planted);
            assertThat(audit).as("audit").doesNotContain(planted);
            assertThat(output.getAll()).as("captured output").doesNotContain(planted);
        }
    }

    // ------------------------------------------------------------------------- held imports

    @Test
    void aSupportAccountGetsTheBlankedCopyButNotTheStatement(CapturedOutput output) {
        ImportJob job = heldJob();
        User support = createSupportUser("IMPORT_TRIAGE_MANAGE");

        ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", support);
        ResponseEntity<byte[]> real = restTemplate.exchange("/api/v1/admin/held-imports/" + job.getId() + "/document",
                HttpMethod.GET, new HttpEntity<>(bearerFor(support)), byte[].class);

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(copy.getBody()).contains("blanked-" + job.getId() + ".trace");
        assertThat(copy.getBody()).contains("\"check\":\"MATCHES\"");
    }

    @Test
    void nothingFromTheStatementOrItsFileNameLeaves(CapturedOutput output) {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditOf(job.getId())).contains("HELD_IMPORT_BLANKED_COPY");
        assertNothingLeft(copy.getBody(), auditOf(job.getId()), output);
    }

    /** The audit row carries ids and whether a password was on file -- nothing else. */
    @Test
    void theAuditRowCarriesIdsAndThePasswordFlagOnly() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");

        get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);

        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .filteredOn(e -> e.getAction().equals("HELD_IMPORT_BLANKED_COPY"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getUserId()).isEqualTo(admin.getId());
                    assertThat(e.getEntityType()).isEqualTo("ImportJob");
                    assertThat(e.getMetadata()).containsOnlyKeys("actorId", "subjectUserId", "savedPasswordOnFile");
                    assertThat(e.getMetadata()).containsEntry("savedPasswordOnFile", false);
                });
    }

    @Test
    void everyCopyIsAuditedBeforeTheRead() {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");
        get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
    }

    /** The stored file is gone: refused with 409 (not the catch-all 500), still on record, and the
     *  log line written for it -- proof the capture sees the service's logging -- names the job only. */
    @Test
    void aFailedReadIsStillAudited(CapturedOutput output) {
        ImportJob job = heldJobWithMissingObject();
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        assertThat(output.getAll()).contains("Stored statement behind held import " + job.getId() + " could not be read");
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void aReleasedItemIsRefused(CapturedOutput output) {
        ImportJob job = heldJob();
        job.returnToQueueForReprocess(Instant.now());
        importJobRepository.save(job);
        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void aCsvIsRefusedWithTheCsvMessage(CapturedOutput output) {
        ImportJob job = heldJob(("Date,Narration,Amount\n01/07/2026,UPI/" + PdfFixtureBuilder.PLANTED_NAME
                + ",-1000.00\n").getBytes(StandardCharsets.UTF_8), "Asha Verma statement.csv", "CSV");
        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Blanked copy not available for CSV yet.");
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void theWrongQueuePermissionIsRefused() {
        ImportJob job = heldJob();
        User trustOnly = createSupportUser("TRUST_REVIEW_MANAGE");
        assertThat(get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", trustOnly).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        ImportJob job = heldJob();
        assertThat(restTemplate.exchange("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy",
                HttpMethod.GET, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/api/v1/admin/blanked-copy/status",
                HttpMethod.GET, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPlainUserIsRefused() {
        ImportJob job = heldJob();
        assertThat(get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("USER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------------- held statements

    @Test
    void theTrustQueueWorksTheSameWay(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610001");
        User support = createSupportUser("TRUST_REVIEW_MANAGE");
        ResponseEntity<String> copy = get("/api/v1/admin/held-statements/HLD-2026-610001/blanked-copy", support);
        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(copy.getBody()).contains("blanked-HLD-2026-610001.trace").doesNotContain(PdfFixtureBuilder.PLANTED_NAME);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .filteredOn(e -> e.getAction().equals("HELD_STATEMENT_BLANKED_COPY"))
                .singleElement()
                .satisfies(e -> assertThat(e.getMetadata())
                        .containsOnlyKeys("actorId", "subjectUserId", "heldId", "savedPasswordOnFile"));
        assertNothingLeft(copy.getBody(), auditOf(held.getId()), output);
    }

    /** Same account both ways, so the 403 on the document is the role pin, not a missing permission. */
    @Test
    void aSupportAccountGetsTheTrustQueueCopyButNotTheStatement() {
        seedHold("HLD-2026-610002");
        User support = createSupportUser("TRUST_REVIEW_MANAGE");
        ResponseEntity<String> copy = get("/api/v1/admin/held-statements/HLD-2026-610002/blanked-copy", support);
        ResponseEntity<byte[]> real = restTemplate.exchange("/api/v1/admin/held-statements/HLD-2026-610002/document",
                HttpMethod.GET, new HttpEntity<>(bearerFor(support)), byte[].class);
        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theTrustQueueRefusesTheTriagePermission() {
        HeldStatement held = seedHold("HLD-2026-610003");
        assertThat(get("/api/v1/admin/held-statements/HLD-2026-610003/blanked-copy",
                createSupportUser("IMPORT_TRIAGE_MANAGE")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
    }

    /** {@code require(heldId)} finds a decided hold too; a blanked copy is for open ones only. */
    @Test
    void aDecidedHoldIsRefused(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610004");
        User admin = createUser("ADMIN");
        held.reject(admin.getId(), Instant.now());
        heldStatementRepository.save(held);

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610004/blanked-copy", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(held.getId()), output);
    }

    /** The stored file is gone: refused with 409, still on record, and the log line the trust queue
     *  writes for it -- proof the capture sees this class's logging -- names the Held ID only. */
    @Test
    void aFailedTrustQueueReadIsStillAudited(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610005");
        storage.delete(importJobRepository.findById(held.getImportJobId()).orElseThrow().getObjectKey());

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610005/blanked-copy",
                createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
        assertThat(output.getAll()).contains("Stored statement behind HLD-2026-610005 could not be read");
        assertNothingLeft(response.getBody(), auditOf(held.getId()), output);
    }

    @Test
    void statusSaysEnabled() {
        assertThat(get("/api/v1/admin/blanked-copy/status", createSupportUser("IMPORT_TRIAGE_MANAGE")).getBody())
                .contains("\"enabled\":true");
        assertThat(get("/api/v1/admin/blanked-copy/status", createSupportUser("TRUST_REVIEW_MANAGE")).getBody())
                .contains("\"enabled\":true");
    }

    @Test
    void statusRefusesAnAccountWithNeitherPermission() {
        assertThat(get("/api/v1/admin/blanked-copy/status", createUser("USER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
