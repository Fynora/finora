package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * D-28 PR4-C. A user's own shareable referral code (proposal §4), generated lazily on first
 * request -- see {@code ReferralService.myCode}. Not extending {@link BaseEntity}: a plain,
 * never-edited mapping row, same reasoning as {@link Plan}/{@link FeatureEntitlement}.
 */
@Entity
@Table(name = "referral_codes")
public class ReferralCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "plus_milestone_counter", nullable = false)
    private int plusMilestoneCounter = 0;

    @Column(name = "premium_milestone_counter", nullable = false)
    private int premiumMilestoneCounter = 0;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Instant getCreatedAt() { return createdAt; }
    public int getPlusMilestoneCounter() { return plusMilestoneCounter; }
    public void setPlusMilestoneCounter(int plusMilestoneCounter) { this.plusMilestoneCounter = plusMilestoneCounter; }
    public int getPremiumMilestoneCounter() { return premiumMilestoneCounter; }
    public void setPremiumMilestoneCounter(int premiumMilestoneCounter) { this.premiumMilestoneCounter = premiumMilestoneCounter; }
}
