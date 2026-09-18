# In-app password reset (mobile)

**Status:** approved in chat 2026-09-18 (Sid); build started once #1650 (app links) merged.

## Problem

`ForgotPasswordScreen` emails a reset link and tells the user to "finish on the web". Since #1650 the
OS can hand emailed `https://app.fynora.net/...` links to the app -- but `/reset-password` was
deliberately left unclaimed because the app has no reset flow, so it still opens the browser. A user
with the app has to leave it, complete the reset on a web page, and come back.

## Goal

With the app installed, tapping the reset email opens the app and the user completes the reset there,
with the same security guarantees as the web page (BH-015: reset link **and** phone OTP).

## Non-goals

- No change to the backend: `POST /auth/reset-password/phone` and `POST /auth/reset-password` already
  exist (and are already in `authApi`, unused).
- No auto sign-in after a reset (web doesn't either); `resetPassword` revokes every session anyway.
- No email-only reset for accounts without a phone number: the backend rejects them and says why.

## Flow

`ResetPasswordScreen` (AuthStack, params `{ token }`), four steps mirroring `frontend/src/pages/ResetPassword.tsx`:

1. **Phone** -- user types their own 10-digit number (`+91`, `PHONE_PATTERN`). `authApi.verifyResetPasswordPhone(token, +91…)`
   confirms it matches the account *without* revealing the real number; only then does Firebase send
   the SMS (`sendPhoneVerificationCode`). The token is validated here, so an expired link fails at step 1.
2. **Code** -- 6 digits (`sanitizeOtp`). `confirmPhoneVerificationCode` -> Firebase ID token, kept in
   memory. "Resend code" sends a new SMS; "Start over" returns to step 1.
3. **New password** -- >= 8 and <= 72 chars (backend `@Size`), confirm field, strength meter.
   `authApi.resetPassword(token, idToken, newPassword)`. A server rejection (same as current /
   recently used) stays on this step and **reuses the ID token**, so it costs no second SMS.
4. **Done** -- rebuilds the auth stack as `[AuthEntry, Login({ message })]` via `navigation.reset`
   (`LoginScreen` already renders that banner). A plain `navigate('Login')` would leave this screen --
   typed password, spent token -- one Back press away on a cold start, where it sits directly above
   `AuthEntry`. "Back to sign in" resets the same way, without a banner.

## Link handling

- `/reset-password` becomes an **exact** claimed path (`APP_LINK_EXACT_PATHS`, AASA regenerated),
  plus `finora://reset-password?token=...` through the shared `parseAppLink`.
- `useResetPasswordDeepLink(navigationRef, { bootstrapping, signedIn, signOut })`:
  - Waits until auth bootstrapping finishes (it cannot tell signed-in from signed-out before that).
  - **Signed out:** navigates to `ResetPassword { token }` once the container is ready.
  - **Signed in** (including phone-unverified/onboarding): a confirm alert -- "Resetting signs you out on all
    your devices" -- then `signOut()`, then the same navigation. Cancel drops the link. Without this the
    link would open the app and do nothing, with no way back to the browser.
  - A link with no token gets an "incomplete link" alert. A link re-delivered while one is still
    pending (or its confirm alert is open) is ignored, so prompts don't stack; tapping the same link
    again after the screen has opened opens it again, since the user may have backed out.

## Security

- Token lives only in route params/in-memory state. AuthStack is not navigation-persisted; Sentry has
  `sendDefaultPii: false` and no navigation integration. Never logged.
- Firebase user is signed out immediately after the ID token is read (`confirmPhoneVerificationCode` already does this).
- Nothing new is trusted from the link: the backend re-validates token and phone on every call.

## Testing

Written first, watched failing: parser/claim (`appLinks`, seam, self-open), hook (signed-out replay,
signed-in confirm/cancel, cold start, no token, duplicates), screen (each step, resend, start over,
error mapping, ID-token reuse after a rejected password, success banner). Real SMS delivery cannot be
unit tested -- see "Not verified".

## Not verified

- End-to-end OTP on a device (needs a real number or a Firebase test number, and -- on iOS -- the
  push-entitlement build from #1651).
- The claim is compiled into the native binary: ships only with the next native build.
