package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
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
 * A category the user renamed or deleted while an import was waiting for review is not created
 * again by confirm. The staged rows still carry the name the engine gave them when the file was
 * read; confirm used to find no category by that name and make a new one. Such a row now waits in
 * "Other" for the user to sort. A name the user picked or typed on the review screen, and a
 * category printed in their own file, are still created as before.
 */
class ConfirmRemovedCategoryIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,SAMPLE,1.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId())).toList());
        createdUserIds.clear();
    }

    private StagedRow staged(String description, String category, String source, int position) {
        return new StagedRow(LocalDate.of(2026, 7, 1), description, new BigDecimal("120.00"), "EXPENSE",
                category, source, null, false, null, null, null, RowKind.TRANSACTION, null, null, null, 80)
                .withRowPosition(position);
    }

    private ConfirmedRow confirmed(StagedRow r, String category) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), category, true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void aCategoryRemovedWhileTheImportWaited_isNotCreatedAgain() {
        User user = new User();
        user.setEmail("confirm-removed-category-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirm Removed Category IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        Category snacks = new Category();
        snacks.setUserId(user.getId());
        snacks.setName("Snacks");
        snacks = categoryRepository.save(snacks);

        // Staged while "Snacks" existed: the engine filed this row there from what it had learned.
        StagedRow learned = staged("SAMPLE SNACK BAR", "Snacks", "learned", 0);
        // The file's own Category column -- a CSV the user (or their own export) wrote.
        StagedRow fromFile = staged("SAMPLE FUEL STATION", "Fuel", "file", 1);
        StagedRow typed = staged("SAMPLE BOOKSHOP", "Other", "default", 2);
        // A default category can't be renamed or deleted, so the engine naming one is never a
        // removed category (this test user was made without the seeded defaults).
        StagedRow keyword = staged("SAMPLE CAFE", "Dining", "rule", 3);
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(learned, fromFile, typed, keyword), null);

        // The user deletes "Snacks" (nothing else uses it yet) before confirming.
        categoryRepository.delete(snacks);

        importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(),
                List.of(confirmed(learned, "Snacks"), confirmed(fromFile, "Fuel"), confirmed(typed, "Books"),
                        confirmed(keyword, "Dining")),
                account.getId(), null, null, null, null));

        List<String> names = categoryRepository.findByUserId(user.getId()).stream().map(Category::getName).toList();
        assertThat(names).doesNotContain("Snacks").contains("Fuel", "Books", "Other", "Dining");

        List<Transaction> rows = transactionRepository.findByUserId(user.getId());
        Transaction snackBar = rows.stream().filter(t -> "SAMPLE SNACK BAR".equals(t.getDescription())).findFirst().orElseThrow();
        Category other = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(user.getId(), "Other").get(0);
        assertThat(snackBar.getCategoryId()).isEqualTo(other.getId());
        assertThat(snackBar.isNeedsCategoryReview()).as("waits for the user to sort it").isTrue();
        assertThat(snackBar.getDecisionSource()).isEqualTo(Transaction.DecisionSource.MERCHANT_DEFAULT);
        assertThat(snackBar.getDecisionConfidence()).isNull();
        assertThat(snackBar.isCategoryManuallySet()).isFalse();

        Transaction bookshop = rows.stream().filter(t -> "SAMPLE BOOKSHOP".equals(t.getDescription())).findFirst().orElseThrow();
        assertThat(bookshop.isCategoryManuallySet()).as("typed on the review screen: the user's choice").isTrue();
    }
}
