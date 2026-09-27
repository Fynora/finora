-- Statement refresh, step 2b: the dry run. After each deploy a background worker re-reads every
-- statement parsed by an older build, compares the result with the transactions the statement has
-- now (StatementRefreshDiff), and records what a refresh WOULD change here. Nothing is written to
-- anyone's transactions. This is what decides who gets told an update is available, and it lets
-- an admin see how much a release changes before any user does.
--
-- One row per statement per build (the unique index is also what makes a second, concurrent
-- worker harmless). A statement keeps only its latest preview: older builds' rows are deleted when
-- a newer one is written.
--
-- detail holds the row-level changes, including statement narrations, so like
-- statement_import_excluded_rows it is deleted explicitly with its statement and by the account
-- purge -- statement_imports rows are soft-deleted, never hard-deleted, so nothing cascades.
CREATE TABLE statement_refresh_previews (
    id                   UUID PRIMARY KEY,
    statement_import_id  UUID NOT NULL REFERENCES statement_imports(id),
    user_id              UUID NOT NULL,
    parser_version       VARCHAR(40) NOT NULL,
    -- CHANGES | NO_CHANGES | NEEDS_REVIEW | NEEDS_PASSWORD | FAILED
    status               VARCHAR(20) NOT NULL,
    rows_changed         INTEGER NOT NULL DEFAULT 0,
    rows_added           INTEGER NOT NULL DEFAULT 0,
    rows_removed         INTEGER NOT NULL DEFAULT 0,
    rows_conflicting     INTEGER NOT NULL DEFAULT 0,
    rows_unchanged       INTEGER NOT NULL DEFAULT 0,
    facts_changed        INTEGER NOT NULL DEFAULT 0,
    detail               JSONB,
    computed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_statement_refresh_previews_statement_version
    ON statement_refresh_previews(statement_import_id, parser_version);
CREATE INDEX idx_statement_refresh_previews_version_status ON statement_refresh_previews(parser_version, status);
CREATE INDEX idx_statement_refresh_previews_user ON statement_refresh_previews(user_id);
