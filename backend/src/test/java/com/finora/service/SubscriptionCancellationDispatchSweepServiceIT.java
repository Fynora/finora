package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubscriptionCancellationDispatchSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionCancellationDispatchSweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("cancel-dispatch-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Cancellation Dispatch Sweep IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Subscription cancelledLocallyNotYetDispatched(User user, LocalDate renewalDate) {
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_dispatch_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(renewalDate);
        return subscriptionRepository.save(subscription);
    }

    @Test
    void dispatchesTheRealCancellationWhenWithinTheBufferWindow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(2));

        int dispatched = sweepService.sweep();

        assertThat(dispatched).isGreaterThanOrEqualTo(1);
        verify(gateway).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), eq(true));
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNotNull();
    }

    @Test
    void leavesASubscriptionAloneWhenOutsideTheBufferWindow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(10));

        sweepService.sweep();

        verify(gateway, never()).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), org.mockito.ArgumentMatchers.anyBoolean());
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }

    @Test
    void isIdempotentOnASecondRunOverTheSameRow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(1));

        sweepService.sweep();
        int secondRunDispatched = sweepService.sweep();

        assertThat(secondRunDispatched).isZero();
        verify(gateway).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), eq(true)); // exactly once total
    }

    @Test
    void oneSubscriptionsGatewayFailureDoesNotBlockDispatchingTheRest() {
        User failing = createUser();
        Subscription failingSubscription = cancelledLocallyNotYetDispatched(failing, LocalDate.now().plusDays(1));
        User succeeding = createUser();
        Subscription succeedingSubscription = cancelledLocallyNotYetDispatched(succeeding, LocalDate.now().plusDays(1));

        doThrow(new RuntimeException("Razorpay unreachable"))
                .when(gateway).cancelSubscription(eq(failingSubscription.getRazorpaySubscriptionId()), eq(true));

        int dispatched = sweepService.sweep();

        assertThat(dispatched).isEqualTo(1);
        Subscription reloadedSucceeding = subscriptionRepository.findById(succeedingSubscription.getId()).orElseThrow();
        assertThat(reloadedSucceeding.getCancellationDispatchedAt()).isNotNull();
        Subscription reloadedFailing = subscriptionRepository.findById(failingSubscription.getId()).orElseThrow();
        assertThat(reloadedFailing.getCancellationDispatchedAt()).isNull(); // left for the next sweep to retry
    }
}
