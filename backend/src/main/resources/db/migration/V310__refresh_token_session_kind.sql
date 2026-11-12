-- Phone sessions and web sessions live by different limits (design 2026-11-10, mobile PIN unlock):
-- a phone session from the PIN-lock app build ends after 90 days unused, a web session keeps the
-- 24-hour idle limit and 30-day cap. The kind is stamped at sign-in and copied on every rotation.
--
-- Enum-backed VARCHAR with no CHECK constraint, the majority pattern in this schema. Every row that
-- exists when this runs was minted under the old shared rules, so it reads as WEB and keeps them.
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_kind VARCHAR(16) NOT NULL DEFAULT 'WEB';
