package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Plan 6, Track B: the three-way diff -- {new, changed, missing} -- that replaces
 * AccountAggregatorTransactionMapper.mapNew's insert-only behavior for the sliding-window re-fetch
 * path. The single highest-value assertion across this whole class: a changed or missing row's OWN
 * amount/narration is never mutated, only pendingBankCorrection and AuditLog -- see round 3's
 * "preserve, don't overwrite" decision in the scope doc.
 */
class AccountAggregatorTransactionDiffServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final LocalDate from = LocalDate.now().minusDays(14);
    private final LocalDate to = LocalDate.now();

    @Test
    void aBrandNewTxnIdIsReportedAsNew() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of());
        when(transactions.existsByAccountIdAndExternalTxnId(any(), any())).thenReturn(false);
        when(transactions.existsByAccountIdAndTransactionFingerprint(any(), any())).thenReturn(false);

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-new", "DEBIT", new BigDecimal("100.00"),
                        LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                        "Coffee shop", new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).hasSize(1);
        assertThat(result.changed()).isZero();
        assertThat(result.missing()).isZero();
        verifyNoInteractions(auditService);
    }

    @Test
    void sameTxnIdDifferentAmountIsChangedNotOverwritten() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction existing = new Transaction();
        existing.setUserId(userId);
        existing.setAccountId(accountId);
        existing.setExternalTxnId("txn-corrected");
        existing.setAmount(new BigDecimal("500.00"));
        existing.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(existing));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-corrected", "DEBIT", new BigDecimal("700.00"),
                        LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                        "Corrected narration", new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.changed()).isEqualTo(1);
        // The existing row's OWN amount is untouched -- the assertion that actually matters.
        assertThat(existing.getAmount()).isEqualByComparingTo("500.00");
        assertThat(existing.isPendingBankCorrection()).isTrue();
        verify(transactions).save(existing);
        // any() for the entity id -- existing is an unpersisted fixture (id is @GeneratedValue,
        // no public setter), same convention Track A's own sweep tests use for this reason.
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED"),
                eq("Transaction"), any(), anyMap());
    }

    @Test
    void sameTxnIdSameValuesIsANoOp() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction existing = new Transaction();
        existing.setAccountId(accountId);
        existing.setExternalTxnId("txn-unchanged");
        existing.setAmount(new BigDecimal("500.00"));
        existing.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        existing.setTransactionFingerprint(AccountAggregatorTransactionMapper.fingerprint(accountId,
                new SetuFiDataTransaction("txn-unchanged", "DEBIT", new BigDecimal("500.00"),
                        existing.getTxnDate(), existing.getTxnDate(), existing.getDescription(),
                        new BigDecimal("900.00"), null)));
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(existing));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-unchanged", "DEBIT", new BigDecimal("500.00"),
                        existing.getTxnDate(), existing.getTxnDate(),
                        existing.getDescription(), new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.changed()).isZero();
        assertThat(existing.isPendingBankCorrection()).isFalse();
        verify(transactions, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void anExistingRowWithATxnIdAbsentFromTheFreshFetchIsMissing() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction vanished = new Transaction();
        vanished.setUserId(userId);
        vanished.setAccountId(accountId);
        vanished.setExternalTxnId("txn-vanished");
        vanished.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        vanished.setTxnDate(LocalDate.now().minusDays(5));
        vanished.setAmount(new BigDecimal("250.00"));
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(vanished));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of());

        assertThat(result.missing()).isEqualTo(1);
        assertThat(vanished.isPendingBankCorrection()).isTrue();
        verify(transactions).save(vanished);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_TRANSACTION_MISSING"),
                eq("Transaction"), any(), anyMap());
    }

    @Test
    void anExistingRowWithNoExternalTxnIdIsNeverReportedAsMissing() {
        // The identity ceiling the scope doc names: without a reliable externalTxnId, "this exact
        // row disappeared" is indistinguishable from "we never had a stable way to track it" --
        // flagging it would be a false positive on exactly the rows least able to support the claim.
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction noStableId = new Transaction();
        noStableId.setAccountId(accountId);
        noStableId.setExternalTxnId(null);
        noStableId.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(noStableId));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of());

        assertThat(result.missing()).isZero();
        assertThat(noStableId.isPendingBankCorrection()).isFalse();
        verifyNoInteractions(auditService);
    }

    @Test
    void aFetchedRowMatchingAnExistingFingerprintWithNoTxnIdIsSkippedNotInserted() {
        // The identity ceiling from the other side: no reliable txnId means a repeat fetch of an
        // already-seen row can only be recognized by fingerprint, exactly like mapNew's old
        // fallback -- and, symmetrically, can never be recognized as "changed" either.
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of());
        when(transactions.existsByAccountIdAndTransactionFingerprint(any(), any())).thenReturn(true);

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction(null, "DEBIT", new BigDecimal("300.00"),
                        LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                        "No stable id", new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.changed()).isZero();
        verifyNoInteractions(auditService);
    }
}
