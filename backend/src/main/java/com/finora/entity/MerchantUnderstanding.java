package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Tier 1 of the AI-category-creation design (spec §3/§4) -- global, free-text understanding
 *  of a merchant, shared across every user. Never contains a category name. */
@Entity
@Table(name = "merchant_understanding")
public class MerchantUnderstanding {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(nullable = false, columnDefinition = "text")
    private String understanding;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getUnderstanding() { return understanding; }
    public void setUnderstanding(String understanding) { this.understanding = understanding; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
