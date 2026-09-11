-- Backs StatementStatusNotifier's own no-double-send guarantee for the statement-held email
-- (premium email redesign, 2026-09-11), which moved off notification_templates/the outbox and so
-- lost the outbox's own notification_key-based idempotency for its email leg. The outbox's
-- guarantee was real and tested (ImportJobWorkerTest.aJobHeldAgainAfterAFailedReprocessReusesThe
-- SameNotificationKey): a job reprocessed after holding, that fails the same way and holds again,
-- must not receive a second "we're checking your statement" email. This column replaces that
-- guarantee at the entity level instead of dropping it.
--
-- Mirrors was_held_for_review (V134): set once, never cleared, including by a reprocess -- a job
-- that already got the email once must not get it again no matter how many more times it holds.
--
-- DEFAULT NULL leaves every existing row unsent, which is correct: no job predating this migration
-- has had this email sent through the new direct path (it went through the outbox instead, whose
-- own history is unaffected by this migration).
ALTER TABLE import_jobs
    ADD COLUMN statement_held_email_sent_at TIMESTAMPTZ;

COMMENT ON COLUMN import_jobs.statement_held_email_sent_at IS
    'When the statement-held email was sent for this job, or NULL if never. Set once via '
    'ImportJob.markStatementHeldEmailSent and never cleared -- see V194 for why this exists '
    'outside the notification outbox.';
