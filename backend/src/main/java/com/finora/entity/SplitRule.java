package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** "Shares from this sender against this bill, every time" (V281). The bill is named by its
 *  category plus its payee key, or its merchant name when that key names no one -- never by amount.
 *  Applied when figures are read (PeopleLedger); removing it sets removedAt and undoes every share
 *  it made. */
@Entity
@Table(name = "split_rules")
public class SplitRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "sender_key", nullable = false)
    private String senderKey;

    @Column(name = "bill_category_id", nullable = false)
    private UUID billCategoryId;

    @Column(name = "bill_payee_key")
    private String billPayeeKey;

    @Column(name = "bill_merchant")
    private String billMerchant;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "removed_at")
    private Instant removedAt;

    protected SplitRule() {}

    public SplitRule(UUID userId, String senderKey, UUID billCategoryId, String billPayeeKey, String billMerchant) {
        this.userId = userId;
        this.senderKey = senderKey;
        this.billCategoryId = billCategoryId;
        this.billPayeeKey = billPayeeKey;
        this.billMerchant = billMerchant;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSenderKey() { return senderKey; }
    public UUID getBillCategoryId() { return billCategoryId; }
    public String getBillPayeeKey() { return billPayeeKey; }
    public String getBillMerchant() { return billMerchant; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
    public boolean isActive() { return removedAt == null; }
}
