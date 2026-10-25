-- Gate 2b spec §7: send a statement from your computer. The phone asks for a short code; the public
-- page app.fynora.net/send takes the code and one file, which goes through the normal queued import
-- for the code's owner. An upload code, not a sign-in: it lets a computer add one file and see
-- nothing (StatementSendService).
--
-- Only the SHA-256 of the code is stored, never the code. A code is live while used_at, cancelled_at
-- are null and expires_at is in the future; the partial unique index keeps two live codes from
-- sharing a hash (the service draws again on a collision). Issuing a new code cancels the user's
-- live ones. import_job_id is the job the file became; no FK, so purging jobs and codes needs no order.
CREATE TABLE statement_send_codes (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash     VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    import_job_id UUID
);

CREATE UNIQUE INDEX idx_statement_send_codes_live_hash
    ON statement_send_codes (code_hash) WHERE used_at IS NULL AND cancelled_at IS NULL;

CREATE INDEX idx_statement_send_codes_user ON statement_send_codes (user_id, created_at DESC);
