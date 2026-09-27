package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountService;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
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
 * BH-003 for a mark written AFTER the import that inserted the row.
 *
 * <p>The confirm-time reversal only ever saw its own batch. A row that became a duplicate later --
 * here, because the user corrected its narration to match an earlier statement's row, which re-runs
 * reconciliation -- was marked, excluded from every total, and left in {@code Account.balance} for
 * good. The reversal now belongs to the reconciliation run that writes the mark, whichever caller
 * triggered it; and deleting the marked row afterwards must not move the balance a second time.
 *
 * <p>The second pair of tests covers a mark written behind an absolute SET: the row predates the
 * SET, so the mark takes nothing off and records the SET instead. Deleting that statement restores
 * the pre-SET balance -- the row's amount with it -- so the release reverses the row then; a manual
 * balance edit replaces the baseline instead, and rebases the mark. Either way the mark ends in
 * the state a mark written with no SET in the way would have, and un-mark then delete returns the
 * balance to where the ledger says it belongs.
 *
 * <p>End to end for the same reason as ImportAccountBalanceIT: the defect is three services'
 * balance writes disagreeing, which a mock of any one of them cannot show.
 */
class RetroactiveDuplicateBalanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionService transactionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /** Same cleanup, same reason, as ImportAccountBalanceIT's own @AfterEach. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("retroactive-dup-balance-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Retroactive Duplicate Balance IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Retroactive Duplicate Balance IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("1000.00"));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private ConfirmedRow row(String description, String amount) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), "EXPENSE",
                "Other", true, "rule", null, false, null, null, false);
    }

    private void importRows(Fixture f, String fileName, ConfirmedRow... rows) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/csv",
                fileName.getBytes(StandardCharsets.UTF_8));
        importService.confirm(f.user().getId(), file,
                new ConfirmRequest(null, List.of(rows), f.account().getId(), null, null, null, null));
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    private Transaction only(Fixture f, String description) {
        return transactionRepository.findByUserId(f.user().getId()).stream()
                .filter(t -> description.equals(t.getDescription())).findFirst().orElseThrow();
    }

    @Test
    void aRowThatBecomesADuplicateAfterAnEdit_comesBackOffTheBalance_andItsDeleteDoesNotMoveItAgain() throws Exception {
        Fixture f = fixture();

        importRows(f, "june.csv", row("METRO FARE", "45.00"));
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // A second statement whose row is the same fare under a narration the engine cannot match
        // at confirm time (no running balance to key on either), so both are counted.
        importRows(f, "june-again.csv", row("MTR FARE TYPO", "45.00"));
        assertThat(balanceOf(f)).isEqualByComparingTo("910.00");
        Transaction typo = only(f, "MTR FARE TYPO");
        assertThat(typo.getIsDuplicateOf()).isNull();
        UUID originalId = only(f, "METRO FARE").getId();

        // The user corrects the narration. update() re-runs reconciliation, which now sees two
        // identical rows from two imports and marks the later one.
        transactionService.update(f.user().getId(), typo.getId(),
                new TransactionDto.UpdateRequest(null, "METRO FARE", null, null, null, null, null, null));
        Transaction marked = transactionRepository.findById(typo.getId()).orElseThrow();
        assertThat(marked.getIsDuplicateOf()).isEqualTo(originalId);
        // Its 45.00 is no longer counted, so it is no longer in the balance either.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // Deleting the marked row removes nothing from the balance: its contribution already left.
        transactionService.delete(f.user().getId(), marked.getId(), f.user().getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
    }

    @Test
    void deletingTheCanonicalRow_putsTheUnmarkedSurvivorsContributionBack() throws Exception {
        Fixture f = fixture();

        importRows(f, "june.csv", row("METRO FARE", "45.00"));
        importRows(f, "june-again.csv", row("METRO FARE", "45.00"));
        // The second copy is marked at its own confirm and reversed: one fare in the balance.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
        List<Transaction> fares = transactionRepository.findByUserId(f.user().getId());
        Transaction canonical = fares.stream().filter(t -> t.getIsDuplicateOf() == null).findFirst().orElseThrow();
        Transaction copy = fares.stream().filter(t -> t.getIsDuplicateOf() != null).findFirst().orElseThrow();

        transactionService.delete(f.user().getId(), canonical.getId(), f.user().getId());

        // The canonical's 45.00 came off; the survivor is counted again, so its 45.00 went back on.
        // The ledger holds one real fare and the balance reflects one.
        assertThat(transactionRepository.findById(copy.getId()).orElseThrow().getIsDuplicateOf()).isNull();
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
    }

    /** Two counted fares (955.00, then 910.00) in July statements; an August statement whose stated
     *  closing balance corroborates its own arithmetic (900 - 100 = 800) SETs the balance to that
     *  figure and becomes the anchor -- a figure the ADDITIVE path could not produce (it would land
     *  on 810.00), so the SET is what the assertion proves. Then the narration fix marks the second
     *  fare, which predates the SET: nothing comes off, and the row records the SET that stood in
     *  the way. Returns the marked row. */
    private Transaction fareMarkedBehindASet(Fixture f) throws Exception {
        // Both July statements state their period: an ABSOLUTE SET is only applied by the most
        // recent statement, which needs the others to say when they are.
        LocalDate julyStart = LocalDate.of(2026, 7, 1), julyEnd = LocalDate.of(2026, 7, 31);
        importService.confirm(f.user().getId(), file("june.csv"), new ConfirmRequest(null,
                List.of(row("METRO FARE", "45.00")), f.account().getId(), null, null, null, null, julyStart, julyEnd));
        importService.confirm(f.user().getId(), file("june-again.csv"), new ConfirmRequest(null,
                List.of(row("MTR FARE TYPO", "45.00")), f.account().getId(), null, null, null, null, julyStart, julyEnd));
        assertThat(balanceOf(f)).isEqualByComparingTo("910.00");

        importService.confirm(f.user().getId(), file("august.csv"), new ConfirmRequest(null,
                List.of(new ConfirmedRow(LocalDate.of(2026, 8, 31), "RENT", new BigDecimal("100.00"), "EXPENSE",
                        "Other", true, "rule", null, false, null, null, false)),
                f.account().getId(), null, new BigDecimal("900.00"), new BigDecimal("800.00"), null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        assertThat(balanceOf(f)).isEqualByComparingTo("800.00");
        UUID anchor = accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId();
        assertThat(anchor).isNotNull();

        // Read before the rename: afterwards two rows carry this narration and only() is unordered.
        UUID originalId = only(f, "METRO FARE").getId();
        Transaction typo = only(f, "MTR FARE TYPO");
        transactionService.update(f.user().getId(), typo.getId(),
                new TransactionDto.UpdateRequest(null, "METRO FARE", null, null, null, null, null, null));
        Transaction marked = transactionRepository.findById(typo.getId()).orElseThrow();
        assertThat(marked.getIsDuplicateOf()).isEqualTo(originalId);
        assertThat(marked.isDuplicateBalanceReversed()).isFalse();
        assertThat(marked.getDuplicateBalanceAnchorId()).isEqualTo(anchor);
        // The SET stands: the stated figure is what the balance is, whatever this row did before.
        assertThat(balanceOf(f)).isEqualByComparingTo("800.00");
        return marked;
    }

    /** A second ABSOLUTE statement, September, on top of {@link #fareMarkedBehindASet}'s August one:
     *  SETs the balance to 750.00 (opening 800 - 50), records August as the SET it replaced. */
    private UUID septemberSet(Fixture f) throws Exception {
        importService.confirm(f.user().getId(), file("september.csv"), new ConfirmRequest(null,
                List.of(new ConfirmedRow(LocalDate.of(2026, 9, 30), "FEES", new BigDecimal("50.00"), "EXPENSE",
                        "Other", true, "rule", null, false, null, null, false)),
                f.account().getId(), null, new BigDecimal("800.00"), new BigDecimal("750.00"), null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        assertThat(balanceOf(f)).isEqualByComparingTo("750.00");
        return accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId();
    }

    private com.finora.entity.StatementImport statement(UUID id) {
        return statementImportRepository.findById(id).orElseThrow();
    }

    @Autowired private com.finora.repository.StatementImportRepository statementImportRepository;

    @Test
    void deletingASet_handsTheAnchorBackToTheSetItReplaced_andKeepsOlderMarksBehindIt() throws Exception {
        Fixture f = fixture();
        Transaction marked = fareMarkedBehindASet(f);
        UUID august = marked.getDuplicateBalanceAnchorId();
        UUID september = septemberSet(f);
        assertThat(statement(september).getPreviousAbsoluteSetStatementId()).isEqualTo(august);
        // The mark was written while August was live; September replaced it. Still held by August.
        assertThat(transactionRepository.findById(marked.getId()).orElseThrow().getDuplicateBalanceAnchorId()).isEqualTo(august);

        statementImportService.delete(f.user().getId(), september, f.user().getId());

        // 750 + (800 - 750): standing on August's figure again, which is the anchor again. The
        // fare predates August, so nothing of it is in this balance and nothing is released.
        assertThat(balanceOf(f)).isEqualByComparingTo("800.00");
        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId()).isEqualTo(august);
        Transaction stillHeld = transactionRepository.findById(marked.getId()).orElseThrow();
        assertThat(stillHeld.isDuplicateBalanceReversed()).isFalse();
        assertThat(stillHeld.getDuplicateBalanceAnchorId()).isEqualTo(august);

        statementImportService.delete(f.user().getId(), august, f.user().getId());

        // 800 + (910 - 800) + 45 released: one fare counted, no anchor.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId()).isNull();
        Transaction released = transactionRepository.findById(marked.getId()).orElseThrow();
        assertThat(released.isDuplicateBalanceReversed()).isTrue();
        assertThat(released.getDuplicateBalanceAnchorId()).isNull();
    }

    @Test
    void deletingASet_alsoReversesTheEarlierSetDeletedWhileItStoodOverIt() throws Exception {
        Fixture f = fixture();
        Transaction marked = fareMarkedBehindASet(f);
        UUID august = marked.getDuplicateBalanceAnchorId();
        UUID september = septemberSet(f);

        // August deleted while September is the live SET: moot, nothing moves.
        statementImportService.delete(f.user().getId(), august, f.user().getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("750.00");
        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId()).isEqualTo(september);

        statementImportService.delete(f.user().getId(), september, f.user().getId());

        // 750 + (800 - 750) + (910 - 800) + 45 released: both SETs undone, the fare counted once.
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
        assertThat(accountRepository.findById(f.account().getId()).orElseThrow().getLastAbsoluteSetStatementId()).isNull();
        Transaction released = transactionRepository.findById(marked.getId()).orElseThrow();
        assertThat(released.isDuplicateBalanceReversed()).isTrue();
        assertThat(released.getDuplicateBalanceAnchorId()).isNull();
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "text/csv", name.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deletingTheStatementWhoseSetHeldAMark_reversesTheMarkWithTheSet() throws Exception {
        Fixture f = fixture();
        Transaction marked = fareMarkedBehindASet(f);
        UUID anchor = marked.getDuplicateBalanceAnchorId();

        statementImportService.delete(f.user().getId(), anchor, f.user().getId());

        // The pre-SET balance (910.00) comes back, and with it the marked fare's 45.00 -- which the
        // mark now takes off, as it would have with no SET in the way: one fare counted, 955.00.
        // (800.00 + (910.00 - 800.00) + 45.00.)
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
        Transaction released = transactionRepository.findById(marked.getId()).orElseThrow();
        assertThat(released.getIsDuplicateOf()).isNotNull();
        assertThat(released.isDuplicateBalanceReversed()).isTrue();
        assertThat(released.getDuplicateBalanceAnchorId()).isNull();

        // From here the mark behaves like any other: un-mark puts the fare back, delete takes it off.
        transactionService.confirmNotDuplicate(f.user().getId(), released.getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("910.00");
        transactionService.delete(f.user().getId(), released.getId(), f.user().getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");
    }

    @Test
    void aManualBalanceEdit_rebasesAMarkHeldBehindTheSetItReplaces() throws Exception {
        Fixture f = fixture();
        Transaction marked = fareMarkedBehindASet(f);

        accountService.update(f.user().getId(), f.account().getId(), new AccountDto.CreateRequest(
                "Retroactive Duplicate Balance IT Account", "SAVINGS", new BigDecimal("7000.00"),
                null, null, null, null, null, null, null, null), f.user().getId());

        // The typed figure is the balance, whole, like a statement's closing balance: the marked
        // fare is inside it or not, and either way the mark took nothing off it.
        assertThat(balanceOf(f)).isEqualByComparingTo("7000.00");
        Transaction rebased = transactionRepository.findById(marked.getId()).orElseThrow();
        assertThat(rebased.getIsDuplicateOf()).isNotNull();
        assertThat(rebased.isDuplicateBalanceReversed()).isFalse();
        assertThat(rebased.getDuplicateBalanceAnchorId()).isNull();

        // So correcting Finora's view of the ledger -- "not a duplicate after all" -- or deleting the
        // row does not change the balance the user stated. This used to add the fare on top of the
        // typed 7000 (6955) on un-mark.
        transactionService.confirmNotDuplicate(f.user().getId(), rebased.getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("7000.00");
        transactionService.delete(f.user().getId(), rebased.getId(), f.user().getId());
        assertThat(balanceOf(f)).isEqualByComparingTo("7000.00");
    }
}
