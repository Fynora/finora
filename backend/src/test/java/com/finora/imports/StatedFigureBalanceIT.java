package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import com.finora.util.UserZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stated figure holds everything before it: a balance the user typed, and a statement's closing
 * balance. Rows already inside one must not move the balance -- not when an older statement is
 * uploaded after the balance was typed, and not when one of those rows is edited or deleted.
 * Before this, a typed balance was treated as if it said nothing about dates (older statements were
 * added on top), and editing or deleting any row under a closing balance moved the balance even
 * though the bank's figure had not changed.
 */
class StatedFigureBalanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /** Same cleanup, for the same reason, as ImportAccountBalanceIT.removeQueuedLearningEvents. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("stated-figure-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Stated Figure IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    /** An account with no typed balance -- as created by an import or before this change. */
    private UUID untypedAccount(UUID userId, String balance) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Stated Figure IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getId();
    }

    private UUID typedAccount(UUID userId, String balance) {
        return accountService.create(userId, new AccountDto.CreateRequest("Typed Account", "SAVINGS",
                new BigDecimal(balance), null, null, null, null, null, null, null, null), userId).id();
    }

    private ConfirmedRow row(LocalDate date, String description, String amount, String type) {
        return new ConfirmedRow(date, description, new BigDecimal(amount), type,
                "Other", true, "rule", null, false, null, null, false);
    }

    private UUID importStatement(UUID userId, UUID accountId, BigDecimal opening, BigDecimal closing,
                                 LocalDate start, LocalDate end, ConfirmedRow... rows) throws Exception {
        importService.confirm(userId, new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, List.of(rows), accountId, null, opening, closing, null, start, end));
        return statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId).get(0).getId();
    }

    private Transaction only(UUID statementId) {
        List<Transaction> rows = transactionRepository.findByStatementImportId(statementId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    private static LocalDate today() {
        return LocalDate.now(UserZone.DEFAULT);
    }

    // ---- 1: a balance the user typed ----

    @Test
    @DisplayName("an older statement uploaded after the balance was typed moves nothing, closing balance or not")
    void olderStatementsAfterATypedBalance_doNotMoveIt() throws Exception {
        UUID user = user().getId();
        UUID account = typedAccount(user, "5000.00");
        LocalDate lastMonth = today().minusMonths(1).withDayOfMonth(1);
        LocalDate twoMonthsAgo = today().minusMonths(2).withDayOfMonth(1);

        // Last month, with a closing balance that corroborates its own rows. It must not overwrite
        // the balance the user typed today: that figure is newer.
        importStatement(user, account, new BigDecimal("4000.00"), new BigDecimal("4300.00"),
                lastMonth, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()),
                row(lastMonth.plusDays(2), "SALARY", "500.00", "INCOME"),
                row(lastMonth.plusDays(9), "RENT", "200.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("5000.00");

        // Two months ago, with no closing balance at all.
        importStatement(user, account, null, null,
                twoMonthsAgo, twoMonthsAgo.withDayOfMonth(twoMonthsAgo.lengthOfMonth()),
                row(twoMonthsAgo.plusDays(4), "GROCERIES", "300.00", "EXPENSE"));
        assertThat(balanceOf(account))
                .as("everything before today is inside the balance the user typed")
                .isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a balance edited by hand is a new stated figure too; a row dated today still counts")
    void aBalanceEditedByHand_holdsEarlierDaysButNotToday() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        accountService.update(user, account, new AccountDto.CreateRequest("Stated Figure IT Account", "SAVINGS",
                new BigDecimal("7000.00"), null, null, null, null, null, null, null, null), user);
        assertThat(balanceOf(account)).isEqualByComparingTo("7000.00");

        // A statement ending today: yesterday's row is inside the typed figure; today's may not be,
        // and counting it is the side the rule takes.
        importStatement(user, account, null, null, today().minusDays(10), today(),
                row(today().minusDays(1), "YESTERDAY", "100.00", "EXPENSE"),
                row(today(), "TODAY", "40.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("6960.00");
    }

    @Test
    @DisplayName("an account created with no balance is not a stated figure: older statements still count")
    void anAccountCreatedWithNoBalance_isNotAStatedFigure() throws Exception {
        UUID user = user().getId();
        UUID account = accountService.create(user, new AccountDto.CreateRequest("No Balance", "SAVINGS",
                null, null, null, null, null, null, null, null, null), user).id();
        LocalDate lastMonth = today().minusMonths(1).withDayOfMonth(1);

        importStatement(user, account, null, null, lastMonth, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()),
                row(lastMonth.plusDays(2), "SALARY", "500.00", "INCOME"));
        assertThat(balanceOf(account))
                .as("a zero nobody typed says nothing about any date; freezing on it would lose every import")
                .isEqualByComparingTo("500.00");
    }

    // ---- 2: editing or deleting a row already inside a stated figure ----

    @Test
    @DisplayName("editing or deleting a row of the statement whose closing balance set the account moves nothing")
    void rowsOfTheClosingBalanceStatement_editAndDeleteMoveNothing() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        LocalDate july = LocalDate.of(2026, 7, 1);
        UUID statement = importStatement(user, account, new BigDecimal("1000.00"), new BigDecimal("850.00"),
                july, LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "150.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");

        Transaction t = only(statement);
        transactionService.update(user, t.getId(),
                new TransactionDto.UpdateRequest(null, null, null, new BigDecimal("15.00"), null, null, null, null));
        assertThat(balanceOf(account))
                .as("correcting a misread amount does not change what the bank said the balance is")
                .isEqualByComparingTo("850.00");

        transactionService.delete(user, t.getId(), user);
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");
    }

    @Test
    @DisplayName("a row held behind a closing balance: editing it moves nothing now, and still comes out right "
            + "if that statement is deleted later")
    void aRowHeldBehindAClosingBalance_isCorrectedInTheSnapshot() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        UUID june = importStatement(user, account, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                row(LocalDate.of(2026, 6, 15), "GROCERIES", "100.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("900.00");
        UUID july = importStatement(user, account, new BigDecimal("900.00"), new BigDecimal("850.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "50.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");

        transactionService.update(user, only(june).getId(),
                new TransactionDto.UpdateRequest(null, null, null, new BigDecimal("130.00"), null, null, null, null));
        assertThat(balanceOf(account))
                .as("July's closing balance is still the balance")
                .isEqualByComparingTo("850.00");

        statementImportService.delete(user, july, user);
        assertThat(balanceOf(account))
                .as("without July, the ledger is the opening 1000 less the corrected 130")
                .isEqualByComparingTo("870.00");
    }

    @Test
    @DisplayName("deleting a row held behind a closing balance, then that statement, leaves the row out")
    void deletingAHeldRow_thenTheClosingBalanceStatement_leavesTheRowOut() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        UUID june = importStatement(user, account, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                row(LocalDate.of(2026, 6, 15), "GROCERIES", "100.00", "EXPENSE"));
        UUID july = importStatement(user, account, new BigDecimal("900.00"), new BigDecimal("850.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "50.00", "EXPENSE"));

        transactionService.delete(user, only(june).getId(), user);
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");

        statementImportService.delete(user, july, user);
        assertThat(balanceOf(account))
                .as("nothing is left on the account but its opening 1000")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("deleting the whole older statement behind a closing balance moves nothing either")
    void deletingAWholeStatementBehindAClosingBalance_movesNothing() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        UUID june = importStatement(user, account, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                row(LocalDate.of(2026, 6, 15), "GROCERIES", "100.00", "EXPENSE"));
        UUID july = importStatement(user, account, new BigDecimal("900.00"), new BigDecimal("850.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "50.00", "EXPENSE"));

        statementImportService.delete(user, june, user);
        assertThat(balanceOf(account))
                .as("the same rule as deleting its rows one by one")
                .isEqualByComparingTo("850.00");
        statementImportService.delete(user, july, user);
        assertThat(balanceOf(account)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a manual entry added after the closing balance still moves the balance when edited or deleted")
    void aManualEntryAfterTheClosingBalance_stillMovesIt() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        importStatement(user, account, new BigDecimal("1000.00"), new BigDecimal("850.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "150.00", "EXPENSE"));
        TransactionDto manual = transactionService.create(user, new TransactionDto.CreateRequest(
                account, "Other", LocalDate.of(2026, 8, 5), "BOOKS", new BigDecimal("80.00"), "EXPENSE", List.of()));
        assertThat(balanceOf(account)).isEqualByComparingTo("770.00");

        transactionService.update(user, manual.id(),
                new TransactionDto.UpdateRequest(null, null, null, new BigDecimal("90.00"), null, null, null, null));
        assertThat(balanceOf(account)).isEqualByComparingTo("760.00");
        transactionService.delete(user, manual.id(), user);
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");
    }

    @Test
    @DisplayName("a row from before the balance was typed moves nothing when edited or deleted; one added after does")
    void rowsBeforeATypedBalance_moveNothing_rowsAfterDo() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        TransactionDto before = transactionService.create(user, new TransactionDto.CreateRequest(
                account, "Other", today().minusDays(3), "OLD COFFEE", new BigDecimal("30.00"), "EXPENSE", List.of()));
        assertThat(balanceOf(account)).isEqualByComparingTo("970.00");

        accountService.update(user, account, new AccountDto.CreateRequest("Stated Figure IT Account", "SAVINGS",
                new BigDecimal("5000.00"), null, null, null, null, null, null, null, null), user);
        transactionService.delete(user, before.id(), user);
        assertThat(balanceOf(account))
                .as("the typed 5000 already had the coffee in it, or not -- either way it is what the user said")
                .isEqualByComparingTo("5000.00");

        TransactionDto after = transactionService.create(user, new TransactionDto.CreateRequest(
                account, "Other", today(), "LUNCH", new BigDecimal("200.00"), "EXPENSE", List.of()));
        assertThat(balanceOf(account)).isEqualByComparingTo("4800.00");
        transactionService.delete(user, after.id(), user);
        assertThat(balanceOf(account)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("moving a covered row's date past the covered day makes it count")
    void editingACoveredRowsDatePastTheCoveredDay_makesItCount() throws Exception {
        UUID user = user().getId();
        UUID account = untypedAccount(user, "1000.00");
        importStatement(user, account, new BigDecimal("1000.00"), new BigDecimal("850.00"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                row(LocalDate.of(2026, 7, 20), "UTILITIES", "150.00", "EXPENSE"));
        // June, uploaded after July: inside July's closing balance, so it moved nothing.
        UUID june = importStatement(user, account, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                row(LocalDate.of(2026, 6, 15), "GROCERIES", "100.00", "EXPENSE"));
        assertThat(balanceOf(account)).isEqualByComparingTo("850.00");

        // The user says the grocery was really on 5 August -- after July's closing balance.
        transactionService.update(user, only(june).getId(),
                new TransactionDto.UpdateRequest(LocalDate.of(2026, 8, 5), null, null, null, null, null, null, null));
        assertThat(balanceOf(account)).isEqualByComparingTo("750.00");
    }
}
