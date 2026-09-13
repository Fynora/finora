-- Plan 6, Track B. Flags a transaction whose upstream (Account Aggregator) value was detected as
-- changed or missing on a later re-fetch, without ever overwriting the row itself -- see
-- docs/superpowers/plans/2026-09-13-account-aggregator-bank-side-mutation-handling.md. Distinct
-- from needs_category_review: that flag means "the category is an unconfirmed guess"; this one
-- means "the bank's own reported values may have changed since this row was created." Deliberately
-- NOT NULL DEFAULT FALSE, same as every other review-flag column in this table, so every existing
-- row backfills to "no correction pending" with no ambiguity.
ALTER TABLE transactions ADD COLUMN pending_bank_correction BOOLEAN NOT NULL DEFAULT FALSE;
