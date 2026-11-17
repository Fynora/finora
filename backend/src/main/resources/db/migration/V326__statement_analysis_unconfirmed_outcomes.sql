-- Why a statement that read cleanly was never imported. V267 records one way a staged statement
-- ends unconfirmed (expired_unconfirmed_at, the 48-hour sweep); these record the others, so the
-- question "did the Free plan's one-month limit stop them?" is answered from the evidence row
-- instead of from logs, which keep nothing for a refused (4xx) request.
--
-- discarded_unconfirmed_at: the customer deleted the staged statement without confirming it. The
--   session row is deleted with it; this stamp stays.
-- free_limit_shown_at: the review screen was given the Free plan's one-month notice for this
--   statement (first time only).
-- free_limit_refused_at: a confirm of this statement was refused for that limit (first time only).
--
-- Each is a later observation about the same upload, written once where still null. No file
-- name, date range, amount or other statement content is stored.
ALTER TABLE statement_analysis_sessions ADD COLUMN discarded_unconfirmed_at TIMESTAMPTZ;
ALTER TABLE statement_analysis_sessions ADD COLUMN free_limit_shown_at TIMESTAMPTZ;
ALTER TABLE statement_analysis_sessions ADD COLUMN free_limit_refused_at TIMESTAMPTZ;
