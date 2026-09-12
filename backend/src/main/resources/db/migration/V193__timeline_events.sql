CREATE TABLE timeline_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    bucket        VARCHAR(20) NOT NULL,
    importance    VARCHAR(10) NOT NULL,
    permanent     BOOLEAN NOT NULL,
    reference_id  UUID,
    title         TEXT NOT NULL,
    detail        TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_timeline_events_user_occurred ON timeline_events(user_id, occurred_at DESC);

-- Enforces "fires at most once" for account-wide singleton milestones (reference_id IS NULL,
-- e.g. FIRST_GOAL_CREATED) and "fires at most once per referenced entity" for per-goal
-- milestones (reference_id = the goal's id, e.g. GOAL_COMPLETED). Two partial indexes because
-- Postgres treats every NULL as distinct under a plain UNIQUE(user_id, event_type,
-- reference_id) -- that alone would never block a duplicate singleton insert.
CREATE UNIQUE INDEX ux_timeline_events_singleton ON timeline_events(user_id, event_type)
    WHERE reference_id IS NULL;
CREATE UNIQUE INDEX ux_timeline_events_per_reference ON timeline_events(user_id, event_type, reference_id)
    WHERE reference_id IS NOT NULL;
