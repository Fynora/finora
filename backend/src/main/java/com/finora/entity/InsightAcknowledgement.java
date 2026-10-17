package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An Insights row the user marked "Got it" (V277), named by its kind and key -- a row has no
 * persisted identity of its own; see the migration for each kind's key.
 */
@Entity
@Table(name = "insight_acknowledgements")
public class InsightAcknowledgement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(name = "acknowledged_at", nullable = false)
    private Instant acknowledgedAt = Instant.now();

    public InsightAcknowledgement() {}

    public UUID getUserId() { return userId; }
    public String getKind() { return kind; }
    public String getItemKey() { return itemKey; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
}
