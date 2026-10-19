-- Gate 2a: "has this user ever imported a statement, deleted ones included" is asked on every
-- dashboard load (ActivationFacts.hasEverImported, for the "What stopped you?" card), at account
-- deletion, and once per signup by the activation report. statement_imports had no index that can
-- answer it: the only one leading with user_id is partial (deleted_at IS NULL), so it cannot see
-- deleted statements, and Postgres scanned the whole table each time -- confirmed with EXPLAIN on
-- the migrated schema, where even with sequential scans disabled the plan stayed a Seq Scan.
-- Plain CREATE INDEX for the same reason as V226: Flyway runs each migration in a transaction.
CREATE INDEX IF NOT EXISTS idx_statement_imports_user_id ON statement_imports (user_id);
