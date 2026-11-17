-- Why a statement that read cleanly was never imported. V267 records one way a staged statement
-- ends unconfirmed (expired_unconfirmed_at, the 48-hour sweep); these record the others, so the
-- question "did the Free plan's one-month limit stop them?" is answered from the evidence row
-- instead of from logs, which keep nothing for a refused (4xx) request.
--
-- discarded_unconfirmed_at: the staged statement was deleted without being confirmed. The session
--   row is deleted with it; this stamp stays.
-- discarded_by: who deleted it, set with discarded_unconfirmed_at.
--   CUSTOMER: the customer chose to (web, or the phone's "Discard this import?").
--   PHONE_MULTI_ACCOUNT: the phone app deleted it itself, because it cannot import a statement
--   covering more than one account. Not the customer abandoning a statement they could import.
-- free_limit_shown_at: the review screen was given the Free plan's one-month notice for this
--   statement (first time only). Not stamped while the statement is held for the team's check:
--   neither app opens the review screen, so the notice is not shown then.
-- free_limit_refused_at: a confirm of this statement was refused for that limit (first time only).
--
-- Each is a later observation about the same upload, written once where still null. No file
-- name, date range, amount or other statement content is stored.
ALTER TABLE statement_analysis_sessions ADD COLUMN discarded_unconfirmed_at TIMESTAMPTZ;
ALTER TABLE statement_analysis_sessions ADD COLUMN discarded_by VARCHAR(24);
ALTER TABLE statement_analysis_sessions ADD CONSTRAINT chk_statement_analysis_discarded_by
    CHECK (discarded_by IS NULL OR discarded_by IN ('CUSTOMER', 'PHONE_MULTI_ACCOUNT'));
ALTER TABLE statement_analysis_sessions ADD COLUMN free_limit_shown_at TIMESTAMPTZ;
ALTER TABLE statement_analysis_sessions ADD COLUMN free_limit_refused_at TIMESTAMPTZ;
