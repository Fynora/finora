package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.testsupport.TestSessions;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSession.Outcome;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.uploads.MalwareScanner;
import com.finora.uploads.MalwareScanner.ScanResult;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Gate 1 spec §5.1: an upload refused before the parser read it leaves a record.
 *
 * <p>Until V267 these refusals left nothing behind, so a tester who tried to upload and was turned
 * away -- wrong file, a locked PDF with no password, a busy server -- was indistinguishable from
 * one who never tried. Over real HTTP, on the three endpoints a customer actually uploads through,
 * because what is under test is the seam: the refusal the client receives is unchanged, and
 * exactly one REJECTED row says who and why, with nothing about the file.
 */
@org.springframework.test.context.TestPropertySource(properties = {
        // The queued endpoint is only reachable with statement storage on (ImportJobEndpointIT
        // explains why the base context leaves it off); the worker itself stays off.
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-pre-read-rejection-it",
        "app.import.queue.enabled=false"
})
class ImportPreReadRejectionIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementAnalysisSessionRepository analysisSessions;
    @Autowired private ImportJobRepository jobs;

    @MockitoBean private MalwareScanner scanner;
    @MockitoSpyBean private ImportConcurrencyLimiter limiter;
    @MockitoSpyBean private com.finora.imports.analysis.StatementAnalysisRecorder recorder;

    private static final String UNREADABLE_CSV = "this file has no header row and no columns at all";
    private static final String READABLE_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            """;

    @BeforeEach
    void aCleanScannerByDefault() {
        when(scanner.scan(any())).thenReturn(ScanResult.clean());
        when(scanner.describe()).thenReturn("mock scanner");
    }

    private User user() {
        User user = new User();
        user.setEmail("pre-read-rejection-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Pre-read Rejection IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> post(User user, String path, byte[] content, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<StatementAnalysisSession> rowsOf(User user) {
        return analysisSessions.findAll().stream().filter(s -> user.getId().equals(s.getUserId())).toList();
    }

    /** The one REJECTED row, checked for everything it must and must not carry. */
    private void assertOnlyARefusal(User user, String format, String code) {
        assertThat(rowsOf(user)).singleElement().satisfies(row -> assertIsRefusal(row, format, code));
    }

    private static void assertIsRefusal(StatementAnalysisSession row, String format, String code) {
        assertThat(row.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(row.getFailureCode()).isEqualTo(code);
        assertThat(row.getSourceFormat()).isEqualTo(format);
        assertThat(row.getFileName()).as("the file never entered the pipeline").isNull();
        assertThat(row.getByteSize()).isNull();
        assertThat(row.getContentHash()).isNull();
        assertThat(row.getFailureDetail()).isNull();
    }

    /**
     * For a 503: the test's HTTP client retries a 503 once by itself (measured: a second request,
     * its own correlation id, about a second later), so there is one refusal per REQUEST rather
     * than exactly one. Each request leaving its own row is the behaviour wanted -- two attempts
     * turned away are two attempts.
     */
    private void assertOneRefusalPerRequest(User user, String format, String code) {
        assertOneRefusalPerRequest(rowsOf(user), format, code);
    }

    /**
     * A busy refusal is recorded off the request thread (the refusal must stay instant -- see
     * {@code UploadRefusalLog.refusedAsBusy}), so its row arrives shortly after the response.
     */
    private void assertOneBusyRefusalPerRequestArrives(User user, String format) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<StatementAnalysisSession> rows = rowsOf(user);
        while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            rows = rowsOf(user);
        }
        // The client's own retry of the 503 has already been answered by the time exchange()
        // returned, so its record is queued behind the first on the same single thread; a moment
        // for that one to be written too before judging "one per request".
        Thread.sleep(500);
        assertOneRefusalPerRequest(rowsOf(user), format, "IMPORT_SYSTEM_BUSY");
    }

    private static void assertOneRefusalPerRequest(List<StatementAnalysisSession> rows, String format, String code) {
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertIsRefusal(row, format, code));
        assertThat(rows).extracting(StatementAnalysisSession::getCorrelationId)
                .as("one row per request, never two for one").doesNotContainNull().doesNotHaveDuplicates();
    }

    // --- /csv/stage -----------------------------------------------------------------------------

    @Test
    void anEmptyCsvUploadIsRefusedAndRecorded() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", new byte[0], "empty.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertOnlyARefusal(user, "CSV", "HTTP_400");
    }

    @Test
    void aPdfSentToTheCsvEndpointIsRefusedAndRecorded() throws Exception {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage",
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "statement.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertOnlyARefusal(user, "CSV", "HTTP_415");
    }

    @Test
    void aFileTheVirusScanFlagsIsRefusedAndRecorded() {
        when(scanner.scan(any())).thenReturn(ScanResult.infected("Eicar-Test-Signature"));
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", bytes(READABLE_CSV), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertOnlyARefusal(user, "CSV", "UPLOAD_MALWARE_DETECTED");
    }

    @Test
    void anUploadRefusedBecauseTheScannerIsDownIsRecorded() {
        when(scanner.scan(any())).thenReturn(ScanResult.unavailable("connection refused"));
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", bytes(READABLE_CSV), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertOneRefusalPerRequest(user, "CSV", "UPLOAD_SCANNER_UNAVAILABLE");
    }

    @Test
    void anUploadTurnedAwayBecauseTheServerIsBusyIsRecorded() throws Exception {
        doThrow(new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY)).when(limiter).runGated(any());
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", bytes(READABLE_CSV), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertOneBusyRefusalPerRequestArrives(user, "CSV");
    }

    /**
     * Review Focus 4: a file that DID reach the parser is a parse failure, already recorded by
     * {@code ImportService} with its name and hash. It must not also be recorded as a refusal.
     */
    @Test
    void aFileTheParserCouldNotReadIsAFailure_notAlsoARefusal() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", bytes(UNREADABLE_CSV), "unreadable.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rowsOf(user)).singleElement().satisfies(row -> {
            assertThat(row.getOutcome()).isEqualTo(Outcome.FAILED);
            assertThat(row.getFileName()).isEqualTo("unreadable.csv");
        });
    }

    @Test
    void aStatementThatIsReadLeavesNoRefusal() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/csv/stage", bytes(READABLE_CSV), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsOf(user)).extracting(StatementAnalysisSession::getOutcome).containsExactly(Outcome.PARSED);
    }

    // --- /pdf/stage -----------------------------------------------------------------------------

    @Test
    void aNonPdfSentToThePdfEndpointIsRefusedAndRecorded() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/pdf/stage", bytes(READABLE_CSV), "statement.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertOnlyARefusal(user, "PDF", "HTTP_415");
    }

    @Test
    void aPdfTurnedAwayBecauseTheServerIsBusyIsRecorded() throws Exception {
        doThrow(new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY)).when(limiter).runGated(any());
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/pdf/stage",
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "statement.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertOneBusyRefusalPerRequestArrives(user, "PDF");
    }

    // --- /jobs (the queued upload): the same refusals, before any job exists -----------------------

    @Test
    void anEmptyUploadToTheQueueIsRefusedAndRecorded() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/jobs", new byte[0], "empty.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertOnlyARefusal(user, "CSV", "HTTP_400");
        assertThat(jobs.findAll()).noneMatch(job -> job.getUserId().equals(user.getId()));
    }

    /**
     * The exit that mattered most in the October 2026 baseline: the queue refuses a locked PDF and
     * the app asks for its password. A user who gives up at that prompt used to leave nothing
     * behind -- no job, no failure, no trace.
     */
    @Test
    void aLockedPdfRefusedAtTheQueueForItsPasswordIsRecorded() throws Exception {
        User user = user();
        byte[] locked = PdfFixtureBuilder.encrypt(
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "AAAA1234");

        ResponseEntity<String> response = post(user, "/api/v1/import/jobs", locked, "statement.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).as("the client's cue to ask for the password is unchanged").contains("IMPORT_008");
        assertOnlyARefusal(user, "PDF", "IMPORT_PDF_PASSWORD_REQUIRED");
        assertThat(jobs.findAll()).noneMatch(job -> job.getUserId().equals(user.getId()));
    }

    @Test
    void anAcceptedQueuedUploadLeavesNoRefusal() {
        User user = user();

        ResponseEntity<String> response = post(user, "/api/v1/import/jobs", bytes(READABLE_CSV), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(rowsOf(user)).isEmpty();
    }

    // --- what a refusal must never do ---------------------------------------------------------------

    /** A refusal has no file name and no hash; in the user's own failure list it would be a
     *  nameless row nothing could ever clear. They were told at the moment of the refusal. */
    @Test
    void aRefusalNeverAppearsInTheUsersOwnFailureList() {
        User user = user();
        post(user, "/api/v1/import/csv/stage", new byte[0], "empty.csv");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));

        ResponseEntity<String> failures = restTemplate.exchange("/api/v1/import/failures", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(failures.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failures.getBody()).contains("\"data\":[]");
    }

    /**
     * Recording is evidence; the refusal is the answer the user is owed. A recorder that cannot
     * write -- thrown here from outside its own try/catch, as a transaction that cannot open is --
     * must leave every refusal exactly as it was, on every endpoint, never a 500.
     */
    @Test
    void aRecorderThatCannotWriteNeverChangesWhatTheUserIsTold() throws Exception {
        // Only the on-request write is stubbed. The busy refusal's write is asynchronous, so it
        // cannot throw into a request at all; that its hand-off never throws is UploadRefusalLogTest's.
        doThrow(new org.springframework.transaction.CannotCreateTransactionException("no connection available"))
                .when(recorder).recordRejected(any(), any(), org.mockito.ArgumentMatchers.anyString());
        User user = user();

        assertThat(post(user, "/api/v1/import/csv/stage", new byte[0], "empty.csv").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(user, "/api/v1/import/pdf/stage", bytes(READABLE_CSV), "statement.pdf").getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ResponseEntity<String> locked = post(user, "/api/v1/import/jobs", PdfFixtureBuilder.encrypt(
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), "AAAA1234"), "statement.pdf");
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(locked.getBody()).contains("IMPORT_008");

        assertThat(rowsOf(user)).as("nothing could be recorded, and nothing else changed").isEmpty();
    }
}
