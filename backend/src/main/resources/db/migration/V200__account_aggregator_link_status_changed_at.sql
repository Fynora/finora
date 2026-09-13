-- One added timestamp (Plan 5 scope doc, "audit trail visibility"): when did this link last
-- change status, distinct from updated_at, which every sync touches too (setLastSyncedAt calls
-- the same touch() setStatus does) and is therefore useless for "when did this stop" or
-- "connected on". Backfilled from updated_at for existing rows -- an approximation for anything
-- that already changed status more than once, acceptable since no rows exist in production yet
-- (Plan 5 is what first makes this feature reachable from the app).
ALTER TABLE account_aggregator_links ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE account_aggregator_links SET status_changed_at = updated_at;
