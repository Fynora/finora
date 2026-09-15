package com.finora.entity;

import com.finora.util.CounterpartyType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One human correction against an eligible (vpa:-keyed, BUSINESS/FINANCIAL_INSTITUTION) merchant
 * -- private, append-only, never read by the categorization waterfall. See
 * docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md §4.
 */
@Entity
@Table(name = "counterparty_category_observation")
public class CounterpartyCategoryObservation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_type_at_vote", nullable = false, length = 24)
    private CounterpartyType counterpartyTypeAtVote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public CounterpartyType getCounterpartyTypeAtVote() { return counterpartyTypeAtVote; }
    public void setCounterpartyTypeAtVote(CounterpartyType counterpartyTypeAtVote) { this.counterpartyTypeAtVote = counterpartyTypeAtVote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
