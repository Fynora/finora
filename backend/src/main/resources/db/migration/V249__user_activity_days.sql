-- One row per (user, calendar day) on which that user made at least one authenticated API request.
-- Answers "on how many days a month does a user actually open Fynora" -- a question nothing could
-- answer before: Sentry is configured for crashes only (no sessions, no traces), refresh_tokens keeps
-- only the latest last_seen_at per session and deletes expired rows, and audit_logs records specific
-- actions, not app opens. Import days alone are already answerable from import_jobs.created_at; this
-- table is the other half -- every day the app was used, importing or not.
--
-- Deliberately a day, not a timestamp or a request log: the question is "how many days", and a row
-- per request would be both far larger and a far more detailed record of someone's behaviour than
-- that question needs. Written by UserActivityInterceptor, at most once per user per day per backend
-- instance; the UNIQUE constraint makes concurrent writes from several instances collapse to one row.
--
-- activity_date is the calendar day in the platform reporting zone (app.platform.reporting-zone,
-- default Asia/Kolkata), the same zone the admin dashboard's "today" tiles use -- not UTC, which
-- would split an Indian evening across two days at 05:30 IST.
--
-- Example: days active per user in September 2026.
--   SELECT user_id, count(*) AS active_days
--   FROM user_activity_days
--   WHERE activity_date >= DATE '2026-09-01' AND activity_date < DATE '2026-10-01'
--   GROUP BY user_id;
--
-- ON DELETE CASCADE from the start (same reasoning as V171), but AccountPurgeSweepService still
-- deletes these rows explicitly: it anonymizes the users row rather than deleting it, so the cascade
-- never fires on that path.
CREATE TABLE user_activity_days (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, activity_date)
);
-- The UNIQUE index already leads with user_id, which covers per-user lookups and the purge delete.
-- This one is for the platform-wide "who was active in this date range" query above.
CREATE INDEX idx_user_activity_days_activity_date ON user_activity_days(activity_date);
