package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** "Every payment from this sender is <kind>". Keyed on Transaction.counterpartyKey, which is never
 *  shown to the user -- a name: key is a guess (CounterpartyIdentity). */
@Entity
@Table(name = "sender_inflow_rules")
public class SenderInflowRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Column(name = "inflow_kind_id", nullable = false)
    private UUID inflowKindId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public UUID getInflowKindId() { return inflowKindId; }
    public void setInflowKindId(UUID inflowKindId) { this.inflowKindId = inflowKindId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
