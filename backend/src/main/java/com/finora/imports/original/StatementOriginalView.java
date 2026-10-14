package com.finora.imports.original;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One opening of a held statement's real pages. See V271 for what the row is for. */
@Entity
@Table(name = "statement_original_views")
public class StatementOriginalView {

    /** How long a view's page images can be fetched after it opens. */
    public static final Duration LIFETIME = Duration.ofMinutes(30);

    @Id private UUID id;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "subject_user_id", nullable = false) private UUID subjectUserId;
    @Column(name = "viewer_id", nullable = false) private UUID viewerId;
    @Column(nullable = false) private String queue;
    @Column(name = "item_ref", nullable = false) private String itemRef;
    @Column(nullable = false) private String format;
    @Column(name = "page_count", nullable = false) private int pageCount;
    @Column(nullable = false) private String reason;
    @Column(name = "opened_at", nullable = false) private Instant openedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    protected StatementOriginalView() {}

    public static StatementOriginalView open(UUID jobId, UUID subjectUserId, UUID viewerId, String queue,
                                             String itemRef, String format, int pageCount, String reason,
                                             Instant now) {
        StatementOriginalView v = new StatementOriginalView();
        v.id = UUID.randomUUID();
        v.jobId = jobId;
        v.subjectUserId = subjectUserId;
        v.viewerId = viewerId;
        v.queue = queue;
        v.itemRef = itemRef;
        v.format = format;
        v.pageCount = pageCount;
        v.reason = reason;
        v.openedAt = now;
        v.expiresAt = now.plus(LIFETIME);
        return v;
    }

    /** Only the person who opened it, and only until it expires. */
    public boolean isLiveFor(UUID viewer, Instant now) {
        return viewerId.equals(viewer) && now.isBefore(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getSubjectUserId() { return subjectUserId; }
    public UUID getViewerId() { return viewerId; }
    public String getQueue() { return queue; }
    public String getItemRef() { return itemRef; }
    public String getFormat() { return format; }
    public int getPageCount() { return pageCount; }
    public String getReason() { return reason; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
