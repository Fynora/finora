package com.finora.integrations.setu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The single place "is this Account Aggregator link stale" is computed -- see this plan's own
 * scope doc for why a second, independently-drifting copy of this threshold is exactly the bug
 * class this plan exists to avoid repeating. Three callers ask this bean the same question:
 * {@link com.finora.imports.AccountAggregatorGuard} (backend enforcement, live per request),
 * {@code AccountService}'s {@code AccountDto} assembly (the frontend-facing {@code aaSyncStale}
 * signal), and {@link AccountAggregatorOutageSweepService} (observability only).
 *
 * <p>Staleness measures absence of successful sync EVENTS, not absence of fresh transaction DATA
 * -- a sync that succeeds and finds zero new transactions is healthy. See the design spec's
 * "Outage escape hatch" section and this plan's scope doc for the full reasoning; not re-derived
 * here.
 */
@Component
public class AccountAggregatorLinkStalenessService {

    private final Duration threshold;

    public AccountAggregatorLinkStalenessService(
            @Value("${app.integrations.setu.expected-cadence-hours:24}") long expectedCadenceHours) {
        // 3x expected cadence -- reuses the design spec's own stated multiplier and its "one
        // number, not three independently invented ones" reasoning (shared by the hatch and the
        // alerting threshold in AccountAggregatorOutageSweepService).
        this.threshold = Duration.ofHours(expectedCadenceHours * 3);
    }

    /**
     * @param link an ACTIVE link -- callers are responsible for the status check; this method only
     *             answers the staleness question, not "should the hatch apply at all."
     */
    public boolean isStale(AccountAggregatorLink link) {
        Instant reference = link.getLastSyncedAt() != null ? link.getLastSyncedAt() : link.getUpdatedAt();
        return Duration.between(reference, Instant.now()).compareTo(threshold) > 0;
    }
}
