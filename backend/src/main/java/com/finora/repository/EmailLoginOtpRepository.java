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
     *  ever consumed. Scoped by accountScope, not just email -- V52's dual-identity design lets
     *  the same email back a separate USER-scope and ADMIN-scope account, and each has its own
     *  independent cooldown. */
    Optional<EmailLoginOtp> findFirstByEmailAndAccountScopeOrderByCreatedAtDesc(String email, String accountScope);

    /** The code a verify attempt checks against -- at most one row per (email, accountScope) can
     *  be unconsumed at a time, since requesting a new code always burns every prior unconsumed
     *  one for that same scope first (see markAllUnconsumedAsConsumed). Scoped, not just by email,
     *  for the same reason the cooldown check above is -- otherwise a code issued for one of the
     *  two accounts a shared email can back could be looked up and consumed against the other. */
    Optional<EmailLoginOtp> findFirstByEmailAndAccountScopeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, String accountScope);

    /** Mirrors PasswordResetTokenRepository.markAllUnusedAsUsed -- one live code at a time, per
     *  scope. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE EmailLoginOtp o SET o.consumedAt = :now "
            + "WHERE o.email = :email AND o.accountScope = :accountScope AND o.consumedAt IS NULL")
    int markAllUnconsumedAsConsumed(
            @Param("email") String email, @Param("accountScope") String accountScope, @Param("now") Instant now);

    /** AccountPurgeSweepService -- same cleanup every other token repository gets. */
    void deleteByUserId(UUID userId);

    /** EmailLoginOtpRetentionSweepService (code-review addition, Step 12b) -- every row here is
     *  dead within minutes; this bounds how long a sweep outage could let them pile up. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int deleteByCreatedAtBefore(Instant cutoff);
}
