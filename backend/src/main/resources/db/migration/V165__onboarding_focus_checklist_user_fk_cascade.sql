-- V162 (first-login onboarding flow) gave user_financial_focus.user_id and
-- user_checklist_events.user_id both a plain REFERENCES users(id), defaulting to NO ACTION --
-- the same gap V106 and V157 already fixed for payments/subscriptions/referral_codes/
-- wallet_ledger and subscription_orders, caught again by the same
-- e2e/tests/workflow/isolation.spec.ts "records which user_id foreign keys still block account
-- deletion" diagnostic (confirmed failing on the 2026-09-07 nightly full E2E run).
--
-- Both tables are the user's own onboarding progress -- a financial-focus selection and a record
-- of which getting-started checklist items they've touched -- not an audit trail that must
-- outlive them the way password_history does. Nobody needs to know a deleted user's answer to
-- "what's your financial focus" or that they once viewed Insights.
ALTER TABLE user_financial_focus DROP CONSTRAINT user_financial_focus_user_id_fkey;
ALTER TABLE user_financial_focus ADD CONSTRAINT user_financial_focus_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_checklist_events DROP CONSTRAINT user_checklist_events_user_id_fkey;
ALTER TABLE user_checklist_events ADD CONSTRAINT user_checklist_events_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
