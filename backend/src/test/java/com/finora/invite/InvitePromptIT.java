package com.finora.invite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.activation.ReportingDay;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * The invite-a-friend card after an import (V315, InvitePromptService): when it is shown, what each
 * tap records, and that web and mobile read the same server-side record. "Today" is pinned through
 * the spied {@link ReportingDay}. Statements are synthetic.
 */
class InvitePromptIT extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoSpyBean private ReportingDay reportingDay;

    /** Nothing in it trips the trust check; the 15th settles the date order (CsvDateOrder). */
    private static final String STATEMENT = """
            Date,Description,Amount,Balance
            01/01/2026,Opening balance,,1000.00
            15/01/2026,Coffee shop,-150.00,850.00
            """;

    /** A different month, so its rows are not duplicates of {@link #STATEMENT}'s. */
    private static final String NEXT_STATEMENT = """
            Date,Description,Amount,Balance
            01/02/2026,Opening balance,,850.00
            16/02/2026,Book shop,-200.00,650.00
            """;

    @BeforeEach
    void pinToday() {
        pinTo(TODAY);
    }

    private void pinTo(LocalDate today) {
        doReturn(today).when(reportingDay).today();
        doReturn(NOW).when(reportingDay).now();
    }

    // ---- when it shows, end to end -----------------------------------------------------------

    /** The id the confirm response hands the apps is the id the check accepts: the whole seam. */
    @Test
    void shownAfterAFirstRealImportUsingTheIdTheConfirmReturned() throws Exception {
        User user = user();
        UUID statementImportId = stageAndConfirm(user, STATEMENT);

        JsonNode check = check(user, statementImportId);
        assertThat(check.get("show").asBoolean()).isTrue();
        assertThat(check.get("shownRecently").asBoolean()).isFalse();
    }

    /** Existing users are included (Sid, 2026-11-12): never shown, so their next import shows it. */
    @Test
    void someoneWhoImportedBeforeSeesItOnTheirNextImport() throws Exception {
        User user = user();
        UUID first = stageAndConfirm(user, STATEMENT);
        UUID second = stageAndConfirm(user, NEXT_STATEMENT);
        assertThat(first).isNotEqualTo(second);

        assertThat(check(user, second).get("show").asBoolean()).isTrue();
    }

    @Test
    void notShownForTheImportAfterTheOneThatShowedIt() throws Exception {
        User user = user();
        stageAndConfirm(user, STATEMENT);
        event(user, "SHOWN");
        UUID second = stageAndConfirm(user, NEXT_STATEMENT);

        assertThat(check(user, second).get("show").asBoolean()).isFalse();
    }

    @Test
    void notShownForAnotherUsersImport() throws Exception {
        User owner = user();
        UUID ownersImport = stageAndConfirm(owner, STATEMENT);

        assertThat(check(user(), ownersImport).get("show").asBoolean()).isFalse();
    }

    // ---- the rules, on stored rows ------------------------------------------------------------

    @Test
    void anImportThatSavedNothingIsNotASuccess() {
        User user = user();
        UUID empty = statementImport(user, 0, false);

        assertThat(check(user, empty).get("show").asBoolean()).isFalse();
    }

    @Test
    void anEarlierDeletedImportDoesNotStopIt() {
        User user = user();
        statementImport(user, 3, true);
        UUID now = statementImport(user, 3, false);

        assertThat(check(user, now).get("show").asBoolean()).isTrue();
    }

    @Test
    void anEarlierImportThatSavedNothingDoesNotStopTheFirst() {
        User user = user();
        statementImport(user, 0, false);
        UUID now = statementImport(user, 4, false);

        assertThat(check(user, now).get("show").asBoolean()).isTrue();
    }

    /** One multi-account statement confirms several imports at once; one answer covers them all. */
    @Test
    void aMultiAccountConfirmIsAskedAboutAsOne() {
        User user = user();
        UUID a = statementImport(user, 2, false);
        UUID b = statementImport(user, 5, false);
        UUID empty = statementImport(user, 0, false);

        assertThat(check(user, a, b).get("show").asBoolean()).isTrue();
        assertThat(check(user, empty).get("show").asBoolean()).as("a section that saved nothing alone").isFalse();
    }

    @Test
    void noIdsMeansNo() {
        User user = user();
        statementImport(user, 2, false);

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/invite-prompt", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"show\":false");
    }

    @Test
    void tooManyIdsIsRefused() {
        User user = user();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i <= InvitePromptService.MAX_IDS; i++) {
            if (i > 0) ids.append(',');
            ids.append(UUID.randomUUID());
        }
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/invite-prompt?statementImportIds=" + ids,
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownEventIsRefused() {
        assertThat(event(user(), "OPENED").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void signedOutIsRefused() {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/invite-prompt?statementImportIds="
                + UUID.randomUUID(), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        ResponseEntity<String> posted = restTemplate.exchange("/api/v1/invite-prompt/events", HttpMethod.POST,
                new HttpEntity<>("{\"event\":\"SHOWN\"}", jsonHeaders(null)), String.class);
        assertThat(posted.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ---- what the taps record -----------------------------------------------------------------

    @Test
    void shownOnceMeansNotShownAgainSoonAndShownRecently() {
        User user = user();
        UUID first = statementImport(user, 3, false);
        assertThat(event(user, "SHOWN").getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode check = check(user, first);
        assertThat(check.get("show").asBoolean()).as("the same import, checked again (a reload)").isFalse();
        assertThat(check.get("shownRecently").asBoolean()).isTrue();
        assertThat(timesShown(user)).isEqualTo(1);
    }

    @Test
    void aSameDayRepeatOfShownIsOneShowingAndCountedOnce() {
        User user = user();
        double before = counter("shown", "web");
        event(user, "SHOWN");
        event(user, "SHOWN");

        assertThat(timesShown(user)).isEqualTo(1);
        assertThat(counter("shown", "web") - before).isEqualTo(1.0);
    }

    @Test
    void shownRecentlyLastsSevenDays() {
        User user = user();
        UUID first = statementImport(user, 3, false);
        pinTo(TODAY.minusDays(InvitePromptService.RECENT_DAYS - 1));
        event(user, "SHOWN");
        pinTo(TODAY);
        assertThat(check(user, first).get("shownRecently").asBoolean()).isTrue();

        User other = user();
        UUID otherFirst = statementImport(other, 3, false);
        pinTo(TODAY.minusDays(InvitePromptService.RECENT_DAYS));
        event(other, "SHOWN");
        pinTo(TODAY);
        assertThat(check(other, otherFirst).get("shownRecently").asBoolean()).isFalse();
    }

    @Test
    void notNowComesBackOnceAfter180DaysThenNever() {
        User user = user();
        statementImport(user, 3, false);
        pinTo(TODAY.minusDays(InvitePromptService.QUIET_DAYS_AFTER_NOT_NOW));
        event(user, "SHOWN");
        event(user, "DISMISSED");

        pinTo(TODAY.minusDays(1));
        UUID tooSoon = statementImport(user, 3, false);
        assertThat(check(user, tooSoon).get("show").asBoolean()).as("179 days later").isFalse();

        pinTo(TODAY);
        UUID later = statementImport(user, 3, false);
        assertThat(check(user, later).get("show").asBoolean()).as("180 days later").isTrue();
        event(user, "SHOWN");
        event(user, "DISMISSED");
        assertThat(timesShown(user)).isEqualTo(2);

        pinTo(TODAY.plusDays(InvitePromptService.QUIET_DAYS_AFTER_NOT_NOW * 2L));
        UUID muchLater = statementImport(user, 3, false);
        assertThat(check(user, muchLater).get("show").asBoolean()).as("after the second showing").isFalse();
    }

    @Test
    void shareTappedMeansNeverAgain() {
        User user = user();
        statementImport(user, 3, false);
        pinTo(TODAY.minusDays(400));
        event(user, "SHOWN");
        event(user, "SHARE_TAPPED");
        pinTo(TODAY.minusDays(300));
        event(user, "SHARE_TAPPED");

        pinTo(TODAY);
        UUID later = statementImport(user, 3, false);
        assertThat(check(user, later).get("show").asBoolean()).isFalse();
        assertThat(((Date) row(user).get("shared_on")).toLocalDate()).as("the first tap's day is kept")
                .isEqualTo(TODAY.minusDays(400));
    }

    /** A Not now on the phone is the same record the web reads: the card does not come back there. */
    @Test
    void webAndMobileShareOneRecordAndMetricsCarryThePlatform() {
        User user = user();
        UUID first = statementImport(user, 3, false);
        double mobileShown = counter("shown", "mobile_android");
        double mobileDismissed = counter("dismissed", "mobile_android");

        HttpHeaders android = jsonHeaders(user);
        android.set("X-Client-Platform", "MOBILE_ANDROID");
        restTemplate.exchange("/api/v1/invite-prompt/events", HttpMethod.POST,
                new HttpEntity<>("{\"event\":\"SHOWN\"}", android), String.class);
        restTemplate.exchange("/api/v1/invite-prompt/events", HttpMethod.POST,
                new HttpEntity<>("{\"event\":\"DISMISSED\"}", android), String.class);

        assertThat(check(user, first).get("show").asBoolean()).as("the web, after the phone said Not now").isFalse();
        assertThat(counter("shown", "mobile_android") - mobileShown).isEqualTo(1.0);
        assertThat(counter("dismissed", "mobile_android") - mobileDismissed).isEqualTo(1.0);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private double counter(String event, String platform) {
        var counter = meterRegistry.find("finora.invite_prompt.events").tag("event", event).tag("platform", platform)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int timesShown(User user) {
        return ((Number) row(user).get("times_shown")).intValue();
    }

    private Map<String, Object> row(User user) {
        return jdbc.queryForMap("SELECT * FROM invite_prompts WHERE user_id = ?", user.getId());
    }

    private JsonNode check(User user, UUID... ids) {
        StringBuilder query = new StringBuilder("/api/v1/invite-prompt?statementImportIds=");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) query.append(',');
            query.append(ids[i]);
        }
        ResponseEntity<String> response = restTemplate.exchange(query.toString(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> event(User user, String event) {
        return restTemplate.exchange("/api/v1/invite-prompt/events", HttpMethod.POST,
                new HttpEntity<>("{\"event\":\"" + event + "\"}", jsonHeaders(user)), String.class);
    }

    private HttpHeaders jsonHeaders(User user) {
        HttpHeaders headers = user == null ? new HttpHeaders() : bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private User user() {
        User user = new User();
        user.setEmail("invite-prompt-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Invite Prompt IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    /** A stored statement import with {@code transactionsImported} saved rows, optionally deleted. */
    private UUID statementImport(User user, int transactionsImported, boolean deleted) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Invite IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        return jdbc.queryForObject("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, "
                        + "transactions_imported, deleted_at) VALUES (?, ?, ?, ?, ?, " + (deleted ? "now()" : "NULL")
                        + ") RETURNING id",
                UUID.class, user.getId(), accountId, "statement.csv", STATEMENT.getBytes(StandardCharsets.UTF_8),
                transactionsImported);
    }

    /** Uploads and confirms {@code csv} the way the apps do. Returns the confirm's statementImportId. */
    private UUID stageAndConfirm(User user, String csv) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> staged = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID sessionId = UUID.fromString(mapper.readTree(staged.getBody()).get("data").get("sessionId").asText());

        ResponseEntity<String> confirmed = restTemplate.exchange("/api/v1/import/csv/confirm", HttpMethod.POST,
                new HttpEntity<>(confirmRequest(sessionId), jsonHeaders(user)), String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(confirmed.getBody()).get("data");
        assertThat(data.get("imported").asInt()).isPositive();
        return UUID.fromString(data.get("statementImportId").asText());
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
        NewAccountRequest account = new NewAccountRequest("Invite IT account " + UUID.randomUUID(), "SAVINGS", null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
    }
}
