# Mobile skeletons Implementation Plan (pull request 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **On this repository the owner has ruled out agents: execute inline with superpowers:executing-plans.**

**Goal:** Every first load in the mobile app shows a placeholder shaped like the content it is waiting for, and every first load that fails says so and offers "Try again".

**Architecture:** One token file (`theme/skeleton.ts`) holds every number a placeholder draws with. `Skeletons.tsx` gains small blocks built only from those tokens and a `SkeletonGroup` that reads "Loading" once to a screen reader. `ScreenSkeletons.tsx` composes one skeleton per converted screen from its real layout. Each screen's loading branch keeps its own `GlassScreen` and `ScrollView` and puts the skeleton inside, so padding and insets are identical when the content lands. A shared `LoadFailed` gives the failure states one look and one "Try again". Two source scans stop regressions: no raw sizes or colours in `components/skeletons/`, and no `ActivityIndicator` outside a named list of action spinners.

**Tech Stack:** Expo SDK 57, React Native 0.86 (Fabric), TypeScript 6, Jest 30, React Native Testing Library 13, TanStack Query v5.

**Spec:** `docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md` (Part 1, Testing and checks, Completion criteria, Shipping).

## Global Constraints

- Worktree `/Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics`, branch `feature/mobile-skeletons-haptics`. Never write in `/Users/sid/Downloads/finora`. Absolute paths in every command.
- Bash: one plain command per call; no `for` loops with variables, no `$(...)` inside `git -C`. Scripts go in the session scratchpad.
- Checks run under Node 22, as CI does (local Node is 26; a suite once passed locally and failed CI). Every `Run: jest <paths>` in this plan means exactly:
  `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile <paths>`
  with `<paths>` relative to `mobile/`.
- Before Task 1, install the mobile dependencies once: `npm ci` with `/Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile` as the working directory (root `npm ci` is already done, for the commit hook).
- Commit messages: lowercase subject after the type (`feat(mobile): ...`), never sentence case, and **never** a `Co-Authored-By` or any AI-attribution line.
- Tests are written before the code they cover, and each new test is watched failing before the code is written.
- A skeleton shows only while React Query's `isLoading` is true (or the section's own equivalent "nothing loaded yet" flag). Never on `isFetching`.
- "A skeleton means your content is coming; a spinner means your action is running." The spinners in the keep list (Task 17) stay.
- Placeholders use the existing look only: the shared `Shimmer` pulse, the `border` fill, the theme's radii and spacing, `Card`/`DashboardCard` wrappers. No new palette, animation or library.
- Each placeholder group is one element labelled "Loading"; its pieces are hidden from screen readers; there is no live-region announcement.
- No artificial minimum display time.
- Existing failure wording stays, except where this plan says otherwise (Statement History's "Pull back and try again" on a screen with no pull, and Notification preferences' "Try reopening Settings"; see Rulings).
- Nothing here changes query keys, cache behaviour, refetch triggers, paging, saves, empty states, sign-in or navigation.
- The simulator runs the owner's production account read only: never press delete, save, confirm, send or import; never enter credentials; no screenshots of real data in the public pull request.
- Ships in store build 1.1.0; never sent over the air to the 1.0.0 runtime.

## Rulings made while writing this plan (the spec is corrected in Task 0)

1. **Eight screens lack a retry, not seven.** `ProfileScreen.tsx:99` returns its failure message before the `ScrollView` that carries pull to refresh, so it has no button and no pull. Profile joins Accounts, Year in Review, Ticket Detail and Settings General, Security, Categorization and Account.
2. **"Only the first state changes" is not true for sections.** Read on 2026-10-15:
   - Advanced Reports' six sections have no failure state at all: a failed fetch falls to `data ?? []` and shows the "nothing yet" message.
   - The statement-detail pop-up (`StatementHistoryScreen.tsx:579`) shows "This import's transactions are no longer on the books" when the fetch fails.
   - Mark as Transfer (`MarkTransferModal.tsx:121`) shows "No matching transactions found" when the search fails.
   - Statement History's list says "Pull back and try again", but the screen has no pull to refresh.
   - Bank Sync links, device sessions, Gmail connection, the "counts as" section and notification preferences show a failure with no way to retry.
   - The source, explanation and Bank Correction pop-ups show a failure with no retry button.

   The spec's own data-states table requires "an error with a way to try again" for every converted section, so each of these gets a `LoadFailed` with "Try again" (Tasks 13–16).
3. **21 section sites, not 16.** The spec's heading says 16, but its list holds 16 sections plus 5 pop-ups. All 21 are converted.
4. **Existing call sites get groups.** The spec drops the live-region "Loading" announcement that `Shimmer` makes today. The eight screens that already use skeletons would then give a screen reader nothing at all, so their placeholders are wrapped in `SkeletonGroup` (Task 4).
5. **The spinner scan covers all of `src/`,** not only `screens/` and `components/`, so `navigation/RootNavigator.tsx` is on the keep list by name rather than outside the scan.

## Review Focus

1. **A screen closed mid-load.** A person backs out while the skeleton is up. The shared pulse must stop, and no state update may land on the unmounted screen. Pinned in Task 1 (`__activeShimmerCountForTests` after unmount) and in each screen task's unmount test.
2. **Offline, nothing cached.** `isLoading` is false for a query paused offline, so the screen leaves the skeleton; it must show its failure, not an empty state and not a blank. Pinned for Investments and Journey (they use `isPausedCold`) in Tasks 6 and 7.
3. **"Try again" while the retry is in flight.** After the tap, the query is fetching again with no data, so `isLoading` is true again and the skeleton must come back (not a stuck failure message, not a spinner). Pinned in every retry test: each one asserts the skeleton on the second attempt before resolving it.
4. **Large text.** Placeholders are sized to font size, so at the largest accessibility size the real text is taller than its placeholder. The skeleton still has to read as the screen's shape without overlap. Checked on the simulator in Task 18 at the largest size; no automated test can see it.
5. **Dark theme.** The `border` fill against glass in dark mode must be visible. Measured and recorded in Task 18 (both themes, against the card and the backdrop).

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `mobile/src/theme/skeleton.ts` | create | `skeletonTokens`: every number and the fill key a placeholder uses |
| `mobile/src/components/skeletons/Shimmer.tsx` | modify | read tokens; drop the announcement; expose the active count for tests |
| `mobile/src/components/skeletons/Skeletons.tsx` | modify | existing blocks on tokens; new `SkeletonGroup`, `SkeletonText`, `SkeletonBox`, `SkeletonIcon`, `SkeletonLines`, `SkeletonListRow`, `SkeletonFormField`, `SkeletonProfileHeader`, `SkeletonSettingsRow`, `SkeletonBarRow`, `SkeletonHero` |
| `mobile/src/components/skeletons/ScreenSkeletons.tsx` | create | one skeleton per converted whole screen (13) |
| `mobile/src/components/skeletons/skeletonTokens.guard.test.ts` | create | scan: no colour, radius, height or width literal in `components/skeletons/` |
| `mobile/src/components/LoadFailed.tsx` | create | failure message plus "Try again" |
| `mobile/src/test/loadingStates.ts` | create | `never()` and `expectSkeletonNotSpinner()` test helpers |
| `mobile/src/components/skeletons/actionSpinners.guard.test.ts` | create | scan: `ActivityIndicator` only in the named keep list |
| 13 whole-screen files, 21 section sites, 8 existing call-site screens | modify | as each task says |

---

### Task 0: Correct the spec against the code

**Files:**
- Modify: `docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md`

- [ ] **Step 1: Edit the spec.**
  - In "Data states", change "Seven of them give no way to try again: no button and no pull to refresh (Accounts, Year in Review, Ticket Detail, Settings › General, Security, Categorization, Account)" to "Eight of them give no way to try again: no button and no pull to refresh (Accounts, Year in Review, Ticket Detail, Profile, Settings › General, Security, Categorization, Account)".
  - Under that paragraph, add a new paragraph:

    > Sections are not all as safe (checked 2026-10-15 while planning). Advanced Reports' six sections, the statement-detail pop-up and Mark as Transfer show their "nothing here" message when the fetch fails. Statement History's list asks for a pull to refresh that the screen does not have. Bank Sync links, device sessions, Gmail connection, "counts as", notification preferences, and the source, explanation and Bank Correction pop-ups show a failure with no way to retry. Each gets the fourth state properly: its failure message and "Try again".
  - Change the heading "Section spinners become section skeletons (16)" to "Section spinners become section skeletons (16 sections and 5 pop-ups)".

- [ ] **Step 2: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "docs(mobile): profile and eleven sections also lack a retry"
```

---

### Task 1: Skeleton tokens, the token scan, and a quieter Shimmer

**Files:**
- Create: `mobile/src/theme/skeleton.ts`
- Create: `mobile/src/components/skeletons/skeletonTokens.guard.test.ts`
- Modify: `mobile/src/components/skeletons/Shimmer.tsx`
- Modify: `mobile/src/components/skeletons/Shimmer.test.tsx`

**Interfaces:**
- Produces: `skeletonTokens` (shape below); types `SkeletonTextSize = keyof typeof skeletonTokens.text`, `SkeletonControlSize = keyof typeof skeletonTokens.control`, `SkeletonIconSize = keyof typeof skeletonTokens.icon`; `Shimmer` with `height: DimensionValue`; `__activeShimmerCountForTests(): number`.

- [ ] **Step 1: Write the failing tests.** Replace the `describe('loading announcement', ...)` block and the `it('still announces loading, since that is not motion', ...)` test in `Shimmer.test.tsx` with:

```tsx
  // The spec drops the live-region announcement: a SkeletonGroup carries a static "Loading" label
  // instead, so a screen reader meets it once per group when it reaches it, not as an interruption.
  describe('screen readers', () => {
    it('never announces on its own', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
      renderShimmer();
      expect(announce).not.toHaveBeenCalled();
    });
  });

  describe('shared pulse', () => {
    it('counts every mounted Shimmer and returns to zero when the last one unmounts', () => {
      const view = render(
        <ThemeProvider>
          <Shimmer height={20} />
          <Shimmer height={20} />
        </ThemeProvider>
      );
      expect(__activeShimmerCountForTests()).toBe(2);
      view.unmount();
      expect(__activeShimmerCountForTests()).toBe(0);
    });

    it('starts one loop however many Shimmers mount', async () => {
      jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
      const loop = jest.spyOn(Animated, 'loop');
      render(
        <ThemeProvider>
          <Shimmer height={20} />
          <Shimmer height={20} />
          <Shimmer height={20} />
        </ThemeProvider>
      );
      await act(async () => {});
      expect(loop).toHaveBeenCalledTimes(1);
    });
  });
```

Change the import line to `import { Shimmer, __activeShimmerCountForTests } from './Shimmer';`.

Create `mobile/src/components/skeletons/skeletonTokens.guard.test.ts`:

```ts
import { readdirSync, readFileSync } from 'fs';
import { join } from 'path';

/**
 * Every number a placeholder draws with lives in theme/skeleton.ts (skeletonTokens), drawn from
 * the existing theme. A file in this folder that writes its own colour, corner radius, height or
 * width would drift from the screens it stands in for without anyone noticing. Percent widths are
 * strings and stay allowed: they describe a text line's share of a row, not a size.
 */
const DIR = __dirname;
const files = readdirSync(DIR).filter((f) => /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f));

const BANNED: { what: string; re: RegExp }[] = [
  { what: 'a colour literal', re: /#[0-9a-fA-F]{3,8}\b|\brgba?\(/ },
  { what: 'a numeric corner radius', re: /borderRadius(=\{|:)\s*\d/ },
  { what: 'a numeric height', re: /\bheight(=\{|:)\s*\d/ },
  { what: 'a numeric width', re: /\bwidth(=\{|:)\s*\d/ },
];

it('the scan sees the skeleton files', () => {
  expect(files).toEqual(expect.arrayContaining(['Shimmer.tsx', 'Skeletons.tsx']));
});

it('no skeleton file writes its own colour, radius, height or width', () => {
  const hits = files.flatMap((f) =>
    readFileSync(join(DIR, f), 'utf8').split('\n').flatMap((line, i) =>
      BANNED.filter(({ re }) => re.test(line)).map(({ what }) => `${f}:${i + 1}: ${what}: ${line.trim()}`)
    )
  );
  expect(hits).toEqual([]);
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components/skeletons` (from `/Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile`)
Expected: FAIL. `never announces` fails (announce called once), `__activeShimmerCountForTests` is not exported, and the scan lists hits in `Skeletons.tsx` (`height={15}`, `width={60}`, `borderRadius={9}` and others) and `Shimmer.tsx`.

- [ ] **Step 3: Create the tokens.** `mobile/src/theme/skeleton.ts`:

```ts
import { CASHFLOW_HEIGHT, DONUT_SIZE, TREND_HEIGHT } from '../lib/chartGeometry';
import { radius, type Palette } from './palette';

/**
 * Every value a loading placeholder draws with, named once and taken from the existing theme
 * rather than invented. Shimmer and every block in components/skeletons/ read from here; a source
 * scan (skeletonTokens.guard.test.ts) fails if one of them writes its own colour, radius, height
 * or width instead.
 *
 * Text placeholders are as tall as the font size of the text they stand in for (the convention the
 * first skeletons already used), so `text` mirrors the font sizes the converted screens use.
 * Controls copy the minimum heights of the real components: TextField's input row and Button are
 * 48, the touch-target floor is 44, iOS's Switch is 51 x 31.
 */
export const skeletonTokens = {
  /** Palette key of the placeholder fill. */
  fill: 'border' as const satisfies keyof Palette,
  /** The shared pulse: opacity sweeps low -> high -> low, each half taking halfCycleMs. */
  pulse: { low: 0.35, high: 1, halfCycleMs: 700, reducedMotion: 0.7 },
  radius: { line: radius.md, block: radius.md, card: radius.lg },
  text: {
    tiny: 10, caption: 11, meta: 12, label: 13, body: 14, heading: 15, subheading: 16,
    subject: 17, amount: 18, value: 20, title: 22, hero: 28, display: 36,
  },
  control: {
    field: 48, button: 48, touch: 44, codeRow: 52,
    switchHeight: 31, chip: 18, badge: 20, bar: 8, barTrack: 6,
  },
  /** Widths that are fixed in the real layout, not a share of the row. */
  fixedWidth: { amount: 60, switch: 51, stepperValue: 64 },
  icon: { sm: 32, md: 36, lg: 44, avatar: 52 },
  chart: { cashflow: CASHFLOW_HEIGHT, trend: TREND_HEIGHT, donut: DONUT_SIZE },
} as const;

export type SkeletonTextSize = keyof typeof skeletonTokens.text;
export type SkeletonControlSize = keyof typeof skeletonTokens.control;
export type SkeletonIconSize = keyof typeof skeletonTokens.icon;
```

- [ ] **Step 4: Make Shimmer read the tokens, drop the announcement, expose the count.** In `Shimmer.tsx`:
  - Import `import { skeletonTokens as T } from '../../theme/skeleton';` and drop the `radius` import.
  - Change `height: number;` in `ShimmerProps` to `height: DimensionValue;`.
  - Replace `new Animated.Value(0.35)` with `new Animated.Value(T.pulse.low)`.
  - Delete the `REDUCED_MOTION_OPACITY` constant and its comment. In its place, write `sharedPulse.setValue(T.pulse.reducedMotion)` where it was used, with the comment moved above that line.
  - In `startPulse`, use `T.pulse.high` and `T.pulse.halfCycleMs` for the first timing, and `T.pulse.low` and `T.pulse.halfCycleMs` for the second.
  - In `acquireSharedLoop`, replace `sharedPulse.setValue(0.35)` with `sharedPulse.setValue(T.pulse.low)`. Delete the `AccessibilityInfo.announceForAccessibility('Loading');` call and its comment block, replacing them with:

    ```ts
    // No announcement here: each placeholder group (SkeletonGroup) is one element labelled
    // "Loading", which a screen reader reads when it reaches it -- the spec rules out a live-region
    // interruption every time something starts loading.
    ```
  - Default `borderRadius = T.radius.line` in the `Shimmer` signature, and `backgroundColor: c[T.fill]` in the style.
  - Add after `releaseSharedLoop`:

    ```ts
    /** Test-only: how many Shimmers hold the shared pulse right now. */
    export function __activeShimmerCountForTests(): number {
      return activeShimmerCount;
    }
    ```

- [ ] **Step 5: Run the Shimmer tests.**

Run: `jest src/components/skeletons/Shimmer.test.tsx`
Expected: PASS. The token scan still fails on `Skeletons.tsx`; Task 2 clears it.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/theme/skeleton.ts mobile/src/components/skeletons/Shimmer.tsx mobile/src/components/skeletons/Shimmer.test.tsx mobile/src/components/skeletons/skeletonTokens.guard.test.ts
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): skeleton tokens, and the pulse stops announcing itself"
```

---

### Task 2: The building blocks

**Files:**
- Modify: `mobile/src/components/skeletons/Skeletons.tsx`
- Modify: `mobile/src/components/skeletons/Skeletons.test.tsx`

**Interfaces:**
- Consumes: `skeletonTokens`, `SkeletonTextSize`, `SkeletonControlSize`, `SkeletonIconSize`, `Shimmer` (Task 1).
- Produces (all exported from `Skeletons.tsx`):
  - `SkeletonGroup({ children, style?, testID? = 'skeleton-group' })`
  - `SkeletonText({ size: SkeletonTextSize, width?: DimensionValue = '100%', style? })`
  - `SkeletonBox({ size: SkeletonControlSize, width?: DimensionValue = '100%', round?: boolean, style? })`
  - `SkeletonIcon({ size: SkeletonIconSize, round?: boolean, style? })`
  - `SkeletonLines({ count = 3, size: SkeletonTextSize = 'meta', lastWidth: DimensionValue = '60%' })`
  - `SkeletonListRow({ icon?: SkeletonIconSize | null = null, roundIcon?: boolean, amount = true, divider = true })`
  - `SkeletonFormField()`
  - `SkeletonProfileHeader()`
  - `SkeletonSettingsRow({ trailing: 'switch' | 'chevron' | 'badge' | 'none' = 'none', divider = true })`
  - `SkeletonBarRow()`
  - `SkeletonHero({ aspectRatio: number })`
  - The existing `SkeletonCard`, `SkeletonTransactionRow`, `SkeletonKpiGrid`, `SkeletonBudgetCard` and `SkeletonChart`, unchanged in shape.

- [ ] **Step 1: Write the failing tests.** Append to `Skeletons.test.tsx` (extend the import from `./Skeletons` with the new names):

```tsx
describe('SkeletonGroup', () => {
  it('is one element a screen reader reads as "Loading", with its pieces hidden', () => {
    withTheme(
      <SkeletonGroup>
        <SkeletonLines count={2} />
      </SkeletonGroup>
    );
    expect(screen.getAllByLabelText('Loading')).toHaveLength(1);
    // The pieces exist but only as hidden placeholders.
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
    expect(screen.queryAllByTestId('shimmer-block')).toHaveLength(0);
  });
});

describe('sized blocks', () => {
  function heightOf(testID = 'shimmer-block') {
    const style = screen.getByTestId(testID, { hidden: true }).props.style;
    return (Array.isArray(style) ? Object.assign({}, ...style.flat(Infinity).filter(Boolean)) : style).height;
  }

  it('a text placeholder is as tall as the font size it stands in for', () => {
    withTheme(<SkeletonText size="title" />);
    expect(heightOf()).toBe(22);
  });

  it('a field placeholder is as tall as a TextField input row', () => {
    withTheme(<SkeletonBox size="field" />);
    expect(heightOf()).toBe(48);
  });
});

describe('row and form blocks', () => {
  it('SkeletonListRow: icon, two lines and an amount', () => {
    withTheme(<SkeletonListRow icon="md" />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonListRow without an icon or amount is just the two lines', () => {
    withTheme(<SkeletonListRow amount={false} />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
  });

  it('SkeletonFormField: a label over a field', () => {
    withTheme(<SkeletonFormField />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
  });

  it('SkeletonProfileHeader: a circle and three lines', () => {
    withTheme(<SkeletonProfileHeader />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonSettingsRow with a switch: two lines and the switch', () => {
    withTheme(<SkeletonSettingsRow trailing="switch" />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });

  it('SkeletonLines draws the requested number of lines', () => {
    withTheme(<SkeletonLines count={4} />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonBarRow: a label, a value and the bar track', () => {
    withTheme(<SkeletonBarRow />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components/skeletons/Skeletons.test.tsx`
Expected: FAIL: `SkeletonGroup` (and the other new names) are not exported.

- [ ] **Step 3: Write the blocks.** Replace `Skeletons.tsx` with the following. The five existing blocks keep their shape; only their literals move to tokens.

```tsx
import type { ReactNode } from 'react';
import { StyleSheet, View, type DimensionValue, type ViewStyle } from 'react-native';
import { spacing, useTheme } from '../../theme';
import {
  skeletonTokens as T, type SkeletonControlSize, type SkeletonIconSize, type SkeletonTextSize,
} from '../../theme/skeleton';
import { Card } from '../Card';
import { DashboardCard } from '../dashboard/DashboardCard';
import { Shimmer } from './Shimmer';

/**
 * First-load placeholders only. Every call site gates these behind `isLoading` (React Query v5: true
 * only when there is no cached data for that key yet), never `isFetching` -- so a background refetch
 * never swaps rendered data back out for one of these.
 *
 * Every size comes from skeletonTokens (theme/skeleton.ts); skeletonTokens.guard.test.ts fails if a
 * file in this folder writes its own. Card-shaped blocks compose the real Card so a change to Card's
 * look cannot drift them out of sync.
 */

/**
 * One loading region, read once by a screen reader as "Loading". The Shimmers inside are already
 * hidden from assistive technology; grouping them gives VoiceOver and TalkBack a single element to
 * land on instead of nothing at all. Wrap every cluster of placeholders that stands in for one
 * region of a screen in exactly one of these.
 */
export function SkeletonGroup({ children, style, testID = 'skeleton-group' }: {
  children: ReactNode;
  style?: ViewStyle | ViewStyle[];
  testID?: string;
}) {
  return (
    <View testID={testID} style={style} accessible accessibilityLabel="Loading">
      {children}
    </View>
  );
}

/** A line of text: as tall as the font size of the text it stands in for. */
export function SkeletonText({ size, width = '100%', style }: {
  size: SkeletonTextSize;
  width?: DimensionValue;
  style?: ViewStyle;
}) {
  return <Shimmer width={width} height={T.text[size]} style={style} />;
}

/** A fixed-height control: a field, a button, a chip, a progress bar. */
export function SkeletonBox({ size, width = '100%', round = false, style }: {
  size: SkeletonControlSize;
  width?: DimensionValue;
  round?: boolean;
  style?: ViewStyle;
}) {
  const height = T.control[size];
  return <Shimmer width={width} height={height} borderRadius={round ? height / 2 : T.radius.block} style={style} />;
}

/** An icon, badge or avatar: square with the block radius, or round. */
export function SkeletonIcon({ size, round = false, style }: {
  size: SkeletonIconSize;
  round?: boolean;
  style?: ViewStyle;
}) {
  const side = T.icon[size];
  return <Shimmer width={side} height={side} borderRadius={round ? side / 2 : T.radius.block} style={style} />;
}

/** A few lines of text, the last one shorter, the way a paragraph ends. */
export function SkeletonLines({ count = 3, size = 'meta', lastWidth = '60%' }: {
  count?: number;
  size?: SkeletonTextSize;
  lastWidth?: DimensionValue;
}) {
  return (
    <View style={styles.lines}>
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonText key={i} size={size} width={i === count - 1 ? lastWidth : '100%'} />
      ))}
    </View>
  );
}

/** An optional icon, a title line over a caption line, an optional amount on the right. For
 *  accounts, holdings, recurring payments, tickets, statements and candidates. */
export function SkeletonListRow({ icon = null, roundIcon = false, amount = true, divider = true }: {
  icon?: SkeletonIconSize | null;
  roundIcon?: boolean;
  amount?: boolean;
  divider?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={[styles.listRow, divider && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
      {icon ? <SkeletonIcon size={icon} round={roundIcon} /> : null}
      <View style={styles.listRowMain}>
        <SkeletonText size="heading" width="65%" />
        <SkeletonText size="caption" width="40%" />
      </View>
      {amount ? <Shimmer width={T.fixedWidth.amount} height={T.text.body} /> : null}
    </View>
  );
}

/** TextField's shape: a label line over the 48pt input row. */
export function SkeletonFormField() {
  return (
    <View style={styles.formField}>
      <SkeletonText size="meta" width="30%" />
      <SkeletonBox size="field" />
    </View>
  );
}

/** Profile's identity card: the avatar circle, then name, email and a meta line. */
export function SkeletonProfileHeader() {
  return (
    <Card style={styles.profileHeader}>
      <SkeletonIcon size="avatar" round />
      <View style={styles.profileHeaderText}>
        <SkeletonText size="subject" width="55%" />
        <SkeletonText size="label" width="70%" />
        <SkeletonText size="caption" width="45%" />
      </View>
    </Card>
  );
}

/** A Settings row: title and meta lines, and the control at its end. */
export function SkeletonSettingsRow({ trailing = 'none', divider = true }: {
  trailing?: 'switch' | 'chevron' | 'badge' | 'none';
  divider?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={[styles.settingsRow, divider && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
      <View style={styles.settingsRowMain}>
        <SkeletonText size="body" width="45%" />
        <SkeletonText size="meta" width="70%" />
      </View>
      {trailing === 'switch' ? (
        <Shimmer width={T.fixedWidth.switch} height={T.control.switchHeight} borderRadius={T.control.switchHeight / 2} />
      ) : trailing === 'chevron' ? (
        <SkeletonText size="value" width="5%" />
      ) : trailing === 'badge' ? (
        <SkeletonBox size="badge" width="20%" round />
      ) : null}
    </View>
  );
}

/** HorizontalBarList's row: label and value on one line, the bar track under them. */
export function SkeletonBarRow() {
  return (
    <View style={styles.barRow}>
      <View style={styles.barRowTop}>
        <SkeletonText size="body" width="50%" />
        <SkeletonText size="meta" width="15%" />
      </View>
      <SkeletonBox size="barTrack" round />
    </View>
  );
}

/** A full-width illustration with a known aspect ratio (Refer & Earn's hero). */
export function SkeletonHero({ aspectRatio }: { aspectRatio: number }) {
  return (
    <View style={[styles.hero, { aspectRatio }]}>
      <Shimmer width="100%" height="100%" borderRadius={T.radius.card} />
    </View>
  );
}

export function SkeletonCard({ lines = 3, style }: { lines?: number; style?: ViewStyle }) {
  return (
    <Card style={style}>
      {/* heading: SectionHeading's real title is 15 (Card.tsx), the size it stands in for. */}
      <SkeletonText size="heading" width="40%" style={styles.heading} />
      {Array.from({ length: lines }).map((_, i) => (
        <SkeletonText key={i} size="meta" width={i === lines - 1 ? '60%' : '100%'} style={styles.line} />
      ))}
    </Card>
  );
}

/** Mirrors DashboardScreen's and LedgerScreen's own txnRow layout: description + meta on the
 *  left, an amount on the right. */
export function SkeletonTransactionRow() {
  const c = useTheme();
  return (
    <View style={[styles.txnRow, { borderBottomColor: c.border }]} testID="skeleton-transaction-row">
      <View style={styles.txnMain}>
        <SkeletonText size="body" width="70%" style={styles.line} />
        <SkeletonText size="caption" width="40%" />
      </View>
      <Shimmer width={T.fixedWidth.amount} height={T.text.body} />
    </View>
  );
}

/** Stands in for LedgerSnapshotCard's four tiles (same two-up basis, same compact glass tile), so
 *  the grid does not jump when the figures land: label, number and chip placeholders per tile. */
export function SkeletonKpiGrid() {
  return (
    <View style={styles.kpiGrid} testID="skeleton-kpi-grid">
      {Array.from({ length: 4 }).map((_, i) => (
        <DashboardCard key={i} padding="compact" style={styles.kpiTile}>
          <SkeletonText size="caption" width="50%" style={styles.line} />
          <SkeletonText size="title" width="80%" style={styles.line} />
          <SkeletonBox size="chip" width="40%" round />
        </DashboardCard>
      ))}
    </View>
  );
}

/** Mirrors BudgetsScreen's budget card: a header placeholder standing in for category name +
 *  amounts, a progress-bar-shaped placeholder, and a one-line footer. */
export function SkeletonBudgetCard() {
  return (
    <Card testID="skeleton-budget-card">
      <SkeletonText size="body" width="60%" style={styles.heading} />
      <SkeletonBox size="bar" round style={styles.line} />
      <SkeletonText size="caption" width="35%" style={styles.line} />
    </Card>
  );
}

/** Matches CashFlowChart's fixed height and DonutChart's fixed diameter -- lib/chartGeometry.ts,
 *  through skeletonTokens.chart -- so the skeleton never drifts from the chart it stands in for. */
export function SkeletonChart({ variant = 'bar', width = '100%' }: { variant?: 'bar' | 'donut'; width?: DimensionValue }) {
  if (variant === 'donut') {
    return (
      <View style={styles.donutWrap} testID="skeleton-chart-donut">
        <Shimmer width={T.chart.donut} height={T.chart.donut} borderRadius={T.chart.donut / 2} />
      </View>
    );
  }
  return <Shimmer testID="skeleton-chart-bar" width={width} height={T.chart.cashflow} borderRadius={T.radius.block} />;
}

const styles = StyleSheet.create({
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: spacing.sm },
  lines: { gap: spacing.sm },
  listRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: 10 },
  listRowMain: { flex: 1, gap: spacing.xs },
  formField: { gap: 6, marginBottom: spacing.xs },
  profileHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.md },
  profileHeaderText: { flex: 1, gap: spacing.xs },
  settingsRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 10 },
  settingsRowMain: { flex: 1, marginRight: spacing.sm, gap: spacing.xs },
  barRow: { gap: spacing.xs },
  barRowTop: { flexDirection: 'row', justifyContent: 'space-between' },
  hero: { width: '100%' },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
  donutWrap: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm },
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  kpiTile: { flexBasis: '47%', flexGrow: 1 },
});
```

  Two existing call sites pass a pixel width to `SkeletonChart` (`DashboardScreen.tsx:1037`, `AdvancedReportsScreen.tsx:256` and `:398`, all `width={chartWidth}`, a number). `DimensionValue` accepts a number, so they keep compiling unchanged.

  `SkeletonCard`'s line placeholder was 12 with an 8 bottom margin (`styles.line`). It is now `meta` (12) with `spacing.sm` (8): identical values.

  `SkeletonBudgetCard`'s bar was height 8 with radius 4. `SkeletonBox size="bar" round` gives the same values.

- [ ] **Step 4: Run the skeleton tests and the token scan.**

Run: `jest src/components/skeletons`
Expected: PASS, including `skeletonTokens.guard.test.ts` with no hits.

- [ ] **Step 5: Run the suites of every screen that already uses skeletons, to show the shapes did not move.**

Run: `jest src/screens/DashboardScreen.test.tsx src/screens/LedgerScreen.test.tsx src/screens/ReportsScreen.test.tsx src/screens/InsightsScreen.test.tsx src/screens/BudgetsScreen.test.tsx src/screens/GmailReviewScreen.test.tsx src/screens/CategoryReviewScreen.test.tsx src/screens/AdvancedReportsScreen.test.tsx`
Expected: PASS, unchanged counts.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/Skeletons.tsx mobile/src/components/skeletons/Skeletons.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): skeleton blocks for rows, fields, settings and groups"
```

---

### Task 3: `LoadFailed` and the test helpers

**Files:**
- Create: `mobile/src/components/LoadFailed.tsx`
- Create: `mobile/src/components/LoadFailed.test.tsx`
- Create: `mobile/src/test/loadingStates.ts`

**Interfaces:**
- Produces:
  - `LoadFailed({ message: string; onRetry: () => void; variant?: 'screen' | 'inline' = 'inline'; tone?: 'muted' | 'danger' = 'muted' })`. The button's accessible name is "Try again".
  - `never<T>(): Promise<T>`
  - `expectSkeletonNotSpinner(): void`

- [ ] **Step 1: Write the failing test.** `mobile/src/components/LoadFailed.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { ThemeProvider } from '../theme';
import { LoadFailed } from './LoadFailed';

describe('LoadFailed', () => {
  it('shows the message and a Try again button that calls onRetry once per press', () => {
    const onRetry = jest.fn();
    render(<ThemeProvider><LoadFailed message="Could not load accounts." onRetry={onRetry} /></ThemeProvider>);

    expect(screen.getByText('Could not load accounts.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('gives Try again at least the 44pt touch target', () => {
    render(<ThemeProvider><LoadFailed message="x" onRetry={jest.fn()} /></ThemeProvider>);
    const button = screen.getByRole('button', { name: 'Try again' });
    const style = Object.assign({}, ...[button.props.style].flat(Infinity).filter(Boolean));
    expect(style.minHeight).toBeGreaterThanOrEqual(44);
  });
});
```

- [ ] **Step 2: Run it to see it fail.**

Run: `jest src/components/LoadFailed.test.tsx`
Expected: FAIL with "Cannot find module './LoadFailed'".

- [ ] **Step 3: Write the component and the helpers.** `mobile/src/components/LoadFailed.tsx`:

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { spacing, useTheme } from '../theme';

/**
 * A first load that failed, and the one way back from it: "Try again", which refetches the same
 * query. The message is each screen's own existing wording; `tone` keeps its existing colour
 * (muted on the whole-screen failures, dangerInk on most inline ones) and `variant` its layout
 * (centred 14 for a whole screen, left-aligned 13 inside a section).
 */
export function LoadFailed({ message, onRetry, variant = 'inline', tone = 'muted' }: {
  message: string;
  onRetry: () => void;
  variant?: 'screen' | 'inline';
  tone?: 'muted' | 'danger';
}) {
  const c = useTheme();
  const screenSized = variant === 'screen';
  return (
    <View style={screenSized ? styles.screen : styles.inline}>
      <Text
        style={[
          screenSized ? styles.screenMessage : styles.inlineMessage,
          { color: tone === 'danger' ? c.dangerInk : c.muted },
        ]}
      >
        {message}
      </Text>
      <Pressable onPress={onRetry} accessibilityRole="button" style={styles.retry} hitSlop={8}>
        <Text style={[styles.retryText, { color: c.primary }]}>Try again</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { alignItems: 'center', gap: spacing.xs },
  inline: { alignItems: 'flex-start', paddingVertical: spacing.xs },
  screenMessage: { fontSize: 14, textAlign: 'center' },
  inlineMessage: { fontSize: 13 },
  // 44pt: the touch-target floor ReferralsScreen's own retry already meets.
  retry: { minHeight: 44, justifyContent: 'center' },
  retryText: { fontSize: 13, fontWeight: '600' },
});
```

`mobile/src/test/loadingStates.ts`:

```ts
import { ActivityIndicator } from 'react-native';
import { screen } from '@testing-library/react-native';

/** A query function result that never settles, so the screen stays in its first load. */
export function never<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

/** First load: at least one "Loading" placeholder group, and no spinner anywhere on screen. */
export function expectSkeletonNotSpinner(): void {
  expect(screen.getAllByLabelText('Loading').length).toBeGreaterThan(0);
  expect(screen.UNSAFE_queryAllByType(ActivityIndicator)).toHaveLength(0);
}
```

- [ ] **Step 4: Run it.**

Run: `jest src/components/LoadFailed.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/LoadFailed.tsx mobile/src/components/LoadFailed.test.tsx mobile/src/test/loadingStates.ts
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): one failed-load message with try again"
```

---

### Task 4: Group the placeholders already in the app

**Files:**
- Modify: `DashboardScreen.tsx` (594, 660, 696–698, 1037, 1073), `LedgerScreen.tsx:563`, `ReportsScreen.tsx` (37–41, 152), `InsightsScreen.tsx` (408, 602, 638, 716, 798–802), `BudgetsScreen.tsx:150–152`, `GmailReviewScreen.tsx:149–150`, `CategoryReviewScreen.tsx:254–256`, `AdvancedReportsScreen.tsx` (256, 398). All under `mobile/src/screens/`.
- Test: `mobile/src/screens/DashboardScreen.test.tsx`, `LedgerScreen.test.tsx`, `BudgetsScreen.test.tsx`

**Interfaces:**
- Consumes: `SkeletonGroup` (Task 2), `never`, `expectSkeletonNotSpinner` (Task 3).

- [ ] **Step 1: Write the failing tests.** In each of the three test files, add one test that holds the screen in its first load and expects a "Loading" group. Use the API each file already mocks for the screen's main query (read the file's `jest.mock('../api/endpoints', ...)` block). For example, in `LedgerScreen.test.tsx`, where the list comes from `transactionsApi.search`:

```tsx
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';

it('reads as one "Loading" region to a screen reader during the first load', () => {
  jest.mocked(transactionsApi.search).mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
});
```

  In `BudgetsScreen.test.tsx`, use the budgets list call the file mocks. In `DashboardScreen.test.tsx`, use the summary call the file mocks. Hold every call the screen's first paint waits on with `never()`; the file's `beforeEach` shows which those are.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/DashboardScreen.test.tsx src/screens/LedgerScreen.test.tsx src/screens/BudgetsScreen.test.tsx -t "Loading"`
Expected: FAIL: no element labelled "Loading" (the announcement is gone and there is no group yet).

- [ ] **Step 3: Wrap each cluster in one `SkeletonGroup`.** One group per region a person would see as one block. Keep any `style` that positioned the cluster on the group itself. The list:
  - **Dashboard:**
    - 594: `<SkeletonGroup><SkeletonKpiGrid /></SkeletonGroup>`
    - 660: around the donut.
    - 696–698: the three rows in one group.
    - 1037: around the cash-flow chart.
    - 1073: around the card.
  - **Ledger 563:** wrap the `Array.from({ length: 8 })` map in one group.
  - **Reports:** 37–41 in one group (the three totals and the section card); 152 in its own group.
  - **Insights:** each of 408, 602, 638, 716 in its own group; 798–802 (card and chart) in one group.
  - **Budgets 150–152:** one group.
  - **Gmail Review 149–150:** one group.
  - **Category Review 254–256:** one group.
  - **Advanced Reports:** 256 and 398, each in its own group.

  Import `SkeletonGroup` from the same `../components/skeletons/Skeletons` import each file already has.

- [ ] **Step 4: Run the tests of every screen touched.**

Run: `jest src/screens/DashboardScreen.test.tsx src/screens/LedgerScreen.test.tsx src/screens/ReportsScreen.test.tsx src/screens/InsightsScreen.test.tsx src/screens/BudgetsScreen.test.tsx src/screens/GmailReviewScreen.test.tsx src/screens/CategoryReviewScreen.test.tsx src/screens/AdvancedReportsScreen.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/screens
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): existing placeholders read as one loading region"
```

---

### Task 5: Accounts

**Files:**
- Create: `mobile/src/components/skeletons/ScreenSkeletons.tsx` (this task adds the shared styles and `AccountsSkeleton`; Tasks 6–12 add one export each)
- Modify: `mobile/src/screens/AccountsScreen.tsx:132-138`, `:153-154`
- Test: `mobile/src/screens/AccountsScreen.test.tsx`

**Interfaces:**
- Consumes: blocks from Task 2, `LoadFailed`, `never`, `expectSkeletonNotSpinner`.
- Produces: `ScreenSkeletons.tsx` with `const styles` below, reused by every later screen skeleton; `AccountsSkeleton()`.

**Real layout (read 2026-10-15):**
- `content` padding md, plus `insets.top + spacing.md` on top.
- `headerRow`: "Accounts" title at 22, with the "Add Account" link on the right.
- One Card per account, each with:
  - a 36pt bank badge (radius md) beside the name (15) and type (11) lines;
  - the balance at 22;
  - a detail line at 12;
  - a hairline stats row of two 11 lines;
  - a hairline actions row.

- [ ] **Step 1: Write the failing tests.** Add to `AccountsScreen.test.tsx` (import `fireEvent` is already there; add `import { expectSkeletonNotSpinner, never } from '../test/loadingStates';`):

```tsx
describe('AccountsScreen first load', () => {
  it('shows the accounts skeleton, not a spinner', () => {
    api.list.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('accounts-skeleton')).toBeTruthy();
  });

  it('after a failed first load, Try again shows the skeleton again and then the accounts', async () => {
    api.list.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText('Could not load accounts.')).toBeTruthy();

    let resolve!: (a: Account[]) => void;
    api.list.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('accounts-skeleton')).toBeTruthy();

    await act(async () => resolve([account()]));
    expect(await screen.findByText('HDFC Savings')).toBeTruthy();
  });

  it('releases the shared pulse when the screen closes mid-load', () => {
    api.list.mockReturnValue(never());
    const view = renderScreen();
    view.unmount();
    expect(__activeShimmerCountForTests()).toBe(0);
  });
});
```

Add `import { __activeShimmerCountForTests } from '../components/skeletons/Shimmer';`.

The empty state and the loaded content are already covered by "shows an empty state that mentions both ways to get an account" and "renders an account with Edit and Delete actions".

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/AccountsScreen.test.tsx -t "first load"`
Expected: FAIL: an `ActivityIndicator` is found, and there is no `accounts-skeleton` and no "Try again" button.

- [ ] **Step 3: Create `ScreenSkeletons.tsx` with the shared styles and the Accounts skeleton.**

```tsx
import { StyleSheet, View } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { Card } from '../Card';
import {
  SkeletonBarRow, SkeletonBox, SkeletonChart, SkeletonFormField, SkeletonGroup, SkeletonHero,
  SkeletonIcon, SkeletonLines, SkeletonListRow, SkeletonProfileHeader, SkeletonSettingsRow,
  SkeletonText,
} from './Skeletons';

/**
 * One skeleton per whole screen whose first load used to be a centred spinner. Each is built from
 * that screen's real layout (read 2026-10-15): the same wrappers, the same number of blocks where
 * the count is fixed, sizes from skeletonTokens. The screen keeps its own GlassScreen and
 * ScrollView around this, with the same contentContainerStyle, so padding, safe-area insets and the
 * tab-bar space are identical when the content lands. Dynamic counts (how many accounts) are not
 * copied; two stand in for "some".
 */

export function AccountsSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="accounts-skeleton">
      <View style={styles.headerRow}>
        <SkeletonText size="title" width="35%" />
        <SkeletonText size="body" width="25%" />
      </View>
      {[0, 1].map((i) => (
        <Card key={i} style={styles.stackSm}>
          <View style={styles.rowCenter}>
            <SkeletonIcon size="md" />
            <View style={styles.fill}>
              <SkeletonText size="heading" width="55%" />
              <SkeletonText size="caption" width="35%" style={styles.tight} />
            </View>
          </View>
          <SkeletonText size="title" width="45%" style={styles.gapSm} />
          <SkeletonText size="meta" width="30%" style={styles.tight} />
          <View style={[styles.dividedRow, { borderTopColor: c.border }]}>
            <SkeletonText size="caption" width="30%" />
            <SkeletonText size="caption" width="30%" />
          </View>
        </Card>
      ))}
    </SkeletonGroup>
  );
}

const styles = StyleSheet.create({
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  headerEnd: { alignItems: 'flex-end', marginBottom: spacing.sm },
  rowCenter: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  rowStart: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  fill: { flex: 1 },
  tight: { marginTop: spacing.xs },
  gapSm: { marginTop: spacing.sm },
  gapMd: { marginTop: spacing.md },
  stackSm: { marginBottom: spacing.sm },
  stackMd: { marginBottom: spacing.md },
  columnSm: { gap: spacing.sm },
  columnMd: { gap: spacing.md },
  dividedRow: {
    flexDirection: 'row', justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.sm, paddingTop: spacing.sm,
  },
  twoUpGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  twoUpTile: { flexBasis: '47%', flexGrow: 1, padding: spacing.sm, gap: spacing.xs },
  threeUp: { flexDirection: 'row', gap: spacing.sm },
  threeUpTile: { flex: 1, paddingHorizontal: spacing.sm, gap: spacing.xs },
  wrappedCard: { borderRadius: radius.xl, padding: spacing.lg },
});
```

  `radius` and the not-yet-used imports are used by Tasks 6–12. Until then the linter flags them, so add each import in the task that first uses it. In this task, import only `StyleSheet, View`, `spacing, useTheme`, `Card`, and `SkeletonGroup, SkeletonIcon, SkeletonText`.

- [ ] **Step 4: Use it in the screen.** In `AccountsScreen.tsx`:
  - Replace the `if (isLoading) { ... }` block with:

```tsx
  if (isLoading) {
    return (
      <GlassScreen style={styles.glassRoot}>
        <ScrollView
          contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md }], tabBar)}
          scrollIndicatorInsets={{ bottom: tabBar }}
        >
          <AccountsSkeleton />
        </ScrollView>
      </GlassScreen>
    );
  }
```

  - Replace `<Text style={[styles.error, { color: c.dangerInk }]}>Could not load accounts.</Text>` (the `isError` branch) with:

```tsx
        <LoadFailed message="Could not load accounts." tone="danger" onRetry={() => void refetch()} />
```

  - Add `refetch` to the `useQuery` destructuring.
  - Remove `ActivityIndicator` from the import only if no other use remains. It does remain: the delete-row spinner at line 230 stays (keep list).
  - Delete the now-unused `centered` style.
  - Add the imports for `AccountsSkeleton` and `LoadFailed`.

- [ ] **Step 5: Run the whole Accounts suite.**

Run: `jest src/screens/AccountsScreen.test.tsx`
Expected: PASS (new and existing tests).

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/AccountsScreen.tsx mobile/src/screens/AccountsScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): accounts loads with a skeleton and can try again"
```

---

### Task 6: Financial Memory, Investments and their sections

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `FinancialMemorySkeleton`, `InvestmentsSkeleton`)
- Modify: `mobile/src/screens/FinancialMemoryScreen.tsx:48-54`, `:132-133`
- Modify: `mobile/src/screens/InvestmentsScreen.tsx:200-206`
- Modify: `mobile/src/components/InvestmentActivityCard.tsx:140-141`
- Test: `FinancialMemoryScreen.test.tsx`, `InvestmentsScreen.test.tsx`, `components/InvestmentActivityCard.test.tsx`

**Real layouts (read 2026-10-15):**
- **Financial Memory:** `content` padding md with gap md. An intro paragraph at 13. A two-up grid of 7 GlassSurface tiles (radius 12, padding sm), each with value 20, label 12 and an optional caption 11. A Card with a SectionHeading and recurring rows (merchant 14 and label 11, amount on the right). Retry: pull to refresh exists in the failure state, so no button is added.
- **Investments:** `content` padding md.
  - Header: "+ Add" aligned right, 14.
  - Totals: three Cards (flex 1, paddingHorizontal sm), each with a label at 10 and a value at 15.
  - An Allocation Card (heading plus donut).
  - A Net Worth Trend Card (heading plus a 150 trend chart).
  - A Holdings Card (heading plus rows).
  - `InvestmentActivityCard`.
  - Retry: pull to refresh exists, and offline-cold falls to the inline "Could not load…" (`isPausedCold`).

- [ ] **Step 1: Write the failing tests.**

  `FinancialMemoryScreen.test.tsx` (add the loadingStates import):

```tsx
describe('FinancialMemoryScreen first load', () => {
  it('shows the memory skeleton, not a spinner', () => {
    workspace.dashboard.mockReturnValue(never());
    recurring.list.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('financial-memory-skeleton')).toBeTruthy();
  });

  it('shows a recurring-payments skeleton while only that section is still loading', async () => {
    recurring.list.mockReturnValue(never());
    renderScreen();
    await screen.findByText('Recognized recurring payments');
    expectSkeletonNotSpinner();
  });
});
```

  This needs `workspace.dashboard` to resolve with a valid summary, which the file's `beforeEach` already sets.

  `InvestmentsScreen.test.tsx`:

```tsx
describe('InvestmentsScreen first load', () => {
  it('shows the investments skeleton, not a spinner', () => {
    accounts.list.mockReturnValue(never());
    networth.current.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('investments-skeleton')).toBeTruthy();
  });
});
```

  `InvestmentActivityCard.test.tsx`:

```tsx
it('shows a skeleton, not a spinner, while the activity first loads', () => {
  transactions.search.mockReturnValue(never());
  renderCard();
  expectSkeletonNotSpinner();
});
```

  Review Focus 2, offline with nothing cached, is already pinned for Investments by the existing `isPausedCold` tests in `InvestmentsScreen.test.tsx`. Confirm one exists with `grep -n "paused\|offline" mobile/src/screens/InvestmentsScreen.test.tsx`. If none does, add:

```tsx
it('offline with nothing cached shows "—", not ₹0 and not a skeleton', async () => {
  const { onlineManager } = jest.requireActual('@tanstack/react-query');
  onlineManager.setOnline(false);
  try {
    renderScreen();
    expect(await screen.findAllByText('—')).toHaveLength(3);
    expect(screen.queryAllByLabelText('Loading')).toHaveLength(0);
  } finally {
    onlineManager.setOnline(true);
  }
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/FinancialMemoryScreen.test.tsx src/screens/InvestmentsScreen.test.tsx src/components/InvestmentActivityCard.test.tsx -t "first load|skeleton|offline"`
Expected: FAIL on the spinner checks; the offline test (if added) passes already, which shows it is a guard rather than a change.

- [ ] **Step 3: Add the two skeletons to `ScreenSkeletons.tsx`.**

```tsx
export function FinancialMemorySkeleton() {
  return (
    <SkeletonGroup testID="financial-memory-skeleton" style={styles.columnMd}>
      <SkeletonLines count={2} size="label" lastWidth="70%" />
      <View style={styles.twoUpGrid}>
        {Array.from({ length: 7 }).map((_, i) => (
          <Card key={i} style={styles.twoUpTile}>
            <SkeletonText size="value" width="50%" />
            <SkeletonText size="meta" width="75%" />
          </Card>
        ))}
      </View>
      <Card>
        <SkeletonText size="heading" width="60%" style={styles.stackSm} />
        <SkeletonListRow />
        <SkeletonListRow />
        <SkeletonListRow divider={false} />
      </Card>
    </SkeletonGroup>
  );
}

export function InvestmentsSkeleton() {
  return (
    <SkeletonGroup testID="investments-skeleton">
      <View style={styles.headerEnd}>
        <SkeletonText size="body" width="15%" />
      </View>
      <View style={styles.threeUp}>
        {[0, 1, 2].map((i) => (
          <Card key={i} style={styles.threeUpTile}>
            <SkeletonText size="tiny" width="70%" />
            <SkeletonText size="heading" width="85%" />
          </Card>
        ))}
      </View>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="35%" style={styles.stackSm} />
        <SkeletonChart variant="donut" />
      </Card>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="45%" style={styles.stackSm} />
        <SkeletonChart />
      </Card>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="30%" style={styles.stackSm} />
        <SkeletonListRow />
        <SkeletonListRow divider={false} />
      </Card>
    </SkeletonGroup>
  );
}
```

  The Net Worth Trend chart is `TREND_HEIGHT` (150) tall; `SkeletonChart`'s bar variant is `CASHFLOW_HEIGHT`, also 150. Both come from `lib/chartGeometry.ts` through `skeletonTokens.chart`.

- [ ] **Step 4: Use them.**
  - **FinancialMemoryScreen:** in the `summaryQ.isLoading` branch, return `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<FinancialMemorySkeleton />`. In the recurring card, replace `<ActivityIndicator color={c.primary} />` with:

    ```tsx
    <SkeletonGroup>
      <SkeletonListRow />
      <SkeletonListRow />
      <SkeletonListRow divider={false} />
    </SkeletonGroup>
    ```

    Remove the `ActivityIndicator` import and the `centered` style.
  - **InvestmentsScreen:** in the `loading` branch, use the same `GlassScreen` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)}` > `<InvestmentsSkeleton />`. Remove the `ActivityIndicator` import and the `centered` style.
  - **InvestmentActivityCard:** replace `<ActivityIndicator color={c.primary} />` with:

    ```tsx
    <SkeletonGroup>
      <SkeletonText size="meta" width="40%" />
      <SkeletonText size="value" width="50%" style={{ marginVertical: spacing.xs }} />
      <SkeletonListRow />
      <SkeletonListRow divider={false} />
    </SkeletonGroup>
    ```

    Put the inline margin in the file's `StyleSheet` as `skeletonValue: { marginVertical: spacing.xs }` and use `styles.skeletonValue`. Remove the `ActivityIndicator` import.

- [ ] **Step 5: Run the three suites.**

Run: `jest src/screens/FinancialMemoryScreen.test.tsx src/screens/InvestmentsScreen.test.tsx src/components/InvestmentActivityCard.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components mobile/src/screens/FinancialMemoryScreen.tsx mobile/src/screens/FinancialMemoryScreen.test.tsx mobile/src/screens/InvestmentsScreen.tsx mobile/src/screens/InvestmentsScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): financial memory and investments load with skeletons"
```

---

### Task 7: Goals, Journey and Year in Review

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `GoalsSkeleton`, `JourneySkeleton`, `WrappedSkeleton`)
- Modify: `GoalsScreen.tsx:160-166`, `JourneyScreen.tsx:37-43`, `WrappedScreen.tsx:22-28`, `:35-38`
- Test: `GoalsScreen.test.tsx`, `JourneyScreen.test.tsx`, `WrappedScreen.test.tsx`

**Real layouts (read 2026-10-15):**
- **Goals:** `content` padding md, top `insets.top + md`. A header row (title 22, "+ New goal" 14). A list with gap sm of goal Cards, each with:
  - a header row (name 15, amounts 12);
  - an 8pt progress bar;
  - a meta line at 11;
  - a hairline actions row of two 44pt bordered buttons.

  Retry: pull to refresh.
- **Journey:** `content` padding md, gap md. Year Cards, each with a year at 16, then events (title 14, detail 12). After them, the link "See your year in review" at 13. Retry: pull to refresh, and the failure text says so.
- **Year in Review:** `content` padding md. One card (radius xl, padding lg) holding:
  - eyebrow 13;
  - year 36;
  - count 18;
  - caption 13;
  - landmark lines 14.

  Retry: none, so add one. The message stays "Couldn't load your year in review. Try again later."

- [ ] **Step 1: Write the failing tests.**

  `GoalsScreen.test.tsx`:

```tsx
it('shows the goals skeleton, not a spinner, on first load', () => {
  api.list.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('goals-skeleton')).toBeTruthy();
});
```

  `JourneyScreen.test.tsx`:

```tsx
it('shows the journey skeleton, not a spinner, on first load', () => {
  api.timeline.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('journey-skeleton')).toBeTruthy();
});

it('offline with nothing cached says it could not load, not "your journey starts here"', async () => {
  const { onlineManager } = jest.requireActual('@tanstack/react-query');
  onlineManager.setOnline(false);
  try {
    renderScreen();
    expect(await screen.findByText(/Couldn't load your journey/)).toBeTruthy();
    expect(screen.queryByText(/Your journey starts here/)).toBeNull();
    expect(screen.queryAllByLabelText('Loading')).toHaveLength(0);
  } finally {
    onlineManager.setOnline(true);
  }
});
```

  `WrappedScreen.test.tsx` (add `fireEvent` to the RNTL import):

```tsx
it('shows the year-in-review skeleton, not a spinner, on first load', () => {
  api.wrapped.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('wrapped-skeleton')).toBeTruthy();
});

it('after a failed first load, Try again refetches and shows the year', async () => {
  api.wrapped.mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your year in review. Try again later.")).toBeTruthy();

  api.wrapped.mockResolvedValueOnce({ year: 2026, landmarksReached: 0, goalContributions: 0, landmarkTitles: [] });
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('2026')).toBeTruthy();
});
```

  If `JourneyScreen.test.tsx` already has an offline test (`grep -n "setOnline" mobile/src/screens/JourneyScreen.test.tsx`), keep that one and skip adding the second test above.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/GoalsScreen.test.tsx src/screens/JourneyScreen.test.tsx src/screens/WrappedScreen.test.tsx -t "skeleton|Try again|offline"`
Expected: FAIL on the skeleton and Try again tests.

- [ ] **Step 3: Add the skeletons.**

```tsx
export function GoalsSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="goals-skeleton">
      <View style={styles.headerRow}>
        <SkeletonText size="title" width="25%" />
        <SkeletonText size="body" width="25%" />
      </View>
      <View style={styles.columnSm}>
        {[0, 1].map((i) => (
          <Card key={i}>
            <View style={[styles.headerRow, styles.stackSm]}>
              <SkeletonText size="heading" width="45%" />
              <SkeletonText size="meta" width="30%" />
            </View>
            <SkeletonBox size="bar" round />
            <SkeletonText size="caption" width="35%" style={styles.gapSm} />
            <View style={[styles.dividedRow, { borderTopColor: c.border }, styles.columnSmRow]}>
              <SkeletonBox size="touch" width="48%" />
              <SkeletonBox size="touch" width="48%" />
            </View>
          </Card>
        ))}
      </View>
    </SkeletonGroup>
  );
}

export function JourneySkeleton() {
  return (
    <SkeletonGroup testID="journey-skeleton" style={styles.columnMd}>
      {[0, 1].map((i) => (
        <Card key={i}>
          <SkeletonText size="subheading" width="20%" style={styles.stackSm} />
          {[0, 1, 2].map((j) => (
            <View key={j} style={styles.stackSm}>
              <SkeletonText size="body" width="70%" />
              <SkeletonText size="meta" width="45%" style={styles.tight} />
            </View>
          ))}
        </Card>
      ))}
      <SkeletonText size="label" width="45%" />
    </SkeletonGroup>
  );
}

export function WrappedSkeleton() {
  return (
    <SkeletonGroup testID="wrapped-skeleton">
      <Card style={styles.wrappedCard}>
        <SkeletonText size="label" width="45%" />
        <SkeletonText size="display" width="30%" style={styles.gapSm} />
        <SkeletonText size="amount" width="12%" style={styles.gapMd} />
        <SkeletonText size="label" width="50%" style={styles.tight} />
        <SkeletonText size="body" width="70%" style={styles.gapMd} />
        <SkeletonText size="body" width="55%" style={styles.gapSm} />
      </Card>
    </SkeletonGroup>
  );
}
```

  Add `columnSmRow: { gap: spacing.sm }` to the shared styles. `dividedRow` is a row with `space-between`; the gap is added for the two buttons.

- [ ] **Step 4: Use them.**
  - **Goals:** the loading branch returns `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md }], tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<GoalsSkeleton />`.
  - **Journey:** `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)}` > `<JourneySkeleton />`.
  - **Wrapped:** `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)}` > `<WrappedSkeleton />`. In the `!data` branch, replace the `<Text>` with:

    ```tsx
    <LoadFailed message="Couldn't load your year in review. Try again later." onRetry={() => void refetch()} />
    ```

    Destructure `refetch`.
  - In all three, remove the `ActivityIndicator` import and the `centered` style. Wrapped's `note` style also becomes unused: delete it.

- [ ] **Step 5: Run the three suites.**

Run: `jest src/screens/GoalsScreen.test.tsx src/screens/JourneyScreen.test.tsx src/screens/WrappedScreen.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/GoalsScreen.tsx mobile/src/screens/GoalsScreen.test.tsx mobile/src/screens/JourneyScreen.tsx mobile/src/screens/JourneyScreen.test.tsx mobile/src/screens/WrappedScreen.tsx mobile/src/screens/WrappedScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): goals, journey and year in review load with skeletons"
```

---

### Task 8: Profile and Refer & Earn

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `ProfileSkeleton`, `ReferralsSkeleton`)
- Modify: `ProfileScreen.tsx:91-105`, `ReferralsScreen.tsx:44` (export the ratio), `:387-393`
- Test: `ProfileScreen.test.tsx`, `ReferralsScreen.test.tsx`

**Real layouts (read 2026-10-15):**
- **Profile:** `content` padding md.
  - An identity GlassSurface: a 52 avatar, then name 17, email 13 and meta 11.
  - A SectionCard "Personal Information": title 16, subtitle 12, body marginTop md, containing:
    - the TextField;
    - three ReadOnlyFields (label 12, value 14);
    - a save row;
    - a 48 Button.
  - A SectionCard "Security Overview": two rows (title 14, meta 12) and a 44 link.

  Retry: none, so add one (Ruling 1).
- **Refer & Earn:** `content` padding md, gap md, top `insets.top + md`.
  - Title 28 and subtitle 14.
  - A hero illustration at aspect ratio `HERO_ASPECT_RATIO`.
  - A steps Card: three 32 circles, each with label 11 and caption.
  - A code Card:
    - label 13;
    - a 52 code row;
    - a 48 share button;
    - a row of five 44 channel circles.
  - A stats row of three tiles.

  It already has "Try again".

- [ ] **Step 1: Write the failing tests.**

  `ProfileScreen.test.tsx` (add `fireEvent` if missing):

```tsx
it('shows the profile skeleton, not a spinner, on first load', () => {
  user.get.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('profile-skeleton')).toBeTruthy();
});

it('after a failed first load, Try again refetches the profile', async () => {
  user.get.mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText(/Couldn.t load your profile/)).toBeTruthy();

  user.get.mockResolvedValueOnce(settings());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('Personal Information')).toBeTruthy();
});
```

  `settings()` is the fixture builder in `ProfileScreen.test.tsx`; read its name from the file (`grep -n "^function" mobile/src/screens/ProfileScreen.test.tsx`) and use that one.

  `ReferralsScreen.test.tsx`:

```tsx
it('shows the referral skeleton, not a spinner, on first load', () => {
  api.mine.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('referrals-skeleton')).toBeTruthy();
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/ProfileScreen.test.tsx src/screens/ReferralsScreen.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Add the skeletons.** In `ReferralsScreen.tsx`, change `const HERO_ASPECT_RATIO` to `export const HERO_ASPECT_RATIO`. The skeleton takes the ratio as a prop, so `components/` does not import from `screens/`.

```tsx
export function ProfileSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="profile-skeleton">
      <SkeletonProfileHeader />
      <Card style={styles.stackMd}>
        <SkeletonText size="subheading" width="50%" />
        <SkeletonText size="meta" width="65%" style={styles.tight} />
        <View style={styles.gapMd}>
          <SkeletonFormField />
          {[0, 1, 2].map((i) => (
            <View key={i} style={styles.stackSm}>
              <SkeletonText size="meta" width="25%" />
              <SkeletonText size="body" width="55%" style={styles.tight} />
            </View>
          ))}
          <SkeletonBox size="button" style={styles.gapSm} />
        </View>
      </Card>
      <Card style={styles.stackMd}>
        <SkeletonText size="subheading" width="45%" />
        <SkeletonText size="meta" width="70%" style={styles.tight} />
        <View style={styles.gapMd}>
          <SkeletonSettingsRow />
          <SkeletonSettingsRow divider={false} trailing="badge" />
          <SkeletonBox size="touch" width="40%" style={styles.gapSm} />
        </View>
      </Card>
    </SkeletonGroup>
  );
}

export function ReferralsSkeleton({ heroAspectRatio }: { heroAspectRatio: number }) {
  return (
    <SkeletonGroup testID="referrals-skeleton" style={styles.columnMd}>
      <View>
        <SkeletonText size="hero" width="50%" />
        <SkeletonLines count={2} size="body" lastWidth="75%" />
      </View>
      <SkeletonHero aspectRatio={heroAspectRatio} />
      <Card>
        <View style={styles.threeUp}>
          {[0, 1, 2].map((i) => (
            <View key={i} style={styles.centredColumn}>
              <SkeletonIcon size="sm" round />
              <SkeletonText size="caption" width="70%" />
            </View>
          ))}
        </View>
      </Card>
      <Card style={styles.columnSm}>
        <SkeletonText size="label" width="40%" />
        <SkeletonBox size="codeRow" />
        <SkeletonBox size="button" />
        <View style={styles.spacedRow}>
          {[0, 1, 2, 3, 4].map((i) => <SkeletonIcon key={i} size="lg" round />)}
        </View>
      </Card>
      <View style={styles.threeUp}>
        {[0, 1, 2].map((i) => (
          <Card key={i} style={styles.threeUpTile}>
            <SkeletonText size="caption" width="70%" />
            <SkeletonText size="value" width="50%" />
          </Card>
        ))}
      </View>
    </SkeletonGroup>
  );
}
```

  Add to the shared styles:
  - `centredColumn: { flex: 1, alignItems: 'center', gap: spacing.xs }`
  - `spacedRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs }`

  `SkeletonLines` already spaces its own lines. Its `View` has no top margin, which matches Refer's subtitle `marginTop: -4`, close enough. The spec does not copy exact text offsets.

- [ ] **Step 4: Use them.**
  - **Profile** loading branch: `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<ProfileSkeleton />`.
  - **Profile** `isError || !user` branch: keep `GlassScreen style={styles.centered}` and replace the `<Text>` with:

    ```tsx
    <LoadFailed variant="screen" message="Couldn't load your profile — please try again later." onRetry={() => void refetch()} />
    ```

    The JSX source writes `Couldn&apos;t`, which renders as "Couldn't"; in a JS string it is `"Couldn't load your profile — please try again later."`, the same text on screen.
  - **Referrals** loading branch: `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md }], tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<ReferralsSkeleton heroAspectRatio={HERO_ASPECT_RATIO} />`.
  - Remove `ActivityIndicator` from both imports. Profile's `message` style becomes unused: delete it. Referrals keeps `centered`, which its failure branch still uses.

- [ ] **Step 5: Run the two suites.**

Run: `jest src/screens/ProfileScreen.test.tsx src/screens/ReferralsScreen.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/ProfileScreen.tsx mobile/src/screens/ProfileScreen.test.tsx mobile/src/screens/ReferralsScreen.tsx mobile/src/screens/ReferralsScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): profile and refer load with skeletons, profile can try again"
```

---

### Task 9: Ticket Detail

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `TicketDetailSkeleton`)
- Modify: `SupportTicketDetailScreen.tsx:67-84`
- Test: `SupportTicketDetailScreen.test.tsx`

**Real layout (read 2026-10-15):** `content` padding md. One GlassSurface card (border 1, radius lg, padding md) holding:
- a header row: meta 11, subject 17, meta 11, and a status badge on the right;
- the description at 14, marginTop md.

The failure shows "Ticket not found" and its body. The query has `retry: false` because a 404 is a real answer, but a network failure reaches the same branch with no way back. Add "Try again" under the existing title and body, keeping their wording.

- [ ] **Step 1: Write the failing tests.**

```tsx
it('shows the ticket skeleton, not a spinner, on first load', () => {
  api.detail.mockReturnValue(never());
  renderScreen('t-1');
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('ticket-detail-skeleton')).toBeTruthy();
});

it('offers Try again under "Ticket not found", and it refetches', async () => {
  api.detail.mockRejectedValueOnce(new Error('down'));
  renderScreen('t-1');
  expect(await screen.findByText('Ticket not found')).toBeTruthy();

  api.detail.mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('ticket-detail-skeleton')).toBeTruthy();
  expect(api.detail).toHaveBeenCalledTimes(2);
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/SupportTicketDetailScreen.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Add the skeleton.**

```tsx
export function TicketDetailSkeleton() {
  return (
    <SkeletonGroup testID="ticket-detail-skeleton">
      <Card>
        <View style={styles.rowStart}>
          <View style={styles.fill}>
            <SkeletonText size="caption" width="45%" />
            <SkeletonText size="subject" width="75%" style={styles.tight} />
            <SkeletonText size="caption" width="30%" style={styles.tight} />
          </View>
          <SkeletonBox size="badge" width="22%" />
        </View>
        <View style={styles.gapMd}>
          <SkeletonLines count={3} size="body" />
        </View>
      </Card>
    </SkeletonGroup>
  );
}
```

- [ ] **Step 4: Use it.**
  - Loading branch: `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<TicketDetailSkeleton />`.
  - In the `!ticketQuery.data` branch, after the body `<Text>`, add the retry button. Only show it when there is a ticket to retry (`ticketId` present):

```tsx
        {ticketId ? (
          <Pressable onPress={() => void ticketQuery.refetch()} accessibilityRole="button" style={styles.retry} hitSlop={8}>
            <Text style={[styles.retryText, { color: c.primary }]}>Try again</Text>
          </Pressable>
        ) : null}
```

  Add styles `retry: { minHeight: 44, justifyContent: 'center' }` and `retryText: { fontSize: 13, fontWeight: '600' }`. `LoadFailed` is not used here because this branch already has its own icon, title and body; only the button is new.

  Remove the `ActivityIndicator` import.

- [ ] **Step 5: Run the suite.**

Run: `jest src/screens/SupportTicketDetailScreen.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/SupportTicketDetailScreen.tsx mobile/src/screens/SupportTicketDetailScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): ticket detail loads with a skeleton and can try again"
```

---

### Task 10: Settings › General and notification preferences

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `SettingsGeneralSkeleton`)
- Modify: `SettingsGeneralScreen.tsx:113-133`
- Modify: `screens/settings/NotificationPreferencesSection.tsx:25-36`, `:53-59`
- Test: `SettingsGeneralScreen.test.tsx`, `screens/settings/NotificationPreferencesSection.test.tsx`

**Real layout (read 2026-10-15):** `content` padding md.
- A TextField (label 12 over a 48 row).
- "Timezone": label 12 and a 48 picker.
- "Theme": label 12 and a 44 segment row.
- A hint at 11.
- A save row.
- A 48 Button.
- `NotificationPreferencesSection`: heading 15, then rows of title 14 and meta 12, each with a Switch.
- The Retake Tour row: a label and hint on the left, a link on the right.

- [ ] **Step 1: Write the failing tests.**

  `SettingsGeneralScreen.test.tsx`:

```tsx
it('shows the general settings skeleton, not a spinner, on first load', () => {
  user.get.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('settings-general-skeleton')).toBeTruthy();
});

it('after a failed first load, Try again refetches the settings', async () => {
  user.get.mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

  user.get.mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('settings-general-skeleton')).toBeTruthy();
});
```

  `NotificationPreferencesSection.test.tsx` (use the file's mocked API, `notificationPreferencesApi.list`):

```tsx
it('shows a skeleton, not a spinner, while the preferences load', () => {
  jest.mocked(notificationPreferencesApi.list).mockReturnValue(never());
  renderSection();
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again loads them again', async () => {
  jest.mocked(notificationPreferencesApi.list).mockRejectedValueOnce(new Error('down'));
  renderSection();
  expect(await screen.findByText("Couldn't load your notification settings.")).toBeTruthy();

  jest.mocked(notificationPreferencesApi.list).mockResolvedValueOnce(both(true, true));
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findAllByRole('switch')).not.toHaveLength(0);
  expect(notificationPreferencesApi.list).toHaveBeenCalledTimes(2);
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/SettingsGeneralScreen.test.tsx src/screens/settings/NotificationPreferencesSection.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Add the skeleton.**

```tsx
export function SettingsGeneralSkeleton() {
  return (
    <SkeletonGroup testID="settings-general-skeleton">
      <SkeletonFormField />
      <View style={styles.gapSm}>
        <SkeletonFormField />
      </View>
      <View style={styles.gapMd}>
        <SkeletonText size="meta" width="20%" style={styles.stackSm} />
        <SkeletonBox size="touch" />
        <SkeletonText size="caption" width="80%" style={styles.gapSm} />
      </View>
      <SkeletonBox size="button" style={styles.gapMd} />
      <View style={styles.gapMd}>
        <SkeletonText size="heading" width="35%" style={styles.stackSm} />
        <SkeletonSettingsRow trailing="switch" />
        <SkeletonSettingsRow trailing="switch" divider={false} />
      </View>
    </SkeletonGroup>
  );
}
```

- [ ] **Step 4: Use it, and give notification preferences a retry.**
  - **SettingsGeneral** loading branch: `GlassScreen style={styles.flex}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<SettingsGeneralSkeleton />`.
  - **SettingsGeneral** failure branch: replace the `<Text>` with:

    ```tsx
    <LoadFailed variant="screen" message="Couldn't load your settings — please try again later." onRetry={() => void userQ.refetch()} />
    ```

    Remove `ActivityIndicator` and the `message` style.
  - **NotificationPreferencesSection:** add `const [attempt, setAttempt] = useState(0);` and use `[attempt]` as the effect's dependency list. The effect body stays as it is.
    - Add a retry handler (no `setState` inside the effect):

      ```tsx
      function retryLoad() {
        setLoadError(false);
        setPrefs(null);
        setAttempt((a) => a + 1);
      }
      ```
    - Replace the `loadError` branch's `<Text>` with:

      ```tsx
      <LoadFailed message="Couldn't load your notification settings." tone="danger" onRetry={retryLoad} />
      ```
    - Replace the `<ActivityIndicator ... />` with:

      ```tsx
      <SkeletonGroup>
        <SkeletonSettingsRow trailing="switch" />
        <SkeletonSettingsRow trailing="switch" divider={false} />
      </SkeletonGroup>
      ```

  The section's failure message drops "Try reopening Settings", which the button replaces (Ruling in the plan header).

- [ ] **Step 5: Run both suites.**

Run: `jest src/screens/SettingsGeneralScreen.test.tsx src/screens/settings/NotificationPreferencesSection.test.tsx`
Expected: PASS. If an existing test asserted the old text "Try reopening Settings", update that one assertion to the new text; it is the wording change this task makes.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/SettingsGeneralScreen.tsx mobile/src/screens/SettingsGeneralScreen.test.tsx mobile/src/screens/settings/NotificationPreferencesSection.tsx mobile/src/screens/settings/NotificationPreferencesSection.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): general settings and notifications load with skeletons"
```

---

### Task 11: Settings › Security, app lock and device sessions

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `SettingsSecuritySkeleton`)
- Modify: `SettingsSecurityScreen.tsx:27-47`
- Modify: `screens/settings/AppLockSection.tsx:73-79`
- Modify: `screens/settings/DeviceSessionsSection.tsx:39`, `:80-86`
- Test: `SettingsSecurityScreen.test.tsx`, `AppLockSection.test.tsx`, `DeviceSessionsSection.test.tsx`

**Real layout (read 2026-10-15):** `content` padding md.
- Email row (title 14, meta 12), then a 48 Change Email button.
- Password row, then a 48 button.
- Phone row with a badge.
- AppLock row with a switch.
- Sessions: title, blurb, and bordered rows.

- [ ] **Step 1: Write the failing tests.**

  `SettingsSecurityScreen.test.tsx`:

```tsx
it('shows the security skeleton, not a spinner, on first load', () => {
  jest.mocked(userApi.get).mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('settings-security-skeleton')).toBeTruthy();
});

it('after a failed first load, Try again refetches', async () => {
  jest.mocked(userApi.get).mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

  jest.mocked(userApi.get).mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('settings-security-skeleton')).toBeTruthy();
});
```

  `AppLockSection.test.tsx`: the section waits on `appLock.isSupported()` and `appLock.isEnabledConfirmed()`, which read `expo-local-authentication` and SecureStore. Hold the first:

```tsx
it('shows a skeleton row, not a spinner, while it reads the device', () => {
  mockedHasHardware.mockReturnValue(never());
  renderSection();
  expectSkeletonNotSpinner();
});
```

  `DeviceSessionsSection.test.tsx`:

```tsx
it('shows a skeleton, not a spinner, while sessions load', () => {
  devices.list.mockReturnValue(never());
  renderSection();
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again refetches the sessions', async () => {
  devices.list.mockRejectedValueOnce(new Error('down'));
  renderSection();
  expect(await screen.findByText(/Couldn.t load your active sessions/)).toBeTruthy();

  devices.list.mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expectSkeletonNotSpinner();
  expect(devices.list).toHaveBeenCalledTimes(2);
});
```

  If `appLock.isSupported()` does not call `hasHardwareAsync` first, hold the call it does make. Read `mobile/src/lib/appLock.ts` for the exact one before writing the test.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/SettingsSecurityScreen.test.tsx src/screens/settings/AppLockSection.test.tsx src/screens/settings/DeviceSessionsSection.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Add the skeleton.**

```tsx
export function SettingsSecuritySkeleton() {
  return (
    <SkeletonGroup testID="settings-security-skeleton">
      <SkeletonSettingsRow />
      <SkeletonBox size="button" style={styles.gapSmStackMd} />
      <SkeletonSettingsRow />
      <SkeletonBox size="button" style={styles.gapSmStackMd} />
      <SkeletonSettingsRow trailing="badge" />
      <SkeletonSettingsRow trailing="switch" />
      <View style={styles.gapMd}>
        <SkeletonText size="heading" width="40%" />
        <SkeletonLines count={2} size="meta" />
        <SkeletonListRow amount={false} />
        <SkeletonListRow amount={false} divider={false} />
      </View>
    </SkeletonGroup>
  );
}
```

  Add `gapSmStackMd: { marginTop: spacing.sm, marginBottom: spacing.md }` to the shared styles. These are the real `changePassword` margins.

- [ ] **Step 4: Use it.**
  - **Security** loading branch: `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > `<SettingsSecuritySkeleton />`.
  - **Security** failure: replace the `<Text>` with:

    ```tsx
    <LoadFailed variant="screen" message="Couldn't load your settings — please try again later." onRetry={() => void userQ.refetch()} />
    ```

    Remove `ActivityIndicator` and the `message` style.
  - **AppLockSection** `loading` branch: return `<SkeletonGroup><SkeletonSettingsRow trailing="switch" /></SkeletonGroup>`. The real row's bottom border is the skeleton row's divider. Remove the `ActivityIndicator` import.
  - **DeviceSessionsSection:**
    - Destructure `refetch`.
    - Replace the loading `ActivityIndicator` with:

      ```tsx
      <SkeletonGroup style={styles.loader}>
        <SkeletonListRow amount={false} />
        <SkeletonListRow amount={false} divider={false} />
      </SkeletonGroup>
      ```
    - Replace the `isError` `<Text>` with:

      ```tsx
      <LoadFailed message="Couldn't load your active sessions — please try again later." tone="danger" onRetry={() => void refetch()} />
      ```
    - The sign-out spinner at line 121 stays (keep list).

- [ ] **Step 5: Run the three suites.**

Run: `jest src/screens/SettingsSecurityScreen.test.tsx src/screens/settings/AppLockSection.test.tsx src/screens/settings/DeviceSessionsSection.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/SettingsSecurityScreen.tsx mobile/src/screens/SettingsSecurityScreen.test.tsx mobile/src/screens/settings/AppLockSection.tsx mobile/src/screens/settings/AppLockSection.test.tsx mobile/src/screens/settings/DeviceSessionsSection.tsx mobile/src/screens/settings/DeviceSessionsSection.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): security settings, app lock and sessions load with skeletons"
```

---

### Task 12: Settings › Categorization and Settings › Account

**Files:**
- Modify: `ScreenSkeletons.tsx` (add `SettingsCategorizationSkeleton`, `SettingsAccountSkeleton`)
- Modify: `SettingsCategorizationScreen.tsx:65-87`, `SettingsAccountScreen.tsx:47-66`
- Test: `SettingsCategorizationScreen.test.tsx`, `SettingsAccountScreen.test.tsx`

**Real layouts (read 2026-10-15):**
- **Categorization:** `content` padding md.
  - A stepper: a 48 button, the value at 20 (min width 64), and a 48 button.
  - A hint at 11 (two lines).
  - A link at 13.
  - A save row.
  - A 48 Button.
  - `InflowKindsSettings`: a heading and switch rows.
- **Account:** `content` padding md. Two danger rows, each with:
  - a label at 12;
  - a hint at 11 (three lines);
  - a link Button at 14.

- [ ] **Step 1: Write the failing tests.**

  `SettingsCategorizationScreen.test.tsx`:

```tsx
it('shows the categorization skeleton, not a spinner, on first load', () => {
  jest.mocked(workspaceApi.getSettings).mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
  expect(screen.getByTestId('settings-categorization-skeleton')).toBeTruthy();
});

it('after a failed first load, Try again refetches', async () => {
  jest.mocked(workspaceApi.getSettings).mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

  jest.mocked(workspaceApi.getSettings).mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('settings-categorization-skeleton')).toBeTruthy();
});
```

  `SettingsAccountScreen.test.tsx`: the same two tests, with `userApi.get` and the test id `settings-account-skeleton`.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/SettingsCategorizationScreen.test.tsx src/screens/SettingsAccountScreen.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Add the skeletons.**

```tsx
export function SettingsCategorizationSkeleton() {
  return (
    <SkeletonGroup testID="settings-categorization-skeleton">
      <View style={styles.rowCenterMd}>
        <SkeletonBox size="field" width="15%" />
        <SkeletonText size="value" width="20%" />
        <SkeletonBox size="field" width="15%" />
      </View>
      <View style={styles.gapSm}>
        <SkeletonLines count={2} size="caption" />
      </View>
      <SkeletonText size="label" width="70%" style={styles.gapSm} />
      <SkeletonBox size="button" style={styles.gapMd} />
      <View style={styles.gapMd}>
        <SkeletonText size="heading" width="35%" style={styles.stackSm} />
        <SkeletonSettingsRow trailing="switch" />
        <SkeletonSettingsRow trailing="switch" divider={false} />
      </View>
    </SkeletonGroup>
  );
}

export function SettingsAccountSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="settings-account-skeleton">
      {[0, 1].map((i) => (
        <View key={i} style={[styles.dangerRow, i === 0 && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
          <SkeletonText size="meta" width="40%" />
          <View style={styles.gapSm}>
            <SkeletonLines count={3} size="caption" />
          </View>
          <SkeletonText size="body" width="35%" style={styles.gapSm} />
        </View>
      ))}
    </SkeletonGroup>
  );
}
```

  Add to the shared styles:
  - `rowCenterMd: { flexDirection: 'row', alignItems: 'center', gap: spacing.md }`
  - `dangerRow: { paddingVertical: spacing.md }`

- [ ] **Step 4: Use them.**
  - In both screens, the loading branch becomes `GlassScreen style={styles.glassRoot}` > `ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}` > the skeleton.
  - The failure branch's `<Text>` becomes `<LoadFailed variant="screen" message="Couldn't load your settings — please try again later." onRetry={...} />`:
    - Categorization: `onRetry={() => void workspaceQ.refetch()}`.
    - Account: `onRetry={() => void userQ.refetch()}`.
  - Remove `ActivityIndicator` and the `message` styles.

- [ ] **Step 5: Run both suites.**

Run: `jest src/screens/SettingsCategorizationScreen.test.tsx src/screens/SettingsAccountScreen.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/ScreenSkeletons.tsx mobile/src/screens/SettingsCategorizationScreen.tsx mobile/src/screens/SettingsCategorizationScreen.test.tsx mobile/src/screens/SettingsAccountScreen.tsx mobile/src/screens/SettingsAccountScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): categorization and account settings load with skeletons"
```

---

### Task 13: Advanced Reports' six sections

**Files:**
- Modify: `mobile/src/screens/AdvancedReportsScreen.tsx:178-220`, `:278-300`, `:349-395`
- Test: `mobile/src/screens/AdvancedReportsScreen.test.tsx`

Each section's `isLoading ? <ActivityIndicator …/>` becomes a group of four `SkeletonBarRow`s; International Spend gets three lines. Each section also gets the failure state it lacks (Ruling 2): a failed first load shows "Couldn't load this section." with "Try again" that refetches that section's queries. The message is new wording, because there was none. Retry also works by pulling to refresh.

- [ ] **Step 1: Write the failing tests.** In `AdvancedReportsScreen.test.tsx`, the file's `beforeEach` grants access and resolves every analytics call. Add:

```tsx
describe('AdvancedReportsScreen sections', () => {
  it('shows section skeletons, not spinners, while sections first load', async () => {
    analytics.topMerchants.mockReturnValue(never());
    analytics.topCategories.mockReturnValue(never());
    analytics.international.mockReturnValue(never());
    analytics.multiYearIncome.mockReturnValue(never());
    analytics.multiYearCategories.mockReturnValue(never());
    analytics.categoryConfidence.mockReturnValue(never());
    renderScreen();
    await screen.findByText('Top Merchants');
    expect(screen.getAllByLabelText('Loading').length).toBeGreaterThanOrEqual(6);
    expect(screen.UNSAFE_queryAllByType(ActivityIndicator)).toHaveLength(0);
  });

  it('a failed section says so, instead of claiming there is nothing yet, and can try again', async () => {
    analytics.topMerchants.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText("Couldn't load this section.")).toBeTruthy();
    expect(screen.queryByText('Import a statement or add transactions to see your top merchants.')).toBeNull();

    analytics.topMerchants.mockResolvedValueOnce([]);
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Import a statement or add transactions to see your top merchants.')).toBeTruthy();
  });
});
```

  Import `ActivityIndicator` from `react-native` and the helpers. If `multiYearIncome` is not what gates the income/spend comparison card in the current file, hold the query that the `multiYearIncomeQ.isLoading || multiYearSpendQ.isLoading` line reads. It is both, so holding `multiYearIncome` is enough.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/AdvancedReportsScreen.test.tsx -t "sections"`
Expected: FAIL: spinners found, and the empty message is shown on failure.

- [ ] **Step 3: Write it.** Near the top of the file (outside the component) add:

```tsx
/** Four bar-list rows: the shape of a HorizontalBarList while its first load is in flight. */
function SectionSkeleton() {
  return (
    <SkeletonGroup style={styles.sectionSkeleton}>
      {[0, 1, 2, 3].map((i) => <SkeletonBarRow key={i} />)}
    </SkeletonGroup>
  );
}

/** A first load that failed, which used to fall through to the empty message as if there were no data. */
function SectionFailed({ onRetry }: { onRetry: () => void }) {
  return <LoadFailed message="Couldn't load this section." tone="danger" onRetry={onRetry} />;
}
```

  Change each section as follows (`q` is that section's query):

  ```tsx
  // before
  {q.isLoading ? <ActivityIndicator …/> : (…)}
  // after
  {q.isLoading ? <SectionSkeleton /> : q.isError && q.data === undefined ? <SectionFailed onRetry={() => void q.refetch()} /> : (…)}
  ```

  The six sections:
  - **Top Merchants:** `topMerchantsQ`.
  - **Top Categories:** `topCategoriesQ`.
  - **International Spend:** `internationalQ`. Its loading shape is `<SkeletonGroup style={styles.sectionSkeleton}><SkeletonLines count={3} size="body" /></SkeletonGroup>`, not the bar rows.
  - **The income/spend comparison:**
    - Loading: `multiYearIncomeQ.isLoading || multiYearSpendQ.isLoading`.
    - Failure: `(multiYearIncomeQ.isError && multiYearIncomeQ.data === undefined) || (multiYearSpendQ.isError && multiYearSpendQ.data === undefined)`.
    - Retry: `() => { void multiYearIncomeQ.refetch(); void multiYearSpendQ.refetch(); }`.
  - **Category Trends:** `multiYearCategoriesQ`.
  - **Category Confidence:** `confidenceQ`.

  Replace the `loader` style with `sectionSkeleton: { gap: spacing.sm, paddingVertical: spacing.sm }`. Remove the `ActivityIndicator` import if no use remains (none does).

- [ ] **Step 4: Run the suite.**

Run: `jest src/screens/AdvancedReportsScreen.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/screens/AdvancedReportsScreen.tsx mobile/src/screens/AdvancedReportsScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): advanced reports sections load with skeletons and admit failures"
```

---

### Task 14: Statement History list and statement detail, My Tickets

**Files:**
- Modify: `mobile/src/screens/StatementHistoryScreen.tsx:76`, `:224-231`, `:545-581`
- Modify: `mobile/src/screens/SupportTicketsScreen.tsx:84-86`
- Test: `StatementHistoryScreen.test.tsx`, `SupportTicketsScreen.test.tsx`

- [ ] **Step 1: Write the failing tests.**

  `StatementHistoryScreen.test.tsx` (new `describe`; `api` is `statementImportsApi`, `groups` the file's fixture):

```tsx
describe('StatementHistoryScreen loading states', () => {
  beforeEach(() => {
    api.listGroupedByAccount.mockReset().mockResolvedValue(groups);
  });

  it('shows statement skeletons, not a spinner, on first load', () => {
    api.listGroupedByAccount.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
  });

  it('a failed list offers Try again instead of asking for a pull the screen does not have', async () => {
    api.listGroupedByAccount.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText("Couldn't load your statement history.")).toBeTruthy();
    expect(screen.queryByText(/Pull back/)).toBeNull();

    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findAllByLabelText('Transactions')).not.toHaveLength(0);
  });

  it('the transactions pop-up shows a skeleton while it loads', async () => {
    api.transactions.mockReturnValue(never());
    renderScreen();
    fireEvent.press((await screen.findAllByLabelText('Transactions'))[0]);
    expect(await screen.findByLabelText('Loading')).toBeTruthy();
  });

  it('a failed transactions pop-up says so, not "no longer on the books"', async () => {
    api.transactions.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    fireEvent.press((await screen.findAllByLabelText('Transactions'))[0]);
    expect(await screen.findByText("Couldn't load this import's transactions.")).toBeTruthy();
    expect(screen.queryByText(/no longer on the books/)).toBeNull();

    api.transactions.mockResolvedValueOnce([]);
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(/no longer on the books/)).toBeTruthy();
  });
});
```

  The "Transactions" button is inside an account group. A lone group opens on its own (`groups.length === 1`). If the file's `groups` fixture has more than one, press the first group's header (its label starts with the account name) before looking for "Transactions".

  `SupportTicketsScreen.test.tsx`:

```tsx
it('shows ticket skeletons, not a spinner, on first load', () => {
  api.list.mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/StatementHistoryScreen.test.tsx src/screens/SupportTicketsScreen.test.tsx -t "loading states|skeletons"`
Expected: FAIL.

- [ ] **Step 3: Write it.**
  - **StatementHistoryScreen list:**
    - Destructure `refetch` from the list query (line 76).
    - Replace `<ActivityIndicator color={c.primary} />` with:

      ```tsx
      <SkeletonGroup>
        {[0, 1].map((i) => (
          <Card key={i} style={styles.section}>
            <SkeletonListRow amount={false} divider={false} />
          </Card>
        ))}
      </SkeletonGroup>
      ```
    - Replace the `isError` branch's `<EmptyState message="Couldn't load your statement history. Pull back and try again." />` with:

      ```tsx
      <LoadFailed message="Couldn't load your statement history." onRetry={() => void refetch()} />
      ```

      Keep it inside the `Card`.
  - **StatementDetailModal:**
    - Destructure `isError` and `refetch` from its query.
    - Replace the loading `ActivityIndicator` with:

      ```tsx
      <SkeletonGroup style={styles.detailLoading}>
        {[0, 1, 2, 3].map((i) => <SkeletonTransactionRow key={i} />)}
      </SkeletonGroup>
      ```
    - Before the `!transactions || transactions.length === 0` branch, add:

      ```tsx
      ) : isError && transactions === undefined ? (
        <LoadFailed message="Couldn't load this import's transactions." tone="danger" onRetry={() => void refetch()} />
      ```
  - **SupportTicketsScreen** `ListEmptyComponent`: replace the loading `ActivityIndicator` with:

    ```tsx
    <SkeletonGroup>
      {[0, 1, 2].map((i) => <SkeletonListRow key={i} amount={false} />)}
    </SkeletonGroup>
    ```

    Its failure already has "Try again".
  - Remove the `ActivityIndicator` imports where no use remains: Statement History has none left; Support Tickets has none left.

- [ ] **Step 4: Run both suites.**

Run: `jest src/screens/StatementHistoryScreen.test.tsx src/screens/SupportTicketsScreen.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/screens/StatementHistoryScreen.tsx mobile/src/screens/StatementHistoryScreen.test.tsx mobile/src/screens/SupportTicketsScreen.tsx mobile/src/screens/SupportTicketsScreen.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): statement history and tickets load with skeletons and can try again"
```

---

### Task 15: Bank Sync links, Gmail connection, counts-as

**Files:**
- Modify: `SettingsBankSyncScreen.tsx:96-106`
- Modify: `screens/settings/GmailConnectionSection.tsx:79-157`
- Modify: `components/CountsAsSection.tsx:41-46`
- Test: `SettingsBankSyncScreen.test.tsx`, `GmailConnectionSection.test.tsx`, `components/CountsAsSection.test.tsx`

- [ ] **Step 1: Write the failing tests.** For each of the three, add one skeleton test and one retry test.

  `SettingsBankSyncScreen.test.tsx` (mock `accountAggregatorApi.list`):

```tsx
it('shows link skeletons, not a spinner, on first load', () => {
  jest.mocked(accountAggregatorApi.list).mockReturnValue(never());
  renderScreen();
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again refetches the links', async () => {
  jest.mocked(accountAggregatorApi.list).mockRejectedValueOnce(new Error('down'));
  renderScreen();
  expect(await screen.findByText(/Couldn't load your linked bank accounts/)).toBeTruthy();
  jest.mocked(accountAggregatorApi.list).mockResolvedValueOnce([]);
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('No bank accounts linked yet.')).toBeTruthy();
});
```

  `GmailConnectionSection.test.tsx` (mock `gmailApi.status`):

```tsx
it('shows a skeleton, not a spinner, while the connection loads', () => {
  jest.mocked(gmailApi.status).mockReturnValue(never());
  renderSection();
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again refetches the connection', async () => {
  jest.mocked(gmailApi.status).mockRejectedValueOnce(new Error('down'));
  renderSection();
  expect(await screen.findByText(/Couldn.t load your Gmail connection/)).toBeTruthy();
  jest.mocked(gmailApi.status).mockReturnValueOnce(never());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expectSkeletonNotSpinner();
  expect(gmailApi.status).toHaveBeenCalledTimes(2);
});
```

  `CountsAsSection.test.tsx` (`inflow` is the file's mocked `inflowApi`):

```tsx
it('shows a skeleton, not a spinner, while it loads', () => {
  inflow.countsAs.mockReturnValue(never());
  renderSection();
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again refetches', async () => {
  inflow.countsAs.mockRejectedValueOnce(new Error('down'));
  renderSection();
  expect(await screen.findByText("Couldn't load what this payment counts as.")).toBeTruthy();
  inflow.countsAs.mockResolvedValueOnce(unresolved);
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('counts-as-section')).toBeTruthy();
});
```

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/screens/SettingsBankSyncScreen.test.tsx src/screens/settings/GmailConnectionSection.test.tsx src/components/CountsAsSection.test.tsx -t "skeleton|Try again"`
Expected: FAIL.

- [ ] **Step 3: Write it.**
  - **Bank Sync:**
    - Loading becomes:

      ```tsx
      <SkeletonGroup>
        {[0, 1].map((i) => <SkeletonListRow key={i} amount={false} />)}
      </SkeletonGroup>
      ```
    - Failure becomes:

      ```tsx
      <LoadFailed message="Couldn't load your linked bank accounts — please try again later." tone="danger" onRetry={() => void linksQ.refetch()} />
      ```
  - **Gmail:**
    - Destructure `refetch`.
    - Loading returns:

      ```tsx
      <SkeletonGroup style={styles.loader}>
        <SkeletonLines count={2} size="label" />
        <SkeletonBox size="button" style={styles.skeletonButton} />
      </SkeletonGroup>
      ```

      Add `skeletonButton: { marginTop: spacing.sm }`.
    - Failure returns:

      ```tsx
      <LoadFailed message="Couldn't load your Gmail connection — please try again later." tone="danger" onRetry={() => void refetch()} />
      ```
    - The syncing spinner at line 228 stays (keep list).
  - **CountsAs:**
    - Loading returns:

      ```tsx
      <SkeletonGroup>
        <SkeletonText size="meta" width="25%" />
        <SkeletonText size="body" width="60%" />
      </SkeletonGroup>
      ```
    - Failure returns:

      ```tsx
      <LoadFailed message="Couldn't load what this payment counts as." tone="danger" onRetry={() => void countsAsQ.refetch()} />
      ```

      The section's `small` style was 12; inline `LoadFailed` is 13, a one-point change accepted for one shared look.
  - Remove the `ActivityIndicator` imports where no use remains: Bank Sync none; Gmail keeps one; CountsAs none.

- [ ] **Step 4: Run the three suites.**

Run: `jest src/screens/SettingsBankSyncScreen.test.tsx src/screens/settings/GmailConnectionSection.test.tsx src/components/CountsAsSection.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/screens/SettingsBankSyncScreen.tsx mobile/src/screens/SettingsBankSyncScreen.test.tsx mobile/src/screens/settings/GmailConnectionSection.tsx mobile/src/screens/settings/GmailConnectionSection.test.tsx mobile/src/components/CountsAsSection.tsx mobile/src/components/CountsAsSection.test.tsx
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): bank sync, gmail and counts-as load with skeletons and can try again"
```

---

### Task 16: The four transaction pop-ups

**Files:**
- Modify: `components/TransactionSourceModal.tsx:34`, `:53-56`
- Modify: `components/TransactionExplanationModal.tsx:40`, `:69-72`
- Modify: `components/BankCorrectionModal.tsx:33`, `:76-79`
- Modify: `components/MarkTransferModal.tsx:42`, `:119-122`
- Test: the four `*.test.tsx` beside them

- [ ] **Step 1: Write the failing tests.** One skeleton test and one retry test per pop-up.

  `TransactionSourceModal.test.tsx` (mocked `transactionsApi.source`, builder `source()`):

```tsx
it('shows a skeleton, not a spinner, while it loads', () => {
  jest.mocked(transactionsApi.source).mockReturnValue(never());
  renderModal('t-1');
  expectSkeletonNotSpinner();
});

it('after a failed load, Try again refetches', async () => {
  jest.mocked(transactionsApi.source).mockRejectedValueOnce(new Error('down'));
  renderModal('t-1');
  expect(await screen.findByText(/Couldn't load where this came from/)).toBeTruthy();
  jest.mocked(transactionsApi.source).mockResolvedValueOnce(source());
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('Bank statement')).toBeTruthy();
});
```

  `TransactionExplanationModal.test.tsx` (builder `explanation()`): the same pair, with `transactionsApi.explanation`, the message `/Couldn't load this explanation/`, and on success any text the file's existing happy-path test asserts for `explanation()`.

  `BankCorrectionModal.test.tsx` (builder `txn()`, `historyEntry()`): the same pair, with `transactions.correctionHistory`, `renderModal(txn())`, and the message `"Couldn't load this correction's history."`.

  `MarkTransferModal.test.tsx` (builders `txn()`, `page()`). The search runs only after typing, so type first:

```tsx
it('shows a skeleton, not a spinner, while a search loads', async () => {
  transactions.search.mockReturnValue(never());
  renderModal(txn());
  fireEvent.changeText(screen.getByLabelText('Search transactions to pair with'), 'rent');
  expect(await screen.findByLabelText('Loading')).toBeTruthy();
  expect(screen.UNSAFE_queryAllByType(ActivityIndicator)).toHaveLength(0);
});

it('a failed search says so, not "no matching transactions", and can try again', async () => {
  transactions.search.mockRejectedValueOnce(new Error('down'));
  renderModal(txn());
  fireEvent.changeText(screen.getByLabelText('Search transactions to pair with'), 'rent');
  expect(await screen.findByText("Couldn't search your transactions.")).toBeTruthy();
  expect(screen.queryByText('No matching transactions found.')).toBeNull();
  transactions.search.mockResolvedValueOnce(page([]));
  fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('No matching transactions found.')).toBeTruthy();
});
```

  If the file debounces the keyword with fake timers, advance them the way the existing search tests do; follow the nearest existing test's steps exactly.

- [ ] **Step 2: Run them to see them fail.**

Run: `jest src/components/TransactionSourceModal.test.tsx src/components/TransactionExplanationModal.test.tsx src/components/BankCorrectionModal.test.tsx src/components/MarkTransferModal.test.tsx -t "skeleton|Try again|failed search"`
Expected: FAIL.

- [ ] **Step 3: Write it.** In each pop-up, destructure `refetch` (and `isError` for Mark as Transfer), then:
  - **Source:**
    - Loading:

      ```tsx
      <SkeletonGroup style={styles.loading}>
        <View style={styles.fieldGrid}>
          {[0, 1, 2, 3].map((i) => (
            <View key={i} style={styles.skeletonField}>
              <SkeletonText size="meta" width="60%" />
              <SkeletonText size="label" width="85%" />
            </View>
          ))}
        </View>
      </SkeletonGroup>
      ```

      Add `skeletonField: { width: '50%', paddingVertical: 6, paddingRight: spacing.sm, gap: 4 }`, the `DetailField` box.
    - Failure: `<LoadFailed message={toUserMessage(error, "Couldn't load where this came from.")} onRetry={() => void refetch()} />`.
  - **Explanation:**
    - Loading: `<SkeletonGroup style={styles.loading}><SkeletonLines count={3} size="body" /></SkeletonGroup>`.
    - Failure: `<LoadFailed message={toUserMessage(error, "Couldn't load this explanation.")} onRetry={() => void refetch()} />`.
  - **Bank Correction:**
    - Loading:

      ```tsx
      <SkeletonGroup style={styles.loading}>
        {[0, 1].map((i) => (
          <View key={i} style={styles.entry}>
            <SkeletonText size="label" width="50%" />
            <SkeletonText size="meta" width="40%" />
            <SkeletonText size="caption" width="35%" />
          </View>
        ))}
      </SkeletonGroup>
      ```
    - Failure: `<LoadFailed message="Couldn't load this correction's history." onRetry={() => void refetch()} />`.
  - **Mark as Transfer:**
    - Loading:

      ```tsx
      <SkeletonGroup style={styles.loading}>
        {[0, 1, 2].map((i) => <SkeletonListRow key={i} />)}
      </SkeletonGroup>
      ```
    - Add before the `candidates.length === 0` branch:

      ```tsx
      ) : isError && data === undefined ? (
        <LoadFailed message="Couldn't search your transactions." tone="danger" onRetry={() => void refetch()} />
      ```
  - Remove the four `ActivityIndicator` imports (no other use in these files).

- [ ] **Step 4: Run the four suites.**

Run: `jest src/components/TransactionSourceModal.test.tsx src/components/TransactionExplanationModal.test.tsx src/components/BankCorrectionModal.test.tsx src/components/MarkTransferModal.test.tsx src/screens/TransactionDetailSheet.test.tsx src/screens/LedgerScreen.test.tsx`
Expected: PASS. The last two host these pop-ups.

- [ ] **Step 5: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "feat(mobile): transaction pop-ups load with skeletons and can try again"
```

---

### Task 17: The spinner guard

**Files:**
- Create: `mobile/src/components/skeletons/actionSpinners.guard.test.ts`

The 15 sites that stay are the spec's 13 kept spinners, two of which cover two sites each.

- [ ] **Step 1: Write the test.**

```ts
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/**
 * A skeleton means "your content is coming"; a spinner means "your action is running". Every
 * ActivityIndicator left in the app is on this list, by file and count, with the action it shows.
 * Adding one is a visible change in review: a first load that reaches for a spinner again fails
 * here and should get a skeleton instead.
 */
const KEEP: Record<string, { count: number; why: string }> = {
  'components/Button.tsx': { count: 1, why: 'a button whose action is running' },
  'screens/AccountsScreen.tsx': { count: 1, why: 'an account being deleted' },
  'components/ledger/LedgerDayCard.tsx': { count: 1, why: 'a transaction being deleted' },
  'screens/PaywallScreen.tsx': { count: 1, why: 'a plan being bought' },
  'components/ImportProgressCard.tsx': { count: 1, why: 'import progress' },
  'screens/LedgerScreen.tsx': { count: 1, why: 'the next page of transactions' },
  'screens/CategoryReviewScreen.tsx': { count: 1, why: 'the refetch after a review action' },
  'screens/GmailReviewScreen.tsx': { count: 1, why: 'an approve or reject running' },
  'screens/SettingsBankSyncConfirmScreen.tsx': { count: 1, why: 'the account-link step' },
  'screens/TransactionDetailSheet.tsx': { count: 1, why: 'a transaction action row running' },
  'screens/import/ImportScreen.tsx': { count: 1, why: 'resuming an unfinished import' },
  'screens/settings/DeviceSessionsSection.tsx': { count: 1, why: 'a device being signed out' },
  'screens/settings/GmailConnectionSection.tsx': { count: 1, why: 'Gmail syncing' },
  'navigation/RootNavigator.tsx': { count: 2, why: 'the app starting up' },
};

const SRC = join(__dirname, '..', '..');
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f) ? [p] : [];
});
const spinnerCounts = Object.fromEntries(
  files(SRC)
    .map((p) => [relative(SRC, p), (readFileSync(p, 'utf8').match(/<ActivityIndicator\b/g) ?? []).length] as const)
    .filter(([, n]) => n > 0)
);

it('every spinner left in the app is an action spinner on the keep list', () => {
  expect(spinnerCounts).toEqual(Object.fromEntries(Object.entries(KEEP).map(([f, { count }]) => [f, count])));
});
```

- [ ] **Step 2: Run it.**

Run: `jest src/components/skeletons/actionSpinners.guard.test.ts`
Expected: PASS. If it fails, the diff names a file. A first-load spinner Tasks 5–16 missed gets converted (go back to the owning task's pattern). A kept site that moved is corrected on the list only if the spec's keep list names it.

- [ ] **Step 3: Show the guard can fail.** Temporarily put `<ActivityIndicator />` back into `WrappedScreen.tsx`'s loading branch, run the test, and see it FAIL naming `screens/WrappedScreen.tsx: 1`. Then restore the file:

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics diff --stat
```
Expected after restoring: only the new test file is untracked; `WrappedScreen.tsx` is unchanged.

- [ ] **Step 4: Commit.**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add mobile/src/components/skeletons/actionSpinners.guard.test.ts
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "test(mobile): spinners only where an action is running"
```

---

### Task 18: Speed pass, the background question, the whole check, the simulator pass, the pull request

**Files:**
- Possibly modify: `mobile/src/components/skeletons/Shimmer.tsx`, `Shimmer.test.tsx` (only if Step 3 finds the pulse running in the background)
- Modify: `docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md` (a "Build record (skeletons)" section)

- [ ] **Step 1: Speed, before.** Measure the base commit `2037a0cd3` in a scratch worktree (`git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics worktree add /private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad/base-2037 2037a0cd3`, then `npm ci` in its `mobile/`), using the profiling recipe from the floating-pill work:
  - Scripts in the scratchpad: `app_reload.sh`, `prof_start.sh`, `prof_finish.sh`, `prof_window.py`.
  - Metro on port 8083.
  - Simulator iPhone 17 Pro `1F831C1D-D25B-4650-9CD3-6D746FC851B5`.

  Record three runs each of Home, Transactions and Advanced Reports first load (sample counts on the JavaScript and main threads), and the frame drops while the pulse runs.

- [ ] **Step 2: Speed, after.** The same three screens, three runs each, on this branch. Compare ranges. If "after" sits outside "before" by more than the run-to-run spread, find the cause from the profile (which functions grew) before changing anything, then fix test-first and re-measure. Then remove the scratch worktree: `git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics worktree remove /private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad/base-2037`.

- [ ] **Step 3: The pulse in the background.** With a skeleton held on screen (set Network Link Conditioner to 100% loss so a first load never ends), background the app for 20 seconds under Time Profiler, then read the samples for the native animation driver on the main thread.
  - **If the samples show it ticking while backgrounded:** write this failing test in `Shimmer.test.tsx`:

```tsx
it('stops the pulse while the app is in the background and resumes it on return', async () => {
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
  const listeners: ((s: string) => void)[] = [];
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, fn) => {
    listeners.push(fn as (s: string) => void);
    return { remove: jest.fn() } as never;
  });
  const loop = jest.spyOn(Animated, 'loop');
  renderShimmer();
  await act(async () => {});
  const first = loop.mock.results[0].value as Animated.CompositeAnimation;
  const stop = jest.spyOn(first, 'stop');

  act(() => listeners.forEach((fn) => fn('background')));
  expect(stop).toHaveBeenCalled();

  act(() => listeners.forEach((fn) => fn('active')));
  await act(async () => {});
  expect(loop).toHaveBeenCalledTimes(2);
});
```

    Watch it fail. Then, in `Shimmer.tsx`, subscribe once at module level the first time `acquireSharedLoop` runs:

```ts
let appStateSub: { remove: () => void } | null = null;

function watchAppState() {
  if (appStateSub) return;
  appStateSub = AppState.addEventListener('change', (state) => {
    if (state === 'active') {
      if (activeShimmerCount > 0 && !sharedLoop) startPulseUnlessReducedMotion();
    } else {
      sharedLoop?.stop();
      sharedLoop = null;
    }
  });
}
```

    Call `watchAppState()` inside `acquireSharedLoop` when the count goes 0 → 1, and in `releaseSharedLoop` when it returns to 0 do `appStateSub?.remove(); appStateSub = null;`. Run `npx jest src/components/skeletons`; expected PASS. Re-measure the background window and record both numbers.
  - **If the samples show it idle:** record that, with the sample counts, and change nothing.

- [ ] **Step 4: Gestures.** With a skeleton held on screen (100% loss), confirm on the simulator that the screen scrolls and the tab pill and back button respond. Record yes or no per screen in the build record.

- [ ] **Step 5: The whole mobile check under Node 22.** With `/Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile` as the working directory, run each and read each tail:

```bash
NODE_OPTIONS=--experimental-vm-modules npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile/node_modules/.bin/jest --rootDir /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile --forceExit
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile/node_modules/.bin/tsc --noEmit -p /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile/node_modules/.bin/expo lint --max-warnings 0
```
```bash
npx -y node@22 /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics/mobile/node_modules/.bin/expo export --platform android --output-dir /private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad/android-export
```
  Expected: all green, and the export writes a bundle. Then the repository guards, from the worktree root: `npm run` lists the guard scripts; run each one the CI workflow runs for mobile (read `.github/workflows/ci.yml` for the exact list).

- [ ] **Step 6: The simulator pass,** signed in to the owner's account, read only, with Network Link Conditioner on "3G" (and 100% loss for failures). For each of the 13 screens and 21 sections:
  - the skeleton on a slowed first load;
  - whether anything jumps when the content lands;
  - light and dark;
  - the largest accessibility text size;
  - Reduce Motion holding the pulse still;
  - "Try again" with the network off, then on.

  Also measure the fill: for one skeleton per theme, take a screenshot of a placeholder over a card and over the backdrop, then read the pixel colours (as for `glassContrast.test.ts`) and compute the contrast ratio. Record these numbers; there is no floor, but the placeholder must be visible. **No screenshot of real data goes into the pull request.** Record findings as text only.

- [ ] **Step 6b: Real iPhone pass, done by me.** This needs the owner to connect the iPhone by USB and sign in; ask when this step is reached, and do not block the pull request on it.
  - Install the dev-client build of this branch on the phone.
  - Walk each converted screen's first load right after a fresh install. A screen whose data is already cached shows content at once; record it as "cached, first load not seen" rather than forcing it.
  - Look for content jumping when it lands.

  Read only; record as text. Android is the owner's, after merge (spec, Platforms).

- [ ] **Step 7: Write the build record** into the spec under a new heading "Build record (skeletons, 2026-10-…)":
  - speed before and after;
  - the background result;
  - gestures;
  - the fill measurements;
  - the simulator pass screen by screen;
  - what could not be checked and why.

  Commit:

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics add docs/superpowers/specs/2026-10-15-mobile-skeletons-and-haptics-design.md
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics commit -m "docs(mobile): skeletons build record"
```

- [ ] **Step 8: Self-review the diff** (`git -C … diff origin/main...HEAD`) for anything the tests do not cover, fix test-first, re-run Step 5. Then push and open the pull request. The repository is public, so the pull request is opened without asking.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics fetch origin
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics log --oneline origin/main..HEAD
```
```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-skeletons-haptics push -u origin feature/mobile-skeletons-haptics
```
  Then `gh pr create` with a body that:
  - summarises what changed, the 8 retry screens and the 11 sections that now admit failure, and the speed numbers;
  - states it ships in 1.1.0 and must not be sent over the air to 1.0.0;
  - ends with the Claude Code line.

  Bind the pull request with the ccd_pr tools.
