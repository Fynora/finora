package com.finora.integrations.setu;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only. Detects and reports Account Aggregator outages -- it does not create the escape
 * hatch, which {@link com.finora.imports.AccountAggregatorGuard} evaluates live on every request
 * regardless of whether this scheduler is even running. This sweep exists so an outage is
 * observable even if no user happens to attempt a manual import during it: on every tick, it
 * counts ACTIVE links currently past the staleness threshold (published as a gauge) and logs one
 * WARN per stale link found.
 *
 * <p>"Incident alerting" (design spec) is realistically scoped to this gauge plus the WARN log
 * line, both of which a future alerting system can consume -- this codebase has no paging/alerting
 * pipeline yet (see the scope doc's own note on this). Mirrors
 * {@code SubscriptionReconciliationSweepService}'s scheduling shape ({@code fixedDelay}, gated by a
 * flag {@code application-test.yml} turns off, tests call {@link #sweep()} directly) -- but unlike
 * that service (and unlike {@link AccountAggregatorLinkSweepService}), mutates nothing.
 */
@Service
public class AccountAggregatorOutageSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorOutageSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountAggregatorLinkStalenessService staleness;
    private final AtomicInteger staleLinkCount = new AtomicInteger(0);

    @Value("${app.integrations.setu.outage-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorOutageSweepService(AccountAggregatorLinkRepository links,
                                                AccountAggregatorLinkStalenessService staleness,
                                                MeterRegistry registry) {
        this.links = links;
        this.staleness = staleness;
        Gauge.builder("finora.account_aggregator.stale_links", staleLinkCount, AtomicInteger::get)
                .description("ACTIVE Account Aggregator links currently past the staleness threshold")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.outage-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int found = sweep();
        if (found > 0) {
            log.info("Account Aggregator outage sweep: {} ACTIVE link(s) currently stale.", found);
        }
    }

    public int sweep() {
        List<AccountAggregatorLink> staleLinks = links.findByStatus(AccountAggregatorLinkStatus.ACTIVE).stream()
                .filter(staleness::isStale)
                .toList();
        staleLinkCount.set(staleLinks.size());
        for (AccountAggregatorLink link : staleLinks) {
            Instant reference = link.getLastSyncedAt() != null ? link.getLastSyncedAt() : link.getUpdatedAt();
            log.warn("AA link {} stale: last synced {} ago.", link.getId(),
                    Duration.between(reference, Instant.now()));
        }
        return staleLinks.size();
    }
}
