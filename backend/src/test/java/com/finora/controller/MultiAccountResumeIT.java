package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement covering more than one account can be picked up again: listed in "Continue a previous
 * import" when the app asks for it, and reopened with its sections by GET /import/sessions/{id}, the
 * endpoint a queued upload's review opens from too. Before this, both refused it, so a customer who
 * left one (or uploaded one from the phone, which cannot review it) had to upload it again.
 */
@TestPropertySource(properties = {
        "app.rate-limit.import-stage.max=10000",
        "app.import.queue.enabled=false"
})
class MultiAccountResumeIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private StatementAnalysisSessionRepository analyses;
    private final ObjectMapper mapper = new ObjectMapper();

    private User customer(String plan) {
        User user = new User();
        user.setEmail("multi-resume-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Multi Resume IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        if (!"FREE".equals(plan)) subscriptionService.changePlan(user.getId(), plan, "test-plan", user.getId());
        return user;
    }

    private HttpHeaders headers(User user, String platform) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        if (platform != null) headers.set("X-Client-Platform", platform);
        return headers;
    }

    /** Two savings accounts in one PDF, rows from {@code first} to {@code last}. */
    private static byte[] twoAccountPdf(LocalDate first, LocalDate last) throws Exception {
        var account = (java.util.function.BiFunction<String, String, SyntheticStatementDefinition.ExpectedEntity>)
                (id, masked) -> new SyntheticStatementDefinition.ExpectedEntity(id, "SAVINGS",
                        SyntheticStatementDefinition.Presence.DETECTED, masked,
                        SyntheticStatementDefinition.ZeroTransactions.FALSE, List.of(
                                new SyntheticStatementDefinition.Row(first, "Coffee shop", new BigDecimal("100.00"), false),
                                new SyntheticStatementDefinition.Row(last, "Book shop", new BigDecimal("80.00"), false)));
        return PdfFixtureBuilder.render(new SyntheticStatementDefinition("multi-resume-001",
                List.of(account.apply("savings-one", "••••4321"), account.apply("savings-two", "••••8765")), List.of()));
    }

    private static final LocalDate JAN_5 = LocalDate.of(2026, 1, 5);

    private UUID stage(User user, byte[] pdf, String platform) throws Exception {
        HttpHeaders headers = headers(user, platform);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "statement.pdf"; }
        });
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/pdf/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("multiAccount").asBoolean()).as("a composite statement").isTrue();
        return UUID.fromString(data.get("sessionId").asText());
    }

    private JsonNode get(User user, String path, String platform) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(user, platform)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static JsonNode rowFor(JsonNode list, UUID sessionId) {
        for (JsonNode row : list) if (row.get("id").asText().equals(sessionId.toString())) return row;
        return null;
    }

    @Test
    void itIsListedOnlyForAnAppThatAsks_andReopensWithItsSections() throws Exception {
        User user = customer("PLUS");
        UUID sessionId = stage(user, twoAccountPdf(JAN_5, JAN_5.plusDays(15)), "WEB");

        assertThat(rowFor(get(user, "/api/v1/import/sessions", "WEB"), sessionId))
                .as("an older app never sees a row it cannot open").isNull();
        JsonNode listed = rowFor(get(user, "/api/v1/import/sessions?includeMultiAccount=true", "WEB"), sessionId);
        assertThat(listed).isNotNull();
        assertThat(listed.get("multiAccount").asBoolean()).isTrue();
        assertThat(listed.get("rowCount").asInt()).isEqualTo(4);

        JsonNode reopened = get(user, "/api/v1/import/sessions/" + sessionId, "WEB");
        assertThat(reopened.get("multiAccount").asBoolean()).isTrue();
        assertThat(reopened.get("staging").isNull()).isTrue();
        assertThat(reopened.get("sections")).hasSize(2);
        assertThat(reopened.get("sections").get(0).get("rows")).hasSize(2);
    }

    @Test
    void aReopenedStatementImportsThroughItsOwnConfirm() throws Exception {
        User user = customer("PLUS");
        UUID sessionId = stage(user, twoAccountPdf(JAN_5, JAN_5.plusDays(15)), "WEB");
        JsonNode sections = get(user, "/api/v1/import/sessions/" + sessionId, "WEB").get("sections");

        List<Map<String, Object>> confirm = new ArrayList<>();
        int n = 0;
        for (JsonNode section : sections) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : section.get("rows")) {
                rows.add(Map.of("date", row.get("date").asText(), "description", row.get("description").asText(),
                        "amount", row.get("amount").asText(), "type", row.get("type").asText(),
                        "category", "Other", "include", true));
            }
            confirm.add(Map.of("rows", rows, "newAccount", Map.of("name", "Resume IT account " + (++n), "accountType", "SAVINGS")));
        }
        HttpHeaders headers = headers(user, "WEB");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> confirmed = restTemplate.exchange("/api/v1/import/pdf/confirm-multi", HttpMethod.POST,
                new HttpEntity<>(Map.of("sessionId", sessionId.toString(), "sections", confirm), headers), String.class);

        assertThat(confirmed.getStatusCode()).as(confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(confirmed.getBody()).get("data").get("perAccount")).hasSize(2);
    }

    @Test
    void theFreePlanNotice_judgesEverySection_andCountsAsShownOnlyOnTheWeb() throws Exception {
        byte[] threeMonths = twoAccountPdf(JAN_5, JAN_5.plusMonths(2));

        User webUser = customer("FREE");
        UUID onWeb = stage(webUser, threeMonths, "WEB");
        analyses.findByImportSessionIdOrderByCreatedAtDesc(onWeb).forEach(a ->
                assertThat(a.getFreeLimitShownAt()).as("shown when the web review opened at staging").isNotNull());
        JsonNode reopened = get(webUser, "/api/v1/import/sessions/" + onWeb, "WEB");
        assertThat(reopened.get("freePlanLimit").get("errorCode").asText()).isEqualTo("ENTITLEMENT_003");

        User phoneUser = customer("FREE");
        UUID onPhone = stage(phoneUser, threeMonths, "MOBILE_ANDROID");
        JsonNode onThePhone = get(phoneUser, "/api/v1/import/sessions/" + onPhone, "MOBILE_ANDROID");
        assertThat(onThePhone.get("freePlanLimit").isNull()).as("the notice is still computed").isFalse();
        assertThat(analyses.findByImportSessionIdOrderByCreatedAtDesc(onPhone).get(0).getFreeLimitShownAt())
                .as("the phone lists it to finish on the web; no review opened there").isNull();
        JsonNode laterOnTheWeb = get(phoneUser, "/api/v1/import/sessions/" + onPhone, "WEB");
        assertThat(laterOnTheWeb.get("freePlanLimit").isNull()).isFalse();
        assertThat(analyses.findByImportSessionIdOrderByCreatedAtDesc(onPhone).get(0).getFreeLimitShownAt())
                .as("recorded once the web review opens it").isNotNull();
    }

    @Test
    void aSingleAccountStatementReopensAsBefore() throws Exception {
        User user = customer("PLUS");
        HttpHeaders headers = headers(user, "WEB");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("""
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                05/01/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
                20/01/2026,UPI-ZORBIC TEAHOUSE-0000000002,80.00,,24800.00
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        JsonNode staged = mapper.readTree(restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody()).get("data");
        UUID sessionId = UUID.fromString(staged.get("sessionId").asText());

        JsonNode reopened = get(user, "/api/v1/import/sessions/" + sessionId, "WEB");
        assertThat(reopened.get("multiAccount").asBoolean()).isFalse();
        assertThat(reopened.get("sections").isNull()).isTrue();
        assertThat(reopened.get("staging").get("rows")).hasSize(2);
        JsonNode listed = rowFor(get(user, "/api/v1/import/sessions", "WEB"), sessionId);
        assertThat(listed.get("multiAccount").asBoolean()).isFalse();
    }
}
