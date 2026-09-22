# iOS share-to-Fynora (design)

Date: 2026-09-22
Status: approved for planning
Scope: iOS only. Follow-up to the Android design
(`docs/superpowers/specs/2026-09-22-mobile-android-share-intent-design.md`), which deliberately
deferred iOS.

## Problem

The Android share-to-Fynora feature (PR #1711) lets a user hit Share in their bank app, pick
Fynora, and land directly in the existing import flow. iOS users get no equivalent today —
`disableIOS: true` in `app.config.ts`'s `expo-share-intent` plugin block explicitly turns this off
on iOS, so Fynora never appears in iOS's share sheet.

## Goals

- Fynora appears in iOS's share sheet for shared PDF and CSV files, matching Android's scope
  exactly (see "Non-goals").
- A shared file is fed into the *existing* staging/confirm import pipeline unchanged — the same
  `useShareIntentDeepLink.ts` hook and `ImportScreen.tsx` consumption path Android already uses,
  no new JS logic.
- Works whether Fynora is already running, backgrounded, or not running at all (cold start) — same
  guarantee Android already has via `expo-share-intent`'s `useShareIntentContext()`.
- Verified on a real iOS Simulator build in this repo, not left as an unexecuted checklist item —
  this machine has Xcode and Simulators available, which was not true for the Android work.

## Non-goals

- Any change to `androidIntentFilters` or Android behavior — this is additive, iOS-only.
- Any new backend/parsing behavior — purely enabling an existing client-side entry point on a
  second platform.
- Sharing images/screenshots, or any file type beyond PDF/CSV — out of scope unless a follow-up
  decides otherwise, matching Android's own scope decision.
- A visible Share Extension UI — `iosHideView` stays at its library default (`true`); the extension
  is invisible and redirects straight into the host app, mirroring Android's silent hand-off.
- Manual Apple Developer portal changes — the App Group and extension provisioning are expected to
  be handled by EAS Build's credential manager, the same way the existing app's credentials already
  are. This is an assumption to be confirmed during the plan's build-verification step, not a claim
  made in advance (see "Open questions").

## Library

Already decided in the Android design (same library, `expo-share-intent@^8.0.1`, already installed
— no new dependency). That design's own rationale: "supports iOS via the same API surface — so the
iOS follow-up is a config change plus its own UI work, not a re-architecture." This spec confirms
the "config change" half; the "own UI work" half turns out not to be needed at all, since
`iosHideView` defaulting to `true` means there is no extension UI to build.

## Design

### 1. Config plugin change

`app.config.ts`'s existing `expo-share-intent` plugin entry (`app.config.ts:294-317`):

- Remove `disableIOS: true`.
- Add `iosActivationRules`: a custom `NSExtensionActivationRule` predicate string matching only
  the PDF and CSV UTIs (`com.adobe.pdf`, `public.comma-separated-values`), so Fynora's share
  extension activates only for those file types — exact parity with Android's tight
  `androidIntentFilters` allowlist, not the library's own default (which matches shared web
  links/pages, not files) and not the library's blunt `NSExtensionActivationSupportsFileWithMaxCount`
  option either (which would match any file type).

Read directly from `expo-share-intent@8.0.1`'s plugin source
(`node_modules/expo-share-intent/plugin/build/ios/`), not assumed from its README:

- `withIosAppEntitlements.js` adds `com.apple.security.application-groups` to the main app
  target's entitlements automatically once `disableIOS` is false — no manual entitlements file.
- `constants.js`: default App Group is `group.<bundleIdentifier>` (i.e. `group.com.fynora.app` /
  `group.com.fynora.app.dev`, one per build variant, consistent with the existing per-variant
  bundle ID split at `app.config.ts:111`) and default extension bundle ID is
  `<bundleIdentifier>.share-extension`. No existing App Group or extension target exists in this
  repo today (checked `app.config.ts` and `eas.json`), so there's no collision — defaults are used
  as-is, no `iosAppGroupIdentifier` override.
- `withIosShareExtensionConfig.js` registers the new extension target under
  `extra.eas.build.experimental.ios.appExtensions` — EAS Build's own experimental app-extensions
  support, which provisions the extension's own credentials at build time rather than requiring a
  manually-created App ID in the Apple Developer portal.
- `ShareExtensionViewController.swift`'s generic file-handling path (`handleFileURL`) preserves the
  original shared filename in `SharedMediaFile.fileName` (only the on-disk copy inside the App
  Group container gets a UUID-based name, to avoid collisions) — this is the field
  `detectFormatFromShareIntentFile()` already reads first in its fallback chain.

### 2. JS side: no changes

Traced `useShareIntentDeepLink.ts` and confirmed every field it reads off `ShareIntentFile`
(`fileName`, `mimeType`, `path`) is populated the same way on iOS as Android, through
`expo-share-intent`'s own cross-platform `useShareIntentContext()` hook — the same hook Android
already uses, already wrapped by `<ShareIntentProvider>` in `App.tsx`. `ImportScreen.tsx`'s
consumption effect, `statementFile.ts`'s `detectStatementFormat()`, and the `SharedStatementFile`/
`SharedStatementError` types in `navigation/types.ts` all stay exactly as they are.

No existing Jest test (`useShareIntentDeepLink.test.ts`, `ImportScreen.test.tsx`, `App.test.tsx`)
needs a change either — they already mock `expo-share-intent` at the JS-module boundary, which
doesn't distinguish platforms.

### 3. Verification

Unlike the Android work (no device available, Task 8's on-device checklist was left unexecuted),
this machine has Xcode 27 and iOS Simulators installed. The implementation plan's verification step
will:

1. `expo prebuild --platform ios` to regenerate the native project with the new plugin config.
2. Build a local iOS Simulator dev client (`expo run:ios` or an EAS local build) and install it on
   a Simulator.
3. From the Simulator's own Files app (or another app capable of a real iOS share sheet), share a
   real PDF and a real CSV, confirm Fynora appears as a share target for both, and confirm tapping
   it lands in Fynora's Import screen with the correct file staged — the same outcome Android's own
   Maestro-free manual check would have covered.
4. Share an unsupported file type (e.g. a `.jpg`) from the same source and confirm Fynora does
   *not* appear as a target — proving the custom `iosActivationRules` predicate is scoped correctly,
   not just that the extension exists.
5. Confirm the existing Jest suite, lint, and typecheck all still pass with the config-plugin
   change in place — no code changes are expected to affect them, but this is a "verify by running
   it" project, not a "verify by expecting it" one.

This is real, executed verification, not a deferred checklist — the closest thing to Android's own
Task 8 that this platform actually allows.

## Open questions

- Whether EAS Build's experimental app-extensions support provisions the new App Group and
  extension credentials automatically on the first `eas build --profile development --platform ios`
  after this change, the same way it already manages this app's existing iOS credentials. Not
  confirmed in advance — will be confirmed (or found to need manual EAS credential-manager steps)
  when that build is actually run, and recorded in the implementation plan's own results, not
  assumed here.
