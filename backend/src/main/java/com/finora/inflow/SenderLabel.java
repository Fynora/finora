package com.finora.inflow;

import com.finora.entity.Transaction;
import com.finora.util.PayeeLabel;

/** The human-readable "who" for a sender -- see {@link PayeeLabel}, which this names for credits. */
final class SenderLabel {

    private SenderLabel() {}

    static String of(Transaction t) {
        return PayeeLabel.of(t, "Unknown sender");
    }
}
