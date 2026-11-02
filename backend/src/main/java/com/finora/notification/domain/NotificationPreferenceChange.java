package com.finora.notification.domain;

import com.finora.entity.ClientPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One change a user made to a notification switch (V306). Append-only: a new change is a new row,
 * never an update, so the history shows when "Offers and news" was turned on, from which app and
 * against which wording -- the record DPDP Act s.6(10) asks Fynora to be able to produce.
 */
@Entity
@Table(name = "notification_preference_history")
public class NotificationPreferenceChange {

    /** The user flipped the switch. */
    public static final String METHOD_SWITCH = "SWITCH";
    /** The setting as it already stood when V306 began the record (migration rows only). */
    public static final String METHOD_BACKFILL = "BACKFILL";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 32)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false, updatable = false)
    private boolean enabled;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** A {@link ClientPlatform} name, or UNKNOWN for a backfilled row. Advisory (X-Client-Platform). */
    @Column(name = "client", nullable = false, updatable = false, length = 16)
    private String client;

    /** Which wording of the switch the app showed, when it says; null otherwise. */
    @Column(name = "notice_version", updatable = false, length = 32)
    private String noticeVersion;

    @Column(name = "method", nullable = false, updatable = false, length = 16)
    private String method;

    protected NotificationPreferenceChange() {}

    public NotificationPreferenceChange(UUID userId, NotificationCategory category, NotificationChannel channel,
            boolean enabled, Instant recordedAt, ClientPlatform client, String noticeVersion) {
        this.userId = userId;
        this.category = category;
        this.channel = channel;
        this.enabled = enabled;
        this.recordedAt = recordedAt;
        this.client = client.name();
        this.noticeVersion = noticeVersion;
        this.method = METHOD_SWITCH;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public NotificationCategory getCategory() { return category; }
    public NotificationChannel getChannel() { return channel; }
    public boolean isEnabled() { return enabled; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getClient() { return client; }
    public String getNoticeVersion() { return noticeVersion; }
    public String getMethod() { return method; }
}
