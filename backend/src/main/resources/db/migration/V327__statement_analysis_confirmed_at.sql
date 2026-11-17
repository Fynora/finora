-- When a staged statement was imported, and why a discarded one was, on its upload record
-- (statement_analysis_sessions).
--
-- V326 records the ways a staged statement ends without being imported (discarded, by whom, and
-- the Free plan limit), beside V267's expiry. The session row says a statement was confirmed, but
-- the 48-hour sweep deletes confirmed sessions as well, so after two days nothing tells an imported
-- statement from one nothing was recorded about. This does, for the weekly "Staged, but was it
-- imported?" count on the admin Reading quality page.
--
-- confirmed_at is written after the confirm commits (single- and multi-account), so a confirm that
-- rolls back leaves none; once, where still null. A time only.
--
-- discard_reason: why the customer discarded it, when they chose one of the optional one-tap
-- reasons on the discard dialog (Sid, 2026-11-17). Set with discarded_unconfirmed_at, only for a
-- discard by the customer. A fixed choice, never free text.
ALTER TABLE statement_analysis_sessions ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE statement_analysis_sessions ADD COLUMN discard_reason VARCHAR(24);
ALTER TABLE statement_analysis_sessions ADD CONSTRAINT chk_statement_analysis_discard_reason
    CHECK (discard_reason IS NULL OR discard_reason IN ('WRONG_FILE', 'TOO_MANY_MONTHS', 'LOOKS_WRONG', 'LATER', 'OTHER'));
