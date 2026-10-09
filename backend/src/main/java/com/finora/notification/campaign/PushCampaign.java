package com.finora.notification.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * An admin push campaign: the words, the audience and the schedule. What each send actually did is
 * a {@link PushCampaignRun}.
 *
 * <p>State changes go through the methods below so an illegal transition (editing a running
 * campaign, resuming a stopped one) is refused in one place. {@code nextRunAt} is the single field
 * the scheduler reads; it is non-null only while the campaign is ACTIVE.
 */
@Entity
@Table(name = "push_campaigns")
public class PushCampaign {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 240)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 32)
    private AudienceType audienceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_kind", nullable = false, length = 16)
    private ScheduleKind scheduleKind;

    @Column(name = "run_at")
    private Instant runAt;

    @Column(name = "send_time_ist")
    private LocalTime sendTimeIst;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_tested_by")
    private UUID lastTestedBy;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PushCampaign() {
        // for JPA
    }

    public PushCampaign(String name, String title, String message, AudienceType audienceType,
            ScheduleKind scheduleKind, Instant runAt, LocalTime sendTimeIst, LocalDate endsOn,
            UUID createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.title = title;
        this.message = message;
        this.audienceType = audienceType;
        this.scheduleKind = scheduleKind;
        this.runAt = runAt;
        this.sendTimeIst = sendTimeIst;
        this.endsOn = endsOn;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Whether the words, audience and schedule may be edited: only before it runs, or while paused. */
    public boolean isEditable() {
        return status == CampaignStatus.DRAFT || status == CampaignStatus.PAUSED;
    }

    public void edit(String name, String title, String message, AudienceType audienceType,
            ScheduleKind scheduleKind, Instant runAt, LocalTime sendTimeIst, LocalDate endsOn,
            Instant now) {
        this.name = name;
        this.title = title;
        this.message = message;
        this.audienceType = audienceType;
        this.scheduleKind = scheduleKind;
        this.runAt = runAt;
        this.sendTimeIst = sendTimeIst;
        this.endsOn = endsOn;
        this.updatedAt = now;
    }

    /** DRAFT or PAUSED to ACTIVE, with the first slot already worked out. */
    public void activate(Instant firstRunAt, Instant now) {
        this.status = CampaignStatus.ACTIVE;
        this.nextRunAt = firstRunAt;
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        this.status = CampaignStatus.PAUSED;
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    public void stop(Instant now) {
        this.status = CampaignStatus.STOPPED;
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        this.status = CampaignStatus.COMPLETED;
        this.nextRunAt = null;
        this.updatedAt = now;
    }

    /** Moves the next scheduled slot (send now on a daily campaign consumes today's slot). */
    public void rescheduleNextRun(Instant nextRunAt, Instant now) {
        this.nextRunAt = nextRunAt;
        this.updatedAt = now;
    }

    public void recordTest(UUID adminId, Instant now) {
        this.lastTestedAt = now;
        this.lastTestedBy = adminId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public AudienceType getAudienceType() { return audienceType; }
    public ScheduleKind getScheduleKind() { return scheduleKind; }
    public Instant getRunAt() { return runAt; }
    public LocalTime getSendTimeIst() { return sendTimeIst; }
    public LocalDate getEndsOn() { return endsOn; }
    public CampaignStatus getStatus() { return status; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public UUID getLastTestedBy() { return lastTestedBy; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
