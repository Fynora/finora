package com.finora.repository;

import com.finora.entity.Transaction;
import com.finora.entity.UserMerchantCategoryResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserMerchantCategoryResolutionRepository extends JpaRepository<UserMerchantCategoryResolution, UUID> {

    Optional<UserMerchantCategoryResolution> findByUserIdAndCounterpartyKeyAndDirection(
            UUID userId, String counterpartyKey, Transaction.Type direction);

    /** Spec §7's concurrency guard: a losing concurrent insert no-ops instead of throwing or
     *  overwriting -- callers check the returned row count, not an exception, to know who won.
     *  REQUIRES_NEW for the same not-@Transactional-caller reason as the AI-suggestion cache. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO user_merchant_category_resolution
               (id, user_id, counterparty_key, direction, category_id, resolved_at)
           VALUES (gen_random_uuid(), :userId, :counterpartyKey, :direction, :categoryId, :resolvedAt)
           ON CONFLICT (user_id, counterparty_key, direction) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("counterpartyKey") String counterpartyKey,
                        @Param("direction") String direction, @Param("categoryId") UUID categoryId,
                        @Param("resolvedAt") Instant resolvedAt);

    /** Human override (spec §8): unconditional overwrite, unlike insertIfAbsent -- "the most
     *  recent manual correction always wins," not "first write wins." */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO user_merchant_category_resolution
               (id, user_id, counterparty_key, direction, category_id, resolved_at)
           VALUES (gen_random_uuid(), :userId, :counterpartyKey, :direction, :categoryId, :resolvedAt)
           ON CONFLICT (user_id, counterparty_key, direction)
           DO UPDATE SET category_id = :categoryId, resolved_at = :resolvedAt
           """, nativeQuery = true)
    void upsertPinned(@Param("userId") UUID userId, @Param("counterpartyKey") String counterpartyKey,
                       @Param("direction") String direction, @Param("categoryId") UUID categoryId,
                       @Param("resolvedAt") Instant resolvedAt);

    /** Task 6's deletion-dependency count. */
    long countByUserIdAndCategoryId(UUID userId, UUID categoryId);

    /** Task 6's deletion-dependency repoint -- plain bulk update, no merge-conflict scenario
     *  possible (unlike MerchantLearningService's repointCategory): the unique constraint is on
     *  (user_id, counterparty_key, direction), not including category_id, so changing a row's
     *  category_id can never collide with another row. */
    @Modifying
    @Query("UPDATE UserMerchantCategoryResolution r SET r.categoryId = :toCategoryId " +
           "WHERE r.userId = :userId AND r.categoryId = :fromCategoryId")
    void repointCategory(@Param("userId") UUID userId, @Param("fromCategoryId") UUID fromCategoryId,
                          @Param("toCategoryId") UUID toCategoryId);
}
