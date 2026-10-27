-- Engine-made merchants whose rows name nobody lose those rows, and are removed when nothing is
-- left on them.
--
-- MerchantNormalizationEngine used to make a merchant for every narration, including one that names
-- no payee (a bare UPI or NACH reference) and a cash withdrawal (ATM, HDFC's ATW and NWD). Each such
-- merchant was shown in Top Merchants and the review groups as if it were a shop. New rows now get no
-- merchant (MerchantNames.narrationNamesNobody). This snapshots the merchants that could hold such
-- rows -- engine guesses (TEMPORARY), never one a person approved, whose name opens with a payment
-- word -- and MerchantNobodyDetachSweepService decides row by row in Java, where the rule lives.
--
-- The service starts in dry-run mode: it counts what it would do, logs the counts and writes
-- nothing, until app.merchant-nobody-detach.sweep.mode is set to apply (owner's decision,
-- 2026-10-14).
CREATE TABLE merchant_nobody_detach (
    merchant_id UUID PRIMARY KEY REFERENCES merchants (id) ON DELETE CASCADE
);

INSERT INTO merchant_nobody_detach (merchant_id)
SELECT id
FROM merchants
WHERE lifecycle_status = 'TEMPORARY'
  AND split_part(lower(btrim(canonical_name)), ' ', 1)
      IN ('upi', 'imps', 'neft', 'rtgs', 'mob', 'mmt', 'ib', 'ach', 'nach', 'pos', 'atm', 'atw', 'nwd');
