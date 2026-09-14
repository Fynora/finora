package com.finora.integrations.setu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountAggregatorLinkRepository extends JpaRepository<AccountAggregatorLink, UUID> {

    Optional<AccountAggregatorLink> findByUserIdAndLinkIdempotencyKey(UUID userId, String linkIdempotencyKey);

    /**
     * Atomically claims a link's status transition -- the fix for the double-submission /
     * concurrent-webhook-redelivery race {@code AccountAggregatorIdentityResolutionService}'s own
     * doc comments already describe but never actually closed. {@code AccountAggregatorLink}
     * deliberately does not extend {@code BaseEntity} (see that entity's own doc comment -- it is
     * connection/session state, not optimistically-locked financial data), so this follows the
     * same alternative this codebase already uses for that category of entity:
     * {@code ImportSessionRepository.claimForConfirmation}'s exact shape, a single conditional
     * {@code UPDATE ... WHERE status = :fromStatus} rather than a version column.
     *
     * <p>Without this, two concurrent requests reaching {@code resolveAndAttach}/
     * {@code confirmExistingAccount}/{@code confirmNewAccount} for the same link (a redelivered
     * {@code consent.approved} webhook racing a real concurrent one, or a user double-tapping /
     * acting from two devices on the same "which account is this?" confirmation screen) both read
     * the same starting status, both proceed, and the loser's write silently overwrites the
     * winner's -- with no {@code @Version} column, there is no {@code
     * ObjectOptimisticLockingFailureException} for {@code GlobalExceptionHandler} to turn into a
     * clean 409, so the result is a second real Account row and a second billable Setu backfill
     * call, not a rejected retry.
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically}: same reasoning as {@code
     * ImportSessionRepository.claimForConfirmation}'s own comment -- a native bulk UPDATE bypasses
     * the persistence context, so any already-loaded {@code AccountAggregatorLink} in the same
     * context needs its stale cache cleared, and a not-yet-flushed change to the same row needs to
     * reach Postgres first.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)}: same reasoning as {@code
     * NetWorthSnapshotRepository}'s identical annotation on its own upsert -- a {@code @Modifying}
     * query needs SOME active transaction to run in at all, and every caller of this method
     * ({@code AccountAggregatorIdentityResolutionService.attach}/{@code resolveAndAttach}) carries
     * no {@code @Transactional} of its own, deliberately, matching this whole package's
     * established "each repository call is its own short transaction" style -- there is no ambient
     * transaction here to join. {@code REQUIRES_NEW} rather than plain {@code @Transactional} so
     * this stays a single, self-contained atomic claim even if a future caller wraps it in a wider
     * transaction of its own.
     *
     * @return 1 if this call performed the transition, 0 if the row was not in {@code fromStatus}
     *         (already claimed by a concurrent request, or genuinely in a different state).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE account_aggregator_links
              SET status = :toStatus, status_changed_at = now(), updated_at = now()
            WHERE id = :id
              AND status = :fromStatus
           """, nativeQuery = true)
    int claimStatusTransition(@Param("id") UUID id, @Param("fromStatus") String fromStatus,
                               @Param("toStatus") String toStatus);

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
