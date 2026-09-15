package com.finora.repository;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CounterpartyCategoryObservationRepository extends JpaRepository<CounterpartyCategoryObservation, UUID> {

    List<CounterpartyCategoryObservation> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    long countByCounterpartyKeyAndDirectionAndCreatedAtAfter(String counterpartyKey, Transaction.Type direction, Instant after);

    void deleteByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    /**
     * Task 6's retention sweep: keys with exactly 1 distinct voter, unpromoted, whose newest
     * observation predates {@code cutoff1Voter} (spec §4: 6 months) -- OR 2 distinct voters,
     * unpromoted, predating {@code cutoff2Voters} (12 months). Never returns a key with a
     * shared_merchant_category row (promoted observations are retained indefinitely). Bounded by
     * {@code limit}, mirroring CounterpartyBackfillSweepService's bounded-batch precedent.
     */
    @Query(value = """
           SELECT o.counterparty_key, o.direction
           FROM counterparty_category_observation o
           LEFT JOIN shared_merchant_category c
               ON c.counterparty_key = o.counterparty_key AND c.direction = o.direction
           WHERE c.id IS NULL
           GROUP BY o.counterparty_key, o.direction
           HAVING (COUNT(DISTINCT o.user_id) = 1 AND MAX(o.created_at) < :cutoff1Voter)
               OR (COUNT(DISTINCT o.user_id) = 2 AND MAX(o.created_at) < :cutoff2Voters)
           LIMIT :limit
           """, nativeQuery = true)
    List<Object[]> findUnpromotedKeysPastRetention(@Param("cutoff1Voter") Instant cutoff1Voter,
                                                     @Param("cutoff2Voters") Instant cutoff2Voters,
                                                     @Param("limit") int limit);

    /** Task 10's observability: how many distinct keys have ever received at least one
     *  observation, promoted or not -- the denominator for the promotion rate. */
    @Query(value = """
           SELECT COUNT(DISTINCT o.counterparty_key || ':' || o.direction)
           FROM counterparty_category_observation o
           """, nativeQuery = true)
    long countDistinctKeysEverObserved();
}
