package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.LoanEntry;
import com.finora.entity.ShareLink;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.LoanEntryRepository;
import com.finora.repository.ShareLinkRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.CounterpartyType;
import com.finora.util.UserZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI insights launch gate's offline evaluation
 * (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §7.2.2 and §7.3), run against the
 * real model. <b>Skipped unless {@code ANTHROPIC_API_KEY} is set</b> -- it spends real tokens, so it
 * never runs in CI or by accident. Run it by hand before the audience goes to "all":
 *
 * <pre>ANTHROPIC_API_KEY=... ./mvnw verify -Dit.test=AiInsightsEvaluationIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>30 synthetic histories (every name and amount invented, seeded so a rerun builds the same ones):
 * plain months, a category spike, a big one-off payment, a weak baseline (one prior month), a first
 * month, a falling category, a user-created category, a shared bill with shares linked, money lent,
 * and mixes of these. Not covered here: statement gaps and card bills, which need import-level
 * fixtures; their figures are InsightsService's own and covered by its tests.
 *
 * <p>Writes {@code target/ai-insights-eval/}: {@code outputs.jsonl} (one line per history: the story,
 * every "Why?", their reasons, both orders) for review by hand, {@code side-by-side.md} (the AI and
 * rules top 3 as A/B in random order) and {@code side-by-side-key.md} (which is which). <b>Asserts
 * nothing about quality</b>: the gate is 0 incorrect claims and 0 advice, predictions or instructions
 * found by a person reading every output, and a check pass rate of at least 90%. This test only fails
 * when a history could not be run at all.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@TestPropertySource(properties = {"app.fyn.insights-audience=all", "app.fyn.insights-ai-ranking=true",
        "app.fyn.insights-max-concurrent=1"})
class AiInsightsEvaluationIT extends AbstractIntegrationTest {

    @Autowired private AiInsightService aiInsightService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;
    @Autowired private LoanEntryRepository loanEntryRepository;
    @Autowired private JdbcTemplate jdbc;

    private final YearMonth month = YearMonth.now(UserZone.DEFAULT);
    private final ObjectMapper mapper = new ObjectMapper();

    /** One synthetic history's shape. */
    private record Shape(String name, int priorMonths, boolean spike, boolean oneOff, boolean falling,
                         boolean userCategory, boolean shared, boolean lent) {}

    private List<Shape> shapes() {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Shape("plain-steady", 3, false, false, false, false, false, false));
        shapes.add(new Shape("plain-steady-2", 3, false, false, false, false, false, false));
        shapes.add(new Shape("plain-steady-3", 3, false, false, false, false, false, false));
        shapes.add(new Shape("spike", 3, true, false, false, false, false, false));
        shapes.add(new Shape("spike-2", 3, true, false, false, false, false, false));
        shapes.add(new Shape("one-off", 3, false, true, false, false, false, false));
        shapes.add(new Shape("one-off-2", 3, false, true, false, false, false, false));
        shapes.add(new Shape("weak-baseline", 1, true, false, false, false, false, false));
        shapes.add(new Shape("weak-baseline-2", 1, false, true, false, false, false, false));
        shapes.add(new Shape("first-month", 0, false, false, false, false, false, false));
        shapes.add(new Shape("first-month-2", 0, false, true, false, false, false, false));
        shapes.add(new Shape("falling", 3, false, false, true, false, false, false));
        shapes.add(new Shape("falling-2", 3, false, false, true, false, false, false));
        shapes.add(new Shape("user-category", 3, true, false, false, true, false, false));
        shapes.add(new Shape("user-category-2", 3, false, false, false, true, false, false));
        shapes.add(new Shape("shared-bill", 3, false, false, false, false, true, false));
        shapes.add(new Shape("shared-bill-2", 3, true, false, false, false, true, false));
        shapes.add(new Shape("lent", 3, false, false, false, false, false, true));
        shapes.add(new Shape("lent-2", 3, true, false, false, false, false, true));
        shapes.add(new Shape("spike-falling", 3, true, false, true, false, false, false));
        shapes.add(new Shape("spike-one-off", 3, true, true, false, false, false, false));
        shapes.add(new Shape("spike-user", 2, true, false, false, true, false, false));
        shapes.add(new Shape("shared-lent", 3, false, false, false, false, true, true));
        shapes.add(new Shape("everything", 3, true, true, true, true, true, true));
        shapes.add(new Shape("everything-weak", 1, true, true, true, true, true, true));
        shapes.add(new Shape("two-months", 2, true, false, false, false, false, false));
        shapes.add(new Shape("two-months-falling", 2, false, false, true, false, false, false));
        shapes.add(new Shape("one-off-falling", 3, false, true, true, false, false, false));
        shapes.add(new Shape("user-shared", 3, false, false, false, true, true, false));
        shapes.add(new Shape("user-lent-spike", 3, true, false, false, true, false, true));
        return shapes;
    }

    @Test
    void runEveryHistoryAndWriteTheOutputsForReview() throws IOException {
        Path out = Path.of("target/ai-insights-eval");
        Files.createDirectories(out);
        List<String> lines = new ArrayList<>();
        StringBuilder sideBySide = new StringBuilder("# AI order vs rules order (A/B, source hidden)\n\n");
        StringBuilder key = new StringBuilder("# Key: which of A/B is the AI order\n\n");
        Random coin = new Random(20261022L);

        List<Shape> shapes = shapes();
        assertThat(shapes).hasSize(30);
        for (int i = 0; i < shapes.size(); i++) {
            Shape shape = shapes.get(i);
            UUID userId = seed(shape, new Random(1000L + i));

            AiInsightService.TextView story = aiInsightService.generateStory(userId, null);
            AiInsightService.LookAt ordered = aiInsightService.lookAt(userId, null);
            List<String> rulesOrder = new ArrayList<>();
            for (LookAtRowDto r : ordered.orderedByAi() ? rulesRows(userId) : ordered.rows()) rulesOrder.add(r.key());
            List<String> aiOrder = ordered.orderedByAi() ? ordered.rows().stream().map(LookAtRowDto::key).toList() : List.of();

            List<Map<String, Object>> whys = new ArrayList<>();
            for (LookAtRowDto row : ordered.rows()) {
                if (!"running-high".equals(row.kind()) || !Boolean.TRUE.equals(row.whyAvailable())) continue;
                AiInsightService.TextView why = aiInsightService.why(userId, row.key(), null);
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("row", row.key());
                w.put("writtenByAi", why.writtenByAi());
                w.put("text", why.text());
                whys.add(w);
            }

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("history", shape.name());
            line.put("storyStatus", story.status());
            line.put("storyWrittenByAi", story.writtenByAi());
            line.put("story", story.text());
            line.put("basis", story.basis());
            line.put("whys", whys);
            line.put("rulesOrder", rulesOrder);
            line.put("aiOrder", aiOrder);
            line.put("reasons", jdbc.queryForList(
                    "SELECT kind, subject, status, reason_code FROM ai_insight_texts WHERE user_id = ? ORDER BY created_at",
                    userId));
            lines.add(mapper.writeValueAsString(line));

            List<String> aiTop = aiOrder.stream().limit(3).toList();
            List<String> rulesTop = rulesOrder.stream().limit(3).toList();
            boolean aiIsA = coin.nextBoolean();
            sideBySide.append("## ").append(i + 1).append(". ").append(shape.name()).append("\n\n")
                    .append("A: ").append(aiIsA ? aiTop : rulesTop).append("\n\n")
                    .append("B: ").append(aiIsA ? rulesTop : aiTop).append("\n\n");
            key.append(i + 1).append(". ").append(shape.name()).append(": AI is ").append(aiIsA ? "A" : "B").append('\n');
        }

        Files.write(out.resolve("outputs.jsonl"), lines);
        Files.writeString(out.resolve("side-by-side.md"), sideBySide.toString());
        Files.writeString(out.resolve("side-by-side-key.md"), key.toString());
        assertThat(lines).hasSize(30);
    }

    private List<LookAtRowDto> rulesRows(UUID userId) {
        return lookAtService.rows(userId, (String) null);
    }

    @Autowired private LookAtService lookAtService;

    private UUID seed(Shape shape, Random random) {
        User u = new User();
        u.setEmail("ai-eval-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Sample User");
        UUID userId = userRepository.save(u).getId();
        subscriptionService.provisionFreeSubscription(userId);
        subscriptionService.changePlan(userId, "PLUS", "evaluation", userId);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("Sample Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(100000));
        UUID accountId = accountRepository.save(a).getId();

        String[] names = {"Food", "Groceries", "Fuel", "Shopping", "Travel"};
        List<Category> categories = new ArrayList<>();
        for (String name : names) categories.add(category(userId, name, true));
        if (shape.userCategory()) categories.add(category(userId, "Gym with Friend K", false));

        for (Category c : categories) {
            int usual = 1000 + random.nextInt(4000);
            for (int back = shape.priorMonths(); back >= 1; back--) {
                LocalDate day = month.minusMonths(back).atDay(3 + random.nextInt(20));
                spend(userId, accountId, usual + random.nextInt(300) - 150, day, c.getId(), "vpa:shop." + c.getName().toLowerCase().replace(' ', '.'));
            }
            int now = usual + random.nextInt(300) - 150;
            if (shape.spike() && c == categories.get(0)) now = usual * 2 + random.nextInt(1000);
            if (shape.falling() && c == categories.get(2)) now = usual / 2;
            if (shape.userCategory() && !c.isSystem() && shape.spike()) now = usual * 3;
            spend(userId, accountId, now, month.atDay(1 + random.nextInt(Math.max(1, Math.min(25, LocalDate.now(UserZone.DEFAULT).getDayOfMonth())))),
                    c.getId(), "vpa:shop." + c.getName().toLowerCase().replace(' ', '.'));
        }
        if (shape.oneOff()) {
            spend(userId, accountId, 25000 + random.nextInt(10000), month.atDay(1), categories.get(3).getId(), "vpa:newstore.n");
        }
        if (shape.shared()) {
            Category rent = category(userId, "Rent", true);
            for (int back = shape.priorMonths(); back >= 0; back--) {
                Transaction bill = spend(userId, accountId, 30000, month.minusMonths(back).atDay(1), rent.getId(), "vpa:owner.b");
                Transaction share = txn(userId, accountId, Transaction.Type.INCOME, 15000, month.minusMonths(back).atDay(1),
                        rent.getId(), "vpa:flatmate.a");
                shareLinkRepository.save(new ShareLink(userId, share.getId(), bill.getId(), BigDecimal.valueOf(15000)));
            }
        }
        if (shape.lent()) {
            Transaction lent = spend(userId, accountId, 4000, month.atDay(1), null, "vpa:friend.k");
            loanEntryRepository.save(new LoanEntry(userId, "vpa:friend.k", lent.getId(), LoanEntry.Direction.LENT,
                    BigDecimal.valueOf(4000)));
        }
        txn(userId, accountId, Transaction.Type.INCOME, 60000, month.atDay(1), null, "vpa:employer.e");
        return userId;
    }

    private Category category(UUID userId, String name, boolean system) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        c.setSystem(system);
        return categoryRepository.save(c);
    }

    private Transaction spend(UUID userId, UUID accountId, int amount, LocalDate date, UUID categoryId, String key) {
        return txn(userId, accountId, Transaction.Type.EXPENSE, amount, date, categoryId, key);
    }

    private Transaction txn(UUID userId, UUID accountId, Transaction.Type type, int amount, LocalDate date, UUID categoryId,
                            String key) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(Math.max(1, amount)));
        t.setTxnDate(date);
        t.setDescription("UPI " + key);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }
}
