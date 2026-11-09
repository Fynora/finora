-- Privacy workstream 10: a suspended customer is told why, can appeal, and can still download their
-- data. Until now a suspension recorded nothing but the status itself.
--
-- suspension_reason: one of SuspensionReason's names (TERMS_VIOLATION, FRAUD, SECURITY_RISK), the
--   grounds the Terms of Service give for a suspension. Enum-backed VARCHAR with no CHECK
--   constraint, the majority pattern in this schema (see V145's note on support_tickets.category).
-- suspension_message: an optional explanation the admin writes for the customer. It is shown to them
--   on the suspended screen and in the notice email, so it is customer-facing text, not a staff note.
-- suspended_at: when the current suspension began. Cleared, with the other two, on reactivation.
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspension_message VARCHAR(1000);
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;

-- Accounts suspended before this migration have no recorded reason. Their start time is the latest
-- ACCOUNT_SUSPENDED audit row written against them (AdminUserService.suspend records one per
-- suspension, entity_id = the suspended user). Their reason stays null: an admin records it by
-- suspending the account again, which sends the notice they never received.
UPDATE users u
SET suspended_at = (
    SELECT max(a.created_at) FROM audit_logs a
    WHERE a.action = 'ACCOUNT_SUSPENDED' AND a.entity_id = u.id
)
WHERE u.status = 'SUSPENDED' AND u.suspended_at IS NULL;
