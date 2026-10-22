-- An admin applied a statement correction for the user (AdminStatementCorrectionService): the same
-- refresh the user's own "Update" button runs, started from the admin portal with a recorded reason.
-- {{message}} is built from the correction's counts and names the account; it never carries an
-- amount or a transaction's text.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'STATEMENT_CORRECTED_BY_SUPPORT', 'EMAIL',
     'We corrected one of your statements',
     '{{message}}'),
    (gen_random_uuid(), 'STATEMENT_CORRECTED_BY_SUPPORT', 'PUSH',
     'Statement corrected',
     '{{message}}');
