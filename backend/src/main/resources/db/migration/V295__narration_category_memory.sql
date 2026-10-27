-- The category a person chose for a row that names nobody, kept so the next row with the same text
-- is filed the same way.
--
-- A row that names nobody (a bare UPI or NACH reference, a cash withdrawal) no longer gets a
-- merchant (V294, MerchantNames.narrationNamesNobody), so merchant learning cannot remember a
-- choice for it. Most such rows never repeat -- each UPI reference is new -- but some do: a NACH
-- debit that prints only its mandate number repeats every month, and withdrawals at one ATM share
-- its place. NarrationCategoryMemory keeps the latest choice per user under a hash of the text that
-- repeats; the narration itself is not stored here.
--
-- Rows are removed with their user by AccountPurgeSweepService (the purge never deletes the users
-- row, so the cascade below covers only a plain user delete), and with their category.
CREATE TABLE narration_category_memory (
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_hash    CHAR(64)    NOT NULL,
    category_id UUID        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key_hash)
);

CREATE INDEX idx_narration_category_memory_category ON narration_category_memory (category_id);
