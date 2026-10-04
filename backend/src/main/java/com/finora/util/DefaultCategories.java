package com.finora.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The categories every user has: seeded at registration (AuthService), backfilled for existing
 * users by migrations, and system rows a user cannot rename or delete. Also the only names that
 * may travel between users -- the shared corpus and admin global rules name one of these, never a
 * category one user made for themselves (see {@link #canonical}).
 */
public final class DefaultCategories {

    // Mirrors the prototype's starter category list, expanded (see V11 migration, which backfills
    // the same additions for existing users) beyond the original 13 to cover common real-life
    // cases the first pass didn't: repaying a friend, EMIs, insurance premiums, and so on, so users
    // aren't stuck recategorizing everything as "Other" or hand-creating categories one at a time.
    private static final Map<String, String[]> ICON_AND_COLOR = new LinkedHashMap<>();
    static {
        ICON_AND_COLOR.put("Salary", new String[]{"arrow-down-circle", "green"});
        // Money a bank or card pays you -- interest credited, cashback (V246, Sid 2026-10-02). Next
        // to Salary as the other money-in category; same icon, a different colour so the two stay
        // tellable apart. Routed by BankActivityCategory.
        ICON_AND_COLOR.put("Interest & Cashback", new String[]{"arrow-down-circle", "teal"});
        ICON_AND_COLOR.put("Rent", new String[]{"home", "blue"});
        ICON_AND_COLOR.put("Groceries", new String[]{"shopping-cart", "green"});
        ICON_AND_COLOR.put("Dining", new String[]{"utensils", "orange"});
        ICON_AND_COLOR.put("Transport", new String[]{"car", "gray"});
        ICON_AND_COLOR.put("Utilities", new String[]{"zap", "yellow"});
        ICON_AND_COLOR.put("Shopping", new String[]{"shopping-bag", "purple"});
        ICON_AND_COLOR.put("Health", new String[]{"heart-pulse", "red"});
        // Salons and beauty parlours (V247, Sid 2026-10-02). Routed by ShopTradeCategory.
        ICON_AND_COLOR.put("Personal Care", new String[]{"scissors", "pink"});
        ICON_AND_COLOR.put("Entertainment", new String[]{"film", "pink"});
        ICON_AND_COLOR.put("Investments", new String[]{"trending-up", "teal"});
        ICON_AND_COLOR.put("Fees/Interest", new String[]{"percent", "gray"});
        ICON_AND_COLOR.put("Transfer", new String[]{"repeat", "blue"});
        ICON_AND_COLOR.put("Friend Repayment", new String[]{"users", "teal"});
        // The 26th category (named "Paid a Person" in V123, renamed direction-neutral by V124 --
        // the detector never looked at direction, so an outbound-sounding name was wrong for the
        // ~23% of its rows that are money received). Placed next to its two nearest neighbours so a
        // user reading their category list meets the three people-shaped options together. This is
        // where CategorizationService.P2P_CATEGORY routes a structurally-detected payment to a
        // named individual. Deliberately a WEAKER claim than either neighbour -- "Transfer" asserts
        // no spending occurred and "Friend Repayment" asserts a debt was settled, and the detector
        // has evidence for neither. See P2P_CATEGORY's own comment for why this stopped being
        // "Transfer". Reuses the existing `users` icon token (CategoryPalette.ICONS is a closed
        // vocabulary and both clients map icons by TOKEN, not by category name, so no client change
        // is needed), in a different colour from Friend Repayment so the two stay tellable apart.
        ICON_AND_COLOR.put("Personal Transfer", new String[]{"users", "orange"});
        ICON_AND_COLOR.put("Loan EMI", new String[]{"landmark", "red"});
        ICON_AND_COLOR.put("Insurance", new String[]{"shield", "blue"});
        ICON_AND_COLOR.put("Education", new String[]{"graduation-cap", "purple"});
        ICON_AND_COLOR.put("Subscriptions", new String[]{"refresh-cw", "pink"});
        ICON_AND_COLOR.put("Travel", new String[]{"plane", "teal"});
        ICON_AND_COLOR.put("Gifts & Donations", new String[]{"gift", "pink"});
        ICON_AND_COLOR.put("Pets", new String[]{"paw-print", "orange"});
        ICON_AND_COLOR.put("Home & Furnishing", new String[]{"sofa", "yellow"});
        ICON_AND_COLOR.put("Taxes", new String[]{"receipt", "gray"});
        ICON_AND_COLOR.put("Cash Withdrawal", new String[]{"banknote", "green"});
        ICON_AND_COLOR.put("Business Expenses", new String[]{"briefcase", "blue"});
        ICON_AND_COLOR.put("Other", new String[]{"tag", "gray"});
    }

    private static final Map<String, String> BY_KEY = new LinkedHashMap<>();
    static {
        for (String name : ICON_AND_COLOR.keySet()) BY_KEY.put(key(name), name);
    }

    private DefaultCategories() {}

    /** Name to {icon, color}, in seeding order. */
    public static Map<String, String[]> iconAndColor() {
        return Collections.unmodifiableMap(ICON_AND_COLOR);
    }

    /**
     * The default category this name means, in its seeded spelling -- trimmed and ignoring case,
     * the same way {@code CategorizationService.resolveOrCreateCategory} matches -- or empty when
     * the name is not a default category (one a user made themselves, or blank/null).
     */
    public static Optional<String> canonical(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(BY_KEY.get(key(name)));
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
