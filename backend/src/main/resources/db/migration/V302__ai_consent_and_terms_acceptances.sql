-- Privacy workstreams 7 and 8 (docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md).
--
-- 1. The AI features choice. NULL means the user has not been asked yet, and counts as no: no AI
--    call is made for this user until the column is TRUE (FynAvailabilityGuard). Both columns move
--    together, like V223's terms pair.
ALTER TABLE users ADD COLUMN ai_processing_consent BOOLEAN;
ALTER TABLE users ADD COLUMN ai_processing_consent_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT chk_users_ai_processing_consent_pair
    CHECK ((ai_processing_consent IS NULL) = (ai_processing_consent_at IS NULL));

COMMENT ON COLUMN users.ai_processing_consent IS
    'The user''s answer to "Use AI features" (Ask Fyn, AI insights, AI category suggestions). '
    'NULL = not asked yet, treated as no.';

-- 2. Every acceptance of the Terms of Service and Privacy Policy, one row each, never updated.
--    users.terms_version / terms_accepted_at stay as the latest acceptance.
--
--    method CHECKBOX: the user ticked the required box (sign-up, or the consent step).
--    method NOTICE: the passive "by continuing you agree" notice that V223 recorded.
--
--    Kept when the account is deleted: the purge anonymises the users row rather than deleting it,
--    so these rows end up pointing at an anonymised account and hold no personal data of their
--    own -- they are the evidence that consent was given. No IP address is stored.
CREATE TABLE user_terms_acceptances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    terms_version VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(16) NOT NULL,
    method VARCHAR(16) NOT NULL,
    CONSTRAINT chk_user_terms_acceptances_channel
        CHECK (channel IN ('WEB', 'MOBILE_IOS', 'MOBILE_ANDROID', 'UNKNOWN')),
    CONSTRAINT chk_user_terms_acceptances_method CHECK (method IN ('CHECKBOX', 'NOTICE'))
);
CREATE INDEX idx_user_terms_acceptances_user ON user_terms_acceptances(user_id, accepted_at DESC);

-- The history starts with what V223 actually recorded: one NOTICE row per account that has a
-- version, channel UNKNOWN because the sign-up channel was never stored. Accounts with no record
-- get no row -- inventing one would be a fabricated consent record (V223's own rule).
INSERT INTO user_terms_acceptances (user_id, terms_version, accepted_at, channel, method)
SELECT id, terms_version, terms_accepted_at, 'UNKNOWN', 'NOTICE'
FROM users
WHERE terms_version IS NOT NULL;
