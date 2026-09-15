package com.finora.integrations.setu;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.EntitlementService;
import com.finora.util.BankRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Decides which Account (if any) a newly-approved AccountAggregatorLink belongs to. Reuses
 * ProductIdentityResolver -- the same NEW/MATCHED/PROBABLE model already governing manual
 * re-import -- rather than a separate, less-safe matching system. See the design spec's "Account
 * identity resolution" section for why silent attachment on anything less than an exact match was
 * rejected.
 */
@Service
public class AccountAggregatorIdentityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorIdentityResolutionService.class);

    private final SetuConsentGateway gateway;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final ProductIdentityResolver productIdentityResolver;
    private final AccountAggregatorLinkRepository links;
    private final EntitlementService entitlementService;
    private final SetuDataFetchService fetchService;
    private final Clock clock;

    @Autowired
    public AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                        AccountService accountService,
                                                        ProductIdentityResolver productIdentityResolver,
                                                        AccountAggregatorLinkRepository links,
                                                        EntitlementService entitlementService,
                                                        SetuDataFetchService fetchService) {
        this(gateway, accountRepository, accountService, productIdentityResolver, links, entitlementService,
                fetchService, Clock.systemUTC());
    }

    /** Package-private: only this package's tests need to fix "now", the same reason
     *  SetuDataFetchService takes an injected {@link Clock} rather than the JVM's default. */
    AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                AccountService accountService,
                                                ProductIdentityResolver productIdentityResolver,
                                                AccountAggregatorLinkRepository links,
                                                EntitlementService entitlementService,
                                                SetuDataFetchService fetchService, Clock clock) {
        this.gateway = gateway;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.productIdentityResolver = productIdentityResolver;
        this.links = links;
        this.entitlementService = entitlementService;
        this.fetchService = fetchService;
        this.clock = clock;
    }

    /**
     * Triggered by the {@code consent.approved} webhook. Bug fix (found during post-implementation
     * review, not part of the original plan): two real gaps closed here.
     *
     * <p><b>Re-entrancy.</b> Setu (like most webhook senders) can redeliver the same logical event
     * under a different delivery id, which {@code WebhookEventService}'s idempotency ledger cannot
     * catch (it dedupes by event id, not by business meaning). Without the status check below, a
     * redelivered {@code consent.approved} would call {@link SetuConsentGateway#fetchConsentDetail}
     * again (a second billable Setu call for no reason) and could create a SECOND new Account for
     * what should resolve to NEW exactly once.
     *
     * <p><b>Entitlement can lapse mid-flight.</b> A user can downgrade from Premium in the window
     * between requesting a link and Setu's webhook arriving. Without the check below, the account
     * would still get silently attached and locked into {@code ACCOUNT_AGGREGATOR} with no active
     * entitlement and no sync ever running to justify blocking manual import -- a real dead end for
     * that account until support intervenes. Downgraded here means the same as a downgrade after a
     * link was already ACTIVE: PAUSED, no account touched.
     *
     * <p><b>Re-entrancy, take two.</b> The status check above only blocks a redelivered webhook
     * arriving AFTER this method already ran to completion (past {@code attach()}'s own status
     * claim). It does nothing for one arriving WHILE an earlier, still-{@code CONSENT_PENDING} run
     * is between here and there -- crashed or merely slow -- because the real, billable {@link
     * SetuConsentGateway#fetchConsentDetail} call and any resulting Account creation both happen
     * before the link's status is ever written past {@code CONSENT_PENDING}. {@link
     * AccountAggregatorLinkRepository#claimIdentityResolution} closes that: claimed atomically,
     * once, immediately below -- before the external call -- so a second delivery of the same
     * logical event (a genuine race, or {@code WebhookEventRecoverySweepService}'s own
     * NULL-crash-recovery re-dispatch) is blocked right here instead of repeating the external call
     * and possibly creating a second Account.
     */
    public void resolveAndAttach(AccountAggregatorLink link) {
        if (link.getStatus() != AccountAggregatorLinkStatus.CONSENT_PENDING) {
            log.info("Ignoring consent.approved for link {} already in status {} (redelivered webhook).",
                    link.getId(), link.getStatus());
            return;
        }
        if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            log.info("Link {} approved but user is no longer entitled to ACCOUNT_AGGREGATOR_SYNC; pausing.",
                    link.getId());
            link.setStatus(AccountAggregatorLinkStatus.PAUSED);
            links.save(link);
            return;
        }
        int resolutionClaimed = links.claimIdentityResolution(link.getId(),
                AccountAggregatorLinkStatus.CONSENT_PENDING.name());
        if (resolutionClaimed == 0) {
            log.info("Link {} identity resolution already claimed by an earlier request -- "
                    + "skipping (redelivered webhook or crash-recovery re-dispatch).", link.getId());
            return;
        }

        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());
        String bankId = detectBankId(detail);
        ProductIdentityResolver.ProductMatch match = resolve(link, detail, bankId);

        switch (match.resolution()) {
            case MATCHED -> attach(link, match.account());
            case NEW -> attach(link, createAccount(link, detail, bankId));
            case PROBABLE -> {
                // Never auto-attached -- see this class's own doc comment. The candidate(s) stay
                // available via match.candidates() for the confirmation endpoints to offer; this
                // method's job ends at surfacing that a decision is needed.
                //
                // Bug fix: claimed atomically, same reasoning as attach() below -- a redelivered
                // consent.approved racing a genuinely concurrent delivery could otherwise both pass
                // the CONSENT_PENDING guard at the top of this method and both reach here. Losing
                // this particular race is harmless either way (both writers agree on the target
                // status), but the claim is still the correct tool: it is what makes "did I win"
                // answerable at all, rather than two redundant writes racing with no way to tell.
                int claimed = links.claimStatusTransition(link.getId(),
                        AccountAggregatorLinkStatus.CONSENT_PENDING.name(),
                        AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION.name());
                if (claimed == 0) {
                    log.info("Link {} lost the race to move to PENDING_ACCOUNT_CONFIRMATION "
                            + "-- already resolved by a concurrent request.", link.getId());
                    return;
                }
                link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
            }
        }
    }

    /**
     * Shared by the MATCHED and NEW branches above, and by the confirm-existing-account/
     * confirm-new-account paths (a user-confirmed PROBABLE match is handled identically to an
     * automatic MATCHED one once the account is settled).
     *
     * <p>Bug fix: the transition out of the link's current status ({@code link.getStatus()} at the
     * moment this is called -- {@code CONSENT_PENDING} for the resolveAndAttach callers,
     * {@code PENDING_ACCOUNT_CONFIRMATION} for the confirm* callers) is claimed atomically first,
     * via {@link AccountAggregatorLinkRepository#claimStatusTransition}, before any Account is
     * touched. {@code AccountAggregatorLink} deliberately carries no {@code @Version} (see its own
     * doc comment -- connection/session state, not BaseEntity's optimistically-locked financial
     * data), so without this, two concurrent requests for the same link -- a redelivered webhook
     * racing a real concurrent one, or a user acting on the same confirmation screen from two
     * devices/tabs -- both read the same starting status, both create/attach an Account, and both
     * trigger a real 3-month Setu backfill call; the loser's write then silently overwrites the
     * winner's with no conflict signal, leaving its Account orphaned (attached to nothing) and one
     * of the two backfill calls wasted. The claim makes the loser detectable instead: it returns
     * false, and every caller below either logs and no-ops (the async, webhook-triggered paths) or
     * surfaces a clean 409 (the synchronous, user-facing confirm endpoints) rather than racing.
     *
     * <p>Residual risk, accepted: the claim (its own {@code REQUIRES_NEW} transaction), the
     * account save, and the final {@code links.save(link)} below are three separate transactions,
     * not one atomic unit -- if {@code accountRepository.save(account)} throws after the claim has
     * already committed {@code ACTIVE}, the link is left {@code ACTIVE} with {@code accountId}
     * still null until someone notices (no sweep currently searches for this combination). Judged
     * acceptable: the failure window is a single local {@code save()} call with nothing to
     * legitimately reject about the row it's writing, so it is not expected to fail in practice,
     * and closing it fully would mean giving up the atomic claim's own short-transaction shape (the
     * same trade {@code SubscriptionCancellationDispatchSweepService}'s own "Residual risk,
     * accepted explicitly" doc comment makes for the identical class of problem).
     *
     * @return true if this call won the claim and attached the account, false if a concurrent
     *         request already claimed this link's transition first.
     */
    boolean attach(AccountAggregatorLink link, Account account) {
        int claimed = links.claimStatusTransition(link.getId(), link.getStatus().name(),
                AccountAggregatorLinkStatus.ACTIVE.name());
        if (claimed == 0) {
            log.info("Link {} lost the race to attach an account -- already resolved by a "
                    + "concurrent request.", link.getId());
            return false;
        }

        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountRepository.save(account);
        link.setAccountId(account.getId());
        // In-memory only -- claimStatusTransition already persisted this. Still required before
        // the links.save(link) below: that save is a merge of a now-detached entity (the native
        // UPDATE's clearAutomatically detaches it), which would otherwise overwrite the DB's
        // freshly-claimed ACTIVE status back to whatever this Java object's stale field still held.
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        links.save(link);

        // First sync happens right here rather than waiting on Setu's first data.ready webhook --
        // the 3-month backfill (Plan 2) is this codebase's own responsibility to trigger, not
        // something to assume a webhook will eventually ask for. See the design spec's "Scope"
        // section. One choke point covers every path to ACTIVE (resolveAndAttach's MATCHED/NEW
        // branches, confirmExistingAccount, confirmNewAccount), rather than four call sites each
        // remembering to trigger a backfill.
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        fetchService.sync(link, today.minusMonths(3), today);
        return true;
    }

    private Account createAccount(AccountAggregatorLink link, SetuConsentDetail detail, String bankId) {
        String accountType = link.getFiType() == FiType.CREDIT_CARD ? "CREDIT_CARD" : "SAVINGS";
        AccountDto created = accountService.create(link.getUserId(), new AccountDto.CreateRequest(
                bankNameOr(bankId, "Bank"), accountType, java.math.BigDecimal.ZERO, null, null,
                null, detail.accountHolderName(), detail.maskedAccountNumber(), bankId,
                null, detail.ifscCode(),
                null, null, null, null, null, null, null), link.getUserId());
        return accountRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("Just-created account not found: " + created.id()));
    }

    /** Controller-facing: the user, shown a PROBABLE match, picked one of the offered candidates.
     *  Loads and ownership-checks both the link and the chosen account, re-checks entitlement and
     *  the link's own status (refusing a link that isn't actually awaiting confirmation -- the same
     *  re-entrancy concern {@link #resolveAndAttach} guards against, here for a double-submitted
     *  confirm request), then delegates to {@link #attach}. */
    public void confirmExistingAccount(UUID userId, UUID linkId, UUID accountId) {
        AccountAggregatorLink link = requireConfirmable(userId, linkId);
        Account account = OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        // Bug fix: a concurrent request (a double-tap, or the user acting from a second device on
        // the same confirmation screen) can win the race between requireConfirmable's check and
        // this attach -- see attach()'s own doc comment. That caller already got the ACTIVE result;
        // this one gets a clean conflict instead of silently creating/attaching a second Account.
        if (!attach(link, account)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This link was already confirmed by another request.");
        }
    }

    /** Controller-facing: the user, shown a PROBABLE match, said "no, this is a different/new
     *  account." Re-fetches consent detail rather than requiring the caller to supply it (there is
     *  nowhere for a client to have gotten it from) -- the one extra Setu call this costs only
     *  happens on this less-common path, not on every link. */
    public void confirmNewAccount(UUID userId, UUID linkId) {
        AccountAggregatorLink link = requireConfirmable(userId, linkId);
        SetuConsentDetail detail = gateway.fetchConsentDetail(link.getConsentHandleId());
        String bankId = detectBankId(detail);
        // Same race as confirmExistingAccount above -- see attach()'s own doc comment. The new
        // Account this just created is left as an ordinary MANUAL account if the claim is lost
        // (createAccount runs before attach() ever touches primarySource), not a silently-orphaned
        // ACCOUNT_AGGREGATOR one -- a harmless duplicate the user can delete, matching the same
        // accepted tradeoff SetuConsentService.initiateLink's own doc comment already makes for an
        // orphaned Setu consent on the identical class of race.
        if (!attach(link, createAccount(link, detail, bankId))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This link was already confirmed by another request.");
        }
    }

    private AccountAggregatorLink requireConfirmable(UUID userId, UUID linkId) {
        AccountAggregatorLink link = OwnershipGuard.requireOwned(
                links.findById(linkId), AccountAggregatorLink::getUserId, userId, "AccountAggregatorLink");
        if (link.getStatus() != AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This link isn't waiting for an account confirmation.");
        }
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account Aggregator sync is a Premium feature.");
        }
        return link;
    }

    private ProductIdentityResolver.ProductMatch resolve(AccountAggregatorLink link, SetuConsentDetail detail,
                                                           String bankId) {
        FinancialProductType type = link.getFiType() == FiType.CREDIT_CARD
                ? FinancialProductType.CREDIT_CARD : FinancialProductType.SAVINGS;

        ProductIdentity discovered = (detail.fullAccountNumber() != null
                ? ProductIdentity.of(bankId, type, detail.fullAccountNumber(), detail.maskedAccountNumber())
                : ProductIdentity.stored(bankId, type, null, detail.maskedAccountNumber()))
                .withWeakSignals(detail.ifscCode(), detail.accountHolderName());

        return productIdentityResolver.resolve(link.getUserId(), discovered);
    }

    /** IFSC is passed as a labelled hint, reusing BankRegistry's own "Signal 1: the account's own,
     *  labelled IFSC" detection path (see BankRegistry.detect) rather than adding a second,
     *  Setu-specific bank-id mapping table. */
    private static String detectBankId(SetuConsentDetail detail) {
        return BankRegistry.detect("account-aggregator", List.of("IFSC " + detail.ifscCode())).id();
    }

    private static String bankNameOr(String bankId, String fallback) {
        BankRegistry.BankInfo info = BankRegistry.get(bankId);
        return info != null ? info.shortName() : fallback;
    }
}
