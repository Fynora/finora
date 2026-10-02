package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.MerchantLearningEventWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shop-trade guess (ShopTradeCategory) confirmed untouched is not a human decision, so it must not
 * be learned. Merchants are grouped by the first word of the payee, and a small shop's first word
 * is often a common name: "SAMPLE MEDICAL" and "SAMPLE TRADERS" resolve to one merchant, so
 * learning the first shop's Health would hand it to the second, ahead of every rule.
 */
@TestPropertySource(properties = "app.learning.queue.enabled=false")
class ShopTradeGuessLearningIT extends AbstractIntegrationTest {

    private static final String MEDICAL_SHOP =
            "UPI-SAMPLE MEDICAL-Q000000000@YBL-YESB0XXXXXX-000000000001-PAYMENT FROM PHONE";
    private static final String OTHER_SHOP_SAME_FIRST_WORD =
            "UPI-SAMPLE TRADERS-Q000000001@YBL-YESB0XXXXXX-000000000002-PAYMENT FROM PHONE";

    @Autowired private ImportService importService;
    @Autowired private CategorizationService categorizationService;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantLearningEventRepository eventRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void anUntouchedTradeGuess_teachesNothing_soAnotherShopWithTheSameFirstWordIsNotGivenIt() throws Exception {
        User user = user();
        assertThat(categorizationService.suggestReadOnly(List.of(), user.getId(), MEDICAL_SHOP, null, null, null,
                Transaction.Type.EXPENSE, null).category())
                .as("precondition: the trade step names the medical shop").isEqualTo("Health");

        confirm(user, row(MEDICAL_SHOP, "Health"));
        drainUntilSettled(user);

        var suggestion = categorizationService.suggestReadOnly(List.of(), user.getId(), OTHER_SHOP_SAME_FIRST_WORD,
                null, null, null, Transaction.Type.EXPENSE, null);
        assertThat(suggestion.category()).isEqualTo("Other");
        assertThat(eventsFor(user)).isEmpty();
    }

    /** The user changing the guess during review is a real decision, and is learned as one. */
    @Test
    void aTradeGuessTheUserChanged_isLearned() throws Exception {
        User user = user();

        confirm(user, row(MEDICAL_SHOP, "Shopping"));

        assertThat(eventsFor(user)).hasSize(1);
    }

    private ConfirmedRow row(String description, String category) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal("54.00"), "EXPENSE",
                category, true, "rule", null, false, null, null);
    }

    private void confirm(User user, ConfirmedRow row) throws Exception {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Trade Guess IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);
        importService.confirm(user.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, List.of(row), account.getId(), null, null, null, null));
    }

    private User user() {
        User user = new User();
        user.setEmail("trade-guess-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Trade Guess IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private List<MerchantLearningEvent> eventsFor(User user) {
        return eventRepository.findAll().stream().filter(e -> e.getUserId().equals(user.getId())).toList();
    }

    /** See MerchantLearningImportIT.drainUntilSettled for why one pass is not enough in the full suite. */
    private void drainUntilSettled(User user) {
        for (int pass = 0; pass < 200; pass++) {
            boolean anyPending = eventsFor(user).stream()
                    .anyMatch(e -> e.getStatus() == MerchantLearningEvent.Status.PENDING);
            if (!anyPending) return;
            if (worker.drainOnce() == 0) return;
        }
    }
}
