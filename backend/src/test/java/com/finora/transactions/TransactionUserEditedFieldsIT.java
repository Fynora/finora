package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V232's user_edited_fields against real Postgres: a row written before anyone edits it reads back
 * empty (the column default), and an edit through the service persists exactly the changed fields
 * -- including through a second, unchanged save, which must not clear them. A statement refresh
 * reads this column to decide whose value wins, so it has to survive a round trip, not just hold in
 * memory.
 */
class TransactionUserEditedFieldsIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Transaction importedRow() {
        User user = new User();
        user.setEmail("user-edited-fields-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User Edited Fields IT User");
        user.setPhoneVerified(true);
        UUID userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();

        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setDescription("UPI GROCER");
        t.setAmount(new BigDecimal("450.00"));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return transactionRepository.save(t);
    }

    private Transaction reload(Transaction t) {
        return transactionRepository.findById(t.getId()).orElseThrow();
    }

    @Test
    void aRowNobodyEdited_readsBackWithNoEditedFields() {
        Transaction t = importedRow();

        assertThat(reload(t).getUserEditedFields()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cardinality(user_edited_fields) FROM transactions WHERE id = ?", Integer.class, t.getId()))
                .isZero();
    }

    @Test
    void anEdit_persistsExactlyTheChangedFields_andAnUnchangedSaveKeepsThem() {
        Transaction t = importedRow();

        transactionService.update(t.getUserId(), t.getId(), new TransactionDto.UpdateRequest(
                LocalDate.of(2026, 7, 11), "UPI GROCER", "", new BigDecimal("540"), "EXPENSE", null, "", List.of()));
        assertThat(reload(t).getUserEditedFields())
                .containsExactlyInAnyOrder(Transaction.EditableField.DATE, Transaction.EditableField.AMOUNT);

        transactionService.update(t.getUserId(), t.getId(), new TransactionDto.UpdateRequest(
                LocalDate.of(2026, 7, 11), "UPI GROCER", "", new BigDecimal("540"), "EXPENSE", null, "", List.of()));
        assertThat(reload(t).getUserEditedFields())
                .containsExactlyInAnyOrder(Transaction.EditableField.DATE, Transaction.EditableField.AMOUNT);
    }
}
