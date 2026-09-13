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

    /** For AccountAggregatorLinkSweepService's stale-row TTL check (Task 12) -- rows stuck in an
     *  in-progress status past a cutoff. */
    List<AccountAggregatorLink> findByStatusInAndCreatedAtBefore(
            List<AccountAggregatorLinkStatus> statuses, Instant cutoff);

    /** AccountService.listForUser's batch resolution of Account.aaSyncStale (Plan 4) -- one query
     *  for every account on the page, not one per account. Staleness itself is computed by
     *  AccountAggregatorLinkStalenessService against each returned link, not by this query. */
    List<AccountAggregatorLink> findByAccountIdInAndStatus(
            Collection<UUID> accountIds, AccountAggregatorLinkStatus status);

    /** AccountAggregatorOutageSweepService's read path (Plan 4) -- every currently-ACTIVE link,
     *  filtered for staleness in Java via AccountAggregatorLinkStalenessService.isStale, not a
     *  second copy of the threshold math in SQL. */
    List<AccountAggregatorLink> findByStatus(AccountAggregatorLinkStatus status);
}
