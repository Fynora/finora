-- A Gmail connection is now finished by the signed-in app, not by Google's redirect.
--
-- Before this, the callback linked whichever mailbox had just consented to the account that
-- started the flow, on the strength of `state` alone. The callback is unauthenticated, so nothing
-- said the person finishing was the person who started: a consent link started in one account and
-- opened by somebody else attached that other person's mailbox to the first account.
--
-- The callback now only parks what Google returned and hands the browser a one-time ticket. The
-- app posts that ticket back while signed in, and the connection is made only when the signed-in
-- user is the one the flow was started by.
--
-- ticket_hash             SHA-256 of the ticket, never the ticket. Same reasoning as state_hash.
-- ticket_expires_at       Short: the app posts the ticket back at once. Also bounded by how long
--                         Google honours the authorization code parked beside it.
-- encrypted_code          Google's authorization code, encrypted like every other credential here
--                         (EncryptionService). Cleared the moment the ticket is redeemed.
-- code_encryption_key_id  Which key encrypted it.
-- confirmed_at            Set when the ticket is redeemed. Enforces single use.
ALTER TABLE gmail_oauth_states
    ADD COLUMN ticket_hash VARCHAR(64),
    ADD COLUMN ticket_expires_at TIMESTAMPTZ,
    ADD COLUMN encrypted_code TEXT,
    ADD COLUMN code_encryption_key_id VARCHAR(64),
    ADD COLUMN confirmed_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_gmail_oauth_states_ticket
    ON gmail_oauth_states (ticket_hash)
    WHERE ticket_hash IS NOT NULL;
