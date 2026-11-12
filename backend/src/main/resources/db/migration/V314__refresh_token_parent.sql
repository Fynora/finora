-- Which token a refresh token replaced (break-test 2026-11-12, device list).
--
-- A refresh retried inside the reuse window (app.jwt.refresh-reuse-grace-ms) is answered with a
-- second token for the same session, so one old token can have two children. The client keeps one
-- and the other is never used again, yet it stayed listed as its own device in Settings for up to
-- 90 days. Knowing each token's parent lets the device list hide such a leftover while still
-- showing a session twice when both copies are actually in use, which is the sign of a stolen
-- token the reuse window cannot detect.
--
-- No foreign key: the expiry sweep deletes rows oldest first, so a parent is routinely gone while
-- its child is still live. Null for sign-ins and for every row minted before this ran.
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS parent_token_id UUID;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_parent_token_id
    ON refresh_tokens (parent_token_id) WHERE parent_token_id IS NOT NULL;
