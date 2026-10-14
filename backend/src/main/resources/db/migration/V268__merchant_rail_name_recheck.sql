-- Engine-made merchants whose stored name opens with a payment rail get the name a fresh import of
-- their own narrations gives them now.
--
-- Until 2026-10-03 (#1920) MerchantNormalizationEngine named a new merchant after the first four
-- words of its narration, so the name opened with the rail and carried what followed it
-- ("Upi Dr <name> <bank>"). The rule has changed twice since (#1920, and MerchantNames.forNarration),
-- but no stored name was ever rewritten. The app already shows such a merchant by its payee
-- (MerchantNames.forDisplay); the stored name still decides the grouping key, so a payee whose old
-- name keys differently from its narrations' payee field keeps getting a second merchant.
--
-- Which merchants change, and to what, is decided in Java by the import rule itself: the new name
-- comes from the merchant's own narrations through CategoryRules' structured-narration parser, which
-- SQL cannot reproduce. So this only takes a snapshot of the merchants that could change -- engine
-- guesses (TEMPORARY), never a name a person approved, whose first word is one of the rails a
-- narration opens with -- and MerchantRailNameSweepService works through it, deleting each row as it
-- is decided. A merchant that would share its new grouping key with another merchant of the same
-- user is left as it is, for a person to merge (owner's decision, 2026-10-14).
--
-- The service starts in dry-run mode: it counts what it would do, logs the counts and writes
-- nothing, until app.merchant-rail-name-recheck.sweep.mode is set to apply.
CREATE TABLE merchant_rail_name_recheck (
    merchant_id UUID PRIMARY KEY REFERENCES merchants (id) ON DELETE CASCADE
);

INSERT INTO merchant_rail_name_recheck (merchant_id)
SELECT id
FROM merchants
WHERE lifecycle_status = 'TEMPORARY'
  AND split_part(lower(btrim(canonical_name)), ' ', 1)
      IN ('upi', 'imps', 'neft', 'rtgs', 'mob', 'mmt', 'ib', 'ach', 'nach');
