-- Undo of "what was this money?" gives back exactly the "Needs Review" flag the answer took away.
--
-- Saying what a credit was (InflowKindService.setChoice, for one payment or every payment from a
-- sender) clears its needs_category_review flag. Undo (clearChoice) used to set the flag on every
-- credit that was uncounted again, so a credit imported without the flag gained a "Needs Review"
-- badge from answer-then-Undo (break-test 2026-11-13). This column remembers which credits the
-- answer un-flagged; Undo re-flags only those.
--
-- Constant default: no table rewrite on PostgreSQL 11+. Existing rows start false, so an Undo of an
-- answer given before this migration re-flags nothing.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS review_cleared_by_inflow_answer BOOLEAN NOT NULL DEFAULT FALSE;
