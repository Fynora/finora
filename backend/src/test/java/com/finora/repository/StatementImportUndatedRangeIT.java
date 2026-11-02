package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StatementImportRepository#findUndatedStatementRanges}: for statements that print no period
 * (a CSV export, almost always), the earliest and latest date of their own transactions. Against
 * real Postgres, because the JPQL joins two entities and groups -- a mock proves nothing about it.
 * Every name and amount here is invented.
 */
class StatementImportUndatedRangeIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;

    private static final LocalDate THROUGH = LocalDate.of(2026, 11, 2);

    private UUID userId;
    private UUID accountId;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        userId = newUser();
        accountId = newAccount(userId);
        Category category = new Category();
        category.setUserId(userId);
        category.setName("Groceries");
        categoryId = categoryRepository.save(category).getId();
    }

    private UUID newUser() {
        User user = new User();
        user.setEmail("undated-range-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Undated Range IT User");
        return userRepository.save(user).getId();
    }

    private UUID newAccount(UUID owner) {
        Account account = new Account();
        account.setUserId(owner);
        account.setName("Sample Bank Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        return accountRepository.save(account).getId();
    }

    private StatementImport statement(UUID owner, UUID account, LocalDate periodStart, LocalDate periodEnd) {
        StatementImport s = new StatementImport();
        s.setUserId(owner);
        s.setAccountId(account);
        s.setFileName("sample.csv");
        s.setSourceFormat("CSV");
        s.setFileContent(new byte[]{1});
        s.setContentHash("undated-range-it-" + UUID.randomUUID());
        s.setStatementPeriodStart(periodStart);
        s.setStatementPeriodEnd(periodEnd);
        return statementImportRepository.save(s);
    }

    private void txn(UUID owner, UUID account, UUID statementId, LocalDate date) {
        Transaction t = new Transaction();
        t.setUserId(owner);
        t.setAccountId(account);
        t.setCategoryId(categoryId);
        t.setTxnDate(date);
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("SAMPLE MART " + UUID.randomUUID());
        t.setSource(Transaction.Source.CSV_IMPORT);
        t.setStatementImportId(statementId);
        transactionRepository.save(t);
    }

    @Test
    @Transactional
    void givesAnUndatedStatementTheRangeOfItsOwnTransactions() {
        StatementImport undated = statement(userId, accountId, null, null);
        txn(userId, accountId, undated.getId(), LocalDate.of(2026, 10, 15));
        txn(userId, accountId, undated.getId(), LocalDate.of(2026, 10, 5));
        txn(userId, accountId, undated.getId(), LocalDate.of(2026, 10, 9));

        List<StatementImportRepository.UndatedRange> ranges =
                statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH);

        assertThat(ranges).singleElement().satisfies(r -> {
            assertThat(r.getStatementImportId()).isEqualTo(undated.getId());
            assertThat(r.getFirstDate()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(r.getLastDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        });
    }

    @Test
    @Transactional
    void leavesOutStatementsThatPrintTheirPeriod() {
        StatementImport dated = statement(userId, accountId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        txn(userId, accountId, dated.getId(), LocalDate.of(2026, 9, 3));

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH)).isEmpty();
    }

    @Test
    @Transactional
    void leavesOutAStatementThatWasReplaced() {
        StatementImport replacement = statement(userId, accountId, null, null);
        StatementImport replaced = statement(userId, accountId, null, null);
        replaced.setSupersededBy(replacement.getId());
        statementImportRepository.save(replaced);
        txn(userId, accountId, replaced.getId(), LocalDate.of(2026, 10, 5));

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH)).isEmpty();
    }

    @Test
    @Transactional
    void leavesOutADeletedStatement() {
        StatementImport undated = statement(userId, accountId, null, null);
        txn(userId, accountId, undated.getId(), LocalDate.of(2026, 10, 5));
        statementImportRepository.delete(undated);
        statementImportRepository.flush();

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH)).isEmpty();
    }

    @Test
    @Transactional
    void staysWithinThisAccountAndThisUser() {
        UUID otherAccount = newAccount(userId);
        StatementImport elsewhere = statement(userId, otherAccount, null, null);
        txn(userId, otherAccount, elsewhere.getId(), LocalDate.of(2026, 10, 5));

        UUID otherUser = newUser();
        UUID theirAccount = newAccount(otherUser);
        StatementImport theirs = statement(otherUser, theirAccount, null, null);
        txn(otherUser, theirAccount, theirs.getId(), LocalDate.of(2026, 10, 5));

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH)).isEmpty();
        assertThat(statementImportRepository.findUndatedStatementRanges(otherUser, accountId, THROUGH)).isEmpty();
    }

    @Test
    @Transactional
    void ignoresARowDatedAfterTheLatestReportableDay() {
        StatementImport undated = statement(userId, accountId, null, null);
        txn(userId, accountId, undated.getId(), LocalDate.of(2026, 10, 5));
        txn(userId, accountId, undated.getId(), LocalDate.of(2096, 10, 5));

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH))
                .singleElement()
                .satisfies(r -> assertThat(r.getLastDate()).isEqualTo(LocalDate.of(2026, 10, 5)));
    }

    @Test
    @Transactional
    void leavesOutAnUndatedStatementWithNoTransactionsLeft() {
        statement(userId, accountId, null, null);

        assertThat(statementImportRepository.findUndatedStatementRanges(userId, accountId, THROUGH)).isEmpty();
    }
}
