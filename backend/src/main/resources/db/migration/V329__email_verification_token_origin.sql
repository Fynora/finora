-- Which flow sent an email-verification link.
--
-- Until now a link said nothing about why it was sent, so AuthService.verifyEmail treated every
-- link the same: open it and the account is verified. That left an account takeover open. Sign-up
-- by password never proves the email belongs to the person signing up, so a stranger can register
-- someone else's address with a password and phone number of their own. Two different links can
-- then reach the real owner's inbox, and they need different handling:
--
--   SIGN_UP  sent by register(). Only the person who signed up should be able to use it, so
--            verifyEmail now requires the request to come from a session of that same account.
--   GOOGLE   sent when a Google sign-in met an account whose email was never proven.
--   APPLE    the same for Apple. Opening one of these proves who owns the inbox, and at that
--            moment whatever the account's creator set (password, phone number, sessions, push
--            devices) is replaced or removed, because the creator may not be the owner.
--
-- Existing rows become SIGN_UP. A conflict link that is still unused when this deploys will ask
-- its reader to sign in first; trying Google or Apple sign-in again sends a fresh one. Links live
-- for 24 hours, so that window closes on its own.
ALTER TABLE email_verification_tokens
    ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'SIGN_UP';

ALTER TABLE email_verification_tokens
    ADD CONSTRAINT chk_email_verification_tokens_origin CHECK (origin IN ('SIGN_UP', 'GOOGLE', 'APPLE'));
