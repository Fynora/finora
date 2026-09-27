-- V233 (inflow kinds) gave inflow_kinds.user_id and sender_inflow_rules.user_id both a plain
-- REFERENCES users(id), defaulting to NO ACTION -- the same gap V106, V157 and V165 already fixed,
-- caught again by the e2e/tests/workflow/isolation.spec.ts "records which user_id foreign keys
-- still block account deletion" diagnostic (confirmed failing on the 2026-09-27 nightly full E2E
-- run).
--
-- Both tables are the user's own choices -- the kinds they named and which sender maps to which
-- kind -- not an audit trail that must outlive them. The account purge already deletes both
-- explicitly (AccountPurgeSweepService.purgeOne); this makes a raw DELETE FROM users agree with it.
-- The inflow_kind_id references into inflow_kinds (from sender_inflow_rules and transactions) stay
-- NO ACTION: deleting a user removes the referencing rows in the same statement.
ALTER TABLE inflow_kinds DROP CONSTRAINT inflow_kinds_user_id_fkey;
ALTER TABLE inflow_kinds ADD CONSTRAINT inflow_kinds_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE sender_inflow_rules DROP CONSTRAINT sender_inflow_rules_user_id_fkey;
ALTER TABLE sender_inflow_rules ADD CONSTRAINT sender_inflow_rules_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
