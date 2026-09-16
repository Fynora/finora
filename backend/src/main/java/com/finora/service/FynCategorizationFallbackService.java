package com.finora.service;

import com.finora.entity.Transaction;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Fyn categorization fallback -- consulted only when the shared corpus and every deterministic
 * layer have nothing for a key (spec docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md
 * §8). A thin orchestration seam kept for CategorizationService's two existing call sites, which
 * this signature must never change for (see the plan's Global Constraints) -- the actual AI work
 * (understanding a merchant, resolving it to one user's own category) lives in
 * MerchantUnderstandingService/UserMerchantCategoryResolutionService, per
 * docs/superpowers/specs/2026-09-15-ai-category-creation-design.md.
 */
@Service
public class FynCategorizationFallbackService {

    private final UserMerchantCategoryResolutionService resolutionService;

    public FynCategorizationFallbackService(UserMerchantCategoryResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    public Optional<String> suggest(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        return resolutionService.resolve(userId, counterpartyKey, direction, description);
    }

    /** Staging/preview's own path -- see {@link UserMerchantCategoryResolutionService#resolveReadOnly}
     *  for why this must never call the LLM or write anything (Bug 36's own precedent). */
    public Optional<String> suggestReadOnly(UUID userId, String counterpartyKey, Transaction.Type direction) {
        return resolutionService.resolveReadOnly(userId, counterpartyKey, direction);
    }

    /** As {@link #suggestReadOnly(UUID, String, Transaction.Type)}, against a {@link
     *  com.finora.imports.ResolutionIndex} the caller built once for the whole statement -- see
     *  that index's own doc comment for why this is needed at all. */
    public Optional<String> suggestReadOnly(UUID userId, String counterpartyKey, Transaction.Type direction,
                                             com.finora.imports.ResolutionIndex resolutionIndex) {
        return resolutionService.resolveReadOnly(userId, counterpartyKey, direction, resolutionIndex);
    }

    public com.finora.imports.ResolutionIndex indexFor(UUID userId) {
        return resolutionService.indexFor(userId);
    }
}
