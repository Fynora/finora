package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The charge that moved a referral to SUBSCRIBED (V241) -- kept so a refund or lost chargeback of
 * that exact charge can take the referral back, even after the referred account was purged. See
 * the migration's own comment for why this is a table of its own.
 */
@Entity
@Table(name = "referral_charges")
public class ReferralCharge {

    public static final String PROVIDER_RAZORPAY = "RAZORPAY";
    public static final String PROVIDER_REVENUECAT = "REVENUECAT";

    @Id
    @GeneratedValue
    private UUID id;

    // NULL only on a row a refund wrote before its charge arrived -- see V241.
    @Column(name = "referrer_user_id")
    private UUID referrerUserId;

    // Nullable: set NULL by the database when the referred user's referral row is purged.
    @Column(name = "referral_id")
    private UUID referralId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "charge_ref", nullable = false)
    private String chargeRef;

    @Column(nullable = false)
    private boolean counted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason", length = 40)
    private String reversalReason;

    public UUID getId() { return id; }
    public UUID getReferrerUserId() { return referrerUserId; }
    public void setReferrerUserId(UUID referrerUserId) { this.referrerUserId = referrerUserId; }
    public UUID getReferralId() { return referralId; }
    public void setReferralId(UUID referralId) { this.referralId = referralId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getChargeRef() { return chargeRef; }
    public void setChargeRef(String chargeRef) { this.chargeRef = chargeRef; }
    public boolean isCounted() { return counted; }
    public void setCounted(boolean counted) { this.counted = counted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReversedAt() { return reversedAt; }
    public void setReversedAt(Instant reversedAt) { this.reversedAt = reversedAt; }
    public String getReversalReason() { return reversalReason; }
    public void setReversalReason(String reversalReason) { this.reversalReason = reversalReason; }
}
