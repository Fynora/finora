package com.finora.util;

import com.finora.entity.Category;
import com.finora.entity.Transaction;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A payment to a credit card's bill, when reconciliation has not linked it to a card statement (a
 * linked one is already left out of spending, see TransactionGraphService#ccPaymentFromTransactionIds).
 * Sid, 2026-10-16: either signal marks it -- the row's category is "Credit Card bill", or its
 * narration reads as a card bill ({@link BankActivityCategory#paysCardBill}). Such a bill is a whole
 * month of card purchases paid at once, so Insights never calls it a spending spike; it still counts
 * as spending. Design: docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md.
 */
public final class CardBills {

    /** A user-made category (no migration seeds it); matched ignoring case. */
    static final String CARD_BILL_CATEGORY = "credit card bill";

    private CardBills() {}

    public static boolean isCardBill(Transaction t, Map<UUID, Category> categoriesById) {
        if (t.getTxnType() != Transaction.Type.EXPENSE) return false;
        Category category = t.getCategoryId() == null ? null : categoriesById.get(t.getCategoryId());
        if (category != null && category.getName() != null
                && CARD_BILL_CATEGORY.equals(category.getName().trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return BankActivityCategory.paysCardBill(t.getDescription());
    }
}
