# Mobile haptics Implementation Plan (pull request 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **On this repository the owner has ruled out agents: execute inline with superpowers:executing-plans.**

**Goal:** The phone answers the person's touch: a light tick for choices, a thud for pull to refresh, distinct feels for success, a blocked send and a failure. A switch in Settings › General turns all of it off.

**Architecture:**
- **Switch state.** `lib/haptics.ts` stays the only caller of `expo-haptics`. It gains a three-state in-memory switch (not yet read / on / off), primed once at app start from `safeStorage`, which every haptic function checks.
- **Shared pieces.** Two shared components carry most of the calls, so new screens inherit them:
  - `AppSwitch`, a selection haptic on every value change;
  - `AppRefreshControl`, an impact on every committed pull.

  A small `hapticSelectionIfChanged(prev, next)` covers segments, chips and pickers.
- **Outcomes.** Success and error each live in exactly one place per action: the handler that learns the outcome.
- **Guards.** Source scans keep `expo-haptics`, `Switch` and `RefreshControl` behind their one door each.

**Tech Stack:** Expo SDK 57 (`expo-haptics` 57.0.3), React Native 0.86, TypeScript 6, Jest 30, React Native Testing Library 13, TanStack Query v5.

**Spec:** `docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md` (Part 2, Testing and checks, Platforms, Completion criteria, Shipping).

**Depends on:** the skeletons pull request (plan `2026-10-15-mobile-skeletons.md`) merged to `main`. Start this one from a fresh worktree off `origin/main` after that merge, never on the squash-merged skeletons branch.

## Global Constraints

- Work in a fresh worktree `/Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics` on branch `feature/mobile-haptics`, cut from `origin/main` after the skeletons merge. Run `npm ci` at the worktree root (commit hook) and in `mobile/`. Never write in `/Users/sid/Downloads/finora`.
- Bash: one plain command per call; no `for` loops with variables, no `$(...)` inside `git -C`.
- Checks run under Node 22, as CI does. Every `Run: jest <paths>` here means exactly:
  `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile <paths>`
  with `<paths>` relative to `mobile/`.
- Commit subjects lowercase after the type; **never** a `Co-Authored-By` or any AI-attribution line.
- Tests first, watched failing. Haptic tests assert the **kind and the count of every kind** (`expectHaptics`, Task 1), never just "one fired".
- `lib/haptics.ts` is the only importer of `expo-haptics`.
- The haptics table in the spec is the contract: every call traces to a row; each outcome haptic lives in one handler; one action gives at most one haptic; never while scrolling; never for something the person did not do.
- The switch's state lives on the phone (`safeStorage`, key `finora_haptics`), not on the server.
- The simulator cannot vibrate; haptics are checked there through tests and the `__DEV__` log (Task 11).
- Ships in store build 1.1.0; never sent over the air to the 1.0.0 runtime.

## Android, read from source (expo-haptics 57.0.3, 2026-10-15)

`android/src/main/java/expo/modules/haptics/HapticsModule.kt` drives the vibrator directly: `Vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))` on API 26 and up, a plain pattern below. It does not use `View.performHapticFeedback`, the path Android's touch-feedback setting governs. The library declares `android.permission.VIBRATE` in its own manifest. The waveforms (milliseconds, amplitude 0–255):

| Kind | Timings | Amplitudes |
|---|---|---|
| selection | 0, 50 | 0, 30 |
| impact (medium) | 0, 43 | 0, 50 |
| success | 0, 40, 100, 40 | 0, 50, 0, 60 |
| warning | 0, 40, 120, 60 | 0, 40, 0, 60 |
| error | 0, 60, 100, 40, 80, 50 | 0, 50, 0, 40, 0, 50 |

So on Android every kind is a short buzz; success, warning and error differ by rhythm, not by texture. Whether the phone's touch-feedback setting silences them is **not established**: the code path suggests it does not, and the owner's Android checklist (Task 12) answers it on a real phone. If it does not silence them, that is reported, not worked around (spec).

## Rulings made while writing this plan (the spec is corrected in Task 0)

1. **Fourteen pull-to-refresh screens, not 13.** These all have `onRefresh`:
   - Dashboard, Transactions, Insights, Reports, Advanced Reports, Budgets;
   - Goals, Investments, Journey, Profile, Financial Memory;
   - My Tickets, Category Review, Gmail Review.

   All fourteen get the impact, through `AppRefreshControl`.
2. **Pickers fire only on change.** `OptionPickerModal` and `CategoryPickerModal` fire selection on every tap today, including on the option already chosen. The spec's table says "tapping the one already selected gives nothing" and is the one contract, so both change to fire only when the pick differs. This is the one change to the nine places that already fire.
3. **A switch that saves at once gets selection only.** The spec says a switch fires selection on change, a failed action fires error, and one action gives at most one haptic. These conflict for switches that save on toggle: notification preferences, app lock, "counts as income" on a money kind. The ruling: such a switch fires selection on the tap and nothing on the outcome. A failure still shows the switch's existing error text and moves the switch back.
4. **A blocked send fires only where a handler checks on press.** Most sheets disable Save until the form is valid, so a press never reaches a handler and nothing fires. The warning fires where a handler validates on press:
   - Goals `addGoal`;
   - Investments `addHolding`;
   - Profile `save`;
   - Settings › General `savePreferences`;
   - Category edit `save`;
   - Budgets, which already does.
5. **Which actions count as "a save, add, import or delete".** The spec names a list and states a rule. This plan applies the rule to every action a person starts that saves, adds or deletes their data or settings.

   **Included** (each gets success after the server confirms, error on failure):

   | Area | Actions |
   |---|---|
   | Transactions and accounts | Add Transaction; Edit Transaction; the Account form; Accounts delete |
   | Categories and money kinds | Category edit; Category delete; "counts as" choice (`CountsAsSection.run`); money-kind rename and delete; forget a sender; create a kind |
   | Goals and investments | goal create; goal contribution (Amount prompt); goal delete; holding add; holding remove; save snapshot |
   | Settings | Profile name; Settings › General preferences; Categorization threshold; Change Email; Change Password (success on the final step, error on any step) |
   | Connections and devices | device sign-out; Bank Sync disconnect; Gmail disconnect; Bank Correction acknowledge |
   | Account and billing | Export data; subscription pause and resume |
   | Support and referrals | Feedback; New Ticket; referral code (prompt and card); referral reward redeem; referral code copy |
   | Statements | statement delete; recent-import dismiss |
   | Home and Insights | the recurring-merchant dismiss on Home and Insights |

   **Left without an outcome haptic, with the reason:**

   | Actions | Why |
   |---|---|
   | sign-in, registration, password reset, phone and email verification steps | getting into the account, not saving data; each ends on a new screen that is its own confirmation |
   | Fyn chat send and its ratings | a conversation, not a save |
   | statement share, invoice open | they open the system share sheet or browser |
   | Gmail connect and sync, Bank Sync connect | they hand off to an outside flow or a background job whose outcome is not known here |
   | statement re-import | hands the rows to Import, which fires on confirm |
   | retake tour | navigates into the tour |
   | deactivate or delete the whole account | the session ends at once |
   | Import, Budgets, Ledger, Quick Sort, Category Review, Gmail Review, Mark as Transfer | they already fire; they keep what they do |

   The owner can move any row between the two lists at review.

## Review Focus

1. **Haptics turned off, then a save.** Someone who switched haptics off must feel nothing anywhere, including the tab bar, pickers, pull to refresh and every outcome. Pinned in Task 1, where the gate lives in `fire()`, plus one off-state test in each of Tasks 2, 3, 4 and 6.
2. **A tap during start-up.** Before the stored value is read, nothing fires, even for someone who never turned haptics off. Pinned in Task 1 (`unread` never reaches `expo-haptics`).
3. **Double tap on Save.** A same-frame double press runs one save (`useSingleFlight`) and must give one success, not two. Pinned in Task 6 (Add Transaction) and Task 8 (Goals create).
4. **A flaky network retry.** A query or mutation that the library retries must not add haptics. Mutations do not retry in this app (`api/queryClient.ts` sets `retry: 1` for queries only), and no haptic is tied to a query, so this holds by construction. Task 9 pins it for one mutation: a failed then retried save gives one error, then one success, never two errors for one press.
5. **The haptics switch toggled fast.** Several flips in a row must leave the last position in memory and in storage, even though SecureStore writes are async. Pinned in Task 1 (serialised writes, the last value stored).

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `mobile/src/lib/haptics.ts` | modify | the switch store, the gate in `fire()`, `hapticSelectionIfChanged`, the `__DEV__` log |
| `mobile/src/lib/haptics.test.ts` | modify | the switch lifecycle |
| `mobile/src/test/haptics.ts` | create | `expectHaptics()` test helper |
| `mobile/src/test/setup.ts` | modify | every test starts with haptics on |
| `mobile/App.tsx` | modify | `primeHapticsSetting()` at module scope |
| `mobile/src/lib/haptics.guard.test.ts` | create | scans: `expo-haptics`, `Switch`, `RefreshControl` each behind one door |
| `mobile/src/components/AppSwitch.tsx` | create | `Switch` plus selection on change |
| `mobile/src/components/AppRefreshControl.tsx` | create | `RefreshControl` plus impact on a committed pull |
| `mobile/src/screens/SettingsGeneralScreen.tsx` | modify | the Haptics switch, the Theme segment |
| the call sites named in Tasks 4–10 | modify | one haptic per row of the table |

---

### Task 0: Correct the spec against the code

**Files:**
- Modify: `docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md`

- [ ] **Step 1: Edit "Where the calls go".**
  - Change "one impact from the `onRefresh` handlers of the 13 screens that have it" to "one impact on every pull to refresh, through a shared `AppRefreshControl` (14 screens have one today)".
  - After the list of sheets, add:

    > The rule in the table applies to every action a person starts that saves, adds or deletes their data or settings; the build plan lists which actions that covers and which are left out, with the reason (sign-in and verification steps, chat, sharing, outside flows, actions that already fire).
- [ ] **Step 2: Edit the table.**
  - In the row "Turning a switch on or off", add to "Only when": "; a switch that saves at once gets this one haptic and no outcome haptic (one action, one haptic)".
  - In the row for segments, chips and pickers, add: "(the two picker pop-ups, which fire on every tap today, change to match)".
  - In the row "A form that cannot be sent yet", add: "; only where the form checks on press -- a Save that is disabled until the form is valid cannot be pressed, so nothing fires".
- [ ] **Step 3: Under "Platforms",** add the Android table and paragraph from this plan's "Android, read from source" section.
- [ ] **Step 4: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "docs(mobile): haptics rules made exact against the code"
```

---

### Task 1: The switch store, the gate, the test helper, the import guard

**Files:**
- Modify: `mobile/src/lib/haptics.ts`, `mobile/src/lib/haptics.test.ts`, `mobile/src/test/setup.ts`, `mobile/App.tsx`
- Create: `mobile/src/test/haptics.ts`, `mobile/src/lib/haptics.guard.test.ts`

**Interfaces:**
- Produces, from `lib/haptics.ts`:
  - the existing five functions, now gated;
  - `hapticSelectionIfChanged<T>(prev: T, next: T): void`;
  - `primeHapticsSetting(): void`;
  - `setHapticsEnabled(enabled: boolean): void`;
  - `useHapticsEnabled(): boolean`;
  - `__resetHapticsForTests(): void`;
  - `__setHapticsStateForTests(state: 'unread' | 'on' | 'off'): void`.
- Produces, from `test/haptics.ts`: `expectHaptics(expected: Partial<Record<'success' | 'warning' | 'error' | 'selection' | 'impact', number>>): void`.

- [ ] **Step 1: Write the failing tests.** Append to `haptics.test.ts`. Merge the imports below into the file's existing import lines, one import per module: `import/no-duplicates` fails a second `from './haptics'`.

```ts
import { act, renderHook } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import {
  __resetHapticsForTests, __setHapticsStateForTests, hapticSelectionIfChanged, primeHapticsSetting,
  setHapticsEnabled, useHapticsEnabled,
} from './haptics';
import { expectHaptics } from '../test/haptics';

const store = SecureStore as jest.Mocked<typeof SecureStore> & { __store: Map<string, string> };
const flush = () => act(async () => {});

describe('the haptics switch', () => {
  beforeEach(() => __resetHapticsForTests());

  it('fires nothing before the stored setting has been read', () => {
    hapticSuccess();
    hapticSelection();
    hapticImpact();
    expectHaptics({});
  });

  it('a missing value means on', async () => {
    primeHapticsSetting();
    await flush();
    hapticSuccess();
    expectHaptics({ success: 1 });
  });

  it('a failed read means on', async () => {
    store.getItemAsync.mockRejectedValueOnce(new Error('keychain'));
    primeHapticsSetting();
    await flush();
    hapticSelection();
    expectHaptics({ selection: 1 });
  });

  it('a stored "off" silences every kind', async () => {
    store.__store.set('finora_haptics', 'off');
    primeHapticsSetting();
    await flush();
    hapticSuccess(); hapticWarning(); hapticError(); hapticSelection(); hapticImpact();
    expectHaptics({});
  });

  it('turning it on fires exactly one selection, after the state is on, and saves "on"', async () => {
    __setHapticsStateForTests('off');
    setHapticsEnabled(true);
    expectHaptics({ selection: 1 });
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('on');
  });

  it('turning it off fires nothing and saves "off"', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    expectHaptics({});
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('off');
  });

  it('a choice made before the read finishes is not overwritten by it', async () => {
    store.__store.set('finora_haptics', 'on');
    primeHapticsSetting();
    setHapticsEnabled(false);
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('fast toggling leaves the last position in memory and in storage', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    setHapticsEnabled(true);
    setHapticsEnabled(false);
    await flush();
    expect(store.__store.get('finora_haptics')).toBe('off');
    hapticSuccess();
    expectHaptics({ selection: 1 });
  });

  it('a failed save does not move the switch back', async () => {
    __setHapticsStateForTests('on');
    store.setItemAsync.mockRejectedValueOnce(new Error('keychain'));
    setHapticsEnabled(false);
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('survives a restart: a fresh read finds the saved value', async () => {
    __setHapticsStateForTests('on');
    setHapticsEnabled(false);
    await flush();
    __resetHapticsForTests();
    primeHapticsSetting();
    await flush();
    hapticSuccess();
    expectHaptics({});
  });

  it('a rejecting native call does not throw', async () => {
    __setHapticsStateForTests('on');
    jest.mocked(Haptics.notificationAsync).mockRejectedValueOnce(new Error('no vibrator'));
    expect(() => hapticSuccess()).not.toThrow();
    await flush();
  });

  it('useHapticsEnabled follows the switch', () => {
    __setHapticsStateForTests('on');
    const { result } = renderHook(() => useHapticsEnabled());
    expect(result.current).toBe(true);
    act(() => setHapticsEnabled(false));
    expect(result.current).toBe(false);
  });

  it('hapticSelectionIfChanged fires only when the value differs', () => {
    __setHapticsStateForTests('on');
    hapticSelectionIfChanged('ALL', 'ALL');
    expectHaptics({});
    hapticSelectionIfChanged('ALL', 'INCOME');
    expectHaptics({ selection: 1 });
  });
});
```

Create `mobile/src/test/haptics.ts`:

```ts
import * as Haptics from 'expo-haptics';

type Kind = 'success' | 'warning' | 'error' | 'selection' | 'impact';

/** Counts every haptic the app asked for since the last jest.clearAllMocks (setup.ts runs one
 *  before each test), by kind. */
export function hapticCalls(): Record<Kind, number> {
  const notes = jest.mocked(Haptics.notificationAsync).mock.calls.map(([t]) => t);
  return {
    success: notes.filter((t) => t === Haptics.NotificationFeedbackType.Success).length,
    warning: notes.filter((t) => t === Haptics.NotificationFeedbackType.Warning).length,
    error: notes.filter((t) => t === Haptics.NotificationFeedbackType.Error).length,
    selection: jest.mocked(Haptics.selectionAsync).mock.calls.length,
    impact: jest.mocked(Haptics.impactAsync).mock.calls.length,
  };
}

/** Exactly these haptics and no others: kind and count, every kind checked. */
export function expectHaptics(expected: Partial<Record<Kind, number>>): void {
  expect(hapticCalls()).toEqual({ success: 0, warning: 0, error: 0, selection: 0, impact: 0, ...expected });
}
```

Create `mobile/src/lib/haptics.guard.test.ts`:

```ts
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/** Each of these native touch points has one door, so the haptics switch and the haptic that goes
 *  with it cannot be bypassed by a new screen. */
const SRC = join(__dirname, '..');
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.(ts|tsx)$/.test(f) && !/\.test\.tsx?$/.test(f) && !p.includes('/test/') ? [p] : [];
});
const importers = (re: RegExp) => files(SRC).filter((p) => re.test(readFileSync(p, 'utf8'))).map((p) => relative(SRC, p)).sort();

it('only lib/haptics.ts imports expo-haptics', () => {
  expect(importers(/from 'expo-haptics'/)).toEqual(['lib/haptics.ts']);
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/lib/haptics.test.ts src/lib/haptics.guard.test.ts`
Expected: FAIL. The new names are not exported, and "fires nothing before … read" fails because today every call fires. The guard passes already, which shows it as a guard, not a change.

- [ ] **Step 3: Write the store.** In `lib/haptics.ts`, add the imports `import { useSyncExternalStore } from 'react';` and `import { safeStorage } from './safeStorage';`. Add this block above `fire`:

```ts
/**
 * The in-app Haptics switch (Settings › General). Three states: 'unread' until the stored value has
 * been read at start-up, then 'on' or 'off'. Nothing fires while 'unread', so a person who turned
 * haptics off never feels one during start-up. A missing value, or a failed read (safeStorage
 * returns null on failure), means on. The phone's own settings still apply on top of this; the
 * switch is only ever an extra way to turn them off.
 */
type HapticsState = 'unread' | 'on' | 'off';
const STORAGE_KEY = 'finora_haptics';
let state: HapticsState = 'unread';
let primed = false;
/** A choice made before the start-up read lands wins over it. */
let chosen = false;
/** Saves run one after another, so fast toggling always leaves the last value in storage. */
let writes: Promise<void> = Promise.resolve();
const listeners = new Set<() => void>();
const setState = (next: HapticsState) => { state = next; listeners.forEach((l) => l()); };

export function primeHapticsSetting(): void {
  if (primed) return;
  primed = true;
  void safeStorage.getItem(STORAGE_KEY).then((stored) => {
    if (!chosen) setState(stored === 'off' ? 'off' : 'on');
  });
}

/** The switch was flipped: the state changes at once, turning it on fires one selection, then the
 *  value is saved. A failed haptic or a failed save does not move the switch back. */
export function setHapticsEnabled(enabled: boolean): void {
  chosen = true;
  setState(enabled ? 'on' : 'off');
  if (enabled) hapticSelection();
  const value = enabled ? 'on' : 'off';
  writes = writes.then(() => safeStorage.setItem(STORAGE_KEY, value));
}

export function useHapticsEnabled(): boolean {
  primeHapticsSetting();
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => state !== 'off',
  );
}

export function __resetHapticsForTests(): void {
  state = 'unread'; primed = false; chosen = false; writes = Promise.resolve(); listeners.clear();
}

export function __setHapticsStateForTests(next: HapticsState): void {
  state = next; primed = true; chosen = false;
}
```

  Change `fire` to:

```ts
function fire(kind: string, call: () => Promise<unknown>): void {
  if (state !== 'on') return;
  if (__DEV__) console.info(`[haptics] ${kind}`);
  call().catch(() => {});
}
```

  Change each of the five functions to pass a kind and a thunk:
  - `hapticSuccess` → `fire('success', () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success))`;
  - the others alike, with `'warning'`, `'error'`, `'selection'` and `'impact'`.

  The thunk matters: the native call must not even start when the switch is off. Add the new helper:

```ts
/** A segment, chip or picker option was chosen: one tick, only when the value actually changes.
 *  Tapping the option that is already selected gives nothing (spec, haptics table). */
export function hapticSelectionIfChanged<T>(prev: T, next: T): void {
  if (!Object.is(prev, next)) hapticSelection();
}
```

  `safeStorage.setItem` never rejects (it swallows failures), so `writes` cannot become a rejected chain. The "a failed save" test mocks `SecureStore.setItemAsync` underneath it, which is the realistic failure.

- [ ] **Step 4: Start every test with haptics on, and prime at app start.**
  - In `test/setup.ts`'s `beforeEach`, after `jest.clearAllMocks();`, add:

    ```ts
    // Module state, like the alert queue above. Every screen test starts with haptics on (the
    // default for a person who never touched the switch); haptics.test.ts resets it to 'unread'
    // itself to test start-up.
    require('../lib/haptics').__setHapticsStateForTests('on');
    ```
  - In `App.tsx`, import `primeHapticsSetting` from `./src/lib/haptics` and call it on the line after `primeReduceTransparency();` (module scope, line 48).

- [ ] **Step 5: Run the haptics tests, then every test that already asserts a haptic.**

Run: `jest src/lib src/screens/BudgetsScreen.test.tsx src/screens/LedgerScreen.test.tsx src/screens/GmailReviewScreen.test.tsx src/screens/CategoryReviewScreen.test.tsx src/screens/import src/components/QuickSortPanel.test.tsx src/components/MarkTransferModal.test.tsx src/components/OptionPickerModal.test.tsx src/components/CategoryPickerModal.test.tsx`
Expected: PASS. The existing haptic assertions still hold because setup turns haptics on. If jest reports a path in the list that matches no test file, `ls` that folder and drop it; do not create one here.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add mobile/src/lib/haptics.ts mobile/src/lib/haptics.test.ts mobile/src/lib/haptics.guard.test.ts mobile/src/test/haptics.ts mobile/src/test/setup.ts mobile/App.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "feat(mobile): a haptics switch that silences everything until read"
```

---

### Task 2: `AppSwitch`, every switch, and the Haptics row in Settings › General

**Files:**
- Create: `mobile/src/components/AppSwitch.tsx`, `mobile/src/components/AppSwitch.test.tsx`
- Modify, replacing `Switch` with `AppSwitch`:
  - `screens/settings/AppLockSection.tsx:116`
  - `components/StatementRefreshBanner.tsx:221`
  - `screens/settings/NotificationPreferencesSection.tsx:71`
  - `screens/import/ImportScreen.tsx:1045`, `:1514`
  - `components/InflowKindsSettings.tsx:89`
  - `components/ledger/LedgerFiltersSheet.tsx:101`
- Modify: `screens/SettingsGeneralScreen.tsx` (new Haptics row; Theme segment)
- Modify: `components/InflowKindsSettings.tsx:28` (`attempt` gains an outcome flag, used in Task 7)
- Modify: `mobile/src/lib/haptics.guard.test.ts`
- Test: `SettingsGeneralScreen.test.tsx`, `components/ledger/LedgerFiltersSheet.test.tsx` (or the Ledger test that opens filters)

**Interfaces:**
- Consumes: `hapticSelection`, `setHapticsEnabled`, `useHapticsEnabled`, `hapticSelectionIfChanged`, `expectHaptics`.
- Produces: `AppSwitch(props: SwitchProps & { silent?: boolean })`. Calls `props.onValueChange(next)` first, then one selection unless `silent`.

- [ ] **Step 1: Write the failing tests.**

  `AppSwitch.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { AppSwitch } from './AppSwitch';
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';

it('fires one selection when the person changes the value, after the change is handled', () => {
  const onValueChange = jest.fn();
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={onValueChange} />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expect(onValueChange).toHaveBeenCalledWith(true);
  expectHaptics({ selection: 1 });
});

it('fires nothing when silent', () => {
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={jest.fn()} silent />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expectHaptics({});
});

it('fires nothing when haptics are off', () => {
  __setHapticsStateForTests('off');
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={jest.fn()} />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expectHaptics({});
});
```

  `SettingsGeneralScreen.test.tsx` (the file already renders with a resolved `user.get`):

```tsx
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';
import * as SecureStore from 'expo-secure-store';

describe('Haptics switch', () => {
  it('is on by default and turning it off fires nothing and saves "off"', async () => {
    renderScreen();
    const sw = await screen.findByLabelText('Haptics');
    expect(sw.props.value).toBe(true);
    fireEvent(sw, 'valueChange', false);
    expectHaptics({});
    await act(async () => {});
    expect((SecureStore as unknown as { __store: Map<string, string> }).__store.get('finora_haptics')).toBe('off');
  });

  it('turning it on fires exactly one selection', async () => {
    __setHapticsStateForTests('off');
    renderScreen();
    fireEvent(await screen.findByLabelText('Haptics'), 'valueChange', true);
    expectHaptics({ selection: 1 });
  });
});

describe('Theme segment', () => {
  it('fires one selection when the theme changes, none when the chosen one is tapped', async () => {
    renderScreen();
    fireEvent.press(await screen.findByLabelText('System theme'));
    expectHaptics({});
    fireEvent.press(screen.getByLabelText('Dark theme'));
    expectHaptics({ selection: 1 });
  });
});
```

  Extend `haptics.guard.test.ts`:

```ts
it('only AppSwitch imports Switch from react-native', () => {
  expect(importers(/import \{[^}]*\bSwitch\b[^}]*\} from 'react-native'/)).toEqual(['components/AppSwitch.tsx']);
});
```

  For the Ledger filters' "International only" row (the row is the control, the switch is its picture), add to the test that covers `LedgerFiltersSheet`:

```tsx
it('the International only row fires one selection per toggle', () => {
  renderSheet({ internationalOnly: false });
  fireEvent.press(screen.getByLabelText('Show only international transactions'));
  expectHaptics({ selection: 1 });
});
```

  Find the file with `grep -rln "LedgerFiltersSheet" mobile/src --include=*.test.tsx`. Use its render helper; if it only reaches the sheet through `LedgerScreen`, open the filters the way its existing filter test does, then press.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components/AppSwitch.test.tsx src/screens/SettingsGeneralScreen.test.tsx src/lib/haptics.guard.test.ts`
Expected: FAIL: no `AppSwitch`, no Haptics row, no theme haptic, and seven files import `Switch`.

- [ ] **Step 3: Write `AppSwitch.tsx`.**

```tsx
import { Switch, type SwitchProps } from 'react-native';
import { hapticSelection } from '../lib/haptics';

/**
 * React Native's Switch with the one haptic the spec gives a switch: a selection tick when the
 * person changes its value, after the change has been handled. Every switch in the app goes through
 * here (haptics.guard.test.ts), so new switches inherit it.
 *
 * `silent` is for the one switch that owns its own haptic: Settings › General › Haptics, where
 * turning haptics on fires its confirming tick inside setHapticsEnabled and turning them off must
 * fire nothing.
 */
export function AppSwitch({ onValueChange, silent = false, ...rest }: SwitchProps & { silent?: boolean }) {
  return (
    <Switch
      {...rest}
      onValueChange={onValueChange ? (next) => {
        void onValueChange(next);
        if (!silent) hapticSelection();
      } : undefined}
    />
  );
}
```

- [ ] **Step 4: Swap every `Switch` for `AppSwitch`.**
  - Change the import and the element name in each of the seven sites; their props are unchanged.
  - `LedgerFiltersSheet`'s switch has no `onValueChange` (a picture), so it fires nothing itself. Its row's `onPress` becomes `() => { hapticSelection(); onInternational(!internationalOnly); }`: a toggle always changes the value.
  - In `InflowKindsSettings`, change `attempt`'s signature to `attempt(action, fallback, outcomeHaptic = true)` (Task 7 uses the flag), and pass `false` from the switch's `onValueChange` (Ruling 3).

- [ ] **Step 5: Add the Haptics row and the Theme haptic in Settings › General.**
  - Inside `SettingsGeneralScreen`, add `const hapticsOn = useHapticsEnabled();`.
  - After the Theme hint `<Text>`, add:

```tsx
      <View style={[styles.switchRow, { borderTopColor: c.border }]}>
        <View style={styles.retakeTourText}>
          <Text style={[styles.fieldLabel, { color: c.ink, marginTop: 0 }]}>Haptics</Text>
          <Text style={[styles.hint, { color: c.mutedInk }]}>
            Small vibrations when you tap, save or pull to refresh. Your phone&apos;s own settings still apply.
          </Text>
        </View>
        <AppSwitch
          silent
          value={hapticsOn}
          onValueChange={setHapticsEnabled}
          accessibilityLabel="Haptics"
          trackColor={{ true: c.primary, false: c.border }}
          thumbColor={hapticsOn ? c.onPrimary : undefined}
        />
      </View>
```

    Add the style `switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth }`.
  - The Theme segment's `onPress` becomes `() => { hapticSelectionIfChanged(themeSetting, option); setThemeSetting(option); }`.

  The row's copy is new wording. It is the spec's switch, and the owner reviews it in the pull request.

- [ ] **Step 6: Run everything touched.**

Run: `jest src/components/AppSwitch.test.tsx src/screens/SettingsGeneralScreen.test.tsx src/lib src/screens/settings src/components/StatementRefreshBanner.test.tsx src/components/InflowKindsSettings.test.tsx src/components/ledger src/screens/import src/screens/LedgerScreen.test.tsx`
Expected: PASS. If a file in that list does not exist, drop it from the command; `ls` the folder first.

- [ ] **Step 7: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add mobile/src
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "feat(mobile): switches tick, and settings can turn haptics off"
```

---

### Task 3: `AppRefreshControl` on all fourteen pull-to-refresh screens

**Files:**
- Create: `mobile/src/components/AppRefreshControl.tsx`, `mobile/src/components/AppRefreshControl.test.tsx`
- Modify (`RefreshControl` → `AppRefreshControl`): Insights `:308`, GmailReview `:140`, Budgets `:112`, CategoryReview `:230`, Journey `:52`, Investments `:216`, Profile `:117`, SupportTickets `:63`, Dashboard `:416`, FinancialMemory `:95`, Reports `:189`, AdvancedReports `:158`, Goals `:174`. All under `screens/`.
- Modify: `screens/LedgerScreen.tsx:526-527` (the list's own `refreshing`/`onRefresh` props become `refreshControl={<AppRefreshControl … />}`)
- Modify: `mobile/src/lib/haptics.guard.test.ts`
- Test: `DashboardScreen.test.tsx`, `LedgerScreen.test.tsx`

**Interfaces:**
- Produces: `AppRefreshControl(props: RefreshControlProps)`. One `hapticImpact()` per `onRefresh` call, then the given `onRefresh`.

- [ ] **Step 1: Write the failing tests.**

  `AppRefreshControl.test.tsx`:

```tsx
import { render } from '@testing-library/react-native';
import { RefreshControl } from 'react-native';
import { AppRefreshControl } from './AppRefreshControl';
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';

// React Native calls the native control's onRefresh when a pull is committed; that is the handler
// this reaches, the inner RefreshControl's.
it('one impact when a pull is committed, then the refresh runs', () => {
  const onRefresh = jest.fn();
  const view = render(<AppRefreshControl refreshing={false} onRefresh={onRefresh} />);
  view.UNSAFE_getByType(RefreshControl).props.onRefresh();
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expectHaptics({ impact: 1 });
});

it('the refresh still runs, without a haptic, when haptics are off', () => {
  __setHapticsStateForTests('off');
  const onRefresh = jest.fn();
  const view = render(<AppRefreshControl refreshing={false} onRefresh={onRefresh} />);
  view.UNSAFE_getByType(RefreshControl).props.onRefresh();
  expect(onRefresh).toHaveBeenCalledTimes(1);
  expectHaptics({});
});
```

  `DashboardScreen.test.tsx` and `LedgerScreen.test.tsx`, one test each:

```tsx
it('a pull to refresh gives one impact', async () => {
  renderScreen();
  // wait for the screen's first content the way the file's other tests do, then:
  const control = screen.UNSAFE_getByType(RefreshControl);
  act(() => control.props.onRefresh());
  expectHaptics({ impact: 1 });
});
```

  Import `RefreshControl` from `react-native`.

  Extend `haptics.guard.test.ts`:

```ts
it('only AppRefreshControl imports RefreshControl from react-native', () => {
  expect(importers(/import \{[^}]*\bRefreshControl\b[^}]*\} from 'react-native'/)).toEqual(['components/AppRefreshControl.tsx']);
});

it('no list uses its own refreshing/onRefresh props, which would skip the shared control', () => {
  const bare = files(SRC).filter((p) => !p.endsWith('components/AppRefreshControl.tsx')).flatMap((p) => {
    const src = readFileSync(p, 'utf8');
    return [...src.matchAll(/\bonRefresh=/g)]
      .filter((m) => !src.slice(Math.max(0, m.index! - 300), m.index).includes('<AppRefreshControl'))
      .map(() => relative(SRC, p));
  });
  expect(bare).toEqual([]);
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components/AppRefreshControl.test.tsx src/lib/haptics.guard.test.ts src/screens/DashboardScreen.test.tsx src/screens/LedgerScreen.test.tsx -t "impact|RefreshControl|refreshing"`
Expected: FAIL.

- [ ] **Step 3: Write it.**

```tsx
import { RefreshControl, type RefreshControlProps } from 'react-native';
import { hapticImpact } from '../lib/haptics';

/**
 * React Native's RefreshControl with the spec's pull-to-refresh haptic: one impact when the pull is
 * committed. onRefresh is the only event React Native gives -- there is no earlier "threshold
 * reached" -- so that is where it fires. Every pull to refresh goes through here
 * (haptics.guard.test.ts).
 *
 * A plain props pass-through, so Android's ScrollView, which clones its refreshControl element and
 * hands it the list as a child, works through this wrapper unchanged.
 */
export function AppRefreshControl({ onRefresh, ...rest }: RefreshControlProps) {
  return (
    <RefreshControl
      {...rest}
      onRefresh={() => {
        hapticImpact();
        onRefresh?.();
      }}
    />
  );
}
```

- [ ] **Step 4: Swap the fourteen sites.**
  - The thirteen `<RefreshControl` sites become `<AppRefreshControl` with the same props, importing from `../components/AppRefreshControl`. Remove `RefreshControl` from each `react-native` import.
  - In `LedgerScreen.tsx`, replace the two list props `refreshing={isFetching && !isFetchingNextPage}` and `onRefresh={() => void refetch()}` with:

    ```tsx
    refreshControl={<AppRefreshControl refreshing={isFetching && !isFetchingNextPage} onRefresh={() => void refetch()} tintColor={c.primary} />}
    ```

    `tintColor` matches every other screen.

- [ ] **Step 5: Run every screen touched.**

Run: `jest src/components/AppRefreshControl.test.tsx src/lib/haptics.guard.test.ts src/screens`
Expected: PASS. A screen test that asserted `RefreshControl`'s `refreshing` prop still finds it: the inner control is the same component.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add mobile/src
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "feat(mobile): one impact on every pull to refresh"
```

---

### Task 4: The tab pill and "+"

**Files:**
- Modify: `mobile/src/navigation/FloatingTabBar.tsx:77`, `mobile/src/navigation/AppTabs.tsx:133-150`
- Test: `mobile/src/navigation/FloatingTabBar.test.tsx`, `mobile/src/navigation/AppTabs.test.tsx`

- [ ] **Step 1: Write the failing tests.** In `FloatingTabBar.test.tsx`, using the file's render helper:

```tsx
it('one selection per tab press, also on the tab already open', () => {
  renderBar(); // the file's helper
  fireEvent.press(screen.getByTestId('tab-Ledger'));
  expectHaptics({ selection: 1 });
  fireEvent.press(screen.getByTestId('tab-Ledger'));
  expectHaptics({ selection: 2 });
});

it('nothing when haptics are off', () => {
  __setHapticsStateForTests('off');
  renderBar();
  fireEvent.press(screen.getByTestId('tab-Ledger'));
  expectHaptics({});
});
```

  Use the route names and testIDs the file's existing tests press. In `AppTabs.test.tsx`, press the "+" (the file's existing Import button test shows its label) and `expectHaptics({ selection: 1 })`.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/navigation/FloatingTabBar.test.tsx src/navigation/AppTabs.test.tsx -t "selection|nothing"`
Expected: FAIL.

- [ ] **Step 3: Write it.**
  - In `FloatingTabBar`'s `onPress`, add `hapticSelection();` as the first statement. It is the one navigation control with a haptic, and a repeated press is the same tap, so the haptic is unconditional.
  - In `ImportFabButton`, change `onPress={onPress}` to `onPress={() => { hapticSelection(); onPress(); }}`.

- [ ] **Step 4: Run.**

Run: `jest src/navigation`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add mobile/src/navigation
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "feat(mobile): the tab pill and plus tick on press"
```

---

### Task 5: Segments, chips and pickers fire only on change

**Files and the change at each site:** `onPress={() => X(next)}` becomes `onPress={() => { hapticSelectionIfChanged(current, next); X(next); }}`.

| Site | `current` | `next` |
|---|---|---|
| `components/ledger/LedgerControls.tsx:68` (All / Income / Expense) | `typeFilter` | `t.value` |
| `components/ledger/LedgerFiltersSheet.tsx:72` (status chips) | `status` | `s` |
| `screens/InsightsScreen.tsx:336` (Insights tab row) | `activeTab` | `t.key` |
| `screens/DashboardScreen.tsx:1009` (Cash Flow range) | `cashFlowRange` | `r` |
| `screens/AdvancedReportsScreen.tsx` (the full year / this year so far buttons, `setComparisonMode`) | `comparisonMode` | `'full'` or `'ytd'` |
| `components/InvestmentActivityCard.tsx:125` (period chips) | `months` | `p.months` |
| `screens/AccountFormSheet.tsx:~150` (account type) | the current type | `t` |
| `screens/AddTransactionSheet.tsx:~148` (Income / Expense) | `type` | `t` |
| `screens/EditTransactionSheet.tsx:~135` (Income / Expense) | `type` | `t` |
| `screens/import/ImportScreen.tsx:1367` (existing / new account) | `accountChoice` | `choice` |
| `screens/import/ImportScreen.tsx:1404` (account rows) | `selectedAccountId` | `a.id` |
| `screens/import/ImportScreen.tsx:1437` (type chips) | `accountForm.accountType` | `t` |
| `screens/settings/ChangePasswordSheet.tsx:347` (other devices) | `signOutOtherDevices` | `option.value` |
| `screens/support/FeedbackSheet.tsx:109` (feedback type) | `type` | `t.value` |
| `components/CategoryEditSheet.tsx:122` (icons) | `icon` | `i.token` |
| `components/CategoryEditSheet.tsx:147` (colours) | `color` | `col.token` |
| `components/InflowKindPicker.tsx:49` (kind chips) | `selectedId` | `k.id` |
| `components/InflowKindPicker.tsx:80` (count as income?) | `countsAsIncome` | `v` |
| `onboarding/SpendingTrackingQuestionScreen.tsx:64` (the Spending Tracking question) | `selected` | `opt.key` |
| `components/OptionPickerModal.tsx:61` (replace `hapticSelection()`) | `selected` | `item` |
| `components/CategoryPickerModal.tsx:117` (replace `hapticSelection()`) | the picker's selected name prop | `category.name` |

The Theme segment was done in Task 2.

- **Approximate lines.** Rows marked `~` give the line from the grep of `accessibilityState={{ selected` on 2026-10-15. The `onPress` sits two to eight lines above it; read the file to confirm.
- **`InflowKindPicker:49`.** Both callers pass `onPick={setPicked}` (`CountsAsSection.tsx:76`, `MoneyReviewScreen.tsx:117`, read 2026-10-15): a pick only sets local state. The save is a separate button ("Just this one" / "Every payment from …") whose outcome is Task 7's `run`. So one tick on the pick and one outcome on the button are two actions, not one.
- **`CategoryPickerModal`.** Read the component's props for the name of the currently selected value before writing the call (`grep -n "selected" mobile/src/components/CategoryPickerModal.tsx`). If it compares by id, use ids on both sides.

- [ ] **Step 1: Write the failing tests.** For each component with a test file, add one test that taps the already-selected option (expect `{}`), then a different option (expect `{ selection: 1 }`). The shape, for `LedgerControls`:

```tsx
it('ticks only when the type filter changes', () => {
  renderControls({ typeFilter: 'ALL' }); // the file's helper or a direct render with its required props
  fireEvent.press(screen.getByLabelText('Filter: all'));
  expectHaptics({});
  fireEvent.press(screen.getByLabelText('Filter: income'));
  expectHaptics({ selection: 1 });
});
```

  - Use the labels each component sets: the `accessibilityLabel` in the table rows' files, or the visible text where there is no label.
  - For `OptionPickerModal` and `CategoryPickerModal`, the existing tests that assert a selection on every tap change. The tap on the already-selected option now expects `{}` (Ruling 2), and a tap on another option expects `{ selection: 1 }`.
  - For a site with no test file of its own (for example `SpendingTrackingQuestionScreen`, if `ls` finds none), create `<Name>.test.tsx` beside it with a direct render inside `ThemeProvider` and the same two assertions.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components src/screens src/onboarding -t "ticks only|selection"`
Expected: FAIL on every new test; the two picker tests fail on their tap-the-selected-one assertion.

- [ ] **Step 3: Make the 21 edits** in the table. Import `hapticSelectionIfChanged` from `lib/haptics` in each file, and drop the `hapticSelection` import where it is no longer used.

- [ ] **Step 4: Run.**

Run: `jest src/components src/screens src/onboarding src/navigation`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add mobile/src
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "feat(mobile): segments, chips and pickers tick only when the choice changes"
```

---

## The outcome pattern (Tasks 6–10)

Every action in Tasks 6–10 has a `try { await <server call>; … } catch (e) { reportTransportFailure(e, '<tag>', …); … }` today. The `<tag>` is how each is named below; find it with `grep -n "'<tag>'" mobile/src -r`.

The change at each action is the same:
- `hapticSuccess();` goes on the line right after the awaited server call (inside `try`), before any navigation or `onSaved()`;
- `hapticError();` goes as the first line of that `catch`;
- for a mutation (`useMutation`), the two calls go in its `onSuccess` and `onError`.

The tests are the same shape for every action. In the action's test file, take the existing test that saves successfully and the existing test that fails (grep its `it(` names for the action). After each one's last `await`, add one assertion:
- the success test gets `expectHaptics({ success: 1 })`;
- the failure test gets `expectHaptics({ error: 1 })`.

Where the file has no failure test for the action, copy its success test and make the server call reject with `mockRejectedValueOnce(new Error('down'))`. Watch both fail before adding the calls.

The two assertions also prove that nothing fired on the tap or while the request was in flight: `expectHaptics` checks every kind.

### Task 6: Transactions and accounts

**Actions:**
- `add-transaction:save` (`screens/AddTransactionSheet.tsx:94-97`)
- `edit-transaction:save` (`screens/EditTransactionSheet.tsx:102-109`)
- the Account form save (`screens/AccountFormSheet.tsx:76-113`; find its tag with `grep -n reportTransportFailure mobile/src/screens/AccountFormSheet.tsx`)
- `accounts:delete` (`screens/AccountsScreen.tsx:86-91`)

**Tests:** `AddTransactionSheet.test.tsx`, `EditTransactionSheet.test.tsx`, `AccountFormSheet.test.tsx`, `AccountsScreen.test.tsx`.

- [ ] **Step 1: Write the failing tests:** the pair for each action as described above. Add Review Focus 3 in `AddTransactionSheet.test.tsx`:

```tsx
it('a double tap on Save adds once and gives one success', async () => {
  // arrange a valid form the way the file's success test does, then:
  const save = screen.getByText('Add Transaction'); // the file's Save label
  fireEvent.press(save);
  fireEvent.press(save);
  await act(async () => {});
  expect(transactionsApi.create).toHaveBeenCalledTimes(1);
  expectHaptics({ success: 1 });
});

it('nothing fires when haptics are off', async () => {
  __setHapticsStateForTests('off');
  // arrange and save the way the file's success test does
  expectHaptics({});
});
```

  Use the button label the file's success test presses.

- [ ] **Step 2: Run to see them fail.** Run: `jest src/screens/AddTransactionSheet.test.tsx src/screens/EditTransactionSheet.test.tsx src/screens/AccountFormSheet.test.tsx src/screens/AccountsScreen.test.tsx`. Expected: FAIL on the new assertions only.
- [ ] **Step 3: Add the calls** at the four actions.
- [ ] **Step 4: Run again.** Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(mobile): saving and deleting transactions and accounts is felt`. Add exactly the four screen files and their four test files.

---

### Task 7: Categories, money kinds, counts-as, bank correction

**Actions:**
- the Category edit save (`components/CategoryEditSheet.tsx:53-70`). Also `hapticWarning();` on the line before `return` after `setError('Enter a name for this category.')` (`:55`).
- `category-delete:confirm` (`components/CategoryDeleteSheet.tsx:58-70`).
- `CountsAsSection.run` (`components/CountsAsSection.tsx:25-39`): success after `await action()`, error in its `catch`.
- `InflowKindsSettings.attempt` (`components/InflowKindsSettings.tsx:28-43`): when `outcomeHaptic` is true, success after `await action()` and error first in `catch`. The switch passes `false` (Task 2). Rename, delete and forget pass the default.
- `InflowKindPicker.submit` (`components/InflowKindPicker.tsx:24-38`): success after `await onCreate(...)`, error in `catch`.
- `bank-correction:acknowledge` (`components/BankCorrectionModal.tsx:50-66`).

**Tests:** `CategoryEditSheet.test.tsx`, `CategoryDeleteSheet.test.tsx`, `CountsAsSection.test.tsx`, `InflowKindsSettings.test.tsx`, `InflowKindPicker.test.tsx`, `BankCorrectionModal.test.tsx`. Use `ls` to confirm each exists; create a direct-render test beside any that does not. Add one warning test to `CategoryEditSheet.test.tsx`: press save with an empty name, then `expectHaptics({ warning: 1 })`. If its Save button is disabled for an empty name, the handler is unreachable by press. In that case drop the warning call and this test, and ledger it under Ruling 4.

Add one test in `InflowKindsSettings.test.tsx`: toggling a kind's "counts as income" switch gives `{ selection: 1 }` on success and also `{ selection: 1 }` (no error) when `inflowApi.updateKind` rejects (Ruling 3).

- [ ] **Steps 1–5** as in Task 6.
  - Run: `jest src/components/CategoryEditSheet.test.tsx src/components/CategoryDeleteSheet.test.tsx src/components/CountsAsSection.test.tsx src/components/InflowKindsSettings.test.tsx src/components/InflowKindPicker.test.tsx src/components/BankCorrectionModal.test.tsx`.
  - Commit message: `feat(mobile): category and money-kind changes are felt`.

---

### Task 8: Goals, investments, profile and the two settings saves

**Actions:**
- `goals:create` (`screens/GoalsScreen.tsx:~95-110`). Also `hapticWarning();` before each of the three validation `return`s (`:77`, `:81`, `:88`).
- `goals:contribute` (`:~120-133`), the Amount prompt's outcome. `AmountPromptModal` disables Confirm while the amount is invalid, so no warning there (Ruling 4).
- `goals:delete` (`:~145-157`).
- `investments:add-holding` (`screens/InvestmentsScreen.tsx:~135-151`). Also warnings before the validation `return`s at `:125` and `:129`.
- `investments:remove-holding` (`:~165-175`).
- `investments:save-snapshot` (`:~185-194`).
- `profile:save-name` (`screens/ProfileScreen.tsx:73-85`). Also a warning before the `return` at `:63`.
- `settings-general:save-preferences` (`screens/SettingsGeneralScreen.tsx:74-90`). Also a warning before the `return` at `:71`.
- `settings-categorization:save-threshold` (`screens/SettingsCategorizationScreen.tsx:43-55`).

For each warning, add a test that presses the form's submit with the invalid input the message names, then `expectHaptics({ warning: 1 })` and `expect(<api>).not.toHaveBeenCalled()`.

Add Review Focus 3 to `GoalsScreen.test.tsx`: a double press on "Add Goal" with a valid form gives one `goalsApi.create` call and `{ success: 1 }`.

- [ ] **Steps 1–5** as in Task 6.
  - Run: `jest src/screens/GoalsScreen.test.tsx src/screens/InvestmentsScreen.test.tsx src/screens/ProfileScreen.test.tsx src/screens/SettingsGeneralScreen.test.tsx src/screens/SettingsCategorizationScreen.test.tsx`.
  - Commit message: `feat(mobile): goals, holdings, profile and settings saves are felt`.

---

### Task 9: Account, connections and billing

**Actions:**
- `change-email:start` (`screens/settings/ChangeEmailSheet.tsx:77-90`).
- `change-password:start`, `change-password:verify-otp` and `change-password:complete` (`screens/settings/ChangePasswordSheet.tsx:120-195`):
  - `hapticError()` first in each of the three `catch` blocks;
  - `hapticSuccess()` only after the `complete` call;
  - the earlier steps succeed silently, because each is a step toward one action;
  - the "session information is missing" stop at `:182` gets nothing, because it is not the person's form that is incomplete.
- `device-sessions:revoke` (`screens/settings/DeviceSessionsSection.tsx:~55-67`).
- `bank-sync:disconnect` (`screens/SettingsBankSyncScreen.tsx:~78-90`).
- `gmail-connection:disconnect` (`screens/settings/GmailConnectionSection.tsx:~130-145`).
- `export-data:submit` (`screens/settings/ExportDataSheet.tsx:~38-50`).
- `my-subscription:pause` and `my-subscription:resume` (`screens/MySubscriptionScreen.tsx:85-127`): success right after `await billingApi.pause()` / `resume()`, before the invalidations.

Add Review Focus 4 to `ChangeEmailSheet.test.tsx`: the request rejects once, the person presses again and it succeeds. Expect `{ error: 1 }` after the first press and `{ error: 1, success: 1 }` after the second.

- [ ] **Steps 1–5** as in Task 6.
  - Run: `jest src/screens/settings src/screens/SettingsBankSyncScreen.test.tsx src/screens/MySubscriptionScreen.test.tsx`.
  - Commit message: `feat(mobile): account, connection and subscription changes are felt`.

---

### Task 10: Support, referrals, statements, and the optimistic dismiss

**Actions:**
- `feedback:save` (`screens/support/FeedbackSheet.tsx:63-76`).
- `new-ticket:save` (`screens/support/NewTicketSheet.tsx:65-80`).
- `ReferralCodePrompt`'s `apply` mutation (`components/ReferralCodePrompt.tsx:42-50`): `hapticSuccess()` in `onSuccess`, `hapticError()` in `onError`.
- `FriendCodeCard`'s apply mutation (`screens/ReferralsScreen.tsx:~261-270`), the same way.
- the redeem mutation (`screens/ReferralsScreen.tsx:~323-332`), the same way.
- `handleCopy` (`screens/ReferralsScreen.tsx:359-363`): `hapticSuccess()` after `await Clipboard.setStringAsync(...)` resolves. The copy worked; if it rejects, nothing fires, as today nothing is shown.
- `statement-history:delete` and `statement-history:dismiss-import` (`screens/StatementHistoryScreen.tsx:~170-180`, `~285-293`).
- `dismissRecurring` on Home (`screens/DashboardScreen.tsx:191-203`) and Insights (`screens/InsightsScreen.tsx:278-290`):
  - add `onSuccess: () => hapticSuccess()`;
  - put `hapticError();` first in the existing `onError`, before the rollback;
  - `onMutate` gets nothing: the row leaving the screen is not the outcome.

Tests for the optimistic dismiss, in `DashboardScreen.test.tsx` and `InsightsScreen.test.tsx`:

```tsx
it('dismissing a recurring merchant: nothing as the row leaves, one success when the server confirms', async () => {
  let resolve!: () => void;
  jest.mocked(recurringApi.dismiss).mockReturnValueOnce(new Promise<void>((r) => { resolve = r; }));
  // render and press the row's dismiss the way the file's existing dismiss test does
  expectHaptics({});
  await act(async () => resolve());
  expectHaptics({ success: 1 });
});

it('a dismiss the server refuses: one error, and the row comes back', async () => {
  jest.mocked(recurringApi.dismiss).mockRejectedValueOnce(new Error('down'));
  // render and press dismiss as above
  await act(async () => {});
  expectHaptics({ error: 1 });
  // and the existing rollback assertion: the merchant row is on screen again
});
```

- [ ] **Steps 1–5** as in Task 6.
  - Run: `jest src/screens/support src/components/ReferralCodePrompt.test.tsx src/screens/ReferralsScreen.test.tsx src/screens/StatementHistoryScreen.test.tsx src/screens/DashboardScreen.test.tsx src/screens/InsightsScreen.test.tsx`.
  - Commit message: `feat(mobile): support, referral, statement and dismiss outcomes are felt`.

---

### Task 11: Every row of the table has its test; the whole check

- [ ] **Step 1: Trace the table to the tests.** Write the mapping into the spec's build record (Task 12), one line per row of the haptics table, each naming the test(s) that pin it. Any row without a test gets one now, test-first. The rows:
  - tabs and "+";
  - the already-open tab;
  - opening a screen (none);
  - segments, chips and pickers;
  - switches;
  - pull to refresh;
  - long press;
  - save, add, import or delete;
  - refused or failed;
  - blocked send;
  - referral copy;
  - cancel (none).

  For "opening a screen: none" and "cancel: none", add to `AccountsScreen.test.tsx`:
  - pressing "Edit HDFC Savings" (opens the sheet) gives `{}`;
  - pressing the sheet's Cancel gives `{}`.

  The long press is pinned by the existing `LedgerScreen` test that asserts `hapticImpact` on a long press. Check it with `grep -n "impact" mobile/src/screens/LedgerScreen.test.tsx`; if it only asserts that the call happened, change it to `expectHaptics({ impact: 1 })`.

- [ ] **Step 2: The whole mobile check under Node 22**, with `/Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile` as the working directory:

```bash
NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile --forceExit
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile/node_modules/.bin/tsc --noEmit -p /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile/node_modules/.bin/expo lint --max-warnings 0
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics/mobile/node_modules/.bin/expo export --platform android --output-dir /private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad/android-export-haptics
```
  Expected: all green. Then the repository guards the CI workflow runs for mobile (read `.github/workflows/ci.yml` for the list).

- [ ] **Step 3: The sensitive flows.** Run their suites by name and confirm that only the haptic assertions this plan added changed in them: `git -C … diff origin/main -- <test file>` should show additions only.

  Run: `jest src/screens/AddTransactionSheet.test.tsx src/screens/EditTransactionSheet.test.tsx src/screens/import src/screens/ReportsScreen.test.tsx src/screens/AdvancedReportsScreen.test.tsx src/screens/DashboardScreen.test.tsx`

---

### Task 12: Simulator pass, the real-phone checklist, the build record, the pull request

- [ ] **Step 1: Simulator pass (haptics cannot be felt there).** Run the dev client against this worktree's Metro, signed in to the owner's account, read only. Watch the Metro log for `[haptics]` lines. Drive only rows that do not change data:
  - tabs and "+" (open and close the sheet without saving);
  - the Transactions type filter;
  - the Insights tabs;
  - the Theme segment;
  - pull to refresh on Home;
  - the Haptics switch off, then the same rows again, expecting no lines.

  Record each row with the log lines seen. Saves, deletes and failures are covered by the tests, not driven on production.

- [ ] **Step 1b: Real iPhone pass, done by me.** This needs the owner to connect the iPhone by USB and sign in; ask when this step is reached, and do not block the pull request on it.
  - Install the dev-client build of this branch on the phone.
  - Drive the same read-only rows as Step 1, with the app's switch on and then off.
  - Read the `[haptics]` lines from Metro to confirm each call fired, or did not, with the right kind and count.
  - Nothing that changes data is driven on the phone either: it is the production account.

  Android is the owner's, after merge (spec, Platforms).

- [ ] **Step 2: Write the real-phone checklist** into the spec under "Build record (haptics)" → "Owner's checklist". About ten actions per phone, each with what should be felt, plus the phone-setting checks. The iPhone section:
  - tap a tab;
  - tap "+";
  - change the Transactions type filter, then tap the chosen one again (nothing);
  - pull to refresh on Home;
  - long-press a transaction;
  - change Theme;
  - turn on a notification switch;
  - add a goal with no name (warning);
  - turn the app's Haptics off and repeat the first three (nothing);
  - with the app's switch on, turn the phone's own setting (Settings › Sounds & Haptics › System Haptics) off and repeat the first three. Record what is felt; the spec expects nothing.

  The Android section has the same ten, and the phone-setting step uses Android's touch-feedback setting. Note the source facts from this plan: every kind is a short buzz, and the touch-feedback setting may not silence it. The owner records what each phone did.

- [ ] **Step 3: The build record** in the spec:
  - the table-to-test map (Task 11);
  - the simulator rows and log lines;
  - the Android source facts;
  - what was not checked: feel, and the phones' own settings, both pending the owner's checklist on a build after merge.

  Commit:

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics add docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-haptics commit -m "docs(mobile): haptics build record and the owner's checklist"
```

- [ ] **Step 4: Self-review the diff, push, open the pull request.**
  - Read the whole diff against `origin/main` for any haptic not traceable to a table row, or any action that fires twice. Fix test-first, then re-run Task 11 Step 2.
  - Fetch `origin`; confirm `git log --oneline origin/main..HEAD` holds only this branch's commits.
  - Push `feature/mobile-haptics` and open the pull request (public repository, no need to ask). The body:
    - summarises the switch, the shared pieces, the action list and its exclusions, and the Android facts;
    - states it ships in 1.1.0 and must not be sent over the air to 1.0.0;
    - links the owner's checklist;
    - ends with the Claude Code line.
  - Bind it with the ccd_pr tools.

- [ ] **Step 5: After both merge,** run the whole check of Task 11 Step 2 once more on `main` (a fresh worktree off `origin/main`) before the 1.1.0 store build (spec, "Whole mobile check").
