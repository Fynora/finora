package com.finora.imports.fix;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClaudeFixJobRepository extends JpaRepository<ClaudeFixJob, UUID> {

    /** Jobs started (first sent) in {@code [from, to)}: what the monthly cap counts. */
    @Query("SELECT COUNT(j) FROM ClaudeFixJob j WHERE j.sentAt >= :from AND j.sentAt < :to")
    long countStartedBetween(@Param("from") Instant from, @Param("to") Instant to);

    List<ClaudeFixJob> findByStateIn(Collection<ClaudeFixJob.State> states);

    /**
     * Held import jobs with no decision yet, held between {@code from} and {@code to}, oldest first:
     * holds whose after-commit start never ran (the starter's queue was full, or the server
     * restarted first). A trust hold counts only with its own review record; one riding another
     * job's review is decided with that review.
     */
    @Query(value = "SELECT j.id FROM import_jobs j "
            + "WHERE (j.status = 'HELD_FOR_REVIEW' OR (j.status = 'HELD_FOR_TRUST_REVIEW' AND j.held_statement_id IS NOT NULL)) "
            + "AND j.finished_at >= :from AND j.finished_at < :to "
            + "AND NOT EXISTS (SELECT 1 FROM claude_fix_jobs c WHERE c.job_id = j.id) "
            + "ORDER BY j.finished_at LIMIT :limit", nativeQuery = true)
    List<UUID> findHeldWithoutDecision(@Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);

    /** Claims the month's cap email: 1 the first time for {@code month} ('YYYY-MM'), 0 after. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO claude_fix_cap_alerts (month, alerted_at) VALUES (:month, :now) "
            + "ON CONFLICT (month) DO NOTHING", nativeQuery = true)
    int claimCapAlert(@Param("month") String month, @Param("now") Instant now);
}
