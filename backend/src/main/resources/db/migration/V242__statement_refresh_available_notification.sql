-- Statement refresh, step 5: telling users an improved parser would change some of their
-- statements. Sent by StatementRefreshNotifier when the background dry run finds a statement a
-- refresh would change -- at most once a week per user, and only while refreshing is switched on.
-- No parameters: the count is not known when the first changed statement is found, and the app
-- shows the full list.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'STATEMENT_REFRESH_AVAILABLE', 'EMAIL',
     'Some of your statements can be updated',
     'We''ve improved how Fynora reads bank statements, and some of the statements you imported would read more accurately now. Open Fynora and tap Update on your Statements page -- nothing is re-uploaded, and you''ll see exactly what changed.'),
    (gen_random_uuid(), 'STATEMENT_REFRESH_AVAILABLE', 'PUSH',
     'Statements can be updated',
     'We read some of your statements more accurately now. Tap to update them.');
