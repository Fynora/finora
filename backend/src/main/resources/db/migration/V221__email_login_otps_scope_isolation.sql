-- Bug fix: email_login_otps (V220) keyed its "one live code per email" invariant on email alone,
-- but V52 (account_scope_for_dual_identity) already established that the SAME email can back two
-- entirely separate accounts -- one USER-scope, one ADMIN-scope. Unlike email_verification_tokens/
-- password_reset_tokens, whose lookup is by the long random token itself (never by email), an OTP
-- login code is looked up by email because it's a short human-typed value -- so without a scope
-- column, requesting a code for one scope's account would both silently invalidate (via
-- markAllUnconsumedAsConsumed) a still-live code for the OTHER scope's account sharing that email,
-- and a code correctly delivered for one scope's account could complete a login into the OTHER
-- scope's account, since loginWithEmailOtp's lookup had no way to tell which account the row
-- actually belonged to. account_scope makes every code, and every "one live code" guarantee,
-- specific to the single account it was actually issued for.
ALTER TABLE email_login_otps ADD COLUMN account_scope VARCHAR(10) NOT NULL DEFAULT 'USER';
ALTER TABLE email_login_otps ADD CONSTRAINT ck_email_login_otps_account_scope
    CHECK (account_scope IN ('USER', 'ADMIN'));
ALTER TABLE email_login_otps ALTER COLUMN account_scope DROP DEFAULT;

DROP INDEX IF EXISTS idx_email_login_otps_email_created_at;
CREATE INDEX idx_email_login_otps_email_scope_created_at
    ON email_login_otps (email, account_scope, created_at DESC);

DROP INDEX IF EXISTS uq_email_login_otps_unconsumed;
CREATE UNIQUE INDEX uq_email_login_otps_unconsumed
    ON email_login_otps (email, account_scope) WHERE consumed_at IS NULL;
