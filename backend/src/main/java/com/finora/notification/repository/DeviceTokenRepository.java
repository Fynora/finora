package com.finora.notification.repository;

import com.finora.notification.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /**
     * Deliberately NOT filtered by {@code revokedAt} -- that is what lets
     * {@code DeviceTokenService.register} reactivate a previously-revoked row with an UPDATE
     * instead of colliding with {@code UNIQUE (user_id, token_fingerprint)} on a blind INSERT (see
     * V129's migration comment for the full argument). Adding {@code AndRevokedAtIsNull} here would
     * turn every re-registration of a revoked token into a 409 CONFLICT.
     */
    Optional<DeviceToken> findByUserIdAndTokenFingerprint(UUID userId, String tokenFingerprint);

    List<DeviceToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * Every OTHER user's currently-active row for this exact token -- deliberately the opposite of
     * every other finder in this interface: NOT scoped to a caller's own userId, by design, because
     * its whole job is to find rows belonging to someone else. FCM/APNs tokens are per app-install,
     * not per user, so the same token string can legitimately turn up under a second user id after
     * an account switch on a shared/handed-down device; {@code DeviceTokenService.register} uses
     * this to revoke the previous owner's row before the new owner's is created or touched.
     *
     * <p><b>Do not confuse this with {@link #findByUserIdAndTokenFingerprint}.</b> That finder's
     * user-scoping is what makes {@code revoke(userId, rawToken)}'s authorization guarantee hold --
     * one user can never revoke another's row. This finder exists specifically to reach across that
     * boundary for one purpose (de-duplicating a shared physical token), and must never be used
     * anywhere an authorization check is expected.
     */
    List<DeviceToken> findByTokenFingerprintAndUserIdNotAndRevokedAtIsNull(String tokenFingerprint,
            UUID userId);

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V129), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap already documented on {@code NotificationRepository} for
     *  V125. Needs its own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
