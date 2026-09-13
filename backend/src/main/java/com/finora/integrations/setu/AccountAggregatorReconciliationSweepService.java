package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * The force-fetch safety net the design spec's own "Architecture" section named back in Plan 2
 * but was never built (confirmed via grep -rn "@Scheduled" across the whole integrations/setu/
 * package during Plan 6's scoping -- three sweeps exist, none of them fetch data). Today the
 * data.ready webhook is the ONLY thing that triggers an ongoing sync; if it's lost for a link, that
 * link never syncs again. This closes that gap directly, reusing AccountAggregatorLinkStalenessService's
 * existing 3x-expected-cadence threshold (the same one the outage escape hatch and
 * AccountAggregatorOutageSweepService's gauge already use) rather than inventing a second number.
 *
 * <p>Deliberately a separate service from AccountAggregatorOutageSweepService, not a merge into it,
 * even though both read the same findByStatus(ACTIVE)-filtered-by-staleness set -- see this plan's
 * own "Why a new service" section. That class mutates nothing by design; this one does, and needs
 * its own independent enabled flag so force-fetching can be paused without losing observability, or
 * vice versa.
 *
 * <p>Mirrors every other sweep's scheduling shape in this package: fixedDelay, gated by a flag
 * application-test.yml turns off, tests call sweep() directly.
 */
@Service
public class AccountAggregatorReconciliationSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorReconciliationSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountAggregatorLinkStalenessService staleness;
    private final SetuDataFetchService fetchService;
    private final AuditService auditService;

    @Value("${app.integrations.setu.reconciliation-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorReconciliationSweepService(AccountAggregatorLinkRepository links,
                                                          AccountAggregatorLinkStalenessService staleness,
                                                          SetuDataFetchService fetchService,
                                                          AuditService auditService) {
        this.links = links;
        this.staleness = staleness;
        this.fetchService = fetchService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.reconciliation-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int forced = sweep();
        if (forced > 0) {
            log.info("Account Aggregator reconciliation sweep: force-fetched {} stale link(s).", forced);
        }
    }

    public int sweep() {
        int forced = 0;
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)) {
            if (!staleness.isStale(link)) continue;
            // Two bugs found during this plan's own post-implementation review, both fixed here:
            //
            // 1. SetuDataFetchService.sync's entitlementService.hasEntitlement(...) call sits
            // outside its own try/catch (that one only wraps the gateway.fetchTransactions/
            // mapper.mapNew pair), so an unexpected RuntimeException there -- a real infra hiccup,
            // not just a false return -- used to propagate uncaught through syncSinceLastAttempt()
            // and abort this whole loop, silently skipping every other stale link still left in the
            // same tick. Before this sweep existed, that failure was always scoped to exactly one
            // link per webhook delivery; this is the first place a single link's failure could take
            // out an unbounded batch of unrelated ones -- the worst possible failure mode for code
            // whose whole purpose is being the fallback when something else already went wrong.
            // Fixed with the try/catch below.
            //
            // 2. syncSinceLastAttempt's own contract is only "was the computed date range
            // non-empty" -- sync() itself still returns early, WITHOUT touching lastSyncedAt, when
            // the user isn't entitled or the gateway isn't configured. Comparing lastSyncedAt
            // before/after is how this sweep tells a genuine force-fetch attempt from that kind of
            // no-op, without duplicating either check sync() already owns. Matters here because a
            // link can be ACTIVE-but-actually-lost-entitlement for a real, narrow window --
            // AccountAggregatorLinkLifecycleSweepService's own sweep hasn't necessarily caught up to
            // flip it to PAUSED yet -- and crediting/auditing those as real force-fetches every tick
            // would be a misleading trail for exactly the kind of regulated data-sharing feature the
            // design spec says audit accuracy is "not optional" for.
            Instant lastSyncedBefore = link.getLastSyncedAt();
            try {
                boolean attempted = fetchService.syncSinceLastAttempt(link);
                if (attempted && !Objects.equals(link.getLastSyncedAt(), lastSyncedBefore)) {
                    auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED",
                            "AccountAggregatorLink", link.getId());
                    forced++;
                }
            } catch (RuntimeException e) {
                log.error("Account Aggregator reconciliation sweep: force-fetch failed for link {}.",
                        link.getId(), e);
            }
        }
        return forced;
    }
}
