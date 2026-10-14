package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.passwords.StatementPasswordService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-blanked-copy-it",
        "app.statement-passwords.save.enabled=true"
})
@ExtendWith(OutputCaptureExtension.class)
class AdminBlankedCopyIT extends AbstractIntegrationTest {

    /** The customer's own file name. It must never reach a response, an audit row or a log line. */
    private static final String CUSTOMER_FILE_NAME = "Asha Verma statement.pdf";

    /** A saved statement password. It must not leave either. */
    private static final String PASSWORD = "open-sesame-blanked";
    private static final String WRONG_PASSWORD = "not-the-password";

    private static final List<String> MUST_NOT_LEAVE = List.of(PdfFixtureBuilder.PLANTED_NAME,
            PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
            PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, PdfFixtureBuilder.PLANTED_IFSC,
            PdfFixtureBuilder.PLANTED_LONG_REFERENCE, PdfFixtureBuilder.PLANTED_REFERENCE,
            PdfFixtureBuilder.PLANTED_OTHER_SCRIPT_NAME, "statement@a.in", PASSWORD, WRONG_PASSWORD, "Asha Verma statement"); // synthetic-ok: invented values

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
    @Autowired private StatementPasswordService statementPasswordService;
    /** The real limiter, spied so one test can make it answer "busy". Mockito resets it after each test. */
    @MockitoSpyBean private ImportConcurrencyLimiter limiter;

    private static byte[] plantedPdf() {
        try {
            return PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] lockedPlantedPdf() {
        try {
            return PdfFixtureBuilder.encrypt(plantedPdf(), PASSWORD);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] pdfWithAnEmailTheRedactorCannotBlank() {
        try {
            return PdfFixtureBuilder.buildSampleWithAnEmailTheRedactorCannotBlank();
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
        return seedHold(heldId, plantedPdf());
    }

    private HeldStatement seedHold(String heldId, byte[] content) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(content);
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
        assertNothingLeft(copy.getBody(), auditOf(job.getId()), output);
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

    /** The second audit entry, written once the copy is made or refused: the check's result. */
    private static void assertResultEntry(List<com.finora.entity.AuditLog> entries, String action, String entityType,
                                          User actor, String check, int differenceCount, List<String> refusedKinds,
                                          String... extraIdKeys) {
        assertThat(entries).extracting(com.finora.entity.AuditLog::getAction)
                .as("the result is written after the access entry")
                .containsSubsequence(action.replace("_RESULT", ""), action);
        assertThat(entries).filteredOn(e -> e.getAction().equals(action))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getUserId()).isEqualTo(actor.getId());
                    assertThat(e.getEntityType()).isEqualTo(entityType);
                    List<String> keys = new java.util.ArrayList<>(List.of(
                            "actorId", "subjectUserId", "check", "differenceCount", "refusedKinds"));
                    keys.addAll(List.of(extraIdKeys));
                    assertThat(e.getMetadata()).containsOnlyKeys(keys.toArray(String[]::new));
                    assertThat(e.getMetadata()).containsEntry("check", check);
                    assertThat(((Number) e.getMetadata().get("differenceCount")).intValue()).isEqualTo(differenceCount);
                    assertThat(e.getMetadata().get("refusedKinds")).isEqualTo(refusedKinds);
                });
    }

    @Test
    void theCheckResultIsAuditedWhenTheCopyMatches(CapturedOutput output) {
        ImportJob job = heldJob();
        User support = createSupportUser("IMPORT_TRIAGE_MANAGE");

        ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", support);

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertResultEntry(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()),
                "HELD_IMPORT_BLANKED_COPY_RESULT", "ImportJob", support, "MATCHES", 0, List.of());
        assertNothingLeft(copy.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void theCheckResultIsAuditedWhenTheCopyDiffers(CapturedOutput output) throws Exception {
        ImportJob job = heldJob(PdfFixtureBuilder.buildDirectionOnlyAsABareLetterSample(), CUSTOMER_FILE_NAME, "PDF");
        User support = createSupportUser("IMPORT_TRIAGE_MANAGE");

        ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", support);

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertResultEntry(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()),
                "HELD_IMPORT_BLANKED_COPY_RESULT", "ImportJob", support, "DIFFERS", 2, List.of());
        assertThat(auditOf(job.getId())).doesNotContain("EMPLOYER", "SENDER", "MERCHANT", "PAYEE");
        assertNothingLeft(copy.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void theCheckResultIsAuditedWhenThePrivacyScanRefuses(CapturedOutput output) {
        ImportJob job = heldJob(pdfWithAnEmailTheRedactorCannotBlank(), CUSTOMER_FILE_NAME, "PDF");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertResultEntry(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()),
                "HELD_IMPORT_BLANKED_COPY_RESULT", "ImportJob", admin, "REFUSED", 0, List.of("email address"));
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void theTrustQueueAuditsTheCheckResult(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610010");
        User support = createSupportUser("TRUST_REVIEW_MANAGE");

        ResponseEntity<String> copy = get("/api/v1/admin/held-statements/HLD-2026-610010/blanked-copy", support);

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertResultEntry(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()),
                "HELD_STATEMENT_BLANKED_COPY_RESULT", "HeldStatement", support, "MATCHES", 0, List.of(), "heldId");
        assertNothingLeft(copy.getBody(), auditOf(held.getId()), output);
    }

    @Test
    void theTrustQueueAuditsARefusedCheck(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610011", pdfWithAnEmailTheRedactorCannotBlank());
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610011/blanked-copy", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertResultEntry(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()),
                "HELD_STATEMENT_BLANKED_COPY_RESULT", "HeldStatement", admin, "REFUSED", 0,
                List.of("email address"), "heldId");
        assertNothingLeft(response.getBody(), auditOf(held.getId()), output);
    }

    /** A refusal before the check (here a stored file that is gone) writes no result entry: no copy
     *  was made or refused. */
    @Test
    void aFailedReadWritesNoResultEntry() {
        ImportJob job = heldJobWithMissingObject();
        get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .extracting(com.finora.entity.AuditLog::getAction)
                .contains("HELD_IMPORT_BLANKED_COPY").doesNotContain("HELD_IMPORT_BLANKED_COPY_RESULT");
    }

    @Test
    void aSuccessfulCopyIsAudited() throws Exception {
        ImportJob job = heldJob();
        User admin = createUser("ADMIN");
        get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        // The read and both readings ran under an import slot, as every other parse does.
        verify(limiter).runGated(any());
    }

    /** Every import slot taken: refused at once with the import queue's own 503, before the audit
     *  row and before the statement is read. */
    @Test
    void aBusyServerRefusesBeforeTheAuditAndTheRead(CapturedOutput output) throws Exception {
        ImportJob job = heldJob();
        doThrow(new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY)).when(limiter).runGated(any());

        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy",
                createSupportUser("IMPORT_TRIAGE_MANAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
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

    /**
     * A statement whose reading depends on a masked word (its direction is only a bare C or D):
     * the endpoint says it does not match and lists each difference as section, row, field and the
     * copy's masked tokens, and nothing else.
     */
    @Test
    void aCopyThatReadsDifferentlyListsTheDifferences(CapturedOutput output) throws Exception {
        ImportJob job = heldJob(PdfFixtureBuilder.buildDirectionOnlyAsABareLetterSample(), CUSTOMER_FILE_NAME, "PDF");

        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy",
                createSupportUser("IMPORT_TRIAGE_MANAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = new ObjectMapper().readTree(response.getBody()).get("data");
        assertThat(data.get("check").asText()).isEqualTo("DIFFERS");
        assertThat(data.get("notCheckedReason").isNull()).isTrue();
        JsonNode differences = data.get("differences");
        assertThat(differences).hasSize(2);
        int i = 0;
        for (JsonNode d : differences) {
            assertThat(d.fieldNames()).toIterable().containsExactlyInAnyOrder("section", "row", "field", "maskedTokens");
            assertThat(d.get("section").asInt()).isZero();
            assertThat(d.get("row").asInt()).isEqualTo(i == 0 ? 1 : 3);
            assertThat(d.get("field").asText()).isEqualTo("DIRECTION");
            assertThat(d.get("maskedTokens").isArray()).isTrue();
            assertThat(d.get("maskedTokens")).allSatisfy(t -> assertThat(t.asText()).matches("[Xx9\\p{Punct}]+"));
            i++;
        }
        assertThat(differences.toString()).doesNotContain("EMPLOYER", "SENDER", "MERCHANT", "PAYEE");
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

    /** The privacy scan's refusal, end to end from a real PDF: 422 naming the kind, never the value. */
    @Test
    void aCopyThePrivacyScanRefusesIsA422NamingKindsOnly(CapturedOutput output) {
        ImportJob job = heldJob(pdfWithAnEmailTheRedactorCannotBlank(), CUSTOMER_FILE_NAME, "PDF");
        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("The blanked copy was refused: it still contained email address.");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    @Test
    void aLockedPdfWithNoSavedPasswordIsRefused(CapturedOutput output) {
        ImportJob job = heldJob(lockedPlantedPdf(), CUSTOMER_FILE_NAME, "PDF");
        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("This statement is password-protected and no password is saved.");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
    }

    /** Unlocked in memory with the saved password, then blanked like any other statement. */
    @Test
    void aLockedPdfWithASavedPasswordIsCopied(CapturedOutput output) {
        ImportJob job = heldJob(lockedPlantedPdf(), CUSTOMER_FILE_NAME, "PDF");
        statementPasswordService.saveForJob(job.getUserId(), job.getId(), PASSWORD);

        ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy",
                createSupportUser("IMPORT_TRIAGE_MANAGE"));

        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(copy.getBody()).contains("blanked-" + job.getId() + ".trace").contains("\"check\":\"MATCHES\"");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .filteredOn(e -> e.getAction().equals("HELD_IMPORT_BLANKED_COPY"))
                .singleElement()
                .satisfies(e -> assertThat(e.getMetadata()).containsEntry("savedPasswordOnFile", true));
        assertNothingLeft(copy.getBody(), auditOf(job.getId()), output);
    }

    /** A saved password that does not open the file: refused naming that, not "no password is saved". */
    @Test
    void aSavedPasswordThatDoesNotOpenTheStatementIsRefused(CapturedOutput output) {
        ImportJob job = heldJob(lockedPlantedPdf(), CUSTOMER_FILE_NAME, "PDF");
        statementPasswordService.saveForJob(job.getUserId(), job.getId(), WRONG_PASSWORD);

        ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("The saved password did not open the statement behind held import "
                + job.getId()).doesNotContain("no password is saved");
        assertNothingLeft(response.getBody(), auditOf(job.getId()), output);
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

    @Test
    void theTrustQueueRefusesWhenTheServerIsBusy(CapturedOutput output) throws Exception {
        HeldStatement held = seedHold("HLD-2026-610009");
        doThrow(new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY)).when(limiter).runGated(any());

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610009/blanked-copy",
                createSupportUser("TRUST_REVIEW_MANAGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(held.getId()), output);
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
    void theTrustQueueRefusesALockedPdfWithNoSavedPassword(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610006", lockedPlantedPdf());
        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610006/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("This statement is password-protected and no password is saved.");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
        assertNothingLeft(response.getBody(), auditOf(held.getId()), output);
    }

    @Test
    void theTrustQueueCopiesALockedPdfWithItsSavedPassword(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610007", lockedPlantedPdf());
        statementPasswordService.saveForJob(held.getUserId(), held.getImportJobId(), PASSWORD);
        ResponseEntity<String> copy = get("/api/v1/admin/held-statements/HLD-2026-610007/blanked-copy",
                createSupportUser("TRUST_REVIEW_MANAGE"));
        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(copy.getBody()).contains("blanked-HLD-2026-610007.trace").contains("\"check\":\"MATCHES\"");
        assertNothingLeft(copy.getBody(), auditOf(held.getId()), output);
    }

    @Test
    void theTrustQueueRefusesASavedPasswordThatDoesNotOpenTheStatement(CapturedOutput output) {
        HeldStatement held = seedHold("HLD-2026-610008", lockedPlantedPdf());
        statementPasswordService.saveForJob(held.getUserId(), held.getImportJobId(), WRONG_PASSWORD);
        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-610008/blanked-copy", createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("The saved password did not open the statement behind HLD-2026-610008")
                .doesNotContain("no password is saved");
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
