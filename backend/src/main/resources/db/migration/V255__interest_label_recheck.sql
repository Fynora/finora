-- Interest credits that V254 labelled "interest" but the import rule no longer does go back to the
-- label a fresh import gives them.
--
-- V254 labelled every stored interest credit "interest". The rule has since been narrowed
-- (CategoryRules.extractMerchantLabel(description, direction)): a narration that names who paid the
-- interest keeps that name, so interest from a lender or another bank's deposit is told apart from
-- the account's own; and interest charged and then refunded or reversed is money coming back, not
-- interest earned, so it keeps its own label too. Rows V254 relabelled that fall under either keep
-- "interest" until they are rechecked.
--
-- Which rows change is decided in Java, by the import rule itself: whether a narration names its
-- payer is read by CategoryRules' structured-narration parser, which SQL cannot reproduce. So this
-- only takes a snapshot of the rows that could change -- money in, labelled "interest", never a
-- label the user set (MERCHANT in user_edited_fields) -- and InterestLabelRecheckSweepService works
-- through it, deleting each row as it is decided. A row the rule still labels "interest" is only
-- dropped from the queue. Rows imported after this runs are labelled by the narrowed rule at import
-- and are never queued.
CREATE TABLE interest_label_recheck (
    transaction_id UUID PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE
);

INSERT INTO interest_label_recheck (transaction_id)
SELECT id
FROM transactions
WHERE txn_type = 'INCOME'
  AND merchant = 'interest'
  AND NOT ('MERCHANT' = ANY (user_edited_fields));
