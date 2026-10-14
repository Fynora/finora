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
 * The merchant-name recheck against a real Postgres: the queue V268 creates, the conditional rename,
 * the audit row, and the twin check that reads the user's other merchants. Every name and narration
 * is invented.
 *
 * <p>The queue is this class's own table and no other test writes it, so it is emptied before each
 * test; every assertion is still on a merchant this test seeded.
 */
class MerchantRailNameSweepIT extends AbstractIntegrationTest {

    @Autowired private MerchantRailNameSweepService sweepService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM merchant_rail_name_recheck");
        User user = new User();
        user.setEmail("merchant-rail-name-" + UUID.randomUUID() + "@example.com");
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
    void anOldRuleNameIsRenamedToThePayeeItsNarrationsName_andTheRenameIsAudited() {
        Merchant old = merchant("Upi Dr Samplepa Hdfc", Merchant.Lifecycle.TEMPORARY);
        expense(old, "UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc");
        expense(old, "UPI/DR/200000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc");
        enqueue(old);

        MerchantRailNameSweepService.Result result = sweepService.apply();

        assertThat(nameOf(old)).isEqualTo("Samplepa");
        assertThat(result.renamed()).isEqualTo(1);
        assertThat(queued()).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT metadata ->> 'previousName' AS previous, metadata ->> 'newName' AS renamed
                FROM audit_logs WHERE entity_id = ? AND action = 'MERCHANT_NAME_RECHECKED'
                """, old.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("previous")).isEqualTo("Upi Dr Samplepa Hdfc");
                    assertThat(row.get("renamed")).isEqualTo("Samplepa");
                });
    }

    /** The owner's decision of 2026-10-14: a merchant that would share its new key with another
     *  merchant on the account is left for a person to merge, never merged or renamed here. */
    @Test
    void aMerchantWhoseNewNameWouldShareAKeyWithAnotherIsLeftAsItIs() {
        Merchant old = merchant("Upi Dr Sampletwo Hdfc", Merchant.Lifecycle.TEMPORARY);
        expense(old, "UPI/DR/100000000002/SAMPLETWO/HDFC/sampletwo@okhdfc");
        merchant("Sampletwo", Merchant.Lifecycle.TEMPORARY);
        enqueue(old);

        MerchantRailNameSweepService.Result result = sweepService.apply();

        assertThat(nameOf(old)).isEqualTo("Upi Dr Sampletwo Hdfc");
        assertThat(result.sharedKey()).isEqualTo(1);
        assertThat(queued()).isEmpty();
    }

    @Test
    void twoQueuedMerchantsThatWouldGetTheSameKeyLeaveTheSecondAsItIs() {
        Merchant first = merchant("Upi Dr Samplethree Hdfc", Merchant.Lifecycle.TEMPORARY);
        expense(first, "UPI/DR/100000000003/SAMPLETHREE/HDFC/samplethree@okhdfc");
        Merchant second = merchant("Upi Samplethree Samplethree Okhdfc", Merchant.Lifecycle.TEMPORARY);
        expense(second, "UPI-SAMPLETHREE-samplethree@okhdfc-HDFC0XXXXXX-100000000004-UPI");
        enqueue(first);
        enqueue(second);

        MerchantRailNameSweepService.Result result = sweepService.apply();

        assertThat(List.of(nameOf(first), nameOf(second)))
                .containsExactlyInAnyOrder("Samplethree", nameOf(first).equals("Samplethree")
                        ? "Upi Samplethree Samplethree Okhdfc" : "Upi Dr Samplethree Hdfc");
        assertThat(result.renamed()).isEqualTo(1);
        assertThat(result.sharedKey()).isEqualTo(1);
    }

    @Test
    void aMerchantThatIsNoLongerAnEngineGuessOrNoLongerOpensWithARailIsLeftAsItIs() {
        Merchant approved = merchant("Upi Dr Samplefour Hdfc", Merchant.Lifecycle.APPROVED);
        expense(approved, "UPI/DR/100000000005/SAMPLEFOUR/HDFC/samplefour@okhdfc");
        Merchant renamedByAPerson = merchant("Samplefive Stores", Merchant.Lifecycle.TEMPORARY);
        expense(renamedByAPerson, "UPI/DR/100000000006/SAMPLEFIVE/HDFC/samplefive@okhdfc");
        Merchant noTransactions = merchant("Upi Dr Samplesix Hdfc", Merchant.Lifecycle.TEMPORARY);
        enqueue(approved);
        enqueue(renamedByAPerson);
        enqueue(noTransactions);

        MerchantRailNameSweepService.Result result = sweepService.apply();

        assertThat(nameOf(approved)).isEqualTo("Upi Dr Samplefour Hdfc");
        assertThat(nameOf(renamedByAPerson)).isEqualTo("Samplefive Stores");
        assertThat(nameOf(noTransactions)).isEqualTo("Upi Dr Samplesix Hdfc");
        assertThat(result.renamed()).isZero();
        assertThat(result.unchanged()).isEqualTo(3);
        assertThat(queued()).isEmpty();
    }

    @Test
    void aDryRunCountsWhatItWouldDoAndWritesNothing() {
        Merchant old = merchant("Upi Dr Sampleseven Hdfc", Merchant.Lifecycle.TEMPORARY);
        expense(old, "UPI/DR/100000000007/SAMPLESEVEN/HDFC/sampleseven@okhdfc");
        Merchant twin = merchant("Upi Dr Sampleeight Hdfc", Merchant.Lifecycle.TEMPORARY);
        expense(twin, "UPI/DR/100000000008/SAMPLEEIGHT/HDFC/sampleeight@okhdfc");
        merchant("Sampleeight", Merchant.Lifecycle.TEMPORARY);
        enqueue(old);
        enqueue(twin);

        MerchantRailNameSweepService.Result result = sweepService.dryRun();

        assertThat(result.renamed()).isEqualTo(1);
        assertThat(result.sharedKey()).isEqualTo(1);
        assertThat(nameOf(old)).isEqualTo("Upi Dr Sampleseven Hdfc");
        assertThat(queued()).containsExactlyInAnyOrder(old.getId(), twin.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ?", Long.class, old.getId()))
                .isZero();
    }

    private Merchant merchant(String name, Merchant.Lifecycle lifecycle) {
        Merchant m = new Merchant();
        m.setUserId(userId);
        m.setCanonicalName(name);
        m.setLifecycleStatus(lifecycle);
        return merchantRepository.save(m);
    }

    private void expense(Merchant merchant, String narration) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setMerchantId(merchant.getId());
        t.setTxnDate(LocalDate.of(2026, 9, 15));
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(narration);
        transactionRepository.save(t);
    }

    private void enqueue(Merchant merchant) {
        jdbc.update("INSERT INTO merchant_rail_name_recheck (merchant_id) VALUES (?)", merchant.getId());
    }

    private List<UUID> queued() {
        return jdbc.queryForList("SELECT merchant_id FROM merchant_rail_name_recheck", UUID.class);
    }

    private String nameOf(Merchant merchant) {
        return jdbc.queryForObject("SELECT canonical_name FROM merchants WHERE id = ?", String.class, merchant.getId());
    }
}
