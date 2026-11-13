package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A blanked copy of an upload of a layout flagged for review, end to end. Nothing about such an
 * upload was held, so the copy carries the rules of opening a held statement: Admins only, a typed
 * reason, an audit entry before the read, and -- once a copy exists -- the customer's notice and an
 * email to the Super Admins. Every statement here is the synthetic one with planted customer data,
 * under a customer-shaped file name, so "nothing from the statement leaves" is checked against the
 * response, the audit rows, the email and the captured log output.
 */
@TestPropertySource(properties = {
        "app.blanked-copy.enabled=true",
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage"
})
@ExtendWith(OutputCaptureExtension.class)
class FlaggedLayoutUploadBlankedCopyIT extends AbstractIntegrationTest {

    private static final String CUSTOMER_FILE_NAME = "Asha Verma statement.pdf"; // synthetic-ok: invented name
    private static final String REASON = "Rebuild the reader for this flagged layout";

    private static final List<String> MUST_NOT_LEAVE = List.of(PdfFixtureBuilder.PLANTED_NAME,
            PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
            PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, PdfFixtureBuilder.PLANTED_IFSC,
            PdfFixtureBuilder.PLANTED_LONG_REFERENCE, PdfFixtureBuilder.PLANTED_REFERENCE,
            PdfFixtureBuilder.PLANTED_OTHER_SCRIPT_NAME, "Asha Verma statement"); // synthetic-ok: invented values

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private RegisteredLayoutRepository layoutRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.finora.repository.AccountRepository accountRepository;
    @MockitoBean private EmailProvider emailProvider;

    @BeforeEach
    void emailsSucceed() {
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
    }

    /** One upload of a layout, its file kept by the queue job that read it. */
    private record Upload(String reference, String fingerprint, ImportJob job) {}

    private static byte[] plantedPdf() {
        try {
            return PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("layout-copy-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Copy IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A customer: no admin scope, as the import timeline expects. */
    private User createCustomer() {
        User user = new User();
        user.setEmail("layout-copy-owner-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Copy Owner");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A support-portal account holding only the layout permission, through a real role, and no ADMIN role. */
    private User createLayoutSupportUser() {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals("LAYOUT_REGISTRY_MANAGE")).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("LAYOUT_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Layout role for the flagged-upload copy IT");
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

    private ResponseEntity<String> copy(String reference, String reason, User caller) {
        Object body = reason == null ? Map.of() : Map.of("reason", reason);
        HttpHeaders headers = caller == null ? jsonOnly() : bearerFor(caller);
        return restTemplate.exchange("/api/v1/admin/statement-analyses/" + reference + "/blanked-copy",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders jsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String newReference() {
        return "SA-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String newFingerprint() {
        return "FP-1-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private void registerLayout(String fingerprint, boolean waitingForReview) {
        layoutRepository.observe(fingerprint, "PDF", "PdfPreviewGenerator", Instant.now());
        if (waitingForReview) {
            jdbc.update("UPDATE layout_registry SET needs_review = TRUE, review_reasons = 'NEW_LAYOUT', "
                    + "review_flagged_at = now() WHERE fingerprint = ?", fingerprint);
        }
    }

    /** A queue upload that finished staging: the job keeps the stored file and names the staged session. */
    private Upload queueUpload(byte[] content, boolean waitingForReview) {
        User owner = createCustomer();
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.complete(sessionId, Instant.now());
        importJobRepository.save(job);
        stagedSession(sessionId, owner.getId());
        String fingerprint = newFingerprint();
        registerLayout(fingerprint, waitingForReview);
        String reference = newReference();
        analysisRepository.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, CUSTOMER_FILE_NAME, "PDF", (long) content.length,
                fingerprint, 1, 100L, 5, "{}", sessionId, "it-correlation"));
        if (waitingForReview) reviewNames(fingerprint, reference);
        return new Upload(reference, fingerprint, job);
    }

    /** The staged session the worker leaves for the customer to confirm, still confirmable. */
    private void stagedSession(UUID sessionId, UUID ownerId) {
        jdbc.update("INSERT INTO import_sessions (id, user_id, file_name, file_content, status, expires_at) "
                + "VALUES (?, ?, 'staged.pdf', ?, 'STAGED', now() + interval '1 day')", sessionId, ownerId, new byte[] {1});
    }

    private Upload queueUpload() {
        return queueUpload(plantedPdf(), true);
    }

    /** The days the customer's own import timeline says our team opened the statement. */
    private List<String> timelineOpenedDays(ImportJob job) throws Exception {
        User owner = userRepository.findById(job.getUserId()).orElseThrow();
        ResponseEntity<String> timeline = restTemplate.exchange("/api/v1/import/jobs/" + job.getId() + "/timeline",
                HttpMethod.GET, new HttpEntity<>(bearerFor(owner)), String.class);
        assertThat(timeline.getStatusCode()).isEqualTo(HttpStatus.OK);
        var days = new com.fasterxml.jackson.databind.ObjectMapper().readTree(timeline.getBody())
                .get("data").get("openedForFixOn");
        List<String> out = new java.util.ArrayList<>();
        days.forEach(d -> out.add(d.asText()));
        return out;
    }

    private List<String> actionsOn(UUID entityId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId).stream().map(AuditLog::getAction).toList();
    }

    private String auditOf(UUID entityId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId).stream()
                .map(e -> e.getAction() + " " + e.getMetadata()).toList().toString();
    }

    private List<Notification> openedNoticesFor(UUID userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> n.getType() == NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX).toList();
    }

    private List<EmailMessage> sent() {
        if (mockingDetails(emailProvider).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("send"))) {
            return List.of();
        }
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, org.mockito.Mockito.atLeast(1)).send(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------------- the copy

    @Test
    void anAdminGetsTheCopyAndTheCustomerAndSuperAdminsAreTold(CapturedOutput output) {
        Upload upload = queueUpload();
        User superAdmin = createUser("SUPER_ADMIN");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = copy(upload.reference(), REASON, admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("blanked-" + upload.reference() + ".trace");
        assertThat(response.getBody()).contains("\"check\":\"MATCHES\"");
        assertThat(actionsOn(upload.job().getId()))
                .containsExactly("LAYOUT_UPLOAD_BLANKED_COPY", "LAYOUT_UPLOAD_BLANKED_COPY_RESULT");
        String audit = auditOf(upload.job().getId());
        assertThat(audit).contains(REASON).contains(upload.reference()).contains(upload.fingerprint());
        assertThat(openedNoticesFor(upload.job().getUserId())).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);

        verify(emailProvider, timeout(10_000)).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
        EmailMessage email = sent().stream().filter(m -> m.to().equals(superAdmin.getEmail())).findFirst().orElseThrow();
        assertThat(email.subject()).contains(upload.reference());
        assertThat(email.html()).contains(admin.getEmail()).contains(REASON).contains(upload.fingerprint());
        assertThat(email.html()).doesNotContain(CUSTOMER_FILE_NAME);

        for (String planted : MUST_NOT_LEAVE) {
            assertThat(response.getBody()).as("response").doesNotContain(planted);
            assertThat(audit).as("audit").doesNotContain(planted);
            assertThat(email.html()).as("email").doesNotContain(planted);
            assertThat(output.getAll()).as("captured output").doesNotContain(planted);
        }
    }

    @Test
    void theCustomersImportTimelineShowsTheDayACopyWasMade() throws Exception {
        Upload upload = queueUpload();
        assertThat(timelineOpenedDays(upload.job())).isEmpty();

        // The day in India either side of the copy, so a copy made at midnight there cannot flake.
        String before = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        String after = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();

        List<String> days = timelineOpenedDays(upload.job());
        assertThat(days).hasSize(1);
        assertThat(days.get(0)).isIn(before, after);
    }

    @Test
    void aJobDeletedWithItsAccountTakesItsCopyRecordsWithIt() {
        Upload upload = queueUpload();
        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM layout_upload_blanked_copies WHERE job_id = ?",
                Integer.class, upload.job().getId())).isEqualTo(1);

        jdbc.update("DELETE FROM import_jobs WHERE id = ?", upload.job().getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM layout_upload_blanked_copies WHERE job_id = ?",
                Integer.class, upload.job().getId())).isZero();
    }

    @Test
    void twoCopiesOnOneDayTellTheCustomerOnce() throws Exception {
        Upload upload = queueUpload();
        User admin = createUser("ADMIN");

        assertThat(copy(upload.reference(), REASON, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(copy(upload.reference(), REASON, admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(openedNoticesFor(upload.job().getUserId())).hasSize(2);
        assertThat(timelineOpenedDays(upload.job())).hasSize(1);
        assertThat(actionsOn(upload.job().getId())).containsExactly("LAYOUT_UPLOAD_BLANKED_COPY",
                "LAYOUT_UPLOAD_BLANKED_COPY_RESULT", "LAYOUT_UPLOAD_BLANKED_COPY", "LAYOUT_UPLOAD_BLANKED_COPY_RESULT");
    }

    @Test
    void aCopyThePrivacyScanRefusesTellsNobody() throws Exception {
        Upload upload = queueUpload(PdfFixtureBuilder.buildSampleWithAnEmailTheRedactorCannotBlank(), true);
        User superAdmin = createUser("SUPER_ADMIN");

        ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(actionsOn(upload.job().getId()))
                .containsExactly("LAYOUT_UPLOAD_BLANKED_COPY", "LAYOUT_UPLOAD_BLANKED_COPY_RESULT");
        assertThat(auditOf(upload.job().getId())).contains("REFUSED");
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
        assertThat(timelineOpenedDays(upload.job())).isEmpty();
        verify(emailProvider, after(1_500).never()).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
    }

    // ------------------------------------------------------------------------- who may

    @Test
    void aSupportAccountWithTheLayoutPermissionButNoAdminRoleIsRefused() {
        Upload upload = queueUpload();

        assertThat(copy(upload.reference(), REASON, createLayoutSupportUser()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        Upload upload = queueUpload();

        assertThat(copy(upload.reference(), REASON, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(actionsOn(upload.job().getId())).isEmpty();
    }

    @Test
    void aGetIsNotAnAction() {
        Upload upload = queueUpload();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/statement-analyses/" + upload.reference() + "/blanked-copy", HttpMethod.GET,
                new HttpEntity<>(bearerFor(createUser("ADMIN"))), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(actionsOn(upload.job().getId())).isEmpty();
    }

    // ------------------------------------------------------------------------- refused before anything is read

    @Test
    void aMissingOrShortReasonReadsAndRecordsNothing() {
        Upload upload = queueUpload();
        User admin = createUser("ADMIN");

        assertThat(copy(upload.reference(), null, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(copy(upload.reference(), "too short", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(copy(upload.reference(), "   " + "x".repeat(14) + "   ", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(copy(upload.reference(), "x".repeat(501), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    @Test
    void aReasonOfExactlyFifteenCharactersIsEnough() {
        Upload upload = queueUpload();

        assertThat(copy(upload.reference(), "x".repeat(15), createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUploadOfALayoutNotWaitingForReviewIsRefused() {
        Upload upload = queueUpload(plantedPdf(), false);

        ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("not of a layout waiting for review");
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    @Test
    void aResolvedLayoutIsNoLongerCopyable() {
        Upload upload = queueUpload();
        jdbc.update("UPDATE layout_registry SET needs_review = FALSE WHERE fingerprint = ?", upload.fingerprint());

        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(actionsOn(upload.job().getId())).isEmpty();
    }

    @Test
    void aDirectUploadHasNoKeptFile() {
        User owner = createUser("USER");
        String fingerprint = newFingerprint();
        registerLayout(fingerprint, true);
        String reference = newReference();
        // A direct upload: its analysis names a staged session no queue job produced.
        analysisRepository.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, CUSTOMER_FILE_NAME, "PDF", 100L,
                fingerprint, 1, 100L, 5, "{}", UUID.randomUUID(), "it-correlation"));
        reviewNames(fingerprint, reference);

        ResponseEntity<String> response = copy(reference, REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("was not made through the import queue");
        assertThat(openedNoticesFor(owner.getId())).isEmpty();
    }

    @Test
    void anUploadWithNoSessionHasNoKeptFile() {
        User owner = createUser("USER");
        String fingerprint = newFingerprint();
        registerLayout(fingerprint, true);
        String reference = newReference();
        analysisRepository.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, CUSTOMER_FILE_NAME, "PDF", 100L,
                fingerprint, 1, 100L, 0, "{}", null, null));
        reviewNames(fingerprint, reference);

        assertThat(copy(reference, REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anImportTheCustomerCancelledIsRefused() {
        Upload upload = queueUpload();
        // A job cancelled after its staged session was recorded: status set directly, as the entity
        // only cancels from states before a session exists.
        jdbc.update("UPDATE import_jobs SET status = 'CANCELLED' WHERE id = ?", upload.job().getId());

        ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("cancelled");
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    @Test
    void anAccountBeingClosedOrClosedIsRefused() {
        for (String status : List.of(User.STATUS_PENDING_DELETION, User.STATUS_DEACTIVATED, User.STATUS_DELETED)) {
            Upload upload = queueUpload();
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, upload.job().getUserId());

            ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));

            assertThat(response.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(actionsOn(upload.job().getId())).as(status).isEmpty();
            assertThat(openedNoticesFor(upload.job().getUserId())).as(status).isEmpty();
        }
    }

    @Test
    void aSuspendedAccountsUploadCanStillBeCopied() {
        Upload upload = queueUpload();
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", User.STATUS_SUSPENDED, upload.job().getUserId());

        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------------- the statement must still be the customer's

    /** The customer confirmed the staging: the session says so and the import holds the upload's hash. */
    private UUID imported(Upload upload) {
        jdbc.update("UPDATE import_sessions SET status = 'CONFIRMED' WHERE id = ?", upload.job().getImportSessionId());
        com.finora.entity.Account account = new com.finora.entity.Account();
        account.setUserId(upload.job().getUserId());
        account.setName("Layout Copy IT Savings");
        account.setAccountType(com.finora.entity.Account.Type.SAVINGS);
        account.setBalance(java.math.BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        UUID importId = UUID.randomUUID();
        jdbc.update("INSERT INTO statement_imports (id, user_id, account_id, file_name, file_content, content_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?)", importId, upload.job().getUserId(), accountId, "statement.pdf", new byte[] {1},
                upload.job().getContentHash());
        return importId;
    }

    private void assertRefusedAsTheCustomersChoice(Upload upload) {
        ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("discarded or deleted");
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    @Test
    void aStagingTheCustomerDiscardedIsRefused() {
        Upload upload = queueUpload();
        // Discarding deletes the session; the queue keeps the job and its file for the retention window.
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", upload.job().getImportSessionId());

        assertRefusedAsTheCustomersChoice(upload);
    }

    @Test
    void aStagingThatExpiredUnconfirmedIsRefused() {
        Upload upload = queueUpload();
        jdbc.update("UPDATE import_sessions SET expires_at = now() - interval '1 minute' WHERE id = ?",
                upload.job().getImportSessionId());

        assertRefusedAsTheCustomersChoice(upload);
    }

    @Test
    void anImportTheCustomerDeletedIsRefused() {
        Upload upload = queueUpload();
        UUID importId = imported(upload);
        jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE id = ?", importId);

        assertRefusedAsTheCustomersChoice(upload);
    }

    @Test
    void anImportTheCustomerKeptCanBeCopied_evenOnceItsSessionIsGone() {
        Upload upload = queueUpload();
        imported(upload);
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", upload.job().getImportSessionId());

        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherCustomersImportOfTheSameFileDoesNotCount() {
        Upload upload = queueUpload();
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", upload.job().getImportSessionId());
        User someoneElse = createCustomer();
        com.finora.entity.Account account = new com.finora.entity.Account();
        account.setUserId(someoneElse.getId());
        account.setName("Other customer savings");
        account.setAccountType(com.finora.entity.Account.Type.SAVINGS);
        account.setBalance(java.math.BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, content_hash) "
                + "VALUES (?, ?, ?, ?, ?)", someoneElse.getId(), accountId, "statement.pdf", new byte[] {1},
                upload.job().getContentHash());

        assertRefusedAsTheCustomersChoice(upload);
    }

    @Test
    void aHeldUploadCanBeCopied_whateverItsSessionsExpiry() {
        Upload upload = queueUpload();
        // A hold keeps its session past its expiry (the sweep spares it); the customer is waiting on us.
        jdbc.update("UPDATE import_jobs SET status = 'HELD_FOR_TRUST_REVIEW' WHERE id = ?", upload.job().getId());
        jdbc.update("UPDATE import_sessions SET expires_at = now() - interval '1 minute' WHERE id = ?",
                upload.job().getImportSessionId());

        assertThat(copy(upload.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------------- only the uploads the flag names

    /** A second upload of the same layout by another customer, which read without raising the review. */
    private Upload anotherUploadOf(Upload first) {
        Upload other = queueUpload(plantedPdf(), false);
        jdbc.update("UPDATE statement_analysis_sessions SET layout_fingerprint = ? WHERE reference = ?",
                first.fingerprint(), other.reference());
        return new Upload(other.reference(), first.fingerprint(), other.job());
    }

    @Test
    void anUploadTheFlagDoesNotNameIsRefused() {
        Upload flagged = queueUpload();
        Upload other = anotherUploadOf(flagged);

        ResponseEntity<String> response = copy(other.reference(), REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("did not raise this layout's review");
        assertThat(actionsOn(other.job().getId())).isEmpty();
        assertThat(openedNoticesFor(other.job().getUserId())).isEmpty();
        assertThat(copy(flagged.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theUploadThatRaisedAReasonCanBeCopied_afterALaterUploadMovedTheReview() {
        Upload first = queueUpload();
        Upload later = anotherUploadOf(first);
        // The later upload raised a reason of its own and the review now points at it; the first
        // upload stays the source of the reason it raised (V270).
        jdbc.update("UPDATE layout_registry SET review_analysis_reference = ?, "
                + "review_reason_sources = jsonb_build_object('NEW_LAYOUT', ?::text, 'VERIFICATION_NOT_PASSED:BALANCE_CHAIN', ?::text) "
                + "WHERE fingerprint = ?", later.reference(), first.reference(), later.reference(), first.fingerprint());

        assertThat(copy(first.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(copy(later.reference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCsvUploadIsRefusedBeforeItsFileIsReadOrTheAccessRecorded() {
        User owner = createCustomer();
        byte[] csv = "Date,Narration,Amount\n01/01/2026,SYNTHETIC SHOP,100.00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentAddress address = storage.store(csv);
        ImportJob job = new ImportJob(owner.getId(), "statement.csv", address.hash(), address.key(), "CSV");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.complete(sessionId, Instant.now());
        importJobRepository.save(job);
        stagedSession(sessionId, owner.getId());
        String fingerprint = newFingerprint();
        registerLayout(fingerprint, true);
        String reference = newReference();
        analysisRepository.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.csv", "CSV", (long) csv.length,
                fingerprint, 1, 100L, 1, "{}", sessionId, "it-correlation"));
        reviewNames(fingerprint, reference);

        ResponseEntity<String> response = copy(reference, REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("not available for CSV");
        assertThat(actionsOn(job.getId())).isEmpty();
        assertThat(openedNoticesFor(owner.getId())).isEmpty();
    }

    // ------------------------------------------------------------------------- the review queue says where a copy can be made

    /** Points the layout's review at this upload, as staging does when it flags. */
    private void reviewNames(String fingerprint, String reference) {
        jdbc.update("UPDATE layout_registry SET review_analysis_reference = ? WHERE fingerprint = ?", reference, fingerprint);
    }

    /** The review queue's {@code reviewCopy} for one layout, as the portal receives it. */
    private com.fasterxml.jackson.databind.JsonNode reviewCopyOf(String fingerprint, User caller) throws Exception {
        ResponseEntity<String> queue = restTemplate.exchange("/api/v1/admin/imports/layout-registry/review-queue",
                HttpMethod.GET, new HttpEntity<>(bearerFor(caller)), String.class);
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var entry : new com.fasterxml.jackson.databind.ObjectMapper().readTree(queue.getBody()).get("data")) {
            if (fingerprint.equals(entry.get("fingerprint").asText())) return entry.get("reviewCopy");
        }
        throw new AssertionError("layout " + fingerprint + " is not in the review queue");
    }

    @Test
    void theQueueSaysACopyCanBeMadeOfAKeptPdfUpload() throws Exception {
        Upload upload = queueUpload();
        reviewNames(upload.fingerprint(), upload.reference());

        var copy = reviewCopyOf(upload.fingerprint(), createUser("ADMIN"));

        assertThat(copy.get("available").asBoolean()).isTrue();
        assertThat(copy.get("refusal").isNull()).isTrue();
        assertThat(copy.get("reference").asText()).isEqualTo(upload.reference());
        // Checking reads nothing and records nothing.
        assertThat(actionsOn(upload.job().getId())).isEmpty();
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
    }

    /** The review now points at {@code latest}; {@code earlier} stays the source of the reason it raised. */
    private void reviewMovedTo(Upload latest, Upload earlier) {
        jdbc.update("UPDATE layout_registry SET review_analysis_reference = ?, "
                + "review_reason_sources = jsonb_build_object('NEW_LAYOUT', ?::text) WHERE fingerprint = ?",
                latest.reference(), earlier.reference(), earlier.fingerprint());
    }

    @Test
    void theQueueOffersTheEarlierUploadThatRaisedAReason_whenTheLatestCannotBeCopied() throws Exception {
        Upload earlier = queueUpload();
        Upload latest = anotherUploadOf(earlier);
        reviewMovedTo(latest, earlier);
        // The customer behind the latest upload discarded it.
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", latest.job().getImportSessionId());
        User admin = createUser("ADMIN");

        var copy = reviewCopyOf(earlier.fingerprint(), admin);

        assertThat(copy.get("available").asBoolean()).isTrue();
        assertThat(copy.get("reference").asText()).isEqualTo(earlier.reference());
        assertThat(actionsOn(earlier.job().getId())).isEmpty();
        assertThat(actionsOn(latest.job().getId())).isEmpty();
        // And the copy the queue offers is one the copy itself makes.
        assertThat(copy(earlier.reference(), REASON, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theQueueKeepsTheLatestUploadWhenItCanBeCopied_evenWithAnEarlierSource() throws Exception {
        Upload earlier = queueUpload();
        Upload latest = anotherUploadOf(earlier);
        reviewMovedTo(latest, earlier);

        var copy = reviewCopyOf(earlier.fingerprint(), createUser("ADMIN"));

        assertThat(copy.get("available").asBoolean()).isTrue();
        assertThat(copy.get("reference").asText()).isEqualTo(latest.reference());
    }

    @Test
    void theQueueGivesTheLatestUploadsRefusal_whenNoUploadTheFlagNamesCanBeCopied() throws Exception {
        Upload earlier = queueUpload();
        Upload latest = anotherUploadOf(earlier);
        reviewMovedTo(latest, earlier);
        // Each refused for its own reason: the latest discarded, the earlier a CSV.
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", latest.job().getImportSessionId());
        jdbc.update("UPDATE import_jobs SET source_format = 'CSV' WHERE id = ?", earlier.job().getId());
        assertThat(copy(earlier.reference(), REASON, createUser("ADMIN")).getBody()).contains("not available for CSV");

        var copy = reviewCopyOf(earlier.fingerprint(), createUser("ADMIN"));

        assertThat(copy.get("available").asBoolean()).isFalse();
        // The latest upload's refusal, not the earlier one's.
        assertThat(copy.get("refusal").asText()).isEqualTo("CUSTOMER_STOPPED");
        assertThat(copy.get("reference").isNull()).isTrue();
    }

    @Test
    void theQueueSaysWhyNoCopyCanBeMade_withoutNamingTheCustomersChoice() throws Exception {
        User admin = createUser("ADMIN");

        Upload cancelled = queueUpload();
        reviewNames(cancelled.fingerprint(), cancelled.reference());
        jdbc.update("UPDATE import_jobs SET status = 'CANCELLED' WHERE id = ?", cancelled.job().getId());
        Upload closing = queueUpload();
        reviewNames(closing.fingerprint(), closing.reference());
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", User.STATUS_PENDING_DELETION, closing.job().getUserId());

        User owner = createCustomer();
        String direct = newFingerprint();
        registerLayout(direct, true);
        String directRef = newReference();
        analysisRepository.save(StatementAnalysisSession.parsed(directRef, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, CUSTOMER_FILE_NAME, "PDF", 100L,
                direct, 1, 100L, 5, "{}", UUID.randomUUID(), "it-correlation"));
        reviewNames(direct, directRef);

        String noUpload = newFingerprint();
        registerLayout(noUpload, true);

        Upload discarded = queueUpload();
        jdbc.update("DELETE FROM import_sessions WHERE id = ?", discarded.job().getImportSessionId());

        assertThat(reviewCopyOf(cancelled.fingerprint(), admin).get("refusal").asText()).isEqualTo("CUSTOMER_STOPPED");
        assertThat(reviewCopyOf(discarded.fingerprint(), admin).get("refusal").asText()).isEqualTo("CUSTOMER_STOPPED");
        assertThat(reviewCopyOf(closing.fingerprint(), admin).get("refusal").asText()).isEqualTo("CUSTOMER_STOPPED");
        assertThat(reviewCopyOf(direct, admin).get("refusal").asText()).isEqualTo("NO_KEPT_FILE");
        assertThat(reviewCopyOf(noUpload, admin).get("refusal").asText()).isEqualTo("NO_UPLOAD");
        assertThat(reviewCopyOf(direct, admin).get("available").asBoolean()).isFalse();

        ResponseEntity<String> queue = restTemplate.exchange("/api/v1/admin/imports/layout-registry/review-queue",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(queue.getBody()).doesNotContain("cancelled").doesNotContain("closed or being closed")
                .doesNotContain("discarded");
    }

    @Test
    void theQueueSaysACsvUploadCannotBeCopied() throws Exception {
        User owner = createCustomer();
        byte[] csv = "Date,Narration,Amount\n01/01/2026,SYNTHETIC SHOP,100.00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentAddress address = storage.store(csv);
        ImportJob job = new ImportJob(owner.getId(), "statement.csv", address.hash(), address.key(), "CSV");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.complete(sessionId, Instant.now());
        importJobRepository.save(job);
        stagedSession(sessionId, owner.getId());
        String fingerprint = newFingerprint();
        registerLayout(fingerprint, true);
        String reference = newReference();
        analysisRepository.save(StatementAnalysisSession.parsed(reference, owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.csv", "CSV", (long) csv.length,
                fingerprint, 1, 100L, 1, "{}", sessionId, "it-correlation"));
        reviewNames(fingerprint, reference);

        assertThat(reviewCopyOf(fingerprint, createUser("ADMIN")).get("refusal").asText()).isEqualTo("NOT_PDF");
    }

    @Test
    void theFullRegistryDoesNotCheckCopies() throws Exception {
        Upload upload = queueUpload();
        reviewNames(upload.fingerprint(), upload.reference());

        ResponseEntity<String> registry = restTemplate.exchange("/api/v1/admin/imports/layout-registry",
                HttpMethod.GET, new HttpEntity<>(bearerFor(createUser("ADMIN"))), String.class);
        var found = java.util.stream.StreamSupport.stream(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(registry.getBody()).get("data").spliterator(), false)
                .filter(e -> upload.fingerprint().equals(e.get("fingerprint").asText())).findFirst().orElseThrow();
        assertThat(found.get("reviewCopy").isNull()).isTrue();
    }

    @Test
    void anUnknownReferenceIsNotFound() {
        assertThat(copy(newReference(), REASON, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------------- refused after the access is recorded

    @Test
    void aFileThatIsGoneIsAConflictAndTheAttemptStaysRecorded() {
        Upload upload = queueUpload();
        storage.delete(upload.job().getObjectKey());
        User superAdmin = createUser("SUPER_ADMIN");

        ResponseEntity<String> response = copy(upload.reference(), REASON, createUser("ADMIN"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("could not be read");
        assertThat(actionsOn(upload.job().getId())).containsExactly("LAYOUT_UPLOAD_BLANKED_COPY");
        assertThat(openedNoticesFor(upload.job().getUserId())).isEmpty();
        verify(emailProvider, after(1_500).never()).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
    }

    @Test
    void theStatusEndpointAnswersALayoutManager() {
        ResponseEntity<String> status = restTemplate.exchange("/api/v1/admin/blanked-copy/status", HttpMethod.GET,
                new HttpEntity<>(bearerFor(createLayoutSupportUser())), String.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("\"enabled\":true");
    }
}
