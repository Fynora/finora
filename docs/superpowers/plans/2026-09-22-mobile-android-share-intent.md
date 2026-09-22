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

### Task 1: Add the `expo-share-intent` dependency and Android intent-filter config

**Files:**
- Modify: `mobile/package.json`, `mobile/package-lock.json` (via `npx expo install`)
- Modify: `mobile/app.config.ts:245-327` (plugins array)

**Interfaces:**
- Produces: Fynora registered as an Android share target for `application/pdf`, `text/csv`, `text/comma-separated-values`, `text/plain` — no JS-visible interface, verified via the generated manifest.

- [ ] **Step 1: Install the dependency and its peer dependencies**

```bash
npm install
npx expo install expo-share-intent expo-linking
```

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
        // arbitrary text snippets, images, or literally anything else shared on the device -- this
        // list is kept identical to statementFile.ts's own ACCEPTED_MIME so the share sheet only
        // ever offers Fynora for something the existing pipeline can actually stage.
        // Verify after any expo-share-intent upgrade: Task 1's manifest check below.
        androidIntentFilters: ['application/pdf', 'text/csv', 'text/comma-separated-values', 'text/plain'],
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

Expected: an `<intent-filter>` block with `android:name="android.intent.action.SEND"`, category `DEFAULT`, and four `<data android:mimeType="..."/>` entries matching the four MIME types from Step 2 exactly (not `text/*`/`*/*`).

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
- Consumes: `useShareIntentContext` from `expo-share-intent` (Task 1/2), `detectStatementFormat` from `../lib/statementFile` (Task 3), `SharedStatementFile`/`SharedStatementError`/`RootParamList` from `./types` (Task 4).
- Produces: `useShareIntentDeepLink(navigationRef, ready, signedIn): { onNavigationReady: () => void }` — same call shape as `usePushNotificationNavigation`/`useAppPathDeepLink`, consumed by Task 6.

- [ ] **Step 1: Write the hook**

Create `mobile/src/navigation/useShareIntentDeepLink.ts`:

```ts
import { useCallback, useEffect, useRef } from 'react';
import { useShareIntentContext, type ShareIntentFile } from 'expo-share-intent';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { detectStatementFormat } from '../lib/statementFile';
import type { RootParamList, SharedStatementFile, SharedStatementError } from './types';

const UNSUPPORTED_MESSAGE = 'Choose a .csv or .pdf bank or credit card statement.';

type Pending = { kind: 'file'; value: SharedStatementFile } | { kind: 'error'; value: SharedStatementError };

function toPending(file: ShareIntentFile): Pending {
  const nonce = Date.now();
  const format = detectStatementFormat(file.fileName);
  if (!format) {
    return { kind: 'error', value: { message: UNSUPPORTED_MESSAGE, nonce } };
  }
  return {
    kind: 'file',
    value: {
      file: { uri: file.path, name: file.fileName, type: format === 'PDF' ? 'application/pdf' : 'text/csv' },
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
 */
export function useShareIntentDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  ready: boolean,
  signedIn: boolean,
) {
  const { hasShareIntent, shareIntent, resetShareIntent } = useShareIntentContext();
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
      navigationRef.navigate('Import', { sharedFile: pending.value });
    } else {
      navigationRef.navigate('Import', { sharedFileError: pending.value });
    }
  }, [navigationRef]);

  // This effect only ever builds a plain object and stores it in a ref -- unlike ImportScreen's own
  // consumption of the result (Task 7), which can trigger upload(), a real network call, so THAT
  // step deliberately runs in ImportScreen's own effect, not here. Safe to run more than once for
  // the same shareIntent value (e.g. React StrictMode's dev-mode double-invoke).
  useEffect(() => {
    if (!hasShareIntent) return;
    const file = shareIntent.files?.[0];
    // Clears expo-share-intent's own state so the same share doesn't replay after this effect has
    // already turned it into a pending navigation.
    resetShareIntent();
    if (!file) return;
    pendingRef.current = toPending(file);
    tryConsume();
    // shareIntent is a fresh object identity per native event, so it -- not hasShareIntent alone --
    // is the real "did a new share arrive" signal; resetShareIntent is a fresh closure every render
    // (not memoized by the library) and reading it via the effect's own closure is enough.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasShareIntent, shareIntent]);

  useEffect(() => {
    readyRef.current = ready;
    if (wasSignedInRef.current && !signedIn) pendingRef.current = null;
    wasSignedInRef.current = signedIn;
    tryConsume();
  }, [ready, signedIn, tryConsume]);

  return { onNavigationReady: tryConsume };
}
```

- [ ] **Step 2: Write the tests**

Create `mobile/src/navigation/useShareIntentDeepLink.test.ts`:

```ts
import { renderHook } from '@testing-library/react-native';
import { useShareIntentDeepLink } from './useShareIntentDeepLink';

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

  it('calls resetShareIntent once a share has been read', () => {
    const navigationRef = fakeNavigationRef();
    const resetShareIntent = jest.fn();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' },
        resetShareIntent,
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(resetShareIntent).toHaveBeenCalled();
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
});
```

- [ ] **Step 3: Run the tests**

```bash
npx node@22 $(npm bin)/jest src/navigation/useShareIntentDeepLink.test.ts
```

Expected: PASS, all 7 cases.

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

- [ ] **Step 2: Read the new route params and add the dedupe state**

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

- [ ] **Step 3: Add the consumption effect**

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

- [ ] **Step 4: Write the tests**

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

- [ ] **Step 5: Run the tests**

```bash
npx node@22 $(npm bin)/jest src/screens/import/ImportScreen.test.tsx
```

Expected: PASS — the 4 new cases, and every pre-existing test in this file (this is the file's own regression check; a shared `applyPicked` extraction touching `handlePick` must not change that path's existing behavior).

- [ ] **Step 6: Typecheck and lint**

```bash
npm run typecheck
npm run lint
```

- [ ] **Step 7: Commit**

```bash
git add src/screens/import/ImportScreen.tsx src/screens/import/ImportScreen.test.tsx
git commit -m "feat(mobile): consume a shared statement file in ImportScreen"
```

---

### Task 8: Full regression pass and manual on-device verification

**Files:** none (verification only)

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
- [ ] Share an unsupported file (e.g. a `.jpg`) → Fynora still appears (matches the intent-filter's declared MIME types) but shows the "Choose a .csv or .pdf..." error on the upload step, not a crash or a blank screen.
- [ ] Cold start: force-stop Fynora first, then share a PDF → Fynora launches fresh, and (if App Lock is enabled in Settings) shows the lock screen before the password card — confirming the "Correction to the spec" section above: this is a genuine app open, and the lock screen is expected here, not suppressed.
- [ ] Warm start: with Fynora already open on some other tab, share a PDF → Fynora comes to the foreground already on the Import tab's password card.
- [ ] Sign out, force-stop, then share a PDF → Fynora opens to the sign-in screen (Login), not the Import tab; sign in and confirm the share was dropped, not silently replayed (no password card appears post-login).
- [ ] Confirm Fynora's existing App Links (tapping an emailed `https://app.fynora.net/...` link) still opens the app correctly on the same build — the regression this task's manifest check in Task 1 flagged as worth confirming.

- [ ] **Step 4: Record any manual-check failures as new tasks, not silent gaps**

Per this project's completion criteria: if any box above fails, that is a bug to fix and re-verify, not something to note and move past. If everything passes, the feature is done for this plan's scope (Android only, per the spec's explicit non-goal on iOS).
