-- AI sorting after an import (docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md).
--
-- A customer who said no to AI is asked one more time, on an import's Done step, and never again:
-- this is when they were asked (and answered, either way). NULL = not asked again yet.
ALTER TABLE users ADD COLUMN ai_import_question_at TIMESTAMPTZ;

-- One row per tap on "Sort with AI": the card's progress, a guard against a second run for the same
-- import(s), and the record the feature's effect is measured from. Counts only; no payment text.
CREATE TABLE ai_sort_runs (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The confirmed statement import ids the card was shown for, sorted and comma-joined.
    imports_key     TEXT        NOT NULL,
    status          VARCHAR(16) NOT NULL
        CHECK (status IN ('RUNNING', 'DONE', 'STOPPED_LIMIT', 'STOPPED_AI_OFF', 'STOPPED')),
    payments_total  INT         NOT NULL CHECK (payments_total >= 0),
    payments_done   INT         NOT NULL DEFAULT 0 CHECK (payments_done >= 0),
    payments_sorted INT         NOT NULL DEFAULT 0 CHECK (payments_sorted >= 0),
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT uq_ai_sort_runs_user_imports UNIQUE (user_id, imports_key)
);
