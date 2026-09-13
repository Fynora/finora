package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one place the "this link is done" transition lives -- both the inbound
 * {@code consent.revoked} webhook (see {@link AccountAggregatorWebhookDispatcher}) and the
 * user-triggered disconnect endpoint (Plan 5) call {@link #revoke} rather than each writing their
 * own copy. Same {@code REVOKED} status either way -- see the Plan 5 scope doc's own "different
 * behavior -> different state, same behavior -> same state" reasoning for why this is deliberately
 * NOT two statuses. The who/why distinction a support ticket actually needs lives in the audit
 * action name instead: {@code ACCOUNT_AGGREGATOR_CONSENT_REVOKED} (the AA app revoked it) vs.
 * {@code ACCOUNT_AGGREGATOR_USER_DISCONNECTED} (the user clicked disconnect in Fynora) -- the
 * mechanism this codebase already uses for provenance questions, not a second copy of it in the
 * state machine.
 */
@Service
public class AccountAggregatorLinkManagementService {

    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AccountAggregatorLinkManagementService(AccountAggregatorLinkRepository links,
                                                    AccountRepository accountRepository,
                                                    AuditService auditService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    // Bug fix (found during Plan 5's own post-implementation review): disconnect() had no status
    // guard at all, unlike AccountAggregatorIdentityResolutionService.requireConfirmable's
    // identical pattern for the confirm endpoints. A caller could disconnect an already-terminal
    // link over and over -- each call a silent no-op re-write plus a fresh, misleading
    // ACCOUNT_AGGREGATOR_USER_DISCONNECTED audit row, with no error telling the caller there was
    // nothing left to disconnect.
    private static final Set<AccountAggregatorLinkStatus> TERMINAL_STATUSES = EnumSet.of(
            AccountAggregatorLinkStatus.REVOKED, AccountAggregatorLinkStatus.EXPIRED,
            AccountAggregatorLinkStatus.REJECTED, AccountAggregatorLinkStatus.LINK_FAILED);

    public List<AccountAggregatorLink> listForUser(UUID userId) {
        return links.findByUserId(userId);
    }

    /** User-triggered. NOT a call to Setu -- see AccountAggregatorLinkStatus.REVOKED's own doc
     *  comment: Fynora cannot force a revoke. This stops Fynora calling Setu for this link; the
     *  user's actual consent grant at their AA app is untouched, which the caller (the disconnect
     *  endpoint / its frontend confirmation copy) must say plainly. */
    public void disconnect(UUID userId, UUID linkId) {
        AccountAggregatorLink link = OwnershipGuard.requireOwned(
                links.findById(linkId), AccountAggregatorLink::getUserId, userId, "AccountAggregatorLink");
        if (TERMINAL_STATUSES.contains(link.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "This link isn't connected -- there's nothing to disconnect.");
        }
        revoke(link, "ACCOUNT_AGGREGATOR_USER_DISCONNECTED");
    }

    /** Shared by {@link #disconnect} and {@link AccountAggregatorWebhookDispatcher}'s
     *  {@code consent.revoked} case -- see this class's own doc comment for why one transition,
     *  not two. */
    void revoke(AccountAggregatorLink link, String auditAction) {
        link.setStatus(AccountAggregatorLinkStatus.REVOKED);
        if (link.getAccountId() != null) {
            accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                account.setPrimarySource(Account.PrimarySource.MANUAL);
                accountRepository.save(account);
            });
        }
        links.save(link);
        auditService.record(link.getUserId(), auditAction, "AccountAggregatorLink", link.getId());
    }
}
