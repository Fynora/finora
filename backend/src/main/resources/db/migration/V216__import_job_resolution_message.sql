-- What an admin tells the user when they resolve a held import, and the copy that delivers it.
--
-- Until now, resolving a held import only wrote an internal note to the audit log. The user had been
-- told at hold time that we were running additional checks and that no action was needed, and was
-- then left with a bare "couldn't finish" and no explanation. Resolve now requires a message for the
-- user (AdminHeldImportService.resolve), delivered by push and email through the notification
-- outbox and kept on the job so the failed-import card can show the same words in the app.
ALTER TABLE import_jobs ADD COLUMN resolution_message VARCHAR(500);

COMMENT ON COLUMN import_jobs.resolution_message IS
    'The message an admin gave the user when resolving a held import, exactly as sent by email and '
    'push. User-facing in full -- never internal notes, which go on the audit entry. Set only by '
    'ImportJob.resolveWithoutFix; NULL for every job nobody resolved.';

-- The body is the admin's message itself, substituted for {{message}}. Operator-authored free text,
-- so the email path escapes it as text (EmailLayout.wrap) and the service caps it at 500 characters,
-- well inside body_template's 2000 and the notifications table's own column widths.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_RESOLVED', 'EMAIL',
     'An update on your statement',
     '{{message}}'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_RESOLVED', 'PUSH',
     'Update on your statement',
     '{{message}}');
