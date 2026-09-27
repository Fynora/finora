-- Statement refresh, step 2 (re-reading a stored statement with an improved parser and patching
-- its transactions in place). Two things a refresh needs to know about each statement, which only
-- the moment of import can record.

-- Which build parsed this statement's rows: the short commit id its import session was staged
-- by (the running build, for paths that parse in the confirming request) -- the same value
-- import sessions and jobs already record. Null for statements confirmed before this
-- column existed, which a refresh treats as "parsed by an older build".
ALTER TABLE statement_imports ADD COLUMN parser_version VARCHAR(40);

-- The rows the user left out on the review screen: unticked by hand, or left unticked after the
-- import flagged them as likely duplicates of transactions already on the books. Until now
-- confirm only counted them (transactions_skipped). A refresh re-reads the statement and must not
-- bring these back as "new": without this table it cannot tell a row the user excluded from one
-- an older parser missed. Holds the statement's own facts for the row (the same four fields
-- ConfirmedRowIntegrity locks) plus its position, so a refresh can recognise it again.
--
-- Deleted explicitly -- by StatementImportService.delete with its statement, and by the account
-- purge -- because statement_imports rows are soft-deleted and anonymised, never hard-deleted, so
-- no cascade would ever fire.
CREATE TABLE statement_import_excluded_rows (
    id                   UUID PRIMARY KEY,
    statement_import_id  UUID NOT NULL REFERENCES statement_imports(id),
    user_id              UUID NOT NULL,
    row_position         INTEGER,
    txn_date             DATE NOT NULL,
    description          VARCHAR(500),
    amount               NUMERIC(14,2) NOT NULL,
    txn_type             VARCHAR(10) NOT NULL,
    likely_duplicate     BOOLEAN NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_statement_import_excluded_rows_statement ON statement_import_excluded_rows(statement_import_id);
CREATE INDEX idx_statement_import_excluded_rows_user ON statement_import_excluded_rows(user_id);
