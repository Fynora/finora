# Admin MFA: rollout, rollback and recovery

Two-factor authentication (TOTP) is mandatory for every admin-scope account once
`ADMIN_MFA_ENFORCED=true` is set on the backend. This is the control behind CASA test case 3.3.1
("multi-factor authentication shall be enforced for all administrative accounts"). This document is
the operator procedure: what to do before turning it on, how to turn it off again, and what to do
if an admin loses both their authenticator and their recovery codes.

## How it behaves

| Setting | Effect |
| --- | --- |
| `ADMIN_MFA_ENABLED=false` | The whole feature is off. Nothing below applies. |
| `ADMIN_MFA_ENABLED=true`, `ADMIN_MFA_ENFORCED` unset or `false` | Enrolment is optional, as before. An admin who has enrolled must give a code at login; one who has not signs in with a password. |
| `ADMIN_MFA_ENABLED=true`, `ADMIN_MFA_ENFORCED=true` | An admin who has not enrolled can do only three things (and the few calls those need): enrol, verify their phone, sign out. Every other request is refused with `403 MFA_ENROLLMENT_REQUIRED` and the admin portal sends them to the "Set up two-factor authentication" screen. |

`ADMIN_MFA_ENFORCED` has no effect unless `ADMIN_MFA_ENABLED` is also true.

The check is made on every request, not only at login. An admin who was already signed in when
enforcement was switched on is therefore stopped on their next request and walked through enrolment;
their existing session is not left running.

Only accounts with `account_scope = 'ADMIN'` are affected. Customer accounts never are.

## Rollout order (do this in order)

The risk is locking out the only admin. Enforcement is safe only after that admin has enrolled and
saved their recovery codes.

1. **Deploy the release with `ADMIN_MFA_ENFORCED` unset.** Nothing changes for anyone.
2. **List the admin accounts and who has enrolled** (query below). Every row with `enrolled = false`
   will be forced through enrolment on their next request once enforcement is on.
   Also check whether any script, integration or scheduled job signs in as an admin-scope account.
   Enforcement stops it the same way (`403 MFA_ENROLLMENT_REQUIRED`), and a script cannot scan a QR
   code, so it needs a different arrangement before enforcement is switched on.
3. **Enrol every admin who must not be interrupted.** Admin portal, Settings, "Two-factor
   authentication", set it up, and store the ten recovery codes somewhere that is not the same
   device as the authenticator app. They are shown once.
4. **Check enrolment worked.** Sign out, sign in again, and confirm the portal asks for a code.
5. **Set `ADMIN_MFA_ENFORCED=true`** in Railway (Finora Tech, backend service, Variables) and let it
   restart.
6. **Check as an admin who has not enrolled**, if one exists, that they land on the setup screen
   rather than an error page.

```sql
-- Admin accounts and whether each has a finished enrolment.
SELECT u.id, u.email,
       EXISTS (SELECT 1 FROM admin_totp_credentials c
               WHERE c.user_id = u.id AND c.enabled) AS enrolled
FROM users u
WHERE u.account_scope = 'ADMIN'
ORDER BY u.email;
```

## Each authenticator code works once

A 6-digit code is accepted a single time (RFC 6238 section 5.2). Before this, a code stayed valid for
up to 90 seconds and could be presented repeatedly in that time, so someone who saw a code and had the
password could reuse it. Now the step a code belongs to is recorded (`admin_totp_credentials.last_used_step`,
migration V218) and only a strictly later step is accepted afterwards. The recording is one conditional
`UPDATE`, so two simultaneous requests carrying the same code cannot both get in.

What an admin will notice: a code that was just used to sign in, or to finish enrolment, is refused if
it is typed again, with the ordinary "That code didn't work" message (a replay is deliberately
indistinguishable from a wrong code). Waiting for the authenticator app to show its next code (at most
30 seconds) fixes it. This also applies right after enrolment: the code typed to confirm the setup
cannot be reused for the very next sign-in, and turning MFA off straight after signing in needs a
fresh code too.

For an operator: a valid code arriving a second time is logged as
`Admin MFA: a correct code that was already used was presented again (userId=...)`. One of these is an
admin who tapped twice; a run of them for one account is worth asking about. Recovery codes are
unaffected (they were already single-use).

Deploying needs no ordering: V218 only adds a nullable column, and an older backend instance ignores it.
Rolling back the backend leaves the column in place, harmlessly.

## Rollback

Unset `ADMIN_MFA_ENFORCED` (or set it to `false`) in Railway and restart the backend. Behaviour
returns to optional enrolment. Nobody's enrolment is touched: an admin who has enrolled is still
asked for a code at login.

If the admin portal itself is the problem (for example the setup screen will not load), this is also
the way out: it takes effect for every session on the next request after the restart.

## Recovery: an admin has lost their authenticator

Try these in order.

1. **Recovery codes.** At the code prompt at login, enter one of the ten recovery codes instead of a
   6-digit code. Each works once. After signing in, turn MFA off and on again in Settings to get a
   fresh set (turning it off needs the account password and a valid code, which the recovery code
   satisfies).
2. **Another admin cannot reset it.** There is deliberately no admin-proxy path onto another
   account's MFA (`AdminMfaController` is self-service only), so a stolen admin session cannot be
   used to strip a colleague's second factor.
3. **Break-glass (operator, database access).** Use this only when the admin has neither the
   authenticator nor any recovery code, and after confirming who is asking by a channel other than
   the admin portal. It removes the admin's MFA enrolment so they can sign in with their password and
   re-enrol. It cannot be undone, and it leaves no audit row, so write down who asked, who approved
   it and when.

```sql
-- Replace the id. Take it from the query above, matching on email AND account_scope = 'ADMIN'.
BEGIN;
DELETE FROM admin_mfa_challenges      WHERE user_id = '00000000-0000-0000-0000-000000000000';
DELETE FROM admin_mfa_recovery_codes  WHERE user_id = '00000000-0000-0000-0000-000000000000';
DELETE FROM admin_totp_credentials    WHERE user_id = '00000000-0000-0000-0000-000000000000';
-- Check the three DELETE counts are what you expect (0 or 1, 0 to 10, 0 or 1), then:
COMMIT;
```

With enforcement on, the admin's next sign-in is by password alone and lands directly on the setup
screen. They must enrol again before they can do anything else. With enforcement off, tell them to
enrol from Settings straight away.

Run this against the production database only through the Railway console or `railway connect`
with the intended service selected, and confirm you are connected to production before running it.
Do not change the database password or any connection variable as part of this (see
`docs/security/secrets-and-iam-audit.md` and the note on the 2026-09-19 outage).

## Known limits

- **A session created before enrolment stays valid after it.** Enrolling MFA does not sign out the
  admin's other sessions. If an admin suspects their password was stolen, they should reset it
  (a reset signs out every session) or use "sign out other devices" when changing it.
- **The recovery codes are shown once.** If an admin closes the tab on the recovery-code step before
  saving them, they are enrolled with no saved codes. They can turn MFA off (password plus a code)
  and on again to get a new set.
- **Nothing here covers the very first setup of a fresh installation.** `POST /api/v1/setup/complete`
  is deliberately left reachable for the bootstrap account so that first-run setup can finish; the
  real administrator it creates enrols at first sign-in.
