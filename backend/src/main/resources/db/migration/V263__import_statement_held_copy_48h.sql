-- A held statement now tells the user what is happening and when.
--
-- V155's copy said "we need to run some additional checks... we'll notify you once it's ready"
-- and named no time, on purpose: triage was manual and volume-dependent. In October 2026 three
-- testers waited 3-6 days on that message, read it as the app having done nothing, and gave up.
-- The new copy says a person is checking the statement by hand and gives a 48-hour limit, which is
-- the owner's commitment for how fast the review queue is worked. It makes the same promise as
-- the web and mobile held screens (HELD_LABEL / HELD_DETAIL in frontend/src/lib/importJob.ts and
-- mobile/src/lib/importJob.ts); if the 48 hours changes, all of them change together.
--
-- It still never suggests the statement itself is in doubt: "read correctly" puts the question on
-- our extraction, not on the user's document (see importJob.ts's detail() for that rule).
--
-- The ready and rejected emails follow up on this one, so their opening line now says what the
-- user was told: "double-checking", not "additional checks". Their push rows never mentioned the
-- checks and are unchanged.
--
-- Retire-and-insert, not UPDATE: all three types have rendered to real users, so the old rows stay
-- (inactive) to keep past renders attributable to the copy they used -- see V127's comment on
-- notification_templates.active, and V230 for the same pattern.
--
-- The dashes in customer-facing strings are real em dashes (U+2014), as in V136.

UPDATE notification_templates
   SET active = false
 WHERE active = true
   AND (type = 'IMPORT_STATEMENT_HELD'
        OR (type IN ('IMPORT_STATEMENT_READY', 'IMPORT_STATEMENT_REJECTED') AND channel = 'EMAIL'));

INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_HELD', 'EMAIL',
     'We''re double-checking your statement',
     'We''re double-checking your statement by hand to make sure every transaction is read '
     'correctly. This takes up to 48 hours, and we''ll notify you as soon as it''s done. There''s '
     'nothing you need to do, and you can keep using Fynora in the meantime.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_HELD', 'PUSH',
     'Checking your statement',
     'We''re double-checking it by hand and will notify you within 48 hours.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_READY', 'EMAIL',
     'Your {{bank}} statement is ready',
     'Good news — we''ve finished double-checking your {{bank}} statement. It''s ready for you to '
     'review and import in Fynora.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_REJECTED', 'EMAIL',
     'Your statement wasn''t imported',
     'We''ve finished double-checking your statement. To keep your accounts accurate, we haven''t '
     'imported it: we couldn''t confirm that every transaction in it was read correctly. Nothing '
     'was added to your accounts. If we''re able to import it later, we''ll let you know.');
