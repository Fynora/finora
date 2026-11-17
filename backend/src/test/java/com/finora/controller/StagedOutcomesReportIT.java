package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.ImportSession;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/admin/imports/reading-quality/staged-outcomes: what became of customers' staged
 * statements, week by week, driven through the real stage, confirm and discard endpoints.
 *
 * <p>The upload records are shared by every IT in the run, so each test reads the report before and
 * after what it does and checks the difference. ITs never run concurrently ({@code @Isolated}).
 */
@TestPropertySource(properties = {
        "app.rate-limit.import-stage.max=10000",
        "app.import.queue.enabled=false"
})
class StagedOutcomesReportIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/admin/imports/reading-quality/staged-outcomes";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private StatementAnalysisSessionRepository analyses;
    @Autowired private ImportSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.finora.imports.ImportSessionService importSessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User admin;

    /** Transactions from 5 Jan to 5 Mar: over the Free plan's one month. */
    private static final String THREE_MONTH_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            05/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            20/02/2026,UPI-ZORBIC TEAHOUSE-0000000002,80.00,,24800.00
            05/03/2026,UPI-ZORBIC TEAHOUSE-0000000003,100.00,,24700.00
            """;

    private static final String ONE_MONTH_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            05/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            20/01/2026,UPI-ZORBIC TEAHOUSE-0000000002,80.00,,24800.00
            05/02/2026,UPI-ZORBIC TEAHOUSE-0000000003,100.00,,24700.00
            """;

    @BeforeEach
    void setUp() {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals("PLATFORM_DIAGNOSTICS_VIEW")).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("STAGED_OUTCOMES_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Role for the staged outcomes IT");
        role.getPermissions().add(p);
        roleRepository.save(role);
        admin = user(null, role.getName(), User.SCOPE_ADMIN, false);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private User user(String plan, String role, String scope, boolean internal) {
        User user = new User();
        user.setEmail("staged-outcomes-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Staged Outcomes IT User");
        user.setRole(role);
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        user.setInternalAccount(internal);
        user = userRepository.save(user);
        if (plan != null) {
            subscriptionService.provisionFreeSubscription(user.getId());
            if (!"FREE".equals(plan)) subscriptionService.changePlan(user.getId(), plan, "test-plan", user.getId());
        }
        return user;
    }

    private User customer(String plan) {
        return user(plan, "USER", User.SCOPE_USER, false);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private UUID stage(User user, String csv) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(mapper.readTree(response.getBody()).get("data").get("sessionId").asText());
    }

    /** The confirm payload the apps send: every staged row, into a new savings account. */
    private ResponseEntity<String> confirm(User user, UUID sessionId) {
        return confirm(user, sessionId, null);
    }

    /** As above; with {@code latestMonth} set, only rows in it are kept and the latest month is asked for. */
    private ResponseEntity<String> confirm(User user, UUID sessionId, java.time.YearMonth latestMonth) {
        ImportSession session = sessions.findById(sessionId).orElseThrow();
        com.finora.dto.ImportDto.DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row ->
                new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                        row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                        latestMonth == null || java.time.YearMonth.from(row.date()).equals(latestMonth),
                        row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false, row.categoryConfidence(),
                        row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount()))
                .toList();
        com.finora.dto.ImportDto.NewAccountRequest account = new com.finora.dto.ImportDto.NewAccountRequest(
                "Staged outcomes IT account", "SAVINGS", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/import/csv/confirm" + (latestMonth == null ? "" : "?latestMonthOnly=true"),
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    private void discard(User user, UUID sessionId, String platform) {
        discard(user, sessionId, platform, null);
    }

    private void discard(User user, UUID sessionId, String platform, String reason) {
        HttpHeaders headers = bearerFor(user);
        if (platform != null) headers.set("X-Client-Platform", platform);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/sessions/" + sessionId + (reason == null ? "" : "?reason=" + reason),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private StatementAnalysisSession evidenceOf(UUID sessionId) {
        return analyses.findByImportSessionIdOrderByCreatedAtDesc(sessionId).get(0);
    }

    private JsonNode report(String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(PATH + query, HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).path("data");
    }

    /** This week's counts (the last entry of a 4-week report). */
    private JsonNode thisWeek() throws Exception {
        JsonNode weekly = report("?weeks=4").path("weekly");
        return weekly.get(weekly.size() - 1);
    }

    private static final List<String> FIELDS = List.of("staged", "imported", "discardedByCustomer",
            "removedByPhone", "expired", "stillOpen", "notRecorded", "freeLimitShown", "freeLimitRefused",
            "freeLimitThenImported", "latestMonthOnly");

    /** after - before, per field, as "field=n" for the non-zero ones. */
    private static List<String> change(JsonNode before, JsonNode after) {
        return FIELDS.stream()
                .filter(f -> after.path(f).asLong() != before.path(f).asLong())
                .map(f -> f + "=" + (after.path(f).asLong() - before.path(f).asLong()))
                .toList();
    }

    // ---- tests -------------------------------------------------------------------------------------

    @Test
    void eachStagedStatementIsCountedOnce_underWhatBecameOfIt() throws Exception {
        JsonNode before = thisWeek();

        // Free, three months: notice shown, confirm refused, then discarded.
        User refusedThenDiscarded = customer("FREE");
        UUID a = stage(refusedThenDiscarded, THREE_MONTH_CSV);
        assertThat(confirm(refusedThenDiscarded, a).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        discard(refusedThenDiscarded, a, "WEB");

        // Free, one month: imported.
        User importer = customer("FREE");
        UUID b = stage(importer, ONE_MONTH_CSV);
        assertThat(confirm(importer, b).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A composite statement the phone removes by itself.
        User phone = customer("FREE");
        UUID c = stage(phone, ONE_MONTH_CSV);
        ImportSession composite = sessions.findById(c).orElseThrow();
        composite.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        sessions.save(composite);
        discard(phone, c, "MOBILE_ANDROID");

        // Staged and still waiting.
        stage(customer("FREE"), ONE_MONTH_CSV);

        // Shown the notice, upgraded, imported.
        User upgrader = customer("FREE");
        UUID e = stage(upgrader, THREE_MONTH_CSV);
        subscriptionService.changePlan(upgrader.getId(), "PLUS", "test-plan", upgrader.getId());
        assertThat(confirm(upgrader, e).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(change(before, thisWeek())).containsExactlyInAnyOrder(
                "staged=5", "imported=2", "discardedByCustomer=1", "removedByPhone=1", "stillOpen=1",
                "freeLimitShown=2", "freeLimitRefused=1", "freeLimitThenImported=1");

        // The import stamp is written once the confirm commits; a refused confirm leaves none.
        assertThat(evidenceOf(b).getConfirmedAt()).isNotNull();
        assertThat(evidenceOf(e).getConfirmedAt()).isNotNull();
        assertThat(evidenceOf(a).getConfirmedAt()).isNull();
    }

    @Test
    void anExpiredStatement_andOneTheSystemRemovedWithoutARecord() throws Exception {
        JsonNode before = thisWeek();

        // Swept at expiry: the sweep stamps the record, then deletes the session.
        UUID expired = stage(customer("FREE"), ONE_MONTH_CSV);
        jdbc.update("UPDATE statement_analysis_sessions SET expired_unconfirmed_at = now() WHERE import_session_id = ?",
                expired);
        sessions.deleteById(expired);

        // Removed with nothing recorded (a re-read, a stale copy replaced on re-upload).
        UUID removed = stage(customer("FREE"), ONE_MONTH_CSV);
        sessions.deleteById(removed);

        // Confirmed before imports were recorded: its session is still there, but it is not waiting.
        User early = customer("FREE");
        UUID confirmedUnrecorded = stage(early, ONE_MONTH_CSV);
        assertThat(confirm(early, confirmedUnrecorded).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE statement_analysis_sessions SET confirmed_at = NULL WHERE import_session_id = ?",
                confirmedUnrecorded);
        assertThat(sessions.existsById(confirmedUnrecorded)).isTrue();

        assertThat(change(before, thisWeek())).containsExactlyInAnyOrder("staged=3", "expired=1", "notRecorded=2");
    }

    /** Two savings accounts in one PDF: staged as a composite statement. */
    private static byte[] twoAccountPdf() throws Exception {
        var account = (java.util.function.BiFunction<String, String,
                com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity>) (id, masked) ->
                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity(
                        id, "SAVINGS", com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence.DETECTED,
                        masked, com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions.FALSE,
                        List.of(
                                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                        LocalDate.of(2026, 1, 5), "Coffee shop", new java.math.BigDecimal("100.00"), false),
                                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                        LocalDate.of(2026, 1, 20), "Book shop", new java.math.BigDecimal("80.00"), false)));
        return com.finora.imports.pdf.fixtures.PdfFixtureBuilder.render(
                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition("staged-outcomes-multi-001",
                        List.of(account.apply("savings-one", "••••4321"), account.apply("savings-two", "••••8765")),
                        List.of()));
    }

    @Test
    void aStatementCoveringTwoAccounts_isImportedThroughItsOwnConfirm() throws Exception {
        JsonNode before = thisWeek();
        User user = customer("PLUS");

        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(twoAccountPdf()) {
            @Override public String getFilename() { return "statement.pdf"; }
        });
        JsonNode staged = mapper.readTree(restTemplate.exchange("/api/v1/import/pdf/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody()).get("data");
        assertThat(staged.get("multiAccount").asBoolean()).as("a composite statement").isTrue();
        UUID sessionId = UUID.fromString(staged.get("sessionId").asText());

        ImportSession session = sessions.findById(sessionId).orElseThrow();
        List<com.finora.dto.ImportDto.SectionConfirm> sections = new java.util.ArrayList<>();
        int n = 0;
        for (com.finora.dto.ImportDto.StagedAccountSection section : importSessionService.readSections(session)) {
            List<ConfirmedRow> rows = section.rows().stream().map(row ->
                    new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(), "Other", true,
                            row.categorySource(), row.ruleId(), row.likelyDuplicate(), row.referenceNumber(),
                            row.balanceAfter(), false)).toList();
            sections.add(new com.finora.dto.ImportDto.SectionConfirm(rows, null,
                    new com.finora.dto.ImportDto.NewAccountRequest("Staged outcomes IT account " + (++n), "SAVINGS",
                            null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null),
                    null, null));
        }
        HttpHeaders json = bearerFor(user);
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> confirmed = restTemplate.exchange("/api/v1/import/pdf/confirm-multi", HttpMethod.POST,
                new HttpEntity<>(new com.finora.dto.ImportDto.MultiAccountConfirmRequest(sessionId, sections), json),
                String.class);
        assertThat(confirmed.getStatusCode()).as(confirmed.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getConfirmedAt()).isNotNull();
        assertThat(change(before, thisWeek())).containsExactlyInAnyOrder("staged=1", "imported=1");
    }

    @Test
    void theCustomersReasons_areCountedUnderTheirDiscards() throws Exception {
        JsonNode before = thisWeek().path("discardReasons");

        for (String reason : java.util.Arrays.asList("WRONG_FILE", "TOO_MANY_MONTHS", "LOOKS_WRONG", "LATER",
                "OTHER", "OTHER", null)) {
            User user = customer("FREE");
            discard(user, stage(user, ONE_MONTH_CSV), "WEB", reason);
        }
        // The phone's own removal is not a customer's discard, so its reason is not counted.
        User phone = customer("FREE");
        UUID composite = stage(phone, ONE_MONTH_CSV);
        ImportSession session = sessions.findById(composite).orElseThrow();
        session.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        sessions.save(session);
        discard(phone, composite, "MOBILE_ANDROID", "WRONG_FILE");

        JsonNode after = thisWeek().path("discardReasons");
        List<String> moved = java.util.stream.Stream.of("wrongFile", "tooManyMonths", "looksWrong", "later", "other",
                        "noReason")
                .filter(f -> after.path(f).asLong() != before.path(f).asLong())
                .map(f -> f + "=" + (after.path(f).asLong() - before.path(f).asLong()))
                .toList();
        assertThat(moved).containsExactlyInAnyOrder(
                "wrongFile=1", "tooManyMonths=1", "looksWrong=1", "later=1", "other=2", "noReason=1");
    }

    @Test
    void importingJustTheLatestMonth_isCountedAsSuch() throws Exception {
        JsonNode before = thisWeek();

        // 5 Jan to 5 Mar on Free: February (one row, 20 Feb) is the latest whole month.
        User user = customer("FREE");
        UUID sessionId = stage(user, THREE_MONTH_CSV);
        ResponseEntity<String> response = confirm(user, sessionId, java.time.YearMonth.of(2026, 2));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(change(before, thisWeek())).containsExactlyInAnyOrder(
                "staged=1", "imported=1", "freeLimitShown=1", "freeLimitThenImported=1", "latestMonthOnly=1");
    }

    @Test
    void internalAndAdminAccountsAreLeftOut() throws Exception {
        JsonNode before = thisWeek();

        stage(user("FREE", "USER", User.SCOPE_USER, true), ONE_MONTH_CSV);

        assertThat(change(before, thisWeek())).isEmpty();
    }

    @Test
    void thePeriod_theTotal_andWhenRecordingBegan() throws Exception {
        JsonNode twelve = report("");
        assertThat(twelve.path("weeks").asInt()).isEqualTo(12);
        assertThat(twelve.path("weekly")).hasSize(12);
        JsonNode weekly = twelve.path("weekly");
        for (int i = 0; i < weekly.size(); i++) {
            assertThat(LocalDate.parse(weekly.get(i).path("weekStart").asText()).getDayOfWeek())
                    .isEqualTo(java.time.DayOfWeek.MONDAY);
        }
        long staged = 0;
        for (JsonNode week : weekly) staged += week.path("staged").asLong();
        assertThat(twelve.path("total").path("staged").asLong()).isEqualTo(staged);
        assertThat(twelve.path("total").path("weekStart").isNull()).isTrue();

        // Flyway ran V327 when this run's database was created.
        LocalDate since = LocalDate.parse(twelve.path("recordedSince").asText());
        assertThat(since).isBeforeOrEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));

        assertThat(report("?weeks=4").path("weekly")).hasSize(4);
    }

    @Test
    void onlyThePlatformDiagnosticsPermissionReadsIt_andOnlyForFourOrTwelveWeeks() throws Exception {
        ResponseEntity<String> asCustomer = restTemplate.exchange(PATH, HttpMethod.GET,
                new HttpEntity<>(bearerFor(customer("FREE"))), String.class);
        assertThat(asCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> fiveWeeks = restTemplate.exchange(PATH + "?weeks=5", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(fiveWeeks.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
