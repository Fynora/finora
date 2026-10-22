package com.finora.repository;

import com.finora.entity.AiInsightText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** AI insight texts (V282; spec §5.4). Every write that decides who calls the model is one SQL
 *  statement, so two requests can never both believe they won. */
public interface AiInsightTextRepository extends JpaRepository<AiInsightText, UUID> {

    /**
     * The single-flight insert: a PENDING row for this key, or nothing when the key is taken. Returns
     * 1 to the one request that should call the model and 0 to every other. Its own transaction, like
     * {@link InsightAcknowledgementRepository#insertIfAbsent}, so the row is visible to a parallel
     * request before the slow model call starts.
     */
    @Transactional
    @Modifying
    @Query(value = """
           INSERT INTO ai_insight_texts (id, user_id, kind, subject, month, facts_hash, prompt_version, model,
                                         policy_version, status, plan_code, created_at, updated_at)
           VALUES (:id, :userId, :kind, :subject, :month, :factsHash, :promptVersion, :model,
                   :policyVersion, 'PENDING', :planCode, now(), now())
           ON CONFLICT (user_id, kind, subject, month, facts_hash, prompt_version, model, policy_version) DO NOTHING
           """, nativeQuery = true)
    int insertPending(@Param("id") UUID id, @Param("userId") UUID userId, @Param("kind") String kind,
                      @Param("subject") String subject, @Param("month") String month,
                      @Param("factsHash") String factsHash, @Param("promptVersion") String promptVersion,
                      @Param("model") String model, @Param("policyVersion") String policyVersion,
                      @Param("planCode") String planCode);

    @Query("""
           SELECT t FROM AiInsightText t
           WHERE t.userId = :userId AND t.kind = :kind AND t.subject = :subject AND t.month = :month
             AND t.factsHash = :factsHash AND t.promptVersion = :promptVersion AND t.model = :model
             AND t.policyVersion = :policyVersion
           """)
    Optional<AiInsightText> findByKey(@Param("userId") UUID userId, @Param("kind") String kind,
                                      @Param("subject") String subject, @Param("month") String month,
                                      @Param("factsHash") String factsHash,
                                      @Param("promptVersion") String promptVersion, @Param("model") String model,
                                      @Param("policyVersion") String policyVersion);

    /**
     * Takes over a row a crashed or timed-out request left PENDING (older than {@code pendingBefore}),
     * or a FAILED row whose cooldown has passed (older than {@code failedBefore}). 1 means this request
     * now owns the row and may call the model; 0 means someone else does, or the cooldown holds.
     */
    @Transactional
    @Modifying
    @Query(value = """
           UPDATE ai_insight_texts SET status = 'PENDING', reason_code = NULL, updated_at = now()
           WHERE id = :id
             AND ((status = 'PENDING' AND updated_at < :pendingBefore)
               OR (status = 'FAILED' AND updated_at < :failedBefore))
           """, nativeQuery = true)
    int takeOverStale(@Param("id") UUID id, @Param("pendingBefore") Instant pendingBefore,
                      @Param("failedBefore") Instant failedBefore);

    /** The newest READY text for this user, kind, subject and month, whatever its versions or facts. */
    @Query(value = """
           SELECT * FROM ai_insight_texts
           WHERE user_id = :userId AND kind = :kind AND subject = :subject AND month = :month AND status = 'READY'
           ORDER BY created_at DESC LIMIT 1
           """, nativeQuery = true)
    Optional<AiInsightText> findLatestReady(@Param("userId") UUID userId, @Param("kind") String kind,
                                            @Param("subject") String subject, @Param("month") String month);

    /** Texts of this kind created for this month since {@code since}, whatever their status: the
     *  story's once-a-day rewrite limit (§6). */
    @Query("""
           SELECT count(t) FROM AiInsightText t
           WHERE t.userId = :userId AND t.kind = :kind AND t.month = :month AND t.createdAt >= :since
           """)
    long countCreatedSince(@Param("userId") UUID userId, @Param("kind") String kind,
                           @Param("month") String month, @Param("since") Instant since);

    Optional<AiInsightText> findByIdAndUserId(UUID id, UUID userId);

    /** DataExportService: every AI text written for the user, oldest first. */
    java.util.List<AiInsightText> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** AccountPurgeSweepService -- the ON DELETE CASCADE never fires there, since the purge
     *  anonymizes the {@code users} row rather than deleting it. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AiInsightText t WHERE t.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
