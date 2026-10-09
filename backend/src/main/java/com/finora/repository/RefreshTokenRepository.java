package com.finora.repository;

import com.finora.entity.RefreshToken;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The owner of a token, read as a bare column rather than as the entity. For
     * {@code RefreshTokenService.resolveUserId}, which runs in the same transaction as the
     * {@link #findByTokenHashForUpdate} that follows it in {@code AuthService.refresh}. Loading
     * the entity here left it in the persistence context, and the locked read then compared the
     * row a concurrent refresh had just committed against that stale copy and threw
     * {@code StaleObjectStateException} -- so a double refresh through the endpoint still failed
     * the request (and signed the client out) despite the row lock meant to give it the grace path.
     */
    @Query("SELECT r.userId FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<UUID> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * The same row, read under {@code SELECT ... FOR UPDATE} so that two requests presenting the
     * same token serialise inside {@link com.finora.service.RefreshTokenService#rotate} rather
     * than racing it. Audit F-11 (2026-09-24): with the plain read, both callers passed the
     * "not yet revoked" check, the loser's save hit {@code @Version} and the client saw a 409,
     * which every client treats as the session ending. Under the lock the second caller reads
     * the row only after the first has committed, sees {@code rotatedAt}, and takes the grace
     * path instead. Must be called inside a transaction; the lock is held until it commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Every unrevoked row of a user, expired or not. The revocations themselves are the bulk
     *  updates further down; this is the read tests use to check what they left live. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /** Backs the device-management list endpoint — unlike findByUserIdAndRevokedAtIsNull above,
     *  also excludes tokens that have simply expired without ever being explicitly revoked, since
     *  those can no longer be used to refresh and so aren't a real "active session" to show or
     *  let the user sign out of.
     *
     * <p>Bug 28. A derived-name query here compiled to a plain {@code ORDER BY last_seen_at DESC},
     * and Postgres's default null ordering for DESC is NULLS FIRST — so a row with no
     * {@code lastSeenAt} (captureDeviceMetadata skips it silently when there's no live request
     * context, see RefreshTokenService) sorted ahead of every genuinely-recent session instead of
     * behind them. {@code NULLS LAST} makes the explicit intent (documented above: most-recent
     * first) hold regardless of the database's default. */
    @Query("SELECT r FROM RefreshToken r WHERE r.userId = :userId AND r.revokedAt IS NULL "
            + "AND r.expiresAt > :now ORDER BY r.lastSeenAt DESC NULLS LAST")
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
            @Param("userId") UUID userId, @Param("now") Instant now);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    /** Whether any row of this session was ended by a remote revocation -- see
     *  {@link RefreshToken#getRevokedRemotelyAt()}. */
    boolean existsBySessionIdAndRevokedRemotelyAtIsNotNull(UUID sessionId);

    // The four bulk revocations below replace a read of the live rows followed by saveAll(). That
    // form lost to any refresh of one of those rows already in progress: its save failed @Version,
    // and the sign-out (or the password change or reset it belonged to) failed with it. A bulk
    // UPDATE instead waits for that refresh to commit, then Postgres re-checks
    // "revoked_at IS NULL" against the row it wrote and skips it. The successor the refresh
    // inserted is outside that statement's snapshot, which is why RefreshTokenService repeats the
    // statement until a fresh read finds nothing live. version is incremented by hand because a
    // bulk UPDATE bypasses @Version, and a stale copy of one of these rows elsewhere must still
    // fail its own save. Each must run inside a transaction.

    /** Logout: every live row of one session, as an ordinary revocation (no remote stamp). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.version = r.version + 1 "
            + "WHERE r.userId = :userId AND r.sessionId = :sessionId AND r.revokedAt IS NULL")
    int endLiveRowsOfSession(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId,
                             @Param("now") Instant now);

    /** "Sign out this device": every live row of one session, stamped as ended remotely. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.revokedRemotelyAt = :now, r.version = r.version + 1 "
            + "WHERE r.userId = :userId AND r.sessionId = :sessionId AND r.revokedAt IS NULL")
    int endLiveRowsOfSessionRemotely(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId,
                                     @Param("now") Instant now);

    /** Account-wide: every live row of the user, stamped as ended remotely. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.revokedRemotelyAt = :now, r.version = r.version + 1 "
            + "WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int endLiveRowsOfUserRemotely(@Param("userId") UUID userId, @Param("now") Instant now);

    /** "Sign out other devices": every live row of the user outside one session, stamped as
     *  ended remotely. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.revokedRemotelyAt = :now, r.version = r.version + 1 "
            + "WHERE r.userId = :userId AND r.sessionId <> :keptSessionId AND r.revokedAt IS NULL")
    int endLiveRowsOfOtherSessionsRemotely(@Param("userId") UUID userId,
                                           @Param("keptSessionId") UUID keptSessionId,
                                           @Param("now") Instant now);

    boolean existsByUserIdAndSessionIdAndRevokedAtIsNull(UUID userId, UUID sessionId);

    boolean existsByUserIdAndRevokedAtIsNull(UUID userId);

    boolean existsByUserIdAndSessionIdNotAndRevokedAtIsNull(UUID userId, UUID keptSessionId);

    /**
     * Whether a session still has a live refresh token, and therefore still exists at all.
     *
     * <p>Read on every authenticated request by {@link com.finora.security.SessionValidator}, which
     * is what stops an access token outliving the revocation of the session that minted it. A
     * session is alive exactly while ONE of its rows is unrevoked and unexpired: rotation revokes
     * the presented row and writes a successor carrying the same {@code session_id}, so a session
     * accumulates one revoked row per refresh and this must not be written as "no revoked rows
     * exist" — that reading would end every session at its first rotation.
     *
     * <p>{@code expires_at} is checked as well as {@code revoked_at} because expiry is silent:
     * nothing writes {@code revoked_at} when a refresh token simply ages out, so a session whose
     * only row expired would otherwise still count as live.
     *
     * <p>Backed by {@code idx_refresh_tokens_live_session} (V71), a partial index on exactly the
     * rows this predicate keeps.
     */
    boolean existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(UUID sessionId, Instant now);

    /** AccountPurgeSweepService -- every row already revoked by requestDeletion() by this point;
     *  this removes the residual device/IP labels too. Hard delete, no soft-delete concern. */
    void deleteByUserId(UUID userId);

    /**
     * Bug 14 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md) / RefreshTokenService.sweepExpiredTokens.
     *
     * <p>{@code expiresAt} ALONE, deliberately -- not {@code OR revokedAt IS NOT NULL}, which the
     * bug report's own reproduction query used. {@code rotate()} never updates {@code expiresAt}
     * when it revokes the presented row (see RefreshToken's own history), so a rotated row's
     * {@code expiresAt} still marks the FULL lifetime the original token would have had. That
     * matters because {@code rotate()}'s reuse-detection depends on a revoked row still existing
     * when the STOLEN copy of it is replayed ({@code findByTokenHash} finding it with
     * {@code revokedAt != null} is what triggers "revoke every session for this user" -- see that
     * method's own doc comment). Deleting on revocation alone would let an attacker's replay of an
     * already-cleaned-up stolen token fall through to a generic "invalid token" 401 instead of
     * tripping that response, for however much of the token's original lifetime remained
     * unexpired. Keying on {@code expiresAt} only preserves that detection window for a row's
     * entire natural lifetime, whether it was rotated early or simply expired unused.
     */
    List<RefreshToken> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant now, Pageable pageable);

    /** D-28 PR4-C: the reuse proposal §4 asks for -- "check device/IP overlap between
     *  referrer_user_id and referred_user_id's sessions before crediting a reward, rather than
     *  building a parallel fingerprinting system." Distinct, non-null IPs only: a null
     *  {@code last_seen_ip} (never populated, e.g. a row created before this column existed) must
     *  never be treated as a shared signal between two accounts. */
    @Query("SELECT DISTINCT r.lastSeenIp FROM RefreshToken r WHERE r.userId = :userId AND r.lastSeenIp IS NOT NULL")
    List<String> findDistinctLastSeenIpsByUserId(@Param("userId") UUID userId);
}
