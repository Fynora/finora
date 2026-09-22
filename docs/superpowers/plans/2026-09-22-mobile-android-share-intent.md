# Android Share-to-Fynora Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user share a PDF/CSV statement straight from another Android app (e.g. their bank app's own Share action) into Fynora, landing in the exact same staging/confirm import flow `pickStatement()` already feeds, with no intermediate save-to-device step.

**Architecture:** `expo-share-intent`'s config plugin registers Fynora as an Android share target and its `useShareIntentContext()` hook delivers the shared file reactively (cold-start and warm-start alike). A new `useShareIntentDeepLink` navigation hook — modeled directly on the existing `usePushNotificationNavigation`/`useAppPathDeepLink` stash-until-ready hooks — converts that into a `PickedStatement`-shaped navigation param and hands it to the Import tab, which already knows how to consume one.

**Tech Stack:** Expo SDK ~57.0.20, React Native 0.86.3, `expo-share-intent` 8.0.1 (peer deps `expo-linking`, `expo-constants`), React Navigation v7, Jest + `@testing-library/react-native`.

**Spec:** [docs/superpowers/specs/2026-09-22-mobile-android-share-intent-design.md](../specs/2026-09-22-mobile-android-share-intent-design.md)

## Global Constraints

- Android only. iOS share extension is explicitly out of scope (`disableIOS: true` in the plugin config).
- No backend or parsing changes. Every task below is client-side only.
- The shared file must reach the same staging/confirm pipeline `pickStatement()` already feeds — no parallel code path.
- Single file only — matches `pickStatement()`'s existing `multiple: false` behavior.
- All commands below run from the `mobile/` directory of this worktree unless stated otherwise.
- Per this project's Node-version rule, run `npx node@22 <jest binary>` (not the bare `npm test`) when verifying test results that will be compared against CI, since local Node may be newer than CI's Node 22.

---

## Corrections to the spec

These came out of reading the actual library source and codebase during planning, not out of changing the design's intent — flagged explicitly rather than silently diverging from the written spec.

**No `withShareSuppression` needed.** The spec's §5 assumed the incoming share should reuse `withShareSuppression` the same way `pickStatement()`'s outgoing document picker does. Re-checking `appLock.ts`'s own doc comment: `withShareSuppression` exists because Fynora **opening** a native picker/share sheet backgrounds *itself* momentarily and returns — a round trip `AppLockGate` must not mistake for a genuine foreground return. A share **arriving** from another app is the opposite direction: the bank app shows its own share sheet, the user taps Fynora, and Fynora is launched/foregrounded directly — Fynora never shows or dismisses any native UI of its own during that. This is a genuine app open, and `AppLockGate`'s normal lock-on-foreground behavior is the *correct*, wanted behavior here, not something to suppress. No task below touches `appLock.ts`. (Task 8's manual checklist has a dedicated line to confirm this on a real device.)

**No separate `src/lib/shareIntent.ts`.** The spec's §3 proposed a standalone lib module wrapping the library's hook. Once `expo-share-intent`'s actual API was read (Task 5's research), its own `useShareIntentContext()` already *is* the reactive "shareIntent.ts"-equivalent source — a separate lib wrapper around it would be a pure pass-through with nothing of its own to test. The conversion logic the spec described for that file (payload → `PickedStatement` shape) lives instead directly in `useShareIntentDeepLink.ts` (Task 5), which is the file that actually needs it.

**§6's open question (logged-out state) is answered, not deferred.** `useShareIntentDeepLink` (Task 5) copies `usePushNotificationNavigation`'s exact ready/signedIn stash-and-drop-on-sign-out behavior: a share arriving before the Import tab is mounted waits; a real sign-out drops it, so it can never replay for a different account signing in next on the same device. Task 8's manual checklist verifies this on a real device.

---

## Corrections from review (post-plan, pre-implementation)

An external review of this plan surfaced two real defects, confirmed against the library source already read for Task 1/5 (not new speculation) — both are folded into the task steps below, not left as separate follow-up work:

**`text/plain` doesn't just widen the share sheet, it's a dead end.** `ExpoShareIntentModule.kt`'s `handleShareIntent` branches on `intent.type.startsWith("text/plain")` *first*: when true, it reads `EXTRA_TEXT` into `shareIntent.text` and never looks at `EXTRA_STREAM`/`files` for that intent at all, regardless of whether a real file was attached. Since `useShareIntentDeepLink` (Task 5) only ever reads `shareIntent.files`, a CSV whose provider reports it as `text/plain` — the exact case `statementFile.ts`'s own `ACCEPTED_MIME` comment warns about — would never surface as a file share through this path; worse, `text/plain` also makes Fynora appear in the OS share sheet for arbitrary shared text (a WhatsApp message, a copied browser selection) with no result when tapped. **Fix:** drop `text/plain` from Task 1's `androidIntentFilters`. `pickStatement()`'s own `ACCEPTED_MIME` (the document-picker file browser) is unaffected and correctly keeps `text/plain` — that's a different mechanism (SAF file browser, not a share-sheet manifest declaration) where the same broadening has no such side effect.

**`file.fileName` is not reliably present.** `expo-share-intent`'s published type says `fileName: string`, but `ExpoShareIntentModule.kt`'s `getFileInfo` reads `OpenableColumns.DISPLAY_NAME` from a `ContentResolver` cursor that can come back null for some content providers, and `utils.ts`'s `parseShareIntent` passes that through as `file.fileName || null` — the runtime type is looser than the declared one. The original Task 5 draft called `detectStatementFormat(file.fileName)` directly, which would throw on a null/undefined name or silently misclassify a name with no extension. **Fix:** Task 5's `toPending()` now falls back name → `mimeType` → `path` before giving up, and synthesizes a filename when none was given.

Two more review points were investigated and found not to apply, with evidence rather than assertion — documented here so the question doesn't resurface without cause:

- *"Take the first shared file, not necessarily a supported one, when several are shared."* Not reachable as designed: Task 1 only sets `androidIntentFilters` (`ACTION_SEND`, one file via `EXTRA_STREAM`), deliberately not `androidMultiIntentFilters` (`ACTION_SEND_MULTIPLE`, an array via `EXTRA_STREAM` as an `ArrayList`) — confirmed by reading `ExpoShareIntentModule.kt`'s own `handleShareIntent`, which routes those two Android actions completely separately. Since Fynora is never registered for `ACTION_SEND_MULTIPLE`, the OS won't offer it as a target for a multi-file share in the first place, so `shareIntent.files` can't contain more than one entry through this feature's own registered filters. The defensive `files?.[0]` in Task 5 stays as belt-and-suspenders, not a gap.
- *"Stale `sharedFile`/`sharedFileError` params linger in navigation state forever."* Checked `useNavigationStatePersistence.ts` directly: its `stripParams()` removes `params` from every route, unconditionally, before anything is written to `AsyncStorage` — route params here are memory-only for the life of the JS process, never persisted and never restored. Since `consumedSharedFileNonce` (also memory-only, `useState`) has exactly the same lifetime as `route.params.sharedFile`, they can never drift out of sync the way a persisted-vs-fresh mismatch would require. Matches `reimport`'s own established precedent, which relies on the identical nonce-only pattern with no `setParams` cleanup.

One point was accepted as a real, low-probability gap and deliberately left unfixed — stated explicitly, not silently: **two shares in very quick succession, before the first is consumed** (e.g. the app is still bootstrapping when the user shares a second file) would silently overwrite `pendingRef`'s single slot, dropping the first with no error shown. This requires an unusual, narrow sequence (share once, leave Fynora before it becomes ready, share again) and no existing hook in this codebase (`usePushNotificationNavigation` included) queues instead of overwriting for the equivalent case. Given that precedent and this project's stated preference against building for a hypothetical rather than a demonstrated need, this plan keeps the single-slot design. If Sid wants it queued instead, that's a small, isolable change to Task 5's `pendingRef` (a `Pending[]` and a pop-one `tryConsume`) — flagging it here rather than deciding it silently.

`useShareIntentDeepLink`'s own manual `resetShareIntent()` call (original Task 5 draft) is also removed, for a related but distinct reason: it added a real window where a share could be cleared from native/JS state before `pendingRef` was actually set, for zero benefit — the effect's own `[hasShareIntent, shareIntent]` dependency array already prevents reprocessing an unchanged value, and the library's own `resetOnBackground: true` default already clears state on backgrounding. This does **not** close the deeper gap the reviewer's crash scenario was gesturing at (a process killed while a share sits in `pendingRef`, not yet consumed, is lost regardless of when `resetShareIntent()` runs — `pendingRef` is memory-only and Android does not redeliver a consumed launch `Intent` to a fresh process). That gap is inherent to every stash-until-ready hook in this codebase (identical for a push notification's tap, an app-path link, etc.) and out of scope to solve here with a persistent queue.

Full corrected `useShareIntentDeepLink.ts` and its tests are in Task 5 below (not duplicated here) — this section is the record of *why* they read the way they do, for whoever reviews the diff next to the original spec.

**Addendum — on-device persistence added, by request.** The corrections above left one gap deliberately open: a process killed between "share stashed in `pendingRef`" and "Import tab actually consumed it" loses the share, since `pendingRef` is memory-only. Sid asked for this closed rather than accepted. Task 5's hook now mirrors every stash into `AsyncStorage` (key `finora_pending_shared_statement`, same convention as `useNavigationStatePersistence`'s `NAV_STATE_KEY`) the instant it's set, clears it once actually consumed, clears it on a real sign-out (alongside the existing in-memory clear), and a mount-time effect recovers it on a fresh cold start — with a 1-hour cutoff (matching `fileCacheSweep.ts`'s own eviction age, Task 8) so a very stale entry pointing at an already-swept cache file is dropped rather than attempted. This is a genuinely different fix from what was asked about Redis for the same underlying problem: Redis is server-side and this gap is entirely on-device — nothing here ever touches the backend before an upload is deliberately triggered, so a server-side store can't participate. This does **not** close the separate, accepted rapid-double-share-overwrite limitation (still a single slot, now just a durable one) — that would need an actual array/queue if ever wanted, unchanged from the review discussion.

**Addendum — the recovery/live-arrival race, checked, not just asserted.** Asked to explicitly verify that a live share arriving during the same cold start can't be double-processed alongside an `AsyncStorage`-recovered stale one. Traced it rather than trusting the `!pendingRef.current` guard's own wording: `expo-share-intent`'s JS wrapper calls its native `getShareIntent()` without awaiting or chaining it, so there is no synchronous "confirmed no live share is coming" signal available even in principle — `AsyncStorage.getItem()`'s resolution and the native `onChange` event for a live share are two independent async bridge calls with no ordering guarantee between them. So the guard is **one-directional**: it correctly stops recovery from clobbering a live share that arrived *first*, but if `AsyncStorage` resolves first, a stale recovered entry can still navigate before the live one arrives — and the live one, arriving after, correctly overwrites `pendingRef` and navigates again regardless (nothing here silently drops the live share). The real consequence traced further: `resetToUpload()` (`ImportScreen.tsx`) discarded `uploadAbort.current` without calling `.abort()` first, so a stale CSV's real `stageCsv()` request could keep running in the background and, if it resolved *after* the live one's own request, silently overwrite the screen with the wrong statement's staged rows — not just a visual flash. Fixed in Task 7 Step 2 by making `resetToUpload()` actually abort the in-flight request, which is also a correctness fix to code that predates this feature (any two overlapping calls to it raced the same way). This bounds the worst case to a brief, harmless flash of the stale file before the live one correctly replaces it — it does not eliminate the race's *existence*, which is inherent to a library that offers no completion signal for "no share" to gate on.

---

### Task 1: Add the `expo-share-intent` dependency and Android intent-filter config

**Files:**
- Modify: `mobile/package.json`, `mobile/package-lock.json` (via `npx expo install`)
- Modify: `mobile/app.config.ts:245-327` (plugins array)

**Interfaces:**
- Produces: Fynora registered as an Android share target for `application/pdf`, `text/csv`, `text/comma-separated-values`, `text/plain` — no JS-visible interface, verified via the generated manifest.

- [ ] **Step 1: Install the dependency and its peer dependencies**

```bash
npm install --legacy-peer-deps
npx expo install expo-share-intent expo-linking -- --legacy-peer-deps
```

(`--legacy-peer-deps`, not plain `npm install`: a fresh install hit `expo-modules-core`'s own outdated `peerOptional` range on `react-native-worklets` — confirmed this is a known, pre-existing condition, not something this feature introduced, since `ci.yml:862` already runs mobile's CI install with the identical flag for the identical reason. `expo install` needs the flag passed through after `--`, since it shells out to a plain `npm install --save` internally with no peer-deps override of its own.

Confirmed installed: `expo-share-intent@8.0.1`, `expo-linking@~57.0.10` (both now in `package.json`), and `expo-constants@57.0.19` was already present transitively, satisfying the `>=57.0.3` peer requirement — no separate install needed for it.

`expo install` also printed "Cannot automatically write to dynamic config at: app.config.ts" — expected: it can't edit a dynamic `.ts` config, so it prints the bare plugin entry as a suggestion. Step 2 below adds the real entry by hand, with the full reasoning as comments, not the bare suggestion.)

`expo-share-intent@8.0.1`'s published peer deps are `expo: ^57`, `expo-linking: >=57.0.1`, `expo-constants: >=57.0.3`, `react-native: *` (confirmed via `https://registry.npmjs.org/expo-share-intent/latest`). This project's `expo` is `~57.0.20` — compatible. `expo-linking` is not currently a direct dependency (confirmed: no `"expo-linking"` key in `package.json` before this step) — `expo install` adds it at the SDK-57-compatible version and pulls in `expo-constants` transitively if not already present.

- [ ] **Step 2: Add the config plugin entry to `app.config.ts`**

In the `plugins` array (`app.config.ts:245`), add a new entry. Place it near the other feature plugins with build-time options (next to `expo-local-authentication`'s entry is fine):

```ts
    [
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
        // Verify after any expo-share-intent upgrade: Task 1's manifest check below.
        androidIntentFilters: ['application/pdf', 'text/csv', 'text/comma-separated-values'],
        // iOS Share Extension is a separate, deliberately deferred piece of work -- see the design
        // spec's non-goals. This flag skips all of the plugin's iOS-side mods (Xcode target,
        // entitlements, Info.plist) entirely.
        disableIOS: true,
      },
    ],
```

- [ ] **Step 3: Generate the Android manifest and verify the intent-filter, without committing it**

`android/` is gitignored and regenerated by `expo prebuild` (confirmed: `git ls-files mobile/android` returns nothing) — this step's output is disposable, not something to commit.

```bash
npx expo prebuild --platform android --no-install --clean
grep -B2 -A10 'android.intent.action.SEND"' android/app/src/main/AndroidManifest.xml
```

Expected: an `<intent-filter>` block with `android:name="android.intent.action.SEND"`, category `DEFAULT`, and three `<data android:mimeType="..."/>` entries matching the three MIME types from Step 2 exactly (not `text/*`/`*/*`, and no `text/plain`).

Also check the existing App Links `VIEW` intent-filter (`app.config.ts:170`) is still present and unchanged in the same file — the plugin only ever appends a new `<intent-filter>` block (confirmed by reading `withAndroidIntentFilters.ts`'s `mainActivity["intent-filter"] = mainActivity["intent-filter"]?.concat(...)`), so it must not have replaced or altered it:

```bash
grep -B2 -A10 'android.intent.action.VIEW"' android/app/src/main/AndroidManifest.xml
```

Also record what `android:launchMode` ends up on `.MainActivity` (the plugin's `withAndroidMainActivityAttributes` mod defaults to `singleTask` if this project doesn't already have one):

```bash
grep -A3 'android:name=".MainActivity"' android/app/src/main/AndroidManifest.xml
```

If this differs from what the project's Google Sign-In / Apple Sign-In / push-notification-tap flows currently rely on, flag it before continuing — those flows are unrelated to this feature and must not silently change behavior. (If it already reads `singleTask`, this is a no-op confirmation, not a change.)

- [ ] **Step 4: Remove the disposable prebuild output**

```bash
rm -rf android
```

(Untracked and gitignored — same reasoning as any other local `expo prebuild` run a developer does not commit.)

- [ ] **Step 5: Typecheck and commit**

```bash
npm run typecheck
git add package.json package-lock.json app.config.ts
git commit -m "feat(mobile): register Fynora as an Android share target for statements"
```

---

### Task 2: Wrap the app in `ShareIntentProvider`

**Files:**
- Modify: `mobile/App.tsx`
- Modify: `mobile/App.test.tsx`

**Interfaces:**
- Consumes: `ShareIntentProvider` from `expo-share-intent` (added in Task 1).
- Produces: `useShareIntentContext()` becomes callable from anywhere under `<App>`, in particular from `RootNavigator` (Task 6).

- [ ] **Step 1: Wrap `App()`'s returned tree**

Per `expo-share-intent`'s README: `ShareIntentProvider` "Must be in your top component (`App.tsx`) before any other Provider." Wrap the outermost element `App()` currently returns (`mobile/App.tsx:87-121`):

```tsx
import { ShareIntentProvider } from 'expo-share-intent';
```

```tsx
  return (
    <ShareIntentProvider>
      <QueryClientProvider client={queryClient}>
        <SafeAreaProvider>
          {/* ... unchanged ... */}
        </SafeAreaProvider>
      </QueryClientProvider>
    </ShareIntentProvider>
  );
```

(Only the outermost `<QueryClientProvider>` wrapper and its matching closing tag move one level in; nothing between them changes.)

- [ ] **Step 2: Update `App.test.tsx`'s stub list**

`App.test.tsx` stubs every provider `App.tsx` renders so only the launch-URL-guard behavior under test runs. Add a stub for the new one, next to the other third-party-package mocks:

```tsx
jest.mock('expo-share-intent', () => ({ ShareIntentProvider: ({ children }: { children: unknown }) => children }));
```

- [ ] **Step 3: Run the test and confirm it still passes**

```bash
npx node@22 $(npm bin)/jest App.test.tsx
```

Expected: PASS (same one test as before — this step only proves the new provider didn't break the existing wiring test).

- [ ] **Step 4: Commit**

```bash
git add App.tsx App.test.tsx
git commit -m "feat(mobile): wrap App in ShareIntentProvider"
```

---

### Task 3: Extract `detectStatementFormat` from `statementFile.ts`

**Files:**
- Modify: `mobile/src/lib/statementFile.ts`
- Modify: `mobile/src/lib/statementFile.test.ts`

**Interfaces:**
- Produces: `export function detectStatementFormat(name: string): StatementFormat | null` — the exact extension-based decision `pickStatement()` already makes, now reusable by the new share-intent hook (Task 5).

- [ ] **Step 1: Write the failing tests**

Add to `mobile/src/lib/statementFile.test.ts` (new `describe` block, alongside the existing `pickStatement` one):

```ts
import { detectStatementFormat } from './statementFile';

describe('detectStatementFormat', () => {
  it('recognises a .pdf extension', () => {
    expect(detectStatementFormat('statement.pdf')).toBe('PDF');
  });

  it('recognises a .csv extension', () => {
    expect(detectStatementFormat('statement.csv')).toBe('CSV');
  });

  it('is case-insensitive', () => {
    expect(detectStatementFormat('STATEMENT.PDF')).toBe('PDF');
    expect(detectStatementFormat('Statement.Csv')).toBe('CSV');
  });

  it('returns null for an unsupported extension', () => {
    expect(detectStatementFormat('statement.txt')).toBeNull();
  });

  it('returns null for a name with no extension', () => {
    expect(detectStatementFormat('statement')).toBeNull();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
npx node@22 $(npm bin)/jest src/lib/statementFile.test.ts
```

Expected: FAIL — `detectStatementFormat` is not exported yet.

- [ ] **Step 3: Extract the helper and refactor `pickStatement` to use it**

In `mobile/src/lib/statementFile.ts`, replace the inline extension check (current lines 44-59) with:

```ts
/** iOS matches on UTIs, Android on MIME types, and some providers report a CSV as text/plain or
 *  even application/octet-stream -- so this decides by extension, not the reported MIME type. The
 *  backend has separate /import/csv/stage and /import/pdf/stage endpoints, so this has to be right. */
export function detectStatementFormat(name: string): StatementFormat | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.pdf')) return 'PDF';
  if (lower.endsWith('.csv')) return 'CSV';
  return null;
}

export async function pickStatement(): Promise<PickedStatement | null> {
  const result = await appLock.withShareSuppression(() => DocumentPicker.getDocumentAsync({
    type: ACCEPTED_MIME,
    copyToCacheDirectory: true,
    multiple: false,
  }));

  if (result.canceled || !result.assets?.length) return null;

  const asset = result.assets[0];
  const name = asset.name ?? 'statement';
  const format = detectStatementFormat(name);

  if (!format) {
    throw new Error('Choose a .csv or .pdf bank or credit card statement.');
  }

  return {
    file: {
      uri: asset.uri,
      name,
      type: format === 'PDF' ? 'application/pdf' : 'text/csv',
    },
    format,
  };
}
```

(Keep the existing doc comments on `pickStatement`, `PickedStatement`, `StatementFormat`, and `ACCEPTED_MIME` exactly as they are — only the inline extension-check block is being replaced.)

- [ ] **Step 4: Run the tests to verify they pass**

```bash
npx node@22 $(npm bin)/jest src/lib/statementFile.test.ts
```

Expected: PASS, all cases including the pre-existing `pickStatement` suppression test (unchanged, must still pass).

- [ ] **Step 5: Commit**

```bash
git add src/lib/statementFile.ts src/lib/statementFile.test.ts
git commit -m "refactor(mobile): extract detectStatementFormat from pickStatement"
```

---

### Task 4: Add the shared-file navigation param types

**Files:**
- Modify: `mobile/src/navigation/types.ts`

**Interfaces:**
- Consumes: `RNFile` from `../api/endpoints`, `StatementFormat` from `../lib/statementFile`.
- Produces: `SharedStatementFile`, `SharedStatementError` — consumed by Task 5 (the navigation hook) and Task 7 (`ImportScreen`).

- [ ] **Step 1: Add the two new interfaces next to `ReimportParams`**

In `mobile/src/navigation/types.ts`, add the import and the two interfaces just above `ReimportParams` (around line 111):

```ts
import type { RNFile } from '../api/endpoints';
import type { StatementFormat } from '../lib/statementFile';
```

```ts
/**
 * A statement handed to the Import tab from Android's share sheet (sharing a PDF/CSV straight out
 * of another app, e.g. a bank app's own Share action) rather than picked via "Choose a file". Same
 * shape `pickStatement()` returns -- see useShareIntentDeepLink's own doc comment for why this is a
 * separate arrival path from `reimport` below, which is already staged server-side and has no raw
 * file to hand over.
 *
 * `nonce` mirrors `ReimportParams.nonce`: the Import tab stays mounted like every other tab, so
 * without a per-arrival key a second share of a same-named file would not be recognised as a new
 * arrival.
 */
export interface SharedStatementFile {
  file: RNFile;
  format: StatementFormat;
  nonce: number;
}

/** The share-sheet counterpart of a `pickStatement()` throw: what was shared isn't a supported
 *  statement format. Carried as its own param (not a generic toast) so it surfaces exactly where
 *  handlePick()'s own catch block already shows one -- the Import tab's upload-step error banner. */
export interface SharedStatementError {
  message: string;
  nonce: number;
}
```

- [ ] **Step 2: Update `AppTabParamList.Import`**

Change (current, line ~181):

```ts
  Import: { reimport: ReimportParams } | undefined;
```

to (matching the existing `Home` tab's own "one object, every field optional" idiom rather than a discriminated union, since `route.params?.reimport` / `?.sharedFile` / `?.sharedFileError` all need to be readable independently):

```ts
  Import:
    | { reimport?: ReimportParams; sharedFile?: SharedStatementFile; sharedFileError?: SharedStatementError }
    | undefined;
```

- [ ] **Step 3: Typecheck**

```bash
npm run typecheck
```

Expected: FAILS at this point, at the one call site still using the old shape — `StatementHistoryScreen.tsx`'s re-import navigation (`nonce: Date.now()` grep hit at `StatementHistoryScreen.tsx:110`). Confirm the failure is exactly there and is a missing-field-vs-optional mismatch, not something else; `{ reimport: {...} }` still satisfies `{ reimport?: ReimportParams; ... }`, so no source change should actually be needed there — if `tsc` reports a real error at that call site, read it before assuming this step is done, per this project's no-guessing rule.

- [ ] **Step 4: Commit**

```bash
git add src/navigation/types.ts
git commit -m "feat(mobile): add SharedStatementFile/SharedStatementError param types"
```

---

### Task 5: `useShareIntentDeepLink` navigation hook

**Files:**
- Create: `mobile/src/navigation/useShareIntentDeepLink.ts`
- Create: `mobile/src/navigation/useShareIntentDeepLink.test.ts`

**Interfaces:**
- Consumes: `useShareIntentContext` from `expo-share-intent` (Task 1/2), `detectStatementFormat` from `../lib/statementFile` (Task 3), `SharedStatementFile`/`SharedStatementError`/`RootParamList` from `./types` (Task 4), `AsyncStorage` from `@react-native-async-storage/async-storage` (already a dependency — `useNavigationStatePersistence.ts` already uses it).
- Produces: `useShareIntentDeepLink(navigationRef, ready, signedIn): { onNavigationReady: () => void }` — same call shape as `usePushNotificationNavigation`/`useAppPathDeepLink`, consumed by Task 6.

- [ ] **Step 1: Write the hook**

Create `mobile/src/navigation/useShareIntentDeepLink.ts`:

```ts
import { useCallback, useEffect, useRef } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useShareIntentContext, type ShareIntentFile } from 'expo-share-intent';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { detectStatementFormat, type StatementFormat } from '../lib/statementFile';
import type { RootParamList, SharedStatementFile, SharedStatementError } from './types';

const UNSUPPORTED_MESSAGE = 'Choose a .csv or .pdf bank or credit card statement.';

/**
 * Same `finora_` key convention as useNavigationStatePersistence's own NAV_STATE_KEY and
 * appLock.ts's ENABLED_KEY. Written the instant a share is stashed (not just on successful
 * navigation) and cleared once consumed, so a process death between "share arrived" and "Import
 * tab actually processed it" -- the app killed by the OS while still bootstrapping, for instance --
 * can recover on the next cold start instead of silently losing the share. Only ever holds a
 * SharedStatementFile, never a SharedStatementError: there is nothing worth recovering from an
 * error message once the process that would have shown it is gone.
 */
const PENDING_SHARE_KEY = 'finora_pending_shared_statement';

/**
 * Same cutoff fileCacheSweep.ts uses for its own cache-file eviction (Task 8). Past this age, the
 * cache file this persisted entry's `file.uri` points at has likely already been swept, so
 * recovering and attempting to upload it would just fail with a confusing "file not found" --
 * dropping it silently here, the same way an ordinary dismissed share is silently dropped, is the
 * more honest failure mode.
 */
const PENDING_SHARE_MAX_AGE_MS = 60 * 60 * 1000;

type Pending = { kind: 'file'; value: SharedStatementFile } | { kind: 'error'; value: SharedStatementError };

/**
 * expo-share-intent's own published type says `fileName: string`, but ExpoShareIntentModule.kt's
 * getFileInfo reads Android's OpenableColumns.DISPLAY_NAME from a ContentResolver cursor that can
 * come back null for some content providers -- utils.ts's parseShareIntent then passes that
 * through as `file.fileName || null`, looser than the declared type. Falls back name -> mimeType ->
 * resolved path before giving up, rather than trusting the name alone the way pickStatement() can
 * (a real DocumentPicker asset always has one).
 */
function detectFormatFromShareIntentFile(file: ShareIntentFile): StatementFormat | null {
  const byName = file.fileName ? detectStatementFormat(file.fileName) : null;
  if (byName) return byName;
  if (file.mimeType === 'application/pdf') return 'PDF';
  if (file.mimeType === 'text/csv' || file.mimeType === 'text/comma-separated-values') return 'CSV';
  return file.path ? detectStatementFormat(file.path) : null;
}

function toPending(file: ShareIntentFile): Pending {
  const nonce = Date.now();
  const format = detectFormatFromShareIntentFile(file);
  if (!format) {
    return { kind: 'error', value: { message: UNSUPPORTED_MESSAGE, nonce } };
  }
  // A synthesized name when the provider gave none -- upload() and the review screen both display
  // this name, so it must never be empty, even though it does not need to be the real one.
  const name = file.fileName || `statement.${format === 'PDF' ? 'pdf' : 'csv'}`;
  return {
    kind: 'file',
    value: {
      file: { uri: file.path, name, type: format === 'PDF' ? 'application/pdf' : 'text/csv' },
      format,
      nonce,
    },
  };
}

/**
 * Android's share sheet arrival path: sharing a PDF/CSV straight out of another app (e.g. a bank
 * app's own Share action) rather than picking one from "Choose a file". expo-share-intent's own
 * useShareIntentContext() already collapses cold-start (app launched by the share) and warm-start
 * (app already running, resumed by the share) into one reactive `shareIntent` value -- unlike
 * useAppPathDeepLink's own https-link handling, no separate Linking.getInitialURL()/
 * addEventListener wiring is needed here.
 *
 * Same stash-until-ready shape as usePushNotificationNavigation: the share can arrive before the
 * Import tab exists to navigate to (signed out, mid phone verification), so it waits in a ref and
 * replays once `ready` turns true; a real sign-out drops anything still waiting, for the same
 * reason a tapped push is dropped rather than replayed for whoever signs in next -- the Import tab
 * has no per-user scoping of its own to reject a stale arrival the way an emailed link's token does.
 *
 * On Android, expo-share-intent's native module resolves a shared content:// URI into a real file
 * copied into the app's own cache directory before `file.path` is ever populated here (confirmed by
 * reading expo-share-intent 8.0.1's ExpoShareIntentModule.kt getDataColumn, not assumed) -- the same
 * "a provider URI can be unreadable or revoked later" reasoning pickStatement()'s own
 * copyToCacheDirectory comment documents, already handled on the library's side.
 *
 * A stashed-but-not-yet-consumed share also survives a killed process (not just a backgrounded
 * one): the instant it's stashed into `pendingRef`, it's mirrored into AsyncStorage under
 * PENDING_SHARE_KEY, and a mount-time effect recovers it on the next cold start if nothing arrived
 * live this session. This does NOT cover two shares arriving in quick succession before either is
 * consumed -- both the in-memory `pendingRef` and the persisted key are a single slot, so the
 * second still overwrites the first either way. That is a known, accepted limitation (see the plan
 * document's own "Corrections from review" section), not something this persistence layer changes.
 */
export function useShareIntentDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  ready: boolean,
  signedIn: boolean,
) {
  const { hasShareIntent, shareIntent } = useShareIntentContext();
  const pendingRef = useRef<Pending | null>(null);
  const readyRef = useRef(ready);
  const wasSignedInRef = useRef(signedIn);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const pending = pendingRef.current;
    if (!pending) return;
    pendingRef.current = null;
    if (pending.kind === 'file') {
      void AsyncStorage.removeItem(PENDING_SHARE_KEY);
      navigationRef.navigate('Import', { sharedFile: pending.value });
    } else {
      navigationRef.navigate('Import', { sharedFileError: pending.value });
    }
  }, [navigationRef]);

  // This effect only ever builds a plain object and stores it in a ref -- unlike ImportScreen's own
  // consumption of the result (Task 7), which can trigger upload(), a real network call, so THAT
  // step deliberately runs in ImportScreen's own effect, not here. Safe to run more than once for
  // the same shareIntent value (e.g. React StrictMode's dev-mode double-invoke) -- re-persisting
  // the same value to AsyncStorage is equally harmless.
  //
  // Deliberately does NOT call expo-share-intent's own resetShareIntent(): the library's own
  // resetOnBackground default (true) already clears its state on backgrounding, and this effect's
  // own [hasShareIntent, shareIntent] dependency array already prevents reprocessing an unchanged
  // value -- shareIntent is a fresh object identity per native event, so nothing here re-runs
  // without a genuinely new share. Calling it eagerly bought nothing and only added a window where
  // the native/JS state could be cleared before pendingRef was actually set.
  useEffect(() => {
    if (!hasShareIntent) return;
    const file = shareIntent.files?.[0];
    if (!file) return;
    const pending = toPending(file);
    pendingRef.current = pending;
    if (pending.kind === 'file') {
      void AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(pending.value));
    }
    tryConsume();
  }, [hasShareIntent, shareIntent]);

  // Mount-only recovery for a process killed after a share was stashed but before it was consumed.
  // Only hydrates when nothing has claimed pendingRef yet -- a share arriving live this session
  // (the effect above) is always more current than a persisted one and must win if both somehow
  // race. A `cancelled` guard, same shape as useAppPathDeepLink's own getInitialURL effect, so a
  // torn-down mount can't act on a navigationRef that no longer belongs to it.
  useEffect(() => {
    let cancelled = false;
    void AsyncStorage.getItem(PENDING_SHARE_KEY).then((raw) => {
      if (cancelled || !raw || pendingRef.current) return;
      let value: SharedStatementFile;
      try {
        value = JSON.parse(raw) as SharedStatementFile;
      } catch {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      if (Date.now() - value.nonce > PENDING_SHARE_MAX_AGE_MS) {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      pendingRef.current = { kind: 'file', value };
      tryConsume();
    });
    return () => { cancelled = true; };
    // Deliberately [] -- a one-time recovery check for this mount's cold start, not something that
    // should re-run as `tryConsume`'s identity changes with navigationRef/ready.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    readyRef.current = ready;
    if (wasSignedInRef.current && !signedIn) {
      pendingRef.current = null;
      void AsyncStorage.removeItem(PENDING_SHARE_KEY);
    }
    wasSignedInRef.current = signedIn;
    tryConsume();
  }, [ready, signedIn, tryConsume]);

  return { onNavigationReady: tryConsume };
}
```

- [ ] **Step 2: Write the tests**

Create `mobile/src/navigation/useShareIntentDeepLink.test.ts`:

```ts
import { renderHook, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useShareIntentDeepLink } from './useShareIntentDeepLink';

// Same key the hook itself uses (PENDING_SHARE_KEY is a private module const, not exported) --
// mirrors useNavigationStatePersistence.test.ts's own NAV_STATE_KEY, hardcoded the same way.
// AsyncStorage itself is mocked globally in src/test/setup.ts (an in-memory map with real async
// semantics, cleared before every test), so it's used here directly, not re-mocked per test.
const PENDING_SHARE_KEY = 'finora_pending_shared_statement';

const mockUseShareIntentContext = jest.fn();
jest.mock('expo-share-intent', () => ({
  useShareIntentContext: () => mockUseShareIntentContext(),
}));

function fakeShareIntentContext(overrides: Record<string, unknown> = {}) {
  return {
    hasShareIntent: false,
    shareIntent: { files: null, text: null, webUrl: null, type: null },
    resetShareIntent: jest.fn(),
    ...overrides,
  };
}

function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
  return {
    current: {},
    isReady: overrides.isReady ?? (() => true),
    navigate: jest.fn(),
  } as unknown as Parameters<typeof useShareIntentDeepLink>[0];
}

function pdfFile(name = 'statement.pdf') {
  return {
    fileName: name, mimeType: 'application/pdf', path: `file:///cache/${name}`,
    size: 1000, width: null, height: null, duration: null,
  };
}

describe('useShareIntentDeepLink', () => {
  beforeEach(() => {
    mockUseShareIntentContext.mockReset();
  });

  it('navigates to Import with the shared file when ready and signed in', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({
        file: { uri: 'file:///cache/statement.pdf', name: 'statement.pdf', type: 'application/pdf' },
        format: 'PDF',
      }),
    });
  });

  it('falls back to mimeType when the provider gave no fileName', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: null, mimeType: 'application/pdf', path: 'file:///cache/FILE_123.pdf', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({
        file: { uri: 'file:///cache/FILE_123.pdf', name: 'statement.pdf', type: 'application/pdf' },
        format: 'PDF',
      }),
    });
  });

  it('falls back to the resolved path when both fileName and mimeType are unhelpful', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: 'document', mimeType: 'application/octet-stream', path: 'file:///cache/document.csv', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({ format: 'CSV' }),
    });
  });

  it('produces a sharedFileError, not a throw, when no name/mimeType/path gives a usable format', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: null, mimeType: 'application/octet-stream', path: 'content://com.example/1', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    expect(() => renderHook(() => useShareIntentDeepLink(navigationRef, true, true))).not.toThrow();
    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFileError: expect.objectContaining({ message: 'Choose a .csv or .pdf bank or credit card statement.' }),
    });
  });

  it('navigates with a sharedFileError for an unsupported shared file', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: 'photo.jpg', mimeType: 'image/jpeg', path: 'file:///cache/photo.jpg', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFileError: expect.objectContaining({ message: "Choose a .csv or .pdf bank or credit card statement." }),
    });
  });

  it('stashes the share and does not navigate yet when not ready', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, false, true));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('replays the stashed share once ready becomes true', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready }: { ready: boolean }) => useShareIntentDeepLink(navigationRef, ready, true),
      { initialProps: { ready: false } },
    );
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true });

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', expect.objectContaining({ sharedFile: expect.anything() }));
  });

  it('drops a stashed share on a real sign-out, never replaying it for a later sign-in', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useShareIntentDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: true } },
    );
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: false, signedIn: false });
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('does nothing when hasShareIntent is false', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('persists a stashed file to AsyncStorage, then clears it once consumed', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalled();
    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
  });

  it('recovers a persisted share on a fresh mount when nothing arrives live this session', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());
    const persisted = {
      file: { uri: 'file:///cache/statement.pdf', name: 'statement.pdf', type: 'application/pdf' },
      format: 'PDF',
      nonce: Date.now(),
    };
    await AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(persisted));

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    await waitFor(() => expect(navigationRef.navigate).toHaveBeenCalledWith('Import', { sharedFile: persisted }));
  });

  it('drops a persisted share older than an hour without consuming it', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());
    const stale = {
      file: { uri: 'file:///cache/old.pdf', name: 'old.pdf', type: 'application/pdf' },
      format: 'PDF',
      nonce: Date.now() - 2 * 60 * 60 * 1000, // 2 hours old, past the 1-hour cutoff
    };
    await AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(stale));

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('clears the persisted share on a real sign-out, not just the in-memory one', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useShareIntentDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: true } },
    );
    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).not.toBeNull());

    rerender({ ready: false, signedIn: false });

    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
  });
});
```

- [ ] **Step 3: Run the tests**

```bash
npx node@22 $(npm bin)/jest src/navigation/useShareIntentDeepLink.test.ts
```

Expected: PASS, all 13 cases.

- [ ] **Step 4: Typecheck and lint**

```bash
npm run typecheck
npm run lint
```

- [ ] **Step 5: Commit**

```bash
git add src/navigation/useShareIntentDeepLink.ts src/navigation/useShareIntentDeepLink.test.ts
git commit -m "feat(mobile): add useShareIntentDeepLink hook"
```

---

### Task 6: Wire the hook into `RootNavigator`

**Files:**
- Modify: `mobile/src/navigation/RootNavigator.tsx`
- Modify: `mobile/src/navigation/RootNavigator.test.tsx`

**Interfaces:**
- Consumes: `useShareIntentDeepLink` from `./useShareIntentDeepLink` (Task 5).

- [ ] **Step 1: Add the hook call and wire it into `onNavigationReady`**

In `mobile/src/navigation/RootNavigator.tsx`, add the import (next to the other deep-link hook imports, line 22):

```ts
import { useShareIntentDeepLink } from './useShareIntentDeepLink';
```

Add the hook call next to `useAppPathDeepLink`'s (same `isAppTabsActive`/`token !== null` gate — a shared statement is exactly as generic/account-scoped as the "Statements" push/app-path destinations, with no per-user scoping of its own):

```ts
  const { onNavigationReady: onAppPathReady } = useAppPathDeepLink(navigationRef, isAppTabsActive, token !== null);
  const { onNavigationReady: onShareIntentReady } = useShareIntentDeepLink(navigationRef, isAppTabsActive, token !== null);
```

Add it to `onNavigationReady()`:

```ts
  function onNavigationReady() {
    onEmailChangeReady();
    onReferralReady();
    onPushNotificationReady();
    onAppPathReady();
    onShareIntentReady();
    onResetPasswordReady();
  }
```

- [ ] **Step 2: Mock the hook in `RootNavigator.test.tsx`**

`RootNavigator.test.tsx` mocks every OTHER deep-link hook it wires (`useEmailChangeDeepLink`, `useReferralDeepLink`, `usePushNotificationNavigation`, `useResetPasswordDeepLink`) so the real ones — which touch native modules unavailable in the test tree — never run. `useShareIntentDeepLink` calls `useShareIntentContext()`, which requires a `<ShareIntentProvider>` this test tree doesn't render, so it needs the same treatment. Add, next to the other mocks (`RootNavigator.test.tsx:38`):

```ts
jest.mock('./useShareIntentDeepLink', () => ({
  useShareIntentDeepLink: () => ({ onNavigationReady: jest.fn() }),
}));
```

- [ ] **Step 3: Run the test**

```bash
npx node@22 $(npm bin)/jest src/navigation/RootNavigator.test.tsx
```

Expected: PASS, unchanged from before this task (this task adds no new assertions to this file — it only keeps the existing ones passing with the new hook wired in).

- [ ] **Step 4: Typecheck**

```bash
npm run typecheck
```

- [ ] **Step 5: Commit**

```bash
git add src/navigation/RootNavigator.tsx src/navigation/RootNavigator.test.tsx
git commit -m "feat(mobile): wire useShareIntentDeepLink into RootNavigator"
```

---

### Task 7: Consume the shared file in `ImportScreen`

**Files:**
- Modify: `mobile/src/screens/import/ImportScreen.tsx`
- Modify: `mobile/src/screens/import/ImportScreen.test.tsx`

**Interfaces:**
- Consumes: `route.params.sharedFile` / `route.params.sharedFileError` (`SharedStatementFile`/`SharedStatementError`, Task 4).

- [ ] **Step 1: Extract `applyPicked` from `handlePick`**

In `mobile/src/screens/import/ImportScreen.tsx`, update the import on line 40 to also bring in `PickedStatement`:

```ts
import { pickStatement, type PickedStatement, type StatementFormat } from '../../lib/statementFile';
```

Replace the current `handlePick` (lines 316-333) with:

```ts
  /**
   * Shared between a manual "Choose a file" pick and a file arriving via Android's share sheet
   * (Task 7 of the share-intent feature) -- both produce the same PickedStatement shape, so both
   * take the same next step: a PDF stops at the password card, a CSV goes straight up.
   */
  async function applyPicked(picked: PickedStatement) {
    setFileFormat(picked.format);
    if (picked.format === 'PDF') {
      setPendingPdf(picked.file);
      setPdfPassword('');
      setPasswordState(null);
      return;
    }
    await upload(picked.file, false, undefined);
  }

  async function handlePick() {
    setError(null);
    let picked;
    try {
      picked = await pickStatement();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not read that file.');
      return;
    }
    // A dismissed picker is not an error and must not show one.
    if (!picked) return;
    await applyPicked(picked);
  }
```

- [ ] **Step 2: Fix `resetToUpload` to actually cancel an in-flight upload, not just forget it**

Found during review of this plan, tracing a real (if narrow) race between Task 5's AsyncStorage recovery and a live share arriving in the same cold start: `resetToUpload()` currently does `uploadAbort.current = null` with no `.abort()` call first — only the dedicated "Cancel upload" handler actually calls `.abort()`. If a stale recovered share (a CSV) has already started a real `stageCsv()` request when a live share arrives moments later and triggers a second `resetToUpload()` + `applyPicked()`, the first request is never cancelled — it keeps running, and if it happens to resolve *after* the second one, its `.then()` callback still fires and silently overwrites the screen with the wrong statement's staged rows. This is a pre-existing gap in `resetToUpload()` itself (any two overlapping triggers of it race the same way, not specific to share-intent), surfaced by this feature rather than caused by it — fixing it here rather than filing it separately, since this task is what makes the race reachable in practice.

In `resetToUpload()` (`mobile/src/screens/import/ImportScreen.tsx`, the `uploadAbort.current = null;` line inside it), change:

```ts
    uploadAbort.current = null;
```

to:

```ts
    // Cancels whatever this screen was doing before -- unlike the two setters around it, this one
    // actually has a side effect to undo. Without the abort() call, a reset that lands while a
    // request is still in flight (e.g. Task 5's AsyncStorage-recovered share racing a live one
    // arriving moments later on the same cold start) leaves that request running in the
    // background; if it resolves after whatever reset() to, its own .then() callback still fires
    // and silently overwrites the screen with the wrong statement's staged rows.
    uploadAbort.current?.abort();
    uploadAbort.current = null;
```

This does not fully close the race (nothing can, deterministically, given expo-share-intent's own JS wrapper never awaits its native `getShareIntent()` call -- there is no synchronous "no live share is coming" signal to gate recovery on). What it does close is the actual harmful consequence: the worst case left after this fix is a brief, harmless flash of the stale recovered file's password/review card before the live one correctly replaces it a moment later -- not silent state corruption from two uploads racing to completion.

Add a regression test for this alongside the existing `ImportScreen — Cancel disabled during confirm` describe block's own upload-abort coverage (or a small new one):

```ts
describe('ImportScreen — resetToUpload cancels an in-flight upload', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
  });

  it('aborts a still-in-flight stageCsv call when a second shared file arrives before the first resolves', async () => {
    let capturedSignal: AbortSignal | undefined;
    api.import.stageCsv.mockReset().mockImplementation((_file, _onProgress, signal) => {
      capturedSignal = signal;
      return new Promise(() => {}); // never resolves -- only the abort signal is asserted
    });
    mockRouteParams = {
      sharedFile: { file: { uri: 'file:///cache/first.csv', name: 'first.csv', type: 'text/csv' }, format: 'CSV', nonce: 1 },
    };
    const { rerender } = render(tree());
    await settle();
    expect(capturedSignal?.aborted).toBe(false);

    mockRouteParams = {
      sharedFile: { file: { uri: 'file:///cache/second.csv', name: 'second.csv', type: 'text/csv' }, format: 'CSV', nonce: 2 },
    };
    rerender(tree());
    await settle();

    expect(capturedSignal?.aborted).toBe(true);
  });
});
```

(Check `importApi.stageCsv`'s real signature before writing this — confirm the `signal` parameter's position matches `stageCsv(file, onProgress, signal)` as used elsewhere in this same test file, e.g. the "async import job" describe block's own mocks, rather than assuming.)

- [ ] **Step 3: Read the new route params and add the dedupe state**

Next to `const reimportParam = route.params?.reimport;` (line 64):

```ts
  const reimportParam = route.params?.reimport;
  const sharedFileParam = route.params?.sharedFile;
  const sharedFileErrorParam = route.params?.sharedFileError;
```

Next to `const [consumedReimportNonce, setConsumedReimportNonce] = useState<number | null>(null);` (line 174):

```ts
  const [consumedSharedFileNonce, setConsumedSharedFileNonce] = useState<number | null>(null);
```

- [ ] **Step 4: Add the consumption effect**

Immediately after the existing `reimportParam` render-phase block closes (after line 277, before `function resetToUpload() {`), add:

```ts
  /**
   * Arriving via Android's share sheet -- see useShareIntentDeepLink's own doc comment. Unlike
   * reimportParam above (a plain state assignment, safe to run twice under React StrictMode's
   * dev-mode double-invoke of render-phase code), applying a shared file can immediately call
   * upload() -- a real network request -- so this runs in an effect keyed on the nonce rather than
   * during render, to avoid firing two uploads for one arrival.
   */
  useEffect(() => {
    if (sharedFileParam && sharedFileParam.nonce !== consumedSharedFileNonce) {
      setConsumedSharedFileNonce(sharedFileParam.nonce);
      resetToUpload();
      void applyPicked({ file: sharedFileParam.file, format: sharedFileParam.format });
    } else if (sharedFileErrorParam && sharedFileErrorParam.nonce !== consumedSharedFileNonce) {
      setConsumedSharedFileNonce(sharedFileErrorParam.nonce);
      resetToUpload();
      setError(sharedFileErrorParam.message);
    }
    // resetToUpload/applyPicked close over this render's state and are redefined every render --
    // same exclusion usePushNotificationNavigation's own onNotificationOpenedApp effect uses for
    // messaging, for the same reason.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sharedFileParam, sharedFileErrorParam, consumedSharedFileNonce]);
```

(This uses `resetToUpload`, defined just below it in source order — safe, since it's a `function` declaration hoisted within the component body, and this effect's callback only runs after the whole render has committed.)

- [ ] **Step 5: Write the tests**

Add to `mobile/src/screens/import/ImportScreen.test.tsx`, after the "synchronous upload failure wording" describe block:

```ts
describe('ImportScreen — arriving via Android share sheet', () => {
  beforeEach(() => {
    mockRouteParams = undefined;
    mockNavigate.mockClear();
    api.accounts.list.mockReset().mockResolvedValue([]);
    api.categories.list.mockReset().mockResolvedValue([]);
    api.import.listSessions.mockReset().mockResolvedValue([]);
  });

  it('shows the password card for a shared PDF, with no extra pick step', async () => {
    mockRouteParams = {
      sharedFile: {
        file: { uri: 'file:///cache/statement.pdf', name: 'statement.pdf', type: 'application/pdf' },
        format: 'PDF',
        nonce: 1,
      },
    };
    render(tree());

    expect(await screen.findByTestId('pdf-password-panel')).toBeTruthy();
    expect(screen.getByText('statement.pdf')).toBeTruthy();
  });

  it('uploads a shared CSV immediately, the same as a manually picked one', async () => {
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: { rows: [], totalParsed: 0, flaggedDuplicates: 0, detectedAccount: detected, unparseableRows: [] },
    } as never);
    mockRouteParams = {
      sharedFile: {
        file: { uri: 'file:///cache/statement.csv', name: 'statement.csv', type: 'text/csv' },
        format: 'CSV',
        nonce: 1,
      },
    };
    render(tree());
    await settle();

    expect(api.import.stageCsv).toHaveBeenCalled();
  });

  it('shows the share-sheet error message for an unsupported shared file', async () => {
    mockRouteParams = {
      sharedFileError: { message: 'Choose a .csv or .pdf bank or credit card statement.', nonce: 1 },
    };
    render(tree());

    expect(await screen.findByText('Choose a .csv or .pdf bank or credit card statement.')).toBeTruthy();
  });

  it('does not re-apply the same shared file twice on a later re-render (nonce dedupe)', async () => {
    api.import.stageCsv.mockReset().mockResolvedValue({
      sessionId: 'session-1',
      multiAccount: false,
      sections: null,
      staging: { rows: [], totalParsed: 0, flaggedDuplicates: 0, detectedAccount: detected, unparseableRows: [] },
    } as never);
    mockRouteParams = {
      sharedFile: {
        file: { uri: 'file:///cache/statement.csv', name: 'statement.csv', type: 'text/csv' },
        format: 'CSV',
        nonce: 1,
      },
    };
    const { rerender } = render(tree());
    await settle();
    expect(api.import.stageCsv).toHaveBeenCalledTimes(1);

    rerender(tree());
    await settle();

    expect(api.import.stageCsv).toHaveBeenCalledTimes(1);
  });
});
```

Also update the file-level `mockRouteParams` type declaration (currently `let mockRouteParams: { reimport?: unknown } | undefined;`) to include the two new optional fields:

```ts
let mockRouteParams: { reimport?: unknown; sharedFile?: unknown; sharedFileError?: unknown } | undefined;
```

- [ ] **Step 6: Run the tests**

```bash
npx node@22 $(npm bin)/jest src/screens/import/ImportScreen.test.tsx
```

Expected: PASS — Step 2's abort-race regression test, Step 5's 4 new cases, and every pre-existing test in this file (this is the file's own regression check; a shared `applyPicked` extraction touching `handlePick` must not change that path's existing behavior).

- [ ] **Step 7: Typecheck and lint**

```bash
npm run typecheck
npm run lint
```

- [ ] **Step 8: Commit**

```bash
git add src/screens/import/ImportScreen.tsx src/screens/import/ImportScreen.test.tsx
git commit -m "feat(mobile): consume a shared statement file in ImportScreen, cancel stale in-flight uploads on reset"
```

---

### Task 8: Full regression pass and manual on-device verification

**Files:** none (verification only)

**Cache cleanup — verified, no new code needed.** A shared PDF/CSV can be large (10-50MB), so it's worth confirming the copied cache file doesn't accumulate. Checked `fileCacheSweep.ts` directly: it sweeps `Paths.cache` (the same directory `expo-file-system` and native Android both resolve to `context.cacheDir`) once per cold start, deleting anything older than an hour, and recurses one level into subdirectories. `ExpoShareIntentModule.kt`'s `getDataColumn` writes the copied file directly into `context.cacheDir` (not a subdirectory) — the sweep's top-level pass already covers it, the same way it already covers `expo-document-picker`'s own copies. No change needed in this task; this is a resolved risk from the spec's own §"Risks", not an open one.

- [ ] **Step 1: Full automated suite**

```bash
npx node@22 $(npm bin)/jest
npm run typecheck
npm run lint
```

Expected: all clean. This is the project's mandatory "run the full suite for whatever module you touched, not just the new tests" pass — `statementFile.ts` and `ImportScreen.tsx` are both shared, high-traffic files.

- [ ] **Step 2: EAS dev build**

Expo Go cannot register a custom Android intent-filter, so this feature is invisible to `expo start`. Build and install a dev client:

```bash
eas build --profile development --platform android
```

(Run from `mobile/`, per this project's own EAS convention — see project memory on `eas` commands.)

- [ ] **Step 3: Manual verification checklist, on a real Android device or emulator with the dev build installed**

- [ ] Share a real PDF statement from Google Drive/Files app → Fynora appears in the share sheet → tapping it opens Fynora directly to the password card with the correct file name shown.
- [ ] Enter the correct password (or leave blank for an unprotected PDF) → statement stages and the review step appears, identical to a manually-picked PDF.
- [ ] Share a `.csv` file the same way → Fynora uploads it immediately and reaches the review step with no extra tap, identical to a manually-picked CSV.
- [ ] If a real bank app is available on the test device, share a statement directly from it (not via an intermediate file manager) and confirm the same behavior.
- [ ] Confirm Fynora does *not* appear in the share sheet for an unregistered type (a `.jpg`, a random text selection) — the intent-filter's MIME list (`application/pdf`, `text/csv`, `text/comma-separated-values`) is exact, not wildcarded, so it shouldn't. (The "unsupported file → error banner" path is covered at the unit level in Task 5/7 — it's for a file whose *reported* MIME type matches one of these three but whose name/content doesn't actually parse as a statement, not for a type outside the registered list, which the OS filters out before Fynora is ever offered.)
- [ ] Cold start: force-stop Fynora first, then share a PDF → Fynora launches fresh, and (if App Lock is enabled in Settings) shows the lock screen before the password card — confirming the "Correction to the spec" section above: this is a genuine app open, and the lock screen is expected here, not suppressed.
- [ ] Warm start: with Fynora already open on some other tab, share a PDF → Fynora comes to the foreground already on the Import tab's password card.
- [ ] Sign out, force-stop, then share a PDF → Fynora opens to the sign-in screen (Login), not the Import tab; sign in and confirm the share was dropped, not silently replayed (no password card appears post-login).
- [ ] Process-death recovery (the on-device persistence addendum above): share a PDF, then — before Fynora reaches the Import tab — force-stop it from Android's app-info screen (approximates the OS killing the process while a share sits unconsumed). Relaunch Fynora normally (tap the icon, not the share sheet again) and confirm it opens straight to the password card with the same file, recovered from `AsyncStorage` rather than lost. Separately, confirm a share older than an hour (simulate by adjusting the device clock forward, or by reading the persisted key's timestamp directly) is silently dropped on next launch, not applied.
- [ ] Confirm Fynora's existing App Links (tapping an emailed `https://app.fynora.net/...` link) still opens the app correctly on the same build — the regression this task's manifest check in Task 1 flagged as worth confirming.

- [ ] **Step 4: Record any manual-check failures as new tasks, not silent gaps**

Per this project's completion criteria: if any box above fails, that is a bug to fix and re-verify, not something to note and move past. If everything passes, the feature is done for this plan's scope (Android only, per the spec's explicit non-goal on iOS).
