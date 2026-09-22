-- Backend-generated login OTP for the email channel (docs/superpowers/specs/
-- 2026-09-22-otp-login-design.md). Same hashed/single-use/short-TTL shape as
-- email_verification_tokens (V93), plus attempt_count: a 6-digit code has far less entropy than
-- that table's 256-bit random token, so guesses against ONE issued code are bounded directly here
-- rather than relying on expiry alone.
CREATE TABLE email_login_otps (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    -- Code-review fix: sized for the hash algorithm actually used (passwordEncoder/BCrypt, ~60
    -- chars today), not the 64-char SHA-256 hex digest this column was originally, wrongly, sized
    -- to match. 255 leaves headroom for a future algorithm (e.g. Argon2, whose encoded output can
    -- run longer) without another migration.
    code_hash     VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cooldown check (findFirstByEmailOrderByCreatedAtDesc) reads the most recent row regardless of
-- consumed_at -- needs (email, created_at).
CREATE INDEX idx_email_login_otps_email_created_at ON email_login_otps (email, created_at DESC);

-- Code-review fix: UNIQUE, not just a plain partial index -- AuthService.requestEmailLoginOtp's
-- own "check cooldown, mark prior unconsumed rows consumed, insert a new one" sequence is
-- read-then-write, not one atomic statement, so two concurrent requests for the same email could
-- otherwise both pass the cooldown check and both insert, leaving two live (unconsumed) codes at
-- once. This constraint makes that impossible at the database level regardless of what the
-- application code does or fails to do -- the loser of the race gets a real constraint-violation
-- error from its INSERT, which requestEmailLoginOtp() catches and turns into the same
-- AUTH_OTP_RESEND_COOLDOWN response a legitimate too-soon retry gets (see Task 2 Step 4), rather
-- than surfacing a raw 500. Also serves as the lookup index for
-- findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc, same as the plain version this replaces.
CREATE UNIQUE INDEX uq_email_login_otps_unconsumed ON email_login_otps (email) WHERE consumed_at IS NULL;
