package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.FreePlanLimitNotice;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Free one-month limit, told to the person as soon as a statement is staged rather than only
 * when they press Import ({@code FreePlanLimitNotice}). Every response the review screen opens from
 * carries it: CSV upload, PDF upload (single and composite), and the session reload a queued upload
 * and "Continue Import" both use. The confirm-time refusal ({@code ImportEntitlementGateIT}) stays
 * the guard; these pin that the warning says the same thing, and only when that refusal would come.
 */
@TestPropertySource(properties = {
        "app.rate-limit.import-stage.max=10000"
})
class FreePlanLimitNoticeIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportService importService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** No printed period, like every bank CSV export; transactions from 5 Jan to 5 Mar. */
    private static final String THREE_MONTH_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            05/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            10/02/2026,UPI-ZORBIC TEAHOUSE-0000000002,80.00,,24800.00
            05/03/2026,UPI-ZORBIC TEAHOUSE-0000000003,100.00,,24700.00
            """;

    private static final String ONE_MONTH_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            05/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            20/01/2026,UPI-ZORBIC TEAHOUSE-0000000002,80.00,,24800.00
            05/02/2026,UPI-ZORBIC TEAHOUSE-0000000003,100.00,,24700.00
            """;

    private static final String REFUSAL = "Free plan statements can cover at most one month. "
            + "Its transactions run from 5 Jan 2026 to 5 Mar 2026. Upgrade to Plus to import longer statement periods.";

    private User user(String plan) {
        User user = new User();
        user.setEmail("free-limit-notice-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Free Limit Notice IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        if (!"FREE".equals(plan)) subscriptionService.changePlan(user.getId(), plan, "test-plan", user.getId());
        return user;
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private JsonNode stageCsv(User user, String csv) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private JsonNode getSession(User user, String sessionId) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/sessions/" + sessionId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    @Test
    void aFreeUsersThreeMonthCsv_isWarnedOnUpload_withTheRefusalsOwnWords() throws Exception {
        JsonNode data = stageCsv(user("FREE"), THREE_MONTH_CSV);

        JsonNode notice = data.get("freePlanLimit");
        assertThat(notice.get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(notice.get("message").asText()).isEqualTo(REFUSAL);
        assertThat(notice.get("coveredFrom").asText()).isEqualTo("2026-01-05");
        assertThat(notice.get("coveredTo").asText()).isEqualTo("2026-03-05");
        assertThat(notice.get("basis").asText()).isEqualTo("TRANSACTIONS");
    }

    @Test
    void aFreeUsersOneMonthCsv_carriesNoWarning() throws Exception {
        assertThat(absent(stageCsv(user("FREE"), ONE_MONTH_CSV).get("freePlanLimit"))).isTrue();
    }

    @Test
    void aPlusUsersThreeMonthCsv_carriesNoWarning() throws Exception {
        assertThat(absent(stageCsv(user("PLUS"), THREE_MONTH_CSV).get("freePlanLimit"))).isTrue();
    }

    @Test
    void reloadingTheSession_warnsOnFree_andNotOnceUpgraded() throws Exception {
        // A queued upload and "Continue Import" both open the review from GET /sessions/{id}. The
        // notice is judged on the plan at that moment, never stored with the session.
        User user = user("FREE");
        String sessionId = stageCsv(user, THREE_MONTH_CSV).get("sessionId").asText();

        assertThat(getSession(user, sessionId).get("freePlanLimit").get("message").asText()).isEqualTo(REFUSAL);

        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        assertThat(absent(getSession(user, sessionId).get("freePlanLimit"))).isTrue();
    }

    // -- PDF upload ---------------------------------------------------------------------------------

    private JsonNode stagePdf(User user, byte[] pdf) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "statement.pdf"; }
        });
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/pdf/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static byte[] threeMonthPdf() throws Exception {
        return com.finora.imports.pdf.fixtures.PdfFixtureBuilder.render(
                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition("free-limit-notice-001", List.of(
                        new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity(
                                "savings-primary", "SAVINGS",
                                com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence.DETECTED, "••••4321",
                                com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions.FALSE, List.of(
                                        new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                                LocalDate.of(2026, 1, 5), "Coffee shop", new BigDecimal("100.00"), false),
                                        new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                                LocalDate.of(2026, 2, 10), "Book shop", new BigDecimal("80.00"), false),
                                        new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                                LocalDate.of(2026, 3, 5), "Tea stall", new BigDecimal("60.00"), false)))),
                        List.of()));
    }

    @Test
    void aFreeUsersThreeMonthPdf_isWarnedOnUpload() throws Exception {
        JsonNode data = stagePdf(user("FREE"), threeMonthPdf());

        assertThat(data.get("multiAccount").asBoolean()).isFalse();
        JsonNode notice = data.get("freePlanLimit");
        assertThat(notice.get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(notice.get("coveredFrom").asText()).isEqualTo("2026-01-05");
        assertThat(notice.get("coveredTo").asText()).isEqualTo("2026-03-05");
    }

    @Test
    void aFreeUsersCompositePdfThatFits_carriesNoWarning() throws Exception {
        JsonNode data = stagePdf(user("FREE"),
                com.finora.imports.pdf.fixtures.PdfFixtureBuilder.buildMultiSectionCompositeStatementSample());

        assertThat(data.get("multiAccount").asBoolean()).isTrue();
        assertThat(absent(data.get("freePlanLimit"))).isTrue();
    }

    // -- Composite statements: the first section that is too long, in confirm-multi's words ----------

    private static DetectedAccountInfo detected(LocalDate start, LocalDate end) {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                start, end, null, null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    private static StagedRow row(LocalDate date) {
        return new StagedRow(date, "Coffee " + date, BigDecimal.TEN, "EXPENSE", "Food", "file", null, false, null, null);
    }

    private static ConfirmedRow confirmed(LocalDate date) {
        return new ConfirmedRow(date, "Coffee " + date, BigDecimal.TEN, "EXPENSE", "Food", true, "file", null, false, null, null);
    }

    @Test
    void aCompositeStatement_isWarnedOnItsLongSection_inTheSameWordsConfirmMultiRefusesWith() throws Exception {
        User user = user("FREE");
        LocalDate jan5 = LocalDate.of(2026, 1, 5), apr5 = LocalDate.of(2026, 4, 5);
        List<StagedAccountSection> sections = List.of(
                new StagedAccountSection(detected(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                        List.of(row(LocalDate.of(2026, 1, 15))), 1, 0, List.of()),
                new StagedAccountSection(detected(null, null), List.of(row(jan5), row(apr5)), 2, 0, List.of()));

        FreePlanLimitNotice notice = importService.freePlanLimitNotice(user.getId(), null, sections);

        assertThat(notice).isNotNull();
        assertThat(notice.message()).isEqualTo("Free plan statements can cover at most one month. "
                + "One account's transactions run from 5 Jan 2026 to 5 Apr 2026. "
                + "Upgrade to Plus to import longer statement periods.");

        UUID sessionId = importSessionService.createMultiSection(user.getId(), "composite-statement.pdf",
                "composite pdf bytes".getBytes(StandardCharsets.UTF_8), sections).getId();
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        MultiAccountConfirmRequest confirm = new MultiAccountConfirmRequest(sessionId, List.of(
                new SectionConfirm(List.of(confirmed(LocalDate.of(2026, 1, 15))), UUID.randomUUID(), null,
                        null, null, null, null, null, null),
                new SectionConfirm(List.of(confirmed(jan5), confirmed(apr5)), UUID.randomUUID(), null,
                        null, null, null, null, null, null)));
        ResponseEntity<String> refused = restTemplate.exchange("/api/v1/import/pdf/confirm-multi", HttpMethod.POST,
                new HttpEntity<>(confirm, headers), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(refused.getBody()).get("message").asText()).isEqualTo(notice.message());
    }

    @Test
    void aCompositeStatementThatFits_carriesNoWarning() {
        User user = user("FREE");
        List<StagedAccountSection> sections = List.of(
                new StagedAccountSection(detected(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                        List.of(row(LocalDate.of(2026, 1, 15))), 1, 0, List.of()),
                new StagedAccountSection(detected(null, null),
                        List.of(row(LocalDate.of(2026, 1, 3)), row(LocalDate.of(2026, 2, 7))), 2, 0, List.of()));

        assertThat(importService.freePlanLimitNotice(user.getId(), null, sections)).isNull();
    }
}
