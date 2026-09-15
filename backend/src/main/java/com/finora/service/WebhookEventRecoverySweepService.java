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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recovery sweep for the webhook idempotency ledger (design spec §4.7), covering two ways a row can
 * get stuck there:
 *
 * <ul>
 *   <li><b>{@code status IS NULL}</b> -- crash recovery. {@code WebhookEventService.claim} commits
 *       in its own transaction, then {@code dispatch()} runs in a separate one -- if the process
 *       crashes or is redeployed anywhere between those two commits, the row is left {@code NULL}
 *       forever ({@code insertIfAbsent} never sets a status, only {@code markProcessed}/{@code
 *       markFailed} do, and neither ran). The outcome here is genuinely unknown, not just "never
 *       ran" -- see {@link #recoverOne} for how re-dispatching handles that ambiguity.
 *   <li><b>{@code status = 'FAILED'}</b> -- retry recovery. A genuine handler exception (not a
 *       crash) already committed a {@code FAILED} row via {@code markFailed}. Unlike the {@code
 *       NULL} case, the outcome here is fully known: {@code dispatch()} is {@code @Transactional},
 *       so the handler ran and every DB write from that attempt was rolled back.
 * </ul>
 *
 * <p>Both share the same underlying bug if left unrecovered: Razorpay/RevenueCat/Setu's own retry
 * of the same event id finds {@code claim()} returning {@code false} (the row already exists,
 * regardless of its status) and is silently swallowed as a duplicate by the controller, which
 * returns {@code 200 OK} -- the sender sees success and stops retrying, so nothing else will ever
 * cause the event to be reprocessed. This sweep is that "something else," for both cases.
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
 *
 *       <p><b>{@code FAILED}-origin re-dispatch only:</b> this handler's one non-DB, non-rollback-safe
 *       side effect -- {@code gateway.cancelSubscription(oldRazorpaySubscriptionId, false)}, cancelling
 *       a superseded subscription on an upgrade -- is a real external call Postgres rollback cannot
 *       undo. If it succeeds and something later in the same handler then throws (leaving the row
 *       {@code FAILED}, DB writes rolled back, but the real Razorpay cancellation already happened),
 *       reclaiming and re-dispatching calls it again. Already caught and logged rather than propagated
 *       at its own call site, and Razorpay's cancel is a no-op on an already-cancelled subscription --
 *       explicitly accepted as the same class of low-severity residual risk as the {@code
 *       handlePending} gap above, not silently guessed away.
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
        List<WebhookEvent> candidates = new ArrayList<>(
                webhookEventRepository.findStuckUnprocessed(cutoff, batchSize));
        candidates.addAll(webhookEventRepository.findFailed(cutoff, batchSize));

        int recovered = 0;
        for (WebhookEvent event : candidates) {
            try {
                if (recoverOne(event)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                // One bad event must not stop the rest of the batch -- matches every other sweep's
                // own per-row try/catch in this codebase (e.g.
                // SubscriptionCancellationDispatchSweepService.sweep()). markFailed leaves a visible
                // FAILED row for manual follow-up instead of leaving it NULL to be retried forever.
                //
                // Its own return value matters here: if it's false, the ORIGINAL still-in-flight
                // request (this event was never actually crashed, just slow past the grace period)
                // already finished and set a real terminal status first -- markStatusIfUnset's own
                // doc explains why this must be a no-op rather than clobbering that with FAILED.
                // This dispatch() call may still have thrown for real (e.g. it lost the resulting
                // optimistic-lock race against the winning transaction) -- that's expected, not a
                // genuine failure, so it's logged at INFO, not ERROR.
                if (webhookEventService.markFailed(event.getEventId())) {
                    log.error("Webhook recovery sweep: event {} ({}/{}) failed on reprocessing, marked FAILED " +
                                    "-- needs manual follow-up.",
                            LogSanitizer.sanitize(event.getEventId()), LogSanitizer.sanitize(event.getProvider()),
                            LogSanitizer.sanitize(event.getEventType()), e);
                } else {
                    log.info("Webhook recovery sweep: event {} ({}/{}) errored on reprocessing, but the " +
                                    "original request had already resolved it -- ignoring.",
                            LogSanitizer.sanitize(event.getEventId()), LogSanitizer.sanitize(event.getProvider()),
                            LogSanitizer.sanitize(event.getEventType()));
                }
            }
        }
        return recovered;
    }

    @SuppressWarnings("unchecked")
    private boolean recoverOne(WebhookEvent event) {
        if (WebhookEvent.STATUS_FAILED.equals(event.getStatus())) {
            // FAILED-origin candidate: reclaimFailed() IS the fresh re-check + claim, combined and
            // atomic (see its own doc) -- a FAILED row has no "still legitimately in flight" case to
            // re-read for the way a NULL row does below, since dispatch() being @Transactional means
            // the handler that set FAILED already fully ran and rolled back.
            if (!webhookEventService.reclaimFailed(event.getEventId())) {
                return false;
            }
        } else if (webhookEventRepository.findById(event.getEventId()).map(WebhookEvent::getStatus).orElse(null) != null) {
            // Fresh re-check, same discipline as SubscriptionCancellationDispatchSweepService.dispatchOne
            // re-reading eligibility inside its own transaction rather than trusting sweep()'s
            // candidate-query snapshot: a batch of up to batchSize rows can take a while to loop
            // through, and a "stuck" row can stop being stuck mid-batch if its original request was
            // merely slow, not actually crashed, and has since finished on its own. Skipping here avoids
            // needlessly re-running a handler's side effects (a second invoice-email attempt, a
            // duplicate SubscriptionEvent audit row) in the common case -- markStatusIfUnset below is
            // still the actual correctness guarantee even without this, but this keeps ordinary
            // operation quiet.
            return false;
        }

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
                // Marked FAILED, not just logged and left NULL: every current provider is one of
                // the three cases above (set by the three controllers, never anything else), so
                // this is unreachable today -- but leaving it NULL would mean every future sweep
                // run re-picks up this same row and re-logs this forever, rather than surfacing
                // once for manual follow-up the way a genuine handler exception already does.
                webhookEventService.markFailed(event.getEventId());
                log.error("Webhook recovery sweep: unrecognized provider '{}' for event {}, cannot reprocess " +
                                "-- marked FAILED, needs manual follow-up.",
                        LogSanitizer.sanitize(event.getProvider()), LogSanitizer.sanitize(event.getEventId()));
                return false;
            }
        }
        // markStatusIfUnset's own conditional UPDATE is the actual correctness guarantee here (see
        // its doc): if the original request finished and set a terminal status in the tiny window
        // between the re-check above and this line, this call is a harmless no-op and returns
        // false -- so `recovered` in sweep() only ever counts events this sweep itself resolved.
        return webhookEventService.markProcessed(event.getEventId());
    }
}
