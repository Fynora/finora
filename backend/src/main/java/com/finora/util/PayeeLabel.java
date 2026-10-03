package com.finora.util;

import com.finora.entity.Transaction;

/**
 * The human-readable "who" for a payment: the name printed in the rail's payee slot when the
 * narration has one of the shapes OwnAccountEvidence reads, else the narration itself -- the same
 * fallback TransactionGroupingService's counterparty groups use. Never the counterparty key, which
 * for a name: key is a guess and for a vpa:/cut: key is an id, not a name (CounterpartyIdentity).
 */
public final class PayeeLabel {

    private PayeeLabel() {}

    /** @param fallback shown when the row has neither a narration nor a merchant name */
    public static String of(Transaction t, String fallback) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            return OwnAccountEvidence.counterpartySlot(description).map(String::trim).orElse(description.trim());
        }
        return t.getMerchant() == null ? fallback : t.getMerchant();
    }
}
