package com.finora.integrations.google.merchant;

import com.finora.dto.ImportDto.DuplicateMatch;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CategoryRules;
import com.finora.util.TextSimilarity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * C6.4, staging-time direction: is a Gmail receipt about to be staged the same purchase as a bank
 * transaction already sitting confirmed in the ledger — design proposal §3.
 *
 * <h2>Why this can't reuse {@link com.finora.imports.DuplicateDetector} as-is</h2>
 *
 * {@code DuplicateDetector} requires exact description equality, which is correct for its own job
 * (CSV/PDF re-import) but structurally cannot fire here: a receipt's description is the merchant
 * domain ({@code "amazon.in"}), a bank line reads {@code "AMZN MKTPLACE 4521"}, and those two
 * strings are never equal. This class matches on amount (exact — the one signal that genuinely
 * should agree) plus a date window (a receipt's send date and a bank's settlement date routinely
 * differ by a day or more) plus merchant-name similarity (comparing the domain's brand token
 * against {@link CategoryRules#extractMerchant}'s reduction of the bank description, the same
 * reduction {@code MerchantNormalizationEngine} already trusts for grouping merchant variants).
 *
 * <h2>Reported as {@code DuplicateMatch}, not a new type</h2>
 *
 * {@code confidence = "LIKELY"} distinguishes this from {@code DuplicateDetector}'s
 * {@code "EXACT"} — {@code DuplicateMatch}'s own doc comment named this exact gap ("a fuzzier
 * tier... would create a real spectrum") before it existed. {@code DuplicateReview.tsx} already
 * renders whatever confidence string it's handed, so no frontend change is needed for a second
 * tier to appear.
 */
@Component
public class GmailReconciliationMatcher {

    /** A receipt's stated date and a bank's settlement date routinely differ by a day or more;
     *  wide enough to catch that, narrow enough that an unrelated same-amount transaction three
     *  weeks later doesn't get pulled in as a candidate. Public: {@code ReconciliationService}'s
     *  cross-source pass (see {@link #findMatchAmongTransactions}) needs the same window to score
     *  a match's date_decay, and duplicating the value would let the two drift apart silently. */
    public static final int DATE_WINDOW_DAYS = 3;

    /** Normalized Levenshtein similarity (1.0 = identical) a receipt's brand token must reach
     *  against some token of the bank description to count as the same business. 0.6 passes
     *  "amazon" vs "amzn" (2 edits / 6 chars ≈ 0.67) without passing unrelated short tokens. */
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /** Below this, a brand token is too short for edit-distance similarity to mean anything —
     *  most any two short tokens are "close" by raw edit count. */
    private static final int MIN_BRAND_TOKEN_LENGTH = 3;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public GmailReconciliationMatcher(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Matches against every LIVE account's transactions, excluding a soft-deleted account's rows
     * which the unscoped query would otherwise keep matching against forever (see
     * {@code TransactionRepository.findByUserIdAndAccountIdIn}'s own doc comment). This search is
     * deliberately cross-account by design -- a Gmail receipt isn't tied to one account until it's
     * matched -- only "including a deleted account's history forever" is what's being fixed here.
     */
    public Optional<DuplicateMatch> findMatch(UUID userId, LocalDate receiptDate,
                                               java.math.BigDecimal amount, String merchantDomain) {
        String brandToken = brandTokenOf(merchantDomain);
        if (brandToken.length() < MIN_BRAND_TOKEN_LENGTH) return Optional.empty();

        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (liveAccountIds.isEmpty()) return Optional.empty();

        List<Transaction> candidates = transactionRepository.findCandidatesForGmailReconciliationAndAccountIdIn(
                userId, amount, receiptDate.minusDays(DATE_WINDOW_DAYS), receiptDate.plusDays(DATE_WINDOW_DAYS),
                liveAccountIds);
        if (candidates.isEmpty()) return Optional.empty();

        List<Transaction> matches = candidates.stream()
                .filter(t -> bestTokenSimilarity(brandToken, t.getDescription()) >= SIMILARITY_THRESHOLD)
                .sorted(Comparator.comparingDouble(
                                (Transaction t) -> bestTokenSimilarity(brandToken, t.getDescription()))
                        .reversed()
                        .thenComparing(t -> Math.abs(t.getTxnDate().toEpochDay() - receiptDate.toEpochDay())))
                .toList();
        if (matches.isEmpty()) return Optional.empty();

        Transaction best = matches.get(0);
        return Optional.of(new DuplicateMatch(
                best.getId(),
                best.getAccountId(),
                best.getTxnDate(),
                best.getDescription(),
                best.getAmount(),
                best.getTxnType() == null ? null : best.getTxnType().name(),
                best.getCreatedAt(),
                matches.size(),
                "LIKELY",
                "Same amount around this date, and the merchant on this transaction looks like "
                        + "the same business as your Gmail receipt."));
    }

    /**
     * The confirmed-transaction-vs-transaction sibling of {@link #findMatch} -- used by {@code
     * ReconciliationService}'s Gmail cross-source pass to find a persisted CSV/PDF-sourced
     * transaction that a persisted {@code GMAIL_IMPORT} transaction appears to duplicate, once
     * both sides are real ledger rows rather than a receipt still being staged.
     *
     * <p>Reduces BOTH descriptions through {@link CategoryRules#extractMerchant} and compares
     * every token pair, rather than {@link #findMatch}'s domain-token split -- a confirmed Gmail
     * transaction's description is whatever {@code descriptionFor(receipt)} chose at staging time
     * (a counterparty name when the receipt had one, a bare domain otherwise), so the "text before
     * the first dot is the brand" assumption {@link #brandTokenOf} makes does not hold once the
     * row is just a persisted {@code Transaction} with no domain field of its own.
     *
     * @param candidates already scoped by amount and date window (see {@link #DATE_WINDOW_DAYS})
     *                   and already excluding other {@code GMAIL_IMPORT} rows -- this method does
     *                   no filtering of its own beyond the merchant-similarity check
     */
    public Optional<Transaction> findMatchAmongTransactions(Transaction gmailTransaction, List<Transaction> candidates) {
        String gmailReduced = CategoryRules.extractMerchant(gmailTransaction.getDescription());
        if (gmailReduced.isBlank()) return Optional.empty();

        return candidates.stream()
                .filter(t -> reducedTokenSimilarity(gmailReduced, t.getDescription()) >= SIMILARITY_THRESHOLD)
                .max(Comparator.<Transaction>comparingDouble(t -> reducedTokenSimilarity(gmailReduced, t.getDescription()))
                        .thenComparing(t -> -Math.abs(t.getTxnDate().toEpochDay() - gmailTransaction.getTxnDate().toEpochDay())));
    }

    /** Best similarity across every (token of {@code reducedA}, token of {@code extractMerchant(rawB)})
     *  pair -- symmetric, unlike {@link #bestTokenSimilarity}, which fixes one side to a single
     *  pre-derived brand token. */
    private static double reducedTokenSimilarity(String reducedA, String rawB) {
        String reducedB = CategoryRules.extractMerchant(rawB);
        double best = 0.0;
        for (String tokenA : reducedA.split(" ")) {
            if (tokenA.isBlank() || tokenA.length() < MIN_BRAND_TOKEN_LENGTH) continue;
            for (String tokenB : reducedB.split(" ")) {
                if (tokenB.isBlank()) continue;
                best = Math.max(best, similarity(tokenA, tokenB));
            }
        }
        return best;
    }

    /** Words that name no merchant in a bank narration or a receipt description: the company
     *  suffixes and site endings a description carries around the actual brand. */
    private static final java.util.Set<String> GENERIC_NAME_TOKENS = java.util.Set.of(
            "india", "limited", "ltd", "pvt", "private", "online", "marketplace", "services",
            "com", "www", "net", "org", "app");

    /** A receipt word has to reach this against some word of the bank narration to count as found.
     *  0.85 is what pass 4c's whole-string rule already used; "dominos" against "domino" scores
     *  0.857 and clears it, "zeptonow" against "zepto" scores 0.625 and does not. */
    private static final double NAME_WORD_THRESHOLD = 0.85;

    /**
     * How well a persisted Gmail row's description names the same merchant as a bank row's
     * narration, for the one pass that removes a row from the totals on a text match (pass 4c, the
     * Account Aggregator against Gmail check).
     *
     * <p>Returns {@code 1.0} when every merchant word of the receipt description is found in the
     * narration, otherwise the whole-string similarity that pass always used, so nothing that
     * matched before stops matching.
     *
     * <p>Why the whole-string similarity alone could not work: a receipt is described by its
     * domain ({@code "instamart.in"}) or a counterparty name, a bank narration is
     * {@code "UPI-SWIGGY INSTAMART 000011112222"}. Compared as two strings the best score over
     * real narrations of eight merchants was 0.38, so the check never fired.
     *
     * <p><b>Every</b> receipt word must be found, not any one: a description of two words
     * ("swiggy instamart") must not match a narration that only says "swiggy", which could be the
     * food arm. Payment-rail words and generic company words are ignored on the receipt side, and
     * a description with no merchant word left never matches by name.
     */
    public static double merchantNameScore(String gmailDescription, String bankDescription) {
        double whole = TextSimilarity.normalizedSimilarity(gmailDescription, bankDescription);
        List<String> receiptWords = merchantWords(gmailDescription);
        if (receiptWords.isEmpty()) return whole;
        List<String> bankWords = words(bankDescription);
        for (String word : receiptWords) {
            boolean found = false;
            for (String bankWord : bankWords) {
                if (TextSimilarity.normalizedSimilarity(word, bankWord) >= NAME_WORD_THRESHOLD) {
                    found = true;
                    break;
                }
            }
            if (!found) return whole;
        }
        return 1.0;
    }

    /** The receipt description's merchant words: at most the first four, as {@link
     *  CategoryRules#extractMerchant} reduces it, minus rail, generic and one/two-letter words. */
    private static List<String> merchantWords(String description) {
        if (description == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String token : CategoryRules.extractMerchant(description).split(" ")) {
            if (token.length() < MIN_BRAND_TOKEN_LENGTH) continue;
            if (com.finora.util.PaymentRailTokens.isRailToken(token)) continue;
            if (GENERIC_NAME_TOKENS.contains(token)) continue;
            out.add(token);
        }
        return out;
    }

    /** Every word of a bank narration, not capped at four the way {@link
     *  CategoryRules#extractMerchant} is: the merchant is often the fifth word of a UPI narration. */
    private static List<String> words(String description) {
        if (description == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String token : CategoryRules.normalize(description).split(" ")) {
            if (token.length() >= MIN_BRAND_TOKEN_LENGTH) out.add(token);
        }
        return out;
    }

    /** {@code "amazon.in"} -> {@code "amazon"}. Domains here are always bare registrable names
     *  ({@code merchant_templates.merchant_domain}'s own seeded rows: {@code "zomato.com"},
     *  never {@code "www.zomato.com"}), so the token before the first dot is the brand. */
    private static String brandTokenOf(String merchantDomain) {
        if (merchantDomain == null) return "";
        int dot = merchantDomain.indexOf('.');
        return (dot < 0 ? merchantDomain : merchantDomain.substring(0, dot)).toLowerCase();
    }

    /** The best similarity between the brand token and any significant token of a bank
     *  description, reduced through {@link CategoryRules#extractMerchant} first so a reference
     *  number embedded in the description (already stripped by that reduction) can't itself be
     *  compared as if it were a merchant name. */
    private static double bestTokenSimilarity(String brandToken, String description) {
        String reduced = CategoryRules.extractMerchant(description);
        double best = 0.0;
        for (String token : reduced.split(" ")) {
            if (token.isBlank()) continue;
            best = Math.max(best, similarity(brandToken, token));
        }
        return best;
    }

    private static double similarity(String a, String b) {
        return TextSimilarity.normalizedSimilarity(a, b);
    }
}
