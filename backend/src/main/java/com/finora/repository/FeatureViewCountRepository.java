package com.finora.repository;

import com.finora.entity.FeatureViewCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FeatureViewCountRepository extends JpaRepository<FeatureViewCount, UUID> {

    Optional<FeatureViewCount> findByUserIdAndFeature(UUID userId, String feature);

    /** Atomic insert-or-increment -- avoids a read-then-write race between concurrent views for
     *  the same (user, feature), which a findOrCreate-then-save round trip would not. */
    @Modifying
    @Query(value = "INSERT INTO feature_view_counts (user_id, feature, view_count, last_viewed_at) "
            + "VALUES (:userId, :feature, 1, now()) "
            + "ON CONFLICT (user_id, feature) DO UPDATE SET "
            + "view_count = feature_view_counts.view_count + 1, last_viewed_at = now()",
            nativeQuery = true)
    void recordView(@Param("userId") UUID userId, @Param("feature") String feature);

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V171), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap already documented on {@code NotificationRepository} for
     *  V125. Needs its own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM FeatureViewCount f WHERE f.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
