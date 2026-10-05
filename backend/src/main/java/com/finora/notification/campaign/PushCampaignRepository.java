package com.finora.notification.campaign;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushCampaignRepository extends JpaRepository<PushCampaign, UUID> {

    List<PushCampaign> findTop200ByOrderByCreatedAtDesc();

    /** Ids of ACTIVE campaigns whose slot has come, oldest first. */
    @Query("""
           SELECT c.id FROM PushCampaign c
            WHERE c.status = com.finora.notification.campaign.CampaignStatus.ACTIVE
              AND c.nextRunAt <= :now
            ORDER BY c.nextRunAt
           """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Compare-and-set on the slot that came due: moves {@code next_run_at} (and, for a one-off,
     * the status) only if the campaign is still ACTIVE and still holds exactly the slot this caller
     * read. Returns 1 for the one caller that wins; any other server or minute that read the same
     * slot gets 0 and must do nothing. That is the whole multi-server guard -- no lock.
     *
     * <p>{@code version} is deliberately NOT bumped: it counts admin saves, and each run records
     * it as {@code campaign_version} so support can tie a run to the configuration that produced
     * it. A claim changes no configuration. The admin writes that could race a claim (pause, stop,
     * send now) all set or recompute {@code next_run_at} themselves, so none can leave a stale slot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE PushCampaign c
              SET c.nextRunAt = :next, c.status = :status, c.updatedAt = :now
            WHERE c.id = :id
              AND c.status = com.finora.notification.campaign.CampaignStatus.ACTIVE
              AND c.nextRunAt = :old
           """)
    int claimSlot(@Param("id") UUID id, @Param("old") Instant old, @Param("next") Instant next,
            @Param("status") CampaignStatus status, @Param("now") Instant now);
}
