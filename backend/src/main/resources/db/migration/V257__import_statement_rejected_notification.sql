-- Copy for NotificationType.IMPORT_STATEMENT_REJECTED: a statement held for trust review that a
-- reviewer rejected.
--
-- The held email tells the user "We'll notify you once it's ready". Until now a rejection sent
-- nothing, so that promise was kept only when the answer was yes: the user had to open the app to
-- find the import had failed. The parser-gap hold already tells the user when it is closed without
-- a fix (V216); this is the trust-review counterpart.
--
-- The EMAIL row is what the outbox renders, but the email itself is built as branded HTML by
-- EmailProvider.sendStatementRejectedEmail, like the held and ready emails -- its subject and
-- heading match this row's title.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_REJECTED', 'EMAIL',
     'Your statement wasn''t imported',
     'We''ve finished the additional checks on your statement. To keep your accounts accurate, we '
     'haven''t imported it: we couldn''t confirm that every transaction in it was read correctly. '
     'Nothing was added to your accounts. If we''re able to import it later, we''ll let you know.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_REJECTED', 'PUSH',
     'Statement not imported',
     'We couldn''t confirm every transaction was read correctly, so nothing was added to your accounts.');
