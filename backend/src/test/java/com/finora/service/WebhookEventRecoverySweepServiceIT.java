package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Payment;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionOrder;
import com.finora.entity.User;
import com.finora.entity.WebhookEvent;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.integrations.setu.FiType;
import com.finora.repository.PaymentRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the exact crash scenario the recovery sweep exists for (see
 * {@link WebhookEventRecoverySweepService}'s own doc): {@code claim()} committing while {@code
 * dispatch()} never runs, and a same-event-id retry alone being silently swallowed as a duplicate
 * rather than ever reaching the business logic. Each test reproduces this by calling {@code claim()}
 * directly (exactly what the controller does) and deliberately never calling the matching {@code
 * dispatch()} -- standing in for the process crashing between the two.
 */
class WebhookEventRecoverySweepServiceIT extends AbstractIntegrationTest {

    @Autowired private WebhookEventService webhookEventService;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private WebhookEventRecoverySweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private AccountAggregatorLinkRepository accountAggregatorLinkRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private RazorpaySubscriptionGateway gateway;
    @MockitoBean private EmailProvider emailProvider;

    private User createUser() {
        User user = new User();
        user.setEmail("webhook-recovery-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Webhook Recovery IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Stands in for a process crash landing between {@code claim()}'s commit and {@code
     *  dispatch()}'s -- the row is real, but still inside {@code WebhookEventRecoverySweepService
     *  .graceMinutes}'s window until this runs. */
    private void backdate(String eventId, int minutesAgo) {
        int updated = jdbcTemplate.update("UPDATE webhook_events SET created_at = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)), eventId);
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void razorpayActivationStuckWithNullStatusIsRecoveredBySweepNotBySenderRetryAlone() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("799.00"));
        subscriptionOrderRepository.save(order);

        // Exactly the shape RazorpayWebhookController stores: the FULL body, "payload" wrapping the
        // inner subscription/payment entities dispatch() actually reads.
        Map<String, Object> fullBody = Map.of(
                "event", "subscription.activated",
                "payload", Map.of("subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        String eventId = "evt_test_" + UUID.randomUUID();

        // claim() commits (own transaction) -- then the process "crashes": dispatch() is
        // deliberately never called, matching the ticket's exact scenario.
        assertThat(webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", fullBody)).isTrue();
        backdate(eventId, 10);

        // The sender's retry of the same event id: claim() alone silently swallows it as a
        // duplicate -- this is the bug. The business effect genuinely never ran.
        assertThat(webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", fullBody)).isFalse();
        assertThat(subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId)
                .orElseThrow().getStatus()).isEqualTo(SubscriptionOrder.STATUS_PENDING);

        int recovered = sweepService.sweep();

        assertThat(recovered).isEqualTo(1);
        SubscriptionOrder completed = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(SubscriptionOrder.STATUS_COMPLETED);
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo(razorpaySubscriptionId);
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        WebhookEvent recoveredEvent = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(recoveredEvent.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void razorpayChargedRecoveryDoesNotInsertASecondPaymentRowWhenTheChargeWasAlreadyRecorded() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        // Simulates dispatch() having already fully committed once (Payment row present) before the
        // crash landed in the narrow window before markProcessed's own separate commit.
        Payment existing = new Payment();
        existing.setUserId(user.getId());
        existing.setSubscriptionId(subscription.getId());
        existing.setProvider("RAZORPAY");
        existing.setStatus(Payment.STATUS_SUCCESS);
        existing.setAmount(new BigDecimal("799.00"));
        existing.setCurrency("INR");
        existing.setProviderTransactionId("pay_test_dup");
        paymentRepository.save(existing);

        Map<String, Object> fullBody = Map.of(
                "event", "subscription.charged",
                "payload", Map.of(
                        "payment", Map.of("entity", Map.of("id", "pay_test_dup", "amount", 79900)),
                        "subscription", Map.of("entity", Map.of(
                                "id", razorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        String eventId = "evt_test_" + UUID.randomUUID();
        assertThat(webhookEventService.claim(eventId, "RAZORPAY", "subscription.charged", fullBody)).isTrue();
        backdate(eventId, 10);

        int recovered = sweepService.sweep();

        assertThat(recovered).isEqualTo(1);
        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).hasSize(1); // not 2 -- the existsByProviderTransactionId guard held.
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void revenueCatCancellationStuckWithNullStatusIsRecoveredBySweep() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String originalTransactionId = "rc_txn_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setRevenuecatOriginalTransactionId(originalTransactionId);
        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);

        // RevenueCatWebhookController stores the event payload AS-IS (no "payload" wrapper) --
        // it's already the unwrapped shape dispatch() reads.
        Map<String, Object> eventPayload = Map.of(
                "original_transaction_id", originalTransactionId, "cancel_reason", "USER_INITIATED");

        String eventId = "revenuecat:" + UUID.randomUUID(); // event_id is VARCHAR(50); this prefix mirrors RevenueCatWebhookController's own scheme
        assertThat(webhookEventService.claim(eventId, "REVENUECAT", "CANCELLATION", eventPayload)).isTrue();
        backdate(eventId, 10);

        int recovered = sweepService.sweep();

        assertThat(recovered).isEqualTo(1);
        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void setuConsentRejectedStuckWithNullStatusIsRecoveredBySweep() {
        User user = createUser();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(user.getId());
        link.setConsentHandleId("consent-handle-" + UUID.randomUUID());
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        link.setLinkIdempotencyKey("idem-" + UUID.randomUUID());
        link = accountAggregatorLinkRepository.save(link);

        Map<String, Object> fullBody = Map.of("event", "consent.rejected", "consentHandleId", link.getConsentHandleId());
        String eventId = "evt_test_" + UUID.randomUUID();
        assertThat(webhookEventService.claim(eventId, "SETU", "consent.rejected", fullBody)).isTrue();
        backdate(eventId, 10);

        int recovered = sweepService.sweep();

        assertThat(recovered).isEqualTo(1);
        AccountAggregatorLink reloaded = accountAggregatorLinkRepository.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REJECTED);
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    /** Distinct from the {@code NULL}-status tests above: this stands in for a genuine handler
     *  exception on the first delivery (claim() commits, dispatch() throws, markFailed() records
     *  it), not a crash. Before this fix, a same-event-id retry after this point was silently
     *  swallowed by claim() forever -- {@code FAILED} was never a sweep candidate, only {@code
     *  NULL} was -- so the event was lost the instant the sender's retry window expired. Still
     *  backdated like the NULL tests: {@code findFailed}'s cutoff is an operational rate limit
     *  (see its own doc), not ambiguity about whether the row is still in flight, but it applies
     *  regardless. */
    @Test
    void revenueCatCancellationLeftFailedAfterAGenuineHandlerErrorIsReclaimedAndReprocessedBySweep() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String originalTransactionId = "rc_txn_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setRevenuecatOriginalTransactionId(originalTransactionId);
        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);

        Map<String, Object> eventPayload = Map.of(
                "original_transaction_id", originalTransactionId, "cancel_reason", "USER_INITIATED");

        String eventId = "revenuecat:" + UUID.randomUUID();
        assertThat(webhookEventService.claim(eventId, "REVENUECAT", "CANCELLATION", eventPayload)).isTrue();
        assertThat(webhookEventService.markFailed(eventId)).isTrue();
        backdate(eventId, 10);

        int recovered = sweepService.sweep();

        assertThat(recovered).isEqualTo(1);
        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void aRowStillWithinTheGracePeriodIsLeftAloneNotPrematurelyReprocessed() {
        Map<String, Object> fullBody = Map.of("event", "subscription.activated", "payload", Map.of());
        String eventId = "evt_test_" + UUID.randomUUID();
        assertThat(webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", fullBody)).isTrue();
        // No backdate -- this row is still "in flight" as far as the sweep should be concerned.

        int recovered = sweepService.sweep();

        assertThat(recovered).isZero();
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus()).isNull();
    }

    /** {@code findFailed}'s cutoff exists as an operational rate limit, not genuine
     *  in-flight ambiguity (see that method's own doc) -- but it still applies, so a
     *  freshly-FAILED row must not be reclaimed the instant it's created. */
    @Test
    void aFailedRowStillWithinTheGracePeriodIsLeftAloneNotPrematurelyReprocessed() {
        Map<String, Object> fullBody = Map.of("event", "subscription.activated", "payload", Map.of());
        String eventId = "evt_test_" + UUID.randomUUID();
        assertThat(webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", fullBody)).isTrue();
        assertThat(webhookEventService.markFailed(eventId)).isTrue();
        // No backdate -- freshly FAILED, still within the grace window.

        int recovered = sweepService.sweep();

        assertThat(recovered).isZero();
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_FAILED);
    }
}
