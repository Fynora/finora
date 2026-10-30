package com.finora.util;

import com.finora.entity.Transaction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The human-readable "who" for a payment: the name printed in the rail's payee slot when the
 * narration has one of the shapes OwnAccountEvidence reads, else the narration itself -- the same
 * fallback TransactionGroupingService's counterparty groups use, after the row's short name. Never the counterparty key, which
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

    /**
     * @param fallback shown when the row has neither a narration nor a merchant name
     *
     * <p>When the narration has none of those shapes, the row's short name comes before the whole
     * narration: "NEFT_IN:&lt;ref&gt;&lt;IFSC&gt;//&lt;ref&gt;/PAYEE" has no payee slot those shapes read,
     * and Quick sort, the people screens and the money-in review list showed the entire narration as
     * the payer's name while the Ledger row showed the short name. The short name is stored in lower
     * case, so it is given capitals here (see {@link #capitalised}).
     */
    public static String of(Transaction t, String fallback) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            Matcher m = NAME_THEN_UPI_ID.matcher(description);
            if (m.find()) return m.group(1).trim();
            java.util.Optional<String> slot = OwnAccountEvidence.counterpartySlot(description).map(String::trim);
            if (slot.isPresent()) return slot.get();
            if (t.getMerchant() != null && !t.getMerchant().isBlank()) return capitalised(t.getMerchant().trim());
            return description.trim();
        }
        return t.getMerchant() == null ? fallback : t.getMerchant();
    }

    /** Payment rails and banking terms kept in capitals, as the mobile row title keeps them. */
    private static final java.util.Set<String> KEPT_IN_CAPITALS = java.util.Set.of(
            "upi", "neft", "imps", "rtgs", "atm", "pos", "emi", "ecs", "nach", "gst", "tds", "sip");

    /** A lower-case short name with each word capitalised ("sample securities pvt lt" reads "Sample
     *  Securities Pvt Lt"), banking terms in capitals. A name that already has a capital is kept. */
    static String capitalised(String name) {
        if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) return name;
        StringBuilder out = new StringBuilder(name.length());
        for (String word : name.split(" ", -1)) {
            if (out.length() > 0) out.append(' ');
            if (word.isEmpty()) continue;
            out.append(KEPT_IN_CAPITALS.contains(word) ? word.toUpperCase(java.util.Locale.ROOT)
                    : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }
}
