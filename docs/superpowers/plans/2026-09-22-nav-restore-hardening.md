# Navigation State Restoration Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do NOT dispatch subagents per task — Finora's project instructions and the user's global instructions both require implementation and its verification to be done directly in-session, not delegated to the Agent tool. Execute inline.

**Goal:** When Finora mobile is cleared from the recents/app-switcher list and relaunched, it must always open on Dashboard (Home) — never the screen the user was last on. Independently, close a real crash risk in two screens and a real content-flash gap in App Lock's foreground re-lock check.

**Architecture:** One removal plus two independent hardening fixes. Task 1 removes navigation-state restoration entirely (`useNavigationStatePersistence` and its three call sites) rather than adjusting what it restores — this is a deliberate product decision (below), not a partial mitigation. Tasks 2 and 3 are unrelated bugs the same audit surfaced and remain worth fixing regardless of Task 1's outcome: a params-destructure crash in two screens, and a content-flash gap in `AppLockGate`'s foreground re-lock check. No new files beyond two small test files, no new dependencies, no navigation-library upgrade.

**Tech Stack:** React Native (Expo SDK 57), React Navigation v7 (`@react-navigation/native` ^7.3.14), `@react-native-async-storage/async-storage`, Jest + `@testing-library/react-native`, TanStack Query v5.

**Spec:** This conversation's audit and the product decision that followed it (no separate spec doc). Key facts this plan treats as ground truth, each independently verified against the running codebase before this plan was written:
- **Product decision (this conversation, 2026-09-22):** clearing the app from recents and relaunching must always open Dashboard, never the last-viewed screen. Plain backgrounding (switched away, not killed) is untouched — that resumes from live JS memory, no code in this feature is involved either way. This supersedes an earlier draft of this plan that tried to preserve top-level-tab restoration while only dropping nested/sensitive screens; that draft is not implemented anywhere and none of its code exists in the repo.
- `route.params.linkId` in `SettingsBankSyncConfirmScreen.tsx:26` and `const { ticketId } = route.params` in `SupportTicketDetailScreen.tsx:40` are unguarded destructures of a value the old persistence hook's `stripParams()` deliberately removed before every write — the mechanism that made this a live crash risk. Removing restoration (Task 1) already eliminates the path that reached this; Task 2 still guards both screens directly, independent of Task 1, against any other path that could reach them without a param (a future deep link, a manual `navigate()` call).
- Both routes are real, reachable, typed-required-param screens inside `AppTabs`'s `More` tab stack (`navigation/types.ts:71,90`), not local component state.
- Every other `route.params` site in `src/screens` (audited via `grep -rn "route\.params" src/screens`) already guards with `?.` or a nullish fallback: `RegisterScreen`, `LoginScreen`, `LedgerScreen`, `DashboardScreen`, `ImportScreen`, `VerifyEmailChangeScreen`. `ResetPasswordScreen.tsx:38` is unguarded but lives in `AuthStack`, which was never part of the persisted tree even before Task 1 (`isAppTabsActive` gated persistence to the signed-in tree only) — out of scope, not reachable via this bug either way.
- App-lock default is OFF (`appLock.ts` comment: "(default, most common) disabled state") — this plan does not change that default (a separate product decision), but Task 3 does close the foreground re-lock content-flash gap in `AppLockGate` for the users who do have it on.
- `AppTabs.tsx`'s `Tab.Navigator` registers `Home` (`DashboardScreen`) first (`AppTabs.tsx:195`) — React Navigation's documented default is the first registered screen when no `initialRouteName`/`initialState` is supplied, so removing `initialState` entirely (Task 1) makes `Home` the default with no further configuration needed.

## Global Constraints

- No new npm dependencies.
- Every behavior change must ship with a test that fails against the current code and passes after the fix (TDD; this repo already follows that pattern in `useNavigationStatePersistence.test.ts`, `RootErrorBoundary.test.tsx`, and `AppLockGate.test.tsx`).
- No `Co-Authored-By` trailer in any commit message (repository CLAUDE.md, absolute rule).
- Every commit happens inside this worktree (`/Users/sid/Downloads/finora/.claude/worktrees/nav-restore-hardening`), never the primary checkout.
- Run the full mobile test suite before considering any task done, not just the new/touched test file — `AppLockGate` and `RootNavigator` in particular are exercised by other components' tests too.

---

## Task 1: Remove navigation-state restoration — always open to Dashboard after a kill

Implements the product decision above by deleting the feature rather than reshaping it: `useNavigationStatePersistence.ts` and every one of its three call sites (`RootNavigator.tsx`'s `NavigationContainer` wiring, `AuthContext.tsx`'s sign-out cleanup, `RootErrorBoundary.tsx`'s crash-recovery cleanup) go away. As a consequence this also removes the restore-time crash and sensitive-route-restoration exposure the earlier audit found (`SettingsBankSyncConfirmScreen`/`SupportTicketDetailScreen` can never again be the screen the app restores into, because nothing is ever restored) and removes any schema-versioning question, since there is no persisted schema left.

**Files:**
- Delete: `mobile/src/navigation/useNavigationStatePersistence.ts`
- Delete: `mobile/src/navigation/useNavigationStatePersistence.test.ts`
- Modify: `mobile/src/navigation/RootNavigator.tsx`
- Modify: `mobile/src/context/AuthContext.tsx`
- Modify: `mobile/src/components/RootErrorBoundary.tsx`
- Test: `mobile/src/navigation/RootNavigator.test.tsx`
- Test: `mobile/src/components/RootErrorBoundary.test.tsx`

**Interfaces:**
- Consumes: nothing new — this task only removes code.
- Produces: `NavigationContainer` in `RootNavigator.tsx` no longer receives `initialState`/`onStateChange` props, so it (and `AppTabs`'s `Tab.Navigator`) falls back to its own default initial route — `Home`. `RootErrorBoundary.reset` becomes a plain synchronous method (was `async`). `AuthContext.clearLocalState` drops one call, no signature change.

- [ ] **Step 1: Delete the two obsolete tests in RootErrorBoundary.test.tsx first**

In `mobile/src/components/RootErrorBoundary.test.tsx`, delete these two `it(...)` blocks entirely — they test the "clear persisted state before remounting" mechanism this task removes:

```ts
  // Regression test: RootNavigator hands useNavigationStatePersistence's persisted route straight
  // to NavigationContainer as `initialState` on every mount. Without clearing it first, "Try
  // again" would remount RootNavigator right back onto the exact screen that just crashed --
  // reproducing the same crash instead of recovering from it.
  it('clears the persisted navigation state before remounting its children', async () => {
    await AsyncStorage.setItem('finora_nav_state', JSON.stringify({ index: 0, routes: [{ name: 'CrashedScreen' }] }));

    renderBoundary(<Boom />);
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    });

    expect(await AsyncStorage.getItem('finora_nav_state')).toBeNull();
  });

  // Regression test: reset() used to await the storage clear unguarded -- a rejected
  // AsyncStorage.removeItem would have left `hasError` stuck true forever, making "Try again"
  // permanently non-functional instead of merely failing to clear stale state.
  it('still recovers when clearing the persisted navigation state fails', async () => {
    jest.spyOn(AsyncStorage, 'removeItem').mockRejectedValueOnce(new Error('disk full'));
    const shouldThrow = { current: true };
    function ThrowsUntilCleared() {
      if (shouldThrow.current) throw new Error('blew up');
      return <Text>recovered</Text>;
    }

    renderBoundary(<ThrowsUntilCleared />);
    shouldThrow.current = false;
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    });

    expect(screen.getByText('recovered')).toBeTruthy();
  });
```

Then check whether `AsyncStorage` is still used anywhere else in this file:

Run: `cd mobile && grep -n "AsyncStorage" src/components/RootErrorBoundary.test.tsx`

If the only remaining hit is the `import AsyncStorage from '@react-native-async-storage/async-storage';` line at the top, delete that import line too.

- [ ] **Step 2: Run RootErrorBoundary's tests to confirm the remainder still passes**

Run: `cd mobile && npx jest src/components/RootErrorBoundary.test.tsx`
Expected: PASS. This step can't go red→green like a normal TDD step (deleting a test can't fail); it exists to confirm the remaining tests don't implicitly depend on setup the two deleted tests provided, before touching production code in Step 3.

- [ ] **Step 3: Simplify RootErrorBoundary.tsx**

Remove the import at the top of `mobile/src/components/RootErrorBoundary.tsx`:

```ts
import { clearPersistedNavigationState } from '../navigation/useNavigationStatePersistence';
```

Replace the `reset` method:

```ts
  private reset = async () => {
    // "Try again" remounts RootNavigator from scratch -- but useNavigationStatePersistence
    // persists the current route on every navigation change and RootNavigator hands it straight
    // back to NavigationContainer as `initialState` on that remount, so without this, "Try again"
    // would very plausibly land the user right back on the exact screen that just crashed and
    // reproduce the same crash immediately. Cleared unconditionally, not just when a crash happens
    // to occur mid-AppTabs (a no-op AsyncStorage.removeItem otherwise) -- same convergence-point
    // reasoning as AuthContext's clearLocalState clearing this same key on sign-out.
    //
    // Guarded, unlike that fire-and-forget call: this one is awaited before the reset below, so an
    // unguarded storage failure here would leave the fallback's own "Try again" permanently
    // non-functional -- worse than the bug it exists to prevent, on the one screen that is
    // supposed to be the app's last line of defense.
    try {
      await clearPersistedNavigationState();
    } catch (error) {
      reportHandledError(error, 'root-navigator-reset');
    }
    this.setState({ hasError: false });
  };
```

with:

```ts
  // "Try again" remounts RootNavigator from scratch. Nothing to clear before that remount --
  // RootNavigator no longer restores a persisted screen on mount, so there is no stale route it
  // could land back on (AppTabs always opens to its own default, Home, regardless of what was on
  // screen when this crashed).
  private reset = () => {
    this.setState({ hasError: false });
  };
```

- [ ] **Step 4: Run RootErrorBoundary's tests again**

Run: `cd mobile && npx jest src/components/RootErrorBoundary.test.tsx`
Expected: PASS.

- [ ] **Step 5: Remove RootNavigator's test mock for the hook**

In `mobile/src/navigation/RootNavigator.test.tsx`, delete this block entirely:

```ts
jest.mock('./useNavigationStatePersistence', () => ({
  useNavigationStatePersistence: () => ({ isReady: true, initialState: undefined, onStateChange: jest.fn() }),
}));
```

- [ ] **Step 6: Run it to verify it now fails**

Run: `cd mobile && npx jest src/navigation/RootNavigator.test.tsx`
Expected: FAIL — `RootNavigator.tsx` still imports and calls `useNavigationStatePersistence`, which now has no mock in this test file, so it hits the real hook and the real (unmocked here) `AsyncStorage`. Red either way, confirming the test file no longer matches the production code Step 7 is about to change.

- [ ] **Step 7: Update RootNavigator.tsx**

Remove the import:

```ts
import { useNavigationStatePersistence } from './useNavigationStatePersistence';
```

Remove the hook call:

```ts
  const navPersistence = useNavigationStatePersistence(bootstrapping, isAppTabsActive);
```

Change the bootstrapping gate from:

```ts
  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  // Also waits on navPersistence: reading its one AsyncStorage key is comparably fast, and folding
  // it into the same spinner avoids a second, separate loading flash right after this one clears.
  if (bootstrapping || !navPersistence.isReady) {
```

to:

```ts
  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  if (bootstrapping) {
```

Change the `NavigationContainer` props from:

```ts
    <NavigationContainer
      ref={navigationRef}
      theme={navTheme}
      linking={{ prefixes: linkingPrefixes }}
      onReady={onNavigationReady}
      // Both undefined whenever isAppTabsActive is false: navPersistence never populates
      // initialState outside that condition (see the hook's own doc comment), and onStateChange
      // itself no-ops via the same activeRef check. Passing them unconditionally rather than only
      // inside the AppTabs branch below because NavigationContainer is the one component instance
      // wrapping all three conditionally-rendered trees -- it can't take different props per
      // child.
      initialState={navPersistence.initialState}
      onStateChange={navPersistence.onStateChange}
    >
```

to:

```ts
    <NavigationContainer
      ref={navigationRef}
      theme={navTheme}
      linking={{ prefixes: linkingPrefixes }}
      onReady={onNavigationReady}
      // No initialState/onStateChange: by product decision, a killed-and-relaunched app always
      // opens on AppTabs's own default route -- Home/Dashboard, the first Tab.Screen registered
      // in AppTabs.tsx -- never wherever the user last was. Plain backgrounding (switched away,
      // not killed) is untouched by this: that resumes from live JS memory, no navigation-state
      // persistence involved either way.
    >
```

`isAppTabsActive` itself stays exactly where it is — it's still read by `useEmailChangeDeepLink`/`usePushNotificationNavigation`/`useAppPathDeepLink` above, unrelated to this removal.

- [ ] **Step 8: Run RootNavigator's tests**

Run: `cd mobile && npx jest src/navigation/RootNavigator.test.tsx`
Expected: PASS.

- [ ] **Step 9: Update AuthContext.tsx**

Remove the import:

```ts
import { clearPersistedNavigationState } from '../navigation/useNavigationStatePersistence';
```

In `clearLocalState`, replace:

```ts
    // Same reasoning as queryClient.clear() just above: a persisted screen position is a smaller
    // leak than a balance, but the next person signing in on this device landing on wherever the
    // previous account last was is still a mistake worth ruling out at this single convergence
    // point rather than by remembering it at every exit path. Fire-and-forget, same as every other
    // AsyncStorage write in this app -- there is no UI waiting on this to resolve.
    void clearPersistedNavigationState();
    // Item B: same convergence-point reasoning as clearPersistedNavigationState just above, one
    // layer further down. queryClient.clear() (above) only empties the IN-MEMORY cache -- Item B's
```

with:

```ts
    // Item B: same convergence-point reasoning as pauseQueryPersistence/queryClient.clear() above.
    // queryClient.clear() only empties the IN-MEMORY cache -- Item B's
```

(This drops the `clearPersistedNavigationState()` call and its own comment block — there is nothing left to clear, navigation state is never persisted any more — and repoints the following comment's dangling "just above" reference at the calls that are still actually there.)

- [ ] **Step 10: Run AuthContext's tests**

Run: `cd mobile && npx jest src/context/AuthContext.test.tsx`
Expected: PASS. This file had no direct reference to `clearPersistedNavigationState` (confirmed via `grep -rln "clearPersistedNavigationState" src --include="*.test.tsx"` before this plan was written, which returned only `RootErrorBoundary.test.tsx` and `RootNavigator.test.tsx`), so no failure is expected here — this step exists to confirm that, not to fix one.

- [ ] **Step 11: Delete the hook and its test file**

```bash
cd mobile
git rm src/navigation/useNavigationStatePersistence.ts src/navigation/useNavigationStatePersistence.test.ts
```

- [ ] **Step 12: Confirm nothing else references the removed module or key**

Run: `cd mobile && grep -rn "useNavigationStatePersistence\|clearPersistedNavigationState\|finora_nav_state" src`
Expected: no output.

- [ ] **Step 13: Full mobile suite and typecheck**

Run: `cd mobile && npx jest && npx tsc --noEmit`
Expected: both clean.

- [ ] **Step 14: Commit**

```bash
cd mobile
git add -A
git commit -m "fix(mobile): always open to Dashboard after a kill, remove nav-state restoration

Product decision: when the app is cleared from recents and relaunched, it
should always open on Dashboard, never the screen the user was last on.
Removes useNavigationStatePersistence and its three call sites
(RootNavigator's NavigationContainer wiring, AuthContext's sign-out
cleanup, RootErrorBoundary's crash-recovery cleanup) rather than adjusting
what it restored.

As a consequence this also removes a restore-time crash risk and
sensitive-route exposure an earlier audit found (SettingsBankSyncConfirmScreen /
SupportTicketDetailScreen could never again be the screen an app restores
into, because nothing is restored) and removes any schema-versioning
question, since there is no persisted schema left."
```

---

## Task 2: Defensive guards on the two unsafe screens

Independent of Task 1: even with navigation-state restoration removed entirely, `SupportTicketDetailScreen` and `SettingsBankSyncConfirmScreen` still destructure a required route param with no guard, and are still reachable by any future deep link, any manually-constructed `navigate()` call with a missing param, or a future feature that re-introduces some form of state restoration. Both should degrade to a normal "not found" UI instead of crashing, independent of how they're reached.

**Files:**
- Modify: `mobile/src/screens/SupportTicketDetailScreen.tsx`
- Modify: `mobile/src/screens/SettingsBankSyncConfirmScreen.tsx`
- Test: `mobile/src/screens/SupportTicketDetailScreen.test.tsx` (create if it doesn't already exist — check first)
- Test: `mobile/src/screens/SettingsBankSyncConfirmScreen.test.tsx` (create if it doesn't already exist — check first)

**Interfaces:**
- Consumes: `MoreStackParamList` from `mobile/src/navigation/types.ts` (unchanged).
- Produces: no change to either component's exported name or `Props` type — both keep `NativeStackScreenProps<MoreStackParamList, '...'>`.

- [ ] **Step 1: Check whether test files already exist for these two screens**

Run: `cd mobile && ls src/screens/SupportTicketDetailScreen.test.tsx src/screens/SettingsBankSyncConfirmScreen.test.tsx 2>&1`

If either exists, read it first and add the new `it(...)` block(s) below into its existing `describe`, matching that file's existing render-harness pattern (mock navigation/route props, existing query-client wrapper if any), instead of creating a new file from scratch. If a file doesn't exist, create it minimally as shown in Step 2/6 below.

- [ ] **Step 2: Write the failing test for SupportTicketDetailScreen**

If no test file exists, create `mobile/src/screens/SupportTicketDetailScreen.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SupportTicketDetailScreen } from './SupportTicketDetailScreen';

function renderScreen(params: { ticketId?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const route = { key: 'SupportTicketDetail', name: 'SupportTicketDetail' as const, params };
  const navigation = { goBack: jest.fn() } as never;
  return render(
    <QueryClientProvider client={queryClient}>
      <SupportTicketDetailScreen route={route as never} navigation={navigation} />
    </QueryClientProvider>
  );
}

describe('SupportTicketDetailScreen', () => {
  it('shows the not-found state instead of crashing when ticketId is missing from route.params', async () => {
    renderScreen({});

    await waitFor(() => expect(screen.getByText('Ticket not found')).toBeTruthy());
  });
});
```

If a test file already exists, add only the `it('shows the not-found state instead of crashing when ticketId is missing from route.params', ...)` block above into it, reusing that file's existing render helper instead of the one shown here.

- [ ] **Step 3: Run it to verify it fails**

Run: `cd mobile && npx jest src/screens/SupportTicketDetailScreen.test.tsx -t "ticketId is missing"`
Expected: FAIL — current code does `const { ticketId } = route.params;` which throws `TypeError: Cannot destructure property 'ticketId' of 'route.params' as it is undefined` when `params` is `{}` or `route.params` itself is `undefined`. Either way the test fails because the component throws during render, not because of a wrong assertion.

- [ ] **Step 4: Fix SupportTicketDetailScreen.tsx**

Change line 40 of `mobile/src/screens/SupportTicketDetailScreen.tsx` from:

```ts
  const { ticketId } = route.params;
```

to:

```ts
  const ticketId = route.params?.ticketId;
```

And change the `useQuery` block starting at line 42 from:

```ts
  const ticketQuery = useQuery({
    queryKey: ['support-ticket-detail', ticketId],
    queryFn: () => supportApi.detail(ticketId),
    // A 404 means "not yours, or doesn't exist" -- same reasoning as the web page's identical
    // query, and StatementHistoryScreen's own owned-resource fetches.
    retry: false,
  });
```

to:

```ts
  const ticketQuery = useQuery({
    queryKey: ['support-ticket-detail', ticketId],
    // Non-null assertion is safe: `enabled` below keeps this from ever running without a
    // ticketId. A missing ticketId (this screen reached without one -- a bad deep link, a
    // navigate() call missing a param) falls through to the same "not found" state as a real
    // 404 below, since `ticketQuery.data` stays undefined either way.
    queryFn: () => supportApi.detail(ticketId!),
    enabled: !!ticketId,
    // A 404 means "not yours, or doesn't exist" -- same reasoning as the web page's identical
    // query, and StatementHistoryScreen's own owned-resource fetches.
    retry: false,
  });
```

No other change needed — the existing `if (!ticketQuery.data)` block (line 58) already renders the "Ticket not found" UI, and with `enabled: !!ticketId` a missing `ticketId` means `ticketQuery.isLoading` is `false` (TanStack Query v5: `isLoading` is `isPending && isFetching`, and a disabled query never fetches) and `ticketQuery.data` stays `undefined`, so render falls straight through to that existing block.

- [ ] **Step 5: Run it to verify it passes**

Run: `cd mobile && npx jest src/screens/SupportTicketDetailScreen.test.tsx`
Expected: PASS, including this new test and every pre-existing test in the file.

- [ ] **Step 6: Write the failing test for SettingsBankSyncConfirmScreen**

If no test file exists, create `mobile/src/screens/SettingsBankSyncConfirmScreen.test.tsx`:

```tsx
import { render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsBankSyncConfirmScreen } from './SettingsBankSyncConfirmScreen';

function renderScreen(params: { linkId?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const route = { key: 'SettingsBankSyncConfirm', name: 'SettingsBankSyncConfirm' as const, params };
  const goBack = jest.fn();
  const navigation = { goBack } as never;
  render(
    <QueryClientProvider client={queryClient}>
      <SettingsBankSyncConfirmScreen route={route as never} navigation={navigation} />
    </QueryClientProvider>
  );
  return { goBack };
}

describe('SettingsBankSyncConfirmScreen', () => {
  it('shows a recoverable message instead of crashing when linkId is missing from route.params', () => {
    const { goBack } = renderScreen({});

    expect(screen.getByText(/link is no longer available/i)).toBeTruthy();
    fireEvent.press(screen.getByText('Go Back'));
    expect(goBack).toHaveBeenCalledTimes(1);
  });
});
```

If a test file already exists, add only the `it('shows a recoverable message...', ...)` block above into it, reusing that file's existing render helper instead of the one shown here.

- [ ] **Step 7: Run it to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncConfirmScreen.test.tsx -t "linkId is missing"`
Expected: FAIL — current code does `const linkId = route.params.linkId;` which throws `TypeError: Cannot read properties of undefined (reading 'linkId')` when `route.params` is `undefined`.

- [ ] **Step 8: Fix SettingsBankSyncConfirmScreen.tsx**

Change line 26 of `mobile/src/screens/SettingsBankSyncConfirmScreen.tsx` from:

```ts
  const linkId = route.params.linkId;
```

to:

```ts
  const linkId = route.params?.linkId;
```

Then, immediately after the existing `useEffect` block (after line 41, before `async function confirmExisting() {` on line 43), insert:

```ts

  // Missing linkId (this screen reached without one -- a bad deep link, a navigate() call
  // missing a param) would otherwise throw inside confirmExisting/confirmNew the moment either
  // is called. Placed after every hook above so this stays a plain early return, not a
  // conditional hook call.
  if (!linkId) {
    return (
      <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
        <Text style={[styles.title, { color: c.ink }]}>Link no longer available</Text>
        <Text style={[styles.hint, { color: c.mutedInk }]}>
          This account link couldn't be found. Go back and try again from Bank Sync.
        </Text>
        <Button label="Go Back" onPress={() => navigation.goBack()} />
      </ScrollView>
    );
  }
```

- [ ] **Step 9: Run it to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncConfirmScreen.test.tsx`
Expected: PASS, including this new test and every pre-existing test in the file.

- [ ] **Step 10: Commit**

```bash
cd mobile
git add src/screens/SupportTicketDetailScreen.tsx src/screens/SettingsBankSyncConfirmScreen.tsx \
        src/screens/SupportTicketDetailScreen.test.tsx src/screens/SettingsBankSyncConfirmScreen.test.tsx
git commit -m "fix(mobile): guard against missing route params in SupportTicketDetail/SettingsBankSyncConfirm

Both screens destructured a required route param with no guard. Task 1
removes the one path that made this a live restore-time crash, but that
only closes one route to these screens -- this adds the same defense
directly, so any other path with a missing param (a bad deep link, a
navigate() call) degrades to a normal not-found/go-back UI instead of a
crash."
```

---

## Task 3: Close the AppLockGate foreground re-lock content-flash gap

On a genuine foreground return with app-lock enabled, `children` currently renders (because `locked` still holds its last value, `false`) for the async gap between the `active` AppState event and `appLock.isEnabled()`'s promise resolving, before `lockAndPrompt()` flips `locked` to `true`. This task closes that gap the same way the existing cold-start gate already works (`if (!checked) return null;`). Entirely independent of Tasks 1 and 2.

**Files:**
- Modify: `mobile/src/components/AppLockGate.tsx`
- Test: `mobile/src/components/AppLockGate.test.tsx`

**Interfaces:**
- Consumes: existing `appLock.isEnabled()` (`Promise<boolean>`), `appLock.isAuthenticating()`, `appLock.justFinishedAuthenticating()`, `appLock.isSharing()`, `appLock.justFinishedSharing()` — no changes to `appLock.ts` itself.
- Produces: `AppLockGate` keeps its existing `{ children: ReactNode }` props and default export — no caller (`App.tsx`) needs any change.

- [ ] **Step 1: Write the failing test**

In `mobile/src/components/AppLockGate.test.tsx`, add this test inside the existing `describe('AppLockGate', ...)` block (after the `'locks and auto-prompts a session with the setting on, unlocking on success'` test), reusing the file's existing `signIn`, `enableAppLock`, `renderGate`, `goToBackground`, `returnToForeground` helpers already defined at the top of the file:

```ts
  it('does not flash protected content between a genuine foreground return and the async lock check resolving', async () => {
    await signIn();
    await enableAppLock();
    mockedAuthenticateAsync.mockResolvedValue({ success: true });
    renderGate();

    // Let the cold-start lock check resolve and the auto-prompt clear it, same as the
    // existing "locks and auto-prompts" test above.
    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
    expect(screen.getByText('protected content')).toBeTruthy();

    goToBackground();

    // Fire the foreground transition inside a sync act() so this assertion runs BEFORE
    // appLock.isEnabled()'s promise has a chance to resolve -- that pending microtask is
    // exactly the gap this test exists to close.
    act(() => {
      returnToForeground();
    });
    expect(screen.queryByText('protected content')).toBeNull();

    await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    expect(screen.queryByText('protected content')).toBeNull();
  });
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd mobile && npx jest src/components/AppLockGate.test.tsx -t "does not flash protected content"`
Expected: FAIL — `expect(screen.queryByText('protected content')).toBeNull()` fails right after `returnToForeground()`, because current code still renders `children` (locked is still `false`) until the `appLock.isEnabled()` promise resolves.

- [ ] **Step 3: Fix AppLockGate.tsx**

Add a new state variable after the existing `checkedToken`/`checked` declaration (after line 69, before the `useEffect` at line 73):

```ts
  // True from the instant a genuine foreground transition starts its appLock.isEnabled() check
  // until that check resolves -- closes the gap where `locked` still holds its pre-background
  // value (false) and children would otherwise paint for one or more frames before lockAndPrompt()
  // has a chance to flip it. Mirrors the cold-start gate below (`if (!checked) return null`),
  // which already closes the equivalent gap for the FIRST check; this is the same gap on every
  // check after the first.
  const [reverifying, setReverifying] = useState(false);
```

Then in the foreground `AppState` effect (lines 130-181), change the block:

```ts
      if (cameToForeground && token !== null && !skipAsSelfInduced) {
        void appLock.isEnabled().then((enabled) => {
          if (enabled) lockAndPrompt();
        });
      }
```

to:

```ts
      if (cameToForeground && token !== null && !skipAsSelfInduced) {
        setReverifying(true);
        void appLock.isEnabled().then((enabled) => {
          setReverifying(false);
          if (enabled) lockAndPrompt();
        });
      }
```

Then change the render gate. Current code (lines 183-195):

```ts
  if (bootstrapping || token === null) {
    return <>{children}</>;
  }
  // Session present but the check for THIS token hasn't resolved yet -- render nothing rather
  // than `children`, closing the cold-start race where RootNavigator would otherwise be paintable
  // for one commit before a lock-enabled session gets locked (and the equivalent race on a fresh
  // login right after a different session's logout, within the same app process).
  if (!checked) {
    return null;
  }
  if (!locked) {
    return <>{children}</>;
  }
```

becomes:

```ts
  if (bootstrapping || token === null) {
    return <>{children}</>;
  }
  // Session present but the check for THIS token hasn't resolved yet -- render nothing rather
  // than `children`, closing the cold-start race where RootNavigator would otherwise be paintable
  // for one commit before a lock-enabled session gets locked (and the equivalent race on a fresh
  // login right after a different session's logout, within the same app process).
  if (!checked) {
    return null;
  }
  // Same reasoning as `!checked` above, for every foreground check after the first: render
  // nothing rather than whatever `locked` last held, until this check's outcome is known.
  if (reverifying) {
    return null;
  }
  if (!locked) {
    return <>{children}</>;
  }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd mobile && npx jest src/components/AppLockGate.test.tsx`
Expected: PASS, including this new test and every pre-existing test in the file (the file has extensive coverage of self-induced blips, grace periods, and mid-session toggle behavior — all of it must stay green, since `reverifying` must not fire or must clear correctly for every one of those paths too).

- [ ] **Step 5: Commit**

```bash
cd mobile
git add src/components/AppLockGate.tsx src/components/AppLockGate.test.tsx
git commit -m "fix(mobile): close AppLockGate content-flash gap on foreground re-lock

locked stayed at its pre-background value (false) for the async gap
between a genuine foreground return and appLock.isEnabled() resolving,
so protected content could paint for a frame before the lock screen
mounted. Adds a reverifying flag that blocks render the same way the
existing cold-start check (!checked) already does, closing the same gap
on every foreground check after the first."
```

---

## Task 4: Full mobile suite + manual on-device verification

**Files:** none modified — verification only.

- [ ] **Step 1: Run the full mobile test suite**

Run: `cd mobile && npx jest`
Expected: PASS, zero failures, including every file touched by Tasks 1-3 and everything that imports them transitively (`App.test.tsx`, any other consumer of `AppLockGate`).

- [ ] **Step 2: Run lint and typecheck**

Run: `cd mobile && npx tsc --noEmit && npx eslint src/navigation/RootNavigator.tsx src/context/AuthContext.tsx src/components/RootErrorBoundary.tsx src/screens/SupportTicketDetailScreen.tsx src/screens/SettingsBankSyncConfirmScreen.tsx src/components/AppLockGate.tsx`
Expected: both clean.

- [ ] **Step 3: Manual on-device confirmation**

Using the iOS Simulator or Android emulator with a signed-in test account:

1. Sign in, navigate to any tab other than Home (e.g. More > Settings > Security, or Transactions), kill the app from the app switcher (swipe away, not just background), relaunch from the home screen icon.
2. Observe: app opens directly on Dashboard (Home), not the tab/screen it was on. This is the primary requirement this task set out to build — confirm it holds for at least two different starting screens (one flat tab like Transactions, one deep inside More like Settings > Security), since the fix is the same for both (no restoration at all) but both are worth eyeballing once.
3. Separately: sign in, navigate to More > Settings > Bank Sync > tap "Confirm Account" on a pending link (or More > Support > open any ticket) to reach `SettingsBankSyncConfirm` or `SupportTicketDetail` directly via a deep link or test harness rather than normal navigation, with the param deliberately omitted if your test setup allows it — confirm the screen shows its "not found"/"link no longer available" message rather than crashing (Task 2's guard, exercised independently of Task 1 since Task 1 already prevents the normal path there).
4. Separately, with app-lock enabled in Settings: background the app, wait a moment, foreground it again, and watch closely for any frame of visible content before the lock screen appears (this is inherently a visual/timing check the unit test in Task 3 can assert on but can't fully substitute for on a real device).

Record the actual observed outcome of each numbered check here before considering this task done — per this repository's standing "no guessing" rule, do not mark this step complete without having actually run it.

---

## Self-Review Notes

- **Spec coverage:** the product decision (always Dashboard after a kill) is Task 1 in full. The earlier audit's other four priorities: (1) disable screen capture before launch — explicitly out of scope, already tracked separately as a go-live checklist item, not a code-correctness bug; (2) sensitive-route allowlist/blocklist for restoration — superseded by Task 1's full removal, there is no restoration left to scope; (3) version persisted navigation state — superseded the same way, there is no persisted state left to version; (4) verify zero content flash before AppLock appears — Task 3.
- **Repo-wide `route.params` audit** the user asked for was run before this plan was written (`grep -rn "route\.params" src/screens`); results are summarized in the Spec section above so the executor doesn't have to re-derive them.
- **Placeholder scan:** no TBD/TODO, every step has real code or a real command.
- **Type consistency:** n/a for Task 1 (pure removal, no new types). Task 2/3 introduce no new shared types across steps.

## Reviewer Feedback Log

**Round 1** (on the original top-level-tab-restoration design, since superseded by the product decision in Round 2):
- Version-tag starting number, structural-vs-string test assertions, and screen-capture priority ordering were all raised and resolved against that design. None of that code exists in the repo any more — Task 1 now deletes the feature outright rather than reshaping it, so the version tag, the `toRestorableState` truncation, and their tests no longer exist to have a version number or an assertion style. Recorded here for history, not carried forward as open items.
- The one round-1 item that did carry forward: **Task 1's behavior change needs explicit product sign-off before coding starts.**

**Round 2** (resolved): asked directly — "clearing the app from recents and relaunching must always open Dashboard, that's it." This is a stronger, simpler requirement than the round-1 draft's top-level-tab-restoration compromise, and this plan's Task 1 implements it directly (full removal, not truncation). No open items remain before execution.
