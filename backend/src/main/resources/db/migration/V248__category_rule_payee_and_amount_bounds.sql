-- Payee rules and amount bounds, for the recurring-payment question
-- (docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md).
--
--   amount_min / amount_max: optional bounds, inclusive, checked in addition to a rule's own
--   condition. NUMERIC(14,2), the same as transactions.amount. Null on every existing rule, so
--   existing rules behave exactly as before.
--
--   field = 'PAYEE' (no schema change; the column is a plain VARCHAR): matched against the payee
--   label (CategoryRules.extractMerchantLabel), on money going out only -- enforced in
--   RuleEngineService, not here.
--
--   uq_category_rules_user_payee: one saved answer per user and payee, ignoring case, so a double
--   submit updates the answer instead of creating a second rule (RecurringAnswerService inserts
--   with ON CONFLICT against this index, then updates).
ALTER TABLE category_rules ADD COLUMN amount_min NUMERIC(14, 2);
ALTER TABLE category_rules ADD COLUMN amount_max NUMERIC(14, 2);

CREATE UNIQUE INDEX uq_category_rules_user_payee
    ON category_rules (user_id, lower(comparison_value))
    WHERE field = 'PAYEE' AND scope = 'USER';
