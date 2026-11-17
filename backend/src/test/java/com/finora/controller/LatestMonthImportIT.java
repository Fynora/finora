package com.finora.controller;

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
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Import only the latest month" (Sid, 2026-11-17): a Free customer whose statement covers more
 * than one month may import its last full calendar month on its own, with that month's own
 * period, opening and closing balance -- driven through the real stage and confirm endpoints, and
 * checked at value level (the statement's figures and the account balance), not by counts alone.
 */
@TestPropertySource(properties = {
        "app.rate-limit.import-stage.max=10000",
        "app.import.queue.enabled=false"
})
class LatestMonthImportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ImportSessionRepository sessions;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportRepository statementImports;
    @Autowired private StatementAnalysisSessionRepository analyses;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 20 Aug to 10 Nov, opening 10,000.00: October (2 Oct, 18 Oct) is the last full month. */
    private static final String AUG_TO_NOV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            20/08/2026,UPI-ZORBIC TEAHOUSE-0000000001,100.00,,9900.00
            15/09/2026,NEFT-QELTHAR WIDGETS-0000000002,,500.00,10400.00
            02/10/2026,UPI-ZORBIC TEAHOUSE-0000000003,200.00,,10200.00
            18/10/2026,NEFT-QELTHAR WIDGETS-0000000004,,1000.00,11200.00
            10/11/2026,UPI-ZORBIC TEAHOUSE-0000000005,300.00,,10900.00
            """;

    private static final String AUG_TO_NOV_NO_BALANCE = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.
            20/08/2026,UPI-ZORBIC TEAHOUSE-0000000001,100.00,
            15/09/2026,NEFT-QELTHAR WIDGETS-0000000002,,500.00
            02/10/2026,UPI-ZORBIC TEAHOUSE-0000000003,200.00,
            18/10/2026,NEFT-QELTHAR WIDGETS-0000000004,,1000.00
            10/11/2026,UPI-ZORBIC TEAHOUSE-0000000005,300.00,
            """;

    /** 13 Jan to 20 Feb: over a month, yet no calendar month wholly inside it. */
    private static final String NO_FULL_MONTH = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            13/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,100.00,,9900.00
            20/02/2026,UPI-ZORBIC TEAHOUSE-0000000002,100.00,,9800.00
            """;

    private static final Set<LocalDate> OCTOBER = Set.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 18));

    private User customer(String plan) {
        User user = new User();
        user.setEmail("latest-month-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Latest Month IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        if (!"FREE".equals(plan)) subscriptionService.changePlan(user.getId(), plan, "test-plan", user.getId());
        return user;
    }

    private HttpHeaders bearer(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private JsonNode stage(User user, String csv) throws Exception {
        HttpHeaders headers = bearer(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    /** The apps' payload: every staged row, kept only on {@code keep} dates, into a new savings account. */
    private ResponseEntity<String> confirm(User user, UUID sessionId, Set<LocalDate> keep, boolean latestMonthOnly) {
        ImportSession session = sessions.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row ->
                new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(), "Other",
                        keep.contains(row.date()), row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                        row.referenceNumber(), row.balanceAfter(), false)).toList();
        // The client echoes the WHOLE statement's figures, as it does today; the server replaces them.
        NewAccountRequest account = new NewAccountRequest("Latest month IT account", "SAVINGS",
                detected == null ? null : detected.openingBalance(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(sessionId, rows, null, account,
                detected == null ? null : detected.openingBalance(), detected == null ? null : detected.closingBalance(),
                null, detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(), null, null, null, null);
        HttpHeaders headers = bearer(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/import/csv/confirm" + (latestMonthOnly ? "?latestMonthOnly=true" : ""),
                HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }

    @Test
    void theNotice_offersTheLastFullMonth_andHowManyTransactionsAreInIt() throws Exception {
        JsonNode notice = stage(customer("FREE"), AUG_TO_NOV).get("freePlanLimit");

        assertThat(notice.get("latestMonthFrom").asText()).isEqualTo("2026-10-01");
        assertThat(notice.get("latestMonthTo").asText()).isEqualTo("2026-10-31");
        assertThat(notice.get("latestMonthRows").asInt()).isEqualTo(2);
        assertThat(notice.get("message").asText()).contains("You can import just its transactions from October 2026");
    }

    @Test
    void importingTheLatestMonth_importsItsRows_withThatMonthsOwnPeriodAndBalances() throws Exception {
        User user = customer("FREE");
        UUID sessionId = UUID.fromString(stage(user, AUG_TO_NOV).get("sessionId").asText());

        ResponseEntity<String> response = confirm(user, sessionId, OCTOBER, true);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("imported").asInt()).isEqualTo(2);
        assertThat(data.get("statementPeriodStart").asText()).isEqualTo("2026-10-01");
        assertThat(data.get("statementPeriodEnd").asText()).isEqualTo("2026-10-31");
        assertThat(data.get("statementOpeningBalance").decimalValue()).isEqualByComparingTo("10400.00");
        assertThat(data.get("statementClosingBalance").decimalValue()).isEqualByComparingTo("11200.00");
        // The account ends at October's closing balance, not the statement's (10,900.00) or a sum.
        assertThat(data.get("account").get("balance").decimalValue()).isEqualByComparingTo("11200.00");

        var stored = statementImports.findById(UUID.fromString(data.get("statementImportId").asText())).orElseThrow();
        assertThat(stored.getOpeningBalance()).isEqualByComparingTo("10400.00");
        assertThat(stored.getClosingBalance()).isEqualByComparingTo("11200.00");
        assertThat(stored.getStatementPeriodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(stored.getStatementPeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void keepingARowFromAnotherMonth_isRefused_andNothingIsImported() throws Exception {
        User user = customer("FREE");
        UUID sessionId = UUID.fromString(stage(user, AUG_TO_NOV).get("sessionId").asText());
        Set<LocalDate> octoberAndSeptember = Set.of(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 18));

        ResponseEntity<String> response = confirm(user, sessionId, octoberAndSeptember, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(statementImports.findAll().stream().noneMatch(s -> user.getId().equals(s.getUserId()))).isTrue();
        assertThat(analyses.findByImportSessionIdOrderByCreatedAtDesc(sessionId).get(0).getFreeLimitRefusedAt()).isNotNull();
        // Still staged: the customer can untick it and try again.
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(ImportSession.STATUS_STAGED);
    }

    @Test
    void withoutAskingForTheLatestMonth_theWholeStatementIsRefusedAsBefore() throws Exception {
        User user = customer("FREE");
        UUID sessionId = UUID.fromString(stage(user, AUG_TO_NOV).get("sessionId").asText());

        assertThat(confirm(user, sessionId, OCTOBER, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aStatementWithoutRunningBalances_importsTheMonth_andClaimsNoBalance() throws Exception {
        User user = customer("FREE");
        UUID sessionId = UUID.fromString(stage(user, AUG_TO_NOV_NO_BALANCE).get("sessionId").asText());

        ResponseEntity<String> response = confirm(user, sessionId, OCTOBER, true);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("imported").asInt()).isEqualTo(2);
        assertThat(data.get("statementOpeningBalance").isNull()).isTrue();
        assertThat(data.get("statementClosingBalance").isNull()).isTrue();
        assertThat(data.get("statementPeriodStart").asText()).isEqualTo("2026-10-01");
    }

    @Test
    void aStatementWithNoFullMonth_offersNone_andAskingForOneIsRefused() throws Exception {
        User user = customer("FREE");
        JsonNode staged = stage(user, NO_FULL_MONTH);
        assertThat(staged.get("freePlanLimit").get("latestMonthFrom").isNull()).isTrue();
        assertThat(staged.get("freePlanLimit").get("message").asText()).contains("Upgrade to Plus");

        ResponseEntity<String> response = confirm(user, UUID.fromString(staged.get("sessionId").asText()),
                Set.of(LocalDate.of(2026, 2, 20)), true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aStatementWithinTheLimit_hasNoNotice_soThereIsNothingToOffer() throws Exception {
        JsonNode staged = stage(customer("FREE"), """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                02/10/2026,UPI-ZORBIC TEAHOUSE-0000000003,200.00,,10200.00
                18/10/2026,NEFT-QELTHAR WIDGETS-0000000004,,1000.00,11200.00
                """);
        assertThat(staged.get("freePlanLimit") == null || staged.get("freePlanLimit").isNull()).isTrue();
    }
}
