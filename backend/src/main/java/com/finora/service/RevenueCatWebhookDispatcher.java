package com.finora.service;

import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.ReferralCharge;
import com.finora.entity.Subscription;
import com.finora.integrations.revenuecat.RevenueCatProperties;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscription billing V4 (design spec §5). One method per RevenueCat event type this application
 * acts on, mirroring RazorpayWebhookDispatcher's own shape and self-invocation caveat
 * (@Transactional lives on dispatch(), not the individual handlers, for the identical reason --
 * see that class's own doc comment).
 */
@Component
public class RevenueCatWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;
    private final ReferralService referralService;
    private final RevenueCatProperties properties;

    public RevenueCatWebhookDispatcher(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                        IapProductRepository iapProductRepository, ReferralService referralService,
                                        RevenueCatProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
        this.referralService = referralService;
        this.properties = properties;
    }

    @Transactional
    public void dispatch(String eventType, Map<String, Object> eventPayload) {
        switch (eventType) {
            case "INITIAL_PURCHASE" -> handleInitialPurchase(eventPayload);
            case "RENEWAL" -> handleRenewal(eventPayload);
            case "CANCELLATION" -> handleCancellation(eventPayload);
            case "UNCANCELLATION" -> handleUncancellation(eventPayload);
            case "EXPIRATION" -> handleExpiration(eventPayload);
            case "BILLING_ISSUE" -> handleBillingIssue(eventPayload);
            case "PRODUCT_CHANGE" -> handleProductChange(eventPayload);
            // The store took a refund back. Deliberately not acted on (Sid's decision, 2026-09-28):
            // it is rare, and a referral taken back by the refund stays REGISTERED and counts
            // again on the friend's next paid charge anyway.
            case "REFUND_REVERSED" -> log.info("RevenueCat REFUND_REVERSED for transaction {} -- logged only, "
                    + "the referral it may have taken back is not restored.",
                    LogSanitizer.sanitize(String.valueOf(eventPayload.get("transaction_id"))));
            default -> log.info("RevenueCat webhook event '{}' received but not handled in V4 yet.",
                    LogSanitizer.sanitize(eventType));
        }
    }

    /** Used only by handleInitialPurchase. Looks up the user's single subscriptions row (design
     *  spec §4.1: exactly one row per user for life) regardless of its current status -- NOT via
     *  findActiveOrTrial, whose ACTIVE/TRIAL filter would miss a user who is currently PAST_DUE
     *  (bug-hunt finding: a user stuck PAST_DUE who cancels through the store's own UI and
     *  resubscribes fresh right there sends a genuine new INITIAL_PURCHASE with a new
     *  original_transaction_id for the same app_user_id; findActiveOrTrial would silently drop it). */
    private Optional<Subscription> subscriptionForAppUserId(Map<String, Object> eventPayload) {
        String appUserId = (String) eventPayload.get("app_user_id");
        if (appUserId == null) return Optional.empty();
        try {
            UUID userId = UUID.fromString(appUserId);
            return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst();
        } catch (IllegalArgumentException e) {
            log.warn("RevenueCat webhook app_user_id '{}' is not a valid Fynora user id, ignoring.",
                    LogSanitizer.sanitize(appUserId));
            return Optional.empty();
        }
    }

    /** Used by every handler EXCEPT handleInitialPurchase. Looks the subscription up by its stable
     *  external id (original_transaction_id) rather than by app_user_id + findActiveOrTrial's
     *  ACTIVE/TRIAL filter -- exactly why RazorpayWebhookDispatcher's own
     *  handleCharged/handlePending/handleHalted/handleCancelled all key off
     *  findByRazorpaySubscriptionId instead of the user's current status. Without this, a
     *  subscription already moved to PAST_DUE (handleBillingIssue) would be invisible to every
     *  later event for it: a successful-retry RENEWAL could never reactivate it, and a
     *  retries-exhausted EXPIRATION could never downgrade it either, leaving it stuck in PAST_DUE
     *  indefinitely (bug-hunt finding, no design-doc precedent for it). handleInitialPurchase alone
     *  still needs the app_user_id path -- there is no original_transaction_id to match against
     *  before the row's first purchase happens.
     *
     *  <p>Throws if {@code original_transaction_id} IS present but matches no subscription -- same
     *  reasoning as {@code RazorpayWebhookDispatcher.handleCharged}'s own doc: neither provider
     *  guarantees webhook delivery order, so any of these events can race ahead of the
     *  INITIAL_PURCHASE that first sets this id on the row. Silently dropping that (the old
     *  behavior) let the controller mark the webhook_events row PROCESSED, so
     *  WebhookEventRecoverySweepService never retried it -- throwing marks it FAILED instead so the
     *  sweep does. Only returns {@code Optional.empty()} for the genuinely different case: the
     *  payload itself carries no {@code original_transaction_id} at all -- a malformed/unusable
     *  payload, not a lookup miss, and not something a retry would ever fix. */
    private Optional<Subscription> subscriptionForOriginalTransactionId(Map<String, Object> eventPayload, String eventType) {
        String originalTransactionId = (String) eventPayload.get("original_transaction_id");
        if (originalTransactionId == null) return Optional.empty();
        Optional<Subscription> subscription = subscriptionRepository.findByRevenuecatOriginalTransactionId(originalTransactionId);
        if (subscription.isEmpty()) {
            throw new IllegalStateException("RevenueCat " + eventType + " for unknown original_transaction_id "
                    + LogSanitizer.sanitize(originalTransactionId));
        }
        return subscription;
    }

    /** spec §6.1 step 4 / §5. app_user_id is always the real Fynora user id (spec §2's "purchase
     *  requires authentication" decision) -- never RevenueCat's own anonymous id -- so this is a
     *  direct lookup, no mapping table. */
    void handleInitialPurchase(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;

        // Ownership-source rule (spec §2.1, invariants 1/2) -- symmetric to §6.4's web-side
        // checkout guard, which blocks the opposite direction. The mobile Paywall is only ever
        // shown when mySubscription() has no active billing subscription (design spec §6.3), so
        // this should not normally fire -- but the store already captured real payment by the
        // time this webhook arrives, so the backend cannot refuse the purchase, only refuse to
        // silently overwrite and orphan a still-live Razorpay mandate. REVENUECAT and ADMIN_GRANT
        // (a complimentary plan, not a real external mandate) are both fine to take over.
        String existingProvider = subscription.getPaymentProvider();
        if (existingProvider != null && !"REVENUECAT".equals(existingProvider) && !"ADMIN_GRANT".equals(existingProvider)) {
            log.error("RevenueCat INITIAL_PURCHASE for user {} conflicts with an existing {}-owned " +
                            "subscription -- not overwriting. Needs manual reconciliation (the store " +
                            "has already charged this user).", subscription.getUserId(), existingProvider);
            return;
        }

        String productId = (String) eventPayload.get("product_id");
        String store = (String) eventPayload.get("store");
        String platform = "PLAY_STORE".equals(store) ? "ANDROID" : "IOS";
        IapProduct product = iapProductRepository.findByProviderProductIdAndPlatformAndActiveTrue(productId, platform).orElse(null);
        if (product == null) {
            log.warn("RevenueCat product_id '{}' ({}) has no iap_products mapping, ignoring.",
                    LogSanitizer.sanitize(productId), platform);
            return;
        }
        Plan plan = planRepository.findById(product.getPlanId()).orElseThrow();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(product.getBillingCycle());
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setStorePlatform(platform);
        subscription.setRevenuecatOriginalTransactionId((String) eventPayload.get("original_transaction_id"));
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);

        // design spec §5 (referral reward ledger): a paid INITIAL_PURCHASE means the store has
        // already charged the user, same as Razorpay's subscription.charged. A free-trial start
        // also arrives as INITIAL_PURCHASE, which is why this goes through countIfPaid.
        countIfPaid(subscription.getUserId(), plan.getCode(), eventPayload);
    }

    /**
     * Counts the referral (if any) of a user whose charge this event reports -- but only a paid
     * one. RevenueCat's webhook docs: {@code period_type} is TRIAL for a free trial, and
     * {@code price} is "0 for free trials, or negative for refunds". A null price ("unknown") is
     * not held against the event; the period_type check still applies. When a trial converts,
     * RevenueCat sends a RENEWAL for the first paid period, which lands here through
     * handleRenewal and counts then.
     *
     * <p>A SANDBOX purchase (the {@code environment} field) costs nothing and does not count,
     * unless {@code app.integrations.revenuecat.count-sandbox-referrals} is set for a test
     * environment -- see RevenueCatProperties.
     *
     * <p>The charge is identified by {@code transaction_id}: that is the id a refund's
     * CANCELLATION carries (see handleCancellation).
     */
    private void countIfPaid(UUID userId, String planCode, Map<String, Object> eventPayload) {
        if ("TRIAL".equals(eventPayload.get("period_type"))) return;
        if ("SANDBOX".equals(eventPayload.get("environment")) && !properties.isCountSandboxReferrals()) return;
        if (eventPayload.get("price") instanceof Number price && price.doubleValue() <= 0) return;
        referralService.onReferredUserCharged(userId, planCode, ReferralCharge.PROVIDER_REVENUECAT,
                (String) eventPayload.get("transaction_id"));
    }

    /** spec §5. Renewal is passive -- just refresh the expiration date. Looked up by
     *  original_transaction_id (not app_user_id), so a subscription currently PAST_DUE from a
     *  prior BILLING_ISSUE is still reachable -- see subscriptionForOriginalTransactionId. */
    void handleRenewal(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForOriginalTransactionId(eventPayload, "RENEWAL").orElse(null);
        if (subscription == null) return;
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);

        // A referred user whose first paid period is a renewal -- a trial converting, or a
        // referral taken back by a refund and then paid for again. For everyone else
        // onReferredUserCharged's REGISTERED-only guard makes this a no-op.
        planRepository.findById(subscription.getPlanId())
                .ifPresent(plan -> countIfPaid(subscription.getUserId(), plan.getCode(), eventPayload));
    }

    /** spec §5/§3. Turns off auto-renew only -- status/plan/renewal_date untouched, exactly
     *  Razorpay's own cancel() (BillingCheckoutService.cancel()). Access continues until
     *  expiration; EXPIRATION below is the actual downgrade point.
     *
     *  <p>Real bug found in bug-hunt review, verified against RevenueCat's own docs (event-flows.md,
     *  event-types-and-fields.md): a billing issue ALWAYS fires BILLING_ISSUE and a companion
     *  CANCELLATION event with {@code cancel_reason=BILLING_ERROR} together, "dispatched in order at
     *  the same time" -- this is not a distinct user action, it's the same underlying event
     *  handleBillingIssue already reflects (STATUS_PAST_DUE). Flipping auto_renew=false here too
     *  would mislabel an in-progress billing retry as "won't renew" -- and since handleRenewal never
     *  restores auto_renew, a subscription that later recovers (a real RENEWAL event, per RevenueCat's
     *  own subscription-lifecycle docs) would stay stuck showing that label forever even though it's
     *  healthy and actively renewing. */
    void handleCancellation(Map<String, Object> eventPayload) {
        if ("BILLING_ERROR".equals(eventPayload.get("cancel_reason"))) return;
        // A refund, by either documented marker: cancel_reason CUSTOMER_SUPPORT ("Customer received
        // a refund"), or a negative price ("negative for refunds") -- the latter also catches a
        // refund Apple reports with cancel_reason UNKNOWN ("Apple did not provide the reason").
        boolean refund = "CUSTOMER_SUPPORT".equals(eventPayload.get("cancel_reason"))
                || (eventPayload.get("price") instanceof Number price && price.doubleValue() < 0);
        if (refund) {
            handleRefund(eventPayload);
            return;
        }
        subscriptionForOriginalTransactionId(eventPayload, "CANCELLATION").ifPresent(subscription -> {
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);
        });
    }

    /**
     * A refund CANCELLATION (see handleCancellation for how one is recognized). Takes back the referral this exact transaction counted, if any
     * (ReferralService.onChargeReversed matches on {@code transaction_id}, so a refund of a later
     * renewal leaves it alone).
     *
     * <p>Unlike every other CANCELLATION, a subscription that cannot be found is not an error
     * here: Apple and Google refund after a subscription has expired, and handleExpiration has by
     * then cleared the original_transaction_id this looks up. Throwing (what
     * subscriptionForOriginalTransactionId does) would roll back the referral reversal with it and
     * fail the webhook on every retry, forever. The auto-renew update that other cancellations get
     * is kept for a subscription that is still there.
     */
    private void handleRefund(Map<String, Object> eventPayload) {
        referralService.onChargeReversed(ReferralCharge.PROVIDER_REVENUECAT,
                (String) eventPayload.get("transaction_id"), "REFUND");
        String originalTransactionId = (String) eventPayload.get("original_transaction_id");
        if (originalTransactionId == null) return;
        subscriptionRepository.findByRevenuecatOriginalTransactionId(originalTransactionId).ifPresent(subscription -> {
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);
        });
    }

    void handleUncancellation(Map<String, Object> eventPayload) {
        subscriptionForOriginalTransactionId(eventPayload, "UNCANCELLATION").ifPresent(subscription -> {
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5. Mirrors RazorpayWebhookDispatcher.handleHalted EXACTLY (checked against the real
     *  code, not assumed): resets the plan to FREE, clears every provider-specific field, and sets
     *  status=ACTIVE on FREE directly -- no intermediate status. Looked up by
     *  original_transaction_id: a subscription already PAST_DUE (retries exhausted) must still be
     *  reachable here, or it would stay PAST_DUE forever instead of ever downgrading. */
    void handleExpiration(Map<String, Object> eventPayload) {
        subscriptionForOriginalTransactionId(eventPayload, "EXPIRATION").ifPresent(subscription -> {
            Plan free = planRepository.findByCode("FREE")
                    .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
            subscription.setPlanId(free.getId());
            subscription.setBillingCycle(null);
            subscription.setPaymentProvider(null);
            subscription.setStorePlatform(null);
            subscription.setRevenuecatOriginalTransactionId(null);
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5. Mirrors RazorpayWebhookDispatcher.handlePending: the store is retrying a failed
     *  renewal charge, access is untouched. Deliberately NOT STATUS_PAYMENT_FAILED -- that status
     *  has no live writer anywhere in the existing Razorpay flow this design otherwise mirrors. */
    void handleBillingIssue(Map<String, Object> eventPayload) {
        subscriptionForOriginalTransactionId(eventPayload, "BILLING_ISSUE").ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_PAST_DUE);
            subscriptionRepository.save(subscription);
        });
    }

    /** spec §3/§5/§9. The user changed plan tier and/or billing cycle through the store's own
     *  native UI -- something Razorpay has no equivalent for. Reconciles BOTH plan and cycle via
     *  iap_products, mirroring RazorpayWebhookDispatcher.handleCharged's plan-id reconciliation.
     *
     *  <p>Real bug found in bug-hunt review, verified against RevenueCat's own docs
     *  (event-types-and-fields.md, cross-checked against four independent sample payloads), not
     *  assumed: on PRODUCT_CHANGE, {@code product_id} is the OLD product the subscriber switched
     *  FROM -- the plan they're leaving -- and {@code new_product_id} is the one they switched TO.
     *  Reading {@code product_id} here would silently reconcile to the plan being abandoned instead
     *  of the one actually purchased. Falls back to {@code product_id} only because RevenueCat's own
     *  docs say {@code new_product_id} is itself omitted for an immediate (non-deferred) Google Play
     *  change -- better to reconcile against something than silently no-op in that one case. */
    void handleProductChange(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForOriginalTransactionId(eventPayload, "PRODUCT_CHANGE").orElse(null);
        if (subscription == null) return;

        String newProductId = (String) eventPayload.get("new_product_id");
        String productId = newProductId != null ? newProductId : (String) eventPayload.get("product_id");
        String store = (String) eventPayload.get("store");
        String platform = "PLAY_STORE".equals(store) ? "ANDROID" : "IOS";
        iapProductRepository.findByProviderProductIdAndPlatformAndActiveTrue(productId, platform).ifPresent(product -> {
            subscription.setPlanId(product.getPlanId());
            subscription.setBillingCycle(product.getBillingCycle());
            subscriptionRepository.save(subscription);
        });
    }

    private void applyExpiration(Subscription subscription, Map<String, Object> eventPayload) {
        Object expirationAtMs = eventPayload.get("expiration_at_ms");
        if (expirationAtMs instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneOffset.UTC));
        }
    }
}
