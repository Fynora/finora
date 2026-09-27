package com.finora.inflow;

import com.finora.entity.Transaction;
import com.finora.util.OwnAccountEvidence;

/**
 * The human-readable "who" for a sender: the name printed in the rail's sender/payee slot when the
 * narration has one of the shapes OwnAccountEvidence reads, else the narration itself -- the same
 * fallback TransactionGroupingService's counterparty groups use. Never the counterparty key, which
 * for a name: key is a guess (CounterpartyIdentity).
 */
final class SenderLabel {

    private SenderLabel() {}

    static String of(Transaction t) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            return OwnAccountEvidence.counterpartySlot(description).map(String::trim).orElse(description.trim());
        }
        return t.getMerchant() == null ? "Unknown sender" : t.getMerchant();
    }
}
