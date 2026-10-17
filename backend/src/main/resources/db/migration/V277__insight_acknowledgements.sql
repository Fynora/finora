-- "Got it" on an Insights row (docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md):
-- the app hides that row for good, on every device. A row has no persisted identity of its own --
-- it is recomputed from the user's transactions on every request -- so it is named by its kind and
-- a key built from what it describes:
--   CATEGORY_HIGH           "<category name>|<yyyy-MM>"   (a new month running high shows again)
--   AMOUNT_CHANGED          "<merchant, lower-cased>|<latest payment date>"
--   CARD_STATEMENT_MISSING  "<yyyy-MM>"
-- The server never filters /insights or /recurring by these; only the app reads them.
--
-- ON DELETE CASCADE as recurring_dismissals: this is the user's own preference, not an audit trail.
-- (The account purge anonymizes the users row rather than deleting it, so it also deletes these
-- rows explicitly -- see AccountPurgeSweepService.)
CREATE TABLE insight_acknowledgements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(40) NOT NULL CHECK (kind IN ('CATEGORY_HIGH', 'AMOUNT_CHANGED', 'CARD_STATEMENT_MISSING')),
    item_key VARCHAR(300) NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, item_key)
);
CREATE INDEX idx_insight_acknowledgements_user_id ON insight_acknowledgements(user_id);
