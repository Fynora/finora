package com.finora.repository;

import com.finora.entity.MerchantUnderstanding;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface MerchantUnderstandingRepository extends JpaRepository<MerchantUnderstanding, java.util.UUID> {

    Optional<MerchantUnderstanding> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    /** Atomic upsert, same ON CONFLICT shape as SharedMerchantCategoryAiSuggestionRepository's own
     *  -- REQUIRES_NEW because the caller (MerchantUnderstandingService, Task 3) is deliberately
     *  not @Transactional, to avoid holding a DB connection across the LLM HTTP call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO merchant_understanding
               (id, counterparty_key, direction, understanding, model, generated_at)
           VALUES (gen_random_uuid(), :counterpartyKey, :direction, :understanding, :model, :generatedAt)
           ON CONFLICT (counterparty_key, direction)
           DO UPDATE SET understanding = :understanding, model = :model, generated_at = :generatedAt
           """, nativeQuery = true)
    void upsert(@Param("counterpartyKey") String counterpartyKey, @Param("direction") String direction,
                @Param("understanding") String understanding, @Param("model") String model,
                @Param("generatedAt") Instant generatedAt);
}
