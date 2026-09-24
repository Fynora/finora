-- Audit fix (2026-09-24): an admin MFA challenge accepted unlimited wrong codes for its whole
-- five-minute life. The only throttle was the per-IP limit on /auth/mfa/verify, which a
-- distributed guesser sidesteps. The email-OTP flow already caps attempts per code
-- (email_login_otps.attempt_count); this gives the MFA challenge the same shape. Existing rows
-- default to zero, which is correct: nothing has been counted against them yet.
ALTER TABLE admin_mfa_challenges ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
