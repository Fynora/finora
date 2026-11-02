package com.finora.ai;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What actually leaves for Anthropic, per AI feature, with real data through the real services and
 * only the model client replaced (privacy workstream 9). The Privacy Policy says Fynora hides the
 * names of people it can recognise, and for a hand-typed description removes UPI IDs, account and
 * reference numbers and IFSC codes, and that AI insights send figures and built-in category names
 * only. Each test seeds an account with those things in it, triggers one feature over HTTP, and
 * reads every request the model client received.
 *
 * <p>Every value is invented: names that are not in Fynora's built-in name list (so only what the
 * shield learned from this account can hide them), and the synthetic number forms the fixture
 * policy allows.
 */
@TestPropertySource(properties = {"app.fyn.anthropic-api-key=test-key-not-real", "app.fyn.insights-audience=all"})
class AiOutboundPayloadIT extends AbstractIntegrationTest {

    /** The profile name and the account holder: hidden because they are the user's own. */
    private static final String PROFILE_FIRST = "Zovrin";
    private static final String PROFILE_LAST = "Qelthar";
    /** Someone this user paid: hidden because the shield learns it from the payment. */
    private static final String PAYEE_FIRST = "Qzxal";
    private static final String PAYEE_LAST = "Korwati";
    private static final String ACCOUNT_NUMBER = "100000000001";
    private static final String IFSC = "HDFC0XXXXXX";

    @MockitoBean private LlmClient llmClient;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private final List<LlmRequest> sent = Collections.synchronizedList(new ArrayList<>());
    private final YearMonth month = YearMonth.now(UserZone.DEFAULT);
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        sent.clear();
        when(llmClient.complete(any())).thenAnswer(inv -> {
            LlmRequest request = inv.getArgument(0);
            sent.add(request);
            return answerFor(request);
        });
        user = new User();
        user.setEmail("ai-outbound-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(PROFILE_FIRST + " " + PROFILE_LAST);
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user.recordAiProcessingConsent(true, java.time.Instant.now());
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        account = new Account();
        account.setUserId(user.getId());
        account.setName("Synthetic Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(100000));
        account.setAccountHolderName(PROFILE_FIRST.toUpperCase() + " " + PROFILE_LAST.toUpperCase());
        account = accountRepository.save(account);

        // A payment to a person, the shape the shield learns names from.
        save(txn("UPI-" + PAYEE_FIRST.toUpperCase() + " " + PAYEE_LAST.toUpperCase() + "-qzxalk@okaxis-UPI",
                CounterpartyType.PERSON, "vpa:qzxalk", 1500, month.atDay(2), null));
    }

    // ---------------------------------------------------------------------------------------
    // The three features the Privacy Policy names
    // ---------------------------------------------------------------------------------------

    @Test
    void askFyn_hidesTheProfileNameAndThePeoplePaid() {
        ResponseEntity<String> response = post("/api/v1/fyn/chat", Map.of("message",
                "Did " + PROFILE_FIRST + " pay " + PAYEE_FIRST + " " + PAYEE_LAST + " back this month, and how much did "
                        + PAYEE_LAST + " get?"));

        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        String outbound = everythingSent();
        assertThat(sent).as("the question reached the model client").isNotEmpty();
        assertThat(outbound).doesNotContainIgnoringCase(PROFILE_FIRST)
                .doesNotContainIgnoringCase(PROFILE_LAST)
                .doesNotContainIgnoringCase(PAYEE_FIRST)
                .doesNotContainIgnoringCase(PAYEE_LAST)
                .contains("[name-");
    }

    @Test
    void aHandTypedTransaction_sendsNoIdentifiersAndNoNames() {
        String vpa = "qzxhw" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String description = "UPI-QZX HARDWARE STORE-" + vpa + "@ybl-REF881234 A/C " + ACCOUNT_NUMBER + " IFSC " + IFSC
                + " " + PROFILE_FIRST.toUpperCase();

        ResponseEntity<String> response = post("/api/v1/transactions", Map.of(
                "accountId", account.getId().toString(), "date", month.atDay(3).toString(),
                "description", description, "amount", 250, "type", "EXPENSE", "tags", List.of()));

        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        assertThat(sent).as("the description reached the model client").isNotEmpty();
        String outbound = everythingSent();
        assertThat(outbound).doesNotContain(vpa)
                .doesNotContain(ACCOUNT_NUMBER)
                .doesNotContain(IFSC)
                .doesNotContain("881234")
                .doesNotContainIgnoringCase(PROFILE_FIRST)
                .doesNotContainIgnoringCase(PROFILE_LAST);
    }

    @Test
    void theMonthStory_sendsFiguresAndBuiltInCategoryNamesOnly() {
        Category own = new Category();
        own.setUserId(user.getId());
        own.setName(PAYEE_FIRST + " Rent Share");
        own.setSystem(false);
        own = categoryRepository.save(own);
        for (int back = 3; back >= 1; back--) {
            save(txn("SYNTHETIC LANDLORD PAYMENT " + ACCOUNT_NUMBER, CounterpartyType.BUSINESS, "vpa:landlord.qzx",
                    1000, month.minusMonths(back).atDay(5), own.getId()));
        }
        save(txn("SYNTHETIC LANDLORD PAYMENT " + ACCOUNT_NUMBER, CounterpartyType.BUSINESS, "vpa:landlord.qzx",
                4000, month.atDay(1), own.getId()));

        ResponseEntity<String> response = post("/api/v1/insights/story", Map.of());

        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        assertThat(sent).as("the story request reached the model client").isNotEmpty();
        String outbound = everythingSent();
        assertThat(outbound).doesNotContain("Rent Share")
                .doesNotContain("LANDLORD")
                .doesNotContain("landlord.qzx")
                .doesNotContain(ACCOUNT_NUMBER)
                .doesNotContainIgnoringCase(PAYEE_FIRST)
                .doesNotContainIgnoringCase(PAYEE_LAST)
                .doesNotContainIgnoringCase(PROFILE_FIRST)
                .doesNotContainIgnoringCase(PROFILE_LAST);
    }

    // ---------------------------------------------------------------------------------------

    /** Every request's system prompt, messages, tool results and tool inputs, as one string. */
    private String everythingSent() {
        StringBuilder all = new StringBuilder();
        synchronized (sent) {
            for (LlmRequest request : sent) all.append(request.systemPrompt()).append('\n').append(request.messages()).append('\n');
        }
        return all.toString();
    }

    /** A plausible answer per feature, so each one runs to completion rather than failing on the reply. */
    private static LlmCompletion answerFor(LlmRequest request) {
        List<String> tools = request.tools() == null ? List.of() : request.tools().stream().map(LlmClient.LlmTool::name).toList();
        if (tools.contains("UNDERSTAND_MERCHANT")) {
            return new LlmCompletion(null, List.of(new ToolUse("t1", "UNDERSTAND_MERCHANT",
                    Map.of("understanding", "A hardware store"))), "claude-haiku-4-5-20251001", 40, 10, "tool_use");
        }
        if (tools.contains("RESOLVE_CATEGORY")) {
            return new LlmCompletion(null, List.of(new ToolUse("t1", "RESOLVE_CATEGORY",
                    Map.of("category", "Other", "reason", "A shop"))), "claude-haiku-4-5-20251001", 40, 10, "tool_use");
        }
        if (request.systemPrompt() != null && request.systemPrompt().contains("2 to 4 sentences")) {
            return new LlmCompletion("{\"sentences\":[{\"type\":\"OBSERVATION\",\"template\":\"Spending came to {month.total}.\"}]}",
                    List.of(), "claude-haiku-4-5-20251001", 900, 60, "end_turn");
        }
        return new LlmCompletion("Here is what I found.", List.of(), "claude-haiku-4-5-20251001", 100, 20, "end_turn");
    }

    private Transaction txn(String description, CounterpartyType type, String key, int amount,
                            java.time.LocalDate date, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(date);
        t.setDescription(description);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(type);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        return t;
    }

    private void save(Transaction t) {
        transactionRepository.save(t);
    }

    private ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
