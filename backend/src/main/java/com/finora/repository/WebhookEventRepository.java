package com.finora.repository;

import com.finora.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * Claim-once insert. {@code WebhookEvent.eventId} is a manually-assigned natural key (Razorpay's
     * own event id), not a {@code @GeneratedValue} — Hibernate's {@code save()} treats an entity
     * whose id is already set as detached and issues a SELECT+UPDATE (a merge) rather than an
     * INSERT, so a plain {@code saveAndFlush()} never throws a duplicate-key violation for a repeat
     * event id and silently "succeeds" twice. Same {@code INSERT ... ON CONFLICT DO NOTHING
     * RETURNING} shape as {@code NotificationRepository.insertIfAbsent} for the identical reason.
     *
     * @return the claimed event id, or empty if {@code eventId} already existed (a Razorpay retry or
     *     a concurrent delivery of the same event).
     */
    @Query(value = """
           INSERT INTO webhook_events (event_id, provider, event_type, payload)
           VALUES (:eventId, :provider, :eventType, CAST(:payload AS jsonb))
           ON CONFLICT (event_id) DO NOTHING
           RETURNING event_id
           """, nativeQuery = true)
    Optional<String> insertIfAbsent(@Param("eventId") String eventId, @Param("provider") String provider,
            @Param("eventType") String eventType, @Param("payload") String payload);

    /**
     * {@code WebhookEventRecoverySweepService}'s candidate query. {@code status IS NULL} means
     * {@code claim()} committed but neither {@code markProcessed} nor {@code markFailed} ever ran --
     * the process crashed or was redeployed between the two, and (see that sweep's own doc) a
     * Razorpay/RevenueCat/Setu retry of the same event id is silently swallowed as a duplicate by
     * {@code claim()} rather than ever reaching {@code dispatch()} again. {@code created_at < cutoff}
     * excludes a row still legitimately mid-flight in the current request.
     */
    @Query(value = """
           SELECT * FROM webhook_events
           WHERE status IS NULL AND created_at < :cutoff
           ORDER BY created_at
           LIMIT :limit
           """, nativeQuery = true)
    List<WebhookEvent> findStuckUnprocessed(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Bug found in self-review of {@code WebhookEventRecoverySweepService}: the sweep can run
     * concurrently against an event whose ORIGINAL request is still genuinely in flight (merely
     * slow past the grace period, not actually crashed -- e.g. a hung Razorpay gateway call). Both
     * paths then race to call {@code markProcessed}/{@code markFailed} for the same event id. A
     * plain {@code findById().ifPresent(set...)} (the old implementation) has no protection against
     * that: whichever of the two finishes LAST silently overwrites the other's terminal status --
     * including a real success getting relabelled FAILED, or vice versa. {@code WHERE status IS
     * NULL} makes this claim-once, exactly like {@code insertIfAbsent} above: only the first writer
     * to reach this ever changes the row, and the second's call is a harmless no-op whose return
     * value says so.
     *
     * @return the number of rows updated -- 1 if this call was the one that set the status, 0 if
     *     another caller already had (concurrently, or on a prior call for the same event id).
     */
    @Modifying
    @Query(value = "UPDATE webhook_events SET status = :status, processed_at = now() " +
            "WHERE event_id = :eventId AND status IS NULL", nativeQuery = true)
    int markStatusIfUnset(@Param("eventId") String eventId, @Param("status") String status);

    /**
     * {@code WebhookEventRecoverySweepService}'s second candidate query, alongside {@link
     * #findStuckUnprocessed}. Unlike that one's grace window, {@code created_at < cutoff} isn't
     * here to wait out ambiguity about whether the row is still genuinely in flight -- a {@code
     * FAILED} row is already fully resolved ({@code dispatch()} is {@code @Transactional}, so a
     * handler throwing means every DB write from that attempt was rolled back). It exists as an
     * operational rate limit: without it, a handler that fails deterministically (e.g. a missing
     * {@code Plan} row) would be retried the instant it failed, and a moment's grace gives a human
     * a chance to notice and intervene before that.
     *
     * <p>Without this method at all, a {@code FAILED} row is a dead end: {@code claim()}'s {@code
     * INSERT ... ON CONFLICT DO NOTHING} treats "row already exists" as the only signal, so a
     * genuine Razorpay/RevenueCat retry of the same event id after this point is silently swallowed
     * as a duplicate and the sender stops retrying, believing it succeeded -- the event is then
     * lost until someone notices and reprocesses it by hand.
     *
     * <p>Two things keep one hopeless event from starving the rest (V330). Events that have used
     * up {@code maxAttempts} retries are not returned at all: they stay {@code FAILED} for a
     * person to look at. And the rest come least-retried first, then oldest, so a newly failed
     * event is always reached ahead of one that has already been tried many times. Ordering by
     * age alone let events that can never succeed hold the front of the queue for good, and a
     * run's whole {@code limit} could go on them.
     *
     * <p>{@code SETU} events are left out for the same reason: the sweep never retries a failed one
     * (see {@code WebhookEventRecoverySweepService.recoverOne} for why that is not safe), so their
     * count never rises, and returned here they would sit at the front of this list for good.
     */
    @Query(value = """
           SELECT * FROM webhook_events
           WHERE status = 'FAILED' AND created_at < :cutoff AND recovery_attempts < :maxAttempts
             AND provider <> 'SETU'
           ORDER BY recovery_attempts, created_at
           LIMIT :limit
           """, nativeQuery = true)
    List<WebhookEvent> findFailed(@Param("cutoff") Instant cutoff, @Param("limit") int limit,
                                  @Param("maxAttempts") int maxAttempts);

    /**
     * Reclaims a {@code FAILED} row for the recovery sweep, atomically flipping it back to the same
     * "claimed, in flight" {@code NULL} state {@link #insertIfAbsent} leaves a brand-new row in --
     * so the existing {@link #markStatusIfUnset} guard is exactly what records whatever this
     * reprocessing attempt's real outcome turns out to be; nothing about that method needs to
     * change. {@code WHERE status = 'FAILED'} makes this claim-once the same way {@code
     * insertIfAbsent}/{@code markStatusIfUnset} are: only the first of two concurrent callers (in
     * practice, two overlapping sweep executions) to reach this row wins the row lock and flips it;
     * the second's {@code WHERE} clause re-evaluates against the now-{@code NULL} status after
     * waiting for the lock, matches nothing, and is a harmless no-op.
     *
     * <p>Each reclaim counts as one retry ({@code recovery_attempts}, V330), in the same statement,
     * so the count can never fall behind the retries actually made. The limit is checked here as
     * well as in {@link #findFailed}: the candidate list is read before the loop that reclaims
     * from it, and an overlapping run could have spent an event's last retry in between.
     *
     * @return the number of rows updated -- 1 if this call reclaimed the row, 0 if it was not (or no
     *     longer) {@code FAILED}, or has no retries left.
     */
    @Modifying
    @Query(value = "UPDATE webhook_events SET status = NULL, processed_at = NULL, " +
            "recovery_attempts = recovery_attempts + 1 " +
            "WHERE event_id = :eventId AND status = 'FAILED' AND recovery_attempts < :maxAttempts",
            nativeQuery = true)
    int reclaimFailed(@Param("eventId") String eventId, @Param("maxAttempts") int maxAttempts);

    /** How many failed events the recovery sweep has stopped retrying. Each needs a person. */
    @Query(value = "SELECT count(*) FROM webhook_events WHERE status = 'FAILED' AND recovery_attempts >= :maxAttempts",
            nativeQuery = true)
    long countGivenUp(@Param("maxAttempts") int maxAttempts);
}
