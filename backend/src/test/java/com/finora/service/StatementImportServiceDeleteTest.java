package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * StatementImportService.delete() regression coverage -- two bugs found during the today's-work
 * bug audit, both fixed alongside these tests:
 *
 * 1. Reconciliation pointer cleanup only handled isDuplicateOf/transferPairId, never
 *    refundOfTransactionId, so deleting a statement containing the EXPENSE side of a matched
 *    refund pair left a surviving INCOME row dangling and permanently stuck at
 *    ReconciliationStatus.REFUND (silently excluded from DashboardService's totals forever).
 * 2. recurringService.detectForUser() was never called here at all, unlike every other write
 *    path that changes a user's transaction set (TransactionService.delete/bulkDelete/create/
 *    update) -- see docs/team-message-financial-intelligence-v1-closeout.md.
 */
class StatementImportServiceDeleteTest {

    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private ReconciliationService reconciliationService;
    private RecurringService recurringService;
    private StatementImportService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID statementImportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        reconciliationService = mock(ReconciliationService.class);
        recurringService = mock(RecurringService.class);
        service = new StatementImportService(
                statementImportRepository, accountRepository, mock(CategoryRepository.class),
                transactionRepository, reconciliationService, recurringService,
                mock(ImportService.class), mock(AuditService.class), mock(BankManagementService.class), new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""),
                mock(com.finora.repository.ReimportConfirmationClaimRepository.class));

        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
    }

    private Transaction transaction(UUID id) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", id);
        t.setUserId(userId);
        t.setDescription("Some transaction");
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    @Test
    void delete_clearsRefundPointer_onASurvivingTransactionOutsideTheStatement() {
        UUID expenseInStatementId = UUID.randomUUID();
        Transaction expenseInStatement = transaction(expenseInStatementId);
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(expenseInStatement));

        UUID refundIncomeId = UUID.randomUUID();
        Transaction refundIncome = transaction(refundIncomeId);
        refundIncome.setTxnType(Transaction.Type.INCOME);
        refundIncome.setRefundOfTransactionId(expenseInStatementId);
        refundIncome.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        when(transactionRepository.findByRefundOfTransactionIdIn(List.of(expenseInStatementId)))
                .thenReturn(List.of(refundIncome));

        service.delete(userId, statementImportId, userId);

        assertThat(refundIncome.getRefundOfTransactionId()).isNull();
        assertThat(refundIncome.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void delete_runsRecurringDetection_afterRemovingTheStatementsTransactions() {
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(transaction(txnId)));

        service.delete(userId, statementImportId, userId);

        verify(recurringService).detectForUser(userId);
        verify(reconciliationService).reconcileForUser(userId);
    }

    @Test
    void delete_withNoTransactions_skipsReconciliationAndRecurringDetection() {
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of());

        service.delete(userId, statementImportId, userId);

        verify(recurringService, never()).detectForUser(any());
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    /** A survivor in ANOTHER statement that pointed at one of this statement's rows is un-marked
     *  and counted again. Out of an ADDITIVE import its contribution was reversed when it was
     *  marked, so it goes back on: this statement's 500 comes off, the survivor's 500 goes back. */
    @Test
    void delete_addsBackTheContribution_ofAnAdditiveImportSurvivorItUnmarks() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        Transaction canonical = transaction(UUID.randomUUID());
        canonical.setAmount(new BigDecimal("500.00"));
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(canonical));

        UUID otherImportId = UUID.randomUUID();
        Transaction survivor = transaction(UUID.randomUUID());
        survivor.setAmount(new BigDecimal("500.00"));
        survivor.setAccountId(accountId);
        survivor.setStatementImportId(otherImportId);
        survivor.setIsDuplicateOf(canonical.getId());
        survivor.setDuplicateBalanceReversed(true);
        survivor.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        when(transactionRepository.findByIsDuplicateOfIn(List.of(canonical.getId()))).thenReturn(List.of(survivor));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(survivor.getIsDuplicateOf()).isNull();
        assertThat(survivor.isDuplicateBalanceReversed()).isFalse();
        assertThat(account.getBalance()).isEqualByComparingTo("9500.00");
    }

    /** A marked row this statement's SET held out of the balance (its effect is in the pre-SET
     *  snapshot, not separately reversed) comes back off when the SET is reversed: the snapshot
     *  restores 10000.00, which includes the held 100.00 expense, so the release takes it off
     *  again and records the row as reversed like any other mark. */
    @Test
    void delete_ofTheLiveAbsoluteAnchor_reversesTheMarksItHeld() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Transaction held = transaction(UUID.randomUUID());
        held.setAccountId(accountId);
        held.setIsDuplicateOf(UUID.randomUUID());
        held.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        held.setDuplicateBalanceAnchorId(statementImportId);
        when(transactionRepository.findByDuplicateBalanceAnchorId(statementImportId)).thenReturn(List.of(held));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("10100.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isNull();
        assertThat(held.isDuplicateBalanceReversed()).isTrue();
        assertThat(held.getDuplicateBalanceAnchorId()).isNull();
        verify(transactionRepository).saveAll(List.of(held));
    }

    private StatementImportRepository.AnchorSnapshot snapshot(UUID id, String before, String closing,
                                                              UUID previous, UUID supersededBy, boolean deleted) {
        StatementImportRepository.AnchorSnapshot s = mock(StatementImportRepository.AnchorSnapshot.class);
        when(s.getId()).thenReturn(id);
        when(s.getBalanceBeforeAbsoluteSet()).thenReturn(before == null ? null : new BigDecimal(before));
        when(s.getClosingBalance()).thenReturn(closing == null ? null : new BigDecimal(closing));
        when(s.getPreviousAbsoluteSetStatementId()).thenReturn(previous);
        when(s.getSupersededBy()).thenReturn(supersededBy);
        when(s.getDeleted()).thenReturn(deleted);
        return s;
    }

    private StatementImport absoluteStatement(UUID id, UUID accountId, String before, String closing, UUID previous) {
        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", id);
        si.setUserId(userId);
        si.setFileName(id + ".csv");
        si.setAccountId(accountId);
        si.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        si.setClosingBalance(new BigDecimal(closing));
        si.setBalanceBeforeAbsoluteSet(new BigDecimal(before));
        si.setPreviousAbsoluteSetStatementId(previous);
        when(statementImportRepository.findById(id)).thenReturn(Optional.of(si));
        return si;
    }

    private Transaction heldRow(UUID accountId, UUID anchorId, java.time.Instant createdAt) {
        Transaction t = transaction(UUID.randomUUID());
        t.setAccountId(accountId);
        t.setIsDuplicateOf(UUID.randomUUID());
        t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        t.setDuplicateBalanceAnchorId(anchorId);
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        return t;
    }

    /** The reversed SET replaced an earlier one that is still live: the restored balance is
     *  standing on that earlier figure, so it becomes the anchor again. A held row older than it
     *  stays behind a SET (handed to it, nothing released); a held row younger than it is in the
     *  restored balance and comes off. */
    @Test
    void delete_ofTheLiveAbsoluteAnchor_restoresThePreviousAnchor_andReleasesOnlyTheRowsItCovered() {
        UUID accountId = UUID.randomUUID();
        UUID earlierId = UUID.randomUUID();
        StatementImport earlier = absoluteStatement(earlierId, accountId, "12000.00", "10000.00", null);
        earlier.setImportedAt(java.time.Instant.parse("2026-08-01T10:00:00Z"));
        absoluteStatement(statementImportId, accountId, "10000.00", "9500.00", earlierId);
        StatementImportRepository.AnchorSnapshot earlierLink = snapshot(earlierId, "12000.00", "10000.00", null, null, false);
        when(statementImportRepository.findAnchorSnapshotIncludingDeleted(any(), any(), eq(earlierId)))
                .thenReturn(Optional.of(earlierLink));
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Transaction olderThanEarlier = heldRow(accountId, statementImportId, java.time.Instant.parse("2026-07-15T10:00:00Z"));
        Transaction betweenTheTwo = heldRow(accountId, statementImportId, java.time.Instant.parse("2026-08-15T10:00:00Z"));
        when(transactionRepository.findByDuplicateBalanceAnchorId(statementImportId))
                .thenReturn(List.of(olderThanEarlier, betweenTheTwo));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        // 9500 + (10000 - 9500) restored, + 100 for the one released expense.
        assertThat(account.getBalance()).isEqualByComparingTo("10100.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isEqualTo(earlierId);
        assertThat(olderThanEarlier.isDuplicateBalanceReversed()).isFalse();
        assertThat(olderThanEarlier.getDuplicateBalanceAnchorId()).isEqualTo(earlierId);
        assertThat(betweenTheTwo.isDuplicateBalanceReversed()).isTrue();
        assertThat(betweenTheTwo.getDuplicateBalanceAnchorId()).isNull();
    }

    /** The earlier SET was deleted while this one stood over it (its reversal was moot then), so
     *  its SET is reversed now too, its held rows released with this one's, and the chain ends
     *  with no anchor. */
    @Test
    void delete_ofTheLiveAbsoluteAnchor_alsoReversesAnEarlierSetDeletedWhileItWasMoot() {
        UUID accountId = UUID.randomUUID();
        UUID earlierId = UUID.randomUUID();
        absoluteStatement(statementImportId, accountId, "10000.00", "9500.00", earlierId);
        StatementImportRepository.AnchorSnapshot deletedLink = snapshot(earlierId, "12000.00", "10000.00", null, null, true);
        when(statementImportRepository.findAnchorSnapshotIncludingDeleted(any(), any(), eq(earlierId)))
                .thenReturn(Optional.of(deletedLink));
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Transaction heldByEarlier = heldRow(accountId, earlierId, java.time.Instant.parse("2026-07-15T10:00:00Z"));
        Transaction heldByThis = heldRow(accountId, statementImportId, java.time.Instant.parse("2026-08-15T10:00:00Z"));
        when(transactionRepository.findByDuplicateBalanceAnchorId(statementImportId)).thenReturn(List.of(heldByThis));
        when(transactionRepository.findByDuplicateBalanceAnchorId(earlierId)).thenReturn(List.of(heldByEarlier));

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        // 9500 + 500 (this SET) + 2000 (the earlier SET) + 200 (two released expenses).
        assertThat(account.getBalance()).isEqualByComparingTo("12200.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isNull();
        assertThat(heldByEarlier.isDuplicateBalanceReversed()).isTrue();
        assertThat(heldByThis.isDuplicateBalanceReversed()).isTrue();
        verify(statementImportRepository, never()).findById(earlierId);
    }

    /** A survivor in another statement that has itself been superseded is not resurrected by this
     *  delete: it stays SUPERSEDED and nothing goes back on the balance. */
    @Test
    void delete_keepsASurvivorOfASupersededStatementSuperseded_andAddsNothingBack() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
        Transaction canonical = transaction(UUID.randomUUID());
        canonical.setAmount(new BigDecimal("500.00"));
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(canonical));

        UUID supersededImportId = UUID.randomUUID();
        StatementImport supersededImport = new StatementImport();
        ReflectionTestUtils.setField(supersededImport, "id", supersededImportId);
        supersededImport.setSupersededBy(UUID.randomUUID());
        when(statementImportRepository.findById(supersededImportId)).thenReturn(Optional.of(supersededImport));
        Transaction survivor = transaction(UUID.randomUUID());
        survivor.setAmount(new BigDecimal("500.00"));
        survivor.setAccountId(accountId);
        survivor.setStatementImportId(supersededImportId);
        survivor.setIsDuplicateOf(canonical.getId());
        survivor.setDuplicateBalanceReversed(true);
        survivor.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        when(transactionRepository.findByIsDuplicateOfIn(List.of(canonical.getId()))).thenReturn(List.of(survivor));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(survivor.getIsDuplicateOf()).isNull();
        assertThat(survivor.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.SUPERSEDED);
        // This statement's 500 comes off; the superseded survivor's 500 stays off.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }

    /** A held row un-marked as SUPERSEDED (its statement replaced) keeps its held record, and its
     *  effect is in the snapshot all the same: reversing the SET releases it by the record alone. */
    @Test
    void delete_ofTheLiveAbsoluteAnchor_releasesAHeldRowUnmarkedAsSuperseded() {
        UUID accountId = UUID.randomUUID();
        absoluteStatement(statementImportId, accountId, "10000.00", "9500.00", null);
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));
        Transaction superseded = heldRow(accountId, statementImportId, java.time.Instant.parse("2026-07-15T10:00:00Z"));
        superseded.setIsDuplicateOf(null);
        superseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
        when(transactionRepository.findByDuplicateBalanceAnchorId(statementImportId)).thenReturn(List.of(superseded));
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("10100.00");
        assertThat(superseded.isDuplicateBalanceReversed()).isTrue();
        assertThat(superseded.getDuplicateBalanceAnchorId()).isNull();
    }

    /** A row the SET took nothing off, then a whole-statement reversal did (recorded as reversed),
     *  is not touched twice. */
    @Test
    void delete_ofTheLiveAbsoluteAnchor_skipsAHeldRowAlreadyRecordedAsReversed() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Transaction stale = transaction(UUID.randomUUID());
        stale.setAccountId(accountId);
        stale.setIsDuplicateOf(UUID.randomUUID());
        stale.setDuplicateBalanceReversed(true);
        stale.setDuplicateBalanceAnchorId(statementImportId);
        when(transactionRepository.findByDuplicateBalanceAnchorId(statementImportId)).thenReturn(List.of(stale));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
        verify(transactionRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    void delete_reversal_excludesATransactionAlreadyFlaggedDuplicate() {
        // A DUPLICATE-flagged row's contribution to Account.balance was already reversed once, at
        // the original statement's own confirm time (ImportService.summarise's BH-003 correction --
        // ReconciliationService always sets isDuplicateOf together with reconciliationStatus
        // DUPLICATE, never leaves a duplicate row at OK). Its CURRENT net contribution is zero, so
        // it must not be summed into the reversal here too -- doing so would move the balance a
        // second time for a row that never really counted. Same fix as StatementImportService's
        // supersede() reversal.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        Transaction realExpense = transaction(UUID.randomUUID());
        realExpense.setAmount(new BigDecimal("500.00"));

        Transaction alreadyDuplicate = transaction(UUID.randomUUID());
        alreadyDuplicate.setAmount(new BigDecimal("300.00"));
        alreadyDuplicate.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        alreadyDuplicate.setIsDuplicateOf(UUID.randomUUID());
        alreadyDuplicate.setDuplicateBalanceReversed(true);

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(realExpense, alreadyDuplicate));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        // Reversing only the real 500 expense's contribution -- not 500 + 300.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void delete_reversal_excludesATransactionAlreadyFlaggedSuperseded() {
        // Same bug, a second trigger (#631 only excluded isDuplicateOf, not this):
        // StatementImportService.supersede() marks an ADDITIVE-mode original's rows SUPERSEDED
        // and, in the same call, reverses their contribution to Account.balance -- so a SUPERSEDED
        // row's CURRENT net contribution is zero, exactly like an already-DUPLICATE-flagged row's.
        // Deleting an already-superseded statement must not sum that row into the reversal again.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        Transaction realExpense = transaction(UUID.randomUUID());
        realExpense.setAmount(new BigDecimal("500.00"));

        Transaction alreadySuperseded = transaction(UUID.randomUUID());
        alreadySuperseded.setAmount(new BigDecimal("300.00"));
        alreadySuperseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(realExpense, alreadySuperseded));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        // Reversing only the real 500 expense's contribution -- not 500 + 300.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void delete_reversesAnAbsoluteStatement_toItsPreSetBalance_whenStillTheLiveAnchor() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isNull();
    }

    @Test
    void delete_doesNotDoubleReverseAnAbsoluteStatement_whenALaterSetAlreadyOverwroteIt() {
        UUID accountId = UUID.randomUUID();
        UUID laterStatementId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("12000.00"));
        account.setLastAbsoluteSetStatementId(laterStatementId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("12000.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isEqualTo(laterStatementId);
    }

    @Test
    void delete_doesNotReverse_andLogsAWarning_whenTheStatementPredatesTheSnapshotField() {
        // BalanceApplicationMode says ABSOLUTE, but balanceBeforeAbsoluteSet is null -- a row
        // confirmed before this fix shipped. Never guess; same stance as UNKNOWN_LEGACY, and the
        // same case supersede() handles via its NO_SNAPSHOT outcome.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        // balanceBeforeAbsoluteSet deliberately left null.
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("9500.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void delete_reversesAnAbsoluteStatement_evenWithZeroTransactions() {
        // ABSOLUTE mode can fire with zero rows (opening == closing trivially corroborates) -- the
        // reversal must not be gated on whether this statement had any transactions, unlike the
        // ADDITIVE/NONE row-based reversal below it.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("100.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("500.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of());

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("100.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId, userId);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
    }
}
