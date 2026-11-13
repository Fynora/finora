package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.AiAuditLog;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.RowKind;
import com.finora.repository.AccountRepository;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionExplanationService;
import com.finora.transactions.TransactionService;
import com.finora.util.CounterpartyIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payee's category the user chose on an earlier import is served back as the user's own answer,
 * not as the AI's. The AI step of the waterfall reads {@code user_merchant_category_resolution},
 * and the import's confirm writes the user's choice into that same table ({@code pin}); before
 * this test every such row came back labelled {@code AI_FALLBACK} at 30, though no model was asked.
 * A row the model itself wrote must keep the AI label -- without that half, a fix that relabelled
 * every saved answer as the user's would pass.
 *
 * <p>Through the real services and Postgres, because the mislabel lives in how three classes hand
 * one row along (resolution service, fallback seam, categorization waterfall); the unit tests mock
 * the seam and could only re-assert what each caller passes on.
 */
class PinnedAnswerIsTheUsersIT extends AbstractIntegrationTest {

    /** A full UPI id: names one payee, so a choice for it is remembered. */
    private static final String PAYEE = "UPI/DR/444444444444/SAMPLEPETS/HDFC/samplepetspinnedit@okhdfc/UPI";

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private TransactionService transactionService;
    @Autowired private TransactionExplanationService explanationService;
    @Autowired private CategorizationService categorizationService;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AiAuditLogRepository aiAuditLogRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    private record Owner(UUID userId, UUID accountId) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("pinned-answer-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Pinned Answer IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return new Owner(user.getId(), accountRepository.save(account).getId());
    }

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private static String key() {
        return CounterpartyIdentity.keyOf(PAYEE);
    }

    private static byte[] statement(String date) {
        return ("Date,Description,Amount,Type\n" + date + "," + PAYEE + ",250.00,DEBIT\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The merchant's learned distribution is applied by a worker after commit; if it had run, a
     *  "learned" answer could come from it rather than from the pinned row this test is about. */
    private void assertNoMerchantLearningYet(Owner o) {
        assertThat(learningRepository.findByUserId(o.userId())).as("merchant learning not applied yet").isEmpty();
    }

    private StagedRow stageOne(Owner o, String date) throws Exception {
        List<StagedRow> staged = importService.parseAndStageAnyFormat(o.userId(), "CSV", "statement.csv",
                statement(date), null).rows();
        assertThat(staged).hasSize(1);
        return staged.get(0);
    }

    private static ConfirmedRow confirmed(StagedRow r, String category, String source) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), category, true,
                source, r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    private Transaction confirmOne(Owner o, StagedRow row, String category, String source) {
        ImportSession session = importSessionService.createSession(o.userId(), "statement.csv",
                statement(row.date().toString()), List.of(row), null);
        importService.confirmSession(o.userId(), new ConfirmRequest(session.getId(),
                List.of(confirmed(row, category, source)), o.accountId(), null, null, null, null));
        return transactionRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(o.userId()) && t.getTxnDate().equals(row.date()))
                .findFirst().orElseThrow();
    }

    /** The narration must produce a key that names one payee, or nothing is pinned and this proves nothing. */
    @Test
    void theNarrationNamesOnePayee() {
        assertThat(key()).startsWith("vpa:");
        assertThat(CounterpartyIdentity.identifiesOnePayee(key())).isTrue();
    }

    /**
     * First import: the user files the payee as "Pets" on the review screen. Second import of the
     * same payee: the preview, the confirmed row, its review flag and its explanation all say the
     * category came from the user's earlier choice.
     */
    @Test
    void importChoice_isServedBackAsTheUsersOwn() throws Exception {
        Owner o = owner();
        StagedRow first = stageOne(o, "2026-07-01");
        confirmOne(o, first, "Pets", CategorizationService.REVIEW_SOURCE);
        assertThat(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                o.userId(), key(), Transaction.Type.EXPENSE)).as("the choice was pinned").isPresent();
        assertNoMerchantLearningYet(o);

        StagedRow second = stageOne(o, "2026-08-01");

        assertThat(second.suggestedCategory()).isEqualTo("Pets");
        assertThat(second.categorySource()).isEqualTo("learned");
        assertThat(second.categoryConfidence()).isEqualTo(100);

        Transaction t = confirmOne(o, second, second.suggestedCategory(), second.categorySource());
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.LEARNED_PATTERN);
        assertThat(t.getDecisionConfidence()).isEqualTo(100);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        // Counts as the user's own choice where flows trust one (a Salary or repayment category).
        assertThat(FlowClassifier.categoryIsTheUsersChoice(t)).isTrue();
        assertThat(explanationService.explain(o.userId(), t.getId()).summary())
                .doesNotContain("Fyn")
                .contains("you");
    }

    /** Adding one by hand with no category goes through the live waterfall, which reads the same table. */
    @Test
    void handAddedRow_withNoCategory_getsTheUsersEarlierChoice() throws Exception {
        Owner o = owner();
        confirmOne(o, stageOne(o, "2026-07-01"), "Pets", CategorizationService.REVIEW_SOURCE);
        assertNoMerchantLearningYet(o);

        TransactionDto created = transactionService.create(o.userId(), new TransactionDto.CreateRequest(
                o.accountId(), null, LocalDate.of(2026, 8, 1), PAYEE, new BigDecimal("250.00"), "EXPENSE", null, null));

        Transaction t = transactionRepository.findById(created.id()).orElseThrow();
        assertThat(categoryName(t)).isEqualTo("Pets / LEARNED_PATTERN");
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.LEARNED_PATTERN);
        assertThat(t.isNeedsCategoryReview()).isFalse();
    }

    /** A row the model wrote ({@code insertIfAbsent}, resolve()'s own write) is still the AI's answer. */
    @Test
    void modelWrittenAnswer_keepsTheAiLabel() throws Exception {
        Owner o = owner();
        Category probe = categorizationService.resolveOrCreateCategory(o.userId(), "Pinned IT Model Pick");
        modelAnswered(o, probe);

        StagedRow staged = stageOne(o, "2026-07-01");

        assertThat(staged.suggestedCategory()).isEqualTo(probe.getName());
        assertThat(staged.categorySource()).isEqualTo(CategorizationService.AI_FALLBACK_SOURCE);
        assertThat(staged.categoryConfidence()).isEqualTo(ConfidenceEngine.INITIAL_AI_FALLBACK_CONFIDENCE);
    }

    /** The user correcting a model-written answer makes it theirs: pin() overwrites the row. */
    @Test
    void modelAnswerTheUserThenCorrected_isTheUsers() throws Exception {
        Owner o = owner();
        Category probe = categorizationService.resolveOrCreateCategory(o.userId(), "Pinned IT Model Pick");
        modelAnswered(o, probe);
        confirmOne(o, stageOne(o, "2026-07-01"), "Pets", CategorizationService.REVIEW_SOURCE);
        assertNoMerchantLearningYet(o);

        StagedRow second = stageOne(o, "2026-08-01");

        assertThat(second.suggestedCategory()).isEqualTo("Pets");
        assertThat(second.categorySource()).isEqualTo("learned");
    }

    /** What resolve() leaves behind when the model answers: its audit row, then the saved answer. */
    private void modelAnswered(Owner o, Category category) {
        AiAuditLog audit = new AiAuditLog();
        audit.setUserId(o.userId());
        audit.setModel("synthetic-model");
        audit.setPromptVersion("pinned-it");
        audit.setToolName("RESOLVE_CATEGORY");
        audit.setTokensIn(1);
        audit.setTokensOut(1);
        audit.setCost(BigDecimal.ZERO);
        audit.setLatencyMs(1);
        aiAuditLogRepository.save(audit);
        resolutionRepository.insertIfAbsent(o.userId(), key(), "EXPENSE", category.getId(), Instant.now());
    }

    private String categoryName(Transaction t) {
        return categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse(null)
                + " / " + t.getDecisionSource();
    }
}
