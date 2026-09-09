-- Sticky "not a transfer" marker, same shape and same reason as notDuplicateConfirmedAt (V65):
-- ReconciliationService's transfer pass groups on amount + opposite direction + a date window and
-- cannot distinguish "these really are the same money moving between two of your accounts" from
-- "two unrelated transactions that happen to coincidentally match" -- exactly the false-positive
-- class notDuplicateConfirmedAt already exists to let a user correct for duplicates. Without a
-- sticky flag, an explicit "unmark as transfer" action would only last until the next
-- reconcileForUser() run (which fires after every create/edit/delete/import for this user) found
-- the same pair still superficially matches and silently re-paired them.
ALTER TABLE transactions ADD COLUMN transfer_rejected_at TIMESTAMPTZ;
