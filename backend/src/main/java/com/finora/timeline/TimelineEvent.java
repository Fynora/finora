package com.finora.timeline;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Append-only, immutable once written -- deliberately not extending BaseEntity (no
 *  soft-delete, no optimistic version, same shape as GoalContribution/NetWorthSnapshot).
 *  See the design spec's Layer 1 section for why every field is persisted at event time
 *  rather than derived on read from AuditLog. */
@Entity
@Table(name = "timeline_events")
public class TimelineEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String bucket;

    @Column(nullable = false)
    private String importance;

    @Column(nullable = false)
    private boolean permanent;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(nullable = false)
    private String title;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getImportance() { return importance; }
    public void setImportance(String importance) { this.importance = importance; }
    public boolean isPermanent() { return permanent; }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID referenceId) { this.referenceId = referenceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
