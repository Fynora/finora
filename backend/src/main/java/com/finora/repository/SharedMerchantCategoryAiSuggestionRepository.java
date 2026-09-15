package com.finora.repository;

import com.finora.entity.SharedMerchantCategoryAiSuggestion;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SharedMerchantCategoryAiSuggestionRepository extends JpaRepository<SharedMerchantCategoryAiSuggestion, UUID> {

    Optional<SharedMerchantCategoryAiSuggestion> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    /** Atomic upsert -- mirrors MerchantCategoryLearningRepository.ensurePairExists's ON CONFLICT
     *  pattern. An AI suggestion is a cache entry (spec §4/§8): replace, don't accumulate.
     *  {@code REQUIRES_NEW}, unlike ensurePairExists -- mirrors RegisteredLayoutRepository's own
     *  upsert precedent instead: FynCategorizationFallbackService.suggest() (Task 8) is
     *  deliberately not @Transactional (avoids holding a DB connection across the LLM HTTP call),
     *  so this write needs its own short transaction rather than relying on a caller's. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO shared_merchant_category_ai_suggestion
               (id, counterparty_key, direction, category, model, generated_at)
           VALUES (gen_random_uuid(), :counterpartyKey, :direction, :category, :model, :generatedAt)
           ON CONFLICT (counterparty_key, direction)
           DO UPDATE SET category = :category, model = :model, generated_at = :generatedAt
           """, nativeQuery = true)
    void upsert(@Param("counterpartyKey") String counterpartyKey, @Param("direction") String direction,
                @Param("category") String category, @Param("model") String model,
                @Param("generatedAt") Instant generatedAt);
}
