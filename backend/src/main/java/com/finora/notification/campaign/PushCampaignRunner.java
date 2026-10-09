package com.finora.notification.campaign;

import com.finora.notification.worker.NotificationDispatcher;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Executes one campaign run: walks the audience in pages, queues each page, then nudges the
 * dispatcher once and keeps draining for a bounded time.
 *
 * <p>Works from the run row's own snapshot (title, message, audience), not from the live campaign,
 * so an edit made while a run is in progress cannot change the words half way through it.
 *
 * <p>{@link #executeAsync} runs on its own single-thread pool ({@code pushCampaignExecutor}), never
 * on the shared scheduler thread: a large run must not hold up the notification poller or any other
 * {@code @Scheduled} job. If the server dies mid-run the run row stays RUNNING until the scheduler's
 * sweep marks it FAILED; pressing send now again finishes the job, because every person's key and
 * daily-cap row already exist for those already queued.
 *
 * <h2>Delivery speed</h2>
 *
 * <p>The dispatcher claims 50 rows per pass and sends them one at a time, and its poller runs every
 * 30 seconds, so on its own it clears about 100 people a minute. After queuing, this keeps calling
 * {@link NotificationDispatcher#drainOnce()} until nothing is left to claim or
 * {@code app.push-campaigns.drain-max-seconds} (default 30) has passed; whatever remains is picked
 * up by the poller. Real FCM latency has not been measured, so the budget is configuration, not a
 * constant.
 */
@Service
public class PushCampaignRunner {

    private static final Logger log = LoggerFactory.getLogger(PushCampaignRunner.class);

    private final PushCampaignRunRepository runs;
    private final CampaignEnqueuer enqueuer;
    private final NotificationDispatcher dispatcher;
    private final IstClock clock;
    private final CampaignCancellation cancellation;
    private final Map<AudienceType, AudienceResolver> resolvers = new EnumMap<>(AudienceType.class);

    @Value("${app.push-campaigns.page-size:200}")
    private int pageSize;

    @Value("${app.push-campaigns.max-audience:100}")
    private long maxAudience;

    @Value("${app.push-campaigns.drain-max-seconds:30}")
    private int drainMaxSeconds;

    public PushCampaignRunner(PushCampaignRunRepository runs, CampaignEnqueuer enqueuer,
            NotificationDispatcher dispatcher, IstClock clock, CampaignCancellation cancellation,
            List<AudienceResolver> resolverList) {
        this.runs = runs;
        this.enqueuer = enqueuer;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.cancellation = cancellation;
        for (AudienceResolver resolver : resolverList) {
            this.resolvers.put(resolver.type(), resolver);
        }
    }

    /** The resolver for {@code type}; every {@link AudienceType} has exactly one. */
    public AudienceResolver resolverFor(AudienceType type) {
        AudienceResolver resolver = resolvers.get(type);
        if (resolver == null) {
            throw new IllegalStateException("No audience resolver registered for " + type);
        }
        return resolver;
    }

    /** The current staged-rollout ceiling on one run's audience (see {@code max-audience}). */
    public long maxAudience() {
        return maxAudience;
    }

    @Async("pushCampaignExecutor")
    public void executeAsync(UUID runId) {
        execute(runId);
    }

    /** Synchronous run, for the async path above and for tests. Never throws: a failure is
     *  recorded on the run itself, because nobody is waiting on this to hand an exception to. */
    public void execute(UUID runId) {
        PushCampaignRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.RUNNING) {
            log.warn("Push campaign run {} is not runnable (missing or not RUNNING)", runId);
            return;
        }
        boolean queuedEverything;
        try {
            queuedEverything = queueAudience(run);
        } catch (RuntimeException e) {
            log.error("Push campaign run {} failed", runId, e);
            try {
                runs.markFailed(runId, clock.now(), "Unexpected error (" + e.getClass().getSimpleName()
                        + "); see the server logs. Some people may already have been queued: see the counts.");
            } catch (RuntimeException recordingFailed) {
                // The database is what failed. The run stays RUNNING and the scheduler's heartbeat
                // sweep marks it FAILED once it has been silent for 30 minutes.
                log.error("Could not record the failure of push campaign run {}", runId, recordingFailed);
            }
            return;
        }
        if (queuedEverything) {
            drainBriefly();
        }
    }

    /**
     * @return true if every page was queued and the run is DONE; false if it failed or was cancelled
     *     (nothing more to deliver, so the caller must not drain)
     */
    private boolean queueAudience(PushCampaignRun run) {
        UUID runId = run.getId();
        AudienceResolver resolver = resolverFor(run.getAudienceSnapshot());
        long audience = resolver.count();
        if (audience > maxAudience) {
            runs.updateProgress(runId, (int) Math.min(audience, Integer.MAX_VALUE), 0, 0, 0, clock.now());
            runs.markFailed(runId, clock.now(), "Audience of " + audience
                    + " is over the current rollout limit of " + maxAudience + "; nothing was sent.");
            return false;
        }
        int queued = 0;
        int skippedCap = 0;
        int skippedAlreadyQueued = 0;
        UUID after = AudienceSql.FIRST;
        while (true) {
            List<UUID> page = resolver.page(after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            CampaignEnqueuer.PageResult result = enqueuer.enqueuePage(run.getCampaignId(),
                    run.getTitleSnapshot(), run.getMessageSnapshot(), run.getRunDateIst(), page);
            queued += result.queued();
            skippedCap += result.skippedCap();
            skippedAlreadyQueued += result.skippedAlreadyQueued();
            if (runs.updateProgress(runId, (int) audience, queued, skippedCap, skippedAlreadyQueued, clock.now()) == 0) {
                return cancelled(run);
            }
            after = page.get(page.size() - 1);
        }
        if (runs.updateProgress(runId, (int) audience, queued, skippedCap, skippedAlreadyQueued, clock.now()) == 0
                || runs.markDone(runId, clock.now()) == 0) {
            return cancelled(run);
        }
        if (queued > 0) {
            dispatcher.nudge();
        }
        return true;
    }

    /**
     * The run stopped being RUNNING under us. Only a real admin cancel withdraws pushes: the page
     * that was being queued when the cancel ran committed after the cancel looked, so withdraw
     * anything of this campaign still queued and give those people their slots back (idempotent).
     * If instead the sweep judged the run interrupted (no heartbeat for 30 minutes), stop queuing
     * but withdraw nothing -- what is already queued was legitimately queued, and withdrawing it
     * would silently take a send away from people.
     */
    private boolean cancelled(PushCampaignRun run) {
        RunStatus now = runs.findById(run.getId()).map(PushCampaignRun::getStatus).orElse(null);
        if (now == RunStatus.CANCELLED) {
            CampaignCancellation.Result swept = cancellation.cancelPending(run.getCampaignId());
            log.info("Push campaign run {} was cancelled; withdrew {} straggling queued push(es)",
                    run.getId(), swept.cancelled());
        } else {
            log.warn("Push campaign run {} stopped queuing: it is no longer RUNNING (now {})",
                    run.getId(), now);
        }
        return false;
    }

    private void drainBriefly() {
        if (drainMaxSeconds <= 0) {
            return;
        }
        // Real elapsed time (not IstClock, which a test freezes): this is a wall-clock budget.
        long deadline = System.nanoTime() + Duration.ofSeconds(drainMaxSeconds).toNanos();
        try {
            while (System.nanoTime() < deadline && dispatcher.drainOnce() > 0) {
                // keep claiming until the queue is empty or the budget runs out
            }
        } catch (RuntimeException e) {
            // Delivery is the dispatcher's job and its poller is the backstop; a failed drain here
            // must not turn a successfully queued run into a failed one.
            log.error("Draining the notification queue after a push campaign run failed", e);
        }
    }
}
