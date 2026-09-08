package com.finora.service;

import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing -- auto-renew resume (design spec at docs/superpowers/specs/
 * 2026-09-08-billing-auto-renew-resume-design.md). {@code BillingCheckoutService.cancel()} no
 * longer calls Razorpay synchronously -- this sweep is what actually sends the real
 * {@code cancel_at_cycle_end=true} call, once a locally-cancelled subscription is within
 * {@code bufferDays} of its {@code renewalDate}. Same shape as
 * {@code SubscriptionReconciliationSweepService}: {@code fixedDelay}, gated by a flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 *
 * <p><b>Per-row transactions, not one transaction around the whole loop.</b> Bug found in
 * self-review: an earlier version wrapped the entire loop -- N sequential Razorpay HTTP calls --
 * in a single {@code @Transactional} method, holding one DB connection/row-lock for however long
 * all N calls took combined. {@code StatementStorageSweepService}'s own doc comment already
 * establishes why that is wrong in this codebase ("holding one open for however long N network
 * calls take would only cost a connection-pool slot for no benefit"), and this repo has a real
 * prior HikariCP pool-exhaustion incident behind that rule. {@link TransactionTemplate} (same
 * pattern {@code NotificationDispatcher} already uses) gives each row its own short transaction
 * instead -- {@code dispatchOne} can't use a plain {@code @Transactional} method here because
 * Spring's proxy-based AOP does not intercept a same-class method call from {@link #sweep()}.
 *
 * <p><b>Re-checks eligibility inside its own transaction.</b> {@link #sweep}'s candidate query
 * runs before any per-row transaction opens, so by the time {@code dispatchOne} actually reads a
 * given row, {@code BillingCheckoutService.resume()} may have already flipped it back to
 * {@code autoRenew=true} concurrently. Re-reading and re-checking inside the same transaction that
 * dispatches (rather than trusting the outer query's now-possibly-stale snapshot) closes that
 * race for the normal case -- the same "safety-critical check, re-checked fresh immediately before
 * the irreversible call" shape {@code StatementStorageSweepService.sweep()} already uses for its
 * own irreversible action.
 *
 * <p>Residual risk, accepted explicitly in the design: if this sweep is down for an outage
 * spanning the entire buffer window, a subscription whose user wants it cancelled could still
 * auto-renew and charge on Razorpay's side, since nothing was ever sent to stop it. A separate,
 * much narrower residual race also remains: if a {@code resume()} call and this sweep's dispatch
 * for the very same row land within the same few-hundred-millisecond window, the optimistic-lock
 * version column ({@code BaseEntity.version}) prevents silent data corruption (one of the two
 * transactions loses and either throws or is simply not chosen by the re-check above) but does not
 * guarantee resume() always wins that exact race. Accepted as-is: engineering out a sub-second
 * window this narrow (pessimistic row locking across an external HTTP call) was judged not worth
 * the added complexity relative to the wide, already-accepted outage-window risk above.
 */
@Service
public class SubscriptionCancellationDispatchSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCancellationDispatchSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.subscription-cancellation-dispatch.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.subscription-cancellation-dispatch.sweep.buffer-days:3}")
    private int bufferDays;

    public SubscriptionCancellationDispatchSweepService(SubscriptionRepository subscriptionRepository,
                                                          SubscriptionEventRepository subscriptionEventRepository,
                                                          RazorpaySubscriptionGateway gateway,
                                                          PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.gateway = gateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.subscription-cancellation-dispatch.sweep.interval-ms:3600000}",
            initialDelayString = "${app.subscription-cancellation-dispatch.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int dispatched = sweep();
        if (dispatched > 0) {
            log.info("Cancellation dispatch sweep: {} subscription(s) sent to Razorpay for cycle-end cancellation.",
                    dispatched);
        }
    }

    public int sweep() {
        LocalDate cutoff = LocalDate.now().plusDays(bufferDays);
        List<Subscription> candidates = subscriptionRepository.findSubscriptionsAwaitingCancellationDispatch(cutoff);

        int dispatched = 0;
        for (Subscription candidate : candidates) {
            try {
                if (dispatchOne(candidate.getId())) {
                    dispatched++;
                }
            } catch (RuntimeException e) {
                // One subscription's Razorpay call failing (network blip, rate limit) must not
                // stop the rest of this batch from being dispatched -- matches
                // StatementStorageSweepService.sweep()'s own per-row try/catch for the same
                // reason. Left with cancellationDispatchedAt still null, so the next hourly run
                // retries it automatically -- no separate retry bookkeeping needed.
                log.error("Cancellation dispatch failed for subscription {}, will retry next sweep.",
                        candidate.getId(), e);
            }
        }
        return dispatched;
    }

    /** @return true if this row was actually dispatched; false if a concurrent change (most
     *  likely {@code BillingCheckoutService.resume()}) made it ineligible between {@link #sweep}'s
     *  candidate query and this transaction's own fresh read. */
    private boolean dispatchOne(UUID subscriptionId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (subscription == null || subscription.isAutoRenew()
                    || subscription.getCancellationDispatchedAt() != null
                    || subscription.getRazorpaySubscriptionId() == null) {
                return false;
            }

            gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
            subscription.setCancellationDispatchedAt(Instant.now());
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.CANCELLATION_DISPATCHED);
            event.setMetadata(Map.of("razorpaySubscriptionId", subscription.getRazorpaySubscriptionId()));
            subscriptionEventRepository.save(event);
            return true;
        }));
    }
}
