package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import com.finora.util.BankRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plans.ts's "Extended financial history" Plus/Premium promise, enforced -- the second and third
 * FeatureEntitlement keys any endpoint actually checks (after ADVANCED_REPORTS): a Free-plan
 * statement's detected period may not exceed one month (ImportService, FreeStatementPeriod,
 * FeatureEntitlement.EXTENDED_HISTORY) and a Free-plan account may not create a 3rd account (AccountService,
 * FeatureEntitlement.UNLIMITED_ACCOUNTS -- covered separately in AccountServiceTest, a unit test,
 * since that gate needs no HTTP layer to exercise).
 *
 * <p>Bug fix: this class used to fabricate a never-staged {@code sessionId} throughout, on the
 * premise that {@code requireStatementPeriodWithinFreeLimit} ran in the controller, against
 * {@code request.statementPeriodStart()}/{@code End()}, before {@code ImportService.confirmSession}
 * /{@code confirmMultiSection} were ever reached. That premise made every one of these tests pass
 * against a gate that trusted the client's own echoed period -- exactly the gap it existed to
 * catch, undetected, because the tests only ever sent an HONEST echo. The gate has since moved
 * into {@code ImportService}, reading the period back from THIS session's own server-computed
 * {@code detectedAccountJson}/{@code sectionsJson} instead (see
 * {@code ImportService.requireStatementWithinFreeLimit}'s own doc comment) -- so these tests
 * now stage a REAL session via {@link ImportSessionService#createSession}/
 * {@link ImportSessionService#createMultiSection} (same shortcut {@code ImportControllerSessionsIT}
 * takes -- a session of a given kind sitting in the STAGED state, not real CSV/PDF parsing) and
 * confirm against its real {@code sessionId}. A period within the Free limit (or a Plus/Premium
 * caller) still falls through to the real service and fails for an UNRELATED reason (404, the
 * {@code existingAccountId} genuinely doesn't exist) -- proving the gate let the request past
 * rather than merely not having been reached yet.
 *
 * <p>Since 2026-10-06 the staged transactions' date range is judged as well as the printed period,
 * with two days of grace at each end (see {@code FreeStatementPeriod}). Sessions staged by
 * {@link #stageSingleAccountSession} carry a single row, so only the printed period decides those
 * tests; the {@code stageWithRows} tests below cover the transactions.
 *
 * <p>The {@code *EvenWhenTheRequestClaims...} tests are the regression coverage for the bug itself:
 * they stage a session whose REAL detected period is over the Free limit, then confirm with a
 * request that claims a shorter (or null) period -- proving the server's decision no longer moves
 * when the request body does.
 */
class ImportEntitlementGateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ImportSessionService importSessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("import-entitlement-gate-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Entitlement Gate IT Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, User user, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    // Same date/description/amount/type on both sides -- ConfirmedRowIntegrity.requireSameRows
    // compares exactly those four fields between the staged and confirmed row lists, and this
    // class's single-account confirm tests need to clear that check to ever reach the period gate.
    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 1, 15), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", "file", null, false, null, null);
    }

    private ConfirmedRow confirmedRow() {
        return new ConfirmedRow(LocalDate.of(2026, 1, 15), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", true, "file", null, false, null, null);
    }

    // Only start/end vary across these tests -- everything else about the detected account is
    // irrelevant to the gate being tested.
    private DetectedAccountInfo detectedAccountWithPeriod(LocalDate start, LocalDate end) {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                start, end, null, null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    /** Stages a real single-account session whose server-side detected period is exactly the one
     *  given -- the value {@code ImportService} now reads the gate's decision from. */
    private UUID stageSingleAccountSession(User user, LocalDate detectedStart, LocalDate detectedEnd) {
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv",
                "Date,Description,Amount\n2026-01-15,COFFEE,10.00\n".getBytes(StandardCharsets.UTF_8),
                List.of(stagedRow()), detectedAccountWithPeriod(detectedStart, detectedEnd));
        return session.getId();
    }

    /** Stages a real multi-account session whose sections carry the given detected periods, one
     *  section per period, same one-staged-row-per-section shape throughout. */
    private UUID stageMultiAccountSession(User user, List<LocalDate[]> detectedPeriods) {
        List<StagedAccountSection> sections = detectedPeriods.stream()
                .map(p -> new StagedAccountSection(detectedAccountWithPeriod(p[0], p[1]), List.of(stagedRow()), 1, 0, List.of()))
                .toList();
        ImportSession session = importSessionService.createMultiSection(user.getId(), "composite-statement.pdf",
                "composite pdf bytes".getBytes(StandardCharsets.UTF_8), sections);
        return session.getId();
    }

    // claimedStart/End are whatever the REQUEST says the period is -- deliberately independent of
    // the session's real detected period above, since the gate must not read these at all anymore.
    private ConfirmRequest confirmRequest(UUID sessionId, LocalDate claimedStart, LocalDate claimedEnd) {
        return new ConfirmRequest(sessionId, List.of(confirmedRow()), UUID.randomUUID(), null,
                null, null, null, claimedStart, claimedEnd, null, null);
    }

    private String errorCodeOf(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("errorCode").asText();
    }

    // -- Single-account confirm ------------------------------------------------------------------

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementSpanningMoreThanOneMonth() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 3, 31);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_allowsAWholeCalendarMonth() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        // A whole calendar month must be let through.
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 1, 31);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        // Falls through to the real service, which then 404s on the fabricated existingAccountId
        // -- proof the entitlement gate itself did not block this request.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAReversedDetectedPeriodOfTheSameRealLength() throws Exception {
        // A reversed (end before start) DETECTED period must reject exactly as its correctly-
        // ordered equivalent would -- a naive `end - start` day count goes negative for this input
        // and always slips under the limit, silently defeating the whole check. Real parsers can
        // genuinely produce this (a mislabelled statement footer, an OCR misread), so this has to
        // hold against the session's own data, not just against a request field.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRequest(sessionId, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAStatementEndingOneDayPastTheGrace() throws Exception {
        // With two days of grace at each end, 1 Jan is judged as 3 Jan, so the last end date allowed
        // is 5 Feb (3 Feb plus two days). Before the grace this boundary was 1 Feb / 2 Feb.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 2, 6);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_allowsAStatementEndingOnTheSameDateNextMonth() throws Exception {
        // 32 days counted inclusively, refused by the old flat 31-day count: an ordinary
        // one-month statement.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 1, 1), end = LocalDate.of(2026, 2, 1);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onFreePlan_allowsAPeriodOpeningOnAMonthsLastDay_toTheNextMonthsLastDay() throws Exception {
        // Opens on the closing day of a 30-day month and ends on the last day of the next month:
        // also 32 days counted inclusively, and also one month.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate start = LocalDate.of(2026, 6, 30), end = LocalDate.of(2026, 7, 31);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onPlusPlan_isNeverBlockedRegardlessOfPeriodLength() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        LocalDate start = LocalDate.of(2025, 1, 1), end = LocalDate.of(2026, 1, 1);
        UUID sessionId = stageSingleAccountSession(user, start, end);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, start, end));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_withNoPeriodDetected_andTransactionsWithinAMonth_isAllowed() throws Exception {
        // A missing period is not itself a reason to refuse: the transactions are judged instead.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRequest(sessionId, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -- Transactions judged as well as the printed period (owner's rules, 2026-10-06) -------------

    private StagedRow stagedRowOn(LocalDate date) {
        return new StagedRow(date, "Coffee " + date, BigDecimal.TEN, "EXPENSE", "Food", "file", null, false, null, null);
    }

    private ConfirmedRow confirmedRowOn(LocalDate date, boolean include) {
        return new ConfirmedRow(date, "Coffee " + date, BigDecimal.TEN, "EXPENSE", "Food", include, "file", null, false, null, null);
    }

    private UUID stageWithRows(User user, LocalDate detectedStart, LocalDate detectedEnd, List<LocalDate> rowDates) {
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv",
                "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8),
                rowDates.stream().map(this::stagedRowOn).toList(), detectedAccountWithPeriod(detectedStart, detectedEnd));
        return session.getId();
    }

    private ConfirmRequest confirmRows(UUID sessionId, List<LocalDate> rowDates, java.util.function.Predicate<LocalDate> included) {
        return new ConfirmRequest(sessionId, rowDates.stream().map(d -> confirmedRowOn(d, included.test(d))).toList(),
                UUID.randomUUID(), null, null, null, null, null, null, null, null);
    }

    private User freeUser() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        return user;
    }

    // -- slice: the printed period is not judged, only the transactions (owner's decision, 2026-10-09)

    private UUID stageWithBank(User user, String bankId, LocalDate printedStart, LocalDate printedEnd,
                               List<LocalDate> rowDates) {
        DetectedAccountInfo detected = new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"),
                new BigDecimal("900"), printedStart, printedEnd, null, null, null, null, null, null, null,
                AccountDto.BankDto.from(BankRegistry.get(bankId)),
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
        ImportSession session = importSessionService.createSession(user.getId(), "statement.pdf",
                "pdf bytes".getBytes(StandardCharsets.UTF_8),
                rowDates.stream().map(this::stagedRowOn).toList(), detected);
        return session.getId();
    }

    @Test
    void confirm_onFreePlan_aSliceStatementPrintingTheWholeYear_isAllowedWhenItsTransactionsFitOneMonth() throws Exception {
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 2));
        UUID sessionId = stageWithBank(user, "SLICE", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        // Past the gate: the random account id in the request is what is not found.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void confirm_onFreePlan_aSliceStatementPrintingTheWholeYear_isRefusedOnItsTransactionsWhenTheySpanMore() throws Exception {
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 6));
        UUID sessionId = stageWithBank(user, "SLICE", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(
                "Free plan statements can cover at most one month. Its transactions run from 31 Jul 2026 to 6 Oct 2026. "
                        + "Upgrade to Plus to import longer statement periods.");
    }

    @Test
    void confirm_onFreePlan_anotherBankPrintingTheWholeYear_isStillRefusedOnThePrintedPeriod() throws Exception {
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 20));
        UUID sessionId = stageWithBank(user, "HDFC", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(
                "Free plan statements can cover at most one month. It covers 1 Apr 2026 to 31 Mar 2027. "
                        + "Upgrade to Plus to import longer statement periods.");
    }

    @Test
    void csvConfirm_onFreePlan_withNoPeriod_rejectsTransactionsSpanningThreeMonths_andNamesTheRange() throws Exception {
        // Every CSV and Excel export: no printed period, so before this the limit never ran at all.
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 5));
        UUID sessionId = stageWithRows(user, null, null, dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(body.get("message").asText()).isEqualTo("Free plan statements can cover at most one month. "
                + "Its transactions run from 5 Jan 2026 to 5 Mar 2026. Upgrade to Plus to import longer statement periods.");
    }

    @Test
    void csvConfirm_onFreePlan_namesThePrintedPeriodWhenThatIsWhatIsTooLong() throws Exception {
        User user = freeUser();
        UUID sessionId = stageSingleAccountSession(user, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRequest(sessionId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));

        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(
                "Free plan statements can cover at most one month. It covers 1 Jan 2026 to 31 Mar 2026. "
                        + "Upgrade to Plus to import longer statement periods.");
    }

    @Test
    void csvConfirm_onFreePlan_rejectsAnEditedPrintedPeriod_whenTheTransactionsStillCoverMore_andLogsIt() throws Exception {
        // The printed period says one month; the rows still run for two. The refusal is logged
        // (spans only, no dates): it is how a wrongly refused real statement gets noticed.
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5));
        UUID sessionId = stageWithRows(user, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5), dates);
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.finora.imports.ImportService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<String> response;
        try {
            response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(body.get("details").get("basis").asText()).isEqualTo("TRANSACTIONS");
        assertThat(body.get("details").get("coveredFrom").asText()).isEqualTo("2026-01-05");
        assertThat(body.get("details").get("coveredTo").asText()).isEqualTo("2026-03-05");
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().equals(
                "Free statement limit: session " + sessionId + " prints a 32-day period, but its transactions span 60 days -- refused"));
    }

    @Test
    void csvConfirm_onFreePlan_aStatementWithNoPeriod_isRefusedWithoutTheEditedPeriodLog() throws Exception {
        // A CSV printing no period being too long is the ordinary case, not a sign of anything.
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5));
        UUID sessionId = stageWithRows(user, null, null, dates);
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.finora.imports.ImportService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        ResponseEntity<String> response;
        try {
            response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().startsWith("Free statement limit"));
    }

    @Test
    void csvConfirm_onFreePlan_untickedRowsStillCount() throws Exception {
        // Unticking the two later months during review does not make a three-month file one month.
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 5));
        UUID sessionId = stageWithRows(user, null, null, dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRows(sessionId, dates, d -> d.getMonthValue() == 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void csvConfirm_onFreePlan_allowsTwoDaysOfGraceAtEachEnd() throws Exception {
        // 5 Jan to 5 Feb is one month, so a statement printed and dated 3 Jan to 7 Feb fits.
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 7));
        UUID sessionId = stageWithRows(user, LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 7), dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void csvConfirm_onFreePlan_rejectsOneDayPastTheGrace() throws Exception {
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 2, 8));
        UUID sessionId = stageWithRows(user, null, null, dates);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void csvConfirm_refusedOnFree_thenUpgraded_theSameUploadGoesThrough() throws Exception {
        // The refusal must not leave the session claimed: after upgrading, the person confirms the
        // upload they already reviewed, and does not get "already confirmed".
        User user = freeUser();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5));
        UUID sessionId = stageWithRows(user, null, null, dates);

        ResponseEntity<String> refused = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        ResponseEntity<String> retried = post("/api/v1/import/csv/confirm", user, confirmRows(sessionId, dates, d -> true));

        // Past the gate and past the claim: the only failure left is the fabricated account id.
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The regression test for the actual bug: the request claims a period well within the Free
     *  limit (indeed, none at all) for a session whose real, server-detected period is nearly
     *  three months. Before the fix, the gate read {@code request.statementPeriodStart()}/{@code
     *  End()} directly -- this exact request would have sailed through, no race or malice-
     *  detection needed, just an edited or honestly-stale request body. */
    @Test
    void csvConfirm_onFreePlan_isRejectedEvenWhenTheRequestClaimsAShorterPeriodThanWhatWasActuallyStaged() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        UUID sessionId = stageSingleAccountSession(user, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user,
                confirmRequest(sessionId, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    // -- Multi-account confirm ---------------------------------------------------------------------

    private MultiAccountConfirmRequest multiRequest(UUID sessionId, LocalDate... claimedPeriodPairs) {
        List<SectionConfirm> sections = new java.util.ArrayList<>();
        for (int i = 0; i < claimedPeriodPairs.length; i += 2) {
            sections.add(new SectionConfirm(List.of(confirmedRow()), UUID.randomUUID(), null,
                    null, null, claimedPeriodPairs[i], claimedPeriodPairs[i + 1], null, null));
        }
        return new MultiAccountConfirmRequest(sessionId, sections);
    }

    @Test
    void pdfConfirmMulti_onFreePlan_rejectsIfAnySectionExceedsOneMonth() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] withinLimit = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] tooLong = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)};
        UUID sessionId = stageMultiAccountSession(user, List.of(withinLimit, tooLong));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, withinLimit[0], withinLimit[1], tooLong[0], tooLong[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }

    @Test
    void pdfConfirmMulti_onFreePlan_allowsEverySectionWithinLimit() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] section1 = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] section2 = {LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)};
        UUID sessionId = stageMultiAccountSession(user, List.of(section1, section2));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, section1[0], section1[1], section2[0], section2[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pdfConfirmMulti_onFreePlan_judgesASectionWithNoPeriodByItsTransactions() throws Exception {
        User user = freeUser();
        StagedAccountSection fits = new StagedAccountSection(
                detectedAccountWithPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), List.of(stagedRow()), 1, 0, List.of());
        StagedAccountSection tooLong = new StagedAccountSection(detectedAccountWithPeriod(null, null),
                List.of(stagedRowOn(LocalDate.of(2026, 1, 5)), stagedRowOn(LocalDate.of(2026, 4, 5))), 2, 0, List.of());
        UUID sessionId = importSessionService.createMultiSection(user.getId(), "composite-statement.pdf",
                "composite pdf bytes".getBytes(StandardCharsets.UTF_8), List.of(fits, tooLong)).getId();
        MultiAccountConfirmRequest request = new MultiAccountConfirmRequest(sessionId, List.of(
                new SectionConfirm(List.of(confirmedRow()), UUID.randomUUID(), null, null, null, null, null, null, null),
                new SectionConfirm(List.of(confirmedRowOn(LocalDate.of(2026, 1, 5), true), confirmedRowOn(LocalDate.of(2026, 4, 5), true)),
                        UUID.randomUUID(), null, null, null, null, null, null, null)));

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
        // Says it is one account of the statement: the dates alone would not tell which part.
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(
                "Free plan statements can cover at most one month. One account's transactions run from "
                        + "5 Jan 2026 to 5 Apr 2026. Upgrade to Plus to import longer statement periods.");
    }

    /** Multi-account equivalent of {@code csvConfirm_..._isRejectedEvenWhenTheRequestClaims...}
     *  above: both sections' REQUEST-claimed periods are within the Free limit, but the second
     *  section's real, staged detection is not -- and that is what must decide the outcome. */
    @Test
    void pdfConfirmMulti_onFreePlan_isRejectedEvenWhenTheRequestClaimsShorterPeriodsThanWhatWasActuallyStaged() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        LocalDate[] detectedWithinLimit = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)};
        LocalDate[] detectedTooLong = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)};
        UUID sessionId = stageMultiAccountSession(user, List.of(detectedWithinLimit, detectedTooLong));
        LocalDate[] claimedShort = {LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)};

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user,
                multiRequest(sessionId, claimedShort[0], claimedShort[1], claimedShort[0], claimedShort[1]));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCodeOf(response)).isEqualTo("ENTITLEMENT_003");
    }
}
