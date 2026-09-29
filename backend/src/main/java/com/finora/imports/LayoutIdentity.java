package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.entity.Account;
import com.finora.imports.product.FinancialProductType;
import com.finora.util.BankRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which bank and which kind of account a statement layout belongs to -- the key automatic layout
 * profile grouping uses (V244): {@code "<bank id>|<account families>"}, e.g. {@code KOTAK|CREDIT_CARD}.
 *
 * <h2>Account family, not product</h2>
 *
 * <p>The family is the product's {@link Account.Type} (credit card, savings, wallet, investment),
 * not the finer product (savings vs current vs overdraft, FD vs RD). Two reasons. The evidence for
 * past layouts includes the account a confirmed import went into, which only records the family --
 * keying on the finer product would make those two sources disagree on the same layout. And a
 * bank's savings and current statements generally share one format, which is the thing a profile
 * versions.
 *
 * <h2>When there is no identity</h2>
 *
 * <p>{@link #of} returns null -- no automatic grouping -- unless every section names the same known
 * bank and an identified product (not UNKNOWN, loan, insurance...) that maps to an account family.
 *
 * <p>An UNPROVEN product is accepted. Measured on the real 33-statement corpus: 14 statements
 * (almost every savings statement, and both HSBC credit-card ones) carry a correct but unproven
 * product -- requiring proof would have left most savings layouts ungrouped. Every family the
 * relaxed rule produced matched the statement's real kind, and no fingerprint yielded two keys.
 * Grouping is only by bank and family, and {@link LayoutProfileAutoLinker} flags a fingerprint that
 * is later seen as a different bank or family instead of moving it.
 *
 * <h2>"Version" means "the bank's next distinct layout"</h2>
 *
 * <p>A bank can run two layouts at once for one family (the corpus holds two HDFC credit-card
 * layouts), and both join the same profile as consecutive versions in first-seen order. A version
 * number orders layouts by when they appeared; it does not claim the older one was retired.
 */
public record LayoutIdentity(String bankId, String bankName, Set<String> families) {

    public static final Map<String, String> FAMILY_LABELS = Map.of(
            "CREDIT_CARD", "Credit Card",
            "SAVINGS", "Savings / Current",
            "WALLET", "Wallet",
            "INVESTMENT", "Investments");

    public LayoutIdentity {
        families = Set.copyOf(new TreeSet<>(families));
    }

    /** From every staged section's detection; null unless all of them agree confidently. */
    public static LayoutIdentity of(List<DetectedAccountInfo> detected) {
        if (detected == null || detected.isEmpty()) return null;
        String bankId = null;
        String bankName = null;
        Set<String> families = new TreeSet<>();
        for (DetectedAccountInfo info : detected) {
            if (info == null || info.bank() == null) return null;
            String id = info.bank().id();
            if (id == null || BankRegistry.UNKNOWN_ID.equals(id)) return null;
            if (bankId != null && !bankId.equals(id)) return null;
            bankId = id;
            bankName = info.bank().officialName();
            String family = familyOfProduct(info.detectedProduct());
            if (family == null) return null;
            families.add(family);
        }
        return bankId == null ? null : new LayoutIdentity(bankId, bankName == null ? bankId : bankName, families);
    }

    /** A FinancialProductType name's account family, or null when it has none (loan, unknown...). */
    public static String familyOfProduct(String product) {
        if (product == null || product.isBlank()) return null;
        try {
            Account.Type type = FinancialProductType.valueOf(product.trim()).accountType();
            return type == null ? null : type.name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An account's type as a family -- the same vocabulary, since families are Account.Type names. */
    public static String familyOfAccountType(String accountType) {
        if (accountType == null) return null;
        String upper = accountType.trim().toUpperCase(Locale.ROOT);
        return FAMILY_LABELS.containsKey(upper) ? upper : null;
    }

    public String key() {
        return bankId + "|" + String.join(",", new TreeSet<>(families));
    }

    /** "Kotak Mahindra Bank — Credit Card"; a composite statement joins its families with " + ". */
    public String profileName() {
        StringBuilder labels = new StringBuilder();
        for (String family : new TreeSet<>(families)) {
            if (!labels.isEmpty()) labels.append(" + ");
            labels.append(FAMILY_LABELS.getOrDefault(family, family));
        }
        return bankName + " — " + labels;
    }
}
