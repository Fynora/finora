package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The actual corpus -- one row per (counterparty_key, direction), created only by promotion
 * (SharedCorpusService), never written to directly. See spec §4/§5.
 */
@Entity
@Table(name = "shared_merchant_category")
public class SharedMerchantCategory {

    public enum Status { TRUSTED, DISPUTED, REVALIDATING }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 80)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_distribution", nullable = false, columnDefinition = "jsonb")
    private Map<String, BigDecimal> categoryDistribution;

    @Column(name = "distinct_user_count", nullable = false)
    private int distinctUserCount;

    @Column(name = "promoted_at", nullable = false)
    private Instant promotedAt = Instant.now();

    @Column(name = "last_recomputed_at", nullable = false)
    private Instant lastRecomputedAt = Instant.now();

    @Column(name = "revalidating_since")
    private Instant revalidatingSince;

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Map<String, BigDecimal> getCategoryDistribution() { return categoryDistribution; }
    public void setCategoryDistribution(Map<String, BigDecimal> categoryDistribution) { this.categoryDistribution = categoryDistribution; }
    public int getDistinctUserCount() { return distinctUserCount; }
    public void setDistinctUserCount(int distinctUserCount) { this.distinctUserCount = distinctUserCount; }
    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant promotedAt) { this.promotedAt = promotedAt; }
    public Instant getLastRecomputedAt() { return lastRecomputedAt; }
    public void setLastRecomputedAt(Instant lastRecomputedAt) { this.lastRecomputedAt = lastRecomputedAt; }
    public Instant getRevalidatingSince() { return revalidatingSince; }
    public void setRevalidatingSince(Instant revalidatingSince) { this.revalidatingSince = revalidatingSince; }
}
