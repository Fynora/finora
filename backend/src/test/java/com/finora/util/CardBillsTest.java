package com.finora.util;

import com.finora.entity.Category;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A payment to a credit card's bill, by its category or its words. Every value is synthetic. */
class CardBillsTest {

    private static final UUID CARD_BILL = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Map<UUID, Category> CATEGORIES = Map.of(
            CARD_BILL, category("credit card BILL"),
            OTHER, category("Other"));

    private static Category category(String name) {
        Category c = new Category();
        c.setName(name);
        return c;
    }

    private static Transaction txn(Transaction.Type type, UUID categoryId, String description) {
        Transaction t = new Transaction();
        t.setTxnType(type);
        t.setCategoryId(categoryId);
        t.setDescription(description);
        return t;
    }

    @Test
    void categoryNameMatchesIgnoringCase() {
        assertThat(CardBills.isCardBill(txn(Transaction.Type.EXPENSE, CARD_BILL, "SAMPLE PAYMENT"), CATEGORIES)).isTrue();
    }

    @Test
    void descriptionAloneMarksIt() {
        assertThat(CardBills.isCardBill(
                txn(Transaction.Type.EXPENSE, OTHER, "UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-"), CATEGORIES)).isTrue(); // synthetic-ok
    }

    @Test
    void neither_isNotACardBill() {
        assertThat(CardBills.isCardBill(txn(Transaction.Type.EXPENSE, OTHER, "SAMPLE STORE PURCHASE"), CATEGORIES)).isFalse();
        assertThat(CardBills.isCardBill(txn(Transaction.Type.EXPENSE, null, null), CATEGORIES)).isFalse();
    }

    @Test
    void incomeIsNeverACardBill() {
        assertThat(CardBills.isCardBill(txn(Transaction.Type.INCOME, CARD_BILL, "UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-"), CATEGORIES)).isFalse(); // synthetic-ok
    }
}
