package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V304's account half: accounts of users deleted before the purge cleared the account's own name
 * and its full-number hash. Flyway has already applied it to the empty test database, so the file
 * runs here again over rows shaped the way the old purge left them. Every value is synthetic.
 */
class V304DeletedAccountNamesIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private User user(String status) {
        User user = new User();
        user.setEmail("v304-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V304 IT User");
        user.setStatus(status);
        return userRepository.save(user);
    }

    private UUID account(User owner, String name) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName(name);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setProductIdentityHash("v304-it-identity-" + UUID.randomUUID());
        return accountRepository.saveAndFlush(account).getId();
    }

    private Map<String, Object> rawAccount(UUID id) {
        return jdbc.queryForMap("SELECT name, product_identity_hash FROM accounts WHERE id = ?", id);
    }

    private void runV304() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                    "db/migration/V304__staged_statement_encryption_and_deleted_account_names.sql")));
        }
    }

    @Test
    void aDeletedUsersAccounts_loseTheirNameAndIdentityHash() throws Exception {
        User deleted = user(User.STATUS_DELETED);
        UUID salary = account(deleted, "Synthetic Salary Account");
        UUID card = account(deleted, "Synthetic Travel Card");
        // An account the user had removed before deleting their Fynora account is cleared too.
        jdbc.update("UPDATE accounts SET deleted_at = now() WHERE id = ?", card);

        runV304();

        for (UUID id : new UUID[]{salary, card}) {
            Map<String, Object> row = rawAccount(id);
            assertThat(row.get("name")).isEqualTo("Deleted account");
            assertThat(row.get("product_identity_hash")).isNull();
        }
    }

    @Test
    void liveAndPendingUsersAccounts_areUntouched() throws Exception {
        UUID active = account(user(User.STATUS_ACTIVE), "Synthetic Active Account");
        // Pending deletion is still the user's: the purge clears it when it runs, not this file.
        UUID pending = account(user(User.STATUS_PENDING_DELETION), "Synthetic Pending Account");

        runV304();

        assertThat(rawAccount(active).get("name")).isEqualTo("Synthetic Active Account");
        assertThat(rawAccount(active).get("product_identity_hash")).isNotNull();
        assertThat(rawAccount(pending).get("name")).isEqualTo("Synthetic Pending Account");
        assertThat(rawAccount(pending).get("product_identity_hash")).isNotNull();
    }

    @Test
    void runningItTwice_changesNothingMore() throws Exception {
        UUID id = account(user(User.STATUS_DELETED), "Synthetic Savings");
        runV304();
        Map<String, Object> once = rawAccount(id);

        runV304();

        assertThat(rawAccount(id)).isEqualTo(once);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'import_sessions' AND column_name = 'encryption_key_id'
                """, Integer.class)).isEqualTo(1);
    }
}
