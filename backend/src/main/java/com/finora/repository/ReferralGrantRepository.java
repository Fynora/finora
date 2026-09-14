package com.finora.repository;

import com.finora.entity.ReferralGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralGrantRepository extends JpaRepository<ReferralGrant, UUID> {

    /** At most one row can ever match -- idx_referral_grants_one_active_per_user (V206). */
    Optional<ReferralGrant> findByUserIdAndStatus(UUID userId, String status);

    /** ReferralGrantSweepService's FIFO activation order. */
    Optional<ReferralGrant> findFirstByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, String status);

    /** ReferralGrantSweepService's expiry sweep. */
    List<ReferralGrant> findByStatusAndExpiresAtBefore(String status, Instant expiresAt);

    /** ReferralGrantSweepService's candidate scan -- every user with at least one queued grant. */
    @Query("select distinct g.userId from ReferralGrant g where g.status = :status")
    List<UUID> findDistinctUserIdsByStatus(@Param("status") String status);

    /** GET /api/v1/referrals/mine -- a user's own grant history, newest first. */
    List<ReferralGrant> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** AccountPurgeSweepService. */
    void deleteByUserId(UUID userId);
}
