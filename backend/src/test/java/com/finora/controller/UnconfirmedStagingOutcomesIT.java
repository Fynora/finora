package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a statement that read cleanly ended without being imported (V326), recorded on the upload's
 * evidence row and shown in the admin import trace.
 *
 * <p>Motivated by a real upload: a new customer on the Free plan staged a savings statement whose
 * every check passed, then deleted it. Whether the one-month limit stopped them could not be
 * answered -- the staged session (and its period) went with the delete, a refused confirm (a 4xx) is
 * deliberately not logged, and nothing else recorded either.
 */
@TestPropertySource(properties = {
        "app.rate-limit.import-stage.max=10000",
        "app.import.queue.enabled=false",
        // A held statement keeps its file for the team's check, as in StagingTrustHoldIT.
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage"
})
class UnconfirmedStagingOutcomesIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private StatementAnalysisSessionRepository analyses;
    @Autowired private ImportSessionRepository sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Transactions from 5 Jan to 5 Mar, as in FreePlanLimitNoticeIT. */
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

    private User user(String plan, String role, String scope) {
        User user = new User();
        user.setEmail("unconfirmed-outcomes-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Unconfirmed Outcomes IT User");
        user.setRole(role);
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        if (plan != null) {
            subscriptionService.provisionFreeSubscription(user.getId());
            if (!"FREE".equals(plan)) subscriptionService.changePlan(user.getId(), plan, "test-plan", user.getId());
        }
        return user;
    }

    private User customer(String plan) {
        return user(plan, "USER", User.SCOPE_USER);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private UUID stageCsv(User user, String csv) throws Exception {
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

    private ResponseEntity<String> reload(User user, UUID sessionId) {
        return restTemplate.exchange("/api/v1/import/sessions/" + sessionId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    /** Confirms exactly the rows staged -- the confirm checks them before the period limit. */
    private ResponseEntity<String> confirm(User user, UUID sessionId) throws Exception {
        JsonNode staged = mapper.readTree(reload(user, sessionId).getBody()).get("data").get("staging").get("rows");
        List<ConfirmedRow> rows = new java.util.ArrayList<>();
        for (JsonNode row : staged) {
            rows.add(new ConfirmedRow(LocalDate.parse(row.get("date").asText()), row.get("description").asText(),
                    new BigDecimal(row.get("amount").asText()), row.get("type").asText(), "Food", true, "file",
                    null, false, null, null));
        }
        ConfirmRequest request = new ConfirmRequest(sessionId, rows, UUID.randomUUID(), null,
                null, null, null, null, null, null, null);
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/import/csv/confirm", HttpMethod.POST,
                new HttpEntity<>(request, headers), String.class);
    }

    private ResponseEntity<String> discard(User user, UUID sessionId) {
        return discard(user, sessionId, null);
    }

    /** As the app named by {@code platform} sends it ({@code X-Client-Platform}); null sends none. */
    private ResponseEntity<String> discard(User user, UUID sessionId, String platform) {
        return discard(user, sessionId, platform, null);
    }

    /** With the dialog's optional one-tap reason ({@code ?reason=}); null sends none. */
    private ResponseEntity<String> discard(User user, UUID sessionId, String platform, String reason) {
        HttpHeaders headers = bearerFor(user);
        if (platform != null) headers.set("X-Client-Platform", platform);
        return restTemplate.exchange("/api/v1/import/sessions/" + sessionId + (reason == null ? "" : "?reason=" + reason),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private JsonNode stage(User user, String path, byte[] content, String fileName, String platform) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (platform != null) headers.set("X-Client-Platform", platform);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private void makeMultiAccount(UUID sessionId) {
        ImportSession session = sessions.findById(sessionId).orElseThrow();
        session.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        sessions.save(session);
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    private StatementAnalysisSession evidenceOf(UUID sessionId) {
        List<StatementAnalysisSession> rows = analyses.findByImportSessionIdOrderByCreatedAtDesc(sessionId);
        assertThat(rows).as("the upload's evidence row").hasSize(1);
        return rows.get(0);
    }

    @Test
    void aFreeUsersTooLongStatement_recordsTheNotice_theRefusal_andTheDiscard() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, THREE_MONTH_CSV);

        Instant shown = evidenceOf(sessionId).getFreeLimitShownAt();
        assertThat(shown).as("notice shown at staging").isNotNull();
        assertThat(evidenceOf(sessionId).getFreeLimitRefusedAt()).isNull();

        // Reopening the review shows the notice again; the first time is what is kept.
        assertThat(reload(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evidenceOf(sessionId).getFreeLimitShownAt()).isEqualTo(shown);

        ResponseEntity<String> refused = confirm(user, sessionId);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(refused.getBody()).get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");
        assertThat(evidenceOf(sessionId).getFreeLimitRefusedAt())
                .as("kept although the refused confirm rolled back").isNotNull();
        assertThat(evidenceOf(sessionId).getDiscardedUnconfirmedAt()).isNull();

        assertThat(discard(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessions.existsById(sessionId)).isFalse();
        StatementAnalysisSession after = evidenceOf(sessionId);
        assertThat(after.getDiscardedUnconfirmedAt()).isNotNull();
        assertThat(after.getDiscardedBy()).isEqualTo("CUSTOMER");
        assertThat(after.getExpiredUnconfirmedAt()).isNull();

        // The admin trace answers the question without SQL.
        User admin = user(null, "ADMIN", User.SCOPE_ADMIN);
        ResponseEntity<String> trace = restTemplate.exchange(
                "/api/v1/admin/imports/traces/by-analysis/" + after.getReference(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(trace.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode completion = mapper.readTree(trace.getBody()).get("data").get("completion");
        assertThat(completion.get("discardedUnconfirmedAt").isNull()).isFalse();
        assertThat(completion.get("discardedBy").asText()).isEqualTo("CUSTOMER");
        assertThat(completion.get("freeLimitShownAt").isNull()).isFalse();
        assertThat(completion.get("freeLimitRefusedAt").isNull()).isFalse();
        assertThat(completion.get("expiredUnconfirmedAt").isNull()).isTrue();
        assertThat(completion.get("statementImportId").isNull()).isTrue();
    }

    @Test
    void aStatementWithinTheLimit_recordsOnlyTheDiscard() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
        assertThat(evidenceOf(sessionId).getFreeLimitShownAt()).isNull();

        assertThat(discard(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);

        StatementAnalysisSession after = evidenceOf(sessionId);
        assertThat(after.getDiscardedUnconfirmedAt()).isNotNull();
        assertThat(after.getFreeLimitShownAt()).isNull();
        assertThat(after.getFreeLimitRefusedAt()).isNull();
    }

    @Test
    void aPlusUsersLongStatement_isNeverRecordedAsLimited() throws Exception {
        User user = customer("PLUS");
        UUID sessionId = stageCsv(user, THREE_MONTH_CSV);
        assertThat(reload(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getFreeLimitShownAt()).isNull();
    }

    @Test
    void aConfirmedSessionCannotBeDiscarded_andRecordsNothing() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
        ImportSession session = sessions.findById(sessionId).orElseThrow();
        session.setStatus(ImportSession.STATUS_CONFIRMED);
        sessions.save(session);

        assertThat(discard(user, sessionId).getStatusCode().is2xxSuccessful()).isFalse();

        assertThat(evidenceOf(sessionId).getDiscardedUnconfirmedAt()).isNull();
    }

    @Test
    void discardingAStatementFynoraBroughtIn_isNotRecordedAsTheCustomerAbandoningAnUpload() throws Exception {
        // A Gmail-sourced session was put in front of the customer, not brought by them -- the same
        // rule the expiry sweep applies (ImportSessionService.stampAbandoned).
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
        ImportSession session = sessions.findById(sessionId).orElseThrow();
        session.setSource("GMAIL");
        sessions.save(session);

        assertThat(discard(user, sessionId).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getDiscardedUnconfirmedAt()).isNull();
        assertThat(evidenceOf(sessionId).getDiscardedBy()).isNull();
    }

    // -- Who discarded it: the phone deletes a statement it cannot import by itself ------------------

    @Test
    void thePhoneDeletingAMultiAccountStatement_isNotRecordedAsTheCustomersChoice() throws Exception {
        // The phone app cannot review a statement covering more than one account and deletes it as
        // soon as it is staged (mobile ImportScreen). That is not a customer abandoning a statement.
        for (String phone : List.of("MOBILE_ANDROID", "MOBILE_IOS")) {
            User user = customer("FREE");
            UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
            makeMultiAccount(sessionId);

            assertThat(discard(user, sessionId, phone).getStatusCode()).isEqualTo(HttpStatus.OK);

            StatementAnalysisSession after = evidenceOf(sessionId);
            assertThat(after.getDiscardedUnconfirmedAt()).as(phone).isNotNull();
            assertThat(after.getDiscardedBy()).as(phone).isEqualTo("PHONE_MULTI_ACCOUNT");
        }
    }

    @Test
    void aMultiAccountStatementDiscardedOnTheWeb_isTheCustomersChoice() throws Exception {
        // The web app reviews composite statements; deleting one there is the customer's decision.
        // No header at all reads as web, as everywhere else (ClientIdentity).
        for (String web : java.util.Arrays.asList("WEB", null)) {
            User user = customer("FREE");
            UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
            makeMultiAccount(sessionId);

            assertThat(discard(user, sessionId, web).getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(evidenceOf(sessionId).getDiscardedBy()).as(String.valueOf(web)).isEqualTo("CUSTOMER");
        }
    }

    @Test
    void aSingleAccountStatementDiscardedOnThePhone_isTheCustomersChoice() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);

        assertThat(discard(user, sessionId, "MOBILE_ANDROID").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getDiscardedBy()).isEqualTo("CUSTOMER");
    }

    // -- "Shown" means the review screen opened with it -----------------------------------------------

    /** Three months, and a statement period in 2030: the trust check holds it (as StagingTrustHoldIT). */
    private static final String HELD_THREE_MONTH_CSV = """
            Date,Description,Amount,Balance,Statement Period
            01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/03/2030
            15/01/2026,Coffee shop,-150.00,850.00,
            20/02/2026,Book shop,-80.00,770.00,
            15/03/2026,Tea stall,-60.00,710.00,
            """;

    @Test
    void aHeldStatement_carriesTheNotice_butIsNotRecordedAsShown() throws Exception {
        // Both apps follow the held job instead of opening the review screen, so the notice in the
        // response is never displayed. It is recorded when the review opens after an approval.
        User user = customer("FREE");
        JsonNode data = stage(user, "/api/v1/import/csv/stage",
                HELD_THREE_MONTH_CSV.getBytes(StandardCharsets.UTF_8), "statement.csv", null);
        assertThat(absent(data.get("heldForReviewJobId"))).as("the trust check held it").isFalse();
        assertThat(absent(data.get("freePlanLimit"))).as("the notice was computed").isFalse();
        UUID sessionId = UUID.fromString(data.get("sessionId").asText());

        assertThat(evidenceOf(sessionId).getFreeLimitShownAt()).isNull();
    }

    private static byte[] multiAccountThreeMonthPdf() throws Exception {
        var account = (java.util.function.BiFunction<String, String,
                com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity>) (id, masked) ->
                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity(
                        id, "SAVINGS", com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence.DETECTED,
                        masked, com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions.FALSE,
                        List.of(
                                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                        LocalDate.of(2026, 1, 5), "Coffee shop", new BigDecimal("100.00"), false),
                                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                        LocalDate.of(2026, 2, 10), "Book shop", new BigDecimal("80.00"), false),
                                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row(
                                        LocalDate.of(2026, 3, 5), "Tea stall", new BigDecimal("60.00"), false)));
        return com.finora.imports.pdf.fixtures.PdfFixtureBuilder.render(
                new com.finora.imports.pdf.fixtures.SyntheticStatementDefinition("unconfirmed-outcomes-multi-001",
                        List.of(account.apply("savings-one", "••••4321"), account.apply("savings-two", "••••8765")),
                        List.of()));
    }

    @Test
    void aMultiAccountStatementStagedOnThePhone_isNotRecordedAsShown_butOnTheWebItIs() throws Exception {
        byte[] pdf = multiAccountThreeMonthPdf();

        User phoneUser = customer("FREE");
        JsonNode onPhone = stage(phoneUser, "/api/v1/import/pdf/stage", pdf, "statement.pdf", "MOBILE_IOS");
        assertThat(onPhone.get("multiAccount").asBoolean()).as("a composite statement").isTrue();
        assertThat(absent(onPhone.get("heldForReviewJobId"))).as("not held").isTrue();
        assertThat(absent(onPhone.get("freePlanLimit"))).as("the notice was computed").isFalse();
        assertThat(evidenceOf(UUID.fromString(onPhone.get("sessionId").asText())).getFreeLimitShownAt()).isNull();

        User webUser = customer("FREE");
        JsonNode onWeb = stage(webUser, "/api/v1/import/pdf/stage", pdf, "statement.pdf", "WEB");
        assertThat(onWeb.get("multiAccount").asBoolean()).isTrue();
        assertThat(evidenceOf(UUID.fromString(onWeb.get("sessionId").asText())).getFreeLimitShownAt()).isNotNull();
    }

    // -- The customer's optional reason ------------------------------------------------------------

    @Test
    void theCustomersReason_isKeptWithTheDiscard_andShownInTheTrace() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);

        assertThat(discard(user, sessionId, "WEB", "TOO_MANY_MONTHS").getStatusCode()).isEqualTo(HttpStatus.OK);

        StatementAnalysisSession after = evidenceOf(sessionId);
        assertThat(after.getDiscardedBy()).isEqualTo("CUSTOMER");
        assertThat(after.getDiscardReason()).isEqualTo("TOO_MANY_MONTHS");

        User admin = user(null, "ADMIN", User.SCOPE_ADMIN);
        JsonNode completion = mapper.readTree(restTemplate.exchange(
                "/api/v1/admin/imports/traces/by-analysis/" + after.getReference(), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class).getBody()).get("data").get("completion");
        assertThat(completion.get("discardReason").asText()).isEqualTo("TOO_MANY_MONTHS");
    }

    @Test
    void noReason_isNormal() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);

        assertThat(discard(user, sessionId, "MOBILE_IOS").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getDiscardedBy()).isEqualTo("CUSTOMER");
        assertThat(evidenceOf(sessionId).getDiscardReason()).isNull();
    }

    @Test
    void thePhoneRemovingAStatementItCannotImport_carriesNoReason() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
        makeMultiAccount(sessionId);

        assertThat(discard(user, sessionId, "MOBILE_ANDROID", "WRONG_FILE").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getDiscardedBy()).isEqualTo("PHONE_MULTI_ACCOUNT");
        assertThat(evidenceOf(sessionId).getDiscardReason()).isNull();
    }

    @Test
    void aPhoneDiscardTheCustomerConfirmed_isTheirs_evenForAStatementCoveringMoreThanOneAccount() throws Exception {
        // Phone builds from this change on ask first (the discard sheet), and never delete one by
        // themselves; only an older build's silent deletion is the phone's.
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);
        makeMultiAccount(sessionId);

        assertThat(discard(user, sessionId, "MOBILE_IOS", "LOOKS_WRONG&confirmedByCustomer=true").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(evidenceOf(sessionId).getDiscardedBy()).isEqualTo("CUSTOMER");
        assertThat(evidenceOf(sessionId).getDiscardReason()).isEqualTo("LOOKS_WRONG");
    }

    @Test
    void aReasonThatIsNotOneOfTheChoices_isRefused_andNothingIsDiscarded() throws Exception {
        User user = customer("FREE");
        UUID sessionId = stageCsv(user, ONE_MONTH_CSV);

        assertThat(discard(user, sessionId, "WEB", "SOMETHING_ELSE").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(sessions.existsById(sessionId)).isTrue();
        assertThat(evidenceOf(sessionId).getDiscardedUnconfirmedAt()).isNull();
    }

    @Test
    void anotherCustomersDiscard_isRefused_andRecordsNothing() throws Exception {
        User owner = customer("FREE");
        UUID sessionId = stageCsv(owner, ONE_MONTH_CSV);

        ResponseEntity<String> response = discard(customer("FREE"), sessionId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(sessions.existsById(sessionId)).isTrue();
        assertThat(evidenceOf(sessionId).getDiscardedUnconfirmedAt()).isNull();
    }
}
