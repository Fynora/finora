-- Interest credits already stored get the one label new ones get: "interest".
--
-- From this release CategoryRules.extractMerchantLabel(description, direction) labels interest the
-- bank credited (BankActivityCategory.isInterestEarned) "interest". Before it, the label kept the
-- date the interest was earned for -- a bank that credits interest daily gave every row a label of
-- its own -- and rows imported before the release still carry those labels.
--
-- The WHERE clause is isInterestEarned written in SQL, and must stay its mirror:
--   - money in;
--   - CategoryRules.normalize(description) -- lowercase, every run of other characters one space --
--     contains one of BankActivityCategory.INTEREST_EARNED's phrases as whole words. Padding the
--     normalized text with a space at each end makes "a space on each side" the same test as the
--     regex's word boundaries, since the normalized alphabet is only letters, digits and spaces;
--   - the payer is not a person. The runtime types the narration on the spot; here the stored
--     counterparty_type is read, which the same typing wrote.
--
-- Never a label the user chose: MERCHANT in user_edited_fields is left alone, as a statement refresh
-- leaves it. Edits made before that column existed (V232) were never recorded, so a label that is
-- not all lowercase is also left alone -- every label the parser writes is lowercase, and a
-- hand-typed one rarely is.
--
-- version moves so the change stamp (ChangeStampService) sees the rows changed and the app
-- refetches them, and so a server-side save racing this update loses on the optimistic lock. It
-- does not protect an edit form left open across the deploy: the clients send no version, so saving
-- that form writes back the label it was opened with. Categories are not touched:
-- CategorizationService.SUGGESTION_VERSION was raised in the same release, which re-checks the rows
-- still waiting for review.
UPDATE transactions
SET merchant = 'interest',
    version = version + 1,
    updated_at = now()
WHERE txn_type = 'INCOME'
  AND description IS NOT NULL
  AND ' ' || trim(regexp_replace(lower(description), '[^a-z0-9]+', ' ', 'g')) || ' '
      ~ ' (interest paid|credit interest|interest credit|interest cr|int pd|sb int|int cr|intcr|savings interest) '
  AND counterparty_type IS DISTINCT FROM 'PERSON'
  AND NOT ('MERCHANT' = ANY (user_edited_fields))
  AND (merchant IS NULL OR merchant = lower(merchant))
  AND merchant IS DISTINCT FROM 'interest';
