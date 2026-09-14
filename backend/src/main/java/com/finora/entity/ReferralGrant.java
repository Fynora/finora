package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A referral-earned free month of Plus or Premium (design spec at docs/superpowers/specs/
 * 2026-09-14-referral-milestone-rewards-design.md, section 3) -- an overlay EntitlementService
 * checks alongside the user's real subscription, never a mutation of Subscription's own billing
 * fields (Fynora has no API to touch a RevenueCat-billed user's renewal date, so the grant has to
 * work identically regardless of payment provider). Not extending BaseEntity: a state-tracking row
 * updated in place by ReferralGrantSweepService's one-directional PENDING -> ACTIVE -> EXPIRED
 * transitions, same reasoning as Referral's own class comment.
 */
@Entity
@Table(name = "referral_grants")
public class ReferralGrant {

    public static final String TIER_PLUS = "PLUS";
    public static final String TIER_PREMIUM = "PREMIUM";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * Shared tier comparison used by EntitlementService, ReferralService, and
     * ReferralGrantSweepService -- one place rather than three copies of the same ranking, so a
     * future tier (if one is ever added) only needs to change here. A plan code not in this list
     * (including null and "FREE") ranks lowest.
     */
    public static int tierRank(String tierOrPlanCode) {
        if (TIER_PREMIUM.equals(tierOrPlanCode)) return 2;
        if (TIER_PLUS.equals(tierOrPlanCode)) return 1;
        return 0;
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String tier;

    @Column(nullable = false, length = 10)
    private String status;

    // Nullable -- see this class's own migration comment (V207): a referral that contributed to a
    // counter can later be hard-deleted by AccountPurgeSweepService without invalidating this
    // grant. Purely informational; never a reason to fail a redemption.
    @Column(name = "earned_from_referral_id")
    private UUID earnedFromReferralId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getEarnedFromReferralId() { return earnedFromReferralId; }
    public void setEarnedFromReferralId(UUID earnedFromReferralId) { this.earnedFromReferralId = earnedFromReferralId; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
