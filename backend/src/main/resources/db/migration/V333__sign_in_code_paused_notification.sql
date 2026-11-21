-- "Sign-in by emailed code is paused on your account" (SignInCodePause), sent when too many
-- wrong codes have been entered for an account within 24 hours. Audit F-07 (2026-11-20).
--
-- {{count}} is how many wrong codes were entered, {{ip}} is the address the last of them came
-- from, {{time}} is India time. Nothing anyone typed appears in either message.
--
-- The email does not say "it was not you": the owner mistyping ten codes reaches this too. It
-- does not say nobody got in either: the pause stops further guesses, it says nothing about
-- sign-ins before it. A sign-in has its own alert (NEW_SIGN_IN, V332).
--
-- The PUSH row carries no address: it shows on a lock screen.
-- The count is "wrong tries on this account's codes in the last 24 hours", read on every code
-- request and every code sign-in. The table had no index on user_id (its lookups were by email),
-- so that read scanned the whole table. It holds a day of rows at most, so the index is small.
CREATE INDEX IF NOT EXISTS idx_email_login_otps_user_created_at
    ON email_login_otps (user_id, created_at);

INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'SIGN_IN_CODE_PAUSED', 'EMAIL',
     'Sign-in by emailed code is paused on your Fynora account',
     'The wrong sign-in code was entered {{count}} times for your Fynora account in the last 24 hours, '
     'most recently on {{time}} from IP address {{ip}}. To keep anyone from guessing a code, we have '
     'paused sign-in by emailed code for your account. It will work again within 24 hours. '
     'Your password still works, and so does signing in with Google or Apple if you use them. '
     'If you were not the one entering codes, someone may be trying to get into your account: '
     'open Settings, Security, sign out any device you do not recognise, and change your password.'),
    (gen_random_uuid(), 'SIGN_IN_CODE_PAUSED', 'PUSH',
     'Sign-in codes are paused',
     'Too many wrong sign-in codes were entered for your Fynora account. Codes are paused for up to '
     '24 hours. Your password still works.');
