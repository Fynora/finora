package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.ChatMessage;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.ChatMessageRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fyn Phase 4's evaluation benchmark -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
 * §7 item 5. The plan makes this a <b>hard gate before Phase 4 reaches real users</b>, not a
 * nice-to-have: "a green test suite is never proof of correctness for financial output," and an
 * LLM composing sentences about a user's real balance is exactly the output that needs value-level
 * verification.
 *
 * <p><b>This class has not been run against a live model as of this commit, and cannot be --
 * there is no {@code ANTHROPIC_API_KEY} configured in this environment.</b> Say so plainly rather
 * than claiming the gate is cleared: {@link EnabledIfEnvironmentVariable} skips this entire class
 * whenever that key is absent, which is true in this session, in CI, and in every environment
 * until Sid provisions a real key. The 100 prompts in {@code fyn-chat-evaluation-prompts.json}
 * (plan §7 item 5's "100+ representative prompts") are real and reviewable now; the actual
 * evaluation run -- and the judgment call of what pass rate is acceptable -- is not.
 *
 * <p><b>Deliberately does not assert a pass-rate threshold.</b> Inventing a number ("must pass
 * 90%") with zero real runs to justify it would be exactly the guessing this repo's own standing
 * rule forbids. This test always completes successfully as a JUnit test as long as every prompt
 * executed without an exception; the actual pass/fail judgment per prompt is logged for a human to
 * read. Once Sid has run this for real and reviewed the failures, a specific threshold belongs
 * here -- as a decision made from evidence, not before.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class FynChatEvaluationIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FynChatEvaluationIT.class);

    @Autowired private FynChatOrchestrationService orchestrationService;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private BudgetRepository budgetRepository;

    private UUID userId;

    private record EvalPrompt(String category, String prompt, String expectedTool, Boolean expectDecline) {}

    /** A minimal but real dataset -- one account, a handful of Dining/Groceries transactions this
     *  month, and a Dining budget already exceeded -- just enough for every tool in the benchmark
     *  to have a genuine, non-empty answer to give. Not a realistic full corpus; that's more than
     *  this evaluation's tool-selection/product-contract rubric needs. */
    @BeforeEach
    void seedRealisticData() {
        User user = new User();
        user.setEmail("fyn-eval-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Fyn Evaluation User");
        user = userRepository.save(user);
        userId = user.getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Evaluation Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("52340.00"));
        accountRepository.save(account);

        Category dining = new Category();
        dining.setUserId(userId);
        dining.setName("Dining");
        dining = categoryRepository.save(dining);

        Category groceries = new Category();
        groceries.setUserId(userId);
        groceries.setName("Groceries");
        groceries = categoryRepository.save(groceries);

        LocalDate today = LocalDate.now();
        seedTransaction(account.getId(), dining.getId(), new BigDecimal("1500.00"), today.minusDays(2));
        seedTransaction(account.getId(), dining.getId(), new BigDecimal("2700.00"), today.minusDays(5));
        seedTransaction(account.getId(), groceries.getId(), new BigDecimal("3200.00"), today.minusDays(1));

        Budget diningBudget = new Budget();
        diningBudget.setUserId(userId);
        diningBudget.setCategoryId(dining.getId());
        diningBudget.setMonthlyLimit(new BigDecimal("3000.00")); // already exceeded by the two Dining txns above
        budgetRepository.save(diningBudget);
    }

    private void seedTransaction(UUID accountId, UUID categoryId, BigDecimal amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Evaluation seed transaction");
        t.setSource(Transaction.Source.MANUAL);
        transactionRepository.save(t);
    }

    @Test
    void runsTheBenchmarkAndReportsToolSelectionAccuracy() throws Exception {
        List<EvalPrompt> prompts = loadPrompts();
        assertThat(prompts).hasSizeGreaterThanOrEqualTo(100);

        int correct = 0;
        int total = 0;
        Map<String, int[]> byCategory = new java.util.TreeMap<>(); // [correct, total]

        for (EvalPrompt p : prompts) {
            total++;
            byCategory.computeIfAbsent(p.category(), k -> new int[2])[1]++;
            boolean ok;
            try {
                var result = orchestrationService.sendMessage(userId, null, p.prompt());
                ok = evaluate(p, result.conversationId());
            } catch (Exception e) {
                log.warn("Fyn eval FAILED (exception) [{}] \"{}\": {}", p.category(), p.prompt(), e.toString());
                ok = false;
            }
            if (ok) {
                correct++;
                byCategory.get(p.category())[0]++;
            }
        }

        log.info("Fyn chat evaluation: {}/{} ({}%) overall", correct, total,
                Math.round(100.0 * correct / total));
        byCategory.forEach((category, counts) -> log.info("  {}: {}/{}", category, counts[0], counts[1]));

        // Deliberately no threshold assertion -- see this class's own doc comment for why.
    }

    /** Structural checks only -- this cannot judge prose quality, only whether the model picked
     *  the right tool (or correctly picked none, for a plain greeting or an advice request). */
    private boolean evaluate(EvalPrompt p, UUID conversationId) {
        List<ChatMessage> messages = chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        ChatMessage lastAssistantMessage = messages.stream()
                .filter(m -> ChatMessage.ROLE_ASSISTANT.equals(m.getRole()))
                .reduce((a, b) -> b)
                .orElse(null);
        if (lastAssistantMessage == null || lastAssistantMessage.getContent().isBlank()) {
            return false;
        }

        if (Boolean.TRUE.equals(p.expectDecline())) {
            String lower = lastAssistantMessage.getContent().toLowerCase(java.util.Locale.ROOT);
            boolean seemsToDecline = lower.contains("advisor") || lower.contains("can't recommend")
                    || lower.contains("cannot recommend") || lower.contains("not able to recommend")
                    || lower.contains("don't give") || lower.contains("do not give")
                    || lower.contains("not a financial advisor");
            if (!seemsToDecline) {
                log.warn("Fyn eval: expected a decline for \"{}\", got: {}", p.prompt(),
                        lastAssistantMessage.getContent());
            }
            return seemsToDecline;
        }

        if (p.expectedTool() == null) {
            return true; // no specific tool expected -- non-blank reply is enough
        }

        @SuppressWarnings("unchecked")
        List<String> toolsUsed = lastAssistantMessage.getToolCallsJson() == null
                ? List.of()
                : (List<String>) lastAssistantMessage.getToolCallsJson().getOrDefault("tools", List.of());
        boolean usedExpectedTool = toolsUsed.contains(p.expectedTool());
        if (!usedExpectedTool) {
            log.warn("Fyn eval: expected tool {} for \"{}\", got tools {}", p.expectedTool(), p.prompt(), toolsUsed);
        }
        return usedExpectedTool;
    }

    private List<EvalPrompt> loadPrompts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("fyn-chat-evaluation-prompts.json")) {
            return mapper.readValue(in, mapper.getTypeFactory()
                    .constructCollectionType(List.class, EvalPrompt.class));
        }
    }
}
