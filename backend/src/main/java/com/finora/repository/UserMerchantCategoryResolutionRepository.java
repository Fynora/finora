package com.finora.repository;

import com.finora.entity.Transaction;
import com.finora.entity.UserMerchantCategoryResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserMerchantCategoryResolutionRepository extends JpaRepository<UserMerchantCategoryResolution, UUID> {

    Optional<UserMerchantCategoryResolution> findByUserIdAndCounterpartyKeyAndDirection(
            UUID userId, String counterpartyKey, Transaction.Type direction);

    /** Spec §7's concurrency guard: a losing concurrent insert no-ops instead of throwing or
     *  overwriting -- callers check the returned row count, not an exception, to know who won.
     *
     *  <p>Deliberately NOT {@code REQUIRES_NEW}, unlike the string-only AI-suggestion cache this
     *  was first modeled on ({@code SharedMerchantCategoryAiSuggestionRepository.upsert}):
     *  {@code category_id} here is a real FK to {@code categories}, and {@code
     *  CategorizationService.resolveOrCreateCategory} can create that very row moments earlier in
     *  the SAME caller transaction (e.g. {@code TransactionService.create()}'s own
     *  {@code @Transactional} method). {@code REQUIRES_NEW} suspends that transaction and opens a
     *  brand-new connection, which -- per ordinary Postgres MVCC visibility, not a flake --
     *  cannot see the still-uncommitted category row, and the FK insert fails with "is not present
     *  in table categories" (caught by CI on #1581's merge to main). Participating in the ambient
     *  transaction instead means both writes commit or roll back together, which is also the
     *  correct semantics: a resolution should never survive if the transaction that produced its
     *  category never did.
     *
     *  <p>Still needs a plain {@code @Transactional} (default propagation, REQUIRED) rather than
     *  no annotation at all -- a bare {@code @Modifying} query throws {@code
     *  TransactionRequiredException} when called with no ambient transaction active (e.g. a
     *  repository test calling this directly), and REQUIRED both joins an ambient transaction
     *  when one exists and opens an ordinary new one when it doesn't. */
    @Transactional
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
     *  recent manual correction always wins," not "first write wins."
     *
     *  <p>Not {@code REQUIRES_NEW}, for the same FK-visibility reason as {@code insertIfAbsent}
     *  above -- every real caller (e.g. {@code UserMerchantCategoryResolutionService.pin}, called
     *  from {@code TransactionService}'s manual-correction methods) runs inside an already-open
     *  {@code @Transactional} method that may have just created the very category being pinned.
     *  Plain {@code @Transactional} (REQUIRED), same reasoning as {@code insertIfAbsent} above. */
    @Transactional
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
