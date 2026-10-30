package com.finora.repository;

import com.finora.entity.SenderInflowRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SenderInflowRuleRepository extends JpaRepository<SenderInflowRule, UUID> {

    List<SenderInflowRule> findByUserId(UUID userId);

    Optional<SenderInflowRule> findByUserIdAndCounterpartyKey(UUID userId, String counterpartyKey);

    long countByInflowKindId(UUID inflowKindId);

    @Modifying
    @Query("DELETE FROM SenderInflowRule r WHERE r.userId = :userId")
    int hardDeleteByUserId(@Param("userId") UUID userId);

    /** Remembers {@code kindId} for the user's payments from {@code key}: one statement, so two
     *  requests for the same sender at once (a double tap) both succeed, the later choice winning,
     *  rather than one tripping the (user_id, counterparty_key) unique constraint. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO sender_inflow_rules (id, user_id, counterparty_key, inflow_kind_id)
            VALUES (gen_random_uuid(), :userId, :key, :kindId)
            ON CONFLICT (user_id, counterparty_key)
            DO UPDATE SET inflow_kind_id = EXCLUDED.inflow_kind_id, updated_at = now()""", nativeQuery = true)
    int remember(@Param("userId") UUID userId, @Param("key") String key, @Param("kindId") UUID kindId);

    /** Copies the user's rule for {@code oldKey} to {@code newKey} when a row is re-keyed, unless
     *  the new key already has its own rule -- that is the newer answer and is kept. The old rule
     *  stays for rows not yet re-keyed. Returns 0 when there was nothing to carry. */
    @Modifying
    @Query(value = """
            INSERT INTO sender_inflow_rules (id, user_id, counterparty_key, inflow_kind_id)
            SELECT gen_random_uuid(), r.user_id, :newKey, r.inflow_kind_id
            FROM sender_inflow_rules r
            WHERE r.user_id = :userId AND r.counterparty_key = :oldKey
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int carryToNewKey(@Param("userId") UUID userId, @Param("oldKey") String oldKey, @Param("newKey") String newKey);
}
