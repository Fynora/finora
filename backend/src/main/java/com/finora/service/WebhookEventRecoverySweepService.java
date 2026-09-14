package com.finora.service;

import com.finora.entity.WebhookEvent;
import com.finora.integrations.setu.AccountAggregatorWebhookDispatcher;
import com.finora.repository.WebhookEventRepository;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Crash-recovery sweep for the webhook idempotency ledger (design spec §4.7).
 * {@code WebhookEventService.claim} commits in its own transaction, then {@code dispatch()} runs in
 * a separate one -- if the process crashes or is redeployed anywhere between those two commits, the
 * {@code webhook_events} row is left with {@code status IS NULL} forever: {@code
 * WebhookEventRepository.insertIfAbsent} never sets a status, only {@code markProcessed}/{@code
 * markFailed} do, and neither ran. Worse than simply "never processed": Razorpay/RevenueCat/Setu's
 * own retry of the same event id now finds {@code claim()} returning {@code false} (the row already
 * exists) and is silently swallowed as a duplicate by the controller, which returns {@code 200 OK}
 * -- the sender sees success and stops retrying, so nothing else will ever cause this event to be
 * reprocessed. This sweep is that "something else."
 *
 * <p>Same shape as every other sweep in this codebase ({@code fixedDelay}, an {@code enabled} flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly) -- chosen over
 * making {@code claim()} and {@code dispatch()} one atomic transaction because {@code dispatch()}'s
 * transactional shape deliberately differs per provider ({@code
 * AccountAggregatorWebhookDispatcher.dispatch} has no {@code @Transactional} at all, since its
 * {@code data.ready} branch makes an outbound Setu network call the way {@code
 * SubscriptionCancellationDispatchSweepService}'s own doc already establishes this codebase avoids
 * holding a DB connection across) -- there is no single transactional boundary that would fit all
 * three without either forcing an external call inside a DB transaction or giving up the {@code
 * FAILED}-status audit trail a genuine handler exception still needs to survive.
 *
 * <p><b>Re-dispatching a row whose outcome is genuinely unknown, not just "never ran."</b> A crash
 * could equally have landed just after {@code dispatch()} itself committed, before the controller's
 * follow-up {@code markProcessed} call did -- this sweep cannot tell the two cases apart, so it must
 * re-dispatch as if the business effect may have already happened. Verified safe for every handler
 * currently reachable here:
 * <ul>
 *   <li>{@code RazorpayWebhookDispatcher.handleActivated} -- already guarded by the target
 *       {@code SubscriptionOrder}'s status (PENDING only), and {@code handleCharged} now has its own
 *       explicit {@code Payment}-existence guard added alongside this sweep -- see that method's
 *       doc. {@code handleHalted}/{@code handleCancelled}/{@code handlePaused}/{@code handleResumed}
 *       re-set the same subscription fields (a no-op on the state itself) and only risk a duplicate
 *       {@code SubscriptionEvent} audit row -- accepted, same category of residual risk this
 *       codebase already documents elsewhere (e.g. {@code SubscriptionCancellationDispatchSweepService}).
 *       {@code handlePending} is the one real known gap: its payload carries no provider transaction
 *       id to guard on (see that method's own doc for why), so a re-dispatch after this exact crash
 *       window can insert a second zero-amount PENDING {@code Payment} row. Explicitly accepted, not
 *       silently guessed away: low severity (no email, no referral trigger, zero amount) next to the
 *       alternative of losing the event entirely.
 *   <li>{@code RevenueCatWebhookDispatcher} -- every handler either re-sets the same fields in place
 *       or is guarded by {@code Subscription} lookup semantics (see e.g. {@code handleInitialPurchase}'s
 *       own ownership-source check); none unconditionally inserts a new row the way Razorpay's
 *       {@code handleCharged} used to.
 *   <li>{@code AccountAggregatorWebhookDispatcher} -- {@code consent.approved} is explicitly guarded
 *       against a redelivered webhook by {@code AccountAggregatorIdentityResolutionService
 *       .resolveAndAttach}'s own status check (see that method's doc, which names this exact
 *       scenario); {@code consent.rejected}/{@code consent.revoked} re-set the same link status
 *       (duplicate audit-log row only); {@code data.ready} is itself named and designed around an
 *       incremental watermark ({@code syncSinceLastAttempt}).
 * </ul>
 */
@Service
public class WebhookEventRecoverySweepService {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventRecoverySweepService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventService webhookEventService;
    private final RazorpayWebhookDispatcher razorpayDispatcher;
    private final RevenueCatWebhookDispatcher revenueCatDispatcher;
    private final AccountAggregatorWebhookDispatcher setuDispatcher;

    @Value("${app.webhook-event-recovery.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.webhook-event-recovery.sweep.grace-minutes:5}")
    private long graceMinutes;

    @Value("${app.webhook-event-recovery.sweep.batch-size:50}")
    private int batchSize;

    public WebhookEventRecoverySweepService(WebhookEventRepository webhookEventRepository,
                                             WebhookEventService webhookEventService,
                                             RazorpayWebhookDispatcher razorpayDispatcher,
                                             RevenueCatWebhookDispatcher revenueCatDispatcher,
                                             AccountAggregatorWebhookDispatcher setuDispatcher) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookEventService = webhookEventService;
        this.razorpayDispatcher = razorpayDispatcher;
        this.revenueCatDispatcher = revenueCatDispatcher;
        this.setuDispatcher = setuDispatcher;
    }

    @Scheduled(fixedDelayString = "${app.webhook-event-recovery.sweep.interval-ms:900000}",
            initialDelayString = "${app.webhook-event-recovery.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int recovered = sweep();
        if (recovered > 0) {
            log.warn("Webhook recovery sweep: {} stuck webhook event(s) reprocessed.", recovered);
        }
    }

    public int sweep() {
        Instant cutoff = Instant.now().minus(graceMinutes, ChronoUnit.MINUTES);
        List<WebhookEvent> stuck = webhookEventRepository.findStuckUnprocessed(cutoff, batchSize);

        int recovered = 0;
        for (WebhookEvent event : stuck) {
            try {
                if (recoverOne(event)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                // One bad event must not stop the rest of the batch -- matches every other sweep's
                // own per-row try/catch in this codebase (e.g.
                // SubscriptionCancellationDispatchSweepService.sweep()). markFailed leaves a visible
                // FAILED row for manual follow-up instead of leaving it NULL to be retried forever.
                webhookEventService.markFailed(event.getEventId());
                log.error("Webhook recovery sweep: event {} ({}/{}) failed on reprocessing, marked FAILED " +
                                "-- needs manual follow-up.",
                        LogSanitizer.sanitize(event.getEventId()), LogSanitizer.sanitize(event.getProvider()),
                        LogSanitizer.sanitize(event.getEventType()), e);
            }
        }
        return recovered;
    }

    @SuppressWarnings("unchecked")
    private boolean recoverOne(WebhookEvent event) {
        Map<String, Object> storedPayload = event.getPayload();
        switch (event.getProvider()) {
            case "RAZORPAY" -> {
                Object inner = storedPayload != null ? storedPayload.get("payload") : null;
                Map<String, Object> eventPayload = inner instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                razorpayDispatcher.dispatch(event.getEventType(), eventPayload);
            }
            case "REVENUECAT" -> revenueCatDispatcher.dispatch(event.getEventType(),
                    storedPayload != null ? storedPayload : Map.of());
            case "SETU" -> {
                Object consentHandleId = storedPayload != null ? storedPayload.get("consentHandleId") : null;
                setuDispatcher.dispatch(event.getEventType(), consentHandleId instanceof String s ? s : null);
            }
            default -> {
                log.error("Webhook recovery sweep: unrecognized provider '{}' for event {}, cannot reprocess " +
                                "-- needs manual follow-up.",
                        LogSanitizer.sanitize(event.getProvider()), LogSanitizer.sanitize(event.getEventId()));
                return false;
            }
        }
        webhookEventService.markProcessed(event.getEventId());
        return true;
    }
}
