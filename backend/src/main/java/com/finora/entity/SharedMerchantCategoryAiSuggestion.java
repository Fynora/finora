package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** The latest AI-guessed category per (counterparty_key, direction) -- upserted, never evidence.
 *  See spec §4/§8. */
@Entity
@Table(name = "shared_merchant_category_ai_suggestion")
public class SharedMerchantCategoryAiSuggestion {

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

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
