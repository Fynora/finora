package com.finora.notification.campaign;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every update a RUNNING run receives while it executes is a conditional {@code UPDATE ... WHERE
 * status = 'RUNNING'} that reports how many rows it changed. That is how a run notices an admin
 * cancelled it: the next progress update changes zero rows. Saving the whole entity instead would
 * overwrite the cancelled status with the stale RUNNING one the runner still holds in memory.
 */
public interface PushCampaignRunRepository extends JpaRepository<PushCampaignRun, UUID> {

    List<PushCampaignRun> findTop50ByCampaignIdOrderByStartedAtDesc(UUID campaignId);

    /** Whether this campaign has a run in progress that started after {@code since} -- a run that
     *  started long ago and never finished is an interrupted one, not a reason to refuse. */
    boolean existsByCampaignIdAndStatusAndUpdatedAtAfter(UUID campaignId, RunStatus status,
            Instant since);

    /** Runs left RUNNING by a crash or deploy: marked FAILED so the history is honest and the
     *  campaign can be sent again. Returns how many. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaignRun r
              SET r.status = com.finora.notification.campaign.RunStatus.FAILED,
                  r.finishedAt = :now,
                  r.note = 'Interrupted: the server stopped before this run finished.'
            WHERE r.status = com.finora.notification.campaign.RunStatus.RUNNING
              AND r.updatedAt < :cutoff
           """)
    int failInterrupted(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /** Live counters for a run in progress. @return 1, or 0 if the run is no longer RUNNING
     *  (cancelled, or marked interrupted) -- the caller must stop queuing people. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaignRun r
              SET r.audienceSize = :audience, r.queuedCount = :queued, r.updatedAt = :now,
                  r.skippedCapCount = :skippedCap, r.skippedAlreadyQueuedCount = :skippedAlreadyQueued
            WHERE r.id = :id AND r.status = com.finora.notification.campaign.RunStatus.RUNNING
           """)
    int updateProgress(@Param("id") UUID id, @Param("audience") int audience,
            @Param("queued") int queued, @Param("skippedCap") int skippedCap,
            @Param("skippedAlreadyQueued") int skippedAlreadyQueued,
            @Param("now") Instant now);

    /** RUNNING to DONE. @return 0 if the run was cancelled meanwhile. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaignRun r
              SET r.status = com.finora.notification.campaign.RunStatus.DONE, r.finishedAt = :now, r.updatedAt = :now
            WHERE r.id = :id AND r.status = com.finora.notification.campaign.RunStatus.RUNNING
           """)
    int markDone(@Param("id") UUID id, @Param("now") Instant now);

    /** RUNNING to FAILED with a reason. @return 0 if the run was cancelled meanwhile. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaignRun r
              SET r.status = com.finora.notification.campaign.RunStatus.FAILED,
                  r.finishedAt = :now, r.updatedAt = :now, r.note = :note
            WHERE r.id = :id AND r.status = com.finora.notification.campaign.RunStatus.RUNNING
           """)
    int markFailed(@Param("id") UUID id, @Param("now") Instant now, @Param("note") String note);

    /** An admin cancelled sending: every RUNNING run of the campaign becomes CANCELLED. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaignRun r
              SET r.status = com.finora.notification.campaign.RunStatus.CANCELLED,
                  r.finishedAt = :now, r.updatedAt = :now, r.note = :note
            WHERE r.campaignId = :campaignId
              AND r.status = com.finora.notification.campaign.RunStatus.RUNNING
           """)
    int cancelRunning(@Param("campaignId") UUID campaignId, @Param("now") Instant now,
            @Param("note") String note);
}
