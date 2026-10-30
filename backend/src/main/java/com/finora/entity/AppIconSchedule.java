package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One planned festival-icon window (V299): show {@link #getIconKey()} from {@link #getStartsAt()}
 * (inclusive) until {@link #getEndsAt()} (exclusive). Windows never overlap; see the migration.
 *
 * <p>The icon is stored as its key string, not as an {@code @Enumerated} {@link AppIcon}: if a key
 * is ever retired from the code while an old row still names it, the row must still load (the
 * public endpoint then treats it as the default icon) rather than break every read of the table.
 */
@Entity
@Table(name = "app_icon_schedule")
public class AppIconSchedule {

    @Id
    private UUID id;

    @Column(name = "icon_key", nullable = false, length = 32)
    private String iconKey;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A wrapper, not a primitive: null until first persisted is how Spring Data tells a new row
     *  from an existing one when the id is assigned in code. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected AppIconSchedule() {
    }

    public AppIconSchedule(UUID id, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = createdBy;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
}
