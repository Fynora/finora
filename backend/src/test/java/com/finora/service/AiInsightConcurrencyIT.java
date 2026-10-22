package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AccountRepository;
import com.finora.repository.AiInsightUsageRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.CounterpartyType;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI insights foundation §5.4, §6 on a real database: parallel requests for one text make one model
 * call, and parallel "Why?" taps never pass the Free allowance. The model is a stub that answers a
 * valid template after 300 ms, so requests genuinely overlap. Every name and amount is invented.
 */
@TestPropertySource(properties = {"app.fyn.anthropic-api-key=test-key-not-real", "app.fyn.insights-audience=all"})
class AiInsightConcurrencyIT extends AbstractIntegrationTest {

    @MockitoBean private LlmClient llmClient;
    @Autowired private AiInsightService aiInsightService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AiInsightUsageRepository usageRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;
    private final YearMonth month = YearMonth.now(UserZone.DEFAULT);

    private static final Pattern SUBJECT = Pattern.compile("\\{\"id\":\"([a-z0-9_]+)\",\"name\":");

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("ai-concurrency-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("Sample User");
        userId = userRepository.save(u).getId();
        subscriptionService.provisionFreeSubscription(userId);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("Sample Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(a).getId();

        // Three categories each running high: 1,000 in each of the three months before, 3,000 now.
        for (String name : new String[]{"Food", "Fuel", "Travel"}) {
            Category c = new Category();
            c.setUserId(userId);
            c.setName(name);
            c.setSystem(true);
            c = categoryRepository.save(c);
            for (int back = 3; back >= 1; back--) spend(1000, month.minusMonths(back).atDay(5), c.getId());
            spend(3000, month.atDay(1), c.getId());
        }

        when(llmClient.complete(any())).thenAnswer(inv -> {
            Thread.sleep(300);
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
            String reply = "{\"sentences\":[{\"type\":\"OBSERVATION\",\"template\":\"" + template + "\"}]}";
            return new LlmCompletion(reply, List.of(), "claude-haiku-4-5-20251001", 900, 60, "end_turn");
        });
    }

    private void spend(int amount, LocalDate date, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
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

    private <T> List<T> parallel(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<T> out = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(calls)) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelStoryGenerationCallsTheModelOnce() throws Exception {
        List<Callable<AiInsightService.TextView>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> aiInsightService.generateStory(userId, null));

        List<AiInsightService.TextView> views = parallel(calls);

        verify(llmClient, times(1)).complete(any());
        assertThat(views).allSatisfy(v -> assertThat(v.status()).isIn("ready", "generating"));
        assertThat(views).anySatisfy(v -> assertThat(v.status()).isEqualTo("ready"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_insight_texts WHERE user_id = ? AND kind = 'STORY'",
                Long.class, userId)).isEqualTo(1L);
        assertThat(aiInsightService.getStory(userId, null).text()).isEqualTo("Spending came to \u20b99,000.");
    }

    @Test
    void parallelFreeWhyTapsNeverPassFive() throws Exception {
        String localMonth = YearMonth.now(UserZone.DEFAULT).toString();
        for (int i = 0; i < 3; i++) usageRepository.reserveWhy(userId, localMonth, 5);
        List<String> keys = aiInsightService.lookAt(userId, null).rows().stream()
                .filter(r -> "running-high".equals(r.kind())).map(r -> r.key()).toList();
        assertThat(keys).hasSize(3);

        List<Callable<AiInsightService.TextView>> calls = new ArrayList<>();
        for (String key : keys) {
            for (int i = 0; i < 3; i++) calls.add(() -> aiInsightService.why(userId, key, null));
        }
        parallel(calls);

        verify(llmClient, times(2)).complete(any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_insight_texts WHERE user_id = ? AND kind = 'WHY' AND status = 'READY'",
                Long.class, userId)).isEqualTo(2L);
        assertThat(usageRepository.whyUsed(userId, localMonth)).isEqualTo(5);
    }
}
