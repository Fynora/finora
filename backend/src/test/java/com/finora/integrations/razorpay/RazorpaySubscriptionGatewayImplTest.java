package com.finora.integrations.razorpay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySubscriptionGatewayImplTest {

    @Test
    void isConfiguredDelegatesToProperties() {
        RazorpayProperties properties = new RazorpayProperties();
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(properties);

        assertThat(gateway.isConfigured()).isFalse();

        properties.setKeyId("rzp_test_123");
        properties.setKeySecret("secret");
        properties.setWebhookSecret("whsec");

        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void createSubscriptionRefusesWhenUnconfigured() {
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(new RazorpayProperties());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.createSubscription("plan_123", "MONTHLY", java.util.Map.of()));
    }

    @Test
    void anOfferCheckoutSendsTheUpfrontAmountTheStartDateAndThePayByDate() {
        java.time.Instant startsAt = java.time.Instant.parse("2026-11-30T12:00:00Z");
        java.time.Instant payBy = java.time.Instant.parse("2026-11-07T18:30:00Z");
        org.json.JSONObject request = RazorpaySubscriptionGatewayImpl.firstPeriodRequest("plan_123", "MONTHLY",
                java.util.Map.of("offerCode", "DIWALI2026"),
                new RazorpaySubscriptionGateway.FirstPeriod(12500, "Diwali offer - first month", startsAt, payBy));

        // Field names and units per Razorpay's create-subscription API: addons[].item with amount in
        // paise, start_at / expire_by in Unix seconds.
        assertThat(request.getString("plan_id")).isEqualTo("plan_123");
        assertThat(request.getInt("total_count")).isEqualTo(120);
        assertThat(request.getLong("start_at")).isEqualTo(startsAt.getEpochSecond());
        assertThat(request.getLong("expire_by")).isEqualTo(payBy.getEpochSecond());
        org.json.JSONObject item = request.getJSONArray("addons").getJSONObject(0).getJSONObject("item");
        assertThat(item.getLong("amount")).isEqualTo(12500L);
        assertThat(item.getString("currency")).isEqualTo("INR");
        assertThat(item.getString("name")).isEqualTo("Diwali offer - first month");
        assertThat(request.getJSONArray("addons").length()).isEqualTo(1);
        assertThat(request.getJSONObject("notes").getString("offerCode")).isEqualTo("DIWALI2026");
    }

    @Test
    void theAuthorisationPaymentIsTheEarliestPaidInvoice() {
        org.json.JSONObject issued = new org.json.JSONObject()
                .put("status", "issued").put("payment_id", org.json.JSONObject.NULL).put("amount_paid", 0);
        org.json.JSONObject later = new org.json.JSONObject()
                .put("status", "paid").put("payment_id", "pay_second").put("amount_paid", 24900).put("paid_at", 2000L);
        org.json.JSONObject first = new org.json.JSONObject()
                .put("status", "paid").put("payment_id", "pay_first").put("amount_paid", 12500).put("paid_at", 1000L);

        assertThat(RazorpaySubscriptionGatewayImpl.earliestPaid(java.util.List.of(issued, later, first)))
                .contains(new RazorpaySubscriptionGateway.AuthorisationPayment("pay_first", 12500));
        assertThat(RazorpaySubscriptionGatewayImpl.earliestPaid(java.util.List.of(issued))).isEmpty();
    }

    @Test
    void aRegularCheckoutSendsNoUpfrontAmountOrStartDate() {
        org.json.JSONObject request = RazorpaySubscriptionGatewayImpl.baseRequest("plan_123", "YEARLY", java.util.Map.of());

        assertThat(request.has("addons")).isFalse();
        assertThat(request.has("start_at")).isFalse();
        assertThat(request.has("expire_by")).isFalse();
        assertThat(request.getInt("total_count")).isEqualTo(20);
    }

    @Test
    void pauseSubscriptionRefusesWhenUnconfigured() {
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(new RazorpayProperties());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.pauseSubscription("sub_123"));
    }

    @Test
    void resumeSubscriptionRefusesWhenUnconfigured() {
        RazorpaySubscriptionGatewayImpl gateway = new RazorpaySubscriptionGatewayImpl(new RazorpayProperties());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.resumeSubscription("sub_123"));
    }
}
