package com.finora.repository;

import com.finora.entity.InsightAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface InsightAcknowledgementRepository extends JpaRepository<InsightAcknowledgement, UUID> {
    List<InsightAcknowledgement> findByUserId(UUID userId);

    /**
     * Inserts the acknowledgement, or does nothing when {@code (user_id, kind, item_key)} is already
     * taken -- one statement, so a double tap or a retried request cannot race into a unique-key
     * error (same reason as {@link RecurringDismissalRepository#insertIfAbsent}). Its own
     * transaction, like ImportFunnelDayRepository#record, so any caller can use it.
     */
    @Transactional
    @Modifying
    @Query(value = """
           INSERT INTO insight_acknowledgements (id, user_id, kind, item_key, acknowledged_at)
           VALUES (gen_random_uuid(), :userId, :kind, :itemKey, now())
           ON CONFLICT (user_id, kind, item_key) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("kind") String kind, @Param("itemKey") String itemKey);

    /** AccountPurgeSweepService -- the ON DELETE CASCADE never fires there, since the purge
     *  anonymizes the {@code users} row rather than deleting it (same trap as recurring_dismissals). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM InsightAcknowledgement a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
