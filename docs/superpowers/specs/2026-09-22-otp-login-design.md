# OTP Login — Design Spec

Date: 2026-09-22
Status: Approved for planning

## Purpose

Add a passwordless "Login with OTP" option to the existing identifier+password
login screen, on web and mobile. A user who has already typed their email or
phone number can choose to receive a one-time code instead of typing their
password.

## Scope

- **Existing accounts only.** No signup-via-OTP. An identifier that doesn't
  resolve to an account behaves the same as it does today at the identify
  step (`IdentifyResponse.nextAction == "CONTINUE"`) — OTP login never
  creates an account.
- **Both channels, both platforms, together.** Phone OTP and email OTP ship
  in the same release, on both the web app and the React Native mobile app.
- **Both channels gate on the contact method already being verified**
  (`phoneVerified` / `emailVerified` on `User`). If not verified, the
  OTP-request step refuses and points the user at the existing verify flow
  instead of sending a code. In practice phone is already verified during
  onboarding, so this gate is expected to trip rarely for phone but is
  enforced identically to email.
- **TOTP MFA still applies.** If the account has TOTP MFA enabled, a
  successful OTP login still produces an `AUTH_MFA_REQUIRED` challenge, same
  as password login does today. OTP replaces the *first* factor only.

## Architecture

Two independent channels, both terminating in the same success/challenge
shape as password login (`AuthResponse`, or an `AUTH_MFA_REQUIRED` challenge
token) so everything downstream — token issuance, refresh-cookie handling,
`AUTH_ACCOUNT_DEACTIVATED` reactivation prompt — is unchanged.

### Phone OTP — reuse existing Firebase Phone Auth pipeline

No new SMS-sending code and no new OTP-policy code. The client (web:
`phoneAuth.ts` / mobile: its equivalent) calls Firebase Phone Auth exactly as
`VerifyPhone.tsx` does today — invisible reCAPTCHA (web) or the native
equivalent, `signInWithPhoneNumber`, confirm — and gets back a Firebase ID
token. That token is sent to a new backend endpoint:

- `POST /auth/otp/phone/login` — body: `{ firebaseIdToken }`. Runs the token
  through the existing `FirebasePhoneVerificationProvider` (already used by
  `VerifyPhoneRequest` / `PhoneChangeService`) to recover the verified phone
  number, resolves it to an account, checks `phoneVerified == true`, and logs
  in (or issues an MFA challenge).

Firebase owns code length, expiry, and resend cooldown for this channel —
those are not configurable by this feature and are not the same policy as
email OTP below.

### Email OTP — net-new, backend-generated

New entity `EmailLoginOtp`, same shape as `EmailVerificationToken`:
`email`, `codeHash` (bcrypt/argon2 hash, never store plaintext), `expiresAt`,
`attemptCount`, `consumedAt`.

Policy: 6-digit numeric code, single-use, 5-minute expiry, 30-second resend
cooldown per email, max 5 verify attempts before the code is invalidated
(caller must request a fresh one).

Two endpoints:

- `POST /auth/otp/email/request` — body: `{ identifier }`. Identifier must
  resolve to an existing account with `emailVerified == true`. Generates the
  code, stores the hash, sends it via the existing `EmailProvider` /
  `ResendEmailProvider`. Enforces the 30s resend cooldown.
- `POST /auth/otp/email/login` — body: `{ identifier, code }`. Checks hash
  match, expiry, and attempt count; consumes the code on success; logs in
  (or issues an MFA challenge).

## Cross-cutting mechanics

- **Identifier detection**: reuse the existing frontend `identifierPatterns.ts`
  classifier — no new phone-vs-email detection logic.
- **Rate limiting**: both new email endpoints go through the existing
  `RateLimitFilter`, tuned tighter than password login since `/request` costs
  a real email send and both endpoints are unauthenticated. Phone gets this
  for free via Firebase's own abuse limits.
- **Account-state parity**: `AUTH_ACCOUNT_DEACTIVATED` and
  `AUTH_MFA_REQUIRED` behave identically to password login — OTP login
  funnels into the same internal login-completion path, just with a
  different first factor.
- **Audit logging**: OTP login attempts (channel, success/failure, masked
  identifier) go through whatever `AuthService.login()` already logs to — no
  separate audit path.
- **Session/token issuance**: unchanged — same `AuthResponse` shape, same
  refresh-cookie handling as password login.

## Frontend

- **Web**: `PasswordStep.tsx` gains a "Login with OTP instead" link near the
  password field. Clicking it swaps the password input for an OTP flow
  (phone or email, based on the already-typed identifier), reusing the same
  identifier — no re-entry.
- **Mobile**: `LoginScreen.tsx` gets the equivalent toggle, same behavior.

New UI states to handle on both platforms: code sent, code expired, wrong
code, resend cooldown active, attempts exhausted (must request a fresh
code), contact method not verified (point at existing verify flow).

## Testing

- Backend: unit tests for `EmailLoginOtp` generation, expiry, and
  attempt-exhaustion; integration test for `/auth/otp/phone/login` against
  `FirebasePhoneVerificationProvider`'s existing test double (same one
  `VerifyPhoneRequest` tests already use, if present).
- Frontend: component tests for the new OTP-entry UI states (sent, expired,
  wrong code, resend cooldown) on both `PasswordStep.tsx` and mobile's
  `LoginScreen.tsx`.

## Out of scope (not building now)

- Signup via OTP (no password ever set).
- Self-verifying an unverified contact method as a side effect of a
  successful OTP login (rejected — verification is a precondition, not an
  outcome, of OTP login).
- Any change to TOTP MFA behavior.
