package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category the user changes on the import review screen is stored as the user's choice
 * (category_manually_set, decision source MANUAL), the same as one picked later from the Ledger; a
 * category they left as suggested stays the engine's. Through the real session confirm path against
 * real Postgres, because the decision is made by pairing the confirmed rows with the server's own
 * staged rows -- which only that path has.
 *
 * <p>Why it matters: a statement refresh keeps only the categories a user chose. A review-screen
 * choice stored as an engine guess would be overwritten by the next refresh.
 */
class ReviewChosenCategoryIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,SAMPLE GROCER,450.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private StagedRow staged(String description, String amount, String suggestedCategory, int rowPosition) {
        return new StagedRow(LocalDate.of(2026, 7, 1), description, new BigDecimal(amount), "EXPENSE",
                suggestedCategory, "rule", null, false, null, null, null, RowKind.TRANSACTION, null, null, null, 90)
                .withRowPosition(rowPosition);
    }

    private ConfirmedRow confirmed(StagedRow r, String category) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), category, true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void aCategoryChangedOnTheReviewScreen_isStoredAsTheUsersChoice_andAnUntouchedOneIsNot() {
        User user = new User();
        user.setEmail("review-category-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Review Category IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        StagedRow changed = staged("SAMPLE GROCER", "450.00", "Shopping", 0);
        StagedRow untouched = staged("SAMPLE CAFE", "120.00", "Dining", 1);
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(changed, untouched), null);

        importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(),
                List.of(confirmed(changed, "Groceries"), confirmed(untouched, "Dining")),
                account.getId(), null, null, null, null));

        List<Transaction> rows = transactionRepository.findByUserId(user.getId());
        Transaction grocer = rows.stream().filter(t -> "SAMPLE GROCER".equals(t.getDescription())).findFirst().orElseThrow();
        Transaction cafe = rows.stream().filter(t -> "SAMPLE CAFE".equals(t.getDescription())).findFirst().orElseThrow();

        assertThat(grocer.isCategoryManuallySet()).isTrue();
        assertThat(grocer.getDecisionSource()).isEqualTo(Transaction.DecisionSource.MANUAL);
        assertThat(grocer.isNeedsCategoryReview()).isFalse();

        assertThat(cafe.isCategoryManuallySet()).isFalse();
        assertThat(cafe.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
    }
}
