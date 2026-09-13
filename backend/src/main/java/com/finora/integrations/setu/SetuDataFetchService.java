package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import com.finora.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class SetuDataFetchService {

    private static final Logger log = LoggerFactory.getLogger(SetuDataFetchService.class);

    private final SetuDataFetchGateway gateway;
    private final AccountAggregatorTransactionDiffService diffService;
    private final TransactionRepository transactionRepository;
    private final AccountAggregatorLinkRepository links;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final ReconciliationService reconciliationService;
    private final Clock clock;

    /** Plan 6, Track B. PLACEHOLDER -- not evidence-based. The scope doc is explicit that a real
     *  number needs Setu sandbox measurement of how long after first appearance corrections
     *  actually land, which this environment cannot do; 14 is a conservative guess, not a validated
     *  default. Constructor-injected like {@code clock} below, not a field-level @Value: a
     *  field-level annotation is never processed by the plain `new SetuDataFetchService(...)`
     *  construction every unit test in this class uses (no Spring context), so it would silently
     *  sit at Java's int default (0) in every one of those tests regardless of what
     *  application.yml says. */
    private final int slidingWindowDays;

    @Autowired
    public SetuDataFetchService(SetuDataFetchGateway gateway, AccountAggregatorTransactionDiffService diffService,
                                 TransactionRepository transactionRepository, AccountAggregatorLinkRepository links,
                                 EntitlementService entitlementService, AuditService auditService,
                                 ReconciliationService reconciliationService,
                                 @Value("${app.integrations.setu.sliding-window-days:14}") int slidingWindowDays) {
        this(gateway, diffService, transactionRepository, links, entitlementService, auditService,
                reconciliationService, slidingWindowDays, Clock.systemUTC());
    }

    /** Package-private: only this package's tests need to fix "now", to pin the exact UTC-day
     *  boundary syncSinceLastAttempt's range math straddles, rather than depend on when the test
     *  happens to run relative to that boundary. */
    SetuDataFetchService(SetuDataFetchGateway gateway, AccountAggregatorTransactionDiffService diffService,
                          TransactionRepository transactionRepository, AccountAggregatorLinkRepository links,
                          EntitlementService entitlementService, AuditService auditService,
                          ReconciliationService reconciliationService, int slidingWindowDays, Clock clock) {
        this.gateway = gateway;
        this.diffService = diffService;
        this.transactionRepository = transactionRepository;
        this.links = links;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
        this.slidingWindowDays = slidingWindowDays;
        this.clock = clock;
    }

    /**
     * Fetches [from, to] for one ACTIVE link, maps and persists whatever's new, and re-reconciles
     * that range against any pre-existing manually-imported rows for the same account. Called by
     * the data.ready webhook and by the initial backfill.
     *
     * <p>Entitlement is re-checked here, immediately before the costly (billable) gateway call --
     * same discipline as Plan 1's resolveAndAttach re-check, for the same reason: a downgrade
     * between ACTIVE and this tick must not pull data for a lapsed user. This method does not pause
     * the link itself on a lapsed entitlement -- that is the dispatcher/sweep's job (Plan 1); this
     * service only declines to do the costly work.
     *
     * <p><b>Known, accepted gap (flagged, not fixed): a narrow concurrency race.</b> Two genuinely
     * concurrent calls for the same link and an overlapping range could both see "not yet seen"
     * from {@link AccountAggregatorTransactionDiffService} before either has persisted, and both then
     * insert the same transactions -- real duplicates, since neither {@code external_txn_id} nor
     * {@code transaction_fingerprint} carries a database uniqueness constraint (see V198's own
     * migration comment on why: a hard unique constraint would reject a genuinely-different
     * transaction that happens to collide on fingerprint, contradicting the design's "land both,
     * flagged for review" policy). Narrow in practice today -- the backfill can only fire once per
     * link (gated by the same re-entrancy status checks Plan 1 relies on) -- but not closed. See
     * docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md's "Addendum" for the
     * full reasoning on why this wasn't fixed speculatively here.
     */
    /** Computes the "since last attempt" range, widened to never be narrower than the sliding
     *  window (Plan 6, Track B -- see slidingWindowDays' own doc comment), and calls sync() if the
     *  range is non-empty. "Attempt," not "sync," because sync() itself sets lastSyncedAt on both
     *  success and failure (see that method's own doc comment): this is genuinely "since we last
     *  tried," not "since it last worked." Shared by AccountAggregatorWebhookDispatcher's data.ready
     *  case and AccountAggregatorReconciliationSweepService's force-fetch path, so the two can never
     *  compute this range differently.
     *
     *  @return whether a fetch was actually attempted. Under normal (non-negative) window
     *          configuration this is effectively always true -- see the window-floor comment below
     *          for why the old "already synced through today" skip no longer applies once a
     *          sliding-window re-fetch runs on every attempt by design. False remains possible only
     *          as a defensive fallback (e.g. a misconfigured window), the caller's own signal for
     *          whether to log a skip either way. */
    public boolean syncSinceLastAttempt(AccountAggregatorLink link) {
        // Both endpoints must resolve "today" in the same zone. `to` used to be the JVM's default
        // zone while `from` was always UTC -- during the window where the JVM's local zone has
        // already rolled to a new calendar day but UTC hasn't (e.g. IST, ~00:00-05:30), that skew
        // could invert the range by exactly one day. `clock` is UTC (not the user's zone: this
        // range is fed straight to the Setu gateway's fetch, which is UTC-dated, not a
        // user-facing date the way DashboardService's "today" is) -- and `from` derives its zone
        // from the same `clock` rather than a separately hardcoded ZoneOffset.UTC, so the two
        // endpoints structurally cannot drift onto different zones again. Found and fixed
        // independently in two places -- #1448 (this file's own history) and Track A's own review
        // of this method while adding the sliding window below -- converged on the same root cause;
        // #1448's Clock-based approach is kept here since it's the more testable of the two.
        LocalDate to = LocalDate.now(clock);
        LocalDate incrementalFrom = link.getLastSyncedAt() != null
                ? link.getLastSyncedAt().atZone(clock.getZone()).toLocalDate().plusDays(1)
                : to.minusMonths(3);
        // Plan 6, Track B: never fetch less than the sliding window, even when incrementalFrom
        // would otherwise be narrower (a recent sync) -- a correction landing inside a window
        // already fetched-and-moved-past must still be visible on the next attempt. Known, accepted
        // cost tradeoff: since windowFloor is always <= to for any non-negative window, this makes
        // the `from.isAfter(to)` guard below effectively unreachable under normal configuration --
        // a data.ready webhook now re-fetches the trailing window on every call, not just the
        // incremental delta. Correctness-safe (idempotent re-evaluation, see sync()'s own doc
        // comment) but not yet cost-measured; see the implementation plan's Task 4 for the full
        // reasoning. The guard itself stays as defensive protection against a misconfigured
        // negative window (already floored to 0 below).
        LocalDate windowFloor = to.minusDays(Math.max(0, slidingWindowDays));
        LocalDate from = incrementalFrom.isBefore(windowFloor) ? incrementalFrom : windowFloor;
        if (from.isAfter(to)) {
            return false;
        }
        sync(link, from, to);
        return true;
    }

    public void sync(AccountAggregatorLink link, LocalDate from, LocalDate to) {
        if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            log.info("Skipping AA sync for link {}: user no longer entitled.", link.getId());
            return;
        }
        if (!gateway.isConfigured()) {
            log.info("Skipping AA sync for link {}: gateway not configured.", link.getId());
            return;
        }

        List<Transaction> newTransactions;
        try {
            SetuFiDataFetchResult fetched = gateway.fetchTransactions(link.getConsentHandleId(), from, to);
            var diffResult = diffService.diff(link.getUserId(), link.getAccountId(), from, to, fetched.transactions());
            newTransactions = diffResult.newTransactions();
            if (!newTransactions.isEmpty()) {
                transactionRepository.saveAll(newTransactions);
            }
        } catch (RuntimeException e) {
            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
            links.save(link);
            auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_SYNC_FAILED",
                    "AccountAggregatorLink", link.getId());
            log.error("AA sync failed for link {}.", link.getId(), e);
            return;
        }

        // lastSyncStatus reflects whether the fetch itself succeeded -- see the field's own doc
        // comment ("outcome of the most recent fetch attempt"). Saved as SUCCESS here, BEFORE
        // reconciliation runs, and deliberately not reverted if reconciliation below throws: the
        // transactions are already safely persisted at this point, and mischaracterizing that as a
        // failed sync (found during this pass's own post-implementation review -- the original
        // version wrapped reconcileForImport inside the same try/catch, so a reconciliation crash
        // AFTER a successful save still marked the whole sync FAILED) would be worse than the
        // narrower truth: the fetch worked, reconciliation separately did not.
        link.setLastSyncedAt(Instant.now());
        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
        links.save(link);

        if (!newTransactions.isEmpty()) {
            try {
                reconciliationService.reconcileForImport(link.getUserId(), from, to);
            } catch (RuntimeException e) {
                // Not re-thrown, and does not touch lastSyncStatus above -- the data is correctly
                // in the ledger regardless. ReconciliationService's passes are idempotent full
                // re-evaluations of current DB state, not incremental deltas, so any later write
                // (another sync, a manual edit, a fresh import) re-evaluates this account from
                // scratch anyway; this is a logged, recoverable gap, not a silent one.
                log.error("Reconciliation failed after AA sync for link {} persisted {} new transaction(s).",
                        link.getId(), newTransactions.size(), e);
            }
        }
    }
}
