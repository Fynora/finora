package com.finora.integrations.razorpay;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscription billing V1. Thin, mostly-untested-at-the-unit-level adapter over the Razorpay SDK —
 * the interface it implements ({@link RazorpaySubscriptionGateway}) is what everything else in this
 * codebase depends on and mocks; this class is exercised for real only against a live Razorpay test
 * account, matching how {@code GoogleOAuthClient} and {@code GmailApiClient} are thin real-network
 * wrappers verified at the IT/stub level rather than heavily unit tested internally.
 *
 * <p>{@code totalCount}: Razorpay's create-subscription API requires a finite cycle count even for
 * what is conceptually an indefinitely-recurring plan. 120 monthly cycles (10 years) / 20 yearly
 * cycles (20 years) are engineering defaults with no product-visible effect — {@code
 * subscription.completed} is explicitly not expected to fire in V1 (design spec §5), and Razorpay
 * subscriptions auto-renew on their own schedule regardless of this number until cancelled.
 */
@Component
public class RazorpaySubscriptionGatewayImpl implements RazorpaySubscriptionGateway {

    private static final int MONTHLY_TOTAL_COUNT = 120;
    private static final int YEARLY_TOTAL_COUNT = 20;

    private final RazorpayProperties properties;

    public RazorpaySubscriptionGatewayImpl(RazorpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    private RazorpayClient client() {
        if (!isConfigured()) {
            throw new IllegalStateException("Razorpay is not configured (RAZORPAY_KEY_ID/KEY_SECRET/WEBHOOK_SECRET unset).");
        }
        try {
            return new RazorpayClient(properties.getKeyId(), properties.getKeySecret());
        } catch (RazorpayException e) {
            throw new IllegalStateException("Failed to initialize Razorpay client.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes) {
        return create(baseRequest(razorpayPlanId, billingCycle, notes));
    }

    @Override
    public RazorpaySubscriptionDto createSubscriptionWithFirstPeriod(String razorpayPlanId, String billingCycle,
                                                                     Map<String, String> notes, FirstPeriod firstPeriod) {
        return create(firstPeriodRequest(razorpayPlanId, billingCycle, notes, firstPeriod));
    }

    /** Package-private so the request shape is unit-tested without a network call. */
    static JSONObject baseRequest(String razorpayPlanId, String billingCycle, Map<String, String> notes) {
        JSONObject request = new JSONObject();
        request.put("plan_id", razorpayPlanId);
        request.put("total_count", "YEARLY".equals(billingCycle) ? YEARLY_TOTAL_COUNT : MONTHLY_TOTAL_COUNT);
        request.put("quantity", 1);
        request.put("customer_notify", 1);
        JSONObject notesJson = new JSONObject();
        notes.forEach(notesJson::put);
        request.put("notes", notesJson);
        return request;
    }

    /** Razorpay's create-subscription API: {@code addons[].item} is an upfront amount collected in
     *  the authorisation payment (amount in paise, currency must match the plan's), {@code start_at}
     *  moves the first plan charge to that time, {@code expire_by} is the last moment the customer
     *  can authorise. All three are Unix seconds / paise as Razorpay documents them. */
    static JSONObject firstPeriodRequest(String razorpayPlanId, String billingCycle, Map<String, String> notes,
                                         FirstPeriod firstPeriod) {
        JSONObject request = baseRequest(razorpayPlanId, billingCycle, notes);
        JSONObject item = new JSONObject();
        item.put("name", firstPeriod.label());
        item.put("amount", firstPeriod.amountPaise());
        item.put("currency", "INR");
        JSONObject addon = new JSONObject();
        addon.put("item", item);
        request.put("addons", new org.json.JSONArray().put(addon));
        request.put("start_at", firstPeriod.regularBillingStartsAt().getEpochSecond());
        request.put("expire_by", firstPeriod.payBy().getEpochSecond());
        return request;
    }

    private RazorpaySubscriptionDto create(JSONObject request) {
        try {
            com.razorpay.Subscription subscription = client().subscriptions.create(request);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay createSubscription failed.", e);
        }
    }

    /** Razorpay's "fetch all invoices of a subscription" ({@code GET /v1/invoices?subscription_id=}).
     *  The authorisation payment is the earliest paid invoice; for an offer checkout it is the only
     *  one until the first regular charge a period later. */
    @Override
    public java.util.Optional<AuthorisationPayment> findAuthorisationPayment(String razorpaySubscriptionId) {
        try {
            JSONObject query = new JSONObject();
            query.put("subscription_id", razorpaySubscriptionId);
            java.util.List<com.razorpay.Invoice> invoices = client().invoices.fetchAll(query);
            return earliestPaid(invoices.stream().map(com.razorpay.Invoice::toJson).toList());
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay fetch invoices failed.", e);
        }
    }

    /** Package-private so the invoice reading is unit-tested without a network call. Invoice
     *  fields per Razorpay's invoice entity: {@code status}, {@code payment_id},
     *  {@code amount_paid} (paise), {@code paid_at} (Unix seconds). */
    static java.util.Optional<AuthorisationPayment> earliestPaid(java.util.List<JSONObject> invoices) {
        return invoices.stream()
                .filter(i -> "paid".equals(i.optString("status")) && !i.optString("payment_id").isEmpty()
                        && i.optLong("amount_paid", 0) > 0)
                .min(java.util.Comparator.comparingLong(i -> i.optLong("paid_at", Long.MAX_VALUE)))
                .map(i -> new AuthorisationPayment(i.getString("payment_id"), i.getLong("amount_paid")));
    }

    @Override
    public RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId) {
        try {
            com.razorpay.Subscription subscription = client().subscriptions.fetch(razorpaySubscriptionId);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay fetchSubscription failed.", e);
        }
    }

    @Override
    public void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("cancel_at_cycle_end", cancelAtCycleEnd);
            client().subscriptions.cancel(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay cancelSubscription failed.", e);
        }
    }

    @Override
    public void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", newRazorpayPlanId);
            request.put("schedule_change_at", scheduleAtCycleEnd ? "cycle_end" : "now");
            client().subscriptions.update(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay updateSubscription failed.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto pauseSubscription(String razorpaySubscriptionId) {
        try {
            JSONObject request = new JSONObject();
            request.put("pause_at", "now");
            com.razorpay.Subscription subscription = client().subscriptions.pause(razorpaySubscriptionId, request);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay pauseSubscription failed.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto resumeSubscription(String razorpaySubscriptionId) {
        try {
            JSONObject request = new JSONObject();
            request.put("resume_at", "now");
            com.razorpay.Subscription subscription = client().subscriptions.resume(razorpaySubscriptionId, request);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay resumeSubscription failed.", e);
        }
    }
}
