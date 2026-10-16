package com.finora.imports.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 1: every import ends somewhere the user can see. A queued import is COMPLETED once its rows
 * are staged and the job never changes again, so both apps read it as "Ready to review" for as long
 * as it existed -- after the rows were imported, and after they expired unconfirmed, when opening
 * the review only failed. {@code reviewState} on the job says which of the three it is.
 *
 * <p>Driven through the real upload, worker, confirm and sweep. Expiry is the one thing put in place
 * by hand ({@code expires_at} moved into the past), because waiting 48 hours is not a test.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-job-review-state-it",
        "app.import.queue.enabled=false"
})
class ImportJobReviewStateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportJobWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    /** Synthetic: three ordinary rows, nothing the trust check holds. */
    private static final String JULY = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            2026-07-14,SALARY CREDIT TEST EMPLOYER,52000.00,CREDIT
            """;

    @Test
    void aFreshlyCompletedImportsReviewIsOpen() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(progress.get("reviewState").asText()).isEqualTo("OPEN");
        assertThat(get(user, "/api/v1/import/sessions/" + sessionOf(progress)).getStatusCode())
                .as("and the review it points at opens").isEqualTo(HttpStatus.OK);
        assertThat(recentState(user, jobId)).isEqualTo("OPEN");
    }

    @Test
    void anImportWhoseRowsWereConfirmedReadsImported() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);

        assertThat(confirm(user, sessionOf(progress(user, jobId))).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("IMPORTED");
        assertThat(recentState(user, jobId)).isEqualTo("IMPORTED");
    }

    /** The sweep deletes confirmed sessions too once they pass their expiry; the file still says
     *  the statement was imported. */
    @Test
    void aConfirmedImportStillReadsImportedOnceItsRowsAreSwept() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);
        UUID sessionId = sessionOf(progress(user, jobId));
        assertThat(confirm(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);

        expireSessionsOf(user);
        importSessionService.sweepExpiredSessions();

        assertThat(sessionRepository.findById(sessionId)).as("the confirmed rows were swept").isEmpty();
        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("IMPORTED");
    }

    /** The case this exists for: read, never confirmed, past its 48 hours -- before the sweep
     *  reaches it, and after. */
    @Test
    void anUnconfirmedImportPastItsExpiryReadsExpiredBeforeAndAfterTheSweep() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);
        UUID sessionId = sessionOf(progress(user, jobId));

        expireSessionsOf(user);
        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("EXPIRED");
        assertThat(get(user, "/api/v1/import/sessions/" + sessionId).getStatusCode())
                .as("the review it would have opened only fails").isEqualTo(HttpStatus.BAD_REQUEST);

        importSessionService.sweepExpiredSessions();
        assertThat(sessionRepository.findById(sessionId)).isEmpty();
        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(progress.get("reviewState").asText()).isEqualTo("EXPIRED");
        assertThat(recentState(user, jobId)).isEqualTo("EXPIRED");
    }

    /** The user opens the review and cancels it: the rows are deleted, nothing was imported, and the
     *  import says so rather than offering a review that no longer exists. */
    @Test
    void anImportWhoseReviewTheUserCancelledReadsAsNotImported() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);
        UUID sessionId = sessionOf(progress(user, jobId));

        ResponseEntity<String> discarded = restTemplate.exchange("/api/v1/import/sessions/" + sessionId,
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("EXPIRED");
        assertThat(dismiss(user, jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** The same file uploaded again and imported: the statement is in the accounts, so the
     *  earlier import no longer says nothing was imported. */
    @Test
    void anExpiredImportReadsImportedOnceTheSameFileIsImportedAgain() throws Exception {
        User user = user();
        UUID first = completedUpload(user);
        expireSessionsOf(user);
        importSessionService.sweepExpiredSessions();
        assertThat(progress(user, first).get("reviewState").asText()).isEqualTo("EXPIRED");

        UUID second = completedUpload(user);
        assertThat(second).isNotEqualTo(first);
        assertThat(confirm(user, sessionOf(progress(user, second))).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(progress(user, first).get("reviewState").asText()).isEqualTo("IMPORTED");
        assertThat(progress(user, second).get("reviewState").asText()).isEqualTo("IMPORTED");
    }

    /** Imported and later deleted is still imported: "nothing was imported" would be false. The
     *  deletion is a soft delete put in place by hand, as the statement delete leaves it. */
    @Test
    void aStatementImportedAndThenDeletedStillReadsImported() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);
        assertThat(confirm(user, sessionOf(progress(user, jobId))).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE user_id = ?", user.getId());
        expireSessionsOf(user);
        importSessionService.sweepExpiredSessions();

        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("IMPORTED");
    }

    /** Another user's import of the same file is not this user's. */
    @Test
    void someoneElsesImportOfTheSameFileDoesNotCount() throws Exception {
        User user = user();
        UUID jobId = completedUpload(user);
        expireSessionsOf(user);
        importSessionService.sweepExpiredSessions();

        User other = user();
        UUID theirs = completedUpload(other);
        assertThat(confirm(other, sessionOf(progress(other, theirs))).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(progress(user, jobId).get("reviewState").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void onlyAJobThatCompletedHasAReviewState() throws Exception {
        User user = user();
        UUID jobId = upload(user, "%PDF-1.4\nthis is not a real document\n".getBytes(StandardCharsets.UTF_8),
                "statement.pdf");
        worker.drainOnce();

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("FAILED");
        assertThat(progress.get("reviewState").isNull()).isTrue();
    }

    /** An expired review ended without importing anything, as a failure does, and can be
     *  dismissed like one. An open or imported one cannot. */
    @Test
    void onlyAnExpiredReviewCanBeDismissed() throws Exception {
        User user = user();
        UUID open = completedUpload(user);
        assertThat(dismiss(user, open).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(confirm(user, sessionOf(progress(user, open))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dismiss(user, open).getStatusCode()).as("imported").isEqualTo(HttpStatus.CONFLICT);

        User other = user();
        UUID expired = completedUpload(other);
        expireSessionsOf(other);
        assertThat(dismiss(other, expired).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dismiss(other, expired).getStatusCode()).as("and again: idempotent").isEqualTo(HttpStatus.OK);
        assertThat(recentJobIds(other)).doesNotContain(expired);
    }

    // ---------------------------------------------------------------------------------------

    private User user() {
        User user = new User();
        user.setEmail("import-job-review-state-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Job Review State IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private ResponseEntity<String> get(User user, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    private UUID upload(User user, byte[] content, String fileName) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }

    /** A queued upload of {@link #JULY}, run by the worker to COMPLETED. */
    private UUID completedUpload(User user) throws Exception {
        UUID jobId = upload(user, JULY.getBytes(StandardCharsets.UTF_8), "statement.csv");
        worker.drainOnce();
        assertThat(progress(user, jobId).get("status").asText()).isEqualTo("COMPLETED");
        return jobId;
    }

    private JsonNode progress(User user, UUID jobId) throws Exception {
        ResponseEntity<String> response = get(user, "/api/v1/import/jobs/" + jobId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static UUID sessionOf(JsonNode progress) {
        return UUID.fromString(progress.get("importSessionId").asText());
    }

    /** This job's {@code reviewState} as the recent-imports list reports it. */
    private String recentState(User user, UUID jobId) throws Exception {
        for (JsonNode job : mapper.readTree(get(user, "/api/v1/import/jobs").getBody()).get("data")) {
            if (job.get("jobId").asText().equals(jobId.toString())) return job.get("reviewState").asText();
        }
        throw new AssertionError("job " + jobId + " is not in the recent-imports list");
    }

    private List<UUID> recentJobIds(User user) throws Exception {
        List<UUID> ids = new ArrayList<>();
        mapper.readTree(get(user, "/api/v1/import/jobs").getBody()).get("data")
                .forEach(job -> ids.add(UUID.fromString(job.get("jobId").asText())));
        return ids;
    }

    private ResponseEntity<String> dismiss(User user, UUID jobId) {
        return restTemplate.exchange("/api/v1/import/jobs/" + jobId + "/dismiss", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    private void expireSessionsOf(User user) {
        int moved = jdbc.update("UPDATE import_sessions SET expires_at = now() - interval '1 minute' "
                + "WHERE user_id = ?", user.getId());
        assertThat(moved).isPositive();
    }

    /** Every staged row included, into a new account -- what a user accepting the review sends. */
    private ResponseEntity<String> confirm(User user, UUID sessionId) {
        ImportSession session = sessionRepository.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream()
                .map(row -> new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                        row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                        row.duplicateMatch() == null, row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false, row.categoryConfidence(),
                        row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount()))
                .toList();
        NewAccountRequest account = new NewAccountRequest("Review state IT account", "SAVINGS", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(), null, null, null, null);
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/import/csv/confirm", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);
    }
}
