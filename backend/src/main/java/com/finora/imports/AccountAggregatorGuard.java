package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Refuses a manual import into an account an ACTIVE AccountAggregatorLink already owns -- see
 *  the design spec's "Account identity resolution" section: hiding the upload control in the UI
 *  is not enforcement, this is. A PAUSED/REVOKED/EXPIRED link's account is unaffected -- see
 *  AccountAggregatorWebhookDispatcher, which reverts primarySource to MANUAL the moment a link
 *  stops being ACTIVE, so this check only ever fires while sync is genuinely live.
 *
 *  <p>Promoted from a static nested class inside ImportService (Plan 1) to a top-level Spring bean
 *  here (Plan 4, the outage escape hatch) -- the two new dependencies below made a hand-constructed
 *  nested class the wrong shape, and this removes a parameter from ImportService's constructor
 *  rather than adding more to it (see that class's own commit history). */
@Component
public class AccountAggregatorGuard {
    private final AccountRepository accountRepository;
    private final AccountAggregatorLinkRepository aaLinks;
    private final AccountAggregatorLinkStalenessService staleness;
    private final AuditService auditService;

    public AccountAggregatorGuard(AccountRepository accountRepository, AccountAggregatorLinkRepository aaLinks,
                                   AccountAggregatorLinkStalenessService staleness, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.aaLinks = aaLinks;
        this.staleness = staleness;
        this.auditService = auditService;
    }

    public void checkNotActivelySynced(UUID userId, UUID accountId) {
        Account account = OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        if (account.getPrimarySource() != Account.PrimarySource.ACCOUNT_AGGREGATOR) return;
        Optional<AccountAggregatorLink> activeLink = aaLinks.findByAccountIdAndStatus(accountId,
                AccountAggregatorLinkStatus.ACTIVE);
        if (activeLink.isEmpty()) return;
        AccountAggregatorLink link = activeLink.get();

        if (staleness.isStale(link)) {
            // Outage escape hatch (Plan 4). primarySource stays ACCOUNT_AGGREGATOR -- this is a
            // transient, self-correcting bypass (the hatch closes on its own the instant a fresh
            // sync succeeds), not the durable MANUAL reversion AccountAggregatorWebhookDispatcher
            // performs for REVOKED/EXPIRED/PAUSED. Audited so product/support has a queryable
            // trail of when the hatch was actually exercised, not just when it was available.
            auditService.record(userId, "ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED",
                    "AccountAggregatorLink", link.getId());
            return;
        }

        throw new ApiException(HttpStatus.CONFLICT,
                "This account syncs automatically and can't be manually imported into "
                + "while that sync is active.");
    }
}
