package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.LookAtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import com.finora.util.CounterpartyType;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The AI insights endpoints (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §8.1)
 * over HTTP, with a real database and a stub model: read then generate, the narration the website
 * reads, "To look at" in rules order, owner scoping and input validation. Every name and amount is
 * invented.
 */
@TestPropertySource(properties = {"app.fyn.anthropic-api-key=test-key-not-real", "app.fyn.insights-audience=all"})
class AiInsightControllerIT extends AbstractIntegrationTest {

    @MockitoBean private LlmClient llmClient;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LookAtService lookAtService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final YearMonth month = YearMonth.now(UserZone.DEFAULT);
    private static final Pattern SUBJECT = Pattern.compile("\\{\"id\":\"([a-z0-9_]+)\",\"name\":");

    private User user;

    @BeforeEach
    void setUp() {
        user = userWithSpending();
        when(llmClient.complete(any())).thenAnswer(inv -> {
            LlmRequest request = inv.getArgument(0);
            String template;
            if (request.systemPrompt().contains("2 to 4 sentences")) {
                template = "Spending came to {month.total}.";
            } else {
                Matcher m = SUBJECT.matcher(request.messages().get(0).content());
                String subject = "month";
                while (m.find()) {
                    if (!m.group(1).equals("month")) { subject = m.group(1); break; }
                }
                template = "{" + subject + "} came to {" + subject + ".this_month}.";
            }
            return new LlmCompletion("{\"sentences\":[{\"type\":\"OBSERVATION\",\"template\":\"" + template + "\"}]}",
                    List.of(), "claude-haiku-4-5-20251001", 900, 60, "end_turn");
        });
    }

    private User userWithSpending() {
        User u = new User();
        u.setEmail("ai-insight-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Sample User");
        u.setAccountScope(User.SCOPE_USER);
        u.setPhoneVerified(true);
        u = userRepository.save(u);
        subscriptionService.provisionFreeSubscription(u.getId());
        Account a = new Account();
        a.setUserId(u.getId());
        a.setName("Sample Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        a = accountRepository.save(a);
        // Food, Fuel and Travel each 1,000 a month before, and running high this month at 3,000, 2,500, 4,000.
        String[][] categories = {{"Food", "3000"}, {"Fuel", "2500"}, {"Travel", "4000"}};
        for (String[] c : categories) {
            Category cat = new Category();
            cat.setUserId(u.getId());
            cat.setName(c[0]);
            cat.setSystem(true);
            cat = categoryRepository.save(cat);
            for (int back = 3; back >= 1; back--) spend(u, a, 1000, month.minusMonths(back).atDay(5), cat.getId());
            spend(u, a, Integer.parseInt(c[1]), month.atDay(1), cat.getId());
        }
        return u;
    }

    private void spend(User u, Account a, int amount, LocalDate date, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(u.getId());
        t.setAccountId(a.getId());
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(date);
        t.setDescription("UPI vpa:shop.sample");
        t.setCounterpartyKey("vpa:shop.sample");
        t.setCounterpartyType(CounterpartyType.BUSINESS);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        transactionRepository.save(t);
    }

    private HttpHeaders bearerFor(User u) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, u));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> call(HttpMethod method, String path, User u, String body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearerFor(u)), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void storyReadThenGenerate() throws Exception {
        ResponseEntity<String> first = call(HttpMethod.GET, "/api/v1/insights/story", user, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(first).get("status").asText()).isEqualTo("none");
        assertThat(data(first).get("canGenerate").asBoolean()).isTrue();

        ResponseEntity<String> made = call(HttpMethod.POST, "/api/v1/insights/story", user, "{}");
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode story = data(made);
        assertThat(story.get("status").asText()).isEqualTo("ready");
        assertThat(story.get("writtenByAi").asBoolean()).isTrue();
        assertThat(story.get("text").asText()).isEqualTo("Spending came to \u20b99,500.");
        assertThat(story.get("basis").asText()).startsWith("Compared with your usual month (");

        JsonNode read = data(call(HttpMethod.GET, "/api/v1/insights/story", user, null));
        assertThat(read.get("id").asText()).isEqualTo(story.get("id").asText());
        assertThat(read.get("canGenerate").asBoolean()).isFalse();
    }

    @Test
    void narrationServesTheStory() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/insights/narration", user, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response).get("narration").asText()).isEqualTo("Spending came to \u20b99,500.");
    }

    @Test
    void lookAtMatchesTheMobileRules() throws Exception {
        JsonNode lookAt = data(call(HttpMethod.GET, "/api/v1/insights/look-at", user, null));

        List<String> keys = new ArrayList<>();
        lookAt.get("rows").forEach(r -> keys.add(r.get("key").asText()));
        List<String> expected = lookAtService.rows(user.getId(), (String) null).stream().map(LookAtRowDto::key).toList();
        assertThat(keys).isEqualTo(expected);
        // Running high, the largest rupee gap first: Travel (+3,000), Food (+2,000), Fuel (+1,500).
        assertThat(keys).containsExactly("CATEGORY_HIGH|Travel|" + month, "CATEGORY_HIGH|Food|" + month,
                "CATEGORY_HIGH|Fuel|" + month);
        assertThat(lookAt.get("orderedByAi").asBoolean()).isFalse();
        assertThat(lookAt.get("rows").get(0).get("whyAvailable").asBoolean()).isTrue();
    }

    @Test
    void whyAnswersAndCountsTheFreeAllowance() throws Exception {
        String body = "{\"kind\":\"CATEGORY_HIGH\",\"itemKey\":\"Food|" + month + "\"}";
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/insights/look-at/why", user, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode why = data(response);
        assertThat(why.get("writtenByAi").asBoolean()).isTrue();
        assertThat(why.get("text").asText()).isEqualTo("Food came to \u20b93,000.");
        assertThat(why.get("freeLeftThisMonth").asInt()).isEqualTo(4);
    }

    @Test
    void whyForAnotherUsersRowKeyGivesNoData() {
        User other = userWithSpending();
        // Travel|month exists for both users, but each only ever reaches their own rows.
        String body = "{\"kind\":\"CATEGORY_HIGH\",\"itemKey\":\"Groceries|" + month + "\"}";
        assertThat(call(HttpMethod.POST, "/api/v1/insights/look-at/why", other, body).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void feedbackOnAnotherUsersTextIs404() throws Exception {
        String id = data(call(HttpMethod.POST, "/api/v1/insights/story", user, "{}")).get("id").asText();
        User other = userWithSpending();

        ResponseEntity<String> theirs = call(HttpMethod.PATCH, "/api/v1/insights/ai-texts/" + id + "/feedback", other,
                "{\"rating\":\"HELPFUL\"}");
        ResponseEntity<String> mine = call(HttpMethod.PATCH, "/api/v1/insights/ai-texts/" + id + "/feedback", user,
                "{\"rating\":\"NOT_HELPFUL\",\"reason\":\"WRONG_NUMBER\"}");

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void badInputIs400() {
        assertThat(call(HttpMethod.GET, "/api/v1/insights/story?month=2026-9", user, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/insights/story", user, "{\"month\":\"October\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/insights/look-at?month=bad", user, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/insights/look-at/why", user,
                "{\"kind\":\"SOMETHING\",\"itemKey\":\"x\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/api/v1/insights/look-at/why", user,
                "{\"kind\":\"CATEGORY_HIGH\",\"itemKey\":\"" + "x".repeat(301) + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PATCH, "/api/v1/insights/ai-texts/" + UUID.randomUUID() + "/feedback", user,
                "{\"rating\":\"LOVED_IT\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
