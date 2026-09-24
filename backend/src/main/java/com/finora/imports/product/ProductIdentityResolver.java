package com.finora.imports.product;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a product found in a statement is one the user already holds.
 *
 * Scoped to the caller's own accounts, always. Identity is only ever meaningful within one person's
 * holdings -- two customers can hold the same deposit number at different banks, and a resolver
 * that searched globally would be a cross-tenant leak wearing a matching function's clothes.
 *
 * The output is deliberately a three-way decision rather than an {@code Optional<Account>}. A
 * caller that gets back "here is the account" cannot tell a certain match from a guess, and the
 * guess is the case that matters: merging two different deposits corrupts both, and splitting one
 * duplicates it in net worth. {@link Resolution#PROBABLE} exists so that case reaches a human.
 *
 * <p>Security/privacy audit finding F-08 (2026-09-18): {@link #logWeakSignalMatch} used to write
 * the raw IFSC code and account holder name straight into the application log at INFO level --
 * see that method's own doc comment for the fix.
 */
@Service
public class ProductIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(ProductIdentityResolver.class);

    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public ProductIdentityResolver(AccountRepository accountRepository, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    public enum Resolution {
        /** No existing product matches -- this is genuinely new. */
        NEW,
        /** Certain: same institution, same product number. Safe to import into. */
        MATCHED,
        /** Plausible but unproven, or more than one candidate. Never auto-merged. */
        PROBABLE
    }

    /**
     * @param resolution what was concluded
     * @param account    the matched account for {@link Resolution#MATCHED}; the single best
     *                   candidate for {@link Resolution#PROBABLE}, which the review screen offers
     *                   rather than applies; null for {@link Resolution#NEW}
     * @param candidates every account that matched at all -- more than one is itself the reason a
     *                   match is only probable
     * @param reason     why, in words, for the review screen and the import report
     */
    public record ProductMatch(Resolution resolution, Account account, List<Account> candidates,
                               String reason) {

        public boolean mayImportWithoutAsking() { return resolution == Resolution.MATCHED; }
    }

    /** Finds the user's existing product matching {@code discovered}, if any. */
    public ProductMatch resolve(UUID userId, ProductIdentity discovered) {
        if (discovered == null) {
            return new ProductMatch(Resolution.NEW, null, List.of(), "no identity to match on");
        }

        List<Account> exact = new ArrayList<>();
        List<Account> probable = new ArrayList<>();

        for (Account account : accountRepository.findByUserId(userId)) {
            ProductIdentity stored = ProductIdentity.stored(
                    account.getBankId(), typeOf(account),
                    account.getProductIdentityHash(), account.getAccountNumberMasked())
                    .withWeakSignals(account.getIfscCode(), account.getAccountHolderName());
            switch (discovered.matches(stored)) {
                case EXACT -> exact.add(account);
                case PROBABLE -> probable.add(account);
                case NONE -> { }
            }
        }

        // Distinguishes the two ways a PROBABLE below can have been reached, for the reason text
        // and the audit log -- see ProductIdentity's own "When there is no number at all" doc
        // section. Only possible when discovered itself has neither a strong key nor masked digits
        // (matches() requires that before the IFSC+holder fallback ever fires on this side), so this
        // check alone is enough to tell which path produced the result without threading a reason
        // back out of matches() itself.
        boolean viaWeakSignalFallback = discovered.strongKey() == null && discovered.maskedNumber() == null;

        // Two accounts sharing a strong key should be impossible, but "should be impossible" is not
        // a reason to pick one arbitrarily and import a statement into it. Duplicates can already
        // exist from before identity was recorded, which is precisely the mess this feature is
        // meant to stop growing -- so it asks rather than guessing which of them is right.
        if (exact.size() == 1) {
            return new ProductMatch(Resolution.MATCHED, exact.get(0), exact,
                    "same institution and product number as an existing " + typeOf(exact.get(0)));
        }
        if (exact.size() > 1) {
            return new ProductMatch(Resolution.PROBABLE, exact.get(0), exact,
                    exact.size() + " existing accounts share this product number -- "
                            + "they may be duplicates created before product identity was recorded");
        }
        if (probable.size() == 1) {
            if (viaWeakSignalFallback) logWeakSignalMatch(userId, discovered, probable);
            String reason = viaWeakSignalFallback
                    ? "no account number could be extracted from this statement, but an existing "
                            + typeOf(probable.get(0)) + " at the same institution shares its IFSC "
                            + "code and account holder name"
                    : "an existing " + typeOf(probable.get(0)) + " at the same institution ends in "
                            + "the same digits, but the statement gave no full number to confirm it";
            return new ProductMatch(Resolution.PROBABLE, probable.get(0), probable, reason);
        }
        if (probable.size() > 1) {
            if (viaWeakSignalFallback) logWeakSignalMatch(userId, discovered, probable);
            String reason = viaWeakSignalFallback
                    ? probable.size() + " existing accounts share this institution's IFSC code and "
                            + "account holder name; the statement gave no account number to tell "
                            + "them apart"
                    : probable.size() + " existing accounts could be this product; "
                            + "the statement gave no full number to tell them apart";
            return new ProductMatch(Resolution.PROBABLE, null, probable, reason);
        }
        return new ProductMatch(Resolution.NEW, null, List.of(), "no existing product matches");
    }

    /**
     * Audit trail for the IFSC+holder fallback specifically -- this is the exact "why did the
     * resolver say PROBABLE" question a support investigation needs answered, and it is not
     * answerable from the reason string alone once several PROBABLE accounts print similar text.
     *
     * <p>Security/privacy audit finding F-08 (2026-09-18): this used to write the raw IFSC code
     * and account holder name straight into the application log at INFO level -- readable by
     * whatever retention and access the deployment's ordinary log aggregator gives, with no
     * redaction and no record of who looked. The application log now gets only {@link
     * #fingerprint}, a stable one-way value, so repeat occurrences of the SAME real-world evidence
     * are still recognisable as "this happened again" without the log line itself carrying the
     * evidence. The raw evidence moves into {@code audit_logs} instead, via {@link
     * AuditService#recordEvenOnRollback} -- the purpose-built, {@code AUDIT_VIEW}-gated record this
     * codebase already has, with its own explicit retention policy ({@link AuditService}'s BH-044
     * redaction sweep) rather than the log platform's own, generally longer and less controlled,
     * retention. {@code recordEvenOnRollback}, not a plain {@code record}: this fires from inside
     * {@code ImportService.confirm}'s own {@code @Transactional} method, well before that method's
     * later work (building/inserting rows, writing the {@code StatementImport}, reconciliation) --
     * an unrelated failure in any of that must not silently discard the very audit row explaining a
     * PROBABLE decision that had already, correctly, been made.
     */
    private void logWeakSignalMatch(UUID userId, ProductIdentity discovered, List<Account> candidates) {
        List<UUID> candidateIds = candidates.stream().map(Account::getId).toList();
        String fingerprint = fingerprint(discovered);
        log.info("PROBABLE match via IFSC+holder fallback: bank={} fingerprint={} accountIds={} "
                        + "reason=account_number_missing",
                discovered.institutionId(), fingerprint, candidateIds);
        auditService.recordEvenOnRollback(userId, "PRODUCT_IDENTITY_WEAK_SIGNAL_MATCH", "Account",
                candidateIds.get(0), Map.of(
                        "bank", String.valueOf(discovered.institutionId()),
                        "ifsc", String.valueOf(discovered.ifscCode()),
                        "holder", String.valueOf(discovered.accountHolderName()),
                        // String.valueOf, not UUID::toString: a candidate account fetched via
                        // accountRepository.findByUserId always has a real, persisted id in
                        // production, but that guarantee doesn't hold for an in-memory Account
                        // built directly in a test -- found exactly this way, via a real
                        // NullPointerException from this class's own test suite, not guessed at.
                        // UUID::toString throws on a null element (an instance-method reference
                        // calling toString() ON the null); String.valueOf(null) safely returns
                        // the literal string "null", the same null-tolerance the log line above
                        // already had by handing SLF4J the raw (possibly-null-containing) list.
                        "accountIds", candidateIds.stream().map(String::valueOf).toList(),
                        "fingerprint", fingerprint,
                        "reason", "account_number_missing"));
    }

    /**
     * A stable, one-way identifier for one (institution, IFSC, holder name) combination -- lets the
     * application log and a support investigation recognise "this is the same evidence as last
     * time" without the log line itself carrying the account holder's name or IFSC code. Same
     * SHA-256-over-normalized-fields convention {@link ProductIdentity}'s own {@code strongKey}
     * hash already uses, for the same reason: a hash of the exact same fields, computed the exact
     * same way every time, is what makes it stable across occurrences.
     *
     * <p>Only ever called from {@link #logWeakSignalMatch}, itself only reachable when {@code
     * viaWeakSignalFallback} holds -- see {@link #resolve}'s own comment on that flag -- which is
     * exactly the condition under which {@link ProductIdentity#matches} requires {@code ifscCode}
     * and {@code accountHolderName} to both be non-null, so no null-guarding is needed here.
     */
    private static String fingerprint(ProductIdentity discovered) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String material = discovered.institutionId() + ':' + discovered.ifscCode() + ':'
                    + discovered.accountHolderName();
            byte[] out = sha.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is genuinely absent, failing loudly beats
            // silently logging the raw evidence this method exists to avoid logging.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** An account's product type, falling back to its coarse account type for rows created before
     *  V49 backfilled the column (and for accounts a user typed in by hand). */
    private FinancialProductType typeOf(Account account) {
        if (account.getProductType() != null) {
            try {
                FinancialProductType stored = FinancialProductType.valueOf(account.getProductType());
                // UNKNOWN is "detection could not tell", not a product. Seven of eleven real
                // credit-card layouts used to detect UNKNOWN (a classifier false contradiction, since
                // fixed), and the accounts created from them stored it -- so once the same cards
                // started detecting CREDIT_CARD, the masked-number match (which requires the types to
                // agree) stopped recognising them. The account type the user confirmed at creation
                // is the better answer for such an account.
                if (stored != FinancialProductType.UNKNOWN) return stored;
            } catch (IllegalArgumentException e) {
                // A value the enum no longer has -- a renamed constant, or a hand-edited row. Fall
                // through to the account type rather than failing an import over it.
            }
        }
        return Optional.ofNullable(account.getAccountType())
                .map(ProductIdentityResolver::coarseTypeOf)
                .orElse(FinancialProductType.UNKNOWN);
    }

    private static FinancialProductType coarseTypeOf(Account.Type type) {
        return switch (type) {
            case CREDIT_CARD -> FinancialProductType.CREDIT_CARD;
            case WALLET -> FinancialProductType.WALLET;
            case INVESTMENT -> FinancialProductType.UNKNOWN; // too coarse to claim FD vs RD vs fund
            case SAVINGS -> FinancialProductType.SAVINGS;
        };
    }
}
