package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import com.finora.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class SetuDataFetchService {

    private static final Logger log = LoggerFactory.getLogger(SetuDataFetchService.class);

    private final SetuDataFetchGateway gateway;
    private final AccountAggregatorTransactionMapper mapper;
    private final TransactionRepository transactionRepository;
    private final AccountAggregatorLinkRepository links;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final ReconciliationService reconciliationService;

    public SetuDataFetchService(SetuDataFetchGateway gateway, AccountAggregatorTransactionMapper mapper,
                                 TransactionRepository transactionRepository, AccountAggregatorLinkRepository links,
                                 EntitlementService entitlementService, AuditService auditService,
                                 ReconciliationService reconciliationService) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.transactionRepository = transactionRepository;
        this.links = links;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
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
     * from {@link AccountAggregatorTransactionMapper} before either has persisted, and both then
     * insert the same transactions -- real duplicates, since neither {@code external_txn_id} nor
     * {@code transaction_fingerprint} carries a database uniqueness constraint (see V198's own
     * migration comment on why: a hard unique constraint would reject a genuinely-different
     * transaction that happens to collide on fingerprint, contradicting the design's "land both,
     * flagged for review" policy). Narrow in practice today -- the backfill can only fire once per
     * link (gated by the same re-entrancy status checks Plan 1 relies on) -- but not closed. See
     * docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md's "Addendum" for the
     * full reasoning on why this wasn't fixed speculatively here.
     */
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
            newTransactions = mapper.mapNew(link.getUserId(), link.getAccountId(), fetched.transactions());
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
