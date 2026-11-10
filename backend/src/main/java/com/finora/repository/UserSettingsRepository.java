package com.finora.repository;

import com.finora.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettings, UUID> {
    Optional<UserSettings> findByUserId(UUID userId);

    /**
     * WorkspaceSettingsService.update: one statement, so a user's first two saves landing at once
     * cannot both miss the row and then collide on {@code user_settings_user_id_key} -- before this,
     * 8 simultaneous first saves gave one 200 and seven 409s. The later write wins.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO user_settings (id, user_id, auto_apply_confidence_threshold, created_at, updated_at) "
            + "VALUES (gen_random_uuid(), :userId, :threshold, now(), now()) "
            + "ON CONFLICT (user_id) DO UPDATE SET auto_apply_confidence_threshold = EXCLUDED.auto_apply_confidence_threshold, "
            + "updated_at = now()", nativeQuery = true)
    int upsertThreshold(@Param("userId") UUID userId, @Param("threshold") int threshold);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
