package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An import that links a row to a merchant while another transaction deletes that merchant
 * (MerchantNobodyDetachSweepService, a discard) gets a new merchant instead of failing on the
 * foreign key. Real Postgres, two real transactions. Every name and narration is invented.
 */
class MerchantDeletedWhileLinkingIT extends AbstractIntegrationTest {

    @Autowired private MerchantNormalizationEngine engine;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("deleted-while-linking-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();
    }

    @Test
    void aMerchantMatchedByItsKeyAndDeletedMeanwhileIsReplaced_andTheRowIsStored() throws Exception {
        UUID doomed = merchant("Sampleshop Traders");

        UUID linked = linkWhileDeleting(doomed, "SAMPLESHOP TRADERS 100000000001");

        assertThat(linked).isNotEqualTo(doomed);
        assertThat(merchantRepository.findById(linked)).isPresent();
        assertThat(merchantRepository.findById(doomed)).isEmpty();
    }

    @Test
    void aMerchantFoundByItsAliasAndDeletedMeanwhileIsReplaced_andTheRowIsStored() throws Exception {
        UUID doomed = merchant("Sampleshop Traders");
        jdbc.update("INSERT INTO merchant_aliases (merchant_id, user_id, normalized_alias) VALUES (?, ?, 'sampleshop traders 100000000002')",
                doomed, userId);

        UUID linked = linkWhileDeleting(doomed, "SAMPLESHOP TRADERS 100000000002");

        assertThat(linked).isNotEqualTo(doomed);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM merchant_aliases WHERE user_id = ? AND normalized_alias = 'sampleshop traders 100000000002'",
                UUID.class, userId)).isEqualTo(linked);
    }

    /**
     * One transaction holds the merchant and deletes it, then waits before committing; meanwhile a
     * second resolves a narration that finds that merchant and stores a row on what it got back.
     */
    private UUID linkWhileDeleting(UUID doomed, String narration) throws Exception {
        CountDownLatch deleting = new CountDownLatch(1);
        Thread deleter = new Thread(() -> transactionTemplate.executeWithoutResult(tx -> {
            jdbc.queryForList("SELECT id FROM merchants WHERE id = ? FOR UPDATE", doomed);
            jdbc.update("DELETE FROM merchants WHERE id = ?", doomed);
            deleting.countDown();
            try { Thread.sleep(1_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        deleter.start();
        assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();

        UUID linked = transactionTemplate.execute(tx -> {
            Merchant merchant = engine.resolve(userId, narration).orElseThrow();
            Transaction t = new Transaction();
            t.setUserId(userId);
            t.setAccountId(accountId);
            t.setMerchantId(merchant.getId());
            t.setTxnDate(LocalDate.of(2026, 9, 15));
            t.setAmount(BigDecimal.valueOf(100));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setDescription(narration);
            transactionRepository.saveAndFlush(t);
            return merchant.getId();
        });
        deleter.join(10_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND merchant_id = ?",
                Long.class, userId, linked)).isEqualTo(1);
        return linked;
    }

    private UUID merchant(String name) {
        Merchant m = new Merchant();
        m.setUserId(userId);
        m.setCanonicalName(name);
        m.setLifecycleStatus(Merchant.Lifecycle.TEMPORARY);
        return merchantRepository.save(m).getId();
    }
}
