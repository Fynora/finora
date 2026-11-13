-- A saved payee category (user_merchant_category_resolution) now records who chose it.
--
-- The table has two writers: the user, whenever they confirm or correct a category
-- (UserMerchantCategoryResolutionService.pin, an unconditional upsert), and the AI fallback, after
-- the model answers for a payee (resolve(), insert-if-absent). Until now a row did not say which
-- one wrote it, and the categorization waterfall served every row back labelled AI_FALLBACK at
-- confidence 30 -- also the user's own earlier choice, on every import of the same payee. That
-- told the user "Fyn, Finora's AI assistant, suggested this category" about a category they had
-- picked themselves, and kept those rows out of every check that trusts the user's own choices
-- (FlowClassifier.categoryIsTheUsersChoice).
--
-- chosen_by_user is true for a row pin() wrote or overwrote, false for one the model wrote.
--
-- Existing rows. A row the model wrote can only exist for a user who has at least one
-- RESOLVE_CATEGORY call in ai_audit_log: resolve() writes that audit row before it saves the
-- answer, the tool name has not changed since the table was created (V214), and ai_audit_log rows
-- are deleted only by the account purge, which deletes the user's resolutions too. carryToNewKey
-- copies a user's row to a new key for the same user, so that holds for copied rows as well. Every
-- row of a user with no such call was therefore written by pin(), and is marked as the user's. A
-- user who has such a call may have rows of either kind that cannot be told apart, so theirs stay
-- marked as the model's: the label they had until now.
-- IF NOT EXISTS, and every UPDATE below only sets what it selects on, so the file can run again
-- (V319ChosenByUserBackfillIT does) and change nothing more.
ALTER TABLE user_merchant_category_resolution
    ADD COLUMN IF NOT EXISTS chosen_by_user BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE user_merchant_category_resolution r
SET chosen_by_user = TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM ai_audit_log a
    WHERE a.user_id = r.user_id
      AND a.tool_name = 'RESOLVE_CATEGORY'
);

-- Transactions already filed from such a row carry decision_source AI_FALLBACK. They get the label
-- the same row now produces: LEARNED_PATTERN at 100, what CategorizationService gives a user's own
-- earlier answer for a payee. Only where it is proven that no model answered:
--   * the user has no RESOLVE_CATEGORY call (see above), so no saved answer of theirs is a model's
--     and the live path never asked the model for them either; and
--   * no SUGGEST_CATEGORY call exists for anyone. That was the fallback before V214: a model answer
--     cached per payee and shared across users, so a user with no call of their own could still be
--     given another user's model answer. Where that ever ran, no AI_FALLBACK row can be shown to be
--     the user's, and none is relabelled.
-- A category the user set by hand is not touched (its decision_source is not AI_FALLBACK anyway).
-- needs_category_review is left as it is: clearing it would take rows out of the user's review
-- list without them seeing why, while leaving it costs at most one look at a row filed their way.
UPDATE transactions t
SET decision_source = 'LEARNED_PATTERN',
    decision_confidence = 100,
    updated_at = now()
WHERE t.decision_source = 'AI_FALLBACK'
  AND NOT EXISTS (
      SELECT 1 FROM ai_audit_log a
      WHERE a.user_id = t.user_id
        AND a.tool_name = 'RESOLVE_CATEGORY'
  )
  AND NOT EXISTS (
      SELECT 1 FROM ai_audit_log a
      WHERE a.tool_name = 'SUGGEST_CATEGORY'
  );

-- "Let it go" keeps the payment's earlier decision so an undo can put it back (V292). The same
-- relabel, under the same proof, so an undo does not bring the AI label back.
UPDATE loan_entries le
SET before_decision_source = 'LEARNED_PATTERN',
    before_decision_confidence = 100
WHERE le.before_decision_source = 'AI_FALLBACK'
  AND NOT EXISTS (
      SELECT 1 FROM ai_audit_log a
      WHERE a.user_id = le.user_id
        AND a.tool_name = 'RESOLVE_CATEGORY'
  )
  AND NOT EXISTS (
      SELECT 1 FROM ai_audit_log a
      WHERE a.tool_name = 'SUGGEST_CATEGORY'
  );
