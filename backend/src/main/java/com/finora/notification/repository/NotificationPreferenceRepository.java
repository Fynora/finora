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

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V128), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap already documented on {@code NotificationRepository} for
     *  V125. Needs its own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM NotificationPreference n WHERE n.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
