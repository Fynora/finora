# Mobile PIN unlock and web idle sign-out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (repo CLAUDE.md forbids subagent delegation for implementation and its verification). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phone users stay signed in for up to 90 days unused behind a required 4-digit PIN (optional Face ID / fingerprint); web users are signed out after 15 minutes of no activity; Face ID is never asked for a session that has already ended.

**Architecture:** The backend stamps each refresh-token session as `WEB` or `MOBILE` at sign-in and applies per-kind idle/absolute limits in `RefreshTokenService.rotate()`. The mobile app replaces its optional biometric App Lock with a required PIN lock (`pinLock.ts`) driven by `AppLockGate`, which first confirms the session with one refresh call. The web adds a client-side idle timer shared across tabs.

**Tech Stack:** Spring Boot + JPA + Flyway (backend), Expo SDK 58 / React Native + expo-local-authentication 58.0.2 + expo-secure-store + expo-crypto (mobile), React + Vite + Vitest (frontend).

**Spec:** `docs/superpowers/specs/2026-11-10-mobile-pin-unlock-and-web-idle-signout-design.md`

## Global Constraints

- Worktree `/Users/sid/Downloads/finora-mobile-pin-unlock`, branch `feature/mobile-pin-unlock`. Absolute paths for every command.
- Commit messages: no `Co-Authored-By` or any AI-attribution trailer.
- Mobile Jest: `cd /Users/sid/Downloads/finora-mobile-pin-unlock/mobile && NODE_OPTIONS=--experimental-vm-modules npx -y node@22 node_modules/.bin/jest <paths>`.
- Frontend: Node 22 (`npx -y node@22`).
- Phone session: idle 90 days (7776000000 ms), no absolute cap. Web session and old app builds: idle 24 h, absolute 30 days (unchanged).
- PIN: exactly 4 digits. 5 wrong in a row = full sign-out. Show tries left when 2 or fewer remain.
- Relock after more than 60 seconds in background; always on cold start.
- Biometric prompt: `disableDeviceFallback: true`; the fallback is the Fynora PIN.
- Web: 15 minutes idle, then a 60-second warning, then sign-out.
- Migration: `V310__refresh_token_session_kind.sql` (V309 is latest on origin/main; no open PR adds a migration, checked 2026-11-10). Re-check before pushing.
- `appLock.isLocked()`, `isAuthenticating()`, `isSharing()` keep their names and meaning; `mobile/src/lib/storeReview.ts` is not edited.

## Review Focus

1. A session minted before this ships (no kind stamped) must read as `WEB` — the migration default, asserted in Task 1's IT.
2. App killed mid-PIN-entry after 4 wrong tries: the 5th wrong try on relaunch must sign out — Task 3 test `persists wrong-try count across reloads`.
3. Offline cold start with a live session: must still ask for PIN, not sign out — Task 4 test `asks for the PIN when the session check cannot reach the server`.
4. Two web tabs, one active: the idle one must not sign out — Task 6 test `activity in another tab resets the timer`.
5. Laptop sleeps 2 hours with the tab open: on wake, sign out before showing data — Task 6 test `signs out on visibility return past the limit`.

---

### Task 1: Backend — session kind and per-kind limits

**Files:**
- Create: `backend/src/main/java/com/finora/entity/SessionKind.java`
- Create: `backend/src/main/resources/db/migration/V310__refresh_token_session_kind.sql`
- Modify: `backend/src/main/java/com/finora/entity/RefreshToken.java`, `config/JwtProperties.java`, `service/RefreshTokenService.java` (issue, rotate), `dto/DeviceSessionDto.java`, `controller/DeviceController.java:46`, `support/ClientIdentity.java` (constant + doc), `src/main/resources/application.yml` (`app.jwt`)
- Test: `backend/src/test/java/com/finora/service/RefreshTokenSessionLimitsTest.java`, plus one IT touching the real column (extend the existing refresh-token IT that boots Flyway, e.g. `RefreshTokenRotationConcurrencyIT` or the auth IT that signs in over HTTP)

**Interfaces:**
- Produces: `enum SessionKind { WEB, MOBILE }`; `RefreshToken.getSessionKind()/setSessionKind(SessionKind)`; `JwtProperties.getMobileIdleTimeoutMs()`, `getMobileAbsoluteSessionMs()`; `RefreshTokenService.issue(UUID userId, Instant sessionStartedAt, UUID sessionId, SessionKind kind)`; `ClientIdentity.APP_LOCK_HEADER = "X-App-Lock"` with value `"pin"`. HTTP contract for mobile: a sign-in request carrying `X-Client-Platform: MOBILE_ANDROID|MOBILE_IOS` **and** `X-App-Lock: pin` gets a `MOBILE` session.

- [ ] **Step 1: Write failing unit tests** in `RefreshTokenSessionLimitsTest`:
  - `mobileSessionSurvivesFiveDaysIdle` — token kind MOBILE, createdAt now−5d, mobile idle 90d → rotate succeeds, new token kind MOBILE, `expiresAt` ≥ now+90d−1min.
  - `mobileSessionEndsAfterMobileIdle` — kind MOBILE, createdAt now−91d → `AUTH_SESSION_IDLE`, row revoked.
  - `mobileSessionHasNoAbsoluteCapByDefault` — kind MOBILE, sessionStartedAt now−200d, createdAt now−1h, mobileAbsolute 0 → rotate succeeds.
  - `webSessionKeepsWebLimits` — kind WEB, createdAt now−25h with web idle 24h → `AUTH_SESSION_IDLE` even though mobile idle is 90d.
  - `signInFromPinBuildIsMobile` / `signInWithoutLockHeaderIsWeb` / `signInFromWebHeaderIsWeb` — mock `HttpServletRequest` headers; `issue(userId)` saves a row with the expected kind (capture via `ArgumentCaptor<RefreshToken>`).
  - `rotationKeepsKindEvenIfHeadersChange` — kind WEB row, request headers say MOBILE+pin → new row is WEB.
- [ ] **Step 2: Run** `cd /Users/sid/Downloads/finora-mobile-pin-unlock/backend && ./mvnw -q test -Dtest=RefreshTokenSessionLimitsTest` — expect compile failure / FAIL.
- [ ] **Step 3: Implement.** Migration: `ALTER TABLE refresh_tokens ADD COLUMN session_kind VARCHAR(16) NOT NULL DEFAULT 'WEB';`. Entity field `@Enumerated(EnumType.STRING) @Column(name = "session_kind", nullable = false) SessionKind sessionKind = SessionKind.WEB`. `issue(userId)` decides the kind from `request.getHeader(ClientIdentity.PLATFORM_HEADER)` / `APP_LOCK_HEADER` inside a try (no request context ⇒ WEB). `rotate()` passes `rt.getSessionKind()` to `issue(...)` (including the grace-replay branch) and picks idle/absolute limits by kind. MOBILE `expiresAt` = now + max(refreshExpirationMs, mobileIdleTimeoutMs). `application.yml`: `mobile-idle-timeout-ms: ${JWT_MOBILE_IDLE_TIMEOUT_MS:7776000000}`, `mobile-absolute-session-ms: ${JWT_MOBILE_ABSOLUTE_SESSION_MS:0}` with a comment pointing at the spec. Update the `idle-timeout-ms` comment ("one shared value for both platforms" is no longer true) and `ClientIdentity`'s "Nothing may authorise" paragraph to name this one exception and why it is safe (chosen only at sign-in, never upgrades an existing session). `DeviceSessionDto.from` uses the absolute cap for the row's kind (0 ⇒ null expiry).
- [ ] **Step 4: Run** the unit test class again — PASS; then `./mvnw -q test -Dtest='RefreshToken*Test,AuthService*Test,DeviceController*Test'` — PASS.
- [ ] **Step 5: IT** — add a test that signs in over HTTP with `X-Client-Platform: MOBILE_IOS` + `X-App-Lock: pin` and asserts the stored row's `session_kind = 'MOBILE'`, and one without headers asserting `'WEB'`; insert a row via SQL without the column and assert it reads `WEB`. Run it with `./mvnw -q verify -Dit.test=<ITName> -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` — PASS. (Never commit while `verify` runs.)
- [ ] **Step 6: Commit** `feat(auth): give phone sessions from the PIN-lock build a 90-day idle limit`.

### Task 2: Mobile — lock header and session check

**Files:**
- Modify: `mobile/src/lib/clientIdentity.ts` (export `APP_LOCK_HEADER = 'X-App-Lock'`), `mobile/src/api/client.ts` (`attachClientHeaders` sends `X-App-Lock: pin`; new export `verifySession`)
- Test: `mobile/src/api/client.test.ts`

**Interfaces:**
- Produces: `verifySession(): Promise<'alive' | 'ended' | 'offline'>` — runs the existing deduped `refreshAccessToken()`. Success ⇒ `'alive'`. Server answered with an error response ⇒ same handling as the response interceptor's failure path (`clearSessionAndRedirect(endedDeliberately(err))`) and `'ended'`. No response (network error, timeout) ⇒ `'offline'`, nothing cleared. `SessionEndedError` ⇒ `'ended'`.

- [ ] **Step 1: Failing tests** — `sends X-App-Lock: pin on every request`; `verifySession returns alive and stores the rotated pair`; `verifySession returns ended and clears the session on a 401 from /auth/refresh`; `verifySession returns offline and keeps tokens on a network error`; `verifySession returns offline on timeout`.
- [ ] **Step 2: Run** the client tests — FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `jest src/api/client.test.ts` — PASS.
- [ ] **Step 5: Commit** `feat(mobile): tell the server this build has the PIN lock, and check a session is alive`.

### Task 3: Mobile — PIN storage

**Files:**
- Create: `mobile/src/lib/pinLock.ts`, `mobile/src/lib/pinLock.test.ts`
- Modify: `mobile/src/lib/appLock.ts` (`authenticate` passes `disableDeviceFallback: true`, `fallbackLabel: 'Use PIN'`; the biometric preference stays under the existing `finora_app_lock_enabled` key, now meaning "unlock with biometrics")

**Interfaces:**
- Produces (all async, SecureStore-backed, keys `finora_pin_hash` (salt and hash in one value), `finora_pin_failures`):
  - `hasPin(): Promise<boolean>`
  - `setPin(pin: string): Promise<void>` — rejects unless `/^\d{4}$/`; new random salt (`expo-crypto` `getRandomBytesAsync(16)`), stores SHA-256 hex of `salt + pin`, resets failures.
  - `checkPin(pin: string): Promise<{ ok: true } | { ok: false; triesLeft: number }>` — constant 5-try budget, increments and persists failures before returning; ok resets to 0.
  - `resetFailures(): Promise<void>` (called after biometric success)
  - `clearPin(): Promise<void>` — removes all three keys.
  - `MAX_PIN_TRIES = 5`
  - `checkPin` and biometric `authenticate` both run inside `appLock`'s `authenticating` tracker so `isAuthenticating()` stays true during either.

- [ ] **Step 1: Failing tests** — `rejects non-4-digit PINs`; `accepts the right PIN and resets failures`; `counts down tries left`; `persists wrong-try count across reloads` (re-import module with `jest.isolateModules`, 4 wrong before, 1 wrong after ⇒ `triesLeft: 0`); `clearPin removes everything`; `stores no plain PIN` (no SecureStore value equals the PIN); `authenticate disables device fallback` (assert `authenticateAsync` called with `disableDeviceFallback: true`).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `jest src/lib/pinLock.test.ts src/lib/appLock.test.ts` — PASS (update `appLock.test.ts` expectations for the new options).
- [ ] **Step 5: Commit** `feat(mobile): store a 4-digit app PIN as a salted hash with a 5-try limit`.

### Task 4: Mobile — lock gate (PIN setup, PIN screen, relock rules, ended-session fix)

**Files:**
- Create: `mobile/src/components/PinPad.tsx` (digits 0–9, delete, 4 dots; optional biometric key), `mobile/src/components/PinSetup.tsx` (enter + confirm, mismatch message, then offers biometrics if `appLock.isSupported()`)
- Modify: `mobile/src/components/AppLockGate.tsx`, `mobile/src/context/AuthContext.tsx` (`clearLocalState` calls `pinLock.clearPin()` and `appLock.setEnabled(false)`)
- Test: `mobile/src/components/AppLockGate.test.tsx`, `PinPad.test.tsx`, `PinSetup.test.tsx`, `mobile/src/context/AuthContext.test.tsx`

**Interfaces:**
- Consumes: `verifySession` (Task 2), `pinLock.*` (Task 3), `appLock.authenticate/isEnabled/isSupported/setLockedFlag`.
- Behaviour:
  - Session present, no PIN set ⇒ `PinSetup` alone (nothing protected mounted); `isLocked()` true while shown.
  - Cold start with a session: show lock screen, call `verifySession()`; `'ended'` ⇒ no prompt (sign-in screen shows); `'alive' | 'offline'` ⇒ PIN screen, and if biometrics on, prompt once (after the launch animation, as today).
  - Every prompt path (including the launch-release timer) prompts only while `token !== null`; when `token` becomes null, `locked` resets to false.
  - Foreground after background: relock only if background lasted > 60 000 ms (record time on transition to background; keep the existing authenticate/share suppression).
  - PIN screen: wrong PIN shows "Wrong PIN" and, at ≤ 2 tries left, "N tries left"; `triesLeft === 0` ⇒ `logout()`. "Forgot PIN?" ⇒ confirm ⇒ `logout()`. Biometric success ⇒ `pinLock.resetFailures()`, unlock.
  - Copy (exact): title "Enter your Fynora PIN"; setup titles "Set a 4-digit PIN" / "Enter it again"; mismatch "The PINs didn't match. Try again."; biometric offer "Unlock with Face ID or fingerprint?" with "Turn on" / "Not now".
- [ ] **Step 1: Failing tests** (`AppLockGate.test.tsx`; adapt existing tests that assume the old optional biometric lock):
  - `does not prompt after the session ends during the launch animation` (the probe from the spec, asserting `authenticateAsync` not called).
  - `goes to sign-in without prompting when the cold-start session check says ended`.
  - `asks for the PIN when the session check cannot reach the server`.
  - `requires setting a PIN before showing the app`.
  - `does not relock after 59 seconds in background; relocks after 61 seconds` (mock `Date.now`).
  - `signs out after the fifth wrong PIN`.
  - `shows tries left at two or fewer`.
  - `isLocked() is true while the PIN screen or PIN setup shows`.
  - `AuthContext: sign-out clears the PIN and biometric choice`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Keep `AppLockGate`'s existing structure (stable wrapper, overlay after first open, back-button handling, alert/banner hiding); swap the lock screen body for `PinPad`.
- [ ] **Step 4: Run** `jest src/components src/context src/lib/storeReview.test.ts` — PASS.
- [ ] **Step 5: Commit** `feat(mobile): unlock with a PIN or biometrics, and never ask on a session that has ended`.

### Task 5: Mobile — Settings > Security

**Files:**
- Modify: `mobile/src/screens/settings/AppLockSection.tsx` (switch label "Unlock with Face ID or fingerprint", hidden when `isSupported()` is false; turning on requires a biometric success; add "Change PIN" row opening a modal: current PIN, then `PinSetup`), `mobile/src/screens/SettingsSecurityScreen.tsx` copy if it describes App Lock or session length
- Test: `mobile/src/screens/settings/AppLockSection.test.tsx`

- [ ] **Step 1: Failing tests** — `change PIN needs the current PIN`; `wrong current PIN counts toward the 5-try limit`; `biometric switch hidden without hardware`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(mobile): change PIN and biometric unlock in Settings`.

### Task 6: Web — idle sign-out

**Files:**
- Create: `frontend/src/lib/idleSignOut.ts` (timer + cross-tab), `frontend/src/components/IdleSignOutDialog.tsx` (mounted once inside `AuthProvider` in `App.tsx`, active only while `token`)
- Modify: `frontend/src/api/endpoints.ts` (upload `onUploadProgress` calls `markActivity()`), `frontend/src/api/client.ts` (export `SESSION_ENDED_REASON_KEY` if not exported), `frontend/src/App.tsx`
- Test: `frontend/src/lib/idleSignOut.test.ts`, `frontend/src/components/IdleSignOutDialog.test.tsx`

**Interfaces:**
- Produces: `IDLE_LIMIT_MS = 15 * 60_000`, `WARNING_MS = 60_000`, `LAST_ACTIVITY_KEY = 'finora_last_activity'`, `markActivity(): void` (writes `Date.now()` to localStorage, throttled to once per 5 s), `msSinceActivity(): number`.
- Behaviour: activity = `mousemove`, `mousedown`, `keydown`, `scroll`, `touchstart`, `wheel` on `window`. Warning dialog at 14 min ("You'll be signed out in 60 seconds" + "Stay signed in"). At 15 min: `logout()`, set `SESSION_ENDED_REASON_KEY` to "You were signed out after 15 minutes of inactivity.", navigate to `/auth`. On mount and on `visibilitychange` to visible: if `msSinceActivity() > IDLE_LIMIT_MS`, sign out before rendering protected content. `storage` events on `LAST_ACTIVITY_KEY` reset the timer; sign-out in one tab reaches others through the existing token-clear path (verify; add a `storage` listener if it does not).
- [ ] **Step 1: Failing tests** (fake timers): `shows the warning at 14 minutes`; `signs out at 15 minutes with the inactivity reason`; `Stay signed in resets the timer`; `activity in another tab resets the timer` (dispatch `StorageEvent`); `signs out on visibility return past the limit`; `signs out on load past the limit`; `does nothing while signed out`; `upload progress counts as activity`.
- [ ] **Step 2: Run** `cd /Users/sid/Downloads/finora-mobile-pin-unlock/frontend && npx -y node@22 node_modules/.bin/vitest run src/lib/idleSignOut.test.ts src/components/IdleSignOutDialog.test.tsx` — FAIL.
- [ ] **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(web): sign out after 15 minutes without activity`.

### Task 7: Copy — Settings and privacy policy

**Files:**
- Modify: `frontend/src/pages/settings/SecurityPane.tsx:102-104`, `frontend/src/pages/Privacy.tsx:~455` (and its "last updated" date if the page has one), any test asserting the old text.

- [ ] **Step 1:** Replace the 24 h / 30 day sentence with: on the web, signed out after 15 minutes without activity, and in any case after 24 hours idle or 30 days from sign-in; in the Fynora app, signed in until 90 days unused or you sign out, with a required 4-digit PIN (and optional Face ID / fingerprint) to open the app. Keep the rotation/compromise sentence.
- [ ] **Step 2: Run** the page tests (`vitest run src/pages/Privacy src/pages/settings`) — PASS.
- [ ] **Step 3: Commit** `docs(privacy): describe phone and web session limits`.

### Task 8: Full verification and PR

- [ ] Backend: `./mvnw -q verify` for auth/refresh-token tests (background; nothing else running against the jar).
- [ ] Mobile: full `jest`, `npx -y node@22 node_modules/.bin/tsc --noEmit`, `npm run lint` (Node 22).
- [ ] Frontend: full `vitest run`, `tsc -b` / `npm run typecheck`, `npm run lint`.
- [ ] Manual check on the iOS simulator: fresh sign-in ⇒ PIN setup ⇒ biometric offer; background 30 s ⇒ no lock; background 70 s ⇒ PIN; 5 wrong ⇒ sign-in screen; web tab idle 15 min (shorten via a dev-only override, not committed) ⇒ warning then sign-out.
- [ ] Re-check migration number against origin/main; merge origin/main if behind.
- [ ] Push, open PR (body: spec link, release order, the header trade-off, privacy wording change, store-build-only note). Check repo visibility; if PRIVATE, follow the full-ci rule.
