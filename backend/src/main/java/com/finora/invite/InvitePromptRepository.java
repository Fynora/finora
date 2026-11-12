package com.finora.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvitePromptRepository extends JpaRepository<InvitePrompt, UUID> {

    /**
     * Records a showing on {@code today}. A second report on the same day changes nothing (React's
     * double effects in development, two tabs, a retry), so one showing can never use up the second
     * chance. Returns 1 when a showing was recorded, 0 when it was a same-day repeat.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO invite_prompts (user_id, times_shown, last_shown_on) VALUES (:userId, 1, :today) "
            + "ON CONFLICT (user_id) DO UPDATE SET times_shown = LEAST(invite_prompts.times_shown + 1, 2), "
            + "last_shown_on = :today "
            + "WHERE invite_prompts.last_shown_on IS DISTINCT FROM :today",
            nativeQuery = true)
    int recordShown(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /**
     * Records a showing on {@code today} only when none was ever recorded. Share and Not now are
     * tapped on a card the person saw, but the app reports SHOWN separately, fire and forget; when
     * that report is lost (a dropped connection), this keeps the record true, so a Not now still
     * keeps the card away and the two-showing cap still holds. Returns 1 when it recorded the
     * showing, 0 when one was already recorded (a SHOWN arriving later the same day is then a
     * same-day repeat, so nothing is counted twice).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO invite_prompts (user_id, times_shown, last_shown_on) VALUES (:userId, 1, :today) "
            + "ON CONFLICT (user_id) DO UPDATE SET times_shown = 1, last_shown_on = :today "
            + "WHERE invite_prompts.times_shown = 0",
            nativeQuery = true)
    int recordShownIfNever(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /** The first day Share was tapped; a later tap keeps it. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO invite_prompts (user_id, shared_on) VALUES (:userId, :today) "
            + "ON CONFLICT (user_id) DO UPDATE SET shared_on = COALESCE(invite_prompts.shared_on, :today)",
            nativeQuery = true)
    void recordShareTapped(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /** The latest day "Not now" was tapped. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO invite_prompts (user_id, dismissed_on) VALUES (:userId, :today) "
            + "ON CONFLICT (user_id) DO UPDATE SET dismissed_on = :today",
            nativeQuery = true)
    void recordDismissed(@Param("userId") UUID userId, @Param("today") LocalDate today);

    /**
     * This user's statement imports that saved at least one transaction, deleted ones included (a
     * deleted statement still means an import succeeded once). Native, so the entity's
     * deleted_at restriction does not apply.
     */
    @Query(value = "SELECT id FROM statement_imports WHERE user_id = :userId AND transactions_imported > 0",
            nativeQuery = true)
    List<UUID> successfulImportIds(@Param("userId") UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM InvitePrompt p WHERE p.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
