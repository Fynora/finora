package com.finora.transactions;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference number and running balance a statement printed beside a row. Both are copied onto
 * the transaction at confirm time (StagedRow.referenceNumber / balanceAfter) and were never sent
 * to a client, so a user could not see either one after import.
 */
class TransactionDtoStatementFactsTest {

    @Test
    void theReferenceNumberAndRunningBalanceReachTheWire() {
        Transaction t = transaction();
        t.setReferenceNumber("REF000000001");
        t.setBalanceAfter(new BigDecimal("12345.67"));

        TransactionDto dto = TransactionDto.from(t, "Other");

        assertThat(dto.referenceNumber()).isEqualTo("REF000000001");
        assertThat(dto.balanceAfter()).isEqualByComparingTo("12345.67");
    }

    @Test
    void aRowWithNeitherPrintedSendsNullsRatherThanAGuess() {
        // Manual entries, card statements with no running balance, and rows imported before these
        // columns existed. Null is the honest answer, and a client renders nothing for it.
        TransactionDto dto = TransactionDto.from(transaction(), "Other");

        assertThat(dto.referenceNumber()).isNull();
        assertThat(dto.balanceAfter()).isNull();
    }

    @Test
    void aNegativeBalanceIsSentAsPrinted() {
        // An overdrawn account prints a negative running balance. It is sent unchanged, sign
        // included, rather than folded into an absolute value.
        Transaction t = transaction();
        t.setBalanceAfter(new BigDecimal("-250.00"));

        assertThat(TransactionDto.from(t, "Other").balanceAfter()).isEqualByComparingTo("-250.00");
    }

    private static Transaction transaction() {
        Transaction t = new Transaction();
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTags(List.of());
        return t;
    }
}
