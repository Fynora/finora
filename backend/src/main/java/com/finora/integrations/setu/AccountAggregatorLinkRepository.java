package com.finora.integrations.setu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountAggregatorLinkRepository extends JpaRepository<AccountAggregatorLink, UUID> {

    Optional<AccountAggregatorLink> findByUserIdAndLinkIdempotencyKey(UUID userId, String linkIdempotencyKey);

    Optional<AccountAggregatorLink> findByConsentHandleId(String consentHandleId);

    Optional<AccountAggregatorLink> findByAccountIdAndStatus(UUID accountId, AccountAggregatorLinkStatus status);

    List<AccountAggregatorLink> findByUserId(UUID userId);

    /** Shared by two independent sweeps' read paths: AccountAggregatorOutageSweepService (Plan 4,
     *  every currently-ACTIVE link, filtered for staleness in Java) and
     *  AccountAggregatorLinkLifecycleSweepService (Plan 5, ACTIVE + PAUSED, re-validated against
     *  entitlement/consent-expiry). */
    List<AccountAggregatorLink> findByStatus(AccountAggregatorLinkStatus status);

    /** For AccountAggregatorLinkSweepService's stale-row TTL check (Task 12) -- rows stuck in an
     *  in-progress status past a cutoff. */
    List<AccountAggregatorLink> findByStatusInAndCreatedAtBefore(
            List<AccountAggregatorLinkStatus> statuses, Instant cutoff);

    /** AccountService.listForUser's batch resolution of Account.aaSyncStale (Plan 4) -- one query
     *  for every account on the page, not one per account. Staleness itself is computed by
     *  AccountAggregatorLinkStalenessService against each returned link, not by this query. */
    List<AccountAggregatorLink> findByAccountIdInAndStatus(
            Collection<UUID> accountIds, AccountAggregatorLinkStatus status);

    /** SetuConsentService's link-cap check (Plan 5) -- counts only non-terminal statuses, so a
     *  REVOKED/EXPIRED/REJECTED/LINK_FAILED link never blocks a fresh one. Whether PAUSED should be
     *  included here is a still-open product decision (Plan 5 scope doc) -- the caller decides
     *  which statuses to pass, this query stays a generic count-by-status-set. */
    long countByUserIdAndStatusIn(UUID userId, List<AccountAggregatorLinkStatus> statuses);

    /** SetuConsentService's relink-throttle check (Plan 5) -- the design spec's own "at most one
     *  consent-creation attempt per specific account per rolling 24h window" is not implementable
     *  as literally stated (no account identity exists before consent completes -- see the scope
     *  doc's own reasoning), so this is scoped to fiType, the coarsest identity available at
     *  initiate time. */
    boolean existsByUserIdAndFiTypeAndCreatedAtAfter(UUID userId, FiType fiType, Instant cutoff);
}
