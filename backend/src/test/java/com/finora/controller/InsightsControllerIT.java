package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numeric Insights endpoint, and {@code /api/v1/insights/narration}, which since the AI insights
 * foundation (V283) serves the AI month story: FYN_INSIGHTS is granted to every plan, so a Free user
 * is no longer refused by plan. With no ANTHROPIC_API_KEY under test, Fyn is unavailable and the
 * endpoint answers 404 (no narration), which the website shows as "nothing to add". The story itself
 * is covered by AiInsightControllerIT with a stub model.
 */
class InsightsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("insights-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Insights IT Test User");
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

    private ResponseEntity<String> get(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void aFreeUser_isNotRefusedByPlan_andWithoutAKeyGetsNoNarration() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/insights/narration", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("errorCode").asText()).isNotEqualTo("ENTITLEMENT_001");
    }

    @Test
    void theNumericInsightsEndpointIsUnaffectedByTheNarrationGate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/insights", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // The month the insights describe reaches the wire under the names both clients read
    // (InsightsData.reportingMonth / reportingMonthIsCurrent) -- the clients label every insights
    // figure from it, so a renamed or dropped field would silently fall back to "this month".
    @Test
    void theNumericInsightsEndpoint_namesTheMonthItReportsOn() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Insights IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setTxnDate(LocalDate.of(2026, 6, 5));
        t.setAmount(BigDecimal.valueOf(500));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setMerchant("Cafe");
        t.setDescription("Cafe");
        transactionRepository.save(t);

        JsonNode body = mapper.readTree(get("/api/v1/insights", user).getBody()).get("data");

        assertThat(body.get("reportingMonth").asText()).isEqualTo("2026-06");
        assertThat(body.get("reportingMonthIsCurrent").asBoolean()).isFalse();
    }

    @Test
    void theNumericInsightsEndpoint_withNoSpending_reportsNoMonth() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        JsonNode body = mapper.readTree(get("/api/v1/insights", user).getBody()).get("data");

        assertThat(body.get("reportingMonth").isNull()).isTrue();
        assertThat(body.get("reportingMonthIsCurrent").asBoolean()).isTrue();
    }
}
