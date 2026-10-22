-- A bank on each layout profile, and a count-only record of the bank each imported account got.
--
-- layout_profile.bank_id: when an upload's layout belongs to a profile with a bank, and the file
-- itself names no bank, the import fills the bank from the profile (ProfileBankFiller). Profiles the
-- auto-linker created already carry their bank in auto_key ("<bankId>|<families>",
-- LayoutIdentity.key()), so it is copied out here. Profiles an admin made by hand have no auto_key
-- and stay empty until an admin chooses a bank.
ALTER TABLE layout_profile ADD COLUMN bank_id VARCHAR(64);

UPDATE layout_profile
SET bank_id = split_part(auto_key, '|', 1)
WHERE auto_key IS NOT NULL
  AND split_part(auto_key, '|', 1) <> ''
  AND split_part(auto_key, '|', 1) <> 'OTHER';

-- One row per account created by a statement import: where its bank came from and what it ended up
-- as. Feeds two admin counts (users' bank choices per layout, and bank origins per week).
-- Deliberately has no user or account column: the admin surfaces built on it show counts only, and
-- a table that cannot name a person cannot leak one.
CREATE TABLE import_bank_choice (
    id                  UUID         PRIMARY KEY,
    layout_fingerprint  VARCHAR(24),
    detected_bank_id    VARCHAR(64)  NOT NULL,
    bank_source         VARCHAR(8)   NOT NULL,
    chosen_bank_id      VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_import_bank_choice_source CHECK (bank_source IN ('FILE', 'PROFILE', 'NONE'))
);

CREATE INDEX ix_import_bank_choice_fingerprint ON import_bank_choice (layout_fingerprint);
CREATE INDEX ix_import_bank_choice_created_at ON import_bank_choice (created_at);
