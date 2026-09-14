package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * The full checkout -> webhook -> entitlements -> renewal -> billing-history path, end to end.
 * Design spec §11's "every state transition has an explicit test, not just the happy path" is
 * satisfied by RazorpayWebhookDispatcherIT's per-event coverage; this test's job is different --
 * proving the pieces actually compose through real HTTP and a real Spring context, which no
 * single-service test can show.
 */
class SubscriptionBillingEndToEndIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private RazorpayWebhookDispatcher dispatcher; // webhook signature verification is
                                                              // covered by RazorpayWebhookControllerIT;
                                                              // this test drives the dispatcher
                                                              // directly to keep focus on state, not
                                                              // signature plumbing.
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("e2e-billing-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("End To End Billing IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void checkoutActivationRenewalAndBillingHistoryAllComposeCorrectly() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        String razorpayPlanId = "plan_e2e_" + UUID.randomUUID();
        price.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(price);
        String razorpaySubscriptionId = "sub_e2e_" + UUID.randomUUID();

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(razorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto(razorpaySubscriptionId, "created"));

        // 1. Checkout.
        ResponseEntity<String> checkoutResponse = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(checkoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Entitlements still reflect Free -- the frontend success page never activates anything.
        ResponseEntity<String> entitlementsBeforeActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeActivation.getBody()).contains("\"planCode\":\"FREE\"");

        // 3. Activation webhook arrives.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Premium.
        ResponseEntity<String> entitlementsAfterActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterActivation.getBody()).contains("\"planCode\":\"PREMIUM\"");
        assertThat(entitlementsAfterActivation.getBody()).contains("\"FINO_AI\":true");

        // 5. A renewal webhook arrives a cycle later.
        dispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_e2e_" + UUID.randomUUID(), "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1896134400L)))); // synthetic-ok: fixture epoch second

        // 6. Billing history now shows the payment -- BillingHistoryService/Controller needed no
        // changes of their own for this; they were always correct, just fed by nothing until now.
        ResponseEntity<String> history = restTemplate.exchange(
                "/api/v1/billing/history", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("799.0");
        assertThat(history.getBody()).contains("SUCCESS");
    }

    /** Race identified in the checkout() review: two concurrent checkouts for a user with no
     *  existing subscription/order (e.g. two browser tabs) both pass the check-then-act
     *  {@code resumableOrderOrGuard} read before either INSERT commits, each creating a real,
     *  separate Razorpay subscription -- idx_subscriptions_one_active_per_user (V99) only guards
     *  once a webhook activates a row, not at checkout time. V206's
     *  idx_subscription_orders_one_pending_per_user partial unique index closes it at the DB
     *  level: whichever INSERT loses the race hits the constraint, and
     *  GlobalExceptionHandler's existing DataIntegrityViolationException handler turns that into
     *  409 CONFLICT instead of a 500.
     *
     *  <p>Two real HTTP threads against the real Testcontainers Postgres, synchronized on a latch
     *  so both requests are in flight together. The exact HTTP-status split is deliberately not
     *  asserted: if the two requests aren't scheduled closely enough, the loser can legitimately
     *  land past the first request's commit and take {@code resumableOrderOrGuard}'s resume path
     *  (also 200) instead of racing the INSERT -- that's correct behaviour, not the bug. What must
     *  always hold regardless of scheduling is the DB invariant: neither request ever 500s, and
     *  exactly one PENDING row survives for the user. */
    @Test
    void concurrentFirstCheckoutsForSameUserOnlyPersistOnePendingOrder() throws Exception {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        String razorpayPlanId = "plan_race_" + UUID.randomUUID();
        price.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(price);

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(razorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenAnswer(invocation -> new RazorpaySubscriptionDto("sub_race_" + UUID.randomUUID(), "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/v1/billing/checkout", request, String.class);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        ok.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflict.incrementAndGet();
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(ok.get() + conflict.get()).isEqualTo(threads); // no 500s from either request
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM subscription_orders WHERE user_id = ? AND status = 'PENDING'",
                Integer.class, user.getId());
        assertThat(pendingCount).isEqualTo(1);
    }

    /** Ambiguous-timeout scenario found in review: {@code BillingCheckoutService.checkout} only
     *  saves a {@link com.finora.entity.SubscriptionOrder} row AFTER {@code
     *  gateway.createSubscription} returns successfully. If the HTTP round-trip to Razorpay times
     *  out on Fynora's end but Razorpay's server actually created the subscription anyway, the
     *  gateway call throws before checkout() ever learns the new subscription's id -- no order row
     *  is ever saved, and {@code RazorpayWebhookDispatcher.handleActivated} used to have nothing to
     *  correlate the eventual activation webhook against, silently dropping ("...unknown
     *  razorpaySubscriptionId, ignoring") a real, live, paying Razorpay subscription forever. {@code
     *  RazorpayWebhookDispatcher.recoverOrderFromNotes} is the fix.
     *
     *  <p>The gateway mock below throws exactly like {@code
     *  RazorpaySubscriptionGatewayImpl.createSubscription} does on a real {@code RazorpayException}
     *  (see its own try/catch), proving checkout() never reaches {@code
     *  subscriptionOrderRepository.save} -- asserted directly against the table, not inferred. The
     *  activation webhook is then replayed carrying the {@code notes} Razorpay would echo back for
     *  the subscription it actually created on its side -- standing in for the real subscription id
     *  this test has no other way to learn, exactly like production: checkout()'s own return value
     *  is precisely what's unavailable in the real failure. */
    @Test
    void checkoutTimeoutAfterRealRazorpayCreationIsRecoveredByActivationWebhook() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        String razorpayPlanId = "plan_timeout_" + UUID.randomUUID();
        price.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(price);

        // Stands in for the real subscription Razorpay actually created on its side -- checkout()'s
        // own gateway call below never returns it, so nothing in this test's own process could
        // otherwise ever learn this id, exactly like production.
        String razorpaySubscriptionId = "sub_timeout_" + UUID.randomUUID();

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(razorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenThrow(new IllegalStateException("Razorpay createSubscription failed.",
                        new java.net.SocketTimeoutException("simulated client-side timeout, ok-real")));

        // 1. Checkout attempts to create the subscription; the simulated client-side timeout means
        // checkout() never learns the real razorpaySubscriptionId and never reaches
        // subscriptionOrderRepository.save.
        ResponseEntity<String> checkoutResponse = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(checkoutResponse.getStatusCode().is5xxServerError()).isTrue();

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM subscription_orders WHERE user_id = ?", Integer.class, user.getId());
        assertThat(orderCount).isZero();

        // 2. Entitlements are still Free -- nothing local knows about the real Razorpay subscription yet.
        ResponseEntity<String> entitlementsBeforeWebhook = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeWebhook.getBody()).contains("\"planCode\":\"FREE\"");

        // 3. Razorpay's activation webhook arrives for the subscription it actually created,
        // carrying back the exact notes checkout() submitted before the timeout.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId,
                        "current_end", 1893456000L, // synthetic-ok: fixture epoch second
                        "notes", Map.of(
                                "fynoraUserId", user.getId().toString(),
                                "planCode", "PREMIUM",
                                "billingCycle", "MONTHLY")))));

        // 4. Recovered: entitlements now reflect Premium, and a COMPLETED order row now exists even
        // though checkout() itself never created one.
        ResponseEntity<String> entitlementsAfterWebhook = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterWebhook.getBody()).contains("\"planCode\":\"PREMIUM\"");

        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT status, razorpay_subscription_id, amount FROM subscription_orders WHERE user_id = ?",
                user.getId());
        assertThat(order.get("status")).isEqualTo("COMPLETED");
        assertThat(order.get("razorpay_subscription_id")).isEqualTo(razorpaySubscriptionId);
    }
}
