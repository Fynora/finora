-- Audit fix (2026-09-24): these tables all carry a user_id, are all queried or deleted by it
-- (AccountPurgeSweepService calls deleteByUserId on each during account deletion), and none had
-- an index with user_id as its leading column. Account deletion was therefore a sequence of
-- sequential scans whose cost grew with the whole platform's data rather than the one user's,
-- inside a single transaction. Plain CREATE INDEX rather than CONCURRENTLY: Flyway runs each
-- migration in a transaction, which CONCURRENTLY cannot do, and every table here is small enough
-- today for the lock to be momentary.
CREATE INDEX IF NOT EXISTS idx_held_statements_user_id
    ON held_statements (user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_entries_user_id
    ON feedback_entries (user_id);
CREATE INDEX IF NOT EXISTS idx_merchant_learning_events_user_id
    ON merchant_learning_events (user_id);
CREATE INDEX IF NOT EXISTS idx_merchant_learning_audit_user_id
    ON merchant_learning_audit (user_id);
CREATE INDEX IF NOT EXISTS idx_account_aggregator_links_user_id
    ON account_aggregator_links (user_id);
CREATE INDEX IF NOT EXISTS idx_reimport_confirmation_claims_user_id
    ON reimport_confirmation_claims (user_id);
CREATE INDEX IF NOT EXISTS idx_counterparty_category_observation_user_id
    ON counterparty_category_observation (user_id);
