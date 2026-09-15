package com.finora.repository;

import com.finora.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Mirrors PasswordResetTokenRepository.markAllUnusedAsUsed -- burns every still-unused
     *  verification link for this user in one statement, so an earlier link (e.g. from
     *  register(), if loginWithGoogle() later mints a fresh one) can't be replayed after a later
     *  one already verified the account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedAsUsed(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Bug fix: verifyEmail() used to read {@code usedAt}, check it was null in Java, and only
     *  write it back at the very end -- classic check-then-act. Two concurrent requests for the
     *  same raw token (a double-click, or an email client's link-preview bot fetching the
     *  verification URL followed by the real user's own click moments later) could both fetch the
     *  row while it was still unused and both pass that check before either committed. Same "the
     *  mutation itself is the check" pattern as ReferralCodeRepository.resetPlusCounterIfAtLeast:
     *  under Postgres's read-committed row locking, a second concurrent UPDATE targeting the same
     *  row blocks until the first commits, then re-evaluates this WHERE clause against the
     *  now-already-set usedAt and affects zero rows.
     *
     * @return 1 if this call actually claimed the token, 0 if it was already used -- including by
     *         a concurrent request that won the race. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    int claimIfUnused(@Param("id") UUID id, @Param("now") Instant now);

    /** AccountPurgeSweepService -- moot after purge (the account can never log in again to use
     *  it) but cheap to clean up. Hard delete, no soft-delete concern. */
    void deleteByUserId(UUID userId);
}
