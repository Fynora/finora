-- Moves IMPORT_STATEMENT_READY/IMPORT_STATEMENT_HELD's EMAIL copy off the notification_templates
-- system (premium email redesign, 2026-09-11). ResendEmailProvider now sends both emails directly
-- (sendStatementReadyEmail/sendStatementHeldEmail), through StatementStatusNotifier, sharing the
-- same branded EmailLayout wrapper, CTA button, and legal footer every other transactional email
-- uses -- a DB-stored plain-{{placeholder}} row had no way to carry that HTML alongside copy that
-- stays reviewable in one place.
--
-- PUSH rows for both types are untouched: push has no HTML-wrapper concern and stays on the
-- existing outbox/DB-template path.
--
-- UPDATE (deactivate), not DELETE, per idx_notification_templates_active's own column comment
-- (V127): both rows have real production renders behind them -- these two email types have been
-- live since V136/V155 -- unlike V136's own UPDATE-in-place of these same rows, which had zero
-- past renders to protect at the time.
UPDATE notification_templates
   SET active = false
 WHERE type IN ('IMPORT_STATEMENT_READY', 'IMPORT_STATEMENT_HELD')
   AND channel = 'EMAIL'
   AND active = true;
