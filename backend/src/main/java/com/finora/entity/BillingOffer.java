package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-boxed price for the first billing period of a paid plan (V297). Checkout charges
 * {@link #firstPeriodPrice} up front and moves the first regular charge one period out -- see
 * {@code BillingCheckoutService.checkout}. Live while {@code startsAt <= now < endsAt}.
 */
@Entity
@Table(name = "billing_offers")
public class BillingOffer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(name = "first_period_price", nullable = false)
    private BigDecimal firstPeriodPrice;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isLiveAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public BigDecimal getFirstPeriodPrice() { return firstPeriodPrice; }
    public void setFirstPeriodPrice(BigDecimal firstPeriodPrice) { this.firstPeriodPrice = firstPeriodPrice; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public Instant getCreatedAt() { return createdAt; }
}
