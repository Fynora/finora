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

    /** AccountPurgeSweepService -- called after GmailConnectionService.disconnect() has already
     *  revoked and closed any LIVE connection; this clears PII (googleEmail/googleUserId) from
     *  disconnected/revoked history rows too, not just the live one. gmail_processed_messages
     *  cascades automatically via its own connection_id ON DELETE CASCADE. Hard delete, no
     *  soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
