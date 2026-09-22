package com.finora.repository;

import com.finora.entity.EmailLoginOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailLoginOtpRepository extends JpaRepository<EmailLoginOtp, UUID> {

    /** Cooldown check: the resend timer applies regardless of whether the most recent code was
     *  ever consumed. */
    Optional<EmailLoginOtp> findFirstByEmailOrderByCreatedAtDesc(String email);

    /** The code a verify attempt checks against -- at most one row per email can be unconsumed at
     *  a time, since requesting a new code always burns every prior unconsumed one first (see
     *  markAllUnconsumedAsConsumed). */
    Optional<EmailLoginOtp> findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(String email);

    /** Mirrors PasswordResetTokenRepository.markAllUnusedAsUsed -- one live code at a time. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE EmailLoginOtp o SET o.consumedAt = :now WHERE o.email = :email AND o.consumedAt IS NULL")
    int markAllUnconsumedAsConsumed(@Param("email") String email, @Param("now") Instant now);

    /** AccountPurgeSweepService -- same cleanup every other token repository gets. */
    void deleteByUserId(UUID userId);

    /** EmailLoginOtpRetentionSweepService (code-review addition, Step 12b) -- every row here is
     *  dead within minutes; this bounds how long a sweep outage could let them pile up. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int deleteByCreatedAtBefore(Instant cutoff);
}
