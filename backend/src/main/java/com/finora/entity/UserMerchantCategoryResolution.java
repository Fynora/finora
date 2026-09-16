package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Tier 2 of the AI-category-creation design (spec §3/§4) -- per-user resolution of a
 *  merchant to a category. Stores category_id, never a name (spec §6: rename-safe by
 *  construction). One row per (user, counterparty_key, direction), ever -- upserted by AI
 *  resolution, re-upserted (never merged) by a human correction, per spec §8. */
@Entity
@Table(name = "user_merchant_category_resolution")
public class UserMerchantCategoryResolution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
