package com.finora.imports;

import com.finora.entity.Transaction;

import java.util.Map;
import java.util.Optional;

/**
 * Every Tier-2 category resolution a user has (spec §3/§4), pre-indexed for one staging pass, so
 * the read-only AI-fallback cache check costs nothing per row.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code UserMerchantCategoryResolutionService.resolveReadOnly(userId, counterpartyKey,
 * direction)} is a live, unconditional {@code SELECT} on every call -- unlike {@code
 * MerchantNormalizationEngine.resolveReadOnly}, it has no per-transaction memo to amortize it,
 * because staging runs outside any transaction (see {@link MerchantIndex}'s own doc comment for
 * why). Calling it once per row from {@code TransactionNormalizer.normalize} reopens exactly the
 * kind of N+1 {@link MerchantIndex} and the hoisted {@code List<CategoryRule>} already fixed for
 * the other two per-row lookups in this pipeline -- caught by {@code ImportQueryCountIT} the same
 * way those were, the first time the two-tier AI resolution's own read-only path got wired into
 * the staging loop.
 *
 * <p>A user's resolved counterparties are bounded by their own transaction history, not by the
 * statement being staged, so -- like {@link MerchantIndex} -- the whole set can be loaded once,
 * eagerly, outside any transaction, and passed into {@code TransactionNormalizer.normalize} for
 * every row of the statement.
 */
public final class ResolutionIndex {

    private static final ResolutionIndex EMPTY = new ResolutionIndex(Map.of());

    private final Map<Transaction.Type, Map<String, String>> categoryNameByDirectionThenKey;

    /**
     * Built only by {@code UserMerchantCategoryResolutionService.indexFor} (a different package
     * from this one), which is why this constructor is public rather than package-private: only
     * that class knows how to load the underlying resolution rows and resolve their category
     * names -- this class is deliberately just the storage, never the loading logic.
     */
    public ResolutionIndex(Map<Transaction.Type, Map<String, String>> categoryNameByDirectionThenKey) {
        this.categoryNameByDirectionThenKey = categoryNameByDirectionThenKey;
    }

    public static ResolutionIndex empty() {
        return EMPTY;
    }

    public Optional<String> categoryNameFor(String counterpartyKey, Transaction.Type direction) {
        Map<String, String> byKey = categoryNameByDirectionThenKey.get(direction);
        return byKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(counterpartyKey));
    }
}
