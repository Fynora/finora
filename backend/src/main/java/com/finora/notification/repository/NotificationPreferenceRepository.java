package com.finora.notification.repository;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(UUID userId,
            NotificationCategory category, NotificationChannel channel);

    List<NotificationPreference> findByUserId(UUID userId);

    /** NotificationPreferenceService -- one statement, so two concurrent toggles of the same
     *  preference cannot both miss the row and then collide on V128's UNIQUE (user_id, category,
     *  channel); the later write simply wins. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
            + "VALUES (:id, :userId, :category, :channel, :enabled) "
            + "ON CONFLICT (user_id, category, channel) DO UPDATE SET enabled = EXCLUDED.enabled",
            nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("category") String category, @Param("channel") String channel,
            @Param("enabled") boolean enabled);

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V128), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap already documented on {@code NotificationRepository} for
     *  V125. Needs its own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM NotificationPreference n WHERE n.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
