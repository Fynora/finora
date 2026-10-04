-- How the user kept track of their spending before Fynora, answered once on a required setup
-- question ("How do you keep track of your spending today?"). One answer per user, so it lives on
-- users rather than in a table of its own. Null until answered: every account that existed before
-- this migration is asked the next time it signs in, and onboarding cannot finish without it.
--
-- The values are SpendingTrackingMethod's names; the CHECK keeps anything else out even if a
-- future writer skips the enum.
ALTER TABLE users
    ADD COLUMN spending_tracking_method VARCHAR(20),
    ADD COLUMN spending_tracking_answered_at TIMESTAMPTZ,
    ADD CONSTRAINT users_spending_tracking_method_check CHECK (spending_tracking_method IN (
        'NOT_TRACKED', 'IN_MY_HEAD', 'PAPER', 'SPREADSHEET', 'EXPENSE_APP', 'BANK_APP', 'OTHER')),
    ADD CONSTRAINT users_spending_tracking_answered_check CHECK (
        (spending_tracking_method IS NULL) = (spending_tracking_answered_at IS NULL));
