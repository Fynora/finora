-- Backoff state for Gmail discovery -- Phase C4 follow-up.
--
-- findDueForDiscovery (GmailConnectionRepository) orders CONNECTED mailboxes by last_discovery_at
-- ascending, nulls first. GmailMessageDiscoveryService.markDiscovered() only advances
-- last_discovery_at on a run that reaches the end of its query window cleanly -- a run that throws
-- (a Gmail rate limit, a transient 5xx) leaves it untouched. Those two facts together mean a
-- mailbox that keeps failing sorts at the very front of "due" again on the very next tick,
-- indefinitely, with nothing to make it wait its turn.
--
-- These columns let a connection back off without being excluded from discovery altogether --
-- GMAIL_SYNC must still eventually catch up, just not spend every tick's slice on the same broken
-- mailbox. discovery_failure_count is consecutive failures since the last clean run; each one
-- pushes discovery_retry_after further out (GmailConnection.recordDiscoveryFailure). A clean run
-- resets both (GmailConnection.recordDiscoverySuccess) -- backoff is about consecutive failures,
-- not a lifetime count.

ALTER TABLE gmail_connections
    ADD COLUMN discovery_failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN discovery_retry_after TIMESTAMPTZ;

-- Supports the added predicate on findDueForDiscovery: most rows have discovery_retry_after null
-- (never failed, or already caught up) and are unaffected by this index; the ones that do have it
-- set are exactly the ones the predicate needs to check quickly.
CREATE INDEX idx_gmail_connections_discovery_retry_after
    ON gmail_connections (discovery_retry_after)
    WHERE discovery_retry_after IS NOT NULL;
