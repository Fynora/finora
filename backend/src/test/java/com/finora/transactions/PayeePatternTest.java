package com.finora.transactions;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayeePatternTest {

    private static Transaction payment(String amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setAmount(new BigDecimal(amount));
        t.setTxnDate(date);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("UPI/SAMPLE PERSON/REF");
        return t;
    }

    private static List<Transaction> payments(String... amounts) {
        List<Transaction> rows = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 8, 1);
        for (String a : amounts) {
            rows.add(payment(a, date));
            date = date.plusDays(7);
        }
        return rows;
    }

    @Test
    void sevenPaymentsUseTheMiddleHalf() {
        PayeePattern p = PayeePattern.of(payments("100", "150", "200", "250", "300", "400", "1000"));
        assertThat(p.payments()).isEqualTo(7);
        assertThat(p.typicalLow()).isEqualByComparingTo("150");
        assertThat(p.typicalHigh()).isEqualByComparingTo("400");
    }

    @Test
    void twoOrThreePaymentsUseLowestToHighest() {
        PayeePattern two = PayeePattern.of(payments("120.40", "300"));
        assertThat(two.typicalLow()).isEqualByComparingTo("120");
        assertThat(two.typicalHigh()).isEqualByComparingTo("300");
        PayeePattern three = PayeePattern.of(payments("75", "50", "90"));
        assertThat(three.typicalLow()).isEqualByComparingTo("50");
        assertThat(three.typicalHigh()).isEqualByComparingTo("90");
    }

    @Test
    void onePaymentHasNoRange() {
        PayeePattern p = PayeePattern.of(payments("250"));
        assertThat(p.payments()).isEqualTo(1);
        assertThat(p.typicalLow()).isNull();
        assertThat(p.typicalHigh()).isNull();
    }

    @Test
    void fourPaymentsBoundary() {
        PayeePattern p = PayeePattern.of(payments("40", "10", "30", "20"));
        assertThat(p.typicalLow()).isEqualByComparingTo("10");
        assertThat(p.typicalHigh()).isEqualByComparingTo("30");
    }

    @Test
    void roundsHalfUpToWholeRupees() {
        PayeePattern p = PayeePattern.of(payments("149.50", "300.49"));
        assertThat(p.typicalLow()).isEqualByComparingTo("150");
        assertThat(p.typicalHigh()).isEqualByComparingTo("300");
    }

    @Test
    void recentIsNewestFirstAndCappedAtTen() {
        List<Transaction> rows = payments("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        java.util.Collections.shuffle(rows, new java.util.Random(7));
        PayeePattern p = PayeePattern.of(rows);
        assertThat(p.recent()).hasSize(10);
        assertThat(p.recent().get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1).plusDays(77));
        assertThat(p.recent().get(9).date()).isEqualTo(LocalDate.of(2026, 8, 1).plusDays(14));
        assertThat(p.firstDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(p.latestDate()).isEqualTo(LocalDate.of(2026, 8, 1).plusDays(77));
    }

    @Test
    void countsOnlyRealPayments() {
        Transaction real = payment("100", LocalDate.of(2026, 9, 1));
        Transaction superseded = payment("100", LocalDate.of(2026, 9, 1));
        superseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
        Transaction duplicateMark = payment("100", LocalDate.of(2026, 9, 1));
        duplicateMark.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        Transaction duplicateOf = payment("100", LocalDate.of(2026, 9, 1));
        duplicateOf.setIsDuplicateOf(java.util.UUID.randomUUID());
        Transaction transfer = payment("100", LocalDate.of(2026, 9, 1));
        transfer.setTransfer(true);
        Transaction reversal = payment("100", LocalDate.of(2026, 9, 1));
        reversal.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        Transaction refund = payment("100", LocalDate.of(2026, 9, 1));
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);

        assertThat(PayeePattern.counts(real)).isTrue();
        for (Transaction t : List.of(superseded, duplicateMark, duplicateOf, transfer, reversal, refund)) {
            assertThat(PayeePattern.counts(t)).isFalse();
        }
    }

    @Test
    void usesAbsoluteAmounts() {
        PayeePattern p = PayeePattern.of(payments("-200", "-100"));
        assertThat(p.typicalLow()).isEqualByComparingTo("100");
        assertThat(p.typicalHigh()).isEqualByComparingTo("200");
    }
}
