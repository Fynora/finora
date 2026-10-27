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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time detach against a real Postgres: the queue V294 creates, the conditional unlink, the
 * delete, the audit rows. Every name and narration is invented. The queue is this class's own table
 * and no other test writes it, so it is emptied before each test.
 */
class MerchantNobodyDetachSweepIT extends AbstractIntegrationTest {

    @Autowired private MerchantNobodyDetachSweepService sweepService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM merchant_nobody_detach");
        User user = new User();
        user.setEmail("merchant-nobody-detach-" + UUID.randomUUID() + "@example.com");
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
    void aMerchantHoldingOnlyRowsThatNameNobodyLosesThemAndIsRemoved_andBothAreAudited() {
        Merchant fake = merchant("UPI payment", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(fake, "UPI/RRN 100000000001/UPI");
        jdbc.update("INSERT INTO merchant_aliases (merchant_id, user_id, normalized_alias) VALUES (?, ?, 'upi rrn 100000000001 upi')",
                fake.getId(), userId);
        enqueue(fake);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isNull();
        assertThat(merchantRepository.findById(fake.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_aliases WHERE merchant_id = ?", Long.class, fake.getId())).isZero();
        assertThat(result.rowsDetached()).isEqualTo(1);
        assertThat(result.merchantsDeleted()).isEqualTo(1);
        assertThat(result.drained()).isTrue();
        assertThat(queued()).isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs WHERE entity_id = ?", String.class, fake.getId()))
                .containsExactlyInAnyOrder("MERCHANT_ROWS_DETACHED", "MERCHANT_DISCARDED");
    }

    @Test
    void aMerchantWithRowsThatNameAPayeeKeepsThemAndStays() {
        Merchant mixed = merchant("Upi Dr Samplepa Hdfc", Merchant.Lifecycle.TEMPORARY);
        UUID nobody = expense(mixed, "UPI/RRN 100000000002/UPI");
        UUID named = expense(mixed, "UPI/DR/100000000003/SAMPLEPA/HDFC/samplepayee21@okhdfc");
        enqueue(mixed);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(nobody)).isNull();
        assertThat(merchantOf(named)).isEqualTo(mixed.getId());
        assertThat(merchantRepository.findById(mixed.getId())).isPresent();
        assertThat(result.rowsDetached()).isEqualTo(1);
        assertThat(result.merchantsKept()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs WHERE entity_id = ?", String.class, mixed.getId()))
                .containsExactly("MERCHANT_ROWS_DETACHED");
    }

    @Test
    void aMerchantWithALearnedCategoryLosesItsNobodyRowsButIsKept() {
        Merchant learned = merchant("Atm Wdl Sampletown", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(learned, "ATM WDL 100000000004 SAMPLETOWN");
        jdbc.update("INSERT INTO merchant_category_learning (merchant_id, user_id, category_id) VALUES (?, ?, ?)",
                learned.getId(), userId, category());
        enqueue(learned);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isNull();
        assertThat(merchantRepository.findById(learned.getId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_category_learning WHERE merchant_id = ?", Long.class, learned.getId()))
                .isEqualTo(1);
        assertThat(result.merchantsKept()).isEqualTo(1);
    }

    @Test
    void aMerchantWithOnlyLearningHistoryIsKeptToo() {
        Merchant audited = merchant("NACH payment", Merchant.Lifecycle.TEMPORARY);
        expense(audited, "NACH CHQ 004471");
        jdbc.update("INSERT INTO merchant_learning_audit (merchant_id, user_id, action, new_category_id) VALUES (?, ?, 'UNDONE', ?)",
                audited.getId(), userId, category());
        enqueue(audited);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantRepository.findById(audited.getId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_learning_audit WHERE merchant_id = ?", Long.class, audited.getId()))
                .isEqualTo(1);
        assertThat(result.merchantsKept()).isEqualTo(1);
    }

    @Test
    void aRowInTheBinIsDetachedToo_soTheMerchantCanGo() {
        Merchant fake = merchant("UPI payment", Merchant.Lifecycle.TEMPORARY);
        UUID live = expense(fake, "UPI/RRN 100000000008/UPI");
        UUID binned = expense(fake, "UPI/RRN 100000000009/UPI");
        jdbc.update("UPDATE transactions SET deleted_at = now() WHERE id = ?", binned);
        enqueue(fake);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(live)).isNull();
        assertThat(merchantOf(binned)).isNull();
        assertThat(merchantRepository.findById(fake.getId())).isEmpty();
        assertThat(result.rowsDetached()).isEqualTo(2);
    }

    @Test
    void aMerchantWhoseRowsAllNameAPayeeIsLeftAsItIs() {
        Merchant real = merchant("Upi Samplestore", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(real, "UPI SAMPLE STORE 100000000010");
        enqueue(real);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isEqualTo(real.getId());
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(queued()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ?", Long.class, real.getId())).isZero();
    }

    @Test
    void anApprovedMerchantIsLeftAsItIs() {
        Merchant approved = merchant("UPI payment", Merchant.Lifecycle.APPROVED);
        UUID row = expense(approved, "UPI/RRN 100000000005/UPI");
        enqueue(approved);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isEqualTo(approved.getId());
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(queued()).isEmpty();
    }

    @Test
    void aMerchantAnImportIsHoldingIsLeftQueued_andTheSweepDoesNotWaitForIt() throws Exception {
        Merchant held = merchant("UPI payment", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(held, "UPI/RRN 100000000020/UPI");
        enqueue(held);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        // Another transaction holds the merchant the way an import linking a row to it does.
        Thread importLike = new Thread(() -> transactionTemplate.executeWithoutResult(tx -> {
            merchantRepository.lockForLink(held.getId(), userId);
            locked.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        importLike.start();
        try {
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            MerchantNobodyDetachSweepService.Result result = sweepService.apply();
            long tookMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(result.busy()).isEqualTo(1);
            assertThat(result.drained()).isFalse();
            assertThat(tookMs).isLessThan(5_000);
            assertThat(merchantOf(row)).isEqualTo(held.getId());
            assertThat(queued()).containsExactly(held.getId());
        } finally {
            release.countDown();
            importLike.join(10_000);
        }

        // Released: the next pass finishes the job.
        assertThat(sweepService.apply().merchantsDeleted()).isEqualTo(1);
        assertThat(merchantOf(row)).isNull();
    }

    @Test
    void aQueueLongerThanOneBatchIsWorkedThroughOverSeveralPasses() {
        // One more than the default batch of 25.
        for (int i = 0; i < 26; i++) {
            Merchant fake = merchant("UPI payment", Merchant.Lifecycle.TEMPORARY);
            expense(fake, "UPI/RRN " + (100000000100L + i) + "/UPI");
            enqueue(fake);
        }

        MerchantNobodyDetachSweepService.Result first = sweepService.apply();
        assertThat(first.merchantsDeleted()).isEqualTo(25);
        assertThat(first.drained()).isFalse();
        assertThat(queued()).hasSize(1);

        MerchantNobodyDetachSweepService.Result second = sweepService.apply();
        assertThat(second.merchantsDeleted()).isEqualTo(1);
        assertThat(second.drained()).isTrue();
        assertThat(queued()).isEmpty();
    }

    @Test
    void aDryRunCountsWhatItWouldDoAndWritesNothing() {
        Merchant fake = merchant("Atw 512345xxxxxx1234 S1anbg12 Sampletown", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(fake, "ATW-512345XXXXXX1234-S1ANBG12-SAMPLETOWN");
        Merchant learned = merchant("Atm Wdl Sampletown", Merchant.Lifecycle.TEMPORARY);
        expense(learned, "ATM WDL 100000000011 SAMPLETOWN");
        jdbc.update("INSERT INTO merchant_category_learning (merchant_id, user_id, category_id) VALUES (?, ?, ?)",
                learned.getId(), userId, category());
        enqueue(fake);
        enqueue(learned);

        MerchantNobodyDetachSweepService.Result result = sweepService.dryRun();

        assertThat(result.rowsDetached()).isEqualTo(2);
        assertThat(result.merchantsDeleted()).isEqualTo(1);
        assertThat(result.merchantsKept()).isEqualTo(1);
        assertThat(merchantOf(row)).isEqualTo(fake.getId());
        assertThat(queued()).containsExactlyInAnyOrder(fake.getId(), learned.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id IN (?, ?)", Long.class,
                fake.getId(), learned.getId())).isZero();
    }

    private Merchant merchant(String name, Merchant.Lifecycle lifecycle) {
        Merchant m = new Merchant();
        m.setUserId(userId);
        m.setCanonicalName(name);
        m.setLifecycleStatus(lifecycle);
        return merchantRepository.save(m);
    }

    private UUID category() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, user_id, name, is_system, icon, color) VALUES (?, ?, ?, false, 'tag', '#000000')",
                id, userId, "Sample Category " + id);
        return id;
    }

    private UUID expense(Merchant merchant, String narration) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setMerchantId(merchant.getId());
        t.setTxnDate(LocalDate.of(2026, 9, 15));
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(narration);
        return transactionRepository.save(t).getId();
    }

    private void enqueue(Merchant merchant) {
        jdbc.update("INSERT INTO merchant_nobody_detach (merchant_id) VALUES (?)", merchant.getId());
    }

    private List<UUID> queued() {
        return jdbc.queryForList("SELECT merchant_id FROM merchant_nobody_detach", UUID.class);
    }

    private UUID merchantOf(UUID transactionId) {
        return jdbc.queryForObject("SELECT merchant_id FROM transactions WHERE id = ?", UUID.class, transactionId);
    }
}
