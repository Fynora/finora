-- One row per time an Admin or Super Admin opened a held statement's real pages in the admin
-- portal (project 2 of docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md).
-- The row is what page fetches are checked against (one viewer, 30 minutes), and the opened_at
-- dates are what the customer is shown on their import detail page. The reason is kept here and
-- on the audit entry; this table's rows go with the import job when the account is deleted.
CREATE TABLE statement_original_views (
    id               UUID        PRIMARY KEY,
    job_id           UUID        NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    subject_user_id  UUID        NOT NULL,
    viewer_id        UUID        NOT NULL,
    queue            VARCHAR(16) NOT NULL,
    item_ref         VARCHAR(64) NOT NULL,
    format           VARCHAR(8)  NOT NULL,
    page_count       INT         NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT statement_original_views_queue_valid CHECK (queue IN ('IMPORT', 'TRUST')),
    CONSTRAINT statement_original_views_format_valid CHECK (format IN ('PDF', 'CSV')),
    CONSTRAINT statement_original_views_pages_valid CHECK (page_count >= 0)
);

CREATE INDEX idx_statement_original_views_job ON statement_original_views (job_id, opened_at);
