package com.finora.notification.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a minute: notices campaigns whose slot has come and starts their runs.
 *
 * <p>All it does is find due ids and hand each to {@link PushCampaignService#claimDue}, whose
 * compare-and-set makes exactly one server act on a slot. The run itself then executes on the
 * campaign thread, never here -- this is the shared scheduler thread and must not be held up.
 * Gated by {@code app.push-campaigns.scheduler.enabled} (off under test), and by the
 * {@code PUSH_CAMPAIGNS_ENABLED} feature flag: while that master switch is off nothing is claimed,
 * so a slot that came due waits and, when the switch is back on, the missed-run rule decides whether
 * it is still worth sending.
 *
 * <p>{@link #claimDueRuns} and {@link #runDue} are public and ignore the enabled property, for
 * tests (the repository's convention for every poller).
 */
@Component
public class PushCampaignScheduler {

    private static final Logger log = LoggerFactory.getLogger(PushCampaignScheduler.class);
    private static final int DUE_BATCH = 20;

    private final PushCampaignRepository campaigns;
    private final PushCampaignService service;
    private final PushCampaignRunner runner;
    private final IstClock clock;

    @Value("${app.push-campaigns.scheduler.enabled:true}")
    private boolean enabled;

    private volatile LocalDate lastCapPruneDay;

    public PushCampaignScheduler(PushCampaignRepository campaigns, PushCampaignService service,
            PushCampaignRunner runner, IstClock clock) {
        this.campaigns = campaigns;
        this.service = service;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push-campaigns.scheduler.poll-interval-ms:60000}",
            initialDelayString = "${app.push-campaigns.scheduler.initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        runDue(clock.now());
    }

    /** Claims everything due at {@code now} and starts each run on the campaign thread. */
    public List<UUID> runDue(Instant now) {
        List<UUID> runIds = claimDueRuns(now);
        runIds.forEach(runner::executeAsync);
        return runIds;
    }

    /** Housekeeping, then claims everything due at {@code now}; returns the runs to execute. */
    public List<UUID> claimDueRuns(Instant now) {
        housekeeping(now);
        List<UUID> runIds = new ArrayList<>();
        if (!service.isSwitchedOn()) {
            return runIds;
        }
        for (UUID id : campaigns.findDueIds(now, PageRequest.of(0, DUE_BATCH))) {
            try {
                service.claimDue(id, now).ifPresent(runIds::add);
            } catch (RuntimeException e) {
                // One bad campaign must not stop the others from being looked at.
                log.error("Could not start the due push campaign {}", id, e);
            }
        }
        return runIds;
    }

    private void housekeeping(Instant now) {
        try {
            LocalDate today = IstClock.dateOf(now);
            boolean pruneCap = !today.equals(lastCapPruneDay);
            service.housekeeping(now, pruneCap);
            if (pruneCap) {
                lastCapPruneDay = today;
            }
        } catch (RuntimeException e) {
            log.error("Push campaign housekeeping failed", e);
        }
    }
}
