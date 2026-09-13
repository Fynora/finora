package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
            if (fetchService.syncSinceLastAttempt(link)) {
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED",
                        "AccountAggregatorLink", link.getId());
                forced++;
            }
        }
        return forced;
    }
}
