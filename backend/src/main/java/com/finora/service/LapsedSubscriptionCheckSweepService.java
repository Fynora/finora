package com.finora.service;

import com.finora.observability.jobs.BackgroundJobReport;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.integrations.revenuecat.RevenueCatSubscriberClient;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The safety net for a provider event that never arrived. Plus is granted by the subscription row's
 * status alone ({@code SubscriptionRepository.findActiveOrTrial}), and only a provider's webhook ends
 * it: RevenueCat's EXPIRATION, Razorpay's halted or cancelled. RevenueCat and Razorpay both stop
 * retrying a webhook after a while, and {@code SubscriptionReconciliationSweepService} covers only a
 * row already CANCELLED, so a paid row whose ending event was lost stayed ACTIVE with Plus for good.
 * Measured on a local backend 2026-10-31: a Play subscription whose period ended 60 days earlier,
 * with no EXPIRATION, still had Plus and Advanced Reports.
 *
 * <p>Once a day, each paid row more than {@code grace-days} past its paid-until date is asked about
 * at its provider (the owner's choice, 2026-10-31: ask, never end access on a date alone):
 * <ul>
 *   <li><b>RevenueCat</b> (Google Play, App Store): ended when RevenueCat says it was refunded, or is
 *       past both its expiry and any billing grace period. Still live: the row's paid-until date is
 *       moved to RevenueCat's expiry, so a lost RENEWAL stops showing a past date and the row is not
 *       asked about again until then.</li>
 *   <li><b>Razorpay</b>: ended only on a final status (halted, cancelled, completed, expired). A live
 *       auto-renewing one gets its paid-until date from Razorpay's current period end. A row the user
 *       cancelled locally (auto-renew off) is never moved: SubscriptionCancellationDispatchSweepService
 *       reads that date.</li>
 * </ul>
 * Ending a row does what the lost event would have done: Free plan, provider and its ids cleared, a
 * SubscriptionEvent recording why. Anything the provider cannot answer (not configured, a network or
 * HTTP error, no such subscription) changes nothing and is counted.
 *
 * <p>Modes ({@code app.lapsed-subscription-check.sweep.mode}), as the other repair sweeps:
 * {@code dry-run}, the default, asks and logs what it would do and writes nothing; {@code apply}
 * writes; {@code off} does nothing. One transaction per row, so one row's failure or lock conflict
 * leaves the others alone. Nothing about a user is logged but the subscription id.
 */
@Service
public class LapsedSubscriptionCheckSweepService {

    private static final Logger log = LoggerFactory.getLogger(LapsedSubscriptionCheckSweepService.class);

    static final String PROVIDER_REVENUECAT = "REVENUECAT";
    static final String PROVIDER_RAZORPAY = "RAZORPAY";
    /** Razorpay statuses after which it will never charge this subscription again. */
    static final Set<String> RAZORPAY_ENDED = Set.of("halted", "cancelled", "completed", "expired");

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;
    private final RevenueCatSubscriberClient revenueCat;
    private final RazorpaySubscriptionGateway razorpay;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Value("${app.lapsed-subscription-check.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.lapsed-subscription-check.sweep.mode:dry-run}")
    private String mode;

    @Value("${app.lapsed-subscription-check.sweep.grace-days:3}")
    private int graceDays;

    @org.springframework.beans.factory.annotation.Autowired
    public LapsedSubscriptionCheckSweepService(SubscriptionRepository subscriptionRepository,
                                               SubscriptionEventRepository subscriptionEventRepository,
                                               PlanRepository planRepository,
                                               IapProductRepository iapProductRepository,
                                               RevenueCatSubscriberClient revenueCat,
                                               RazorpaySubscriptionGateway razorpay,
                                               PlatformTransactionManager transactionManager) {
        this(subscriptionRepository, subscriptionEventRepository, planRepository, iapProductRepository, revenueCat,
                razorpay, transactionManager, Clock.systemUTC());
    }

    LapsedSubscriptionCheckSweepService(SubscriptionRepository subscriptionRepository,
                                        SubscriptionEventRepository subscriptionEventRepository,
                                        PlanRepository planRepository,
                                        IapProductRepository iapProductRepository,
                                        RevenueCatSubscriberClient revenueCat,
                                        RazorpaySubscriptionGateway razorpay,
                                        PlatformTransactionManager transactionManager,
                                        Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
        this.revenueCat = revenueCat;
        this.razorpay = razorpay;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What one pass did (or, in dry-run, would do). */
    public record Result(int checked, int ended, int extended, int stillUnconfirmed, int unanswered) {}

    /** What the provider said about one row. */
    enum Outcome { ENDED, EXTENDED, UNCONFIRMED, UNANSWERED }

    @Scheduled(fixedDelayString = "${app.lapsed-subscription-check.sweep.interval-ms:86400000}",
            initialDelayString = "${app.lapsed-subscription-check.sweep.initial-delay-ms:600000}")
    public void scheduledSweep() {
        if (!sweepEnabled || "off".equalsIgnoreCase(mode)) {
            BackgroundJobReport.switchedOff();
            return;
        }
        Result r = sweep(isApply());
        if (r.checked() > 0) {
            log.info("Lapsed subscription check ({}): {} checked, {} ended, {} paid-until moved, {} not confirmed ended, "
                    + "{} unanswered.", isApply() ? "apply" : "dry-run", r.checked(), r.ended(), r.extended(),
                    r.stillUnconfirmed(), r.unanswered());
        }
    }

    private boolean isApply() {
        return "apply".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    /** One pass. {@code apply=false} asks the providers and writes nothing. */
    public Result sweep(boolean apply) {
        LocalDate cutoff = BillingCalendar.today().minusDays(Math.max(0, graceDays));
        List<Subscription> candidates = subscriptionRepository.findPaidAccessPastRenewalDate(cutoff);
        int ended = 0, extended = 0, unconfirmed = 0, unanswered = 0;
        for (Subscription candidate : candidates) {
            Outcome outcome;
            try {
                outcome = checkOne(candidate.getId(), cutoff, apply);
            } catch (RuntimeException e) {
                BackgroundJobReport.itemFailed(e);
                // A provider error, a lock conflict with a webhook landing now: this row only, next pass.
                log.warn("Lapsed subscription check skipped subscription {}: {}", candidate.getId(),
                        e.getClass().getSimpleName());
                outcome = Outcome.UNANSWERED;
            }
            switch (outcome) {
                case ENDED -> ended++;
                case EXTENDED -> extended++;
                case UNCONFIRMED -> unconfirmed++;
                case UNANSWERED -> unanswered++;
            }
        }
        return new Result(candidates.size(), ended, extended, unconfirmed, unanswered);
    }

    private Outcome checkOne(UUID subscriptionId, LocalDate cutoff, boolean apply) {
        // Read fresh inside its own transaction: a webhook may have settled this row since the query.
        Outcome outcome = transactionTemplate.execute(status -> {
            Subscription s = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (s == null || s.getRenewalDate() == null || !s.getRenewalDate().isBefore(cutoff)
                    || !Set.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIAL).contains(s.getStatus())) {
                return Outcome.UNCONFIRMED;
            }
            return switch (String.valueOf(s.getPaymentProvider())) {
                case PROVIDER_REVENUECAT -> checkStore(s, apply);
                case PROVIDER_RAZORPAY -> checkRazorpay(s, apply);
                default -> Outcome.UNCONFIRMED;
            };
        });
        return outcome == null ? Outcome.UNANSWERED : outcome;
    }

    private Outcome checkStore(Subscription s, boolean apply) {
        if (!revenueCat.isConfigured() || s.getRevenuecatOriginalTransactionId() == null) return Outcome.UNANSWERED;
        Optional<RevenueCatSubscriberClient.StoreSubscription> store = revenueCat.subscription(
                s.getUserId().toString(), s.getRevenuecatOriginalTransactionId(), productIdOf(s));
        if (store.isEmpty()) return Outcome.UNANSWERED;
        Instant now = clock.instant();
        if (store.get().endedBy(now)) {
            if (apply) endAccess(s, PROVIDER_REVENUECAT, store.get().refundedAt() != null ? "refunded" : "expired");
            return Outcome.ENDED;
        }
        Instant paidUntil = store.get().expiresAt();
        if (paidUntil != null && paidUntil.isAfter(now)) {
            if (apply) {
                s.setRenewalDate(BillingCalendar.dateOf(paidUntil));
                subscriptionRepository.save(s);
            }
            return Outcome.EXTENDED;
        }
        // Expired but inside a billing grace period: still the customer's, asked again tomorrow.
        return Outcome.UNCONFIRMED;
    }

    private Outcome checkRazorpay(Subscription s, boolean apply) {
        if (!razorpay.isConfigured() || s.getRazorpaySubscriptionId() == null) return Outcome.UNANSWERED;
        RazorpaySubscriptionGateway.SubscriptionState state = razorpay.fetchSubscriptionState(s.getRazorpaySubscriptionId());
        String status = state.status() == null ? "" : state.status().toLowerCase(Locale.ROOT);
        if (RAZORPAY_ENDED.contains(status)) {
            if (apply) endAccess(s, PROVIDER_RAZORPAY, status);
            return Outcome.ENDED;
        }
        Instant currentEnd = state.currentEnd();
        if (s.isAutoRenew() && "active".equals(status) && currentEnd != null && currentEnd.isAfter(clock.instant())) {
            if (apply) {
                s.setRenewalDate(BillingCalendar.dateOf(currentEnd));
                subscriptionRepository.save(s);
            }
            return Outcome.EXTENDED;
        }
        return Outcome.UNCONFIRMED;
    }

    /** What the lost event would have done: Free, no provider, no provider ids, a recorded reason. */
    private void endAccess(Subscription s, String provider, String providerStatus) {
        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        s.setPlanId(free.getId());
        s.setBillingCycle(null);
        s.setPaymentProvider(null);
        s.setStorePlatform(null);
        s.setRazorpaySubscriptionId(null);
        s.setRevenuecatOriginalTransactionId(null);
        s.setRevenuecatLatestTransactionId(null);
        s.setStatus(Subscription.STATUS_ACTIVE);
        s.setAutoRenew(true);
        subscriptionRepository.save(s);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(s.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
        event.setMetadata(Map.of("reason", "PROVIDER_CONFIRMED_ENDED", "provider", provider,
                "providerStatus", providerStatus));
        subscriptionEventRepository.save(event);
    }

    /** The store product id this row was bought as (iap_products), for RevenueCat's fallback match. */
    private String productIdOf(Subscription s) {
        return iapProductRepository.findAll().stream()
                .filter(IapProduct::isActive)
                .filter(p -> p.getPlanId().equals(s.getPlanId()))
                .filter(p -> s.getStorePlatform() == null || s.getStorePlatform().equals(p.getPlatform()))
                .filter(p -> s.getBillingCycle() == null || s.getBillingCycle().equals(p.getBillingCycle()))
                .map(IapProduct::getProviderProductId)
                .findFirst().orElse(null);
    }
}
