package com.finora.repository;

import com.finora.entity.RecurringDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface RecurringDismissalRepository extends JpaRepository<RecurringDismissal, UUID> {
    Set<RecurringDismissal> findByUserId(UUID userId);

    /**
     * Inserts the dismissal, or does nothing if {@code (user_id, merchant)} is already taken --
     * same shape and same reason as {@link MerchantAliasRepository#insertIfAbsent}: a plain
     * {@code findByUserIdAndMerchant} check followed by a separate {@code save()} has a real race
     * (a double-tap, or a retried request, arriving as two near-simultaneous calls) that a single
     * {@code ON CONFLICT DO NOTHING} statement doesn't.
     */
    @Modifying
    @Query(value = """
           INSERT INTO recurring_dismissals (id, user_id, merchant, dismissed_at)
           VALUES (gen_random_uuid(), :userId, :merchant, now())
           ON CONFLICT (user_id, merchant) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("merchant") String merchant);

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V190), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap already documented on {@code NotificationRepository} for
     *  V125. Needs its own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RecurringDismissal r WHERE r.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
