-- Gate 2b spec §6: the month-start statement nudge (StatementMonthStartNudge). PUSH only -- no EMAIL
-- rows, so a request for any other channel would dead-letter; the sender only ever asks for PUSH.
-- Copy approved by Sid on 2026-10-16; the two titles were new in the plan and shown to him in the PR.
-- No bank, account or amount anywhere: a push shows on the lock screen.
--
-- STATEMENT_MONTH_START_FIRST: the user has never imported a statement. {{month}} is the previous
-- month's full English name ("April" on 3 May).
-- STATEMENT_MONTH_START_NEXT: one of the user's accounts has had no newer statement for more than 33
-- days. No params.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'STATEMENT_MONTH_START_FIRST', 'PUSH',
     'Your {{month}} statement is out',
     'Your {{month}} statement is out. It''s a good one to start with, and takes a couple of minutes.'),
    (gen_random_uuid(), 'STATEMENT_MONTH_START_NEXT', 'PUSH',
     'A new statement',
     'A new statement has probably arrived for one of your accounts. Add it to keep your months complete.');
