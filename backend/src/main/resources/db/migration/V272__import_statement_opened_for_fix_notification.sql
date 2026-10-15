-- Seeds copy for NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX: the customer is told, after the
-- fact, that an administrator opened the statement they uploaded to fix a reading problem
-- (project 2 of docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md).
-- It names the date and the purpose, never who. Consent before opening is parked by the owner
-- until the user base grows; this notice is the interim. {{date}} is "15 Oct 2026", India time.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_OPENED_FOR_FIX', 'EMAIL',
     'We opened your statement to fix a reading problem',
     'On {{date}}, a member of our team opened the statement you uploaded so we could fix a problem '
     'reading it. Only administrators can do this, and every opening is recorded. Nothing in your '
     'accounts was changed by it.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_OPENED_FOR_FIX', 'PUSH',
     'Your statement was opened',
     'On {{date}} our team opened it to fix a reading problem.');
