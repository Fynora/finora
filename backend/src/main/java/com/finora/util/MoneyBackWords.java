package com.finora.util;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The words that say a credit is money coming back -- a refund or a reversal -- rather than money
 * earned. One vocabulary for every reader: ReconciliationService's refund pass, FlowClassifier, and
 * {@link BankActivityCategory#isInterestEarned} (interest charged and then refunded or reversed is
 * not interest earned). Kept in this package so the util classes can read it without depending on
 * a service.
 */
public final class MoneyBackWords {

    private MoneyBackWords() {}

    // "reversal" used to live in the refund set. Split out (Phase 1 of the reconciliation roadmap,
    // docs/proposals/reconciliation-evolution-roadmap-proposal.md) because a bank-side reversal
    // ("this payment bounced") and a merchant refund ("this order was returned") are different
    // real-world events that were producing an identical REFUND verdict.
    private static final Set<String> REFUND_KEYWORDS = Set.of(
            "refund", "returned", "chargeback", "credit adjustment", "cancelled", "canceled");
    private static final Set<String> REVERSAL_KEYWORDS = Set.of("reversal", "payment reversed");

    /** "refund" split by a wrapped line ("R EFUND", "REFU ND"), starting at a word. Not the word with
     *  every space removed: that also matched fund names ("INFRASTRUCTURE FUND" -> "...urefund"). */
    private static final Pattern WRAPPED_REFUND = Pattern.compile("(^| )r ?e ?f ?u ?n ?d");

    public static boolean looksLikeRefund(String description) {
        String normalized = CategoryRules.normalize(description);
        return REFUND_KEYWORDS.stream().anyMatch(normalized::contains)
                || WRAPPED_REFUND.matcher(normalized).find();
    }

    public static boolean looksLikeReversal(String description) {
        String normalized = CategoryRules.normalize(description);
        return REVERSAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /**
     * A refund, a reversal, or a reversal word cut off by the statement ("... REVERS"): every reading
     * FlowClassifier takes ahead of interest, so a credit it does not count as interest income is not
     * counted as interest earned anywhere else either.
     */
    public static boolean readsAsMoneyBack(String description) {
        if (description == null || description.isBlank()) return false;
        // The cut-off word exactly, as FlowClassifier reads it: "REVERSE SWEEP" is a deposit coming
        // back, not a payment reversed.
        return looksLikeReversal(description)
                || (" " + CategoryRules.normalize(description) + " ").contains(" revers ")
                || looksLikeRefund(description);
    }
}
