-- Outcome of the most recent fetch attempt against this link -- see
-- docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md, Task 1.
ALTER TABLE account_aggregator_links ADD COLUMN last_sync_status VARCHAR(16);
