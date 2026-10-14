-- A reminder before a read-but-unconfirmed statement is removed.
--
-- A staged import session lives for ImportSessionService.SESSION_TTL (48 hours) after it was read,
-- or after a trust review released it, and is then deleted by the sweep. Until now nothing told
-- the user beforehand: in October 2026 a tester's statement was released from review, never
-- confirmed, and removed two days later with no word to them. UnconfirmedImportReminder now sends
-- one push and one email when less than a day is left -- one per user per check, however many of
-- their statements are waiting -- and points them at the Import screen's unfinished-imports list.
--
-- confirm_reminder_expires_at records the expires_at a reminder was sent for. Comparing the two,
-- rather than keeping a flag, gives a session whose clock is restarted (an approved trust review,
-- ImportSessionService.renewExpiry) one reminder for its new window, and never two for the same.
--
-- {{waiting}} is "Your statement is" or "3 of your statements are" -- see
-- StatementStatusNotifier.notifyConfirmReminder. "48 hours" is SESSION_TTL; if the TTL changes,
-- this copy changes with it. The dash is a real em dash (U+2014), as in V136.

ALTER TABLE import_sessions ADD COLUMN confirm_reminder_expires_at TIMESTAMPTZ;

INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_CONFIRM_REMINDER', 'EMAIL',
     '{{waiting}} waiting to be confirmed',
     '{{waiting}} ready and waiting for you. Transactions are added to your accounts only when '
     'you review and confirm them, and statements that aren''t confirmed are removed 48 hours after '
     'they''re ready. There''s less than a day left — confirm in Fynora, or upload again later.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_CONFIRM_REMINDER', 'PUSH',
     'Waiting to be confirmed',
     '{{waiting}} ready for you to confirm. Confirm within a day, or you''ll need to upload again.');
