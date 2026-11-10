# Mobile PIN unlock and web idle sign-out — design

Date: 2026-11-10. Status: approved by Sid in chat on 2026-11-10 ("this is good"), with one addition
he reported in the same message (Face ID asked on the sign-in screen, section 4).

## Why

Sid: "the user gets logout very early on the mobile phone instead I want they can set there pin and
biometrices as well to login, but on web it should be different that after no activity it should get
sign out".

### What happens today (traced in code on origin/main 8ebfe11e6)

- `backend/src/main/resources/application.yml` `app.jwt`: access token 15 minutes, refresh token
  30 days, `idle-timeout-ms` 24 hours, `absolute-session-ms` 30 days. One value for web and phone.
- `RefreshTokenService.rotate()` ends a session when the presented refresh token is more than
  24 hours old (`AUTH_SESSION_IDLE`) or the session started more than 30 days ago
  (`AUTH_SESSION_MAX_AGE`). So a phone left unopened for one day goes back to the sign-in screen.
- The phone's App Lock (`mobile/src/lib/appLock.ts`, `AppLockGate.tsx`) is optional, biometric with
  the phone's passcode as fallback, and cannot bring back a session the server has already ended.
- The web has no idle sign-out of its own. An open tab refreshes silently for up to 24 hours idle.

### What other apps do (researched 2026-11-10)

Google Pay and Paytm keep you signed in on the phone and lock the app with biometrics, the phone's
screen lock, or an app PIN. Neither documents a sign-in-again-every-N-days rule. A new phone or SIM
needs OTP re-registration. Payments still need the UPI PIN. Axis Bank's MPIN length and web idle
timeout were not confirmed from an official source.

## Goals

1. A phone user stays signed in and unlocks with a PIN, or Face ID / fingerprint, instead of
   signing in again.
2. The web signs out after 15 minutes without activity.
3. Face ID is never asked for a session that has already ended.

## Non-goals

- No change to how a payment, import, or any single action is authorised.
- No server-side PIN. The PIN never leaves the phone.
- No change to "sign out this device" or "sign out everywhere"; both already exist and keep working.
- No SIM binding.

## Anti-goals (would make this a failure even if it works)

- A phone holding a long session with no lock on it.
- A stolen web session that can be turned into a long phone session.
- The store-rating prompt (PR #2511) showing over the lock screen or the PIN screen.

## Decisions

### 1. Session length (backend)

- Every session records at sign-in whether it is a **phone** or a **web** session (new column on
  `refresh_tokens`, Flyway migration, next free version checked against origin/main and open PRs at
  build time). Rotation copies the value; it is never re-read from a later request.
- A session is a phone session only when the sign-in request says it comes from the phone app
  (`X-Client-Platform` is `MOBILE_ANDROID` or `MOBILE_IOS`) **and** from a build that has the PIN
  lock (new request header sent only by the new build, for example `X-App-Lock: pin`).
- Phone sessions: idle limit 90 days, no absolute cap. Web sessions and every older app build keep
  today's 24-hour idle and 30-day cap.
- Sessions that exist when this ships keep their old rules. A phone user moves to the long session
  at their next sign-in. (My call: upgrading a live session from a request header would let a stolen
  refresh token be upgraded.)
- Plain statement of the trade-off, approved by Sid: the server cannot verify the platform header.
  `ClientIdentity`'s doc says nothing may authorise on it; this is a deliberate, documented
  exception, limited to choosing the session length at sign-in. Faking it gives a long session only
  to someone who has just signed in with the account's own password, OTP or Google/Apple sign-in, and
  it can never extend a session that already exists. `ClientIdentity`'s doc comment is updated to say
  so.
- Both new limits are configuration values like the existing ones (`app.jwt.mobile-idle-timeout-ms`,
  `app.jwt.mobile-absolute-session-ms`, 0 = off).

### 2. The lock (mobile)

- **PIN required** for every signed-in phone session on the new build. 4 digits.
- First open after updating, or right after any sign-in: the user sets a PIN (enter, then confirm)
  before reaching the app. No skip.
- **Face ID / fingerprint optional**, offered right after setting the PIN and switchable in
  Settings > Security. This replaces today's App Lock switch: a user who had App Lock on gets
  biometrics on after setting the PIN.
- Biometric prompt uses `disableDeviceFallback: true` (expo-local-authentication 58.0.2, read in
  `LocalAuthentication.types.d.ts`): the fallback is the Fynora PIN, not the phone's passcode. (My
  call: otherwise anyone who knows the phone's passcode skips the PIN.)
- **When it asks:** every cold start, and coming back after more than 60 seconds in the background.
  Shorter trips (copying an OTP, a share sheet) do not ask. The existing share and authenticate
  suppression stays.
- **Wrong PIN:** 5 wrong PINs in a row sign the user out fully on that phone (same path as the
  existing Sign Out: server logout, local tokens wiped, PIN wiped). The count is stored in
  SecureStore so closing the app does not reset it; a correct PIN or biometric success resets it.
  The screen shows how many tries are left once 2 or fewer remain.
- **Forgot PIN:** a link on the PIN screen that signs out (after a confirm), then the user signs in
  and sets a new PIN.
- **Change PIN:** Settings > Security, needs the current PIN.
- **Storage:** the PIN is stored only as a salted SHA-256 hash (expo-crypto, already a dependency)
  in SecureStore (Keychain / Keystore). Stated limit: a 4-digit PIN hash can be brute-forced by
  anyone who can read SecureStore, which needs a compromised phone; at that point the refresh token
  in the same store is already exposed, so a slower hash would not change the outcome.
- Sign out clears the PIN, biometric choice and wrong-try count, so the next account on the phone
  starts clean.
- `isLocked()`, `isAuthenticating()` and `isSharing()` in `appLock.ts` keep their meaning: true
  while the PIN screen or lock screen is up, while a biometric prompt or PIN check is running, and
  while sharing. `storeReview.ts` keeps reading them unchanged.

### 3. Signing out on one device

Unchanged. Settings lists each device; signing one out ends its session at its next refresh, and
the phone then shows "This device was signed out" and the sign-in screen. Sign out everywhere also
unchanged.

### 4. Never ask for Face ID on an ended session (bug Sid reported)

Proven with a throwaway Jest probe on 2026-11-10 (not committed): with App Lock on and a saved
session, if the session ends while the cold-start launch animation is up, `AppLockGate`'s held
prompt still calls `authenticateAsync` once the animation goes, over the sign-in screen
(`authenticateAsync calls after sign-out = 1 | sign-in screen visible = true`). Cause: the
launch-release timer checks only `locked`, which is not reset when the token goes to null.

Also by design today (AuthContext bootstrap comment): the saved session is restored without asking
the server, so a dead session is unlocked with Face ID and only then fails.

Fix:
- The held prompt, and every prompt, fires only while a session is still present. Signing out
  clears the lock state.
- On cold start, before asking for PIN or biometrics, the app makes one refresh call. Session ended:
  straight to the sign-in screen, no prompt. Session alive: ask. No network: ask anyway (the app
  works with cached data offline), and if the session turns out dead later the existing path takes
  the user to sign-in.
- Regression tests for both.

### 5. Web idle sign-out (frontend)

- 15 minutes without mouse, keyboard, scroll or touch in any Fynora tab: a dialog "You'll be signed
  out in 60 seconds" with a "Stay signed in" button. No answer: proper sign-out (server logout, so the
  cookie session ends), then the sign-in page with a message saying it was for inactivity.
- Last activity time is shared between tabs (localStorage), so activity in one tab keeps every tab
  alive and a sign-out in one tab signs out all of them.
- Opening the site, or the laptop waking, more than 15 minutes after the last activity signs out
  before showing anything.
- Server-side web rules stay 24 hours idle and 30 days as a backstop.
- A long upload or import in progress counts as activity while it runs. (My call: being signed out
  mid-upload would lose work.)

### 6. Copy that changes

- `frontend/src/pages/settings/SecurityPane.tsx` Active Sessions text.
- `frontend/src/pages/Privacy.tsx` security paragraph (line ~455) states 24 hours / 30 days for all
  sessions. Needs the phone and web rules. Sid, 2026-11-10: change it in the same PR.
- Mobile Settings > Security copy for the PIN and biometrics.

## Release order

1. Backend (column, phone/web rules) can deploy first; with no new app build out it changes nothing,
   because no request sends the new lock header yet.
2. Web idle sign-out ships with the frontend deploy.
3. Phone PIN screen ships in the next store build, not an over-the-air update (it changes what a
   signed-in user must do on first open, and older builds must keep the 24-hour rule).

## Left to the builder

Exact screen layout of the PIN pad (follow existing mobile components and theme), header name, test
structure, and how the web dialog reuses existing modal components.
