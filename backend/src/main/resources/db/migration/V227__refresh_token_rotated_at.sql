-- Audit F-11 (2026-09-24). Records WHEN a refresh token was retired by ordinary rotation, as
-- distinct from the other ways revoked_at gets written (logout, idle/absolute limits, and the
-- account-wide revocation that reuse detection performs). RefreshTokenService.rotate reads it to
-- tell a token replayed a second later by the same client -- a retried POST, two app instances --
-- from one replayed by a thief after the session moved on. Null on every existing row, which
-- keeps their behaviour exactly as before: a revoked row with no rotated_at is treated as theft.
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;
