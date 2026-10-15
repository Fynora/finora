-- One decision per held import job about the automatic Claude fix (project 5 of
-- docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md; design in
-- docs/superpowers/specs/2026-10-15-claude-fix-job-design.md). Holds references, states and PR
-- numbers only: never statement text. One row per held job, so a second hold of the same job (a
-- worker retry, or a re-hold after a reprocess) starts nothing: the primary key refuses it. The row
-- goes with the job when the account is deleted.
CREATE TABLE claude_fix_jobs (
    job_id          UUID        PRIMARY KEY REFERENCES import_jobs(id) ON DELETE CASCADE,
    state           VARCHAR(16) NOT NULL,
    reason          VARCHAR(16),
    attempt         INT         NOT NULL DEFAULT 0,
    sent_commit_sha VARCHAR(64),
    public_pr       INT,
    private_pr      INT,
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT claude_fix_jobs_state_valid CHECK (state IN ('NOT_STARTED','SENT','PR_OPEN','REREADING','FIXED','GAVE_UP')),
    CONSTRAINT claude_fix_jobs_reason_valid CHECK (reason IS NULL OR reason IN ('OFF','CSV','DIFFERS','NOT_CHECKED',
        'REFUSED','CAP_REACHED','ERROR','RUN_FAILED','NO_PR','STILL_HELD','DECIDED')),
    CONSTRAINT claude_fix_jobs_attempt_valid CHECK (attempt BETWEEN 0 AND 2)
);
CREATE INDEX idx_claude_fix_jobs_open ON claude_fix_jobs (state) WHERE state IN ('SENT','PR_OPEN','REREADING');
CREATE INDEX idx_claude_fix_jobs_sent_at ON claude_fix_jobs (sent_at);

-- One row per calendar month (India time, 'YYYY-MM') in which the monthly cap was reached and the
-- Super Admins were emailed. Claimed with INSERT ... ON CONFLICT DO NOTHING, so the email goes once
-- a month even when two holds reach the cap at the same moment. The codebase has no key-value
-- settings table to keep this in.
CREATE TABLE claude_fix_cap_alerts (
    month       CHAR(7)     PRIMARY KEY,
    alerted_at  TIMESTAMPTZ NOT NULL
);
