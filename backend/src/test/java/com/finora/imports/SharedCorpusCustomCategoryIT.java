package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.SharedCorpusService;
import com.finora.util.CounterpartyTyping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category one user made for themselves never reaches another user's account through the shared
 * corpus. Through the real session confirm path against real Postgres: three users each filed one
 * shop under their own "Quick Bites" on the review screen, the corpus made it the shop's trusted
 * answer, and a fourth user who never had that category was suggested it and got it created by
 * confirm.
 */
class SharedCorpusCustomCategoryIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategorizationService categorizationService;
    @Autowired private CounterpartyCategoryObservationRepository observationRepository;
    @Autowired private SharedMerchantCategoryRepository corpusRepository;

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,SAMPLE,1.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);
    private static final BigDecimal AMOUNT = new BigDecimal("250.00");

    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void removeSharedRows() {
        // Both corpus tables are shared by every IT -- see it-suite-shared-global-tables.
        for (String key : keys) {
            observationRepository.deleteAll(observationRepository.findByCounterpartyKeyAndDirection(key, Transaction.Type.EXPENSE));
            corpusRepository.findByCounterpartyKeyAndDirection(key, Transaction.Type.EXPENSE).ifPresent(corpusRepository::delete);
        }
        keys.clear();
    }

    /** A shop narration typed BUSINESS under a full UPI id, unique per run. */
    private String shopNarration() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String description = "UPI/ACMEZOLT TRADERS/ACMEZOLT" + suffix.toUpperCase() + "@YBL/0000000000@PTAXIS";
        CounterpartyTyping typing = CounterpartyTyping.of(description);
        assertThat(SharedCorpusService.isEligible(typing.key(), typing.type())).as("a corpus-eligible shop").isTrue();
        keys.add(typing.key());
        return description;
    }

    private User user(String tag) {
        User u = new User();
        u.setEmail("corpus-custom-" + tag + "-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Corpus Custom " + tag);
        u.setPhoneVerified(true);
        return userRepository.save(u);
    }

    private void importOne(User u, String description, String stagedCategory, String stagedSource, String chosen) {
        Account a = new Account();
        a.setUserId(u.getId());
        a.setName("Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        a = accountRepository.save(a);
        StagedRow row = new StagedRow(LocalDate.of(2026, 7, 1), description, AMOUNT, "EXPENSE",
                stagedCategory, stagedSource, null, false, null, null, null, RowKind.TRANSACTION, null, null, null, 90)
                .withRowPosition(0);
        ImportSession s = importSessionService.createSession(u.getId(), "statement.csv", FILE, List.of(row), null);
        ConfirmedRow confirmed = new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(), chosen, true,
                row.categorySource(), row.ruleId(), false, null, null, false, row.categoryConfidence(), row.rowPosition());
        importService.confirmSession(u.getId(), new ConfirmRequest(s.getId(), List.of(confirmed), a.getId(),
                null, null, null, null));
    }

    private CategorizationService.Suggestion suggestionFor(User u, String description) {
        return categorizationService.suggestReadOnly(categorizationService.ruleSetFor(u.getId()), u.getId(),
                description, AMOUNT, null, null, Transaction.Type.EXPENSE, null);
    }

    private List<String> categoryNames(User u) {
        return categoryRepository.findByUserId(u.getId()).stream().map(Category::getName).toList();
    }

    @Test
    void threeUsersFilingAShopUnderTheirOwnCategory_neverGiveThatCategoryToAFourth() {
        String shop = shopNarration();
        for (int i = 0; i < 3; i++) {
            // The engine said Other; each user changed it on the review screen to a name of their own.
            importOne(user("voter" + i), shop, "Other", "default", "Quick Bites");
        }
        String key = CounterpartyTyping.of(shop).key();
        assertThat(observationRepository.findByCounterpartyKeyAndDirection(key, Transaction.Type.EXPENSE)).isEmpty();
        assertThat(corpusRepository.findByCounterpartyKeyAndDirection(key, Transaction.Type.EXPENSE)).isEmpty();

        User fourth = user("fourth");
        CategorizationService.Suggestion s = suggestionFor(fourth, shop);
        assertThat(s.category()).isNotEqualTo("Quick Bites");
        assertThat(s.source()).isNotEqualTo("shared_corpus");
        // The app sends the staged suggestion back unchanged when the user does not touch it.
        importOne(fourth, shop, s.category(), s.source(), s.category());
        assertThat(categoryNames(fourth)).doesNotContain("Quick Bites");
    }

    @Test
    void aTrustedRowPromotedBeforeTheLimit_isNotSuggested() {
        String shop = shopNarration();
        SharedMerchantCategory row = new SharedMerchantCategory();
        row.setCounterpartyKey(CounterpartyTyping.of(shop).key());
        row.setDirection(Transaction.Type.EXPENSE);
        row.setStatus(SharedMerchantCategory.Status.TRUSTED);
        row.setCategory("Quick Bites");
        row.setCategoryDistribution(Map.of("Quick Bites", BigDecimal.ONE));
        row.setDistinctUserCount(3);
        row.setPromotedAt(Instant.now());
        row.setLastRecomputedAt(Instant.now());
        corpusRepository.save(row);

        User reader = user("reader");
        assertThat(suggestionFor(reader, shop).category()).isNotEqualTo("Quick Bites");
    }

    @Test
    void aDefaultCategoryChosenByThreeUsers_isStillSharedAsBefore() {
        String shop = shopNarration();
        for (int i = 0; i < 3; i++) {
            importOne(user("dining" + i), shop, "Other", "default", "Dining");
        }
        CategorizationService.Suggestion s = suggestionFor(user("dining-reader"), shop);
        assertThat(s.category()).isEqualTo("Dining");
        assertThat(s.source()).isEqualTo("shared_corpus");
    }
}
