package com.finora.integrations.revenuecat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Picking our subscription out of RevenueCat's GET /v1/subscribers body. Synthetic ids and dates;
 *  the shape follows RevenueCat's documented response. */
class RevenueCatSubscriberClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode body(String subscriptions) throws Exception {
        return MAPPER.readTree("{\"subscriber\":{\"subscriptions\":{" + subscriptions + "}}}");
    }

    private static final String PLUS = """
            "plus:monthly":{"expires_date":"2026-11-30T10:00:00Z","grace_period_expires_date":null,
             "refunded_at":null,"store":"play_store","store_transaction_id":"GPA.1111-2222-3333-44444..2"}
            """;

    @Test
    void picksTheSubscriptionByItsOriginalTransactionIdOrARenewalOfIt() throws Exception {
        var picked = RevenueCatSubscriberClient.pick(body(PLUS), "GPA.1111-2222-3333-44444", null);
        assertThat(picked).isPresent();
        assertThat(picked.get().expiresAt()).isEqualTo(Instant.parse("2026-11-30T10:00:00Z"));

        String original = PLUS.replace("..2\"", "\"");
        assertThat(RevenueCatSubscriberClient.pick(body(original), "GPA.1111-2222-3333-44444", null)).isPresent();
    }

    @Test
    void anotherSubscriptionsIdIsNotAMatchButTheProductIdIs() throws Exception {
        // A prefix of another id is not a renewal of it: only "<id>.." counts.
        assertThat(RevenueCatSubscriberClient.pick(body(PLUS), "GPA.1111-2222-3333-4444", null)).isEmpty();
        assertThat(RevenueCatSubscriberClient.pick(body(PLUS), "GPA.9999", "plus:monthly")).isPresent();
        assertThat(RevenueCatSubscriberClient.pick(body(PLUS), "GPA.9999", "premium:monthly")).isEmpty();
        assertThat(RevenueCatSubscriberClient.pick(MAPPER.readTree("{}"), "GPA.9999", "plus:monthly")).isEmpty();
    }

    @Test
    void endedMeansRefundedOrPastBothExpiryAndGrace() {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        Instant past = now.minusSeconds(86_400);
        Instant future = now.plusSeconds(86_400);
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(past, null, null).endedBy(now)).isTrue();
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(past, past, null).endedBy(now)).isTrue();
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(past, future, null).endedBy(now)).isFalse();
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(future, null, null).endedBy(now)).isFalse();
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(future, null, past).endedBy(now)).isTrue();
        // No expiry date at all: nothing to confirm an end by.
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(null, null, null).endedBy(now)).isFalse();
        // Exactly at expiry is ended.
        assertThat(new RevenueCatSubscriberClient.StoreSubscription(now, null, null).endedBy(now)).isTrue();
    }
}
