package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
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
 * The same-day order repair against a real Postgres: the unique (statement, ordinal) index it has to
 * step around, the ledger's own tie-break (created_at, then id), the version bump and the audit
 * row. Every value is invented.
 *
 * <p>The repair reads every user's PDF statements and other tests leave rows in the shared tables,
 * so each test asserts only on the statement it seeded.
 */
class StatementRowOrderRepairSweepIT extends AbstractIntegrationTest {

    @Autowired private StatementRowOrderRepairSweepService sweepService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("row-order-repair-" + UUID.randomUUID() + "@example.com");
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

    /**
     * A newest-first statement saved the way the import used to save it: days oldest-first, each
     * day's rows still in print order. Print positions 1 (top, newest) to 5; true order 5, 4, 3, 2, 1.
     */
    private UUID savedReversed(String format) {
        UUID statement = statement(format);
        row(statement, 0, 5, "2026-09-01", "10000.00", Transaction.Type.INCOME, "35000.84");
        row(statement, 1, 3, "2026-09-29", "20000.00", Transaction.Type.INCOME, "35000.84");
        row(statement, 2, 4, "2026-09-29", "20000.00", Transaction.Type.EXPENSE, "15000.84");
        row(statement, 3, 1, "2026-09-30", "212.00", Transaction.Type.INCOME, "45212.84");
        row(statement, 4, 2, "2026-09-30", "10000.00", Transaction.Type.INCOME, "45000.84");
        return statement;
    }

    @Test
    void aNewestFirstStatementsDaysArePutInTrueOrder_asTheLedgerOrdersThem() {
        UUID statement = savedReversed("PDF");
        long versionsBefore = versionSum(statement);

        sweepService.run(true);

        assertThat(ledgerOrder(statement)).containsExactly(5, 4, 3, 2, 1);
        assertThat(ordinalOrder(statement)).containsExactly(5, 4, 3, 2, 1);
        assertThat(versionSum(statement)).isEqualTo(versionsBefore + 4);
        assertThat(jdbc.queryForObject("""
                SELECT (metadata ->> 'rowsMoved')::int FROM audit_logs
                WHERE entity_id = ? AND action = 'STATEMENT_ROW_ORDER_REPAIRED'
                """, Integer.class, statement)).isEqualTo(4);
    }

    @Test
    void aSecondRunMovesNothing() {
        UUID statement = savedReversed("PDF");
        sweepService.run(true);
        long versionsAfterFirst = versionSum(statement);

        sweepService.run(true);

        assertThat(ledgerOrder(statement)).containsExactly(5, 4, 3, 2, 1);
        assertThat(versionSum(statement)).isEqualTo(versionsAfterFirst);
    }

    @Test
    void anOldestFirstStatementIsLeftAlone() {
        UUID statement = statement("PDF");
        row(statement, 0, 1, "2026-09-01", "10000.00", Transaction.Type.INCOME, "35000.84");
        row(statement, 1, 2, "2026-09-29", "20000.00", Transaction.Type.EXPENSE, "15000.84");
        row(statement, 2, 3, "2026-09-29", "20000.00", Transaction.Type.INCOME, "35000.84");
        row(statement, 3, 4, "2026-09-30", "10000.00", Transaction.Type.INCOME, "45000.84");
        long versionsBefore = versionSum(statement);

        sweepService.run(true);

        assertThat(ledgerOrder(statement)).containsExactly(1, 2, 3, 4);
        assertThat(versionSum(statement)).isEqualTo(versionsBefore);
    }

    @Test
    void aStatementWithARowMissingItsPrintedPositionIsLeftAlone() {
        UUID statement = savedReversed("PDF");
        jdbc.update("UPDATE transactions SET source_row_position = NULL WHERE statement_import_id = ? AND source_row_position = 5",
                statement);
        long versionsBefore = versionSum(statement);

        sweepService.run(true);

        assertThat(versionSum(statement)).isEqualTo(versionsBefore);
    }

    @Test
    void aCsvStatementIsLeftAlone() {
        UUID statement = savedReversed("CSV");
        long versionsBefore = versionSum(statement);

        sweepService.run(true);

        assertThat(versionSum(statement)).isEqualTo(versionsBefore);
    }

    @Test
    void aDryRunCountsTheRowsAndWritesNothing() {
        UUID statement = savedReversed("PDF");
        long versionsBefore = versionSum(statement);

        StatementRowOrderRepairSweepService.Result result = sweepService.run(false);

        assertThat(result.rowsMoved()).isGreaterThanOrEqualTo(4);
        assertThat(ledgerOrder(statement)).containsExactly(5, 3, 4, 1, 2);
        assertThat(versionSum(statement)).isEqualTo(versionsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'STATEMENT_ROW_ORDER_REPAIRED'",
                Long.class, statement)).isZero();
    }

    @Test
    void aDeletedRowKeepsItsOrdinal_andTheLiveRowsAreRepairedAroundIt() {
        UUID statement = savedReversed("PDF");
        UUID deleted = row(statement, 5, 6, "2026-09-30", "1.00", Transaction.Type.EXPENSE, "45211.84");
        jdbc.update("UPDATE transactions SET deleted_at = now() WHERE id = ?", deleted);

        sweepService.run(true);

        assertThat(ledgerOrder(statement)).containsExactly(5, 4, 3, 2, 1);
        assertThat(jdbc.queryForObject("SELECT row_ordinal FROM transactions WHERE id = ?", Integer.class, deleted)).isEqualTo(5);
    }

    private UUID statement(String format) {
        StatementImport s = new StatementImport();
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName("PDF".equals(format) ? "statement.pdf" : "statement.csv");
        s.setSourceFormat(format);
        s.setFileContent(new byte[]{1});
        s.setContentHash("row-order-repair-it-" + UUID.randomUUID());
        return statementImportRepository.save(s).getId();
    }

    /** Saved one at a time, so created_at follows the ordinal, as an import's single saveAll leaves it. */
    private UUID row(UUID statement, int ordinal, int position, String date, String amount, Transaction.Type type,
                     String balanceAfter) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setStatementImportId(statement);
        t.setRowOrdinal(ordinal);
        t.setSourceRowPosition(position);
        t.setTxnDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setBalanceAfter(new BigDecimal(balanceAfter));
        t.setDescription("SAMPLE ROW " + position);
        UUID id = transactionRepository.saveAndFlush(t).getId();
        // A distinct, increasing save time per row, whatever the clock's resolution.
        jdbc.update("UPDATE transactions SET created_at = timestamptz '2026-10-05 20:15:00+00' + make_interval(secs => ?) WHERE id = ?",
                ordinal, id);
        return id;
    }

    /** Printed positions in the ledger's own order: date, then save time, then id. */
    private List<Integer> ledgerOrder(UUID statement) {
        return jdbc.queryForList("""
                SELECT source_row_position FROM transactions
                WHERE statement_import_id = ? AND deleted_at IS NULL ORDER BY txn_date, created_at, id
                """, Integer.class, statement);
    }

    private List<Integer> ordinalOrder(UUID statement) {
        return jdbc.queryForList("""
                SELECT source_row_position FROM transactions
                WHERE statement_import_id = ? AND deleted_at IS NULL ORDER BY row_ordinal
                """, Integer.class, statement);
    }

    private long versionSum(UUID statement) {
        return jdbc.queryForObject("SELECT coalesce(sum(version), 0) FROM transactions WHERE statement_import_id = ?",
                Long.class, statement);
    }
}
