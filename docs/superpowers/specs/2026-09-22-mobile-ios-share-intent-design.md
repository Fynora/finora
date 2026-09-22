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
  the PDF and CSV UTIs, so Fynora's share extension activates only for those file types — exact
  parity with Android's tight `androidIntentFilters` allowlist, not the library's own default
  (which matches shared web links/pages, not files) and not the library's blunt
  `NSExtensionActivationSupportsFileWithMaxCount` option either (which would match any file type).

  The exact PDF UTI (`com.adobe.pdf`) and CSV UTI were confirmed by running a Swift snippet against
  this machine's own `UniformTypeIdentifiers` framework (`xcrun swift`), not taken from the
  library's README or assumed from the file extension:

  ```
  csv -> identifier=public.comma-separated-values-text preferredMIMEType=text/csv conformsToCSV=true
  pdf -> identifier=com.adobe.pdf preferredMIMEType=application/pdf
  ```

  The system UTI for CSV is `public.comma-separated-values-text` — note the `-text` suffix. An
  earlier draft of this predicate used `public.comma-separated-values` (no suffix), which does not
  exist as a real system UTI on this OS version and would have silently failed to match any real
  CSV file. The predicate matches by **conformance** (`UTI-CONFORMS-TO`), not exact identifier
  equality, so a source app that tags its CSV with a more specific subtype (if one ever exists)
  still matches; this does not protect against a source app that mistags a CSV under a completely
  unrelated or generic UTI (e.g. `public.data`), which is a real but unverifiable-in-advance risk of
  any third-party file provider, not something this predicate can control.

  The predicate also constrains each extension item to exactly one attachment, so a multi-file
  share (e.g. selecting two PDFs in the Files app and sharing both at once) does **not** activate
  Fynora — matching Android's own behavior, where `androidMultiIntentFilters` is left at its empty
  default and Fynora likewise never appears for a multi-select share. This was an open question in
  an earlier draft of this spec; the decision is: single-file-only on both platforms, not "accept
  multiple and silently import only the first," which would otherwise happen because
  `useShareIntentDeepLink.ts`'s stash effect already only ever reads `shareIntent.files?.[0]`.

  ```
  SUBQUERY (
    extensionItems,
    $extensionItem,
    $extensionItem.attachments.@count == 1 AND SUBQUERY (
      $extensionItem.attachments,
      $attachment,
      ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO "com.adobe.pdf"
        OR ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO "public.comma-separated-values-text"
    ).@count == 1
  ).@count == extensionItems.@count
  ```

  This predicate string is the implementation plan's own responsibility to encode literally (as
  `iosActivationRules`, a raw string rather than the object form) and to verify against a real
  Simulator share — see "Verification" below. `UTI-CONFORMS-TO` predicate syntax is a documented
  Apple mechanism (`NSExtensionActivationRule` reference), but this exact predicate has not yet
  been run against a real share sheet in this repo; that happens during plan execution, not during
  this spec.

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
this machine has Xcode 27 and iOS Simulators installed. `expo prebuild --platform ios` cannot run
in a fresh worktree as-is, though: `mobile/GoogleService-Info.plist` is a real Firebase credential
file, gitignored and present only in the primary checkout, not copied into fresh worktrees (a known
gap — the Android work never needed it since only `google-services.json`, the Android counterpart,
was required there via the Maestro CI fixture). Copying a real credential file between directories
needs the user's go-ahead first; the implementation plan's first verification task asks for that
explicitly rather than doing it silently.

Once that file is available, the implementation plan's verification step will:

1. `expo prebuild --platform ios` to regenerate the native project with the new plugin config, then
   inspect the generated `ios/*/*.entitlements` and `ios/ShareExtension/ShareExtension.entitlements`
   files directly to confirm `com.apple.security.application-groups` is present in **both** the main
   app target and the extension target, and that both reference the same App Group string — the
   review point that this should be inspected, not trusted from the plugin source alone.
2. Also inspect the generated `ios/ShareExtension/ShareExtension-Info.plist` to confirm the literal
   `NSExtensionActivationRule` string matches the predicate above, byte for byte — config-plugin
   string interpolation bugs are a real, checkable failure mode.
3. Build a local iOS Simulator dev client (`expo run:ios` or an EAS local build) and install it on
   a Simulator.
4. From the Simulator's own Files app (or another app capable of a real iOS share sheet), share a
   real PDF and a real CSV **sourced from at least two different providers each** (e.g. Files app
   and Mail, not just one) — the review's concern that different providers can tag the same file
   type under different UTIs is real and worth more than a single source confirming. Confirm Fynora
   appears as a share target for both, and confirm tapping it lands in Fynora's Import screen with
   the correct file staged.
5. Share an unsupported file type (e.g. a `.jpg`) and confirm Fynora does *not* appear as a target.
6. Multi-select two files (e.g. two PDFs) from the same source and confirm Fynora does *not* appear
   as a target, per the single-file-only decision above.
7. Explicitly test all three app-state scenarios, not just one, even though `useShareIntentContext()`
   is expected to behave identically to Android for each:
   - Fynora already open in the foreground when the share happens.
   - Fynora backgrounded (not terminated) when the share happens.
   - Fynora fully terminated (force-quit) when the share happens — cold start.
8. After a successful import via share, relaunch the app normally (not via another share) and
   confirm no stale share payload re-triggers a second import — `useShareIntentDeepLink.ts`'s
   `PENDING_SHARE_KEY` AsyncStorage entry is expected to already be cleared on consumption (the same
   mechanism Android relies on), but this needs confirming on iOS specifically rather than assumed
   to carry over unchanged.
9. Confirm the existing Jest suite, lint, and typecheck all still pass with the config-plugin
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
