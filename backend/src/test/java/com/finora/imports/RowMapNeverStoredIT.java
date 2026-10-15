package com.finora.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.blanked.RowMapBlock;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row map never reaches stored data (docs/superpowers/specs/2026-10-15-row-map-design.md,
 * "The note never escapes"). A synthetic PDF goes through the direct upload and its session
 * confirm, and through the queued import job; then every row of every table, every response body
 * and every log line captured is searched for any shape the row map could take.
 *
 * <p>Positive control first: the same PDF, read with a sink, does produce a non-empty row map, so
 * there was something to leak.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-row-map-never-stored-it",
        "app.import.queue.enabled=false",
        "app.rate-limit.import-stage.max=10000"
})
@ExtendWith(OutputCaptureExtension.class)
class RowMapNeverStoredIT extends AbstractIntegrationTest {

    /** What a row map would look like anywhere it leaked: its class and field names, its record
     *  string form, and the blanked copy's block keyword. */
    static final List<String> ROW_MAP_SHAPES = List.of("RowLines", "yHundredths", "Line[page=", "rowLines", "rowmap");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportJobWorker worker;
    @Autowired private PdfPreviewGenerator generator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @Test
    void noRowMapReachesAnyTableResponseOrLogLine(CapturedOutput output) throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();

        Map<Integer, Map<Integer, RowLines>> rowMap = new LinkedHashMap<>();
        generator.generateSectionsFromAcquired(UUID.randomUUID(), "statement.pdf", generator.acquire(pdf, null),
                rowMap::put);
        assertThat(rowMap).as("positive control: the reader does make a row map for this PDF").isNotEmpty();
        assertThat(rowMap.values().iterator().next()).isNotEmpty();
        assertThat(rowMap.toString()).as("the shapes searched for are what a leaked row map looks like")
                .contains("RowLines", "yHundredths", "Line[page=");

        // And the positions themselves, written as the blanked copy's block writes them.
        List<String> positions = new ArrayList<>(ROW_MAP_SHAPES);
        RowMapBlock.format(rowMap).lines().filter(line -> line.contains(":"))
                .map(line -> line.substring(line.indexOf(':') + 1)).forEach(positions::add);
        assertThat(positions).hasSizeGreaterThan(ROW_MAP_SHAPES.size());

        List<String> bodies = new ArrayList<>();

        // The direct upload, then its session confirmed.
        User direct = user();
        ResponseEntity<String> staged = restTemplate.exchange("/api/v1/import/pdf/stage", HttpMethod.POST,
                fileUpload(direct, pdf), String.class);
        assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
        bodies.add(staged.getBody());
        UUID sessionId = UUID.fromString(mapper.readTree(staged.getBody()).get("data").get("sessionId").asText());
        ResponseEntity<String> review = restTemplate.exchange("/api/v1/import/sessions/" + sessionId,
                HttpMethod.GET, new HttpEntity<>(bearerFor(direct)), String.class);
        bodies.add(review.getBody());
        ResponseEntity<String> confirmed = restTemplate.exchange("/api/v1/import/csv/confirm", HttpMethod.POST,
                new HttpEntity<>(confirmRequest(sessionId), jsonBearerFor(direct)), String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        bodies.add(confirmed.getBody());

        // The queued import job, run to completion.
        User queued = user();
        ResponseEntity<String> accepted = restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                fileUpload(queued, pdf), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        bodies.add(accepted.getBody());
        UUID jobId = UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
        worker.drainOnce();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        bodies.add(restTemplate.exchange("/api/v1/import/jobs/" + jobId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(queued)), String.class).getBody());

        boolean sawThisTestsUsers = false;
        for (String table : tables()) {
            List<String> rows = jdbc.queryForList("SELECT t::text FROM \"" + table + "\" t", String.class);
            for (String row : rows) {
                if (row.contains(direct.getId().toString()) || row.contains(queued.getId().toString())) {
                    sawThisTestsUsers = true;
                }
                for (String shape : positions) {
                    assertThat(row).as("table %s", table).doesNotContain(shape);
                }
            }
        }
        assertThat(sawThisTestsUsers).as("the scan reads the rows this test wrote").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_sessions WHERE user_id IN (?, ?)", Long.class,
                direct.getId(), queued.getId())).as("both paths left a session to scan").isGreaterThanOrEqualTo(2L);
        for (String body : bodies) {
            for (String shape : positions) assertThat(body).doesNotContain(shape);
        }
        for (String shape : positions) assertThat(output.getAll()).doesNotContain(shape);
    }

    private List<String> tables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'""", String.class);
        assertThat(tables).contains("import_sessions", "import_verification_findings", "import_job_stages",
                "statement_analysis_sessions", "audit_logs");
        return tables;
    }

    private ConfirmRequest confirmRequest(UUID sessionId) {
        ImportSession session = sessionRepository.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row ->
                new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                        row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                        row.duplicateMatch() == null, row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false, row.categoryConfidence(),
                        row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount())).toList();
        NewAccountRequest account = new NewAccountRequest("Row map IT account", "SAVINGS", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
    }

    private User user() {
        User user = new User();
        user.setEmail("row-map-never-stored-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Row Map IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpHeaders jsonBearerFor(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpEntity<MultiValueMap<String, Object>> fileUpload(User user, byte[] content) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return "statement.pdf"; }
        });
        return new HttpEntity<>(body, headers);
    }
}
