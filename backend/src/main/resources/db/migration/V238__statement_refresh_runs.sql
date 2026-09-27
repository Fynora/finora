-- Statement refresh, step 3: applying a refresh. One row per refresh of one statement: what it
-- changed, for the "what changed" summary the user sees afterwards (step 5), and as the record of
-- what a refresh did to someone's transactions.
--
-- status: APPLIED (changes were made), NO_CHANGES, NEEDS_PASSWORD, NEEDS_REVIEW (the re-read would
-- remove too much to trust -- nothing applied), FAILED (nothing applied; reason in detail).
--
-- detail quotes statement narrations (the rows added, corrected and removed), so like
-- statement_refresh_previews it is deleted explicitly with its statement and by the account purge:
-- statement_imports rows are soft-deleted, never hard-deleted, so nothing cascades.
CREATE TABLE statement_refresh_runs (
    id                   UUID PRIMARY KEY,
    statement_import_id  UUID NOT NULL REFERENCES statement_imports(id),
    user_id              UUID NOT NULL,
    parser_version       VARCHAR(40),
    status               VARCHAR(20) NOT NULL,
    rows_changed         INTEGER NOT NULL DEFAULT 0,
    rows_added           INTEGER NOT NULL DEFAULT 0,
    rows_removed         INTEGER NOT NULL DEFAULT 0,
    facts_changed        INTEGER NOT NULL DEFAULT 0,
    -- What the refresh moved the account's balance by (null when it did not touch it).
    balance_change       NUMERIC(14, 2),
    detail               JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_statement_refresh_runs_statement ON statement_refresh_runs(statement_import_id, created_at DESC);
CREATE INDEX idx_statement_refresh_runs_user ON statement_refresh_runs(user_id, created_at DESC);
