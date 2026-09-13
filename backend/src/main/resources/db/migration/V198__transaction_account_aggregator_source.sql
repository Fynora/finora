-- Identity columns for AA-sourced transactions -- see
-- docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Transaction identity and
-- idempotency". Both nullable: only ever populated for Source.ACCOUNT_AGGREGATOR rows.
ALTER TABLE transactions ADD COLUMN external_txn_id VARCHAR(255);
ALTER TABLE transactions ADD COLUMN transaction_fingerprint VARCHAR(128);

-- Primary dedup lookup path (falls back to the fingerprint index below when absent/unseen). Not
-- unique: Setu's txnId reliability across FIPs is unverified (see the spec) -- a non-unique index
-- lets the mapper's own two-step lookup logic decide what "already seen" means, rather than the
-- database enforcing a guarantee this codebase isn't confident actually holds.
CREATE INDEX idx_transactions_external_txn_id ON transactions (account_id, external_txn_id)
    WHERE external_txn_id IS NOT NULL;

CREATE INDEX idx_transactions_fingerprint ON transactions (account_id, transaction_fingerprint)
    WHERE transaction_fingerprint IS NOT NULL;
