-- Import-verification framework gap: the computed VerificationReport (balance-chain/summary-totals
-- checks, docs/engineering/import/import-verification-framework.md) was only ever attached to the
-- synchronous stage response and never persisted on the session, so GET /import/sessions/{id} --
-- resume, and the async job queue's completion path, which reads this same endpoint -- always
-- returned verification=null even when it had already been computed. Nullable TEXT JSON, same
-- best-effort convention as detected_account_json/credit_card_summary_json on this table.
ALTER TABLE import_sessions ADD COLUMN verification_report_json TEXT;
