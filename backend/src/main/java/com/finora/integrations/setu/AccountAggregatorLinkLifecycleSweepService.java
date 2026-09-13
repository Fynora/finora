package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.repository.AccountRepository;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Closes two real gaps found while scoping Plan 5, neither previously flagged:
 * <ul>
 *   <li>An ACTIVE link whose user downgrades stays ACTIVE forever -- SetuDataFetchService.sync's
 *       own entitlement check only skips that one fetch, never touching status or
 *       Account.primarySource. Only the consent-approval-time check (resolveAndAttach) actually
 *       pauses a link, and only if the entitlement was already lost at that exact moment.</li>
 *   <li>PAUSED has zero resume path anywhere in this codebase (confirmed via
 *       {@code grep -rn "AccountAggregatorLinkStatus.PAUSED"}: one write site, no reads) -- a user
 *       who re-upgrades has no way back to a working link short of disconnecting and starting a
 *       brand-new consent flow.</li>
 *   <li>EXPIRED is never set anywhere despite consentExpiresAt being stored on every link.</li>
 * </ul>
 * One sweep for all three, not three sweeps, since each is the same shape: re-validate an
 * ACTIVE/PAUSED link against something that can change independently of any webhook. Mirrors
 * {@code SubscriptionReconciliationSweepService}'s scheduling shape (fixedDelay, gated by a flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly).
 */
@Service
public class AccountAggregatorLinkLifecycleSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorLinkLifecycleSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final EntitlementService entitlementService;

    @Value("${app.integrations.setu.lifecycle-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorLinkLifecycleSweepService(AccountAggregatorLinkRepository links,
                                                        AccountRepository accountRepository,
                                                        EntitlementService entitlementService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.entitlementService = entitlementService;
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.lifecycle-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int changed = sweep();
        if (changed > 0) {
            log.info("Account Aggregator lifecycle sweep: {} link(s) transitioned.", changed);
        }
    }

    public int sweep() {
        int changed = 0;
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)) {
            if (link.getConsentExpiresAt() != null && link.getConsentExpiresAt().isBefore(Instant.now())) {
                link.setStatus(AccountAggregatorLinkStatus.EXPIRED);
                links.save(link);
                changed++;
            } else if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
                setPrimarySourceAndSave(link, AccountAggregatorLinkStatus.PAUSED, Account.PrimarySource.MANUAL);
                changed++;
            }
        }
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.PAUSED)) {
            // Bug fix (found during Plan 5's own post-implementation review): a link can reach
            // PAUSED without ever being attached to an Account --
            // AccountAggregatorIdentityResolutionService.resolveAndAttach's entitlement-lapsed-at-
            // approval-time branch sets PAUSED directly, never calling attach(). Its own
            // re-entrancy guard means resolveAndAttach can never run again for this link, so there
            // is no automatic path back to a real Account -- flipping it to ACTIVE here would leave
            // a "usable, syncing" link with nothing behind it, and the next data.ready webhook would
            // pass a null accountId straight into SetuDataFetchService.sync() ->
            // AccountAggregatorTransactionMapper.mapNew(), which sets Transaction.accountId (NOT
            // NULL) to null. Left PAUSED, not resolved automatically -- resolving it needs
            // re-running identity resolution outside the webhook path, which is its own follow-up.
            if (link.getAccountId() == null) {
                log.warn("Link {} is PAUSED with no account attached -- entitlement may be regained "
                        + "but this link cannot resume automatically (identity resolution never ran).",
                        link.getId());
                continue;
            }
            if (entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
                setPrimarySourceAndSave(link, AccountAggregatorLinkStatus.ACTIVE, Account.PrimarySource.ACCOUNT_AGGREGATOR);
                changed++;
            }
        }
        return changed;
    }

    private void setPrimarySourceAndSave(AccountAggregatorLink link, AccountAggregatorLinkStatus newStatus,
                                          Account.PrimarySource newPrimarySource) {
        link.setStatus(newStatus);
        links.save(link);
        if (link.getAccountId() != null) {
            accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                account.setPrimarySource(newPrimarySource);
                accountRepository.save(account);
            });
        }
    }
}
