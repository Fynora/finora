package com.finora.repository;

import com.finora.entity.AiInsightUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The Free plan's monthly AI insights allowance (V282; spec §6). A reservation is one conditional
 * UPDATE after the row exists: Postgres re-checks {@code why_used < :limit} on the row it locks, so
 * parallel taps queue on that row and never pass the limit. A use is given back with release* when
 * the attempt ends without an AI text shown.
 */
public interface AiInsightUsageRepository extends JpaRepository<AiInsightUsage, UUID> {

    @Modifying
    @Query(value = """
           INSERT INTO ai_insight_usage (id, user_id, month, why_used, story_generations)
           VALUES (gen_random_uuid(), :userId, :month, 0, 0)
           ON CONFLICT (user_id, month) DO NOTHING
           """, nativeQuery = true)
    int ensureRow(@Param("userId") UUID userId, @Param("month") String month);

    @Modifying
    @Query(value = """
           UPDATE ai_insight_usage SET why_used = why_used + 1
           WHERE user_id = :userId AND month = :month AND why_used < :limit
           """, nativeQuery = true)
    int incrementWhy(@Param("userId") UUID userId, @Param("month") String month, @Param("limit") int limit);

    @Modifying
    @Query(value = """
           UPDATE ai_insight_usage SET story_generations = story_generations + 1
           WHERE user_id = :userId AND month = :month AND story_generations < :limit
           """, nativeQuery = true)
    int incrementStory(@Param("userId") UUID userId, @Param("month") String month, @Param("limit") int limit);

    /** 1 when a "Why?" use was reserved for this month, 0 when the allowance is spent. */
    @Transactional
    default int reserveWhy(UUID userId, String month, int limit) {
        ensureRow(userId, month);
        return incrementWhy(userId, month, limit);
    }

    /** 1 when a story generation was reserved for this month, 0 when the allowance is spent. */
    @Transactional
    default int reserveStory(UUID userId, String month, int limit) {
        ensureRow(userId, month);
        return incrementStory(userId, month, limit);
    }

    @Transactional
    @Modifying
    @Query(value = """
           UPDATE ai_insight_usage SET why_used = greatest(why_used - 1, 0)
           WHERE user_id = :userId AND month = :month
           """, nativeQuery = true)
    int releaseWhy(@Param("userId") UUID userId, @Param("month") String month);

    @Transactional
    @Modifying
    @Query(value = """
           UPDATE ai_insight_usage SET story_generations = greatest(story_generations - 1, 0)
           WHERE user_id = :userId AND month = :month
           """, nativeQuery = true)
    int releaseStory(@Param("userId") UUID userId, @Param("month") String month);

    @Query(value = "SELECT coalesce((SELECT why_used FROM ai_insight_usage WHERE user_id = :userId AND month = :month), 0)",
            nativeQuery = true)
    int whyUsed(@Param("userId") UUID userId, @Param("month") String month);

    /** AccountPurgeSweepService -- the cascade never fires there (the users row is anonymized). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AiInsightUsage u WHERE u.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
