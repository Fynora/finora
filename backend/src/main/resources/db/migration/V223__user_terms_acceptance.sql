-- When a user agreed to the Terms of Service and Privacy Policy, and which version of them.
--
-- Every sign-up path (password, Google, Apple -- web and mobile) shows a "you agree to Fynora's
-- Terms of Service and Privacy Policy" notice beside the action that creates the account, but
-- nothing recorded that the agreement happened, so there was no evidence of consent to point to.
-- AuthService now stamps both columns at account creation.
--
-- Nullable, and deliberately NOT backfilled: accounts created before this column existed have no
-- record of which terms they saw, and inventing one (created_at, or today's version) would be a
-- fabricated consent record. NULL means "created before acceptance was recorded".
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN terms_version VARCHAR(32);
ALTER TABLE users ADD CONSTRAINT chk_users_terms_acceptance_pair
    CHECK ((terms_accepted_at IS NULL) = (terms_version IS NULL));

COMMENT ON COLUMN users.terms_accepted_at IS
    'When the user accepted the Terms of Service and Privacy Policy (set at account creation). '
    'NULL for accounts created before acceptance was recorded (V223) -- never backfilled.';
COMMENT ON COLUMN users.terms_version IS
    'LegalTerms.CURRENT_VERSION at the moment of acceptance -- the "Last updated" month of the '
    'Terms/Privacy pages the user was shown.';
