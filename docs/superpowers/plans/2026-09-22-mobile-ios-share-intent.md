# iOS share-to-Fynora Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Fynora appear in iOS's native share sheet for PDF and CSV files, landing the shared
file in the existing import pipeline — the iOS counterpart to the already-shipped Android feature
(PR #1711).

**Architecture:** One config-plugin change to `app.config.ts` (enable `expo-share-intent`'s iOS
Share Extension, scoped to PDF/CSV only via a custom `NSExtensionActivationRule` predicate). No new
JS code — `useShareIntentDeepLink.ts` and `ImportScreen.tsx` already consume `expo-share-intent`'s
cross-platform hook and need no iOS-specific branch. The bulk of this plan is native build and
on-device-equivalent verification (this machine has Xcode + Simulators, unlike the Android work),
not new application code.

**Tech Stack:** Expo SDK ~57.0.20 / `expo-share-intent@^8.0.1` (already installed) / Xcode 27 / iOS
Simulator.

**Spec:** `docs/superpowers/specs/2026-09-22-mobile-ios-share-intent-design.md`

## Global Constraints

- iOS scope is PDF + CSV only — no images, plain text, URLs, or any other file type (spec "Goals").
- No visible Share Extension UI — `iosHideView` stays at the library default (`true`); the
  extension silently redirects into the host app.
- No JS/TypeScript source changes outside `app.config.ts` — `useShareIntentDeepLink.ts`,
  `ImportScreen.tsx`, `statementFile.ts`, `navigation/types.ts` are all out of scope for edits.
- Single-file shares only — a multi-select share must **not** activate Fynora (spec "Design > 1").
- The `iosActivationRules` predicate must use `UTI-CONFORMS-TO "com.adobe.pdf"` for PDF and
  `UTI-CONFORMS-TO "public.comma-separated-values-text"` for CSV — these exact UTI strings were
  confirmed on this machine via `xcrun swift` against `UniformTypeIdentifiers`, not assumed (spec
  "Design > 1").
- `mobile/GoogleService-Info.plist` is a real Firebase credential file, gitignored and present only
  in the primary checkout (`/Users/sid/Downloads/finora/mobile/GoogleService-Info.plist`). Copying
  it into this worktree needs the user's explicit go-ahead — never copy it silently.
- All commits: no `Co-Authored-By: Claude` trailer (repo hard rule, `CLAUDE.md`).
- Work happens in this worktree (`/Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent`,
  branch `worktree-mobile-ios-share-intent`) — never the primary checkout.

---

## Task 1: iOS build environment bring-up (baseline, before any code change)

**Files:** None tracked — this task copies an untracked, gitignored credential file and runs a
native build to establish a working baseline before Task 2's actual change.

**Interfaces:**
- Produces: a working `ios/` native project directory (from `expo prebuild`) and a Fynora dev build
  installed on a booted Simulator, confirmed launching normally — the baseline every later task's
  "did I break something" comparison is made against.

- [ ] **Step 1: Ask the user for permission to copy the Firebase credential file**

  Ask exactly this in chat before touching anything:

  > "`expo prebuild --platform ios` needs `mobile/GoogleService-Info.plist`, a real Firebase
  > credential file that's gitignored and only exists in the primary checkout
  > (`/Users/sid/Downloads/finora/mobile/GoogleService-Info.plist`), not in this fresh worktree. OK
  > to copy it into this worktree's `mobile/` directory so I can build and test the iOS Share
  > Extension locally?"

  Do not proceed to Step 2 without an explicit yes.

- [ ] **Step 2: Copy the file**

  ```bash
  cp /Users/sid/Downloads/finora/mobile/GoogleService-Info.plist /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile/GoogleService-Info.plist
  ```

  Confirm it landed and is still gitignored (must never be staged):

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  git status --short GoogleService-Info.plist
  ```

  Expected: no output (gitignored files don't show in `git status` for an untracked-but-ignored
  path). If it shows as untracked (not ignored), STOP — the `.gitignore` entry may have drifted;
  do not commit this file under any circumstance.

- [ ] **Step 3: Baseline prebuild, before the config change**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  npx expo prebuild --platform ios --clean
  ```

  Expected: completes without error, creates an `ios/` directory. This confirms the environment
  itself works, on the *current* (pre-change) config — so if Task 2's prebuild fails later, the
  failure can be attributed to the config change, not to environment drift.

- [ ] **Step 4: Baseline build and launch on Simulator**

  ```bash
  xcrun simctl list devices available | grep "iPhone 17 Pro"
  ```

  Boot it if not already booted (the `(Shutdown)` suffix means not booted):

  ```bash
  xcrun simctl boot "iPhone 17 Pro"
  ```

  Then, from `mobile/`:

  ```bash
  npx expo run:ios --device "iPhone 17 Pro"
  ```

  Expected: builds (first build is slow — CocoaPods install + full Xcode compile, several minutes),
  installs, and launches Fynora on the simulator to its normal entry screen (login or dashboard,
  depending on any persisted session in this fresh Simulator — a fresh Simulator has no persisted
  session, so expect the login/onboarding screen).

- [ ] **Step 5: Confirm the baseline visually**

  Use the iOS Simulator control tool: `attach` (to open the live panel), then `screenshot`, and
  confirm the app rendered its normal entry screen with no crash or blank screen. This screenshot is
  the "before" reference for Task 3's regression check.

No commit — this task changes no tracked files.

---

## Task 2: iOS share-intent config plugin change

**Files:**
- Modify: `mobile/app.config.ts:294-317` (the existing `expo-share-intent` plugin entry)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: the `expo-share-intent` plugin config that Task 3's `expo prebuild` reads to generate
  the native `ShareExtension` target, its entitlements, and its `Info.plist`.

- [ ] **Step 1: Read the current plugin block**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  sed -n '290,320p' app.config.ts
  ```

  Confirm it still matches this (if it has drifted, stop and re-read the full block before editing):

  ```ts
      'expo-share-intent',
      {
        // expo-share-intent's own published option type is a literal union of wildcard families
        // ("text/*" | "image/*" | "video/*" | "*/*") -- there is no typed option for an exact mime
        // type. Read the plugin's generator directly (withAndroidIntentFilters.ts, expo-share-intent
        // 8.0.1): it writes whatever strings this array holds straight into each
        // <data android:mimeType="..."/> entry with no validation against that union, so an exact
        // list works at the manifest level even though it is narrower than the published type.
        // Deliberately NOT "text/*" or "*/*": those would also register Fynora as a share target for
        // arbitrary text snippets, images, or literally anything else shared on the device.
        // Deliberately NOT "text/plain" either, unlike statementFile.ts's own ACCEPTED_MIME (the
        // document-picker's file-browser filter, a different mechanism where this doesn't apply):
        // ExpoShareIntentModule.kt's handleShareIntent routes any intent.type starting with
        // "text/plain" into shareIntent.text (reading EXTRA_TEXT) and never inspects EXTRA_STREAM/
        // files for it at all -- registering it here would make Fynora appear as a share target for
        // arbitrary shared text with no result when tapped, and would never actually deliver a CSV a
        // provider happens to report as text/plain through the `files` path this feature reads.
        // Verify after any expo-share-intent upgrade against a fresh `expo prebuild` manifest.
        androidIntentFilters: ['application/pdf', 'text/csv', 'text/comma-separated-values'],
        // iOS Share Extension is a separate, deliberately deferred piece of work -- see the design
        // spec's non-goals. This flag skips all of the plugin's iOS-side mods (Xcode target,
        // entitlements, Info.plist) entirely.
        disableIOS: true,
      },
    ],
  ```

- [ ] **Step 2: Replace the `disableIOS` line with the iOS activation-rule config**

  Replace:

  ```ts
        // iOS Share Extension is a separate, deliberately deferred piece of work -- see the design
        // spec's non-goals. This flag skips all of the plugin's iOS-side mods (Xcode target,
        // entitlements, Info.plist) entirely.
        disableIOS: true,
  ```

  with:

  ```ts
        // iOS Share Extension. UTIs confirmed by running a Swift snippet against this machine's own
        // UniformTypeIdentifiers framework (`xcrun swift`), not taken from the library's README:
        // CSV's real system UTI is "public.comma-separated-values-text" (note the "-text" suffix --
        // "public.comma-separated-values" without it is not a real system UTI and would silently
        // match nothing). Matches by UTI-CONFORMS-TO, not exact identifier equality, so a more
        // specific subtype would still match. The `$extensionItem.attachments.@count == 1` clause
        // constrains each extension item to exactly one attachment, so a multi-file share does not
        // activate Fynora -- mirroring androidMultiIntentFilters' empty default above, so both
        // platforms are single-file-only (see the iOS design spec's "Design > 1. Config plugin
        // change" for the full reasoning, including why this differs from the library's own default
        // rule, which matches shared web links/pages rather than files).
        // Verify this predicate against a real Simulator share after any expo-share-intent upgrade,
        // the same way androidIntentFilters above is verified against a fresh manifest.
        iosActivationRules:
          'SUBQUERY (' +
          'extensionItems, $extensionItem, ' +
          '$extensionItem.attachments.@count == 1 AND SUBQUERY (' +
          '$extensionItem.attachments, $attachment, ' +
          'ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO "com.adobe.pdf" OR ' +
          'ANY $attachment.registeredTypeIdentifiers UTI-CONFORMS-TO "public.comma-separated-values-text"' +
          ').@count == 1' +
          ').@count == extensionItems.@count',
  ```

  The full block's closing lines (`},` then `],`) are unchanged — only the `disableIOS: true,` line
  and its three-line comment are replaced.

- [ ] **Step 3: Typecheck**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  npm run typecheck
  ```

  Expected: passes clean. `iosActivationRules` accepts `string | { [key: string]: number | boolean
  | string }` per `node_modules/expo-share-intent/plugin/build/types.d.ts:5-7` — a plain string is a
  valid value for this field.

- [ ] **Step 4: Prebuild with the new config**

  ```bash
  npx expo prebuild --platform ios --clean
  ```

  Expected: completes without error and creates `ios/ShareExtension/` (did not exist in Task 1's
  baseline prebuild).

- [ ] **Step 5: Inspect the generated entitlements on both targets**

  ```bash
  cat ios/Finora/Finora.entitlements
  cat ios/ShareExtension/ShareExtension.entitlements
  ```

  Expected in **both** files: a `com.apple.security.application-groups` key whose array contains
  the identical string, expected to be `group.com.fynora.app.dev` (this build uses the `isDev`
  bundle identifier `com.fynora.app.dev` per `app.config.ts:111`, and the plugin's default App
  Group is `group.<bundleIdentifier>` per `node_modules/expo-share-intent/plugin/build/ios/constants.js`).
  If the two files list different App Group strings, STOP — the extension and host app would not
  be able to share data, and the feature would silently fail to hand off the file.

- [ ] **Step 6: Inspect the generated extension Info.plist**

  ```bash
  cat ios/ShareExtension/ShareExtension-Info.plist
  ```

  Find the `NSExtensionActivationRule` key and confirm its string value is byte-for-byte the
  predicate written in Step 2 (whitespace differences from plist formatting are fine; the predicate
  clauses and quoted UTI strings must match exactly). If it differs, the config-plugin's string
  interpolation did something unexpected — stop and diagnose before continuing to Task 3.

- [ ] **Step 7: Commit**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent
  git add mobile/app.config.ts
  git commit -m "feat(mobile): enable the iOS share-to-Fynora Share Extension"
  ```

  Note: `ios/` is not committed — it's a `expo prebuild`-generated directory, regenerated from
  `app.config.ts` on every build (confirm it's gitignored: `git status --short mobile/ios` should
  show nothing).

---

## Task 3: Build and install the dev client, confirm no regression

**Files:** None tracked — native build only.

**Interfaces:**
- Consumes: Task 2's `app.config.ts` change.
- Produces: a Fynora dev build with the Share Extension target installed on the Simulator, used by
  Task 4 and Task 5.

- [ ] **Step 1: Build and install**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  npx expo run:ios --device "iPhone 17 Pro"
  ```

  Expected: builds both the `Finora` target and the new `ShareExtension` target, installs, and
  launches. Watch the build output for the `ShareExtension` target compiling — its absence would
  mean the config plugin didn't register the target (contradicts Task 2 Step 4's `ios/ShareExtension/`
  directory check, so this would be a real inconsistency worth stopping on, not a flake to retry
  past).

- [ ] **Step 2: Confirm no regression to normal app launch**

  Use the iOS Simulator control tool: `attach`, `screenshot`. Compare against Task 1 Step 5's
  baseline screenshot — same entry screen, no crash, no blank/white screen. Use `inspect` (marker:
  a visible label from the entry screen, e.g. a login button's text) to confirm the screen is
  actually interactive, not just visually rendered.

No commit — no tracked files changed.

---

## Task 4: Share-sheet activation verification

**Files:** None tracked — manual/scripted verification only, using existing fixtures:
- `mobile/.maestro/fixtures/maestro-statement.csv` (already in the repo, used by Android's Maestro
  suite — reused here rather than fabricating a new CSV).
- `backend/src/test/resources/pdf/separate_debit_credit_balance_sample.pdf` (already in the repo, a
  real backend test fixture — reused here rather than fabricating a new PDF).

**Interfaces:**
- Consumes: Task 3's installed dev build.
- Produces: confirmed evidence (screenshots, `inspect` output) that the activation-rule predicate
  from Task 2 does what Task 2's spec section claims — this is the task that actually answers "does
  the predicate work," not just "did prebuild accept the string."

- [ ] **Step 1: Serve the fixture files over local HTTP for the Simulator's Safari to reach**

  The Simulator shares the host Mac's network, so a plain local server works:

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent
  mkdir -p /tmp/ios-share-fixtures
  cp mobile/.maestro/fixtures/maestro-statement.csv /tmp/ios-share-fixtures/
  cp backend/src/test/resources/pdf/separate_debit_credit_balance_sample.pdf /tmp/ios-share-fixtures/
  cd /tmp/ios-share-fixtures && python3 -m http.server 8123
  ```

  Leave this running in the background for the rest of this task.

- [ ] **Step 2: Positive test — CSV via Safari**

  Using the iOS Simulator control tool: `open_url` to
  `http://localhost:8123/maestro-statement.csv`, wait for Safari to render its file-preview screen,
  then `inspect` to find and `tap` the Share icon (typically top-right or in the bottom toolbar
  depending on iOS version — `inspect` first rather than guessing coordinates). `screenshot` the
  resulting share sheet.

  Expected: Fynora appears as a share-target app icon in the sheet. Tap it. Expected: Fynora opens
  (or comes to foreground) and lands on the Import screen with the CSV staged — confirm via
  `inspect` (marker: text matching the staged filename or the Import screen's own confirm-screen
  copy) rather than screenshot alone, since a screenshot can't prove the *file* was recognized
  correctly versus just that some screen rendered.

- [ ] **Step 3: Positive test — PDF via Safari**

  Same as Step 2, using `http://localhost:8123/separate_debit_credit_balance_sample.pdf`. Expected:
  same outcome — Fynora appears in the share sheet, tapping it lands on Import with the PDF staged.

- [ ] **Step 4: Negative test — unsupported file type**

  Create a throwaway `.txt` file, serve it the same way (`echo "not a statement" >
  /tmp/ios-share-fixtures/note.txt`, already served by the running server), open it via
  `open_url` to `http://localhost:8123/note.txt`, tap Share, `screenshot`. Expected: Fynora does
  **not** appear as a share target. If it does, the activation-rule predicate is matching too
  broadly — stop and re-examine Task 2 Step 6's Info.plist inspection before proceeding.

- [ ] **Step 5: Negative test — multi-file share**

  This needs two files selected simultaneously, which Safari's per-page share sheet cannot do (it
  only ever shares the one open page/file). Use the Simulator's Files app instead: this step
  requires manually dragging both fixture files from Finder onto the Simulator's Files app window
  (a real macOS-level drag between two applications — not reachable through the Simulator control
  tool's in-simulator touch primitives, which only act on the Simulator's own screen). Do this
  manually: with the Simulator window and a Finder window showing `/tmp/ios-share-fixtures/` both
  visible, drag `maestro-statement.csv` and `separate_debit_credit_balance_sample.pdf` onto the
  Simulator's Files app icon or an open Files app window, confirming the "On My iPhone" location.
  Then open the Files app, multi-select both (long-press one, then tap the other while the
  selection UI is active), tap Share, `screenshot`. Expected: Fynora does **not** appear as a share
  target.

- [ ] **Step 6: Positive test from a second provider — Files app (single file)**

  Using the same Files app "On My iPhone" location from Step 5, select only the CSV, tap Share,
  `screenshot`. Expected: Fynora appears as a share target and the flow completes the same as Step
  2 — confirming the activation rule and hand-off work from a source other than Safari, per the
  spec's "at least two different providers" requirement.

- [ ] **Step 7: Stop the local server**

  ```bash
  # kill the python3 -m http.server process started in Step 1
  ```

No commit — no tracked files changed.

---

## Task 5: App-state matrix, stale-payload check, full regression, and PR

**Files:**
- Modify: `docs/superpowers/plans/2026-09-22-mobile-ios-share-intent.md` (this file — record real
  results in a "Post-implementation results" section, the same pattern the Android plan used).

**Interfaces:**
- Consumes: Task 4's confirmed-working share flow.
- Produces: final verified state, ready for PR.

- [ ] **Step 1: Foreground scenario**

  With Fynora already open (from Task 4's testing), repeat Task 4 Step 2's CSV share. Expected:
  works identically to Task 4 — this is the scenario Task 4 already exercised, recorded here
  explicitly for the matrix.

- [ ] **Step 2: Backgrounded scenario**

  Press the Simulator's home button (`button` action, `name: "HOME"`) to background Fynora without
  terminating it, then repeat the CSV share via Safari. Expected: Fynora comes to the foreground
  and lands on Import with the CSV staged, same as the foreground case.

- [ ] **Step 3: Terminated (cold-start) scenario**

  Force-quit Fynora: from the app switcher (double-press equivalent or long-press Home then swipe
  up on Fynora's card — `inspect` the app-switcher screen to find the right gesture target), or via
  `xcrun simctl terminate <udid> com.fynora.app.dev`. Confirm it's no longer running (`xcrun simctl
  spawn <udid> launchctl list | grep fynora` should show nothing, or simply confirm via a fresh
  `screenshot` that the home screen shows, not Fynora). Repeat the CSV share via Safari. Expected:
  Fynora launches fresh (cold start) and lands on Import with the CSV staged — matching Android's
  own cold-start guarantee via the same `useShareIntentContext()` mechanism.

- [ ] **Step 4: Stale-payload check**

  After Step 3's successful cold-start import completes, force-quit Fynora again and relaunch it
  normally (tap its home-screen icon directly, not via a share). `inspect` the resulting screen.
  Expected: Fynora opens to its normal entry point (dashboard, if a session persisted from earlier
  testing, or login otherwise) — **not** a re-triggered Import screen with the same CSV staged
  again. If the same file re-imports, `useShareIntentDeepLink.ts`'s `PENDING_SHARE_KEY` AsyncStorage
  entry did not get cleared on consumption for this platform — a real bug to investigate before
  calling this task done, not something to note and move past.

- [ ] **Step 5: Full regression suite**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent/mobile
  npm run typecheck
  npm run lint
  NODE_OPTIONS=--experimental-vm-modules npm test
  ```

  Expected: all pass clean, identical to pre-change baseline (no JS files changed, so no test
  count/content change is expected — confirm the totals match what `main` already has, not just
  "green").

- [ ] **Step 6: Record real results in this plan file**

  Append a "Post-implementation results" section to this file (a sibling to Android's own plan's
  "Post-implementation bug hunt" section) stating, for each of Task 4 and Task 5's steps: what was
  actually observed, not what was expected — including the exact App Group string from Task 2 Step
  5, and whether the EAS-provisioning assumption in the spec's "Open questions" was confirmed (this
  plan's tasks use a local Xcode build, not `eas build`, so that specific question stays open for
  whoever runs the first `eas build --profile development --platform ios` after this merges — say
  so explicitly rather than letting it look resolved).

- [ ] **Step 7: Commit the results update**

  ```bash
  cd /Users/sid/Downloads/finora/.claude/worktrees/mobile-ios-share-intent
  git add docs/superpowers/plans/2026-09-22-mobile-ios-share-intent.md
  git commit -m "docs: record iOS share-intent verification results"
  ```

- [ ] **Step 8: Push and open a PR**

  ```bash
  git push -u origin worktree-mobile-ios-share-intent
  gh pr create --title "feat(mobile): iOS share-to-Fynora statement import" --body "$(cat <<'EOF'
  ## Summary
  - Enables Fynora as an iOS share-sheet target for PDF/CSV statements, the iOS counterpart to the
    Android share-intent feature (#1711).
  - Single config-plugin change (`app.config.ts`): iOS Share Extension enabled, scoped to PDF/CSV
    only via a custom `NSExtensionActivationRule` predicate (UTIs confirmed against this machine's
    own UniformTypeIdentifiers framework, not assumed from the library's README).
  - No JS/TypeScript changes — `useShareIntentDeepLink.ts` and `ImportScreen.tsx` already handle
    both platforms through `expo-share-intent`'s cross-platform hook.

  ## Verification
  - Full on-device-equivalent verification via local Xcode build + iOS Simulator (see the plan's
    "Post-implementation results" for exact findings): positive share from two providers
    (Safari, Files app), negative test for unsupported file types, negative test for multi-file
    shares, all three app-state scenarios (foreground/backgrounded/terminated), and a stale-payload
    check after a successful import.
  - EAS-managed build provisioning (App Group + extension credentials on `eas build`) was **not**
    exercised by this PR's local-Xcode verification — flagged as still open, to be confirmed on the
    first `eas build --profile development --platform ios` after this merges.

  See `docs/superpowers/specs/2026-09-22-mobile-ios-share-intent-design.md` and
  `docs/superpowers/plans/2026-09-22-mobile-ios-share-intent.md` for full design/verification detail.
  EOF
  )"
  ```

---

## Post-implementation results

Real findings from actually executing this plan, not what was expected — see this repo's own
"No guessing" standing rule.

### Task 1 — Baseline

Confirmed clean once two pre-existing, unrelated environment issues were found and fixed:

- CocoaPods needs `LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8` set for every `pod install`/`xcodebuild`
  invocation in this environment (`ci.yml`'s own mobile job doesn't need this since GitHub Actions
  runners already default to a UTF-8 locale; a fresh macOS shell here does not).
- `react-refresh` was missing a top-level hoisted copy in a fresh `npm ci --legacy-peer-deps`
  install (`babel-preset-expo` only *peer*-depends on it — `expo` and `react-native` both regular-
  depend on compatible `^0.14.x` ranges, which should let npm hoist a single shared copy, but did
  not in this install). Fixed by pinning `react-refresh@0.14.2` as an explicit devDependency
  (commit `fix(mobile): hoist react-refresh to a top-level devDependency`) — a real, packaging-level
  fix, not a config-plugin concern, and unrelated to this feature except that it blocked verifying
  it.

A long-running Metro dev server left over from an earlier baseline attempt (before this task even
started) served a *stale, cross-worktree-poisoned* in-memory transform cache and produced what
looked like a severe native crash in `react-native-worklets`. Fully root-caused (see the session's
own investigation) before returning to this task: not a bug in this repo, this feature, or
`react-native-worklets` — purely an artifact of a Metro process outliving the worktree session that
started it. Killing it and starting fresh resolved it completely; no code changed.

### Task 2 — Config plugin change

Exactly as designed. `expo prebuild --platform ios --clean` created `ios/ShareExtension/`, and
direct inspection (not assumed) confirmed:

- `com.apple.security.application-groups` present in both `ios/Fynora/Fynora.entitlements` and
  `ios/ShareExtension/ShareExtension.entitlements`, identical value `group.com.fynora.app` in both.
- `ios/ShareExtension/ShareExtension-Info.plist`'s `NSExtensionActivationRule` matches the predicate
  committed to `app.config.ts` byte-for-byte.

### Task 3 — Build with the ShareExtension target

One real gap found: the `Fynora.xcscheme`'s own `BuildActionEntries` list doesn't explicitly
enumerate `ShareExtension`, and two concurrent `xcodebuild` invocations against the same
DerivedData (an artifact of this session's own earlier build-process cleanup) caused the extension
target to silently not compile in one run, even though its `PBXTargetDependency` and "Copy Files"
embed phase are present in `project.pbxproj`. A single, uncontested `xcodebuild` run compiled and
embedded it correctly — confirmed via 98 `ShareExtension`/`ShareViewController` matches in the
build log and `Fynora.app/PlugIns/ShareExtension.appex` physically present in the built bundle. No
regression to normal app launch.

### Task 4 — Share-sheet activation verification

**Real finding, not anticipated in the design:** sharing a file Safari is *actively viewing inline*
(its own CSV/PDF renderer, reached via "View" on a download prompt) shares Safari's *web page*, not
a file attachment — the share sheet in that case offers only 1-2 generic apps (News, Preview) and
Fynora correctly does not appear, but not for the reason being tested. This is not a bug in the
predicate; it's a wrong test vehicle. The plan's own Step 6 (Files app) was the right one, and
using it end to end gave clean, decisive results:

- **CSV positive** (Files app → long-press → Share → single file): Fynora appears; tapping it opens
  Fynora, landing on the login screen (not signed in during this session — see below).
- **PDF positive** (Safari → "Save to Files" → Files app's own Quick Look share, the same real
  file-attachment mechanism): Fynora appears; same successful hand-off.
- **Unsupported-type negative** (`.txt`, same file-attachment mechanism): Fynora correctly absent.
- **Multi-file negative** (two CSVs, Files app multi-select → Share): Fynora correctly absent,
  confirming the `$extensionItem.attachments.@count == 1` clause works as designed.

**Known, explicitly-flagged gap:** verifying the file actually lands *staged in the Import screen*
requires a signed-in account. None was available in this session (no real backend credentials to
sign in with) — every share, landing while signed out, correctly stashes and shows the login
screen instead, which is `useShareIntentDeepLink.ts`'s already-documented "arrived while signed
out" behavior, not a failure. This was not silently assumed to be fine; it's an acknowledged,
unclosed gap for whoever does have a test account to close before shipping.

### Task 5 — App-state matrix, stale-payload check, regression

- **Foreground / backgrounded / terminated:** all three confirmed working, no crash, all landing
  correctly on the login screen. The terminated (cold-start) case surfaced expo-dev-client's own
  "Development servers" reconnect screen first — expected only for a local Metro-based dev build,
  not a production/EAS binary, and not a bug.
- **Stale-payload-after-import check:** **not verified** — same signed-in-account gap as Task 4.
- **Regression suite:** `npm run typecheck` clean, `npm run lint` clean, full Jest suite
  **189/189 suites, 2003/2003 tests passing** — matching `main`'s own test count exactly (no JS
  files were touched by this feature). The first full-suite run showed 3 suites / 12 tests failing
  on `Exceeded timeout` errors (`VerifyPhoneScreen.test.tsx`, `PaywallScreen.test.tsx`,
  `ImportScreen.test.tsx`) — re-running those three in isolation passed 62/62, and a second full-
  suite run passed clean, confirming CPU-contention timing flakiness from this session's own heavy
  parallel Xcode/Simulator activity, not a real regression.

### Still open

- EAS Build's automatic provisioning of the new App Group + extension credentials
  (`eas build --profile development --platform ios`) — not exercised here (local Xcode build only),
  as the spec's own "Open questions" already anticipated.
- Full sign-in → share → Import-screen-staged → successful-import → relaunch-no-restage path needs
  a real test account.
