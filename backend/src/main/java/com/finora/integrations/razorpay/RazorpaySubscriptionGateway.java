package com.finora.integrations.razorpay;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §6). The only Razorpay-facing seam every billing service
 * depends on — checkout (Task 6), cancellation (Task 11), and upgrade/downgrade (Plan 2) all
 * program against this interface, never the SDK's {@code RazorpayClient} directly, so they can be
 * unit-tested with a plain mock.
 */
public interface RazorpaySubscriptionGateway {

    boolean isConfigured();

    /** Creates a new Razorpay Subscription against an already-provisioned Razorpay Plan (see
     *  {@code billing_prices.razorpayPlanId}). {@code notes} is stored on the Razorpay side for
     *  support/debugging correlation, not read back by this application. */
    RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes);

    /** Same as {@link #createSubscription}, for a checkout under a billing offer (V297): the
     *  customer pays {@code firstPeriod.amountPaise} once, at authorisation, as an upfront amount
     *  (Razorpay's {@code addons}), and the first regular plan charge happens at
     *  {@code firstPeriod.regularBillingStartsAt} (Razorpay's {@code start_at}). The authorisation
     *  link stops working at {@code firstPeriod.payBy} (Razorpay's {@code expire_by}), so a checkout
     *  opened during an offer can't be paid at the offer price after it ends. */
    RazorpaySubscriptionDto createSubscriptionWithFirstPeriod(String razorpayPlanId, String billingCycle,
                                                              Map<String, String> notes, FirstPeriod firstPeriod);

    /** The payment the customer made when authorising this subscription, read from Razorpay's
     *  invoices for it -- for an offer checkout, the upfront offer payment. Empty when Razorpay
     *  has no paid invoice for it yet. */
    java.util.Optional<AuthorisationPayment> findAuthorisationPayment(String razorpaySubscriptionId);

    /** @param amountPaise what was paid, in paise */
    record AuthorisationPayment(String paymentId, long amountPaise) {}

    /** @param amountPaise what the customer pays at authorisation, in paise
     *  @param label the upfront amount's name, shown by Razorpay at checkout and on its invoice */
    record FirstPeriod(long amountPaise, String label, java.time.Instant regularBillingStartsAt,
                       java.time.Instant payBy) {}

    RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId);

    /** A subscription's status and the end of its current period. LapsedSubscriptionCheckSweepService
     *  reads both: whether Razorpay ended it, and when a live one is paid until. A default so the many
     *  test fakes keep compiling; it knows no period end. */
    default SubscriptionState fetchSubscriptionState(String razorpaySubscriptionId) {
        return new SubscriptionState(fetchSubscription(razorpaySubscriptionId).status(), null);
    }

    /** @param currentEnd the end of the current billing period, or null when Razorpay gave none */
    record SubscriptionState(String status, java.time.Instant currentEnd) {}

    /** {@code cancelAtCycleEnd=true} for every user-initiated cancellation (spec §6.3); {@code false}
     *  is an immediate stop, used by two Plan 2 callers: the admin support action (spec §6.6), and
     *  {@code RazorpayWebhookDispatcher.handleActivated} stopping the OLD subscription once an
     *  upgrade's new one is confirmed active (spec §6.5 step 4). */
    void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd);

    /** {@code scheduleAtCycleEnd=true} defers the change to the next billing cycle -- the only mode
     *  used today, by Plan 2's downgrade (spec §6.4; {@code BillingCheckoutService.scheduleDowngrade}).
     *  Plan 2's upgrade does NOT call this method at all: it creates a second, independent Razorpay
     *  subscription instead (spec §6.5) and cancels the old one only after the new one activates, so
     *  {@code scheduleAtCycleEnd=false} has no caller yet -- kept for a same-subscription immediate
     *  plan change, should one ever be needed. */
    void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd);

    /** Product decision (2026-09-08): pause, distinct from cancel -- billing stops immediately
     *  (unlike cancel's cycle-end grace) but the mandate itself isn't torn down, so resume needs no
     *  new checkout. Razorpay's pause API accepts only {@code pause_at=now} (no cycle-end option) and
     *  only accepts a subscription currently in Razorpay's own {@code active} state; the returned
     *  status is authoritative synchronously -- unlike {@code createSubscription}'s "created" status,
     *  there is no separate confirming webhook needed before the caller can trust this. */
    RazorpaySubscriptionDto pauseSubscription(String razorpaySubscriptionId);

    /** {@code resume_at=now} is likewise the only mode; only valid for a subscription currently in
     *  Razorpay's own {@code paused} state. Same synchronous-confirmation note as
     *  {@link #pauseSubscription} above. */
    RazorpaySubscriptionDto resumeSubscription(String razorpaySubscriptionId);
}
