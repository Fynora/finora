package com.finora.repository;

import com.finora.entity.SignupAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SignupAttributionRepository extends JpaRepository<SignupAttribution, UUID> {

    /** One line of the admin "sign-ups by source" count -- see {@link #countBySource}. */
    interface SourceCount {
        String getSource();
        long getUsers();
    }

    /**
     * Every self-service account (USER scope, not internal, not the bootstrap account) counted by
     * where it came from: the landing link's utm_source when it had one, else the host that linked
     * here, else "referral" for an account that redeemed a friend's code (the referrals table is
     * the one that already records that, so this does not store it twice), else "direct" -- which
     * also covers mobile-app sign-ups, where no browser is involved.
     */
    @Query(value = """
            SELECT COALESCE(a.utm_source, a.referrer_host,
                            CASE WHEN r.id IS NOT NULL THEN 'referral' END, 'direct') AS source,
                   COUNT(*) AS users
            FROM users u
            LEFT JOIN signup_attributions a ON a.user_id = u.id
            LEFT JOIN referrals r ON r.referred_user_id = u.id
            WHERE u.account_scope = 'USER' AND u.internal_account = FALSE AND u.email <> :bootstrapIdentifier
            GROUP BY 1
            ORDER BY users DESC, source ASC
            """, nativeQuery = true)
    List<SourceCount> countBySource(@Param("bootstrapIdentifier") String bootstrapIdentifier);

    /** AccountPurgeSweepService -- the ON DELETE CASCADE never fires there, since the purge
     *  anonymizes the {@code users} row rather than deleting it (same trap as insight_acknowledgements). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SignupAttribution a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
