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

        try {
            SetuFiDataFetchResult fetched = gateway.fetchTransactions(link.getConsentHandleId(), from, to);
            List<Transaction> newTransactions =
                    mapper.mapNew(link.getUserId(), link.getAccountId(), fetched.transactions());

            if (!newTransactions.isEmpty()) {
                transactionRepository.saveAll(newTransactions);
                reconciliationService.reconcileForImport(link.getUserId(), from, to);
            }

            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
            links.save(link);
        } catch (RuntimeException e) {
            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
            links.save(link);
            auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_SYNC_FAILED",
                    "AccountAggregatorLink", link.getId());
            log.error("AA sync failed for link {}.", link.getId(), e);
        }
    }
}
