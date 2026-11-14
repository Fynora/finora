package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V320: a payment out that V319 relabelled as the user's own learned answer, and that still waits
 * for review, leaves the review list -- the way a fresh import of it would read. A credit keeps its
 * flag (it may be what keeps its "what was it?" question asked), and so does every other label, a
 * category set by hand, and a learned row below 100. Flyway has already applied the file to the
 * empty test database, so it runs here again over rows shaped the way V319 left them. Every value
 * is synthetic.
 */
class V320ClearReviewOnUsersOwnChoiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private record Owner(UUID userId, UUID accountId) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("v320-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V320 IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return new Owner(user.getId(), accountRepository.save(account).getId());
    }

    /** A row with the given label and flag, made through the real service and then shaped by SQL. */
    private UUID row(Owner o, String type, String source, int confidence, boolean manual) {
        TransactionDto created = transactionService.create(o.userId(), new TransactionDto.CreateRequest(
                o.accountId(), "Pets", LocalDate.of(2026, 7, 1), "UPI/DR/666666666666/SAMPLE/HDFC/samplev320@okhdfc/UPI",
                new BigDecimal("250.00"), type, null, null));
        jdbc.update("""
                UPDATE transactions SET decision_source = ?, decision_confidence = ?,
                       needs_category_review = TRUE, category_manually_set = ? WHERE id = ?
                """, source, confidence, manual, created.id());
        return created.id();
    }

    private boolean flagged(UUID txnId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT needs_category_review FROM transactions WHERE id = ?", Boolean.class, txnId));
    }

    private void runV320() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                    "db/migration/V320__clear_review_on_users_own_choice.sql")));
        }
    }

    @Test
    void aPaymentOutRelabelledAsTheUsersChoice_leavesTheReviewList() throws Exception {
        Owner o = owner();
        UUID relabelled = row(o, "EXPENSE", "LEARNED_PATTERN", 100, false);
        assertThat(flagged(relabelled)).as("precondition").isTrue();

        runV320();

        assertThat(flagged(relabelled)).isFalse();
    }

    @Test
    void everythingElse_keepsItsFlag() throws Exception {
        Owner o = owner();
        // A credit: its flag may be what keeps its "what was it?" question asked.
        UUID credit = row(o, "INCOME", "LEARNED_PATTERN", 100, false);
        UUID setByHand = row(o, "EXPENSE", "LEARNED_PATTERN", 100, true);
        UUID learnedBelow100 = row(o, "EXPENSE", "LEARNED_PATTERN", 80, false);
        UUID aiGuess = row(o, "EXPENSE", "AI_FALLBACK", 30, false);
        UUID other = row(o, "EXPENSE", "MERCHANT_DEFAULT", 20, false);

        runV320();

        for (UUID id : new UUID[]{credit, setByHand, learnedBelow100, aiGuess, other}) {
            assertThat(flagged(id)).as("kept: %s", id).isTrue();
        }
    }

    @Test
    void runningItTwice_changesNothingMore() throws Exception {
        Owner o = owner();
        UUID relabelled = row(o, "EXPENSE", "LEARNED_PATTERN", 100, false);
        runV320();
        Object once = jdbc.queryForObject("SELECT updated_at FROM transactions WHERE id = ?", Object.class, relabelled);

        runV320();

        assertThat(flagged(relabelled)).isFalse();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM transactions WHERE id = ?", Object.class, relabelled))
                .isEqualTo(once);
    }
}
