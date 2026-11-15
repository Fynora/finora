-- A payment linked as a transfer between the user's own accounts stops waiting for a category.
--
-- Linking a row as a transfer (is_transfer) already takes it out of the totals (FlowClassifier),
-- but it kept needs_category_review, so Quick sort, the Needs review list and "Sort with AI" kept
-- asking about it and the Ledger badged it. Transaction.setTransfer now clears the flag when it
-- links a waiting row, and this column remembers that it did, so unlinking gives back exactly that
-- flag (as V317's review_cleared_by_inflow_answer does for a credit's answer). Spec:
-- docs/superpowers/specs/2026-11-15-card-bill-issuer-list-design.md.
--
-- Constant default: no table rewrite on PostgreSQL 11+.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS review_cleared_by_transfer BOOLEAN NOT NULL DEFAULT FALSE;

-- Rows linked before this change and still waiting: cleared the way a link now clears them, and
-- marked, so undoing those links later puts the flag back the same way. updated_at is bumped so
-- client caches that compare it pick the change up, as V319 and V320 did.
--
-- Safe to run again: a cleared row no longer matches.
UPDATE transactions
SET needs_category_review = FALSE,
    review_cleared_by_transfer = TRUE,
    updated_at = now()
WHERE is_transfer
  AND needs_category_review
  AND deleted_at IS NULL;
