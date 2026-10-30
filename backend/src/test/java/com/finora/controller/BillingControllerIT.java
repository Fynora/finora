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
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private com.finora.repository.BillingOfferRepository billingOfferRepository;
    @Autowired private com.finora.repository.SubscriptionOrderRepository subscriptionOrderRepository;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("billing-checkout-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Checkout IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void checkoutCreatesARazorpaySubscriptionAndReturnsItsId() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        price.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(price);

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(price.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_test_123", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/checkout", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("sub_test_123");
    }

    @Test
    void cancelSetsAutoRenewFalseWithoutCallingRazorpayYet() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancel", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }

    @Test
    void undoCancellationFlipsAutoRenewBackOnBeforeAnythingIsDispatched() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancellation/undo", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isTrue();
    }

    @Test
    void undoCancellationReturnsConflictOnceDispatched() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscription.setCancellationDispatchedAt(java.time.Instant.now());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancellation/undo", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void undoCancellationReturnsBadRequestWhenThereIsNoBillingSubscription() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancellation/undo", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePlanSchedulesADowngradeForAnExistingPaidSubscriber() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PLUS").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        plusMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(plusMonthly);

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).updateSubscription(eq(subscription.getRazorpaySubscriptionId()),
                eq(plusMonthly.getRazorpayPlanId()), eq(true));
    }

    @Test
    void changePlanReturnsCheckoutDetailsForAnUpgrade() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PREMIUM").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        when(gateway.createSubscription(eq(premiumMonthly.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_new_" + UUID.randomUUID(), "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("razorpaySubscriptionId").contains("keyId");
    }

    @Test
    void pauseCallsRazorpayAndSetsStatusPaused() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/pause", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).pauseSubscription(razorpaySubscriptionId);

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(com.finora.entity.Subscription.STATUS_PAUSED);
    }

    @Test
    void pausedSubscriberCanStillReachTheirBillingPageAndThenResume() {
        // The whole reason mySubscription() was widened past findActiveOrTrial: without it, this
        // GET would 404 for a paused user, hiding the Resume button that's the only way back.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        restTemplate.postForEntity("/api/v1/billing/pause", new HttpEntity<>(null, bearerFor(user)), String.class);

        ResponseEntity<String> mySubscription = restTemplate.exchange(
                "/api/v1/billing/subscription", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(mySubscription.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mySubscription.getBody()).contains("\"status\":\"PAUSED\"");

        ResponseEntity<String> resume = restTemplate.postForEntity(
                "/api/v1/billing/resume", new HttpEntity<>(null, bearerFor(user)), String.class);
        assertThat(resume.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).resumeSubscription(razorpaySubscriptionId);

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(com.finora.entity.Subscription.STATUS_ACTIVE);
    }

    @Test
    void pauseRefusesASubscriptionAlreadySetToCancel() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/pause", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(gateway, org.mockito.Mockito.never()).pauseSubscription(any());
    }

    @Test
    void resumeRefusesWhenNoPausedSubscriptionExists() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/resume", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(gateway, org.mockito.Mockito.never()).resumeSubscription(any());
    }

    @Test
    void mySubscriptionReportsThePlanAndRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRenewalDate(java.time.LocalDate.of(2026, 11, 1));
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/subscription", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"planCode\":\"PLUS\"").contains("2026-11-01").contains("\"hasBillingSubscription\":true");
    }

    @Test
    void aSecondCheckoutForTheSamePlanResumesTheFirstInsteadOfCreatingAnother() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.isConfigured()).thenReturn(true);
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        when(gateway.createSubscription(any(), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_first_attempt", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));
        ResponseEntity<String> first = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);
        assertThat(first.getBody()).contains("sub_first_attempt");

        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("sub_first_attempt");
        verify(gateway, times(1)).createSubscription(any(), any(), anyMap());
    }

    @Test
    void cancellingAPendingOrderAllowsCheckingOutADifferentPlanAfterwards() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.isConfigured()).thenReturn(true);
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_prem_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        plusMonthly.setRazorpayPlanId("plan_plus_" + UUID.randomUUID());
        billingPriceRepository.save(plusMonthly);
        when(gateway.createSubscription(eq(premiumMonthly.getRazorpayPlanId()), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_premium_attempt", "created"));
        when(gateway.createSubscription(eq(plusMonthly.getRazorpayPlanId()), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_plus_attempt", "created"));
        // V297 seeds a Plus offer live until 8 Nov 2026; a run inside that window checks out through
        // the offer path. This test is about the pending-order guard, not the price, so both paths
        // return the same subscription.
        when(gateway.createSubscriptionWithFirstPeriod(eq(plusMonthly.getRazorpayPlanId()), any(), anyMap(), any()))
                .thenReturn(new RazorpaySubscriptionDto("sub_plus_attempt", "created"));

        restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);

        ResponseEntity<String> blocked = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> cancel = restTemplate.postForEntity(
                "/api/v1/billing/pending-order/cancel", new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> retried = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).contains("sub_plus_attempt");
    }

    // --- Billing offers (V297) -----------------------------------------------------------------

    @Test
    void theDiwaliOfferIsSeededForPlusUntilTheEndOf7NovemberIndiaTime() {
        // Fixed instants, so this holds whatever day the suite runs. 18:30 UTC is midnight in India.
        java.util.List<com.finora.entity.BillingOffer> lastSecond = billingOfferRepository
                .findLiveAt(java.time.Instant.parse("2026-11-07T18:29:59Z")).stream()
                .filter(o -> "DIWALI2026".equals(o.getCode())).toList();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        assertThat(lastSecond).hasSize(2).allMatch(o -> o.getPlanId().equals(plus.getId()));
        assertThat(lastSecond).anySatisfy(o -> {
            assertThat(o.getBillingCycle()).isEqualTo("MONTHLY");
            assertThat(o.getFirstPeriodPrice()).isEqualByComparingTo("125.00");
        });
        assertThat(lastSecond).anySatisfy(o -> {
            assertThat(o.getBillingCycle()).isEqualTo("YEARLY");
            assertThat(o.getFirstPeriodPrice()).isEqualByComparingTo("999.00");
        });

        assertThat(billingOfferRepository.findLiveAt(java.time.Instant.parse("2026-11-07T18:30:00Z")))
                .noneMatch(o -> "DIWALI2026".equals(o.getCode()));
        assertThat(billingOfferRepository.findLiveAt(java.time.Instant.parse("2026-10-29T18:29:59Z")))
                .noneMatch(o -> "DIWALI2026".equals(o.getCode()));
    }

    @Test
    void offersAreReadableWithoutSigningIn() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/billing/offers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\":[");
    }

    /** End to end through HTTP with an offer live right now. Premium yearly, because no other test
     *  checks out that plan and cycle, and the row is removed afterwards: the IT classes share one
     *  database. */
    @Test
    void checkoutUnderALiveOfferChargesTheOfferPriceUpFront() {
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumYearly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "YEARLY").orElseThrow();
        premiumYearly.setRazorpayPlanId("plan_prem_y_" + UUID.randomUUID());
        billingPriceRepository.save(premiumYearly);
        com.finora.entity.BillingOffer offer = new com.finora.entity.BillingOffer();
        offer.setCode("IT_" + UUID.randomUUID().toString().substring(0, 8));
        offer.setPlanId(premium.getId());
        offer.setBillingCycle("YEARLY");
        offer.setFirstPeriodPrice(new BigDecimal("4000.00"));
        offer.setLabel("Test offer");
        offer.setStartsAt(java.time.Instant.now().minusSeconds(3600));
        offer.setEndsAt(java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        offer = billingOfferRepository.save(offer);
        try {
            String offerSubId = "sub_offer_" + UUID.randomUUID();
            User user = createUser();
            subscriptionService.provisionFreeSubscription(user.getId());
            when(gateway.isConfigured()).thenReturn(true);
            when(gateway.createSubscriptionWithFirstPeriod(eq(premiumYearly.getRazorpayPlanId()), eq("YEARLY"), anyMap(), any()))
                    .thenReturn(new RazorpaySubscriptionDto(offerSubId, "created"));

            ResponseEntity<String> offers = restTemplate.getForEntity("/api/v1/billing/offers", String.class);
            assertThat(offers.getBody()).contains("\"code\":\"" + offer.getCode() + "\"")
                    .contains("\"regularPrice\":8000");

            ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/billing/checkout",
                    new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"YEARLY\"}", bearerFor(user)), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains(offerSubId);
            org.mockito.ArgumentCaptor<RazorpaySubscriptionGateway.FirstPeriod> firstPeriod =
                    org.mockito.ArgumentCaptor.forClass(RazorpaySubscriptionGateway.FirstPeriod.class);
            verify(gateway).createSubscriptionWithFirstPeriod(eq(premiumYearly.getRazorpayPlanId()), eq("YEARLY"),
                    anyMap(), firstPeriod.capture());
            assertThat(firstPeriod.getValue().amountPaise()).isEqualTo(400000L);
            assertThat(firstPeriod.getValue().payBy()).isEqualTo(offer.getEndsAt());
            assertThat(firstPeriod.getValue().regularBillingStartsAt())
                    .isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(364)));
            verify(gateway, never()).createSubscription(eq(premiumYearly.getRazorpayPlanId()), any(), anyMap());

            com.finora.entity.SubscriptionOrder order =
                    subscriptionOrderRepository.findByRazorpaySubscriptionId(offerSubId).orElseThrow();
            assertThat(order.getOfferCode()).isEqualTo(offer.getCode());
            assertThat(order.getAmount()).isEqualByComparingTo("4000.00");
        } finally {
            billingOfferRepository.delete(offer);
        }
    }
}
