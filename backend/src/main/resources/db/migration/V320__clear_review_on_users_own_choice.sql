-- V319 relabelled transactions filed from the user's own saved payee category: AI_FALLBACK at 30
-- became LEARNED_PATTERN at 100. It left needs_category_review as it was, so some of those rows still
-- wait in the user's review list as if Fynora had guessed. Today's code never flags such a row: a
-- learned answer is not an unconfirmed guess (CategorizationService.isUnconfirmedGuess), so
-- needsCategoryReview returns false for it on every path -- import confirm, statement refresh, the
-- suggestion re-check sweep and a hand-added row. This clears the flag on the same rows, so they
-- read the way a fresh import of them would.
--
-- Which rows: a learned row at 100 that is still flagged and was not set by hand. Outside V319's
-- relabel, nothing writes that combination for a payment out. A credit can carry it legitimately:
-- undoing a "what was it?" answer puts back the review flag that answer cleared
-- (InflowKindService), whatever the row's decision source, and the flag is what keeps that credit's
-- question asked. So only payments out (EXPENSE) are cleared; a credit keeps its flag.
--
-- updated_at is bumped so client caches that compare it pick the change up, as V319 did.
--
-- Safe to run again: a cleared row no longer matches.
UPDATE transactions
SET needs_category_review = FALSE,
    updated_at = now()
WHERE decision_source = 'LEARNED_PATTERN'
  AND decision_confidence = 100
  AND needs_category_review
  AND NOT category_manually_set
  AND txn_type = 'EXPENSE';
