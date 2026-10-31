package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.integrations.revenuecat.RevenueCatSubscriberClient;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A paid row whose ending event never arrived (found 2026-10-31: a Play subscription 60 days past its
 * period still had Plus). Assertions are per row, never on the pass's totals: the IT suite shares
 * tables, so other classes' rows can be candidates too.
 */
class LapsedSubscriptionCheckSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private LapsedSubscriptionCheckSweepService sweep;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;
    @Autowired private EntitlementService entitlementService;

    @MockitoBean private RevenueCatSubscriberClient revenueCat;
    @MockitoBean private RazorpaySubscriptionGateway razorpay;

    private final Instant now = Instant.now();
    private final java.util.List<UUID> created = new java.util.ArrayList<>();

    @BeforeEach
    void configured() {
        when(revenueCat.isConfigured()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(true);
    }

    /** These rows are candidates for the other billing sweeps too (a Razorpay row with auto-renew off
     *  and a past date is exactly SubscriptionCancellationDispatchSweepServiceIT's input, whose pass
     *  count it asserts). Soft-deleted afterwards, every sweep's query skips them. */
    @org.junit.jupiter.api.AfterEach
    void removeTheseRows() {
        subscriptionRepository.deleteAllById(created);
    }

    private Subscription paid(String provider, LocalDate renewalDate, boolean autoRenew) {
        User user = new User();
        user.setEmail("lapsed-check-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Lapsed Check IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        Subscription s = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        s.setPlanId(plus.getId());
        s.setBillingCycle("MONTHLY");
        s.setStatus(Subscription.STATUS_ACTIVE);
        s.setPaymentProvider(provider);
        s.setAutoRenew(autoRenew);
        s.setRenewalDate(renewalDate);
        if ("REVENUECAT".equals(provider)) {
            s.setStorePlatform("ANDROID");
            s.setRevenuecatOriginalTransactionId("GPA.0000-0000-0000-" + UUID.randomUUID());
        } else if ("RAZORPAY".equals(provider)) {
            s.setRazorpaySubscriptionId("sub_lapsed_" + UUID.randomUUID());
        }
        Subscription saved = subscriptionRepository.save(s);
        created.add(saved.getId());
        return saved;
    }

    private LocalDate daysAgo(int days) {
        return BillingCalendar.today().minusDays(days);
    }

    private void store(Subscription s, Instant expires, Instant grace, Instant refunded) {
        when(revenueCat.subscription(eq(s.getUserId().toString()), eq(s.getRevenuecatOriginalTransactionId()), any()))
                .thenReturn(Optional.of(new RevenueCatSubscriberClient.StoreSubscription(expires, grace, refunded)));
    }

    private Subscription reload(Subscription s) {
        return subscriptionRepository.findById(s.getId()).orElseThrow();
    }

    private String plan(Subscription s) {
        return entitlementService.planCodeFor(s.getUserId());
    }

    @Test
    void aStoreSubscriptionRevenueCatSaysExpiredEndsPlus() {
        Subscription s = paid("REVENUECAT", daysAgo(60), true);
        store(s, now.minus(60, ChronoUnit.DAYS), null, null);
        assertThat(plan(s)).isEqualTo("PLUS");

        sweep.sweep(true);

        Subscription after = reload(s);
        assertThat(plan(s)).isEqualTo("FREE");
        assertThat(after.getPaymentProvider()).isNull();
        assertThat(after.getRevenuecatOriginalTransactionId()).isNull();
        assertThat(subscriptionEventRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getSubscriptionId()).isEqualTo(s.getId());
            assertThat(e.getMetadata()).containsEntry("reason", "PROVIDER_CONFIRMED_ENDED").containsEntry("provider", "REVENUECAT");
        });
    }

    @Test
    void aDryRunAsksButChangesNothing() {
        Subscription s = paid("REVENUECAT", daysAgo(60), true);
        store(s, now.minus(60, ChronoUnit.DAYS), null, null);

        sweep.sweep(false);

        assertThat(plan(s)).isEqualTo("PLUS");
        assertThat(reload(s).getRenewalDate()).isEqualTo(daysAgo(60));
    }

    @Test
    void aStoreSubscriptionStillLiveKeepsPlusAndGetsItsRealPaidUntilDate() {
        Subscription s = paid("REVENUECAT", daysAgo(10), true);
        Instant expires = now.plus(20, ChronoUnit.DAYS);
        store(s, expires, null, null);

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
        assertThat(reload(s).getRenewalDate()).isEqualTo(BillingCalendar.dateOf(expires));
    }

    @Test
    void aStoreSubscriptionInsideItsBillingGracePeriodIsLeftAlone() {
        Subscription s = paid("REVENUECAT", daysAgo(5), true);
        store(s, now.minus(5, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS), null);

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
        assertThat(reload(s).getRenewalDate()).isEqualTo(daysAgo(5));
    }

    @Test
    void aRefundedStoreSubscriptionEndsPlusEvenBeforeItsExpiry() {
        Subscription s = paid("REVENUECAT", daysAgo(10), true);
        store(s, now.plus(20, ChronoUnit.DAYS), null, now.minus(1, ChronoUnit.DAYS));

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("FREE");
    }

    @Test
    void whenRevenueCatCannotBeAskedNothingChanges() {
        Subscription failing = paid("REVENUECAT", daysAgo(60), true);
        when(revenueCat.subscription(eq(failing.getUserId().toString()), any(), any()))
                .thenThrow(new IllegalStateException("network"));
        Subscription unknown = paid("REVENUECAT", daysAgo(60), true);
        when(revenueCat.subscription(eq(unknown.getUserId().toString()), any(), any())).thenReturn(Optional.empty());
        // A failure on one row does not stop the next.
        Subscription ended = paid("REVENUECAT", daysAgo(60), true);
        store(ended, now.minus(60, ChronoUnit.DAYS), null, null);

        sweep.sweep(true);

        assertThat(plan(failing)).isEqualTo("PLUS");
        assertThat(plan(unknown)).isEqualTo("PLUS");
        assertThat(plan(ended)).isEqualTo("FREE");
    }

    @Test
    void withoutARevenueCatKeyStoreRowsAreLeftAlone() {
        when(revenueCat.isConfigured()).thenReturn(false);
        Subscription s = paid("REVENUECAT", daysAgo(60), true);
        store(s, now.minus(60, ChronoUnit.DAYS), null, null);

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
    }

    @Test
    void aRowStillInsideTheGraceDaysIsNotAsked() {
        Subscription s = paid("REVENUECAT", daysAgo(1), true);
        store(s, now.minus(1, ChronoUnit.DAYS), null, null);

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
    }

    @Test
    void aRazorpaySubscriptionRazorpayEndedEndsPlus() {
        Subscription s = paid("RAZORPAY", daysAgo(20), true);
        when(razorpay.fetchSubscriptionState(s.getRazorpaySubscriptionId()))
                .thenReturn(new RazorpaySubscriptionGateway.SubscriptionState("halted", null));

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("FREE");
        assertThat(reload(s).getRazorpaySubscriptionId()).isNull();
    }

    @Test
    void aLiveRazorpaySubscriptionGetsItsRealPaidUntilDate() {
        Subscription s = paid("RAZORPAY", daysAgo(20), true);
        Instant currentEnd = now.plus(10, ChronoUnit.DAYS);
        when(razorpay.fetchSubscriptionState(s.getRazorpaySubscriptionId()))
                .thenReturn(new RazorpaySubscriptionGateway.SubscriptionState("active", currentEnd));

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
        assertThat(reload(s).getRenewalDate()).isEqualTo(BillingCalendar.dateOf(currentEnd));
    }

    @Test
    void aRazorpaySubscriptionStillRetryingOrCancelledLocallyIsLeftAlone() {
        Subscription retrying = paid("RAZORPAY", daysAgo(20), true);
        when(razorpay.fetchSubscriptionState(retrying.getRazorpaySubscriptionId()))
                .thenReturn(new RazorpaySubscriptionGateway.SubscriptionState("pending", null));
        // Auto-renew off: SubscriptionCancellationDispatchSweepService reads this date; never moved here.
        Subscription cancelledLocally = paid("RAZORPAY", daysAgo(20), false);
        when(razorpay.fetchSubscriptionState(cancelledLocally.getRazorpaySubscriptionId()))
                .thenReturn(new RazorpaySubscriptionGateway.SubscriptionState("active", now.plus(10, ChronoUnit.DAYS)));

        sweep.sweep(true);

        assertThat(plan(retrying)).isEqualTo("PLUS");
        assertThat(reload(retrying).getRenewalDate()).isEqualTo(daysAgo(20));
        assertThat(plan(cancelledLocally)).isEqualTo("PLUS");
        assertThat(reload(cancelledLocally).getRenewalDate()).isEqualTo(daysAgo(20));
    }

    @Test
    void aComplimentaryPlanIsNeverAsked() {
        Subscription s = paid(null, daysAgo(60), true);

        sweep.sweep(true);

        assertThat(plan(s)).isEqualTo("PLUS");
        // Only this row: other rows in the shared tables may well be asked about.
        org.mockito.Mockito.verify(razorpay, org.mockito.Mockito.never())
                .fetchSubscriptionState(org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.verify(revenueCat, org.mockito.Mockito.never())
                .subscription(eq(s.getUserId().toString()), any(), any());
    }
}
