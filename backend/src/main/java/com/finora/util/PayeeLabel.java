package com.finora.util;

import com.finora.entity.Transaction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The human-readable "who" for a payment: the name printed in the rail's payee slot when the
 * narration has one of the shapes OwnAccountEvidence reads, else the narration itself -- the same
 * fallback TransactionGroupingService's counterparty groups use. Never the counterparty key, which
 * for a name: key is a guess and for a vpa:/cut: key is an id, not a name (CounterpartyIdentity).
 */
public final class PayeeLabel {

    private PayeeLabel() {}

    /**
     * "UPI/&lt;name&gt;/&lt;id&gt;@&lt;bank&gt;/..." -- a card statement's layout, where the segment
     * after the name is the payee's UPI id. OwnAccountEvidence's slash slot reads the second segment
     * as the name (right for "UPI/CR/&lt;ref&gt;/&lt;name&gt;/"), so on this layout it returned the id:
     * measured over the corpus and a tester's statements (2,107 rows), 158 rows labelled with an id.
     * Also after a leading copy of the name ("&lt;name&gt; UPI/&lt;name&gt;/&lt;id&gt;@...", a savings layout).
     */
    private static final Pattern NAME_THEN_UPI_ID = Pattern.compile("(?i)(?:^|\\s)UPI/([^/@]{2,60})/[^/\\s]*@");

    /** @param fallback shown when the row has neither a narration nor a merchant name */
    public static String of(Transaction t, String fallback) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            Matcher m = NAME_THEN_UPI_ID.matcher(description);
            if (m.find()) return m.group(1).trim();
            return OwnAccountEvidence.counterpartySlot(description).map(String::trim).orElse(description.trim());
        }
        return t.getMerchant() == null ? fallback : t.getMerchant();
    }
}
