package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionReconciliationSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionReconciliationSweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("reconcile-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Reconciliation Sweep IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void downgradesACancelledSubscriptionPastItsPeriodEndToFree() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(BillingCalendar.today().minusDays(1));
        subscriptionRepository.save(subscription);

        int downgraded = sweepService.sweep();

        assertThat(downgraded).isGreaterThanOrEqualTo(1);
        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(free.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    /** The renewal date is the India day the period ends on, and the period ends at the start of
     *  that day. On the renewal day itself a cancelled subscription has already ended, and it has
     *  no ACTIVE/TRIAL row until this sweep downgrades it, so waiting one more day would leave the
     *  user without even Free access for that whole day. */
    @Test
    void downgradesACancelledSubscriptionOnItsRenewalDay() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(BillingCalendar.today());
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(planRepository.findByCode("FREE").orElseThrow().getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    @Test
    void leavesACancelledSubscriptionAloneTheDayBeforeItsRenewalDay() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(BillingCalendar.today().plusDays(1));
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(premium.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }

    @Test
    void leavesACancelledSubscriptionAloneBeforeItsPeriodEnd() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(BillingCalendar.today().plusDays(5));
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(premium.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }

    @Test
    void downgradingToFreeClearsAnyStaleCancellationDispatchedAt() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(BillingCalendar.today().minusDays(1));
        subscription.setCancellationDispatchedAt(java.time.Instant.now().minusSeconds(3600));
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }
}
