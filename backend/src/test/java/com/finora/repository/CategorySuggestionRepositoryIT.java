package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JPQL behind the suggestion re-check, against real Postgres: which rows it finds, and that
 * its guarded writes refuse a row the user has since chosen. Written to survive other classes'
 * rows (the discovery query is table-wide): every assertion is on an id seeded here.
 */
class CategorySuggestionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID userId;
    private UUID accountId;
    private UUID otherCategoryId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("suggestion-version-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();

        otherCategoryId = category("Other");
    }

    @Test
    void findsOnlyWaitingUnchosenGuessesBelowTheVersion() {
        UUID waitingDefault = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        UUID waitingPerson = seed(Transaction.DecisionSource.STRUCTURAL_P2P, true, false, (short) 0);
        UUID chosen = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, true, (short) 0);
        UUID notWaiting = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, false, false, (short) 0);
        UUID ruleMatched = seed(Transaction.DecisionSource.KEYWORD_MATCH, true, false, (short) 0);
        UUID alreadyStamped = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false,
                CategorizationService.SUGGESTION_VERSION);

        List<UUID> found = transactionRepository
                .findWaitingRowsBelowSuggestionVersion(CategorizationService.SUGGESTION_VERSION, PageRequest.of(0, 10_000))
                .stream().map(TransactionRepository.CategorySuggestionRow::getId).toList();

        assertThat(found).contains(waitingDefault, waitingPerson);
        assertThat(found).doesNotContain(chosen, notWaiting, ruleMatched, alreadyStamped);
    }

    @Test
    void applyWritesTheAnswerAndBumpsTheVersion() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        long versionBefore = reload(id).getVersion();
        UUID newCategory = category("Interest & Cashback");

        int written = inTx(() -> transactionRepository.applyCategorySuggestion(id, newCategory,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION));

        Transaction t = reload(id);
        assertThat(written).isEqualTo(1);
        assertThat(t.getCategoryId()).isEqualTo(newCategory);
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(t.getDecisionConfidence()).isEqualTo(70);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        // The phone learns of a change from version (ChangeStampService).
        assertThat(t.getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void applyRefusesARowTheUserHasSinceChosen() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, true, (short) 0);

        int written = inTx(() -> transactionRepository.applyCategorySuggestion(id, category("Dining"),
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION));

        assertThat(written).isZero();
        assertThat(reload(id).getCategoryId()).isEqualTo(otherCategoryId);
    }

    @Test
    void stampMovesOnlyTheSuggestionVersion() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        long versionBefore = reload(id).getVersion();

        int stamped = inTx(() -> transactionRepository.stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION));

        Transaction t = reload(id);
        assertThat(stamped).isEqualTo(1);
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        assertThat(t.getCategoryId()).isEqualTo(otherCategoryId);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.getVersion()).isEqualTo(versionBefore);
    }

    private UUID seed(Transaction.DecisionSource source, boolean waiting, boolean chosen, short suggestionVersion) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(otherCategoryId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(120));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("UPI/REF91/UPI"); // synthetic-ok
        t.setDecisionSource(source);
        t.setNeedsCategoryReview(waiting);
        t.setCategoryManuallySet(chosen);
        t.setSuggestionVersion(suggestionVersion);
        return transactionRepository.save(t).getId();
    }

    private UUID category(String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        return categoryRepository.save(c).getId();
    }

    private int inTx(Supplier<Integer> write) {
        return transactionTemplate.execute(status -> write.get());
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }
}
