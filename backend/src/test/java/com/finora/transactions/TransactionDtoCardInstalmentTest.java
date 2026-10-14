package com.finora.transactions;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card instalment row repeats part of an earlier purchase, and every spending total counts both
 * when that purchase is in the ledger too. Nothing told the user so. The row now says what it is on
 * the wire, so a client can mark it; no total reads the flag.
 */
class TransactionDtoCardInstalmentTest {

    @Test
    void aCardInstalmentRowIsMarkedOnTheWire() {
        assertThat(TransactionDto.from(expense("FP EMI 02/06(EXCL TAX   49.40)"), "Loan EMI").cardInstalment()).isTrue();
        assertThat(TransactionDto.from(expense("EMI PRINCIPAL - 1/6, REF# 00000001"), "Loan EMI").cardInstalment()).isTrue();
    }

    @Test
    void theMarkIsReadFromTheRowsWords_notFromItsCategory() {
        // The user may have moved the row to another category; it is still an instalment.
        assertThat(TransactionDto.from(expense("FP EMI 02/06(EXCL TAX   49.40)"), "Shopping").cardInstalment()).isTrue();
        // And a row the user filed under "Loan EMI" by hand is not one because of that.
        assertThat(TransactionDto.from(expense("SAMPLE STORE PURCHASE"), "Loan EMI").cardInstalment()).isFalse();
    }

    @Test
    void anOrdinaryRowIsNotMarked() {
        assertThat(TransactionDto.from(expense("SAMPLE STORE PURCHASE"), "Shopping").cardInstalment()).isFalse();
        assertThat(TransactionDto.from(expense("INTEREST ON EMI"), "Fees/Interest").cardInstalment()).isFalse();
        // A manual entry has no description at all.
        assertThat(TransactionDto.from(expense(null), "Other").cardInstalment()).isFalse();
    }

    @Test
    void aCreditIsNeverMarked() {
        Transaction credit = expense("EMI PRINCIPAL - 1/6, REF# 00000001");
        credit.setTxnType(Transaction.Type.INCOME);

        assertThat(TransactionDto.from(credit, "Other").cardInstalment()).isFalse();
    }

    private static Transaction expense(String description) {
        Transaction t = new Transaction();
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setTags(List.of());
        return t;
    }
}
