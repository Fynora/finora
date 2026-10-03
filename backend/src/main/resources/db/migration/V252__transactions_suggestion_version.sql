-- Which revision of the category-suggestion rules last examined this row
-- (CategorizationService.SUGGESTION_VERSION). Existing rows start at 0, so every row still waiting
-- for review is re-checked once by CategorySuggestionSweepService.
ALTER TABLE transactions
    ADD COLUMN suggestion_version SMALLINT NOT NULL DEFAULT 0;

-- Partial: the sweep only ever looks at rows still waiting and not chosen by the user, a small
-- slice of the table, so a drained sweep costs an empty index probe.
CREATE INDEX idx_transactions_waiting_suggestion_version
    ON transactions (suggestion_version)
    WHERE needs_category_review AND NOT category_manually_set;
