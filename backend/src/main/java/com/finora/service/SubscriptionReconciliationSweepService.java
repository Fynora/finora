package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing V1 (design spec §6.3). Safety net, not the primary mechanism — a cancelled
 * subscription is normally downgraded to Free by {@code RazorpayWebhookDispatcher.handleCancelled}'s
 * webhook path reaching {@code current_period_end} naturally (no further {@code subscription.charged}
 * arrives). This sweep exists because "we stopped hearing an event" is not itself a reliable signal:
 * Razorpay disables a webhook endpoint after 24h of failed deliveries, so a missed webhook could
 * otherwise leave paid access active indefinitely with nothing to notice. Same shape as
 * {@code AccountPurgeSweepService}/{@code NetWorthSnapshotSweepService}: {@code fixedDelay}, gated by
 * a flag {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 *
 * <p><b>Per-row transactions, not one transaction around the whole loop.</b> Same fix as
 * {@code SubscriptionCancellationDispatchSweepService} -- see that class's own doc comment for the
 * general reasoning. Here the trigger is optimistic locking rather than HTTP latency: {@code
 * Subscription} extends {@code BaseEntity} and carries {@code @Version}, and a genuinely concurrent
 * webhook (e.g. {@code RazorpayWebhookDispatcher.handleResumed}/{@code handleCharged}) can save the
 * same row this sweep is mid-loop on. With the whole loop in one {@code @Transactional} method, that
 * row's {@code OptimisticLockingFailureException} was uncaught and rolled back the entire batch --
 * every other legitimately-expired subscription in the same pass got silently reverted too.
 * {@link TransactionTemplate} gives each row its own short transaction so one row's lock conflict
 * only affects that row; {@code dispatchOne}'s per-row try/catch is mirrored here for the same
 * reason -- one bad row must not stop the rest of the sweep.
 */
@Service
public class SubscriptionReconciliationSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReconciliationSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.subscription-reconciliation.sweep.enabled:true}")
    private boolean sweepEnabled;

    public SubscriptionReconciliationSweepService(SubscriptionRepository subscriptionRepository,
                                                   SubscriptionEventRepository subscriptionEventRepository,
                                                   PlanRepository planRepository,
                                                   PlatformTransactionManager transactionManager) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.subscription-reconciliation.sweep.interval-ms:3600000}",
            initialDelayString = "${app.subscription-reconciliation.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int downgraded = sweep();
        if (downgraded > 0) {
            log.info("Subscription reconciliation sweep: {} subscription(s) downgraded to Free.", downgraded);
        }
    }

    public int sweep() {
        LocalDate cutoff = LocalDate.now();
        List<Subscription> overdue = subscriptionRepository.findCancelledSubscriptionsPastPeriodEnd(cutoff);

        int downgraded = 0;
        for (Subscription subscription : overdue) {
            try {
                if (downgradeOne(subscription.getId(), cutoff)) {
                    downgraded++;
                }
            } catch (OptimisticLockingFailureException e) {
                // A concurrent webhook (handleResumed/handleCharged) saved this same row mid-sweep.
                // Isolated to its own transaction, so this only skips this one row -- left for the
                // next scheduled run to re-evaluate, rather than rolling back the whole batch.
                log.warn("Reconciliation downgrade skipped for subscription {} due to concurrent update, "
                        + "will retry next sweep.", subscription.getId(), e);
            }
        }
        return downgraded;
    }

    /** @return true if this row was actually downgraded; false if a concurrent change made it
     *  no longer eligible between {@link #sweep}'s candidate query and this transaction's own
     *  fresh read. */
    private boolean downgradeOne(UUID subscriptionId, LocalDate cutoff) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (subscription == null || subscription.isAutoRenew()
                    || !Subscription.STATUS_CANCELLED.equals(subscription.getStatus())
                    || subscription.getRenewalDate() == null
                    || !subscription.getRenewalDate().isBefore(cutoff)) {
                return false;
            }

            Plan free = planRepository.findByCode("FREE")
                    .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));

            subscription.setPlanId(free.getId());
            subscription.setBillingCycle(null);
            subscription.setRazorpaySubscriptionId(null);
            subscription.setPaymentProvider(null);
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            subscription.setAutoRenew(true);
            subscription.setCancellationDispatchedAt(null);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.PLAN_CHANGED);
            event.setMetadata(Map.of("reason", "CANCELLATION_PERIOD_ENDED"));
            subscriptionEventRepository.save(event);
            return true;
        }));
    }
}
