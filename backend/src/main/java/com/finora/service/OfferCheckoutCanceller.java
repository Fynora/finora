package com.finora.service;

import com.finora.entity.BillingOffer;
import com.finora.entity.SubscriptionOrder;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When an admin ends a billing offer early, cancels the checkouts customers still have open under
 * it. Checkout created each one as a Razorpay subscription carrying the offer price, payable until
 * the offer's original end ({@code expire_by}), so ending the offer alone would leave those payable.
 *
 * <p>Runs after the end-now transaction has committed and outside any transaction of its own, so no
 * database transaction stays open across the Razorpay calls (the same rule
 * {@link BillingCheckoutService} follows). Each checkout is handled on its own:
 * <ol>
 *   <li>Fetch the subscription. Only one still in Razorpay's {@code created} state is unpaid. An
 *       {@code authenticated} or {@code active} one is already paid, waiting for (or past) its
 *       webhook, and is left alone: the customer paid the price they were shown.</li>
 *   <li>Cancel it immediately ({@code cancel_at_cycle_end=false}). Razorpay refuses a cycle-end
 *       cancel before billing starts and points to the immediate one instead.</li>
 *   <li>Check Razorpay has no paid invoice for it, in case the customer paid between the fetch and
 *       the cancel. Only then is the order marked ABANDONED. The activation webhook ignores an
 *       ABANDONED order, so marking a paid one would leave a customer who paid without Plus; a
 *       payment found here is logged for manual review and the order stays PENDING.</li>
 * </ol>
 * Any failure leaves that checkout PENDING, logged, and counted as not cancelled; ending the offer
 * has already succeeded either way.
 */
@Service
public class OfferCheckoutCanceller {

    private static final Logger log = LoggerFactory.getLogger(OfferCheckoutCanceller.class);
    static final String STATUS_CREATED = "created";

    private final BillingOfferRepository offerRepository;
    private final SubscriptionOrderRepository orderRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    public OfferCheckoutCanceller(BillingOfferRepository offerRepository, SubscriptionOrderRepository orderRepository,
                                  RazorpaySubscriptionGateway gateway, TransactionTemplate transactionTemplate,
                                  AuditService auditService) {
        this.offerRepository = offerRepository;
        this.orderRepository = orderRepository;
        this.gateway = gateway;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    /** How many open checkouts were cancelled, and how many could not be. */
    public record Result(int cancelled, int notCancelled) {
    }

    /** For the offer just ended. Call only after the end-now transaction has committed. */
    public Result cancelOpenCheckouts(UUID adminUserId, UUID offerId) {
        BillingOffer offer = offerRepository.findById(offerId).orElse(null);
        if (offer == null) {
            return new Result(0, 0);
        }
        String offerCode = offer.getCode();
        String billingCycle = offer.getBillingCycle();
        List<SubscriptionOrder> open = orderRepository.findByStatusAndOfferCodeAndPlanIdAndBillingCycle(
                SubscriptionOrder.STATUS_PENDING, offerCode, offer.getPlanId(), billingCycle);
        if (open.isEmpty()) {
            return new Result(0, 0);
        }
        int cancelled = 0;
        int notCancelled = 0;
        int notOpen = 0;
        if (!gateway.isConfigured()) {
            log.warn("Offer {} ended with {} open checkout(s), but Razorpay is not configured here, so none were "
                    + "cancelled. They stay payable at the offer price until the offer's original end.",
                    LogSanitizer.sanitize(offerCode), open.size());
            notCancelled = open.size();
        } else {
            for (SubscriptionOrder order : open) {
                switch (cancelOne(order, offerCode)) {
                    case CANCELLED -> cancelled++;
                    case NOT_OPEN -> notOpen++;
                    case NOT_CANCELLED -> notCancelled++;
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", offerCode);
        metadata.put("billingCycle", billingCycle);
        metadata.put("openCheckouts", open.size());
        metadata.put("cancelled", cancelled);
        metadata.put("notOpen", notOpen);
        metadata.put("notCancelled", notCancelled);
        auditService.record(adminUserId, "BILLING_OFFER_CHECKOUTS_CANCELLED", "BillingOffer", offerId, metadata);
        return new Result(cancelled, notCancelled);
    }

    private enum Outcome { CANCELLED, NOT_OPEN, NOT_CANCELLED }

    private Outcome cancelOne(SubscriptionOrder order, String offerCode) {
        String subscriptionId = order.getRazorpaySubscriptionId();
        String safeId = LogSanitizer.sanitize(subscriptionId);
        RazorpaySubscriptionDto subscription;
        try {
            subscription = gateway.fetchSubscription(subscriptionId);
        } catch (RuntimeException e) {
            log.warn("Offer {} ended: could not fetch open checkout {} from Razorpay, left open.",
                    LogSanitizer.sanitize(offerCode), safeId, e);
            return Outcome.NOT_CANCELLED;
        }
        if (!STATUS_CREATED.equals(subscription.status())) {
            // Paid (authenticated/active), or already cancelled or expired: not an open checkout to stop.
            log.info("Offer {} ended: checkout {} is {} on Razorpay, left alone.",
                    LogSanitizer.sanitize(offerCode), safeId, LogSanitizer.sanitize(subscription.status()));
            return Outcome.NOT_OPEN;
        }
        try {
            gateway.cancelSubscription(subscriptionId, false);
        } catch (RuntimeException e) {
            log.warn("Offer {} ended: Razorpay refused to cancel open checkout {}; it stays payable at the offer "
                    + "price until the offer's original end.", LogSanitizer.sanitize(offerCode), safeId, e);
            return Outcome.NOT_CANCELLED;
        }
        boolean paidMeanwhile;
        try {
            paidMeanwhile = gateway.findAuthorisationPayment(subscriptionId).isPresent();
        } catch (RuntimeException e) {
            // Cancelled, but whether a payment slipped in first is unknown: leave PENDING, so a
            // payment that did land is still completed by the webhook.
            log.error("Offer {} ended: cancelled checkout {} on Razorpay but could not check it for a payment; "
                    + "left PENDING. Check the Razorpay dashboard.", LogSanitizer.sanitize(offerCode), safeId, e);
            return Outcome.NOT_CANCELLED;
        }
        if (paidMeanwhile) {
            log.error("Offer {} ended: checkout {} was paid while it was being cancelled. Left PENDING so the "
                    + "webhook still grants the plan; review it on the Razorpay dashboard (the subscription is "
                    + "cancelled, so it will not renew).", LogSanitizer.sanitize(offerCode), safeId);
            return Outcome.NOT_CANCELLED;
        }
        transactionTemplate.executeWithoutResult(status ->
                orderRepository.findByRazorpaySubscriptionId(subscriptionId)
                        .filter(o -> SubscriptionOrder.STATUS_PENDING.equals(o.getStatus()))
                        .ifPresent(o -> {
                            o.setStatus(SubscriptionOrder.STATUS_ABANDONED);
                            orderRepository.save(o);
                        }));
        return Outcome.CANCELLED;
    }
}
