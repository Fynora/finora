-- When an urgent layout-review alert was last sent for a layout.
--
-- Until now an alert went out only when a layout's review flag was first raised, so a layout that
-- was already flagged for something small and then failed the balance check badly sent nothing.
-- An urgent alert is now also sent when the layout has had none since it was last flagged:
-- last_immediate_alert_at < review_flagged_at (or NULL). Compared against review_flagged_at rather
-- than reset anywhere, so clearing and re-raising the flag needs no change to the paths that do it.
ALTER TABLE layout_registry ADD COLUMN last_immediate_alert_at TIMESTAMPTZ;

-- A layout flagged before this migration already had its alert when it was flagged. Treat it as
-- alerted, so deploying this does not send a second email for every layout currently in the queue.
UPDATE layout_registry SET last_immediate_alert_at = review_flagged_at
WHERE needs_review AND review_flagged_at IS NOT NULL;
