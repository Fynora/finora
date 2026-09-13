package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorTransactionMapperTest {

    private TransactionRepository transactionRepository;
    private AccountAggregatorTransactionMapper mapper;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        mapper = new AccountAggregatorTransactionMapper(transactionRepository);
        when(transactionRepository.existsByAccountIdAndExternalTxnId(any(), any())).thenReturn(false);
        when(transactionRepository.existsByAccountIdAndTransactionFingerprint(any(), any())).thenReturn(false);
    }

    @Test
    void mapsADebitTransactionToAnExpense() {
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-1", "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).hasSize(1);
        Transaction txn = mapped.get(0);
        assertThat(txn.getUserId()).isEqualTo(userId);
        assertThat(txn.getAccountId()).isEqualTo(accountId);
        assertThat(txn.getTxnType()).isEqualTo(Transaction.Type.EXPENSE);
        assertThat(txn.getAmount()).isEqualByComparingTo("450.00");
        assertThat(txn.getSource()).isEqualTo(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(txn.getExternalTxnId()).isEqualTo("txn-1");
        assertThat(txn.getTransactionFingerprint()).isNotBlank();
        assertThat(txn.getBalanceAfter()).isEqualByComparingTo("10450.00");
        assertThat(txn.getDescription()).isEqualTo("UPI-SWIGGY-PAYMENT");
    }

    @Test
    void mapsACreditTransactionToIncome() {
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-2", "CREDIT", new BigDecimal("50000.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "NEFT-SALARY", new BigDecimal("60000.00"), "ref-2");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped.get(0).getTxnType()).isEqualTo(Transaction.Type.INCOME);
    }

    @Test
    void skipsATransactionAlreadySeenByExternalTxnId() {
        when(transactionRepository.existsByAccountIdAndExternalTxnId(accountId, "txn-1")).thenReturn(true);
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-1", "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).isEmpty();
    }

    @Test
    void fallsBackToFingerprintWhenExternalTxnIdIsAbsent() {
        // No txnId at all -- some FIPs may not populate it (see the design spec's "Transaction
        // identity" section). Dedup must still work off the fingerprint alone.
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                null, "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");
        when(transactionRepository.existsByAccountIdAndTransactionFingerprint(eq(accountId), any()))
                .thenReturn(true);

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).isEmpty();
    }

    @Test
    void twoTransactionsWithTheSameFingerprintInputsStillGetTheSameFingerprint() {
        SetuFiDataTransaction a = new SetuFiDataTransaction(
                "txn-a", "DEBIT", new BigDecimal("100.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-REFCODE-PAYMENT", new BigDecimal("900.00"), "ref-a");
        SetuFiDataTransaction b = new SetuFiDataTransaction(
                "txn-b", "DEBIT", new BigDecimal("100.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-REFCODE-PAYMENT", new BigDecimal("800.00"), "ref-b");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(a, b));

        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).getTransactionFingerprint())
                .isEqualTo(mapped.get(1).getTransactionFingerprint());
    }
}
