package com.finora.imports.ownership;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an extracted account-holder value can be a person's (or a joint holder's) name at all.
 *
 * <p>Every holder rule in the extractor reads a position or a label, so a layout the rule was not
 * written for hands it whatever sits there. Measured on a tester's production imports (2026-10-02):
 * three credit-card statements stored a holder of a lone ".", a summary label, and a footer
 * sentence beginning "/ Place of Supply and GST code details visit ...". Each was saved onto the
 * account and shown in the ownership warning as the statement's holder. None of those can be a
 * name, whichever rule produced it, so they are refused here and the statement reads as having no
 * holder: the honest answer, which the ownership check already handles (NO_HOLDER_FOUND).
 *
 * <p>Checked against every holder the real corpus extracts (32 names over 35 statement sections):
 * titles with or without a full stop, initials, title case and capitals, doubled spaces -- every one
 * extracted exactly as before.
 */
public final class HolderNameSanity {

    private HolderNameSanity() {}

    /** Parse diagnostic: a value read from a holder LABEL could not be a name and was refused.
     *  With no holder found anywhere else, staging flags the layout for an admin
     *  (LayoutReviewService.Reason.HOLDER_NAME_UNREADABLE). */
    public static final String REFUSED_DIAGNOSTIC = "HOLDER_NAME_REFUSED";

    /** Letters, spaces, and the punctuation names carry: a title's full stop, an apostrophe, a
     *  hyphenated surname, and "&" or "," between joint holders. Anything else (a digit, "/", ":",
     *  "@", a bracket) is a reference, an address or a sentence. */
    private static final Pattern NAME_CHARACTERS = Pattern.compile("[\\p{L} .'&,-]+");

    /** Statement vocabulary no person is named after, each word seen where a holder was read:
     *  the extractor's own leading-line vocabulary, a card statement's stacked summary grid
     *  ("Previous Balance", "Purchases & Other Charges", "Cash Advance"), a card's payment-summary
     *  labels ("Total Payment Due Minimum Payment Due Statement Period", "Available Credit Limit",
     *  "TRANSACTION DETAILS") and its GST footer. A word counts after its punctuation is stripped
     *  and case is folded. */
    private static final Set<String> STATEMENT_WORDS = Set.of(
            "account", "statement", "card", "credit", "debit", "savings", "current", "passbook",
            "details", "summary", "bank", "name", "value", "added", "services", "interest", "accrued",
            "previous", "balance", "purchases", "charges", "cash", "advance",
            "total", "payment", "due", "minimum", "period", "available", "limit", "transaction",
            "supply", "gst", "code", "visit");

    public static boolean isPlausible(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (!NAME_CHARACTERS.matcher(trimmed).matches()) return false;
        boolean hasAWord = false;
        for (String word : trimmed.split("[\\s,&]+")) {
            String letters = word.replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
            if (STATEMENT_WORDS.contains(letters)) return false;
            if (letters.length() >= 2) hasAWord = true;
        }
        return hasAWord;
    }

    /** The value trimmed when it {@link #isPlausible can be a name}, otherwise null. */
    public static String orNull(String value) {
        return isPlausible(value) ? value.trim() : null;
    }
}
