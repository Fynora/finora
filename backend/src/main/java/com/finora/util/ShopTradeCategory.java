package com.finora.util;

import com.finora.entity.Transaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The category of a payment to a small shop whose NAME says what it sells -- "... MEDICAL",
 * "... NASHTA HOUSE", "... GENERAL STORES", "... BEAUTY PARLOUR".
 *
 * <p>Most payments to small shops go through a merchant QR code, and the payee is printed as the
 * shop's own name: no brand the keyword table knows, often a person's name with a trade word after
 * it. Measured on the real statement corpus (2026-10-02), about seventy "Other" rows carried such a
 * word; the trade words and their categories below were decided with Sid on 2026-10-02.
 *
 * <p>Separate from {@link CategoryRules}' keyword table on purpose. That table's order is match
 * priority for every caller, its Transfer, Salary and Investments answers are read by
 * reconciliation, and its vocabulary is merchant identity for {@link MerchantIdentityLookup}. A
 * trade word is weaker evidence than a brand: "hotel" placed there would beat "hotel booking"
 * (Travel), and "medical" would make every shop with that word a known merchant. Here it only ever
 * replaces "Other".
 *
 * <p>Reads the PAYEE name only ({@link CategoryRules#extractMerchantLabel}), never the whole
 * narration: a note the payer typed ("Milk", "Furniture") and a bank's own field are not the
 * payee's trade. Money going out only -- money coming in from a shop is a refund, not a purchase.
 *
 * <p>Runs after the keyword table, the shared corpus and the AI cache, and BEFORE the
 * person-transfer rule: a shop named "SAMPLE HAIR STUDIO" reads as a person's name to that rule,
 * and the trade word is the better evidence.
 *
 * <p>Deliberately not matched, each a trap measured on the corpus: "enterprises" and "traders"
 * (any business), a bare "store" or "shop", "food"/"foods" (a restaurant operator, a fish seller and
 * a food store alike), "motors" (a dealer, a workshop or a loan), "labs" (a digital company), a bare
 * "hair" (a hair-transplant clinic is health care) and a bare "auto" (the bank's "auto debit").
 */
public final class ShopTradeCategory {

    /** The default category for salons and beauty parlours -- see V247. */
    public static final String PERSONAL_CARE = "Personal Care";

    private ShopTradeCategory() {}

    /**
     * First match wins, in this order. The specific trades come first, so a shop named for its
     * trade ("... FURNITURE MART", "... HOTEL ... MUTTON ...") follows that trade; "mart" alone,
     * the most general word here, is last.
     */
    private static final Pattern MART = words("mart");
    private static final Map<Pattern, String> TRADES = new LinkedHashMap<>();
    static {
        TRADES.put(words("medical", "medicals", "medico", "medicos", "chemist", "chemists"), "Health");
        // "hotel": Sid, 2026-10-02 -- a small "HOTEL <name>" paid by shop QR is an eatery. A hotel
        // booking is already Travel in the keyword table, which runs first.
        TRADES.put(words("hotel", "nashta", "dhaba", "caters", "caterers", "snacks", "juice", "kitchen",
                "bakery", "tea stall", "sweets"), "Dining");
        TRADES.put(words("beauty", "salon", "saloon", "hair studio", "hair salon", "haircut", "hair cut"),
                PERSONAL_CARE);
        // "auto centr": the narration cuts the payee at fifteen characters on one corpus layout.
        TRADES.put(words("petroleum", "auto care", "auto centre", "auto center", "auto centr"), "Transport");
        TRADES.put(words("hardware", "furniture"), "Home & Furnishing");
        TRADES.put(words("general store", "general stores", "provision", "provisions", "kirana", "dairy",
                "mutton shop", "chicken shop", "meat shop"), "Groceries");
        TRADES.put(MART, "Groceries");
    }

    /**
     * A payee these words describe is not the trade its name mentions, so no trade decides it: a
     * college or school is paid fees ("... MEDICAL COLLEGE" is education or a hospital, and the
     * name cannot say which), and a seller of a trade's equipment is not that trade ("... KITCHEN
     * APPLIANCES", "... HOTEL EQUIPMENT"). Read over the whole narration, not the payee's first
     * four words, because a college's name is often longer than that; a match only ever leaves the
     * row "Other".
     */
    private static final Pattern NOT_THE_TRADE = words(
            "college", "colleges", "university", "school", "schools", "academy",
            "appliances", "appliance", "equipment", "equipments");

    /** Merchandise that makes a "mart" something other than a grocer. */
    private static final Pattern NOT_A_GROCERY_MART = words(
            "mobile", "mobiles", "shoe", "shoes", "footwear", "fashion", "fashions", "garment", "garments",
            "cloth", "clothing", "electronics", "electricals", "book", "books", "toys", "gift", "gifts",
            "mega");

    /** What a car or pet "salon" grooms. */
    private static final Pattern NOT_A_PERSON = words("car", "cars", "bike", "bikes", "pet", "pets", "dog", "dogs");

    public static Optional<String> of(String description, Transaction.Type direction) {
        if (direction != Transaction.Type.EXPENSE || description == null || description.isBlank()) {
            return Optional.empty();
        }
        String payee = CategoryRules.extractMerchantLabel(description);
        if (payee == null || payee.isBlank()) return Optional.empty();
        if (NOT_THE_TRADE.matcher(CategoryRules.normalize(description)).find()) return Optional.empty();
        String text = CategoryRules.normalize(payee);
        for (Map.Entry<Pattern, String> e : TRADES.entrySet()) {
            if (!e.getKey().matcher(text).find()) continue;
            String category = e.getValue();
            if (PERSONAL_CARE.equals(category) && NOT_A_PERSON.matcher(text).find()) return Optional.empty();
            if (e.getKey() == MART && NOT_A_GROCERY_MART.matcher(text).find()) return Optional.empty();
            return Optional.of(category);
        }
        return Optional.empty();
    }

    /** Phrases are lowercase letters and single spaces -- the alphabet normalize() leaves. */
    private static Pattern words(String... phrases) {
        return Pattern.compile("\\b(?:" + String.join("|", phrases) + ")\\b");
    }
}
