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
}
