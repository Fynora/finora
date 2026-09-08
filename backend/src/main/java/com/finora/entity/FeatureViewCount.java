package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One row per (user, feature) -- see V171__feature_view_counts.sql. Not extending
 *  {@link BaseEntity}: writes go through {@code FeatureViewCountRepository}'s native upsert, never
 *  through JPA's save/merge path, so this entity only ever backs reads. */
@Entity
@Table(name = "feature_view_counts")
public class FeatureViewCount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String feature;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "last_viewed_at", nullable = false)
    private Instant lastViewedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getFeature() { return feature; }
    public int getViewCount() { return viewCount; }
    public Instant getLastViewedAt() { return lastViewedAt; }
}
