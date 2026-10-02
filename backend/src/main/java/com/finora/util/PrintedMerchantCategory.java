package com.finora.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the merchant category a card statement prints beside each purchase as one of our own
 * default categories.
 *
 * <p>Axis ("MERCHANT CATEGORY") and IndusInd ("Merchant Category") print the card network's
 * classification of the shop on every row; until now it was located into the row and never read.
 * On the real statement corpus (2026-10-02) the labels were RESTAURANTS, DEPT STORES /
 * DEPARTMENTAL STORES, RETAIL STORES, MISC STORE, MERCHANDISE, GROCERY & SUPERMARKETS, FOOD
 * PRODUCTS, HOME FURNISHING, FUEL, PETROL, AUTO SERVICES, MOTO, MEDICAL, UTILITIES,
 * TELECOMMUNICATIONS, ELECTRONICS, COMPUTERS, AIRLINES, GST, SERVICES and MISCELLANEOUS.
 *
 * <p>Sid, 2026-10-02: this is used only when the engine would otherwise say "Other" -- our own
 * keyword guess wins when the two disagree (see TransactionNormalizer). A label that names no kind
 * of spending ("SERVICES", "MISCELLANEOUS") maps to nothing, and so does a narration printed in
 * this column on a credit row: neither is evidence of what was bought. Nor is "MOTO", the card
 * industry's mail/telephone-order marker.
 */
public final class PrintedMerchantCategory {

    private PrintedMerchantCategory() {}

    /** First match wins, in this order. Matched as whole words over the lowercased label. */
    private static final Map<Pattern, String> WORDS = new LinkedHashMap<>();
    static {
        WORDS.put(words("restaurant", "restaurants", "eating places", "fast food", "bakeries"), "Dining");
        WORDS.put(words("grocery", "groceries", "supermarket", "supermarkets", "food products"), "Groceries");
        // Not "moto": the card industry's mail/telephone-order marker, a way of paying -- on the
        // corpus it sits beside an online refund -- not a kind of shop.
        WORDS.put(words("fuel", "petrol", "service station", "service stations", "auto services",
                "automotive"), "Transport");
        WORDS.put(words("airline", "airlines", "air travel", "hotels", "lodging", "travel agencies"), "Travel");
        WORDS.put(words("medical", "pharmacy", "pharmacies", "drug stores", "hospital", "hospitals"), "Health");
        WORDS.put(words("utilities", "utility", "telecommunications", "telecom"), "Utilities");
        WORDS.put(words("home furnishing", "home furnishings", "furniture"), "Home & Furnishing");
        // Singular "Departmental Store" and "Computer" are the labels a real Kotak card statement
        // prints in its plain Category column.
        WORDS.put(words("dept stores", "dept store", "departmental stores", "departmental store",
                "department stores", "department store", "retail stores", "retail store", "misc store",
                "merchandise", "electronics", "computers", "computer", "apparel", "clothing"), "Shopping");
        WORDS.put(words("gst"), "Taxes");
    }

    /** A label longer than this is a narration that landed in the column, not a category. */
    private static final int LONGEST_LABEL = 40;

    public static Optional<String> toCategory(String label) {
        if (label == null) return Optional.empty();
        String text = label.toLowerCase().replaceAll("[^a-z&]+", " ").trim();
        if (text.isEmpty() || label.trim().length() > LONGEST_LABEL || label.matches(".*\\d.*")) {
            return Optional.empty();
        }
        for (Map.Entry<Pattern, String> e : WORDS.entrySet()) {
            if (e.getKey().matcher(text).find()) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    private static Pattern words(String... phrases) {
        return Pattern.compile("\\b(?:" + String.join("|", phrases).replace(" ", "\\s+") + ")\\b");
    }
}
