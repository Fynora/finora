package com.finora.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin layout report reads what a real import stores. A statement is uploaded and confirmed
 * over HTTP the way the apps do it, then the report is read over HTTP the way the admin portal
 * does. Before this, the report parsed activated_capabilities_json as a list of strings while
 * every import stored a list of {capability, status} objects, so every layout showed no
 * capabilities and each page view logged a stack trace per import -- with unit tests green,
 * because their fixture wrote a shape no import ever produced.
 *
 * <p>The statement is synthetic. Its Balance column makes the import record RUNNING_BALANCE
 * (TransactionNormalizer), so the stored row is non-empty -- an empty list reads the same under
 * either shape and would prove nothing.
 */
class LayoutReportReadsRealImportsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    /** Nothing in it trips the trust check; the 15th settles the date order (CsvDateOrder). */
    private static final String WITH_BALANCE = """
            Date,Description,Amount,Balance
            01/01/2026,Opening balance,,1000.00
            15/01/2026,Coffee shop,-150.00,850.00
            """;

    @Test
    void theLayoutReportShowsTheCapabilitiesARealImportRecorded() throws Exception {
        User owner = saveUser(null);
        UUID sessionId = UUID.fromString(stage(owner, WITH_BALANCE).get("sessionId").asText());
        assertThat(post(owner, "/api/v1/import/csv/confirm", confirmRequest(sessionId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT layout_fingerprint, activated_capabilities_json FROM statement_imports WHERE user_id = ?",
                owner.getId());
        String fingerprint = (String) stored.get("layout_fingerprint");
        assertThat(fingerprint).isNotBlank();
        // The shape the report must read, as the import stored it.
        assertThat((String) stored.get("activated_capabilities_json"))
                .contains("{\"capability\":\"RUNNING_BALANCE\"");

        User admin = saveUser("ADMIN");

        JsonNode timeline = data(get(admin, "/api/v1/admin/imports/layouts/" + fingerprint + "/timeline"));
        assertThat(timeline).isNotEmpty();
        assertThat(textValues(timeline.get(timeline.size() - 1).get("capabilities"))).contains("RUNNING_BALANCE");

        JsonNode layout = null;
        for (JsonNode summary : data(get(admin, "/api/v1/admin/imports/layouts"))) {
            if (fingerprint.equals(summary.get("fingerprint").asText())) layout = summary;
        }
        assertThat(layout).as("the layout this import produced is in the overview").isNotNull();
        List<String> seen = new ArrayList<>(textValues(layout.get("stableCapabilities")));
        seen.addAll(textValues(layout.get("unstableCapabilities")));
        assertThat(seen).contains("RUNNING_BALANCE");
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private User saveUser(String role) {
        User user = new User();
        user.setEmail("layout-report-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Report IT User");
        user.setPhoneVerified(true);
        if (role != null) {
            user.setRole(role);
            user.setAccountScope(User.SCOPE_ADMIN);
        }
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

    private ResponseEntity<String> post(User user, String path, Object body) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** {@code POST /import/csv/stage}, as the apps upload a statement. Returns the response's data. */
    private JsonNode stage(User user, String csv) throws Exception {
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
        assertThat(data.get("heldForReviewJobId").isNull()).isTrue();
        return data;
    }

    /** Every staged row confirmed into a new account -- the apps' payload when nothing was questioned. */
    private ConfirmRequest confirmRequest(UUID sessionId) {
        ImportSession session = sessionRepository.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row ->
                new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                        row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                        row.duplicateMatch() == null, row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false, row.categoryConfidence(),
                        row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount())).toList();
        NewAccountRequest account = new NewAccountRequest("Layout report IT account", "SAVINGS", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
    }
}
