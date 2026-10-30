package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The narration cleanup against a real Postgres: the candidate query, the guarded UPDATE, the
 * version bump and the audit row. Every narration and name is invented.
 *
 * <p>The sweep reads every user's rows, and other tests leave rows behind in the shared tables, so a
 * test runs it until its pass is complete and asserts only on the rows it seeded.
 */
class TransactionNarrationCleanupSweepIT extends AbstractIntegrationTest {

    private static final String STAMP = " Date: 25/06/2026 13:07:57";
    private static final String NEFT_IN = "NEFT_IN:25HDFCH00000000001HDFC0XXXXXX//HD FCH00000000001/SAMPLE PAYEE";

    @Autowired private TransactionNarrationCleanupSweepService sweepService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("narration-cleanup-" + UUID.randomUUID() + "@example.com");
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
    void theStampIsRemoved_theBrokenNameReRead_theVersionBumped_andTheChangeAudited() {
        UUID id = income(NEFT_IN + STAMP, "in 25hdfch00000000001hdfc0xxxxxx hd");
        long versionBefore = versionOf(id);

        runWholePass();

        Map<String, Object> row = row(id);
        assertThat(row.get("description")).isEqualTo(NEFT_IN);
        assertThat(row.get("merchant")).isEqualTo("sample payee");
        assertThat(versionOf(id)).isEqualTo(versionBefore + 1);
        assertThat(jdbc.queryForList("""
                SELECT (metadata ->> 'stampsRemoved')::int AS stamps, (metadata ->> 'namesReread')::int AS names
                FROM audit_logs WHERE user_id = ? AND action = 'TRANSACTION_NARRATIONS_CLEANED'
                """, userId))
                .singleElement()
                .satisfies(audit -> {
                    assertThat(audit.get("stamps")).isEqualTo(1);
                    assertThat(audit.get("names")).isEqualTo(1);
                });
    }

    @Test
    void aStampedRowWithACleanNameLosesOnlyTheStamp() {
        UUID id = expense("UPI/DR/100000000001/SAMPLE STORE/HDFC/samplestore@okhdfc" + STAMP, "sample store");

        runWholePass();

        assertThat(row(id).get("description")).isEqualTo("UPI/DR/100000000001/SAMPLE STORE/HDFC/samplestore@okhdfc");
        assertThat(row(id).get("merchant")).isEqualTo("sample store");
    }

    @Test
    void whatAPersonEditedIsNeverTouched() {
        UUID editedNarration = income(NEFT_IN + STAMP, "sample payee");
        mark(editedNarration, Transaction.EditableField.DESCRIPTION);
        UUID editedName = income(NEFT_IN, "in 25hdfch00000000001hdfc0xxxxxx hd");
        mark(editedName, Transaction.EditableField.MERCHANT);
        long narrationVersion = versionOf(editedNarration);
        long nameVersion = versionOf(editedName);

        runWholePass();

        assertThat(row(editedNarration).get("description")).isEqualTo(NEFT_IN + STAMP);
        assertThat(row(editedName).get("merchant")).isEqualTo("in 25hdfch00000000001hdfc0xxxxxx hd");
        assertThat(versionOf(editedNarration)).isEqualTo(narrationVersion);
        assertThat(versionOf(editedName)).isEqualTo(nameVersion);
    }

    @Test
    void aNarrationThatIsOnlyTheStamp_aMidNarrationDate_andACleanRowAreLeftAlone() {
        UUID onlyStamp = expense(STAMP.trim(), null);
        UUID midDate = expense("CHQ DEP Date: 25/06/2026 13:07:57 SAMPLE BRANCH", "chq dep");
        UUID clean = expense("UPI/DR/100000000001/SAMPLE STORE/HDFC/samplestore@okhdfc", "sample store");
        long onlyStampVersion = versionOf(onlyStamp);
        long midDateVersion = versionOf(midDate);
        long cleanVersion = versionOf(clean);

        runWholePass();

        assertThat(row(onlyStamp).get("description")).isEqualTo(STAMP.trim());
        assertThat(row(midDate).get("description")).isEqualTo("CHQ DEP Date: 25/06/2026 13:07:57 SAMPLE BRANCH");
        assertThat(versionOf(onlyStamp)).isEqualTo(onlyStampVersion);
        assertThat(versionOf(midDate)).isEqualTo(midDateVersion);
        assertThat(versionOf(clean)).isEqualTo(cleanVersion);
    }

    @Test
    void aDryRunCountsTheRowsAndWritesNothing() {
        UUID id = income(NEFT_IN + STAMP, "in 25hdfch00000000001hdfc0xxxxxx hd");
        long versionBefore = versionOf(id);

        TransactionNarrationCleanupSweepService.Result result = sweepService.dryRun();

        assertThat(result.stampsRemoved()).isGreaterThanOrEqualTo(1);
        assertThat(result.namesReread()).isGreaterThanOrEqualTo(1);
        assertThat(row(id).get("description")).isEqualTo(NEFT_IN + STAMP);
        assertThat(versionOf(id)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id = ? AND action = 'TRANSACTION_NARRATIONS_CLEANED'",
                Long.class, userId)).isZero();
    }

    @Test
    void aDeletedRowIsLeftAlone() {
        UUID id = income(NEFT_IN + STAMP, "in 25hdfch00000000001hdfc0xxxxxx hd");
        jdbc.update("UPDATE transactions SET deleted_at = now() WHERE id = ?", id);

        runWholePass();

        assertThat(row(id).get("description")).isEqualTo(NEFT_IN + STAMP);
    }

    private void runWholePass() {
        for (int i = 0; i < 10_000; i++) {
            if (sweepService.apply().drained()) return;
        }
        throw new AssertionError("the cleanup pass never completed");
    }

    private UUID income(String narration, String merchant) {
        return save(narration, merchant, Transaction.Type.INCOME);
    }

    private UUID expense(String narration, String merchant) {
        return save(narration, merchant, Transaction.Type.EXPENSE);
    }

    private UUID save(String narration, String merchant, Transaction.Type type) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.of(2026, 6, 20));
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(type);
        t.setDescription(narration);
        t.setMerchant(merchant);
        return transactionRepository.save(t).getId();
    }

    private void mark(UUID id, Transaction.EditableField field) {
        Transaction t = transactionRepository.findById(id).orElseThrow();
        t.markUserEdited(field);
        transactionRepository.save(t);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT description, merchant FROM transactions WHERE id = ?", id);
    }

    private long versionOf(UUID id) {
        return jdbc.queryForObject("SELECT version FROM transactions WHERE id = ?", Long.class, id);
    }
}
