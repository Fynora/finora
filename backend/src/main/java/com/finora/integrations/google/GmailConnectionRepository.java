package com.finora.integrations.google;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, UUID> {

    /** The user's live connection, if any. Mirrors the partial unique index in V80 — at most one
     *  row can match, and the database is what guarantees that rather than this query. */
    Optional<GmailConnection> findByUserIdAndStatusIn(UUID userId, List<GmailConnection.Status> statuses);

    /** Whether this mailbox is already connected to SOME Finora account -- not necessarily this
     *  one. Lets the connect path answer "that Gmail account is already linked elsewhere" with a
     *  clear message instead of letting the unique index reject it as an opaque 409. */
    Optional<GmailConnection> findByGoogleUserIdAndStatusIn(String googleUserId,
                                                            List<GmailConnection.Status> statuses);

    List<GmailConnection> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Connections due for a discovery pass, oldest-checked first.
     *
     * <p>{@code CONNECTED} only, never the whole LIVE set: a {@code REAUTH_REQUIRED} connection has
     * a dead grant that only the user can revive, so including it would spend a token-refresh
     * request per tick to learn the same thing forever.
     *
     * <p>Never-checked rows sort first, so a mailbox connected moments ago is picked up on the next
     * tick rather than queueing behind every established connection.
     *
     * <p>The {@code discoveryRetryAfter} predicate is the backoff: {@code lastDiscoveryAt} does not
     * move on a failed run (see {@code GmailMessageDiscoveryService.markDiscovered}), so without
     * this a mailbox that keeps failing would sort at the very front of this list again on every
     * tick, forever -- {@link GmailConnection#recordDiscoveryFailure} is what sets it, and a clean
     * run ({@link GmailConnection#recordDiscoverySuccess}) clears it.
     *
     * <p>Paged rather than "all of them", so one tick's work is bounded by the slice size instead of
     * by how many users the product has.
     */
    @Query("""
           select c from GmailConnection c
           where c.status = com.finora.integrations.google.GmailConnection$Status.CONNECTED
             and (c.lastDiscoveryAt is null or c.lastDiscoveryAt < :checkedBefore)
             and (c.discoveryRetryAfter is null or c.discoveryRetryAfter < :now)
           order by c.lastDiscoveryAt asc nulls first
           """)
    List<GmailConnection> findDueForDiscovery(@Param("checkedBefore") Instant checkedBefore,
                                              @Param("now") Instant now,
                                              Pageable pageable);

    /**
     * Connections carrying at least one {@code DETECTED_NOT_STAGED} message -- a backlog extraction
     * can still drain even when discovery itself is backed off.
     *
     * <p><b>Deliberately NOT gated by {@code discoveryRetryAfter} or {@code lastDiscoveryAt}</b>,
     * unlike {@link #findDueForDiscovery}. That backoff exists to stop a mailbox that keeps failing
     * discovery from crowding the front of the due-for-discovery ordering -- but discovery and
     * extraction hit different Gmail endpoints with different cost profiles (a header fetch over
     * newly-listed mail vs. a body fetch over already-known messages), so a discovery backoff has no
     * bearing on whether extraction can still make progress. Without this query, a connection whose
     * discovery keeps failing would be excluded from {@code findDueForDiscovery} entirely for up to
     * {@code GmailConnection.MAX_DISCOVERY_BACKOFF_MINUTES}, and {@code GmailDiscoveryWorker} only
     * ever attempts extraction for connections that query returns -- so a real backlog would sit
     * untouched for hours at a time even though extraction's own request pattern might still succeed.
     *
     * <p>{@code CONNECTED} only, same reasoning as {@code findDueForDiscovery}: a dead grant or a
     * missing scope means extraction's own access-token fetch fails identically, and a connection
     * without {@code gmail.readonly} can never have a {@code DETECTED_NOT_STAGED} row in the first
     * place (discovery itself never runs for it), so the status filter alone is enough here.
     *
     * <p>Paged for the same reason {@code findDueForDiscovery} is: one tick's extraction-only work
     * must be bounded by the slice size, not by how many mailboxes are currently backed off.
     */
    @Query("""
           select c from GmailConnection c
           where c.status = com.finora.integrations.google.GmailConnection$Status.CONNECTED
             and exists (
                 select 1 from GmailProcessedMessage m
                 where m.connectionId = c.id
                   and m.outcome = com.finora.integrations.google.GmailProcessedMessage$Outcome.DETECTED_NOT_STAGED
             )
           order by c.id
           """)
    List<GmailConnection> findWithPendingExtraction(Pageable pageable);

    /** AccountPurgeSweepService -- called after GmailConnectionService.disconnect() has already
     *  revoked and closed any LIVE connection; this clears PII (googleEmail/googleUserId) from
     *  disconnected/revoked history rows too, not just the live one. gmail_processed_messages
     *  cascades automatically via its own connection_id ON DELETE CASCADE. Hard delete, no
     *  soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
