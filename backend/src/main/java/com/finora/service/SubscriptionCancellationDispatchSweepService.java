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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Subscription billing -- auto-renew resume (design spec at docs/superpowers/specs/
 * 2026-09-08-billing-auto-renew-resume-design.md). {@code BillingCheckoutService.cancel()} no
 * longer calls Razorpay synchronously -- this sweep is what actually sends the real
 * {@code cancel_at_cycle_end=true} call, once a locally-cancelled subscription is within
 * {@code bufferDays} of its {@code renewalDate}. Same shape as
 * {@code SubscriptionReconciliationSweepService}: {@code fixedDelay}, gated by a flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 *
 * <p>Residual risk, accepted explicitly in the design: if this sweep is down for an outage
 * spanning the entire buffer window, a subscription whose user wants it cancelled could still
 * auto-renew and charge on Razorpay's side, since nothing was ever sent to stop it.
 */
@Service
public class SubscriptionCancellationDispatchSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCancellationDispatchSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final RazorpaySubscriptionGateway gateway;

    @Value("${app.subscription-cancellation-dispatch.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.subscription-cancellation-dispatch.sweep.buffer-days:3}")
    private int bufferDays;

    public SubscriptionCancellationDispatchSweepService(SubscriptionRepository subscriptionRepository,
                                                          SubscriptionEventRepository subscriptionEventRepository,
                                                          RazorpaySubscriptionGateway gateway) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.gateway = gateway;
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

    @Transactional
    public int sweep() {
        LocalDate cutoff = LocalDate.now().plusDays(bufferDays);
        List<Subscription> candidates = subscriptionRepository.findSubscriptionsAwaitingCancellationDispatch(cutoff);

        for (Subscription subscription : candidates) {
            gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
            subscription.setCancellationDispatchedAt(Instant.now());
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.CANCELLATION_DISPATCHED);
            event.setMetadata(Map.of("razorpaySubscriptionId", subscription.getRazorpaySubscriptionId()));
            subscriptionEventRepository.save(event);
        }
        return candidates.size();
    }
}
