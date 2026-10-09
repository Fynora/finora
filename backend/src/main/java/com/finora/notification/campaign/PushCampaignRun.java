package com.finora.notification.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One execution of a campaign, kept as history. The title, message and audience are frozen on the
 * row ({@code *_snapshot}) so what an admin sees later is what was really sent, even after the
 * campaign was edited. Counters are written as the run goes, so a run in progress shows live
 * numbers.
 */
@Entity
@Table(name = "push_campaign_runs")
public class PushCampaignRun {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "campaign_version", nullable = false, updatable = false)
    private long campaignVersion;

    @Column(name = "run_date_ist", nullable = false, updatable = false)
    private LocalDate runDateIst;

    @Column(name = "scheduled_for", updatable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, updatable = false, length = 16)
    private RunTrigger triggeredBy;

    @Column(name = "triggered_by_user", updatable = false)
    private UUID triggeredByUser;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Heartbeat, moved by every progress update; see V261. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "audience_size", nullable = false)
    private int audienceSize;

    @Column(name = "queued_count", nullable = false)
    private int queuedCount;

    @Column(name = "skipped_cap_count", nullable = false)
    private int skippedCapCount;

    @Column(name = "skipped_already_queued_count", nullable = false)
    private int skippedAlreadyQueuedCount;

    @Column(name = "title_snapshot", nullable = false, updatable = false, length = 80)
    private String titleSnapshot;

    @Column(name = "message_snapshot", nullable = false, updatable = false, length = 240)
    private String messageSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_snapshot", nullable = false, updatable = false, length = 32)
    private AudienceType audienceSnapshot;

    @Column(length = 300)
    private String note;

    protected PushCampaignRun() {
        // for JPA
    }

    private PushCampaignRun(PushCampaign campaign, LocalDate runDateIst, Instant scheduledFor,
            RunTrigger triggeredBy, UUID triggeredByUser, RunStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.campaignId = campaign.getId();
        this.campaignVersion = campaign.getVersion();
        this.runDateIst = runDateIst;
        this.scheduledFor = scheduledFor;
        this.triggeredBy = triggeredBy;
        this.triggeredByUser = triggeredByUser;
        this.startedAt = now;
        this.updatedAt = now;
        this.status = status;
        this.titleSnapshot = campaign.getTitle();
        this.messageSnapshot = campaign.getMessage();
        this.audienceSnapshot = campaign.getAudienceType();
    }

    /** A run that is about to start sending. */
    public static PushCampaignRun starting(PushCampaign campaign, LocalDate runDateIst,
            Instant scheduledFor, RunTrigger triggeredBy, UUID triggeredByUser, Instant now) {
        return new PushCampaignRun(campaign, runDateIst, scheduledFor, triggeredBy, triggeredByUser,
                RunStatus.RUNNING, now);
    }

    /** A slot that came due but was not sent -- recorded so the history shows it. */
    public static PushCampaignRun missed(PushCampaign campaign, LocalDate runDateIst,
            Instant scheduledFor, String reason, Instant now) {
        PushCampaignRun run = new PushCampaignRun(campaign, runDateIst, scheduledFor,
                RunTrigger.SCHEDULE, null, RunStatus.MISSED, now);
        run.finishedAt = now;
        run.note = truncate(reason);
        return run;
    }

    // No mutators for progress, finish or failure on purpose: a run in progress is updated only through
    // PushCampaignRunRepository's conditional "WHERE status = 'RUNNING'" updates, so a stale in-memory
    // copy can never overwrite a cancel.

    private static String truncate(String value) {
        if (value == null || value.length() <= 300) {
            return value;
        }
        return value.substring(0, 300);
    }

    public UUID getId() { return id; }
    public UUID getCampaignId() { return campaignId; }
    public long getCampaignVersion() { return campaignVersion; }
    public LocalDate getRunDateIst() { return runDateIst; }
    public Instant getScheduledFor() { return scheduledFor; }
    public RunTrigger getTriggeredBy() { return triggeredBy; }
    public UUID getTriggeredByUser() { return triggeredByUser; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public RunStatus getStatus() { return status; }
    public int getAudienceSize() { return audienceSize; }
    public int getQueuedCount() { return queuedCount; }
    public int getSkippedCapCount() { return skippedCapCount; }
    public int getSkippedAlreadyQueuedCount() { return skippedAlreadyQueuedCount; }
    public String getTitleSnapshot() { return titleSnapshot; }
    public String getMessageSnapshot() { return messageSnapshot; }
    public AudienceType getAudienceSnapshot() { return audienceSnapshot; }
    public String getNote() { return note; }
}
