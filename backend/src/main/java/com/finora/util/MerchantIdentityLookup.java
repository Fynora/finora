package com.finora.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Answers one question: <b>is there a known merchant entity named in this narration?</b>
 *
 * <h2>Why this exists rather than a direct call to CategoryRules</h2>
 *
 * <p>"Amazon is a known merchant" and "Amazon means Shopping" are different facts. The first is
 * identity; the second is categorization. Only the first belongs in a counterparty decision, and a
 * counterparty classifier reaching into the categorization engine to get it would make "who" depend
 * permanently on "what" -- the wrong direction, because identity is the more foundational layer.
 *
 * <p>This class is the seam. Today it is <i>backed by</i> the {@link CategoryRules} vocabulary,
 * because that is where the merchant names currently happen to live. Callers depend on this
 * abstraction instead, so when merchant identity becomes first-class -- a persisted entity with
 * aliases and a review history -- the backing changes here and no caller moves. The eventual
 * direction is the reverse of today's: {@code CategoryRules -> MerchantIdentityLookup}.
 *
 * <h2>The measurement that forced it</h2>
 *
 * <p>On the real corpus, <b>130 rows were recognised as a known brand by the category layer while
 * the counterparty classifier still answered UNKNOWN</b> -- 24.6% of the entire UNKNOWN bucket by
 * row. A row cannot coherently be "Amazon, Shopping" and "counterparty unknown" at the same time.
 * AMAZON was in fact the single most frequent token among the highest-value UNKNOWN rows.
 *
 * <h2>Not every keyword is an entity</h2>
 *
 * <p>The vocabulary mixes named merchants ("swiggy", "netflix", "zerodha") with mechanism and
 * purpose words ("salary", "atm withdrawal", "emi payment", "mutual fund"). Treating the second
 * group as merchant identity would be a new bug, not a fix: a salary credit does not have a
 * merchant on the other side. {@link #NON_ENTITY_TERMS} names that second group explicitly and the
 * entity set is everything else, so a keyword added to {@code CategoryRules} joins the entity set
 * by default -- which is right for this table, where additions are overwhelmingly brands. A test
 * asserts every term listed here still exists upstream, so a rename cannot leave a stale exclusion
 * silently widening what counts as a merchant.
 */
public final class MerchantIdentityLookup {

    private MerchantIdentityLookup() {}

    /**
     * Keywords that describe a MECHANISM or a PURPOSE rather than naming a business. Generic trade
     * nouns ("restaurant", "pharmacy", "grocery") are here too: they do indicate a business, but
     * they are not an identity, and {@code PersonToPersonTransferDetector.hasBusinessToken} already
     * covers them -- keeping them out of the entity set stops this class quietly becoming a second
     * business-token vocabulary.
     */
    static final Set<String> NON_ENTITY_TERMS = Set.of(
            // purpose / mechanism
            "salary", "payroll", "income tax refund", "stipend",
            "house rent", "rent paid", "rent payment", "monthly rent", "rent due", "landlord",
            "housing society", "maintenance chg",
            "mutual fund", "mutualfunds", "sip", "nps", "ppf", "demat",
            "annual fee", "late fee", "finance charge", "interest charged", "penalty",
            "credit card payment", "card bill payment", "cc payment", "autopay", "neft to", "imps to",
            "loan emi", "emi payment", "emi deduction", "personal loan", "home loan", "car loan",
            "auto loan", "insurance", "premium payment",
            "atm withdrawal", "atm wdl", "cash withdrawal", "cash wdl", "nwd",
            "tuition fee", "school fee", "college fee",
            "donation", "charity", "ngo donation", "gift",
            "hotel booking", "recharge",
            // generic trade nouns -- a business, but not an identity
            "grocery", "supermarket", "restaurant", "cafe", "pharmacy", "hospital", "clinic",
            "petrol", "fuel", "metro", "parking",
            "electricity", "power bill", "water bill", "gas bill", "broadband");

    /** Below this a brand is too short to tell from the start of a person's own id ("ola", "uber"). */
    private static final int MIN_HANDLE_PREFIX = 5;

    private static final Pattern ENTITY_PATTERN;
    private static final Set<String> ENTITY_TERMS;
    private static final List<String> HANDLE_PREFIXES;

    static {
        Set<String> entities = new HashSet<>(CategoryRules.allKeywords());
        entities.removeAll(NON_ENTITY_TERMS);
        ENTITY_TERMS = Collections.unmodifiableSet(entities);
        // One alternation, not one pattern per term: the per-term loop scanned the narration once
        // for each of the vocabulary's terms, which measured as the classifier's costliest step on
        // a long narration. The answer is the same -- some term occurs between the boundaries --
        // since a failed boundary check after one alternative backtracks into the next. Longest
        // terms first, so the engine tries the specific ones before their prefixes.
        // Same word-boundary discipline CategoryRules itself uses -- naive substring matching
        // makes "ola" match inside "cola", which that class documents as a real, evidenced false
        // positive rather than a hypothetical one.
        String alternation = entities.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed().thenComparing(t -> t))
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        ENTITY_PATTERN = Pattern.compile("(?<![a-z0-9])(?:" + alternation + ")(?![a-z0-9])");
        HANDLE_PREFIXES = entities.stream()
                .map(t -> t.replaceAll("[^a-z]", ""))
                .filter(t -> t.length() >= MIN_HANDLE_PREFIX)
                .distinct()
                .toList();
    }

    /** Whether a known merchant entity is named in this narration. */
    public static boolean namesKnownMerchant(String description) {
        if (description == null || description.isBlank()) return false;
        return ENTITY_PATTERN.matcher(CategoryRules.normalize(description)).find();
    }

    /**
     * Whether a payee's UPI id (its local part, before the "@") begins with a known merchant's name:
     * "airtelprepaid..." or "appleservices...". Some banks print the payee's name cut to eight
     * characters ("APPLE ME") and the id cut before its "@", so neither the name nor a merchant
     * handle survives, and the cut name passes as a person's. The id is glued together, so the
     * word-boundary lookup above cannot see the brand in it; this compares letters only. Only a
     * prefix counts, and only a brand of 5+ letters.
     */
    public static boolean handleNamesKnownMerchant(String handle) {
        if (handle == null) return false;
        String letters = handle.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        if (letters.length() < MIN_HANDLE_PREFIX) return false;
        for (String prefix : HANDLE_PREFIXES) {
            if (letters.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The entity vocabulary this lookup currently knows. Exposed for tests and diagnostics. */
    public static Set<String> knownEntityTerms() {
        return ENTITY_TERMS;
    }
}
