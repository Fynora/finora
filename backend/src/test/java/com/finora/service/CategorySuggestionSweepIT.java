package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.BankActivityCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-check end to end: real Postgres, the real waterfall, the real category resolver. Survives
 * other classes' rows the same way CounterpartyBackfillSweepIT does: drains, and asserts only on
 * ids seeded here.
 */
class CategorySuggestionSweepIT extends AbstractIntegrationTest {

    // The bank's own interest credit in the shape the rules read ("Int.Pd" with the period after it).
    private static final String INTEREST = "1234567890:Int.Pd:01-03-2026 to 31-05-2026"; // synthetic-ok

    @Autowired private CategorySuggestionSweepService sweepService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;

    private UUID userId;
    private UUID accountId;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("suggestion-sweep-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();

        Category other = new Category();
        other.setUserId(userId);
        other.setName("Other");
        otherId = categoryRepository.save(other).getId();

        ReflectionTestUtils.setField(sweepService, "batchSize", 500);
    }

    @Test
    void aWaitingInterestCreditImportedBeforeTheRuleIsFiledAndLeavesTheQueue() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, true, false);

        drain();

        Transaction t = reload(id);
        Category category = categoryRepository.findById(t.getCategoryId()).orElseThrow();
        // The user had no such category: the re-check created it, as an import confirm would.
        assertThat(category.getName()).isEqualTo(BankActivityCategory.INTEREST_AND_CASHBACK);
        assertThat(category.getUserId()).isEqualTo(userId);
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isCategoryManuallySet()).isFalse();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
    }

    @Test
    void aRowTheUserChoseIsNeverTouched() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, true, true);

        drain();

        Transaction t = reload(id);
        assertThat(t.getCategoryId()).isEqualTo(otherId);
        assertThat(t.getSuggestionVersion()).isZero();
    }

    @Test
    void aRowNotWaitingIsNeverTouched() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, false, false);

        drain();

        assertThat(reload(id).getCategoryId()).isEqualTo(otherId);
    }

    @Test
    void aRowTheRulesStillCannotPlaceStaysWaitingAndIsNotReExamined() {
        UUID id = seed("UPI/REF96/UPI", Transaction.Type.EXPENSE, true, false); // synthetic-ok
        long versionBefore = reload(id).getVersion();

        drain();

        Transaction t = reload(id);
        assertThat(t.getCategoryId()).isEqualTo(otherId);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        assertThat(t.getVersion()).isEqualTo(versionBefore);
    }

    private void drain() {
        for (int pass = 0; pass < 20; pass++) {
            if (sweepService.sweep().drained()) return;
        }
        throw new AssertionError("Re-check did not drain in 20 passes -- the sweep is not making progress.");
    }

    private UUID seed(String description, Transaction.Type type, boolean waiting, boolean chosen) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(otherId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(812));
        t.setTxnType(type);
        t.setDescription(description);
        t.applyCounterpartyTyping(description);
        t.setDecisionSource(Transaction.DecisionSource.MERCHANT_DEFAULT);
        t.setNeedsCategoryReview(waiting);
        t.setCategoryManuallySet(chosen);
        return transactionRepository.save(t).getId();
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }
}
