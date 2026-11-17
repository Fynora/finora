-- When a staged statement was imported as just its latest month: the Free plan's "import only the
-- latest month" (Sid, 2026-11-17), on the upload record (statement_analysis_sessions).
--
-- confirmed_at (V327) says a statement was imported; free_limit_shown_at (V326) says the Free
-- notice was shown. Together they could not tell an upgrade-then-import from a latest-month import,
-- so the admin weekly view could not say how often the offer is used. Written with confirmed_at,
-- after the confirm commits; once, where still null. A time only.
ALTER TABLE statement_analysis_sessions ADD COLUMN latest_month_imported_at TIMESTAMPTZ;
