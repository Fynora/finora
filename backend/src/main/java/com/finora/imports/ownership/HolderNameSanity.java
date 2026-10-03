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

    /** Letters, spaces, and the punctuation names carry: a title's full stop, an apostrophe
     *  (straight or curly), a hyphenated surname, "&" or "," between joint holders, and brackets
     *  around a suffix such as "(HUF)". Anything else (a digit, "/", ":", "@") is a reference, an
     *  address or a sentence -- except in the tokens below. */
    private static final Pattern NAME_CHARACTERS = Pattern.compile("[\\p{L} .'’&,()-]+");

    /** The one place a "/" belongs in a holder: "M/S" before a business name (the extractor's own
     *  holder patterns accept it as a title) and the relationship markers S/O, D/O, W/O, C/O. Set
     *  aside before the character check, so they neither fail it nor count as a word. */
    private static final Pattern SLASHED_TITLES = Pattern.compile("(?i)(?<![\\p{L}/])(?:m/s|s/o|d/o|w/o|c/o)\\.?(?![\\p{L}/])");

    /** Statement-label vocabulary, each word seen where a holder was read: a card statement's
     *  stacked summary grid ("Previous Balance", "Purchases & Other Charges"), a card's
     *  payment-summary labels ("Total Payment Due Minimum Payment Due Statement Period",
     *  "Available Credit Limit", "TRANSACTION DETAILS") and its GST footer.
     *
     *  <p>Only words no business is named with either. A labelled holder ("Name: ...") can be a
     *  firm -- "M/S ... TRAVEL SERVICES", "... CREDIT SOCIETY", "... CASH &amp; CARRY" -- and those
     *  were accepted before this check existed, so a word such as services, credit, card, cash,
     *  advance, total, code, value or current is deliberately absent: the three fragments actually
     *  stored on a tester's account are refused by their punctuation and by the words below. The
     *  extractor's unlabelled leading-line rule keeps its own, wider list. A word counts after its
     *  punctuation is stripped and case is folded. */
    private static final Set<String> STATEMENT_WORDS = Set.of(
            "account", "statement", "summary", "details", "passbook", "savings", "debit", "bank",
            "name", "interest", "accrued", "previous", "balance", "purchases", "charges",
            "payment", "due", "minimum", "period", "available", "limit", "transaction",
            "supply", "gst", "visit");

    public static boolean isPlausible(String value) {
        if (value == null) return false;
        String trimmed = SLASHED_TITLES.matcher(value).replaceAll(" ").trim();
        if (trimmed.isEmpty() || !NAME_CHARACTERS.matcher(trimmed).matches()) return false;
        boolean hasAWord = false;
        for (String word : trimmed.split("[\\s,&()]+")) {
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
