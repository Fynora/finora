package com.finora.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementContentService;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy workstream 6 (V304): an uploaded statement waiting for the user to press Import is stored
 * encrypted, like a confirmed one, and still imports. Driven over HTTP the way the apps do it, with
 * the stored row read back by raw SQL so the test sees what the database holds, not what the entity
 * hands back. Every statement here is synthetic.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage",
        "app.import.queue.enabled=false"
})
class StagedStatementEncryptionIT extends AbstractIntegrationTest {

    private static final String JULY = """
            Date,Description,Amount,Type
            2026-07-10,SYNTHETIC FOOD ORDER,486.00,DEBIT
            2026-07-11,SYNTHETIC GROCERIES,1240.50,DEBIT
            2026-07-14,SYNTHETIC SALARY CREDIT,52000.00,CREDIT
            """;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementContentService statementContentService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @Test
    void aStagedUpload_isStoredEncrypted_underARecordedKey() throws Exception {
        User user = user();
        byte[] original = JULY.getBytes(StandardCharsets.UTF_8);

        UUID sessionId = stage(user, JULY);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT file_content, encryption_key_id, content_hash FROM import_sessions WHERE id = ?", sessionId);
        byte[] stored = (byte[]) row.get("file_content");
        assertThat(row.get("encryption_key_id")).isNotNull();
        assertThat(stored).isNotEqualTo(original);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .doesNotContain("SYNTHETIC FOOD ORDER")
                .doesNotContain("Date,Description");
        // The identity hash is the ORIGINAL file's, as before: dedupe and the confirmed row rely on it.
        assertThat(row.get("content_hash")).isEqualTo(ContentAddress.hashOf(original));
    }

    @Test
    void anEncryptedStagedUpload_confirmsAndKeepsTheOriginalFile() throws Exception {
        User user = user();
        byte[] original = JULY.getBytes(StandardCharsets.UTF_8);
        UUID sessionId = stage(user, JULY);

        ResponseEntity<String> confirmed = post(user, "/api/v1/import/csv/confirm", confirmRequest(sessionId));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        // The confirmed statement holds the original bytes, not the staging ciphertext.
        UUID statementId = jdbc.queryForObject(
                "SELECT id FROM statement_imports WHERE user_id = ?", UUID.class, user.getId());
        byte[] kept = transactionTemplate.execute(tx -> {
            StatementImport statement = statementImportRepository.findById(statementId).orElseThrow();
            return statementContentService.read(statement);
        });
        assertThat(kept).isEqualTo(original);
    }

    /** Export reads a staged file back as the original. */
    @Test
    void anEncryptedStagedUpload_readsBackForExportAsTheOriginal() throws Exception {
        User user = user();
        UUID sessionId = stage(user, JULY);

        assertThat(importSessionService.readOwnedFileContent(user.getId(), sessionId))
                .isEqualTo(JULY.getBytes(StandardCharsets.UTF_8));
    }

    /** A session staged by the release before V304 holds the plain file and no key id. It must
     *  still confirm, for the 48 hours such a row can outlive the deploy. */
    @Test
    void aSessionStagedBeforeEncryption_stillConfirms() throws Exception {
        User user = user();
        UUID sessionId = stage(user, JULY);
        jdbc.update("UPDATE import_sessions SET file_content = ?, encryption_key_id = NULL WHERE id = ?",
                JULY.getBytes(StandardCharsets.UTF_8), sessionId);

        ResponseEntity<String> confirmed = post(user, "/api/v1/import/csv/confirm", confirmRequest(sessionId));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
    }

    /** Uploading the same file again finds the session it already staged -- dedupe compares the
     *  original file's hash, which encryption (a fresh IV each time) does not change. */
    @Test
    void theSameFileStagedTwice_resolvesToOneSession() throws Exception {
        User user = user();

        UUID first = stage(user, JULY);
        UUID second = stage(user, JULY);

        assertThat(second).isEqualTo(first);
    }

    // ---------------------------------------------------------------------------------------

    private User user() {
        User user = new User();
        user.setEmail("staged-encryption-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Staged Encryption IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private ResponseEntity<String> post(User user, String path, Object body) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** {@code POST /import/csv/stage}; returns the session id. */
    private UUID stage(User user, String csv) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> staged = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(staged.getBody()).get("data");
        return UUID.fromString(data.get("sessionId").asText());
    }

    /** The confirm payload the apps send: every row ticked, into a new savings account. */
    private ConfirmRequest confirmRequest(UUID sessionId) {
        ImportSession session = sessionRepository.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row ->
                new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                        row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                        true, row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false, row.categoryConfidence(),
                        row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount()))
                .toList();
        NewAccountRequest account = new NewAccountRequest("Staged encryption IT account", "SAVINGS", null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
    }
}
