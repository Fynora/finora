-- Background jobs health (docs/superpowers/specs/2026-11-13-background-jobs-health-design.md).
--
-- The backend's scheduled jobs recorded nothing about their runs, and most of them catch one item's
-- failure and carry on, so a broken job was visible only in logs. These two tables back the admin
-- portal's Background jobs page and its alert emails.
--
-- background_job_status: one row per job (key = SimpleClassName.methodName), updated in place on
-- every run, so a job that runs every 30 seconds costs one small UPDATE, never a row per run.
-- The email columns let one backend instance claim each alert with a conditional UPDATE, so two
-- instances never send the same email twice.
CREATE TABLE background_job_status (
    job_key              TEXT        PRIMARY KEY,
    last_started_at      TIMESTAMPTZ,
    last_finished_at     TIMESTAMPTZ,
    last_outcome         TEXT,
    last_failed_items    INTEGER     NOT NULL DEFAULT 0,
    last_handled         INTEGER     NOT NULL DEFAULT 0,
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    broken_since         TIMESTAMPTZ,
    last_email_kind      TEXT,
    last_email_at        TIMESTAMPTZ,
    last_broken_email_at TIMESTAMPTZ,
    last_instance        TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT background_job_status_outcome_check
        CHECK (last_outcome IS NULL OR last_outcome IN ('OK', 'PARTLY_FAILED', 'CRASHED', 'OFF')),
    CONSTRAINT background_job_status_email_kind_check
        CHECK (last_email_kind IS NULL OR last_email_kind IN ('BROKEN', 'STILL_BROKEN', 'WORKING_AGAIN'))
);

-- background_job_failures: one row per run that crashed or partly failed, kept 30 days. The error
-- is stored as its type and code location only (for example "DataIntegrityViolationException at
-- AccountPurgeSweepService:212"), never its message: production has real customers, and a message
-- can carry an email address, a name or an account id.
CREATE TABLE background_job_failures (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_key       TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    outcome       TEXT        NOT NULL,
    failed_items  INTEGER     NOT NULL DEFAULT 0,
    error_summary TEXT,
    CONSTRAINT background_job_failures_outcome_check CHECK (outcome IN ('PARTLY_FAILED', 'CRASHED'))
);

CREATE INDEX idx_background_job_failures_job_time ON background_job_failures (job_key, occurred_at DESC);
CREATE INDEX idx_background_job_failures_time ON background_job_failures (occurred_at);
