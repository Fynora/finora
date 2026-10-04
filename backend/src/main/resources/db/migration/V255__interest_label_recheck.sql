-- Stored interest credits get the label a fresh import gives them under the narrowed and widened
-- rule.
--
-- V254 labelled stored interest credits "interest". The rule has changed since
-- (CategoryRules.extractMerchantLabel(description, direction), BankActivityCategory):
--   - a narration that names who paid the interest keeps that name, so interest from a lender or
--     another bank's deposit is told apart from the account's own;
--   - interest charged and then refunded or reversed is money coming back, not interest earned,
--     and keeps its own label;
--   - four more spellings count as interest earned ("interest credited", "fd interest",
--     "int credit", "interest payment"), and still carry the date in their label.
--
-- Which rows change is decided in Java, by the import rule itself: whether a narration names its
-- payer is read by CategoryRules' structured-narration parser, which SQL cannot reproduce. So this
-- only takes a snapshot of the rows that could change -- money in, never a label the user set
-- (MERCHANT in user_edited_fields), and either labelled "interest" or carrying one of the four new
-- spellings -- and InterestLabelRecheckSweepService works through it, deleting each row as it is
-- decided. A queued row whose label already matches the rule is only dropped from the queue. Rows
-- imported after this runs are labelled by the current rule at import and are never queued.
--
-- Categories are not touched here: CategorizationService.SUGGESTION_VERSION was raised in the same
-- release, which re-checks the rows still waiting for review.
CREATE TABLE interest_label_recheck (
    transaction_id UUID PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE
);

INSERT INTO interest_label_recheck (transaction_id)
SELECT id
FROM transactions
WHERE txn_type = 'INCOME'
  AND description IS NOT NULL
  AND NOT ('MERCHANT' = ANY (user_edited_fields))
  AND (merchant = 'interest'
       OR regexp_replace(lower(description), '[^a-z0-9]+', ' ', 'g')
          ~ '(interest credited|fd interest|int credit|interest payment)');
