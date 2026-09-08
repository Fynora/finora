package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * D-28 PR4-A. A user's current plan and standing -- {@link SubscriptionEvent} is the append-only
 * log of how it got there. Soft-deleted like every other core domain entity in this codebase
 * (see Goal/Budget's own @SQLDelete comments): Hibernate binds a second (version) parameter on
 * delete regardless of custom SQL, so "AND version = ?" must be present here too.
 */
@Entity
@Table(name = "subscriptions")
@SQLDelete(sql = "UPDATE subscriptions SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class Subscription extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_TRIAL = "TRIAL";
    public static final String STATUS_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String STATUS_PAST_DUE = "PAST_DUE";
    /** Product decision (2026-09-08): billing on hold, distinct from {@link #STATUS_CANCELLED} --
     *  access is revoked immediately (excluded from {@code SubscriptionRepository.findActiveOrTrial},
     *  so {@code EntitlementService} naturally denies premium features while paused), but planId/
     *  razorpaySubscriptionId are left untouched so resume needs no new checkout. */
    public static final String STATUS_PAUSED = "PAUSED";

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "renewal_date")
    private LocalDate renewalDate;

    @Column(name = "trial_start")
    private LocalDate trialStart;

    @Column(name = "trial_end")
    private LocalDate trialEnd;

    @Column(name = "payment_provider", length = 30)
    private String paymentProvider;

    @Column(name = "billing_cycle", length = 10)
    private String billingCycle;

    @Column(name = "razorpay_subscription_id", length = 50)
    private String razorpaySubscriptionId;

    @Column(name = "store_platform", length = 10)
    private String storePlatform;

    @Column(name = "revenuecat_original_transaction_id", length = 100)
    private String revenuecatOriginalTransactionId;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew = true;

    @Column(name = "cancellation_dispatched_at")
    private Instant cancellationDispatchedAt;

    /** Populated from {@code payment.entity.card} on a Razorpay {@code subscription.activated}/
     *  {@code subscription.charged} webhook (see {@code RazorpayWebhookDispatcher}) -- last4/network/
     *  type only, exactly what Razorpay's own payload already carries. Never the full card number or
     *  CVV; Fynora never receives those. Null for a non-card mandate (UPI/emandate) or before the
     *  first such webhook lands. */
    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_network", length = 20)
    private String cardNetwork;

    @Column(name = "card_type", length = 20)
    private String cardType;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public LocalDate getRenewalDate() { return renewalDate; }
    public void setRenewalDate(LocalDate renewalDate) { this.renewalDate = renewalDate; }
    public LocalDate getTrialStart() { return trialStart; }
    public void setTrialStart(LocalDate trialStart) { this.trialStart = trialStart; }
    public LocalDate getTrialEnd() { return trialEnd; }
    public void setTrialEnd(LocalDate trialEnd) { this.trialEnd = trialEnd; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; }
    public String getStorePlatform() { return storePlatform; }
    public void setStorePlatform(String storePlatform) { this.storePlatform = storePlatform; }
    public String getRevenuecatOriginalTransactionId() { return revenuecatOriginalTransactionId; }
    public void setRevenuecatOriginalTransactionId(String revenuecatOriginalTransactionId) { this.revenuecatOriginalTransactionId = revenuecatOriginalTransactionId; }
    public boolean isAutoRenew() { return autoRenew; }
    public void setAutoRenew(boolean autoRenew) { this.autoRenew = autoRenew; }
    public Instant getCancellationDispatchedAt() { return cancellationDispatchedAt; }
    public void setCancellationDispatchedAt(Instant cancellationDispatchedAt) { this.cancellationDispatchedAt = cancellationDispatchedAt; }
    public String getCardLast4() { return cardLast4; }
    public void setCardLast4(String cardLast4) { this.cardLast4 = cardLast4; }
    public String getCardNetwork() { return cardNetwork; }
    public void setCardNetwork(String cardNetwork) { this.cardNetwork = cardNetwork; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
}
