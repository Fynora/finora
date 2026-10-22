package com.finora.imports.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportSessionService;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.HeldStatementService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trust check on the synchronous stage endpoints ({@code POST /api/v1/import/csv/stage},
 * {@code /pdf/stage}).
 *
 * <p>Only the queue used to run it, so a statement staged here -- the path both clients send a
 * locked PDF down unless the user ticks "keep password" -- reached the confirm step even when its own
 * extraction contradicted itself. Driven end to end through the real endpoints and the real confirm
 * gate ({@code claimForConfirmation}, {@code listResumableSessions}). The statement period in 2030 is
 * what makes the trust predicate hold, the same trigger {@link HeldStatementReuploadIT} uses.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage",
        "app.import.queue.enabled=false"
})
class StagingTrustHoldIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementService heldStatementService;
    @Autowired private com.finora.imports.fix.ClaudeFixJobRepository claudeFixJobs;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private com.finora.imports.original.StatementOriginalViewRepository originalViews;
    @Autowired private ImportJobService importJobService;
    @Autowired private com.finora.repository.AuditLogRepository auditLogRepository;
    @Autowired private com.finora.notification.repository.NotificationRepository notificationRepository;
    @Autowired private JwtService jwtService;
    @Autowired private com.finora.repository.RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    private static final byte[] CLEAN_CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);

    private static final String PASSWORD = "sample-pass";

    private User user() {
        User user = new User();
        user.setEmail("staging-trust-hold-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Staging Trust Hold IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearer(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(com.finora.testsupport.TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private ResponseEntity<String> stage(User user, String path, byte[] content, String fileName, String password) {
        HttpHeaders headers = bearer(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        if (password != null) body.add("password", password);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** POST /csv/stage, asserting the upload itself succeeded. */
    private JsonNode stageCsv(User user, byte[] content) throws Exception {
        ResponseEntity<String> response = stage(user, "/api/v1/import/csv/stage", content, "statement.csv", null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static UUID sessionIdOf(JsonNode data) {
        return UUID.fromString(data.get("sessionId").asText());
    }

    private static UUID heldJobIdOf(JsonNode data) {
        JsonNode held = data.get("heldForReviewJobId");
        return held == null || held.isNull() ? null : UUID.fromString(held.asText());
    }

    private List<ImportJob> jobsOf(User owner) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(owner.getId(), PageRequest.of(0, 20));
    }

    /** The one held job this user's upload produced, and its review record. */
    private ImportJob onlyHeldJob(User owner, UUID sessionId) {
        List<ImportJob> jobs = jobsOf(owner);
        assertThat(jobs).as("one job carries the hold").hasSize(1);
        ImportJob job = jobs.get(0);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(job.getImportSessionId()).isEqualTo(sessionId);
        assertThat(job.getHeldStatementId()).as("an operator can act on it from the queue").isNotNull();
        return job;
    }

    private HeldStatement holdOf(ImportJob job) {
        return heldStatementRepository.findByImportJobId(job.getId()).orElseThrow();
    }

    private void assertNotConfirmable(User owner, UUID sessionId) {
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).doesNotContain(sessionId);
        assertThatThrownBy(() -> importSessionService.claimForConfirmation(owner.getId(), sessionId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.IMPORT_SESSION_HELD_FOR_REVIEW);
    }

    private void assertConfirmable(User owner, UUID sessionId) {
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).contains(sessionId);
        assertThat(importSessionService.claimForConfirmation(owner.getId(), sessionId).getStatus())
                .isEqualTo(ImportSession.STATUS_CONFIRMED);
    }

    // ------------------------------------------------------------------------------- CSV

    @Test
    void aDistrustedStatementStagedDirectlyCannotBeConfirmedUntilAnOperatorApprovesIt() throws Exception {
        User owner = user();
        JsonNode staged = stageCsv(owner, FUTURE_PERIOD_CSV);
        UUID sessionId = sessionIdOf(staged);

        assertNotConfirmable(owner, sessionId);
        ImportJob job = onlyHeldJob(owner, sessionId);
        assertThat(heldJobIdOf(staged)).as("the response tells the client which job to follow").isEqualTo(job.getId());
        assertThat(job.getSourceFormat()).isEqualTo("CSV");
        HeldStatement held = holdOf(job);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.HELD);
        assertThat(held.getTriggerSummary()).contains("future");
        assertThat(held.isLockedWithoutPassword()).isFalse();

        heldStatementService.approve(user().getId(), held.getHeldId(), null, null);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, sessionId);
    }

    @Test
    void aRejectedDirectHoldStaysOutOfTheLedger() throws Exception {
        User owner = user();
        UUID sessionId = sessionIdOf(stageCsv(owner, FUTURE_PERIOD_CSV));
        ImportJob job = onlyHeldJob(owner, sessionId);

        heldStatementService.reject(user().getId(), holdOf(job).getHeldId(), "rows do not match the document");

        ImportJob rejected = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(rejected.getFailureCode()).isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());
        assertNotConfirmable(owner, sessionId);
    }

    @Test
    void theHeldJobReadsAsHeldOnTheProgressEndpointTheClientFollows() throws Exception {
        User owner = user();
        UUID jobId = heldJobIdOf(stageCsv(owner, FUTURE_PERIOD_CSV));
        assertThat(jobId).isNotNull();

        ResponseEntity<String> progress = restTemplate.exchange("/api/v1/import/jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<>(bearer(owner)), String.class);

        assertThat(progress.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(progress.getBody()).get("data").get("status").asText())
                .isEqualTo("HELD_FOR_TRUST_REVIEW");
    }

    @Test
    void aTrustedStatementStagedDirectlyIsNotHeld() throws Exception {
        User owner = user();
        JsonNode staged = stageCsv(owner, CLEAN_CSV);

        assertThat(heldJobIdOf(staged)).isNull();
        assertThat(jobsOf(owner)).isEmpty();
        assertConfirmable(owner, sessionIdOf(staged));
    }

    @Test
    void stagingTheSameDistrustedStatementAgainFollowsTheReviewItAlreadyHas() throws Exception {
        User owner = user();
        JsonNode first = stageCsv(owner, FUTURE_PERIOD_CSV);
        JsonNode again = stageCsv(owner, FUTURE_PERIOD_CSV);

        assertThat(sessionIdOf(again)).as("replayed").isEqualTo(sessionIdOf(first));
        assertThat(heldJobIdOf(again)).isEqualTo(heldJobIdOf(first));
        ImportJob job = onlyHeldJob(owner, sessionIdOf(first));

        heldStatementService.approve(user().getId(), holdOf(job).getHeldId(), null, null);
        JsonNode afterApproval = stageCsv(owner, FUTURE_PERIOD_CSV);

        assertThat(heldJobIdOf(afterApproval)).as("an approved review is not opened again").isNull();
        assertThat(jobsOf(owner)).hasSize(1);
        assertConfirmable(owner, sessionIdOf(first));
    }

    @Test
    void stagingAgainAfterARejectionFollowsTheRejectedJob() throws Exception {
        User owner = user();
        JsonNode first = stageCsv(owner, FUTURE_PERIOD_CSV);
        ImportJob job = onlyHeldJob(owner, sessionIdOf(first));
        heldStatementService.reject(user().getId(), holdOf(job).getHeldId(), "rows do not match the document");

        JsonNode again = stageCsv(owner, FUTURE_PERIOD_CSV);

        assertThat(heldJobIdOf(again)).isEqualTo(job.getId());
        assertThat(jobsOf(owner)).hasSize(1);
        assertNotConfirmable(owner, sessionIdOf(first));
    }

    @Test
    void aQueuedUploadOfADirectlyHeldStatementFollowsItsReview() throws Exception {
        User owner = user();
        UUID heldJobId = heldJobIdOf(stageCsv(owner, FUTURE_PERIOD_CSV));

        HttpHeaders headers = bearer(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(FUTURE_PERIOD_CSV) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> queued = restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(mapper.readTree(queued.getBody()).get("data").get("jobId").asText())
                .isEqualTo(heldJobId.toString());
        assertThat(jobsOf(owner)).hasSize(1);
    }


    /** The Claude fix starter runs on its own thread after the hold commits; waits for its row
     *  (the switch is off under test, so the row records OFF). */
    private com.finora.imports.fix.ClaudeFixJob claudeFixRowFor(UUID jobId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            var row = claudeFixJobs.findById(jobId);
            if (row.isPresent()) return row.get();
            Thread.sleep(100);
        }
        throw new AssertionError("no Claude fix row for " + jobId + " after 15 s");
    }

    /** A hold made while staging starts the Claude fix decision once it commits. */
    @Test
    void aStagedHoldRecordsAClaudeFixDecision() throws Exception {
        User owner = user();
        JsonNode staged = stageCsv(owner, FUTURE_PERIOD_CSV);
        ImportJob held = onlyHeldJob(owner, sessionIdOf(staged));

        assertThat(claudeFixRowFor(held.getId()).getReason()).isEqualTo(com.finora.imports.fix.ClaudeFixJob.Reason.OFF);
    }

    @Test
    void aSecondHoldOnTheSameSessionFollowsTheFirstInsteadOfOpeningAnotherReview() throws Exception {
        User owner = user();
        JsonNode staged = stageCsv(owner, FUTURE_PERIOD_CSV);
        UUID sessionId = sessionIdOf(staged);
        ImportJob first = onlyHeldJob(owner, sessionId);

        // What a concurrent second staging of the same bytes does once it gets the session's lock.
        var second = heldStatementService.holdStagedUpload(owner.getId(), "statement.csv", "CSV",
                new com.finora.imports.storage.ContentAddress(first.getContentHash(), "objects/unused"), null,
                new StagedForJob(sessionId, 2, 2, null, List.of(), List.of()),
                new com.finora.imports.trust.HoldDecision(true, List.of("test")), null, false);

        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.getId());
        assertThat(jobsOf(owner)).hasSize(1);
        assertThat(heldStatementRepository.findAll().stream()
                .filter(held -> held.getUserId().equals(owner.getId()))).hasSize(1);
    }

    // ------------------------------------------------------------------------------- locked PDF

    @Test
    void aLockedPdfStagedWithAPasswordThatWasNotKeptIsHeldAndReviewedFromItsRows() throws Exception {
        User owner = user();
        byte[] locked = PdfFixtureBuilder.encrypt(futurePeriodStatementPdf(), PASSWORD);

        ResponseEntity<String> response = stage(owner, "/api/v1/import/pdf/stage", locked, "statement.pdf", PASSWORD);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode staged = mapper.readTree(response.getBody()).get("data");
        UUID sessionId = sessionIdOf(staged);

        assertNotConfirmable(owner, sessionId);
        ImportJob job = onlyHeldJob(owner, sessionId);
        assertThat(heldJobIdOf(staged)).isEqualTo(job.getId());
        assertThat(job.getSourceFormat()).isEqualTo("PDF");
        HeldStatement held = holdOf(job);
        assertThat(held.getTriggerSummary()).contains("future");
        assertThat(held.isLockedWithoutPassword()).isTrue();
        UUID admin = user().getId();

        // Neither the reviewer nor the parser can open the file: both refuse, saying what to use.
        assertThatThrownBy(() -> heldStatementService.rerunParser(admin, held.getHeldId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("password was not kept");
        assertThatThrownBy(() -> heldStatementService.contentForOriginalView(admin, held.getHeldId(),
                "Rows on page 2 are missing from the preview"))
                .isInstanceOf(ApiException.class).hasMessageContaining("password was not kept");
        // ... and the rows are there to review instead -- after a typed reason, with the same
        // rules as the original view: audited with the reason, recorded as an opening of the
        // statement (a ROWS view row, which feeds the customer's import page and the hold's
        // History), and the customer told, once that day.
        var rows = heldStatementService.stagedRows(admin, held.getHeldId(), "Rows on page 2 are missing from the preview");
        assertThat(rows.sections()).hasSize(1);
        assertThat(rows.sections().get(0).rows()).extracting(row -> row.description())
                .contains("SAMPLE GROCERY", "SAMPLE SALARY");
        int rowCount = rows.sections().get(0).rows().size();
        assertThat(originalViews.findByJobIdOrderByOpenedAtAsc(job.getId())).singleElement().satisfies(v -> {
            assertThat(v.getFormat()).isEqualTo("ROWS");
            assertThat(v.getQueue()).isEqualTo("TRUST");
            assertThat(v.getItemRef()).isEqualTo(held.getHeldId());
            assertThat(v.getViewerId()).isEqualTo(admin);
            assertThat(v.getPageCount()).isEqualTo(rowCount);
        });
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
                .filteredOn(e -> e.getAction().equals("TRUST_REVIEW_STAGED_ROWS_VIEWED"))
                .singleElement()
                .satisfies(e -> assertThat(e.getMetadata()).containsEntry("reason", "Rows on page 2 are missing from the preview"));
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                .filteredOn(n -> n.getType() == com.finora.notification.domain.NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX)
                .hasSize(2);
        // A second look the same day is recorded and audited, but tells the customer nothing new.
        heldStatementService.stagedRows(admin, held.getHeldId(), "Second look at the rows on page 2");
        assertThat(originalViews.findByJobIdOrderByOpenedAtAsc(job.getId())).hasSize(2);
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                .filteredOn(n -> n.getType() == com.finora.notification.domain.NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX)
                .hasSize(2);
        // The hold's History names the opening, and the customer's import page lists the day.
        assertThat(heldStatementService.detail(held.getHeldId()).timeline())
                .extracting(e -> e.eventType()).contains("STAGED_ROWS_VIEWED");
        assertThat(importJobService.timeline(owner.getId(), job.getId()).openedForFixOn()).hasSize(1);
        // Through HTTP: a POST with the reason in the body, answered with no-store.
        User adminAccount = user();
        adminAccount.setRole("ADMIN");
        adminAccount.setAccountScope(User.SCOPE_ADMIN);
        userRepository.save(adminAccount);
        HttpHeaders headers = bearer(adminAccount);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> overHttp = restTemplate.exchange(
                "/api/v1/admin/held-statements/" + held.getHeldId() + "/staged-rows", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("reason", "Third look at the rows on page 2"), headers), String.class);
        assertThat(overHttp.getStatusCode()).as(overHttp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(overHttp.getHeaders().getCacheControl()).contains("no-store");
        assertThat(overHttp.getBody()).contains("SAMPLE GROCERY");
        // And a blank reason reads nothing.
        assertThatThrownBy(() -> heldStatementService.stagedRows(admin, held.getHeldId(), "    "))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

        heldStatementService.approve(admin, held.getHeldId(), null, null);
        assertConfirmable(owner, sessionId);
    }

    @Test
    void aRejectedLockedDirectHoldCannotBeReopenedBecauseNothingCanReadItAgain() throws Exception {
        User owner = user();
        byte[] locked = PdfFixtureBuilder.encrypt(futurePeriodStatementPdf(), PASSWORD);
        JsonNode staged = mapper.readTree(stage(owner, "/api/v1/import/pdf/stage", locked, "statement.pdf", PASSWORD)
                .getBody()).get("data");
        HeldStatement held = holdOf(onlyHeldJob(owner, sessionIdOf(staged)));
        UUID admin = user().getId();
        heldStatementService.reject(admin, held.getHeldId(), "rows do not match the document");

        assertThatThrownBy(() -> heldStatementService.reopen(admin, held.getHeldId(), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("its password was never kept");
        assertNotConfirmable(owner, sessionIdOf(staged));
    }

    /** A one-page savings ledger whose printed period is in 2030 -- invented values, laid out the way
     *  the PDF parser reads a ledger: one text run per cell, a header row, then dated rows. */
    private static byte[] futurePeriodStatementPdf() throws Exception {
        float[] col = {50f, 120f, 330f, 420f, 500f};
        String[][] rows = {
                {"DATE", "DETAILS", "REF NO.", "AMOUNT", "BALANCE"},
                {"02/01/2026", "SAMPLE SALARY", "000000000001", "5,000.00", "6,000.00"},
                {"05/01/2026", "SAMPLE GROCERY", "000000000002", "-400.00", "5,600.00"},
                {"09/01/2026", "SAMPLE RENT", "000000000003", "-2,000.00", "3,600.00"},
        };
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9f);
                float y = 770f;
                for (String line : List.of("Sample Bank Savings Account Statement",
                        "Account Number 000000001234", "Statement Period 01/01/2030 to 31/01/2030")) {
                    cs.beginText();
                    cs.newLineAtOffset(50f, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 10f;
                }
                for (String[] row : rows) {
                    for (int i = 0; i < row.length; i++) {
                        cs.beginText();
                        cs.newLineAtOffset(col[i], y);
                        cs.showText(row[i]);
                        cs.endText();
                    }
                    y -= 10f;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
