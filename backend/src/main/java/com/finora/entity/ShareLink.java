package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A credit the user confirmed is someone's share of a bill (V279). Read through PeopleLedger,
 *  which caps the amount at what is left of the bill and of the credit. */
@Entity
@Table(name = "share_links")
public class ShareLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "credit_txn_id", nullable = false)
    private UUID creditTxnId;

    @Column(name = "bill_txn_id", nullable = false)
    private UUID billTxnId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ShareLink() {}

    public ShareLink(UUID userId, UUID creditTxnId, UUID billTxnId, BigDecimal amount) {
        this.userId = userId;
        this.creditTxnId = creditTxnId;
        this.billTxnId = billTxnId;
        this.amount = amount;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCreditTxnId() { return creditTxnId; }
    public UUID getBillTxnId() { return billTxnId; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}
