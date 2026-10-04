package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.InterestLabelRecheckSweepService;
import com.finora.util.CategoryRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V255's snapshot and InterestLabelRecheckSweepService, which give the rows V254 labelled
 * "interest" the label a fresh import gives them now. Flyway has already run V255 on the empty test
 * database, so its snapshot statement is run again here over rows as V254 left them, and the sweep
 * is then run until the queue is empty. Every description is synthetic.
 */
class InterestLabelRecheckIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InterestLabelRecheckSweepService sweepService;

    private Account account() {
        User user = new User();
        user.setEmail("interest-recheck-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Interest Recheck IT User");
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    /** A row as V254 left it: labelled "interest", version moved once. */
    private UUID labelledInterest(Account account, String description, String... userEdited) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, description, merchant, amount, txn_type,
                                          created_at, updated_at, version, reconciliation_status, source,
                                          counterparty_type, user_edited_fields)
                VALUES (?, ?, ?, DATE '2026-07-01', ?, 'interest', 12.34, 'INCOME', now(), now(), 1, 'OK', 'CSV_IMPORT',
                        ?, ?::varchar[])
                """, id, account.getUserId(), account.getId(), description,
                com.finora.util.CounterpartyTyping.of(description).type().name(), "{" + String.join(",", userEdited) + "}");
        return id;
    }

    /** A row as an older release left it: the label it was imported with, version untouched. */
    private UUID labelled(Account account, String description, String merchant) {
        UUID id = labelledInterest(account, description);
        jdbc.update("UPDATE transactions SET merchant = ?, version = 0 WHERE id = ?", merchant, id);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT merchant, version FROM transactions WHERE id = ?", id);
    }

    private boolean queued(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM interest_label_recheck WHERE transaction_id = ?)", Boolean.class, id));
    }

    /** V255's own INSERT, read from the migration file -- the CREATE TABLE before it has already run. */
    private void snapshot() throws Exception {
        String migration = new String(new ClassPathResource("db/migration/V255__interest_label_recheck.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String insert = migration.substring(migration.indexOf("INSERT INTO interest_label_recheck")).trim();
        jdbc.execute(insert.substring(0, insert.length() - 1) + " ON CONFLICT DO NOTHING");
    }

    private void drain() {
        for (int pass = 0; pass < 100; pass++) {
            if (sweepService.sweep().drained()) return;
        }
        throw new AssertionError("the interest label recheck never drained");
    }

    @Test
    void aNamedPayerOrAReversal_getsBackTheLabelAFreshImportGivesIt() throws Exception {
        Account account = account();
        UUID lender = labelledInterest(account, "NEFT CR-YESB0000000-SAMPLE FINANCE LTD-INTEREST PAID");
        UUID upiLender = labelledInterest(account, "UPI/CR/000000000000/SAMPLE CAPITAL/samplecap@ybl/interest paid");
        UUID reversal = labelledInterest(account, "INTEREST CR REVERSAL");
        UUID refund = labelledInterest(account, "INTEREST REFUND CR");

        snapshot();
        assertThat(queued(lender)).isTrue();
        drain();

        assertThat(row(lender)).containsEntry("merchant", "sample finance ltd").containsEntry("version", 2L);
        assertThat(row(upiLender)).containsEntry("merchant", "sample capital").containsEntry("version", 2L);
        assertThat(row(reversal)).containsEntry("merchant", CategoryRules.extractMerchantLabel("INTEREST CR REVERSAL"))
                .containsEntry("version", 2L);
        assertThat(row(refund)).containsEntry("merchant", CategoryRules.extractMerchantLabel("INTEREST REFUND CR"))
                .containsEntry("version", 2L);
        for (UUID id : new UUID[]{lender, upiLender, reversal, refund}) assertThat(queued(id)).isFalse();

        snapshot();
        drain();
        assertThat(row(lender)).as("a second run changes nothing").containsEntry("version", 2L);
    }

    @Test
    void aNewlyReadSpelling_getsTheInterestLabel() throws Exception {
        Account account = account();
        UUID credited = labelled(account, "INTEREST CREDITED 30-06-2026", "interest credited 30 06");
        UUID fd = labelled(account, "FD INTEREST 0000000000", "fd interest");
        UUID handTyped = labelled(account, "INTEREST PAYMENT", "Savings Interest");
        // Queued for its note, but its label differs from today's only because of how an older
        // release reduced narrations -- not this recheck's business.
        UUID person = labelled(account, "UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-INTEREST PAYMENT",
                "upi amit kumar");

        snapshot();
        assertThat(queued(credited)).isTrue();
        assertThat(queued(person)).isTrue();
        drain();

        assertThat(row(credited)).containsEntry("merchant", "interest").containsEntry("version", 1L);
        assertThat(row(fd)).containsEntry("merchant", "interest").containsEntry("version", 1L);
        assertThat(row(handTyped)).as("typed before edits were recorded").containsEntry("merchant", "Savings Interest")
                .containsEntry("version", 0L);
        assertThat(row(person)).containsEntry("merchant", "upi amit kumar").containsEntry("version", 0L);
    }

    @Test
    void theAccountsOwnInterest_keepsTheLabel_untouched() throws Exception {
        Account account = account();
        UUID daily = labelledInterest(account, "Interest Cr. for 03-Jan-2026");
        UUID quarterly = labelledInterest(account, "INTEREST PAID TILL 31-MAR-2026");
        UUID noPayee = labelledInterest(account, "MMT/IMPS/000000000000/INTEREST PAID/SAMPLE NBFC");

        snapshot();
        drain();

        for (UUID id : new UUID[]{daily, quarterly, noPayee}) {
            assertThat(row(id)).containsEntry("merchant", "interest").containsEntry("version", 1L);
            assertThat(queued(id)).isFalse();
        }
    }

    @Test
    void aLabelTheUserSet_isNeverOverwritten() throws Exception {
        Account account = account();
        UUID chosenBefore = labelledInterest(account, "NEFT CR-YESB0000000-SAMPLE FINANCE LTD-INTEREST PAID",
                Transaction.EditableField.MERCHANT.name());
        UUID chosenAfter = labelledInterest(account, "NEFT CR-YESB0000000-SAMPLE FINANCE LTD-INTEREST PAID");
        snapshot();
        assertThat(queued(chosenBefore)).as("a label the user set is never queued").isFalse();
        jdbc.update("UPDATE transactions SET merchant = 'my lender', user_edited_fields = '{MERCHANT}', version = version + 1 "
                + "WHERE id = ?", chosenAfter);

        drain();

        assertThat(row(chosenBefore)).containsEntry("merchant", "interest").containsEntry("version", 1L);
        assertThat(row(chosenAfter)).containsEntry("merchant", "my lender").containsEntry("version", 2L);
    }

    @Test
    void aQueuedRowWhoseTransactionIsDeleted_leavesTheQueueWithIt() throws Exception {
        Account account = account();
        UUID id = labelledInterest(account, "NEFT CR-YESB0000000-SAMPLE FINANCE LTD-INTEREST PAID");
        snapshot();

        jdbc.update("DELETE FROM transactions WHERE id = ?", id);

        assertThat(queued(id)).isFalse();
        drain();
    }

    /** The recheck is the import rule itself: a stored row ends with the label a fresh import gives it. */
    @Test
    void aStoredRowEndsWithTheLabelAFreshImportGivesIt() throws Exception {
        Account account = account();
        String[] descriptions = {"Interest Cr. for 03-Jan-2026", "INTEREST PAID TILL 31-MAR-2026", "CREDIT INTEREST",
                "SB INT CREDIT", "INTCR 01-2026", "SAVINGS INTEREST Q1", "Int.Cr-Jan", "SAVING A/C CREDIT INTEREST",
                "NEFT CR-YESB0000000-SAMPLE FINANCE LTD-INTEREST PAID", "MMT/IMPS/000000000000/INTEREST PAID/SAMPLE NBFC",
                "UPI/CR/000000000000/SAMPLE CAPITAL/samplecap@ybl/interest paid",
                "UPI/CR/000000000000/0000000000@ybl/interest paid", "INTEREST CR REVERSAL", "INTEREST REFUND CR",
                "INT CR REVERS"};
        Map<UUID, String> ids = new LinkedHashMap<>();
        for (String d : descriptions) ids.put(labelledInterest(account, d), d);

        snapshot();
        drain();

        long kept = ids.keySet().stream().filter(id -> "interest".equals(row(id).get("merchant"))).count();
        assertThat(kept).as("the comparison is not vacuous either way").isBetween(5L, (long) descriptions.length - 4);
        ids.forEach((id, d) -> assertThat(row(id).get("merchant")).as(d)
                .isEqualTo(CategoryRules.extractMerchantLabel(d, Transaction.Type.INCOME)));
    }
}
