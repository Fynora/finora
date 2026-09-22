# Android share-to-Fynora (design)

Date: 2026-09-22
Status: approved for planning
Scope: Android only. iOS is a deliberate follow-up, not part of this design.

## Problem

Today a user must save a bank statement (PDF/CSV) to their device first, then open
Fynora and use `pickStatement()` (`mobile/src/lib/statementFile.ts`) to browse for it.
Most bank apps expose a native "Share" action straight from the statement viewer. The
user should be able to hit Share in the bank app, pick Fynora, and land in the same
import flow Fynora already has — without an intermediate save-to-device step.

## Goals

- Fynora appears in Android's share sheet for PDF/CSV/plain-text content.
- A shared file is fed into the *existing* staging/confirm import pipeline unchanged —
  no new backend or parsing code.
- Works whether Fynora is already running, backgrounded, or not running at all
  (cold start).
- Matches existing single-file behavior (`multiple: false`) and existing error
  handling for unsupported files.

## Non-goals

- iOS Share Extension — separate future spec.
- Any new backend/parsing behavior — this is purely a new client-side entry point
  into the pipeline that already exists.
- Sharing images/screenshots — out of scope unless a follow-up decides otherwise.

## Library choice

Use `expo-share-intent` rather than a hand-rolled native module.

Alternatives considered:
- Hand-rolled Kotlin native module + Expo config plugin to add the Android
  intent-filter and bridge `onNewIntent` to JS. Rejected: more custom native code to
  maintain for a solved problem, and doesn't extend to iOS later without writing the
  Share Extension bridging from scratch too.
- `react-native-receive-sharing-intent`. Rejected: less actively maintained, weaker
  Expo SDK 57 / config-plugin support than `expo-share-intent`.

`expo-share-intent` is chosen because it: ships an Expo config plugin (fits the
existing `app.config.ts` pattern already used for Android App Links at
`app.config.ts:170`), handles both cold-start and warm-start delivery, and supports
iOS via the same API surface — so the iOS follow-up is a config change plus its own
UI work, not a re-architecture.

## Design

### 1. Config plugin

Add `expo-share-intent` to `app.config.ts` plugins, with its Android intent-filter
MIME types set to match `ACCEPTED_MIME` in `statementFile.ts:22`
(`application/pdf`, `text/csv`, `text/comma-separated-values`, `text/plain`). iOS
share-extension activation left disabled in this pass.

### 2. Shared format-detection helper

`statementFile.ts` currently determines `StatementFormat` (`CSV` | `PDF`) from a
picked file's name/extension inline inside `pickStatement()`. Extract that
extension-sniffing logic into a standalone helper (e.g.
`detectStatementFormat(name: string): StatementFormat | null`) that both
`pickStatement()` and the new share-intent handler call. No behavior change for the
existing picker path.

### 3. New `src/lib/shareIntent.ts`

Wraps `expo-share-intent`'s hook/API. Converts the library's incoming share payload
(a content URI + reported mime type) into the same `PickedStatement` shape
`pickStatement()` returns (`{file: RNFile, format: StatementFormat}`), using the
shared helper from (2) to decide format, and applying the same
copy-into-cache-directory handling `pickStatement()` already relies on (content://
URIs from other apps are not reliably readable later, same reasoning as the existing
comment at `statementFile.ts:33`).

If the shared content doesn't match a supported format, throw the same "unusable
selection" error `pickStatement()` throws today, so the Import screen's existing
error handling covers it with no new UI code.

If multiple files are shared at once, take the first and proceed — consistent with
today's `multiple: false` single-file picker behavior.

### 4. Wiring into navigation

On app start (cold start with a pending share) and on the share-intent hook firing
while the app is already running, navigate to the Import screen with the converted
file pre-loaded, feeding it into the exact staging/confirm code path
`ImportScreen.tsx:321` already uses when `pickStatement()` returns a result. No new
staging/confirm logic.

### 5. AppLock interaction

Wrap the share hand-off with `withShareSuppression` (`appLock.ts`), the same
suppression `pickStatement()` already uses, since handing off to/from the OS share
sheet backgrounds and re-foregrounds Fynora exactly like the document picker or
`Sharing.shareAsync` do today. Without this, `AppLockGate` would show a spurious
lock prompt on return from the share sheet.

### 6. Open question — logged-out state

What happens if a share arrives while the user is logged out (or the app requires
re-authentication) is not established by this design and must be answered against
the actual current auth/navigation code during planning, not assumed. The
implementation plan must trace the real behavior (e.g., does the app already have a
"resume deferred action after login" pattern, or does the shared file need to be
held/dropped) before writing code for it.

## Testing

- Unit test the new `shareIntent.ts` conversion logic and the extracted
  `detectStatementFormat` helper, following the existing pattern in
  `statementFile.test.ts`.
- Manual on-device verification via an EAS dev build (Expo Go cannot register
  custom intent-filters): share a real PDF from Google Drive/Files, and from an
  actual bank app if one is available on the test device, and confirm it lands in
  the existing staging/confirm flow correctly.

## Risks / things the plan must verify, not assume

- Exact shape of `expo-share-intent`'s payload (mime type reporting reliability may
  be as inconsistent as `DocumentPicker`'s, per the existing comment at
  `statementFile.ts:20` — the extension-based detection approach should carry over
  for the same reason).
- Interaction between the library's own intent-filter contribution and the existing
  App Links intent-filters already declared in `app.config.ts:170`, to confirm they
  don't conflict in the generated `AndroidManifest.xml`.
- Cold-start delivery timing relative to the app's existing auth bootstrap sequence.
