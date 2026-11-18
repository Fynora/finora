package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.entity.Account;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact edges of the 30-day windows, read at a fixed moment far ahead of every other test's rows
 * so the counts are this test's alone. Each test rolls back, so those future rows never reach the
 * tests that read the newest events.
 */
@Transactional
class AdminLearningStatsWindowIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2040-06-15T12:00:00Z");
    private static final Instant LAST_START = NOW.minus(Duration.ofDays(30));
    private static final Instant PREVIOUS_START = NOW.minus(Duration.ofDays(60));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private AccountRepository accountRepository;

    private AdminLearningStatsService service() {
        return new AdminLearningStatsService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private User customer() {
        User user = new User();
        user.setEmail("learning-window-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Window IT Customer");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.saveAndFlush(user);
    }

    private UUID merchant(User owner) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName("Learning Window IT " + UUID.randomUUID());
        return merchantRepository.saveAndFlush(merchant).getId();
    }

    private void corrected(User owner, UUID merchantId, Instant at) {
        jdbc.update("INSERT INTO merchant_learning_audit (merchant_id, user_id, action, created_at) VALUES (?, ?, 'CORRECTED', ?)",
                merchantId, owner.getId(), OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private void transaction(Account account, Instant createdAt) {
        jdbc.update("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, amount, txn_type, created_at, updated_at,
                                          version, reconciliation_status, source, counterparty_type, decision_source)
                VALUES (?, ?, ?, DATE '2040-01-01', 10.00, 'EXPENSE', ?, now(), 1, 'OK', 'CSV_IMPORT', 'UNKNOWN', 'USER_RULE')
                """, UUID.randomUUID(), account.getUserId(), account.getId(), OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    @Test
    void anEventExactlyAtACutoffBelongsToTheNewerWindow() {
        User owner = customer();
        UUID merchant = merchant(owner);
        corrected(owner, merchant, LAST_START);                  // first moment of the last 30 days
        corrected(owner, merchant, LAST_START.minusMillis(1));   // last moment of the 30 days before
        corrected(owner, merchant, PREVIOUS_START);              // first moment of the 30 days before
        corrected(owner, merchant, PREVIOUS_START.minusMillis(1)); // too old for either

        LearningPlatformStatsDto stats = service().platformStats();

        assertThat(stats.last30Days().corrected()).isEqualTo(1);
        assertThat(stats.previous30Days().corrected()).isEqualTo(2);
    }

    @Test
    void aTransactionAddedExactlyAtTheCutoffCounts() {
        Account account = new Account();
        account.setUserId(customer().getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.saveAndFlush(account);
        transaction(account, LAST_START);
        transaction(account, LAST_START.minusMillis(1));

        LearningPlatformStatsDto stats = service().platformStats();

        assertThat(stats.categorySources()).hasSize(1);
        assertThat(stats.categorySources().get(0).source()).isEqualTo("USER_RULE");
        assertThat(stats.categorySources().get(0).transactions()).isEqualTo(1);
    }
}
