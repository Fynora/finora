-- Carries NotificationRequest.params() through to delivery time (premium email redesign follow-up,
-- 2026-09-11): notifications.title/message are already-rendered strings, so a channel provider
-- claiming a row for delivery has no way to recover the original structured params that produced
-- them. EmailNotificationProvider needs exactly this for IMPORT_STATEMENT_READY's "Review
-- Statement" CTA -- the import job id was never part of any {{placeholder}} in the rendered copy,
-- only of the deep link a rich HTML email builds around it.
--
-- Nullable JSONB, the same shape AuditLog.metadata (V89) already uses for an analogous problem:
-- most notification types need nothing here, and NULL/empty is the correct default for all of
-- them, not just a migration-time placeholder.
ALTER TABLE notifications
    ADD COLUMN params JSONB;

COMMENT ON COLUMN notifications.params IS
    'The caller-supplied NotificationRequest.params() map, stored verbatim as JSON so a channel '
    'provider can recover structured data lost once title/message are rendered strings -- e.g. '
    'EmailNotificationProvider reads bank/jobId back out of this for IMPORT_STATEMENT_READY''s '
    '"Review Statement" deep link. NULL for any notification whose provider needs nothing beyond '
    'title/message.';
