package com.finora.integrations.revenuecat;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a customer's store subscriptions from RevenueCat's REST API (GET /v1/subscribers/{app_user_id},
 * secret key). Used only by LapsedSubscriptionCheckSweepService, to ask whether a store subscription
 * past its paid-until date really ended when its EXPIRATION webhook never arrived.
 */
public interface RevenueCatSubscriberClient {

    /** Whether a secret API key is set. Unset, nothing is asked and store rows are left alone. */
    boolean isConfigured();

    /**
     * The subscription with this original transaction id as RevenueCat knows it now, or empty when
     * the customer has no such subscription. Throws when RevenueCat cannot be asked (network, 4xx,
     * 5xx): the caller then changes nothing.
     */
    Optional<StoreSubscription> subscription(String appUserId, String originalTransactionId, String productId);

    /** The three dates that decide whether paid access is over. Any may be null. */
    record StoreSubscription(Instant expiresAt, Instant gracePeriodExpiresAt, Instant refundedAt) {

        /** Refunded, or past both its expiry and any billing grace period. A subscription with no
         *  expiry date at all is never called ended: there is nothing to confirm it by. */
        public boolean endedBy(Instant now) {
            if (refundedAt != null) return true;
            if (expiresAt == null || expiresAt.isAfter(now)) return false;
            return gracePeriodExpiresAt == null || !gracePeriodExpiresAt.isAfter(now);
        }
    }

    /**
     * Picks this subscription out of a GET /v1/subscribers response: the entry whose
     * {@code store_transaction_id} is the original transaction id, or one of its renewals (Google Play
     * numbers them {@code <original>..1}, {@code ..2}); failing that, the entry for {@code productId}
     * (RevenueCat keys entries by product). Empty when neither is there.
     */
    static Optional<StoreSubscription> pick(JsonNode body, String originalTransactionId, String productId) {
        JsonNode subscriptions = body == null ? null : body.path("subscriber").path("subscriptions");
        if (subscriptions == null || !subscriptions.isObject()) return Optional.empty();
        if (originalTransactionId != null && !originalTransactionId.isBlank()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = subscriptions.fields(); it.hasNext(); ) {
                JsonNode entry = it.next().getValue();
                String storeId = entry.path("store_transaction_id").asText("");
                if (storeId.equals(originalTransactionId) || storeId.startsWith(originalTransactionId + "..")) {
                    return Optional.of(of(entry));
                }
            }
        }
        if (productId != null && subscriptions.has(productId)) return Optional.of(of(subscriptions.get(productId)));
        return Optional.empty();
    }

    private static StoreSubscription of(JsonNode entry) {
        return new StoreSubscription(instant(entry, "expires_date"), instant(entry, "grace_period_expires_date"),
                instant(entry, "refunded_at"));
    }

    private static Instant instant(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : Instant.parse(value.asText());
    }
}
