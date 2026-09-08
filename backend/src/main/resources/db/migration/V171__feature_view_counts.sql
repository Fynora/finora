-- Per-user, per-feature view counters -- backs the Billing page's "Smart Insights" usage tile,
-- which previously showed a hardcoded "142" with no real tracking behind it (see Billing.tsx's
-- UsageTile isStatic comment). One row per (user, feature), incremented on every real view rather
-- than logging one row per view -- the tile only ever needs a running count, not a history.
--
-- ON DELETE CASCADE from the start (V162's user_checklist_events had to be patched for this later
-- in V165 after the same "records which user_id foreign keys still block account deletion" e2e
-- diagnostic caught it).
CREATE TABLE feature_view_counts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature VARCHAR(30) NOT NULL,
    view_count INTEGER NOT NULL DEFAULT 0,
    last_viewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, feature)
);
CREATE INDEX idx_feature_view_counts_user_id ON feature_view_counts(user_id);
