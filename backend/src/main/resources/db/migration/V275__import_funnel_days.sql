-- Gate 2a spec §3: the import-funnel record. One row per (user, event, calendar day) on which the user
-- reached one of three moments on the way to a first statement:
--
--   IMPORT_SCREEN_OPENED  the import screen was shown (web /app/import, mobile Import tab focused)
--   FILE_CHOSEN           a file was picked, before any upload -- including one the app then refuses
--   REVIEW_OPENED         the review of staged rows was shown, from any path
--
-- With statement_analysis_sessions and import_jobs, these say where a user stopped: never opened the
-- import screen, opened it and chose nothing, chose a file and was refused, and so on. Nothing
-- recorded before this table could tell those apart.
--
-- Deliberately a day, not a timestamp, for the same reason as user_activity_days (V249): the privacy
-- policy promises the date only, never the time, so there is no created_at. Nothing about the file
-- (name, size, type) or the screen is stored.
--
-- funnel_date is the calendar day in app.platform.reporting-zone (default Asia/Kolkata), as for
-- user_activity_days. The UNIQUE constraint collapses repeats from retries or several instances.
--
-- ON DELETE CASCADE, but AccountPurgeSweepService deletes these rows explicitly: it anonymizes the
-- users row rather than deleting it, so the cascade never fires on that path.
CREATE TABLE import_funnel_days (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event VARCHAR(32) NOT NULL
        CONSTRAINT import_funnel_days_event_valid
        CHECK (event IN ('IMPORT_SCREEN_OPENED', 'FILE_CHOSEN', 'REVIEW_OPENED')),
    funnel_date DATE NOT NULL,
    UNIQUE (user_id, event, funnel_date)
);
-- The UNIQUE index leads with user_id, which covers per-user lookups and the purge delete. This one
-- is for platform-wide date-range counts (the activation page).
CREATE INDEX idx_import_funnel_days_funnel_date ON import_funnel_days(funnel_date);
