-- "Let it go" re-files the lent payment under Gifts & Donations (PeopleService.settle), which sets the
-- category by hand and clears the engine's decision. Undoing it -- deleting the LENT entry or the
-- LET_GO entry -- used to leave the payment there, filed as a gift by hand, with nothing to say where
-- it was before (measured on a local backend, 2026-10-25). A LET_GO naming a lent payment now keeps
-- what the payment's category was, so the undo can put it back.
--
-- before_decision_source is set exactly when the rest was taken (transactions.decision_source is NOT
-- NULL), so it marks a kept category; every other before_* column may be null as the payment's own
-- was. let_go_category_id is the category the let-go filed it under: the undo restores only while the
-- payment is still there, never over a category the user chose since. No foreign keys: a category
-- deleted since is simply not restored. Existing LET_GO rows predate this and keep nothing.
ALTER TABLE loan_entries
    ADD COLUMN before_category_id UUID,
    ADD COLUMN before_category_manually_set BOOLEAN,
    ADD COLUMN before_needs_category_review BOOLEAN,
    ADD COLUMN before_decision_source VARCHAR(30),
    ADD COLUMN before_decision_rule_id UUID,
    ADD COLUMN before_decision_confidence INTEGER,
    ADD COLUMN let_go_category_id UUID;
