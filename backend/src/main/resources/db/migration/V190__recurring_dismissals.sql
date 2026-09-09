-- Lets a user dismiss a wrongly-detected recurring group (a one-off large purchase RecurringService
-- mistook for a subscription, e.g.) -- there was previously no mutation endpoint at all for
-- recurring detection, only GET /api/v1/recurring. Keyed on the same `merchant` string
-- RecurringService.detectForUser already groups by (RecurringDto.merchant) -- there is no other
-- stable identity for a detected group, since it's recomputed fresh on every call, not a persisted
-- entity of its own.
--
-- ON DELETE CASCADE from the start (not V165's original NO ACTION mistake, fixed after the fact
-- for other tables) -- this is the user's own preference, not an audit trail that must outlive them.
CREATE TABLE recurring_dismissals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant VARCHAR(255) NOT NULL,
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, merchant)
);
CREATE INDEX idx_recurring_dismissals_user_id ON recurring_dismissals(user_id);
