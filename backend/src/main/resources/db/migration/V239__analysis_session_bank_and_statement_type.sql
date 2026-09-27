-- Which bank, and which kind of statement, the engine read each upload as -- so Layout Studio can
-- show an admin "HDFC Bank, savings" instead of only a reference and a fingerprint.
--
-- Copied from the engine's own detection at parse time (BankRegistry + Financial Product
-- Discovery; see DocumentIdentity). A bank name and a product type only: no account number, no
-- holder name, no file name.
--
-- identity_checked separates "detection ran and recognised nothing" (true, bank_name NULL) from
-- "detection never ran" (false) -- a document that failed before staging, or any row written
-- before this migration. Existing rows are deliberately not backfilled: only customer uploads whose
-- staging session has not yet expired still hold the detection, so a fill would be partial and
-- would make an older row's blank mean two different things.
ALTER TABLE statement_analysis_sessions
    ADD COLUMN identity_checked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN bank_name        VARCHAR(128),
    ADD COLUMN statement_type   VARCHAR(64);
