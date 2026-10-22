package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the same silent-drop-on-unknown-id bug found and fixed in
 * RazorpayWebhookDispatcher.handleCharged (see that class's own doc): RevenueCat, like Razorpay,
 * does not guarantee webhook delivery order, so any of these events can race ahead of the
 * INITIAL_PURCHASE that first sets original_transaction_id on the row. Silently dropping the event
 * (the old behavior) let the controller mark the webhook_events row PROCESSED, so
 * WebhookEventRecoverySweepService never retried it -- see
 * RevenueCatWebhookDispatcher.subscriptionForOriginalTransactionId's own doc for the fix.
 */
class RevenueCatWebhookDispatcherIT extends AbstractIntegrationTest {

    @Autowired private RevenueCatWebhookDispatcher dispatcher;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;

    /** A store period ending at midnight India time (18:30 UTC the day before) renews on the
     *  India day, the same calendar BillingCalendar gives Razorpay renewals. */
    @Test
    void renewalStoresTheIndiaDayTheStorePeriodEndsOnNotThePreviousUtcDay() {
        User user = new User();
        user.setEmail("revenuecat-renewal-day-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat Renewal Day IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        String originalTransactionId = "txn_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(planRepository.findByCode("PLUS").orElseThrow().getId());
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setRevenuecatOriginalTransactionId(originalTransactionId);
        subscriptionRepository.save(subscription);

        dispatcher.dispatch("RENEWAL", Map.of(
                "original_transaction_id", originalTransactionId,
                "expiration_at_ms", 1893522600000L)); // synthetic-ok: 2030-01-01T18:30:00Z, fixture epoch millis

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getRenewalDate()).isEqualTo(LocalDate.of(2030, 1, 2));
    }

    @Test
    void renewalForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "original_transaction_id", originalTransactionId,
                "expiration_at_ms", 1893456000000L); // synthetic-ok: fixture epoch millis

        assertThatThrownBy(() -> dispatcher.dispatch("RENEWAL", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }

    @Test
    void cancellationForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("original_transaction_id", originalTransactionId);

        assertThatThrownBy(() -> dispatcher.dispatch("CANCELLATION", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }

    @Test
    void uncancellationForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("original_transaction_id", originalTransactionId);

        assertThatThrownBy(() -> dispatcher.dispatch("UNCANCELLATION", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }

    /** Highest-severity case of the six: if silently dropped, a subscription whose store-side
     *  access already expired would stay ACTIVE on its paid plan in Fynora indefinitely. */
    @Test
    void expirationForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("original_transaction_id", originalTransactionId);

        assertThatThrownBy(() -> dispatcher.dispatch("EXPIRATION", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }

    @Test
    void billingIssueForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("original_transaction_id", originalTransactionId);

        assertThatThrownBy(() -> dispatcher.dispatch("BILLING_ISSUE", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }

    @Test
    void productChangeForUnknownOriginalTransactionIdThrowsInsteadOfSilentlyDroppingIt() {
        String originalTransactionId = "txn_unknown_" + UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "original_transaction_id", originalTransactionId,
                "new_product_id", "irrelevant_product_id", "store", "APP_STORE");

        assertThatThrownBy(() -> dispatcher.dispatch("PRODUCT_CHANGE", payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originalTransactionId);
    }
}
