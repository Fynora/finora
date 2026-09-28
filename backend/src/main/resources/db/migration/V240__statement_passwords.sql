-- Statement refresh, step 4: a protected PDF's password, kept only when the user agreed to it.
--
-- Encrypted with EncryptionService (AES-256-GCM), the same way Gmail refresh tokens and device
-- tokens are: the value has to be read back to open the file again, so it cannot be hashed.
-- encryption_key_id names the key it was written under, so keys can rotate row by row.
--
-- A row belongs to exactly one of:
--   import_job_id        -- a queued upload, from the moment it is accepted until it is confirmed
--                           (then it moves to the statements it produced) or can no longer be;
--   statement_import_id  -- a stored statement, so a refresh, re-import or the dry run can open it.
--
-- consent_version / consented_at record what the user agreed to and when. The user can remove one
-- or all from Settings. statement_imports rows are soft-deleted, never hard-deleted, and users are
-- anonymised rather than deleted, so no cascade from either fires: the statement delete, the
-- account delete and the account purge delete these rows explicitly. The import_jobs cascade does
-- fire (jobs are hard-deleted by the purge).
CREATE TABLE statement_passwords (
    id                   UUID PRIMARY KEY,
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    import_job_id        UUID REFERENCES import_jobs(id) ON DELETE CASCADE,
    statement_import_id  UUID REFERENCES statement_imports(id),
    encrypted_password   TEXT NOT NULL,
    encryption_key_id    VARCHAR(64) NOT NULL,
    consent_version      VARCHAR(32) NOT NULL,
    consented_at         TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT statement_passwords_one_owner CHECK (num_nonnulls(import_job_id, statement_import_id) = 1)
);
CREATE UNIQUE INDEX uq_statement_passwords_job ON statement_passwords(import_job_id) WHERE import_job_id IS NOT NULL;
CREATE UNIQUE INDEX uq_statement_passwords_statement ON statement_passwords(statement_import_id) WHERE statement_import_id IS NOT NULL;
CREATE INDEX idx_statement_passwords_user ON statement_passwords(user_id);
