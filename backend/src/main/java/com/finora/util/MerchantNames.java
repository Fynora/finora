package com.finora.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The name a merchant is stored under ({@code merchants.canonical_name}) and the name a customer is
 * shown for it.
 *
 * <p>Until 2026-10-03 a new merchant was named after the first four words of its narration, so the
 * name opened with the payment rail and carried whatever followed it: a direction word, a bank
 * code, a UPI handle. Those names are still stored. Since then a structured narration is named
 * after its payee field, but a narration with no such field kept the old rule and its rail word.
 * Measured on the statement corpus (34 statements, 1,965 rows): the old rule gave 1,785 rows a name
 * that opens with a rail word, and the rule that replaced it still gave 264.
 *
 * <p>Two things follow, and they are kept apart on purpose:
 * <ul>
 *   <li>{@link #forNarration} names a NEW merchant. It may only drop words the engine's grouping key
 *       already ignores, because the key is read back from the stored name: a name the key reads
 *       differently stops collecting the payee's later rows.</li>
 *   <li>{@link #forDisplay} decides what a customer reads. It changes nothing stored, so it can drop
 *       more, and it can read the merchant's own narrations for the payee the stored name lost.</li>
 * </ul>
 */
public final class MerchantNames {

    private MerchantNames() {}

    /** The words a bank opens a narration with, and so the words an old name opens with; each with
     *  what a payment is called when the narration names nobody. */
    private static final Map<String, String> RAIL_PAYMENTS = Map.of(
            "upi", "UPI payment",
            "imps", "IMPS payment",
            "neft", "NEFT payment",
            "rtgs", "RTGS payment",
            "mmt", "MMT payment",
            "ach", "ACH payment",
            "nach", "NACH payment",
            "mob", "Mobile banking payment",
            "ib", "Internet banking payment",
            "pos", "Card payment");

    /**
     * A word holding four or more digits in a row: a reference or a masked card number
     * ("512345xxxxxx1234"), which {@link CategoryRules#extractMerchant} strips only when it is
     * digits alone. It is never what a customer should read first.
     */
    private static final Pattern CODE = Pattern.compile("\\d{4,}");

    /**
     * What is left of a generated UPI handle ("q&lt;digits&gt;@ybl") once its digits are stripped:
     * the handle's suffix. It says which app took the payment, never who received it -- unlike an
     * app's own name ("Paytm"), which can be the payee and is therefore not listed.
     */
    private static final Set<String> HANDLE_SUFFIXES = Set.of("ybl", "axl", "ibl");

    /**
     * The name a new merchant is stored under: the payee field of a structured narration, else the
     * narration's leading words without the rail words they open with. A narration that names
     * nobody ("UPI/RRN &lt;n&gt;/UPI") is named after its rail.
     *
     * <p>Only {@link PaymentRailTokens} are dropped. MerchantNormalizationEngine's grouping key
     * skips exactly those words, so the key read back from the stored name is the key the narration
     * was matched by. "ib" and "mob" open a narration too but are not in that list -- the key reads
     * them -- so the stored name keeps them and only {@link #forDisplay} hides them.
     *
     * <p>A card purchase keeps its "Pos". One bank prints the masked card number after it, the
     * stored name keeps that number (the key reads it), and the "Pos" in front is what tells
     * {@link #forDisplay} to hide both.
     */
    public static String forNarration(String description) {
        String payee = CategoryRules.structuredPayee(description);
        if (payee != null) return titleCase(payee);
        String[] words = CategoryRules.extractMerchant(description).split(" ");
        if (!opensWithRail(words) || !PaymentRailTokens.isRailToken(words[0]) || words[0].equals("pos")) {
            return titleCase(String.join(" ", words));
        }
        // Rail words and handle suffixes give the grouping key nothing either, so this name has no
        // key, exactly as the narration has none.
        if (Arrays.stream(words).allMatch(w -> PaymentRailTokens.isRailToken(w) || HANDLE_SUFFIXES.contains(w))) {
            return RAIL_PAYMENTS.get(words[0]);
        }
        int from = 0;
        while (from < words.length && PaymentRailTokens.isRailToken(words[from])) from++;
        // What is left names nobody but the key reads it ("dr"): the name stays whole, so that it
        // still opens with its rail and forDisplay shows it as that rail's payment.
        if (Arrays.stream(words, from, words.length).allMatch(MerchantNames::namesNobody)) {
            return titleCase(String.join(" ", words));
        }
        return titleCase(join(words, from));
    }

    /**
     * The name to show for a merchant stored as {@code storedName}. A name that opens with a rail
     * word is shown from its first word that is not a rail, direction or filler word or a code (see
     * {@link #CODE}); a name with no such word is shown as that rail's payment. Any other name is
     * shown as stored.
     */
    public static String forDisplay(String storedName) {
        if (storedName == null || storedName.isBlank()) return storedName;
        String[] words = storedName.trim().split("\\s+");
        if (!opensWithRail(words)) return storedName;
        if (Arrays.stream(words).allMatch(w -> namesNobody(w) || isCode(w))) {
            return RAIL_PAYMENTS.get(words[0].toLowerCase(Locale.ROOT));
        }
        int from = 0;
        while (from < words.length && (isLeadingNoise(words[from]) || isCode(words[from]))) from++;
        return join(words, from);
    }

    /**
     * {@link #forDisplay(String)} for a merchant whose transactions are at hand. An old name keeps
     * only the narration's first four words, so the payee can be cut short or followed by a handle;
     * the narrations still hold it. A name that opens with a rail word is therefore shown as the
     * payee most of {@code narrations} name -- what the merchant would be called if it were created
     * today -- and falls back to the stored name's own words when none names one.
     */
    public static String forDisplay(String storedName, Collection<String> narrations) {
        if (storedName == null || storedName.isBlank() || narrations == null
                || !opensWithRail(storedName.trim().split("\\s+"))) {
            return forDisplay(storedName);
        }
        // A narration that names nobody, or whose name still opens with a rail the stored name
        // cannot drop, says nothing the stored name does not.
        String payee = mostCommonName(narrations, name -> !RAIL_PAYMENTS.containsValue(name) && !opensWithRail(name.split(" ")));
        return payee != null ? payee : forDisplay(storedName);
    }

    /**
     * The name a merchant would be stored under if {@code narrations} were imported today: the
     * {@link #forNarration} name most of them give, a rail's payment included. Null when there is no
     * narration to read.
     */
    public static String importedNameFor(Collection<String> narrations) {
        return narrations == null ? null : mostCommonName(narrations, name -> true);
    }

    private static String mostCommonName(Collection<String> narrations, java.util.function.Predicate<String> counts) {
        // Sorted, so an even split is settled by the name and not by the order the rows came in.
        Map<String, Integer> votes = new TreeMap<>();
        for (String narration : narrations) {
            if (narration == null || narration.isBlank()) continue;
            String name = forNarration(narration);
            if (counts.test(name)) votes.merge(name, 1, Integer::sum);
        }
        return votes.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Whether a stored name opens with a payment rail, as {@link #forDisplay(String)} reads it. */
    public static boolean opensWithRail(String name) {
        return name != null && !name.isBlank() && opensWithRail(name.trim().split("\\s+"));
    }

    /**
     * Whether these words open with a payment rail. "mmt" opens one bank's IMPS narrations and is
     * also how a card statement abbreviates a travel site, so it counts only when another rail,
     * direction or filler word follows it ("MMT IMPS ..."); on its own it may be the merchant.
     */
    private static boolean opensWithRail(String[] words) {
        String first = words[0].toLowerCase(Locale.ROOT);
        if (!RAIL_PAYMENTS.containsKey(first)) return false;
        return !first.equals("mmt") || (words.length > 1 && isLeadingNoise(words[1]));
    }

    private static boolean isLeadingNoise(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return CategoryRules.isFillerToken(lower) || PaymentRailTokens.isRailToken(lower);
    }

    private static boolean isCode(String word) {
        return CODE.matcher(word).find();
    }

    /** A rail, direction or filler word, or a handle suffix: a word that never names a payee. */
    private static boolean namesNobody(String word) {
        return isLeadingNoise(word) || HANDLE_SUFFIXES.contains(word.toLowerCase(Locale.ROOT));
    }

    private static String join(String[] words, int from) {
        return String.join(" ", Arrays.copyOfRange(words, from, words.length));
    }

    private static String titleCase(String text) {
        StringBuilder sb = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
