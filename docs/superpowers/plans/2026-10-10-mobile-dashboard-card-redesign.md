# Mobile Dashboard Card and Grid Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the look of every card and grid on the mobile Home (Dashboard) screen to match the approved Figma direction, without changing any data, copy, navigation or accessibility label.

**Architecture:** A small set of shared dashboard primitives (type ramp, card, section header, delta chip, progress bar, icon well) is added first, then each existing dashboard component is restyled on top of them, then `DashboardScreen.tsx` is re-laid-out. No backend, API, navigation or native-module change. Work ships as two pull requests: the "story" layer (header through Quick Actions), then the "operational" layer (Upcoming through Getting Started, plus empty and loading states).

**Tech Stack:** Expo SDK 57, React Native 0.86.3, TypeScript 6, `react-native-svg` 15.15.5, `react-native-reanimated` 4.7.1, `@expo/vector-icons` (Ionicons), Jest 30 with `jest-expo` and `@testing-library/react-native` 13.3.3.

**Spec:** Figma file `https://www.figma.com/design/t3Bapfm0kCVpfbSIfMr5U1` (frames "Dashboard — Redesign (Light)" and "Dashboard — States (Light)"), approved by Sid on 2026-10-10. The Figma file lives in Sid's drafts and has no dark mode, so the "Design reference" section below is the binding copy of every value this plan uses.

## Design reference (binding)

Values measured from the approved Figma frame. Token names are the ones this plan adds or already exist in `mobile/src/theme/palette.ts`.

| Thing | Value |
|---|---|
| Screen side padding | 20 (`spacing.ml`, new) |
| Gap between cards | 16 (`spacing.md`) |
| Gap inside grids and tile rows | 12 (`spacing.ms`, new) |
| Card radius | 20 (`radius.xxl`, new) |
| Hero radius | 28 (`radius.hero`, new) |
| Card padding | 20 regular, 16 compact (tiles) |
| Screen title | Manrope ExtraBold 26/32, tracking -0.5 |
| Card title | Manrope Bold 16/22 |
| Big number | Manrope ExtraBold 26/30 |
| Tile number | Manrope Bold 20/24 |
| Row number | Manrope Bold 15/20 |
| Eyebrow label | Inter SemiBold 11/14, tracking 0.9, uppercase |
| Body | Inter Regular 14/20 and 12/17 |
| Hero score | Manrope ExtraBold 56/58 |
| Hero gauge | 116 square, 270 degree sweep starting bottom-left, stroke 9, round caps |
| Progress bars | 4 high in factor tiles, 6 high elsewhere, fully rounded |
| Icon well | 40 square, radius 12 (or round), tinted fill |
| Quick action tile | min height 104, three per row |
| Goal tile | 164 wide |
| Factor tile | 200 wide (kept, because the suggestion sentence must stay) |

Section order on the screen after this plan:

1. Brand row, greeting, search button
2. Banners (statement refresh, statement gap, review queue, limited history)
3. Health hero, health factor tiles
4. Balance card (full width)
5. Month section: header plus KPI tile grid, then the "money not counted" banner
6. Cash flow trend card (full width, only when monthly data exists)
7. Spending by Category
8. Goals
9. Financial Note
10. Recent Transactions
11. Quick Actions
12. Upcoming, Categorization Confidence, Next Actions, Detected Issues, Cash Flow, Budget Progress, Insights, Your Journey, Getting Started

Deliberate differences from the Figma frame, each for a stated reason:

- **The hero and the Financial Note are glass.** The Figma frame drew the hero as a solid dark slab and the note as a solid brass card. Sid's instruction on 2026-10-10: make everything glass, with the hero keeping its colour ("option B"). The hero is `primaryDark`-tinted glass and the note is brass-tinted glass.
- **Factor tiles keep the suggestion sentence** and stay 200 wide. The Figma tile dropped it; `HealthFactorsRow.test.tsx` and the product both rely on it.
- **Spending donut stays stacked** (donut above, legend below, rupee amounts, tap-through). The Figma frame put the legend beside the donut; with `DONUT_SIZE = 160` that leaves 133 px on a 393 wide phone and 100 px on a 360 wide phone, too narrow for a category name plus an amount.
- **Chart colours do not change.** `CHART_PALETTE` and the income/expense line colours stay as they are.
- **Getting Started stays collapsible** as today. The Figma frame showed it expanded.
- **Tab bar is not touched.** It is outside "dashboard cards and grids".
- **Icons are real Ionicons.** The Figma icons were stand-ins.
- **Delta arrows stay `▲` / `▼`** (the Figma frame used `↑`), so screen-reader and test copy keeps one glyph.

## Glass UI

Sid's instruction (2026-10-10): the redesign uses the app's glass UI. Checked against `origin/main` at `d041270b4` and against every surface this plan draws.

How glass works in this app (`mobile/src/components/GlassSurface.tsx`): the screen sits on `GlassScreen`'s mesh backdrop, and a surface is a `GlassSurface`. A `panel` (the default) is Liquid Glass on iOS 26, `BlurView` on iOS 16.4 to 25, and a translucent tinted fill on Android. A `row` is the tinted fill on every platform. With Reduce Transparency on, every surface is a solid card. The plan does not change any of that.

| Surface in the redesign | What it is |
|---|---|
| Balance card, cash flow trend, Spending, Recent Transactions, Upcoming, Categorization Confidence, Next Actions, Detected Issues, Cash Flow, Budget Progress, Insights, Your Journey, Getting Started | glass panel (`DashboardCard`) |
| Health factor tiles, goal tiles | glass panel (`DashboardCard`, compact) |
| KPI tiles, quick action tiles, the loading-state KPI tiles | glass panel (`DashboardCard`, compact). The first draft made these tinted `row` surfaces; changed to real glass on Sid's instruction |
| Search button | glass panel (`GlassSurface`). First draft: `row`; changed the same way |
| Banners (statement gap, review queue, limited history, money not counted) | glass panel with their existing colour tint (`Card`), unchanged |
| Tab bar | glass panel, untouched by this plan |
| Health hero | tinted glass: a `GlassSurface` panel tinted with `primaryDark` at 0.86, so it keeps its dark (light theme) or cream (dark theme) look. Sid chose this ("option B") on 2026-10-10 over a solid slab and over plain glass. Opaque `primaryDark` under Reduce Transparency |
| Financial Note | brass glass: a `GlassSurface` panel tinted with the `brassBg` wash at the glass alpha, with a brass edge. On `main` it was a solid `primaryLight` card; changed on the same instruction |
| Rows inside a card (transactions, upcoming, budgets, checklist), chips, icon wells, bars | plain content on the card's glass; no glass nested inside glass |

Nothing in the redesign paints an opaque `c.bg` or `c.card` background; the old quick action cell's opaque fill (the screen's one `glass-exempt` marker) is removed. `glassMigration.test.ts` enforces that across `mobile/src`.

With the hero and the note, no surface on the dashboard is a solid card unless Reduce Transparency is on.

What is not established until a device runs it: that eleven more native glass views (thirteen added, two removed) scroll as smoothly as `main`. Task 12 Step 3 measures it and puts the decision to Sid if it does not.

## Dark theme audit

The Figma file has one variable mode, so there is no dark frame. Dark is built from the palette's `dark` object, and every colour pair this plan draws was measured on 2026-10-10 against `origin/main` at `d041270b4`: the palette values in `mobile/src/theme/palette.ts`, and for glass surfaces every distinct pixel of the committed backdrop `mobile/assets/glass/mesh-dark.png` (284 distinct pixels) and `mesh-light.png` (237), composited the way `glassContrast.test.ts` does it (tint at 0.72 over the pixel). "Glass" below is the worst pixel; "solid" is the Reduce Transparency and Android card.

| Pair | Dark | Light | Floor |
|---|---|---|---|
| Hero text on tinted glass, worst pixel / solid | 9.30 / 12.25 | 11.96 / 17.93 | 4.5 |
| Hero soft text (0.72) on tinted glass, worst pixel / solid | 4.99 / 5.85 | 7.07 / 9.65 | 4.5 |
| Hero gauge on tinted glass (worst pixel), Excellent / Good / Fair / Needs Attention | 3.70 / 8.04 / 3.68 / 4.31 | 5.25 / 10.61 / 7.16 / 4.32 | 3 |
| Hero delta pill text on its wash | 7.83 | 5.37 | 4.5 |
| Hero "Continue Setup" text on its button | 12.25 | 17.93 | 4.5 |
| Delta chip, good / bad, text on wash | 8.20 / 9.85 | 6.49 / 6.80 | 4.5 |
| Icon on well, neutral / success / warning / brass | 9.18 / 8.20 / 11.22 / 7.83 | 13.93 / 6.49 / 6.37 / 5.37 | 3 |
| Income amount (`successInk`), glass / solid | 5.93 / 6.31 | 6.54 / 7.13 | 4.5 |
| Expense amount (`dangerInk`), glass / solid | 7.12 / 7.57 | 7.62 / 8.31 | 4.5 |
| Bar or line in `success`, glass / solid | 5.93 / 6.31 | 3.02 / 3.30 | 3 |
| Bar in `primary`, glass / solid | 12.00 / 12.75 | 13.17 / 14.37 | 3 |
| Bar in `warning`, glass / solid | 8.10 / 8.61 | **2.92** / 3.19 | 3 |
| Bar in `danger`, glass / solid | 4.89 / 5.19 | 4.43 / 4.83 | 3 |
| Checklist tick on `success` | 7.87 | 3.30 | 3 |
| Financial Note, title / body / gain on brass glass | 14.36 / 8.03 / 7.04 | 14.45 / 6.14 / 5.14 | 4.5 |
| Button text on `primary` | 15.92 | 14.37 | 4.5 |
| "+N" avatar text on `border` | 7.91 | 14.81 | 4.5 |

Every text token on a glass card (`ink`, `muted`, `mutedInk`, `primary`, `brassInk`, `successInk`, `warningInk`, `dangerInk`) is already pinned at every pixel by `glassContrast.test.ts` on both themes; this plan uses no other text token on glass.

What the audit changed in this plan:

- **Neutral icon well.** The first draft filled it with `primaryLight`. In dark that is `#26241F` on a `#262A33` card: 1.01:1 on glass, 1.08:1 solid. The well was not there. It is now a wash of the theme's ink at 0.08: 1.25:1 dark, 1.17:1 light, identical on every pixel because it is composited over the card. Pinned by tests in Task 2.
- **Insight bullet.** `brass` measured 2.97:1 on a light glass card. It is now `brassInk`: 5.82:1 at the worst light glass pixel and 6.35:1 solid; in dark it is the same value as `brass`, 5.65:1 and 6.01:1.
- **Checklist tick.** Was "not measured"; now measured, above.
- **Dark tests.** Task 2 renders the chip, bar, card and icon well in the dark theme. Task 3 measures the hero's tinted glass on both themes, and Task 8 measures the brass glass on both.
- **Dark screenshots.** Task 12 and Task 17 capture dark at default size, large text, Reduce Transparency and on the small phone.

What stays as it is, with the number, so nobody reads it as checked-and-fine:

- **`warning` bar on a light glass card is 2.92:1 at the single worst backdrop pixel** (3.19:1 solid). This is a light-theme figure, it is on `main` today (`healthBarColor` in the factor card), and the bar is never the only carrier: the percentage and the word "Fair" sit beside it in `ink` and `warningInk`. Changing `warning` or `healthBarColor` moves colours across the whole app, so it is not done here. Raise it with Sid as its own change.
- **Card against backdrop in dark** is 1.00 to 1.18:1 on glass and 1.25:1 solid. That is the shipped glass system for every card in the app, not something this plan introduces; the edge hairline and the blur carry the separation. The KPI and quick action tiles are new glass panels on that same system; Task 12 Step 3 confirms them on device.
- **The hero is cream glass in dark** (`primaryDark` = `#DAD5C9` at 0.86), the brightest object on a dark screen: 6.24:1 against the backdrop at its weakest pixel. Sid saw this in the option image and chose it. The dark-theme margins are the thinnest in the plan (soft text 4.99:1, two gauge colours at about 3.7:1), which is why the alpha is pinned by a test.
- **The card shadow** (`#000000` at 0.07) does nothing visible on a dark backdrop. Harmless; the edge does the work.

Not measurable from tokens, so it stays a device check: how the native blur (Liquid Glass on iOS 26, `BlurView` below it) renders over the dark backdrop. The inner tint layer keeps text at the measured floor either way.

## Global Constraints

- **Copy, `testID`s and accessibility labels do not change.** Uppercase eyebrow labels are produced with `textTransform: 'uppercase'`, never by changing the string. `mobile/src/screens/DashboardScreen.test.tsx` (1,700 lines) is the regression net and must pass with only the edits this plan names.
- **No hardcoded period wording.** `scripts/check-reporting-period-labels.py` scans `DashboardScreen.tsx`, `useDashboardKpis.ts`, `LedgerSnapshotCard.tsx`, `CashFlowMiniCard.tsx` and `HealthHero.tsx` for literal "this month" / "vs last month" / "last month". Period text must come from the `title` and `deltaLabel` values those files already receive. The KPI grid therefore stays inside `LedgerSnapshotCard.tsx`.
- **Glass source guard** (`mobile/src/theme/glassMigration.test.ts`): no unmarked `backgroundColor: c.bg` or `backgroundColor: c.card`; no `color: … c.danger` or `color: … c.success` in a style object. Text uses `dangerInk` / `successInk`.
- **Text tokens on glass** are limited to the set `glassContrast.test.ts` measures: `ink`, `muted`, `mutedInk`, `primary`, `brassInk`, `successInk`, `warningInk`, `dangerInk`.
- **Fonts:** never set `fontWeight` next to a `fonts.*` family (see `mobile/src/theme/fonts.ts`).
- **Touch targets:** every pressable is at least 44 by 44 points.
- **No new native module and no `app.config.ts` version change.** This is JavaScript only.
- **Release rule:** `runtimeVersion.policy` is `appVersion` and `main` is at 1.1.0. This work may ship in a 1.1.0 store build or as an over-the-air update to 1.1.0 binaries. It must never be published to the 1.0.0 runtime. When and how it ships is Sid's decision; the plan does not publish anything.
- **Node 22 for every check.** CI runs Node 22; the machine default is Node 26. A suite that passes on 26 is not evidence.
- **Repository rules** (`CLAUDE.md`): the primary checkout is read-only, work happens in a worktree, commit messages carry no `Co-Authored-By` trailer, every claim of "verified" is backed by a command that was run, and review of your own work is not delegated to sub-agents.
- **Expo:** `mobile/AGENTS.md` requires reading `https://docs.expo.dev/versions/v57.0.0/` before using an Expo API you have not used in this repo. This plan uses none beyond what the touched files already import.

## Review Focus

Inputs the design does not show that are most likely to bite a real person. Each has a pinned test in the task named.

1. **Very large amounts in a half-width tile** (₹1 crore and above, 11+ characters). Expected: the KPI grid falls back to one full-width column instead of clipping the number. Test in Task 6.
2. **Large Dynamic Type.** Expected: KPI grid goes to one column, names wrap to two lines, nothing overlaps. Tests in Task 6 and the existing "large Dynamic Type support" block.
3. **The hero's tinted glass, both themes.** The hero is `primaryDark` glass, so its surface is near-black in light and cream in dark, and the backdrop shows through it differently at every pixel. Expected: all hero text clears 4.5:1 and every gauge colour clears 3:1 on both themes, on every backdrop pixel and on the opaque fallback. Test in Task 3.
4. **Out-of-range progress values:** a budget at 150%, a goal at 125%, a score of 0 or 100, a `NaN` from a zero denominator. Expected: bars and arcs stay inside their track and the label still shows the real figure. Tests in Tasks 2 and 3.
5. **Reduce Transparency and Android**, where every glass surface is a solid card. Expected: tiles still read as separate cards. Test in Task 2, visual check in Task 12.

## File Structure

New files:

| File | Responsibility |
|---|---|
| `mobile/src/theme/typography.ts` | The dashboard type ramp as `TextStyle` objects |
| `mobile/src/components/dashboard/DeltaChip.tsx` | Up/down percentage pill |
| `mobile/src/components/dashboard/ProgressBar.tsx` | Clamped rounded progress bar |
| `mobile/src/components/dashboard/DashboardSectionHeader.tsx` | Title row with optional caption, action link or accessory |
| `mobile/src/components/dashboard/IconWell.tsx` | Tinted square or round icon holder |
| `mobile/src/components/dashboard/DashboardEmptyState.tsx` | Icon, message and optional action (pull request 2) |
| `mobile/src/lib/heroGauge.ts` | Gauge geometry (arc path for a score) |
| `mobile/src/lib/heroTones.ts` | Colours used on the hero surface, per theme |

Modified files:

| File | Change |
|---|---|
| `mobile/src/theme/palette.ts`, `mobile/src/theme/index.ts` | New radius and spacing steps; export `typography` |
| `mobile/src/lib/health.ts` | `healthLabelInk` (text-safe tier colour) |
| `mobile/src/components/dashboard/DashboardCard.tsx` | Radius 20, `padding` and `variant` props |
| `mobile/src/components/dashboard/HealthHero.tsx` | New layout, both states |
| `mobile/src/components/dashboard/FinancialHealthFactorCard.tsx`, `HealthFactorsRow.tsx` | Tile restyle |
| `mobile/src/components/dashboard/AccountsCard.tsx` | Full-width balance card |
| `mobile/src/components/dashboard/LedgerSnapshotCard.tsx` | KPI tile grid |
| `mobile/src/components/dashboard/CashFlowMiniCard.tsx` | Full-width area chart card |
| `mobile/src/components/dashboard/GoalsRow.tsx`, `FinancialNoteCard.tsx`, `JourneyWidget.tsx` | Restyle |
| `mobile/src/onboarding/ChecklistWidget.tsx` | Restyle |
| `mobile/src/components/skeletons/Skeletons.tsx` | `SkeletonKpiGrid` |
| `mobile/src/screens/DashboardScreen.tsx` | Layout, section order, section restyles |
| `mobile/.maestro/flows/dashboard.yaml` | Comment update only |

---

# Pull request 1: foundation and story layer

### Task 0: Worktree and baseline

**Files:** none changed.

- [ ] **Step 1: Create the worktree**

Use the `EnterWorktree` tool with name `mobile-dashboard-cards`. If it is unavailable:

```bash
git -C /Users/sid/Downloads/finora fetch origin
```

```bash
git -C /Users/sid/Downloads/finora worktree add ../finora-mobile-dashboard-cards -b feature/mobile-dashboard-cards origin/main
```

- [ ] **Step 2: Verify where you are**

Run each from the worktree's absolute path and read the output before continuing:

```bash
pwd
```

```bash
git branch --show-current
```

```bash
git status --short
```

Expected: the worktree path, the feature branch, an empty status.

- [ ] **Step 3: Put Node 22 first on the path**

```bash
npx --yes node@22 -p "process.execPath"
```

Take the directory of the printed path and run `export PATH="<that directory>:$PATH"` in the same shell. Confirm:

```bash
node -v
```

Expected: `v22.x`.

- [ ] **Step 4: Install and record the baseline**

From `<worktree>/mobile`:

```bash
npm ci --legacy-peer-deps
```

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit
```

```bash
npm run typecheck
```

```bash
npm run lint
```

Expected: all green. Write down the suite and test counts printed by Jest. If anything fails before you have changed a file, stop and report it; do not fix unrelated failures inside this branch.

- [ ] **Step 5: Copy this plan into the branch and commit**

Copy this file to `<worktree>/docs/superpowers/plans/2026-10-10-mobile-dashboard-card-redesign.md`.

```bash
git add docs/superpowers/plans/2026-10-10-mobile-dashboard-card-redesign.md
git commit -m "docs(mobile): plan for the dashboard card and grid redesign"
```

---

### Task 1: Tokens and type ramp

**Files:**
- Modify: `mobile/src/theme/palette.ts` (the `radius` and `spacing` objects at the bottom)
- Create: `mobile/src/theme/typography.ts`
- Modify: `mobile/src/theme/index.ts`
- Test: `mobile/src/theme/typography.test.ts`

**Interfaces:**
- Produces: `radius.xxl = 20`, `radius.hero = 28`, `spacing.ms = 12`, `spacing.ml = 20`, and `typography` with keys `display`, `screenTitle`, `cardTitle`, `numberL`, `numberM`, `numberS`, `labelM`, `labelS`, `bodyM`, `bodyS`, `caption`, `eyebrow`. All exported from `mobile/src/theme` (`import { radius, spacing, typography } from '../../theme'`).

- [ ] **Step 1: Write the failing test**

`mobile/src/theme/typography.test.ts`:

```ts
import { fonts } from './fonts';
import { radius, spacing } from './palette';
import { typography } from './typography';

describe('dashboard type ramp', () => {
  it('uses only font files the app loads, and never a fontWeight on top of one', () => {
    const loaded = new Set<string>(Object.values(fonts));
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, loaded: loaded.has(style.fontFamily) }).toEqual({ name, loaded: true });
      expect({ name, hasWeight: 'fontWeight' in style }).toEqual({ name, hasWeight: false });
    }
  });

  it('gives every step a line height at least as tall as its font size', () => {
    for (const [name, style] of Object.entries(typography)) {
      expect({ name, ok: style.lineHeight >= style.fontSize }).toEqual({ name, ok: true });
    }
  });

  it('keeps the eyebrow uppercase through styling, so the underlying string is untouched', () => {
    expect(typography.eyebrow.textTransform).toBe('uppercase');
  });
});

describe('redesign spacing and radius steps', () => {
  it('adds the steps the design uses without moving the existing ones', () => {
    expect(spacing).toEqual({ xs: 4, sm: 8, ms: 12, md: 16, ml: 20, lg: 24, xl: 32 });
    expect(radius).toEqual({ md: 8, lg: 12, xl: 16, xxl: 20, hero: 28 });
  });
});
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/theme/typography.test.ts
```

Expected: FAIL, cannot find module `./typography`.

- [ ] **Step 3: Implement**

In `mobile/src/theme/palette.ts` replace the two objects at the bottom:

```ts
export const radius = {
  md: 8,
  lg: 12,
  xl: 16,
  // Dashboard card redesign (2026-10-10): cards and the health hero.
  xxl: 20,
  hero: 28,
};

export const spacing = {
  xs: 4,
  sm: 8,
  // Dashboard card redesign (2026-10-10): the gap inside tile grids.
  ms: 12,
  md: 16,
  // Dashboard card redesign (2026-10-10): screen side padding and regular card padding.
  ml: 20,
  lg: 24,
  xl: 32,
};
```

Create `mobile/src/theme/typography.ts`:

```ts
import type { TextStyle } from 'react-native';
import { fonts } from './fonts';

/**
 * The dashboard's type ramp. Each entry names a role, not a size, so a card says what a piece of
 * text IS and the ramp decides how it looks.
 *
 * No fontWeight anywhere: every family name in fonts.ts is one specific weight's font file, and a
 * fontWeight beside it makes React Native synthesise a second bolding on top (see fonts.ts).
 */
export const typography = {
  display: { fontFamily: fonts.display, fontSize: 56, lineHeight: 58, letterSpacing: -2 },
  screenTitle: { fontFamily: fonts.display, fontSize: 26, lineHeight: 32, letterSpacing: -0.5 },
  cardTitle: { fontFamily: fonts.displayBold, fontSize: 16, lineHeight: 22, letterSpacing: -0.2 },
  numberL: { fontFamily: fonts.display, fontSize: 26, lineHeight: 30, letterSpacing: -0.6 },
  numberM: { fontFamily: fonts.displayBold, fontSize: 20, lineHeight: 24, letterSpacing: -0.4 },
  numberS: { fontFamily: fonts.displayBold, fontSize: 15, lineHeight: 20, letterSpacing: -0.2 },
  labelM: { fontFamily: fonts.bodySemibold, fontSize: 14, lineHeight: 20 },
  labelS: { fontFamily: fonts.bodySemibold, fontSize: 12, lineHeight: 16 },
  bodyM: { fontFamily: fonts.body, fontSize: 14, lineHeight: 20 },
  bodyS: { fontFamily: fonts.body, fontSize: 12, lineHeight: 17 },
  caption: { fontFamily: fonts.bodyMedium, fontSize: 11, lineHeight: 14 },
  // Uppercase by style, never by changing the string: tests and screen readers keep the real text.
  eyebrow: {
    fontFamily: fonts.bodySemibold, fontSize: 11, lineHeight: 14, letterSpacing: 0.9, textTransform: 'uppercase',
  },
} as const satisfies Record<string, TextStyle & { fontFamily: string; fontSize: number; lineHeight: number }>;
```

In `mobile/src/theme/index.ts` add one line after the `fonts` export:

```ts
export { typography } from './typography';
```

- [ ] **Step 4: Run the theme tests**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/theme
```

Expected: PASS for `typography.test.ts`, `palette.test.ts`, `glassContrast.test.ts`, `glassMigration.test.ts`.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/theme/palette.ts mobile/src/theme/typography.ts mobile/src/theme/typography.test.ts mobile/src/theme/index.ts
git commit -m "feat(mobile): dashboard type ramp and the spacing and radius steps the card redesign uses"
```

---

### Task 2: Shared primitives

**Files:**
- Modify: `mobile/src/components/dashboard/DashboardCard.tsx`, `mobile/src/components/dashboard/DashboardCard.test.tsx`
- Create: `mobile/src/components/dashboard/DeltaChip.tsx`, `ProgressBar.tsx`, `DashboardSectionHeader.tsx`, `IconWell.tsx`
- Test: `mobile/src/components/dashboard/primitives.test.tsx`

**Interfaces:**
- Consumes: `radius`, `spacing`, `typography`, `useTheme` from `../../theme`; `GlassSurface`, `GlassVariant` from `../GlassSurface`.
- Produces:
  - `DashboardCard({ children, style?, testID?, variant?: GlassVariant, padding?: 'regular' | 'compact' })`
  - `DeltaChip({ delta: number, good: boolean, testID?: string })` renders `▲ 4.2%` or `▼ 4.2%`
  - `ProgressBar({ percent: number, color: string, trackColor?: string, height?: number, testID?: string })`; the fill carries `testID + '-fill'`
  - `DashboardSectionHeader({ title: string, caption?: string, actionLabel?: string, onAction?: () => void, showChevron?: boolean, accessory?: ReactNode })`
  - `IconWell({ name: IoniconName, tone?: 'neutral' | 'success' | 'warning' | 'brass', round?: boolean, size?: number, testID?: string })`, and `NEUTRAL_WASH` (the alpha of the neutral well's ink wash, `0.08`)

- [ ] **Step 1: Write the failing tests**

`mobile/src/components/dashboard/primitives.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { DashboardSectionHeader } from './DashboardSectionHeader';
import { DeltaChip } from './DeltaChip';
import { IconWell } from './IconWell';
import { ProgressBar } from './ProgressBar';
import { light } from '../../theme/palette';
import { ThemeProvider } from '../../theme';

const flat = (id: string) => StyleSheet.flatten(screen.getByTestId(id).props.style);

describe('DashboardCard', () => {
  it('uses the redesign radius and regular padding by default', () => {
    render(<DashboardCard testID="card"><Text>x</Text></DashboardCard>);
    expect(flat('card').borderRadius).toBe(20);
    expect(flat('card').padding).toBe(20);
  });

  it('offers a compact padding for tiles', () => {
    render(<DashboardCard testID="card" padding="compact"><Text>x</Text></DashboardCard>);
    expect(flat('card').padding).toBe(16);
  });

  // Reduce Transparency is unknown in tests, so GlassSurface renders its solid fallback: the
  // same path Android and Reduce Transparency users get. A tile must still have an edge there.
  it('keeps a visible edge on the solid fallback', () => {
    render(<DashboardCard testID="card" variant="row"><Text>x</Text></DashboardCard>);
    expect(flat('card').borderWidth).toBe(StyleSheet.hairlineWidth);
    expect(flat('card').borderColor).toBe(light.border);
    expect(flat('card').backgroundColor).toBe(light.card);
  });
});

describe('DeltaChip', () => {
  it('points up for a rise and down for a fall, with one decimal', () => {
    const { rerender } = render(<ThemeProvider><DeltaChip delta={4.25} good /></ThemeProvider>);
    expect(screen.getByText('▲ 4.3%')).toBeTruthy();
    rerender(<ThemeProvider><DeltaChip delta={-3.2} good={false} /></ThemeProvider>);
    expect(screen.getByText('▼ 3.2%')).toBeTruthy();
  });

  it('colours by whether the move is good, not by its direction (a rise in expenses is bad)', () => {
    render(<ThemeProvider><DeltaChip delta={8.1} good={false} testID="chip" /></ThemeProvider>);
    expect(flat('chip').backgroundColor).toBe(light.dangerBg);
    expect(screen.getByText('▲ 8.1%')).toHaveStyle({ color: light.dangerInk });
  });

  it('treats zero as a rise of 0.0%, matching the wording the cards used before', () => {
    render(<ThemeProvider><DeltaChip delta={0} good /></ThemeProvider>);
    expect(screen.getByText('▲ 0.0%')).toBeTruthy();
  });
});

describe('ProgressBar', () => {
  const width = (pct: number) => {
    render(<ThemeProvider><ProgressBar percent={pct} color="#000000" testID="bar" /></ThemeProvider>);
    return flat('bar-fill').width;
  };
  it('draws the given percentage', () => { expect(width(68)).toBe('68%'); });
  it('never draws past its track for an over-budget value', () => { expect(width(150)).toBe('100%'); });
  it('never draws a negative width', () => { expect(width(-5)).toBe('0%'); });
  it('draws nothing for a value that is not a number', () => { expect(width(Number.NaN)).toBe('0%'); });
});

describe('DashboardSectionHeader', () => {
  it('shows the title as a heading', () => {
    render(<ThemeProvider><DashboardSectionHeader title="Goals" /></ThemeProvider>);
    expect(screen.getByRole('header', { name: 'Goals' })).toBeTruthy();
  });

  it('shows a plain caption when there is nothing to press', () => {
    render(<ThemeProvider><DashboardSectionHeader title="June 2026" caption="vs the month before Jun 26" /></ThemeProvider>);
    expect(screen.getByText('vs the month before Jun 26')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('offers an action with a 44 point target', () => {
    const onAction = jest.fn();
    render(<ThemeProvider><DashboardSectionHeader title="Budget Progress" actionLabel="Manage Budgets" onAction={onAction} /></ThemeProvider>);
    const button = screen.getByLabelText('Manage Budgets');
    expect(StyleSheet.flatten(button.props.style).minHeight).toBe(44);
    fireEvent.press(button);
    expect(onAction).toHaveBeenCalledTimes(1);
  });
});

describe('IconWell', () => {
  it('tints by tone', () => {
    render(<ThemeProvider><IconWell name="arrow-down-outline" tone="success" testID="well" /></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(light.successBg);
  });

  it('washes the neutral well with the light theme ink', () => {
    render(<ThemeProvider><IconWell name="card-outline" testID="well" /></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(withAlpha(light.ink, NEUTRAL_WASH));
    expect(flat('well').borderColor).toBe(light.border);
  });

  it('follows the dark theme', () => {
    render(<ThemeProvider><ForceDarkTheme><IconWell name="card-outline" testID="well" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(withAlpha(dark.ink, NEUTRAL_WASH));
    expect(flat('well').borderColor).toBe(dark.border);
  });

  // The well this replaced (primaryLight) measured 1.01:1 on a dark card: it was not there.
  // 1.15 is below both measured values (1.17 light, 1.25 dark), so this fails if the wash is
  // weakened, and 3:1 is the floor for the icon drawn on it.
  it.each([['light', light], ['dark', dark]] as const)('stays visible on a %s card, with a readable icon', (_name, p) => {
    const card = hex(p.card);
    const well = over(hex(p.ink), NEUTRAL_WASH, card);
    expect(ratio(well, card)).toBeGreaterThanOrEqual(1.15);
    expect(ratio(hex(p.ink), well)).toBeGreaterThanOrEqual(3);
  });
});

describe('dark theme', () => {
  it('draws a delta chip from the dark washes and inks', () => {
    render(<ThemeProvider><ForceDarkTheme><DeltaChip delta={-2} good={false} testID="chip" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('chip').backgroundColor).toBe(dark.dangerBg);
    expect(screen.getByText('▼ 2.0%')).toHaveStyle({ color: dark.dangerInk });
  });

  it('draws a progress track from the dark border', () => {
    render(<ThemeProvider><ForceDarkTheme><ProgressBar percent={40} color={dark.primary} testID="bar" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('bar').backgroundColor).toBe(dark.border);
  });

  it('gives a card the dark solid fallback, edge included', () => {
    render(<ThemeProvider><ForceDarkTheme><DashboardCard testID="card"><Text>x</Text></DashboardCard></ForceDarkTheme></ThemeProvider>);
    expect(flat('card').backgroundColor).toBe(dark.card);
    expect(flat('card').borderColor).toBe(dark.border);
  });
});
```

The dark-theme tests use the same switch a person taps, the way `AccountsCard.test.tsx` already does (the app has no `ThemeProvider` prop for forcing a theme). Put these helpers at the top of the file, under the imports:

```tsx
function ForceDarkTheme({ children }: { children: React.ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const hex = (h: string) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const over = (top: number[], a: number, bot: number[]) => top.map((v, i) => v * a + bot[i] * (1 - a));
```

and change the imports to:

```tsx
import { useEffect } from 'react';
import { IconWell, NEUTRAL_WASH } from './IconWell';
import { dark, light } from '../../theme/palette';
import { withAlpha } from '../../theme/glass';
import { ThemeProvider, useThemeSetting } from '../../theme';
```

A dark test does not leak into the next test. `setSetting` writes the choice to SecureStore, and `mobile/src/test/setup.ts` clears the mocked SecureStore in a `beforeEach` ("Every test starts from a clean SecureStore/AsyncStorage"), so each `ThemeProvider` mounts on `system`, which resolves to light in jest.

Leave `mobile/src/components/dashboard/DashboardCard.test.tsx` as it is. Its two tests ("renders its children" and the hairline-border test) still hold after this task and must keep passing unchanged.

- [ ] **Step 2: Run and confirm failure**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/primitives.test.tsx
```

Expected: FAIL, modules not found.

- [ ] **Step 3: Implement `DashboardCard.tsx`**

Replace the component and styles, keeping the existing doc comment and adding one paragraph to it:

```tsx
import type { ReactNode } from 'react';
import { Platform, StyleSheet, type ViewStyle } from 'react-native';
import { radius, spacing } from '../../theme';
import { GlassSurface, type GlassVariant } from '../GlassSurface';

// (existing doc comment stays)
//
// Card redesign (2026-10-10): radius 20 and two paddings. Every dashboard card and tile is a
// glass `panel` (GlassSurface's default): Liquid Glass on iOS 26, blur on older iOS, the tinted
// fill on Android. `variant` is passed through only so a call site can drop to the tinted `row`
// surface if the device measurement in the plan's Task 12 shows the extra native effect views
// cost frames; nothing on the dashboard uses it by default.
export function DashboardCard({
  children, style, testID, variant, padding = 'regular',
}: {
  children: ReactNode; style?: ViewStyle; testID?: string; variant?: GlassVariant; padding?: 'regular' | 'compact';
}) {
  return (
    <GlassSurface
      testID={testID}
      variant={variant}
      style={[styles.card, padding === 'compact' ? styles.compact : styles.regular, style]}
    >
      {children}
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.xxl,
    ...Platform.select({
      ios: {
        shadowColor: '#000000',
        shadowOffset: { width: 0, height: 8 },
        shadowOpacity: 0.07,
        shadowRadius: 18,
      },
      android: { elevation: 2 },
    }),
  },
  regular: { padding: spacing.ml },
  compact: { padding: spacing.md },
});
```

- [ ] **Step 4: Implement `DeltaChip.tsx`**

```tsx
import { StyleSheet, Text, View } from 'react-native';
import { spacing, typography, useTheme } from '../../theme';

/**
 * A change against the comparison period. `good` is separate from the sign on purpose: a rise in
 * expenses is bad, a rise in income is good, and the caller is the one that knows which it has.
 * The comparison period itself ("vs last month") is rendered by the caller from the label it was
 * given, never here: see scripts/check-reporting-period-labels.py.
 */
export function DeltaChip({ delta, good, testID }: { delta: number; good: boolean; testID?: string }) {
  const c = useTheme();
  return (
    <View testID={testID} style={[styles.chip, { backgroundColor: good ? c.successBg : c.dangerBg }]}>
      <Text style={[typography.labelS, { color: good ? c.successInk : c.dangerInk }]}>
        {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}%
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  chip: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: spacing.sm, paddingVertical: 3 },
});
```

- [ ] **Step 5: Implement `ProgressBar.tsx`**

```tsx
import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme';

/**
 * Decorative: the figure a bar stands for is always written out next to it, so the bar is hidden
 * from screen readers. The fill is clamped to its track; the caller keeps the real, uncapped
 * figure for its label (a budget at 150% still says 150%).
 */
export function ProgressBar({
  percent, color, trackColor, height = 6, testID,
}: { percent: number; color: string; trackColor?: string; height?: number; testID?: string }) {
  const c = useTheme();
  const pct = Number.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0;
  return (
    <View
      testID={testID}
      style={[styles.track, { height, borderRadius: height / 2, backgroundColor: trackColor ?? c.border }]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <View
        testID={testID ? `${testID}-fill` : undefined}
        style={{ width: `${pct}%`, height, borderRadius: height / 2, backgroundColor: color }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { overflow: 'hidden', alignSelf: 'stretch' },
});
```

- [ ] **Step 6: Implement `DashboardSectionHeader.tsx`**

```tsx
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, typography, useTheme } from '../../theme';

/**
 * A dashboard section's title row. One of three things may sit on the right: a plain caption
 * (the comparison period), a pressable action ("See all"), or a caller-supplied accessory (the
 * cash flow range control). Card.tsx's SectionHeading stays as it is for the rest of the app.
 */
export function DashboardSectionHeader({
  title, caption, actionLabel, onAction, showChevron = true, accessory,
}: {
  title: string; caption?: string; actionLabel?: string; onAction?: () => void; showChevron?: boolean; accessory?: ReactNode;
}) {
  const c = useTheme();
  return (
    <View style={styles.row}>
      <Text accessibilityRole="header" style={[typography.cardTitle, styles.title, { color: c.ink }]}>{title}</Text>
      {accessory}
      {!accessory && actionLabel && onAction ? (
        <Pressable
          onPress={onAction}
          style={styles.action}
          hitSlop={8}
          accessibilityRole="button"
          accessibilityLabel={actionLabel}
        >
          <Text style={[typography.labelS, { color: c.mutedInk }]}>{actionLabel}</Text>
          {showChevron ? <Ionicons name="chevron-forward" size={14} color={c.muted} /> : null}
        </Pressable>
      ) : null}
      {!accessory && !onAction && caption ? (
        <Text style={[typography.labelS, styles.caption, { color: c.mutedInk }]} numberOfLines={2}>{caption}</Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm, minHeight: 24 },
  title: { flexShrink: 1 },
  action: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44 },
  caption: { flexShrink: 1, textAlign: 'right' },
});
```

- [ ] **Step 7: Implement `IconWell.tsx`**

```tsx
import type { ComponentProps } from 'react';
import { StyleSheet, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, useTheme } from '../../theme';
import { withAlpha } from '../../theme/glass';

type IoniconName = ComponentProps<typeof Ionicons>['name'];
type Tone = 'neutral' | 'success' | 'warning' | 'brass';

/** Alpha of the ink wash behind a neutral icon. primitives.test.tsx measures it on both themes. */
export const NEUTRAL_WASH = 0.08;

/**
 * A tinted holder for a row or tile icon. The neutral well is a wash of the theme's own ink, not
 * `primaryLight`: `primaryLight` measured 1.01:1 against a dark-theme glass card (the well
 * vanished) and 1.03:1 against a light one. Ink at 0.08 measured 1.25:1 dark and 1.17:1 light,
 * the same on every backdrop pixel because it is composited over whatever the card is. The
 * hairline edge (`border`, 1.46:1 dark) carries the rest of the shape.
 */
export function IconWell({
  name, tone = 'neutral', round = false, size = 40, testID,
}: { name: IoniconName; tone?: Tone; round?: boolean; size?: number; testID?: string }) {
  const c = useTheme();
  const fill = { neutral: withAlpha(c.ink, NEUTRAL_WASH), success: c.successBg, warning: c.warningBg, brass: c.brassBg }[tone];
  const ink = { neutral: c.ink, success: c.successInk, warning: c.warningInk, brass: c.brassInk }[tone];
  return (
    <View
      testID={testID}
      style={[
        styles.well,
        { width: size, height: size, borderRadius: round ? size / 2 : radius.lg, backgroundColor: fill, borderColor: c.border },
      ]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Ionicons name={name} size={Math.round(size / 2)} color={ink} />
    </View>
  );
}

const styles = StyleSheet.create({
  well: { alignItems: 'center', justifyContent: 'center', borderWidth: StyleSheet.hairlineWidth },
});
```

- [ ] **Step 8: Run the primitives and every existing dashboard test**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard src/theme
```

Expected: PASS. `DashboardCard`'s default padding moved from 24 to 20; if an existing test pinned 24, read it, confirm it was pinning the old design value and update it to 20.

- [ ] **Step 9: Commit**

```bash
git add mobile/src/components/dashboard
git commit -m "feat(mobile): shared dashboard primitives for the card redesign"
```

---

### Task 3: Health hero

**Files:**
- Create: `mobile/src/lib/heroGauge.ts`, `mobile/src/lib/heroGauge.test.ts`, `mobile/src/lib/heroTones.ts`, `mobile/src/lib/heroTones.test.ts`
- Modify: `mobile/src/components/dashboard/HealthHero.tsx`
- Test: `mobile/src/components/dashboard/HealthHero.test.tsx` (existing tests stay; two added)

**Interfaces:**
- Consumes: `ProgressBar`, `GlassSurface` and `useReduceTransparency` (both existing), `typography`, `radius.hero`, `spacing`.
- Produces:
  - `heroGauge.ts`: `GAUGE_SIZE = 116`, `GAUGE_STROKE = 9`, `GAUGE_RADIUS`, `gaugeArcPath(score: number): string` (a worklet; `''` for a score of 0 or less)
  - `heroTones.ts`: `HERO_GLASS_ALPHA = 0.86` and `heroTones(c: Palette): { surface: string; solidSurface: string; text: string; textSoft: string; track: string; divider: string; arc: (score: number) => string }`

Background the implementer needs: Sid chose "option B" on 2026-10-10: everything on the dashboard is glass, and the hero keeps its own colour. So the hero is a `GlassSurface` panel tinted with `c.primaryDark` (near-black in the light theme, cream in the dark theme) at `HERO_GLASS_ALPHA`, and opaque `c.primaryDark` under Reduce Transparency. Because `primaryDark` flips between themes, a colour that works on one hero fails on the other; `heroTones` is the one place that knows this. The alpha is 0.86 because the backdrop shows through the tint: measured on every backdrop pixel, the app's ordinary glass alpha of 0.72 left the dark-theme soft text at 4.13:1 and three gauge colours under 3:1; 0.80 cleared the floors narrowly; 0.86 is the value Sid saw in the option image and leaves margin (worst cases 4.99:1 for text, 3.68:1 for a gauge colour).

- [ ] **Step 1: Write the failing tests**

`mobile/src/lib/heroGauge.test.ts`:

```ts
import { GAUGE_RADIUS, GAUGE_SIZE, gaugeArcPath } from './heroGauge';

const nums = (d: string) => d.match(/-?\d+(\.\d+)?/g)!.map(Number);

describe('gaugeArcPath', () => {
  it('draws nothing for a score of zero or below', () => {
    expect(gaugeArcPath(0)).toBe('');
    expect(gaugeArcPath(-10)).toBe('');
  });

  it('starts at the bottom-left of the dial', () => {
    const [x, y] = nums(gaugeArcPath(50));
    expect(x).toBeLessThan(GAUGE_SIZE / 2);
    expect(y).toBeGreaterThan(GAUGE_SIZE / 2);
  });

  it('uses the large-arc flag only once the sweep passes half a turn', () => {
    // M x y A r r 0 <large> 1 x y  ->  the sixth number is the large-arc flag
    expect(nums(gaugeArcPath(60))[5]).toBe(0); // 162 degrees
    expect(nums(gaugeArcPath(70))[5]).toBe(1); // 189 degrees
  });

  it('ends at the bottom-right for a full score, and never beyond it', () => {
    const full = nums(gaugeArcPath(100));
    const over = nums(gaugeArcPath(140));
    expect(full[7]).toBeGreaterThan(GAUGE_SIZE / 2);
    expect(full[8]).toBeGreaterThan(GAUGE_SIZE / 2);
    expect(over.slice(7)).toEqual(full.slice(7));
  });

  it('keeps every point on the dial radius', () => {
    const [x, y] = nums(gaugeArcPath(33)).slice(7);
    expect(Math.hypot(x - GAUGE_SIZE / 2, y - GAUGE_SIZE / 2)).toBeCloseTo(GAUGE_RADIUS, 5);
  });
});
```

`mobile/src/lib/heroTones.test.ts`:

```ts
import { readFileSync } from 'fs';
import { join } from 'path';
import { PNG } from 'pngjs';
import { dark, light } from '../theme/palette';
import { HERO_GLASS_ALPHA, heroTones } from './heroTones';

/**
 * The hero is tinted glass, so what sits under its text is the backdrop seen through the tint.
 * Like glassContrast.test.ts this reads the committed backdrop PNGs and checks every distinct
 * pixel, and it checks the opaque fallback (Reduce Transparency) as well. A failure is fixed by
 * changing a colour or HERO_GLASS_ALPHA in heroTones.ts, never by lowering 4.5 or 3.
 */
const ASSETS = join(__dirname, '../../assets/glass');
const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
// Resolves '#RRGGBB' or 'rgba(r,g,b,a)' to the colour actually seen over `under`.
const seen = (colour: string, under: number[]) => {
  if (colour.startsWith('#')) { const n = parseInt(colour.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
  const [r, g, b, a] = colour.match(/[\d.]+/g)!.map(Number);
  return [r, g, b].map((v, i) => v * a + under[i] * (1 - a));
};
const distinctPixels = (theme: string) => {
  const png = PNG.sync.read(readFileSync(join(ASSETS, `mesh-${theme}.png`)));
  const packed = new Set<number>();
  for (let i = 0; i < png.data.length; i += 4) packed.add((png.data[i] << 16) | (png.data[i + 1] << 8) | png.data[i + 2]);
  return [...packed].map((n) => [(n >> 16) & 255, (n >> 8) & 255, n & 255]);
};
const SCORES = [95, 70, 50, 20];

describe.each([['light', light], ['dark', dark]] as const)('hero tones in the %s theme', (theme, palette) => {
  const t = heroTones(palette);
  // Every surface the hero can be: the tint over each backdrop pixel, and the opaque fallback.
  const surfaces = [...distinctPixels(theme).map((px) => seen(t.surface, px)), seen(t.solidSurface, [0, 0, 0])];
  const worst = (colour: string) => Math.min(...surfaces.map((s) => ratio(seen(colour, s), s)));

  it('tints the glass with the surface token the hero has always used', () => {
    expect(t.solidSurface).toBe(palette.primaryDark);
    expect(seen(t.surface, [0, 0, 0])).toEqual(seen(palette.primaryDark, [0, 0, 0]).map((v) => v * HERO_GLASS_ALPHA));
  });

  it.each(['text', 'textSoft'] as const)('%s clears 4.5:1 on the hero, on every backdrop pixel and on the solid fallback', (key) => {
    expect(worst(t[key])).toBeGreaterThanOrEqual(4.5);
  });

  it.each(SCORES)('the gauge colour for a score of %d clears 3:1 on the hero', (score) => {
    expect(worst(t.arc(score))).toBeGreaterThanOrEqual(3);
  });

  it('keeps the track fainter than any gauge colour, so progress is distinguishable from what remains', () => {
    const track = Math.max(...surfaces.map((s) => ratio(seen(t.track, s), s)));
    for (const score of SCORES) expect(worst(t.arc(score))).toBeGreaterThan(track);
  });
});
```

- [ ] **Step 2: Run and confirm failure**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/lib/heroGauge.test.ts src/lib/heroTones.test.ts
```

Expected: FAIL, modules not found.

- [ ] **Step 3: Implement `heroGauge.ts`**

```ts
/**
 * Geometry for the health hero's dial: a 270 degree arc that starts at the bottom-left and runs
 * clockwise to the bottom-right. Angles are in SVG's own frame (0 = three o'clock, y grows
 * downward, positive = clockwise), so no flipping happens anywhere.
 *
 * Both functions are worklets: the hero animates the score on the UI thread and builds the path
 * there. They are plain functions as well, so the static track and the tests call them directly.
 */
export const GAUGE_SIZE = 116;
export const GAUGE_STROKE = 9;
export const GAUGE_RADIUS = (GAUGE_SIZE - GAUGE_STROKE) / 2;
const CENTER = GAUGE_SIZE / 2;
const START_DEG = 135;
const SWEEP_DEG = 270;

function pointAt(deg: number): { x: number; y: number } {
  'worklet';
  const a = (deg * Math.PI) / 180;
  return { x: CENTER + GAUGE_RADIUS * Math.cos(a), y: CENTER + GAUGE_RADIUS * Math.sin(a) };
}

export function gaugeArcPath(score: number): string {
  'worklet';
  const s = Math.max(0, Math.min(100, score));
  if (!(s > 0)) return '';
  const sweep = (SWEEP_DEG * s) / 100;
  const from = pointAt(START_DEG);
  const to = pointAt(START_DEG + sweep);
  return `M ${from.x} ${from.y} A ${GAUGE_RADIUS} ${GAUGE_RADIUS} 0 ${sweep > 180 ? 1 : 0} 1 ${to.x} ${to.y}`;
}
```

- [ ] **Step 4: Implement `heroTones.ts`**

```ts
import { dark, light, type Palette } from '../theme/palette';
import { withAlpha } from '../theme/glass';

/**
 * How opaque the hero's glass tint is. Higher than the app's glass alpha (0.72) on purpose: the
 * backdrop shows through the tint, and at 0.72 the dark theme's soft text measured 4.13:1 and
 * three gauge colours fell under 3:1. heroTones.test.ts measures this value on every backdrop
 * pixel; lowering it has to go back through that test.
 */
export const HERO_GLASS_ALPHA = 0.86;

export interface HeroTones {
  /** The glass tint: `primaryDark` at HERO_GLASS_ALPHA. */
  surface: string;
  /** The opaque fill under Reduce Transparency, and the colour of text on the hero's own button. */
  solidSurface: string;
  text: string;
  textSoft: string;
  track: string;
  divider: string;
  arc: (score: number) => string;
}

/**
 * Every colour drawn on the health hero. The hero is glass tinted with `primaryDark`, which is near-black
 * in the light theme and light cream in the dark theme, so the hero is the one place where a
 * theme's own semantic tones are on the wrong ground: dark-theme green on cream measured 1.45:1.
 * Each theme therefore borrows the tones made for the opposite ground. heroTones.test.ts measures
 * every value here against the surface on both themes; a change that fails it is a change to
 * these choices, never to the thresholds.
 */
export function heroTones(c: Palette): HeroTones {
  const surfaceIsDark = c !== dark; // light theme -> near-black hero; dark theme -> cream hero
  const tier = surfaceIsDark
    ? { excellent: dark.success, fair: dark.warning, poor: dark.danger }
    : { excellent: light.successInk, fair: light.warningInk, poor: light.dangerInk };
  return {
    surface: withAlpha(c.primaryDark, HERO_GLASS_ALPHA),
    solidSurface: c.primaryDark,
    text: c.onPrimary,
    textSoft: withAlpha(c.onPrimary, 0.72),
    track: withAlpha(c.onPrimary, 0.16),
    divider: withAlpha(c.onPrimary, 0.12),
    arc: (score: number) => {
      if (score >= 80) return tier.excellent;
      if (score >= 60) return c.primaryLight; // the neutral "Good" tier: no alarm colour
      if (score >= 40) return tier.fair;
      return tier.poor;
    },
  };
}
```

- [ ] **Step 5: Run the two lib tests**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/lib/heroGauge.test.ts src/lib/heroTones.test.ts
```

Expected: PASS. If a contrast assertion fails, the message names the theme and the ratio. Read it, then change the colour choice in `heroTones.ts`. Do not lower 4.5 or 3.

- [ ] **Step 6: Add one test to `HealthHero.test.tsx`**

Append inside the existing `describe('HealthHero', …)`:

```tsx
  // Zero is a real denominator when the server sends no floor; the old code divided by it.
  it('shows 0% rather than NaN% when the transaction floor is zero', () => {
    renderHero({ available: false, healthScoreTransactionCount: 3, healthScoreMinTransactions: 0 });
    expect(screen.getByText('0%')).toBeTruthy();
    expect(screen.queryByText(/NaN/)).toBeNull();
  });
```

Run it and confirm it fails with `NaN%` or `Infinity%` on screen before changing the component:

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/HealthHero.test.tsx
```

- [ ] **Step 7: Rewrite `HealthHero.tsx`**

Keep the `Props` interface exactly as it is. Replace the rest of the file with:

```tsx
import { useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Defs, Path, RadialGradient, Stop } from 'react-native-svg';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { AnimatedHealthScoreNumber } from '../AnimatedHealthScoreNumber';
import { GlassSurface } from '../GlassSurface';
import { GAUGE_SIZE, GAUGE_STROKE, gaugeArcPath } from '../../lib/heroGauge';
import { heroTones } from '../../lib/heroTones';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { HealthSparkline } from './HealthSparkline';
import { ProgressBar } from './ProgressBar';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { HealthScorePoint } from '../../types';

const AnimatedPath = Animated.createAnimatedComponent(Path);
const GLOW = 260;

interface Props {
  available: boolean;
  healthScore: number;
  healthLabel: string;
  healthScoreDeltaVsLastMonth: number | null;
  healthSparkline: HealthScorePoint[];
  healthScoreTransactionCount: number;
  healthScoreMinTransactions: number;
  onImportPress: () => void;
}

/** The brass light in the hero's top-right corner. Decorative; clipped by the card's radius. */
function Glow({ color }: { color: string }) {
  return (
    <Svg width={GLOW} height={GLOW} style={styles.glow} pointerEvents="none">
      <Defs>
        <RadialGradient id="heroGlow" cx="50%" cy="50%" r="50%">
          <Stop offset="0" stopColor={color} stopOpacity={0.34} />
          <Stop offset="1" stopColor={color} stopOpacity={0} />
        </RadialGradient>
      </Defs>
      <Circle cx={GLOW / 2} cy={GLOW / 2} r={GLOW / 2} fill="url(#heroGlow)" />
    </Svg>
  );
}

export function HealthHero({
  available, healthScore, healthLabel, healthScoreDeltaVsLastMonth, healthSparkline,
  healthScoreTransactionCount, healthScoreMinTransactions, onImportPress,
}: Props) {
  const c = useTheme();
  const t = heroTones(c);
  // Tinted glass. GlassSurface uses a caller's fill as given (it moves it onto its tint layer),
  // so the opaque fallback for Reduce Transparency, or before the setting is known, is chosen
  // here. The edge takes the fill's colour on that path: a solid hero has never had an outline.
  const solid = useReduceTransparency() !== false;
  const surface = solid
    ? { backgroundColor: t.solidSurface, borderColor: t.solidSurface }
    : { backgroundColor: t.surface };
  // Draws the arc in from zero once per mount, landing on the real score.
  const scoreProgress = useSharedValue(0);
  useEffect(() => {
    scoreProgress.value = withTiming(healthScore, { duration: 700, easing: Easing.out(Easing.cubic) });
  }, [healthScore, scoreProgress]);
  const arcAnimatedProps = useAnimatedProps(() => ({ d: gaugeArcPath(scoreProgress.value) }));

  if (!available) {
    // A zero floor is a real value when the server sends none; dividing by it printed "NaN%".
    const share = healthScoreMinTransactions > 0 ? healthScoreTransactionCount / healthScoreMinTransactions : 0;
    const percent = Math.round(Math.min(100, share * 100));
    return (
      <GlassSurface testID="health-hero" style={[styles.card, surface]}>
        <Glow color={c.brass} />
        <Text style={[typography.eyebrow, { color: t.textSoft }]}>Financial Health Score</Text>
        <View style={styles.lockedText}>
          <Text style={[typography.numberL, { color: t.text }]}>Getting Started</Text>
          <Text style={[typography.bodyM, { color: t.textSoft }]}>
            Import more transactions to unlock your Financial Health Score.
          </Text>
        </View>
        <View style={styles.lockedProgress}>
          <View style={styles.lockedLabels}>
            <Text style={[typography.labelS, { color: t.textSoft }]}>
              {healthScoreTransactionCount} / {healthScoreMinTransactions} transactions
            </Text>
            <Text style={[typography.labelS, { color: t.textSoft }]}>{percent}%</Text>
          </View>
          <ProgressBar percent={percent} color={c.primaryLight} trackColor={t.track} />
        </View>
        <Pressable
          onPress={onImportPress}
          hitSlop={8}
          style={[styles.continueButton, { backgroundColor: t.text }]}
          accessibilityRole="button"
        >
          <Text style={[typography.labelM, { color: t.solidSurface }]}>Continue Setup</Text>
        </Pressable>
      </GlassSurface>
    );
  }

  const deltaPositive = (healthScoreDeltaVsLastMonth ?? 0) > 0;

  return (
    <GlassSurface testID="health-hero" style={[styles.card, surface]}>
      <Glow color={c.brass} />
      <Text style={[typography.eyebrow, { color: t.textSoft }]}>Financial Health Score</Text>

      <View style={styles.scoreRow}>
        <View style={styles.scoreCol}>
          <View style={styles.scoreValueRow}>
            <AnimatedHealthScoreNumber
              testID="health-score-value"
              value={healthScore}
              style={[typography.display, { color: t.text }]}
            />
            <Text style={[typography.bodyM, styles.outOf, { color: t.textSoft }]}>/ 100</Text>
          </View>
          {healthScoreDeltaVsLastMonth !== null && healthScoreDeltaVsLastMonth !== 0 ? (
            <View style={[styles.deltaPill, { backgroundColor: c.brassBg }]}>
              <Text style={[typography.labelS, { color: c.brassInk }]}>
                {deltaPositive ? '+' : ''}{healthScoreDeltaVsLastMonth} vs last score
              </Text>
            </View>
          ) : null}
        </View>

        <View style={styles.gauge}>
          <Svg width={GAUGE_SIZE} height={GAUGE_SIZE}>
            {/* What remains: the whole dial, faint. */}
            <Path d={gaugeArcPath(100)} stroke={t.track} strokeWidth={GAUGE_STROKE} strokeLinecap="round" fill="none" />
            {/* Progress, in the tier's colour. Same stroke width as the track so the two arcs
                are concentric everywhere: a wider progress arc leaves a visible step where it
                ends (found on a 4x screenshot in the previous design). */}
            {healthScore > 0 ? (
              <AnimatedPath
                stroke={t.arc(healthScore)}
                strokeWidth={GAUGE_STROKE}
                strokeLinecap="round"
                fill="none"
                animatedProps={arcAnimatedProps}
              />
            ) : null}
          </Svg>
          <View style={styles.gaugeLabel} pointerEvents="none">
            <Text style={[typography.cardTitle, { color: t.text }]}>{healthLabel}</Text>
          </View>
        </View>
      </View>

      {healthSparkline.length >= 2 ? (
        <>
          <View style={[styles.divider, { backgroundColor: t.divider }]} />
          <View style={styles.trendRow}>
            <Text style={[typography.eyebrow, { color: t.textSoft }]}>6-month trend</Text>
            <View style={styles.sparkline}>
              <HealthSparkline points={healthSparkline} color={t.text} />
            </View>
          </View>
        </>
      ) : null}
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.hero, padding: spacing.lg, overflow: 'hidden', gap: spacing.ml },
  glow: { position: 'absolute', top: -GLOW / 2, right: -GLOW / 3 },
  scoreRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  scoreCol: { flexShrink: 1, gap: spacing.ms },
  scoreValueRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 6 },
  outOf: { marginBottom: 10 },
  deltaPill: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  gauge: { width: GAUGE_SIZE, height: GAUGE_SIZE },
  gaugeLabel: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, alignItems: 'center', justifyContent: 'center' },
  divider: { height: StyleSheet.hairlineWidth, alignSelf: 'stretch' },
  trendRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  sparkline: { flex: 1, maxWidth: 170 },
  lockedText: { gap: spacing.sm },
  lockedProgress: { gap: spacing.sm },
  lockedLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  continueButton: { minHeight: 44, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
```

Notes for the implementer:
- In jest the Reduce Transparency setting is unknown, which is the solid path. Add this to `HealthHero.test.tsx` next to the zero-floor test. The file's `renderHero()` helper renders an available hero by default. Add `import { StyleSheet } from 'react-native';` and `import { light } from '../../theme/palette';` to the file:

```tsx
  it('is an opaque hero when transparency is reduced or unknown', () => {
    renderHero();
    const style = StyleSheet.flatten(screen.getByTestId('health-hero').props.style);
    expect(style.backgroundColor).toBe(light.primaryDark);
    expect(style.borderColor).toBe(light.primaryDark);
  });
```

- The glass path (Reduce Transparency known to be off) is covered in two places: `heroTones.test.ts` measures the tint on every backdrop pixel, and `GlassSurface.native.test.tsx` already proves a caller's fill moves onto the tint layer. Read that file; if its mocks can be reused in three lines, add a `HealthHero` test that renders with transparency allowed and expects the element with testID `glass-tint` to carry `heroTones(light).surface`. If they cannot, say so in the commit message instead of writing a test that re-implements `GlassSurface`.
- "6-month trend" is existing copy and is not a comparison-period claim; `check-reporting-period-labels.py` matches "this month", "vs last month" and "last month" only. Step 9 proves it.
- "/ 100" is new visible text. It restates the scale and makes no period claim.

- [ ] **Step 8: Run the hero tests**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/HealthHero.test.tsx src/components/dashboard/HealthSparkline.test.tsx
```

Expected: PASS, including the new zero-floor test, the new opaque-fallback test and the unchanged animated-props test.

- [ ] **Step 9: Run the period-label guard from the repository root**

```bash
python3 scripts/check-reporting-period-labels.py
```

Expected: exit 0.

- [ ] **Step 10: Commit**

```bash
git add mobile/src/lib/heroGauge.ts mobile/src/lib/heroGauge.test.ts mobile/src/lib/heroTones.ts mobile/src/lib/heroTones.test.ts mobile/src/components/dashboard/HealthHero.tsx mobile/src/components/dashboard/HealthHero.test.tsx
git commit -m "feat(mobile): health hero as tinted glass, with the score as type and a 270 degree dial"
```

---

### Task 4: Health factor tiles

**Files:**
- Modify: `mobile/src/lib/health.ts`, `mobile/src/components/dashboard/FinancialHealthFactorCard.tsx`, `mobile/src/components/dashboard/HealthFactorsRow.tsx`
- Test: `mobile/src/lib/health.test.ts` (create if absent), `mobile/src/components/dashboard/HealthFactorsRow.test.tsx` (existing, unchanged)

**Interfaces:**
- Produces: `healthLabelInk(score: number, c: Palette): string`, a text-safe colour for a tier label.

- [ ] **Step 1: Write the failing test**

Check whether `mobile/src/lib/health.test.ts` exists (`ls mobile/src/lib/health.test.ts`). Add this block to it, or create the file with it:

```ts
import { healthLabelInk } from './health';
import { dark, light } from '../theme/palette';

describe('healthLabelInk', () => {
  it.each([
    [95, 'successInk'], [80, 'successInk'], [79, 'ink'], [60, 'ink'], [59, 'warningInk'], [40, 'warningInk'], [39, 'dangerInk'], [0, 'dangerInk'],
  ] as const)('a score of %d is written in %s', (score, token) => {
    expect(healthLabelInk(score, light)).toBe(light[token]);
    expect(healthLabelInk(score, dark)).toBe(dark[token]);
  });
});
```

Run and confirm it fails (`healthLabelInk` is not exported).

- [ ] **Step 2: Implement in `health.ts`**

Add after `healthBarColor`:

```ts
/**
 * The colour for a tier's LABEL. healthBarColor is for bars and arcs (graphics need 3:1); its
 * success and danger tones do not clear 4.5:1 as text on a glass card, which is what the *Ink
 * tokens exist for (see glassMigration.test.ts). Same cutoffs as scoreLabel.
 */
export function healthLabelInk(score: number, c: Palette): string {
  if (score >= 80) return c.successInk;
  if (score >= 60) return c.ink;
  if (score >= 40) return c.warningInk;
  return c.dangerInk;
}
```

- [ ] **Step 3: Rewrite `FinancialHealthFactorCard.tsx`**

Keep the props. Replace the body and styles:

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { ProgressBar } from './ProgressBar';
import { healthBarColor, healthImprovementSuggestion, healthLabelInk, scoreLabel } from '../../lib/health';
import { spacing, typography, useTheme } from '../../theme';

export function FinancialHealthFactorCard({
  name, score, detail, isExpanded, onToggle, isTopOpportunity, topOpportunityPotentialGain,
}: {
  name: string;
  score: number;
  detail: string | undefined;
  isExpanded: boolean;
  onToggle: () => void;
  isTopOpportunity: boolean;
  topOpportunityPotentialGain: number | null;
}) {
  const c = useTheme();
  return (
    <DashboardCard style={styles.card} padding="compact">
      <View style={styles.headerRow}>
        <Text style={[typography.labelS, styles.name, { color: c.mutedInk }]} numberOfLines={1}>{name}</Text>
        {detail ? (
          <Pressable
            onPress={onToggle}
            hitSlop={14}
            accessibilityRole="button"
            accessibilityState={{ expanded: isExpanded }}
            accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
          >
            <Text style={[typography.labelS, styles.why, { color: c.primary }]}>{isExpanded ? 'Hide' : 'Why?'}</Text>
          </Pressable>
        ) : null}
      </View>
      <Text style={[typography.numberM, { color: c.ink }]}>{Math.round(score)}%</Text>
      <ProgressBar percent={score} color={healthBarColor(score, c)} height={4} />
      <Text style={[typography.labelS, { color: healthLabelInk(score, c) }]}>{scoreLabel(score)}</Text>
      {detail && isExpanded ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{detail}</Text> : null}
      <Text style={[typography.bodyS, { color: c.mutedInk }]}>{healthImprovementSuggestion(name, score)}</Text>
      {isTopOpportunity ? (
        <Text style={[typography.labelS, { color: c.brassInk }]}>
          ↑ +{topOpportunityPotentialGain} point opportunity
        </Text>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { width: 200, gap: spacing.sm },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.xs },
  name: { flexShrink: 1 },
  why: { textDecorationLine: 'underline' },
});
```

In `HealthFactorsRow.tsx` change only the row gap:

```ts
const styles = StyleSheet.create({
  row: { gap: spacing.ms, paddingVertical: spacing.xs },
});
```

- [ ] **Step 4: Run**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/lib/health.test.ts src/components/dashboard/HealthFactorsRow.test.tsx
```

Expected: PASS with `HealthFactorsRow.test.tsx` unchanged.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/lib/health.ts mobile/src/lib/health.test.ts mobile/src/components/dashboard/FinancialHealthFactorCard.tsx mobile/src/components/dashboard/HealthFactorsRow.tsx
git commit -m "feat(mobile): health factor tiles with a bar and a text-safe tier label"
```

---

### Task 5: Balance card

**Files:**
- Modify: `mobile/src/components/dashboard/AccountsCard.tsx`
- Test: `mobile/src/components/dashboard/AccountsCard.test.tsx` (existing tests stay; one added)

**Interfaces:** props unchanged: `{ accounts: Account[]; totalBalance: number; caption: string; onViewAll: () => void }`.

- [ ] **Step 1: Add the failing test**

Append to the existing `describe('AccountsCard', …)`, reusing whatever render helper and fixtures the file already has (read the top of the file first):

```tsx
  it('gives View Accounts a 44 point target', () => {
    // reuse the file's existing render call for a two-account fixture here
    const target = screen.getByLabelText('View Accounts');
    expect(StyleSheet.flatten(target.props.style).minHeight).toBe(44);
  });
```

Run and confirm it fails: today the control has `minHeight: 36` and no accessibility label.

- [ ] **Step 2: Rewrite the component body and styles**

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { DashboardCard } from './DashboardCard';
import { fmtCurrency } from '../../lib/format';
import { fonts, spacing, typography, useTheme } from '../../theme';
import type { Account } from '../../types';

const MAX_AVATARS = 4;

export function AccountsCard({
  accounts, totalBalance, caption, onViewAll,
}: {
  accounts: Account[];
  totalBalance: number;
  caption: string;
  onViewAll: () => void;
}) {
  const c = useTheme();
  const bankCount = new Set(accounts.map((a) => a.bank.id)).size;
  const shown = accounts.slice(0, MAX_AVATARS);
  const overflow = accounts.length - shown.length;

  return (
    <DashboardCard style={styles.card}>
      <View style={styles.top}>
        {/* One accessible node, same reason as before: "Total Balance", the amount and the
            caption are one claim, and swiping them as three items loses the connection. */}
        <View style={styles.balance} accessible accessibilityLabel={`Total Balance: ${fmtCurrency(totalBalance)}, ${caption}`}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Total Balance</Text>
          <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {fmtCurrency(totalBalance)}
          </Text>
          <Text style={[typography.caption, { color: c.mutedInk }]}>{caption}</Text>
        </View>
        <View style={styles.avatarRow}>
          {shown.map((a) => (
            <View key={a.id} testID={`account-avatar-${a.id}`} style={[styles.avatar, { backgroundColor: a.bank.colorHex, borderColor: c.card }]}>
              <Text style={styles.avatarText}>{a.bank.initials}</Text>
            </View>
          ))}
          {overflow > 0 ? (
            <View testID="account-avatar-overflow" style={[styles.avatar, { backgroundColor: c.border, borderColor: c.card }]}>
              <Text style={[styles.avatarText, { color: c.ink }]}>+{overflow}</Text>
            </View>
          ) : null}
        </View>
      </View>
      <View style={[styles.footer, { borderTopColor: c.border }]}>
        <Text style={[typography.bodyS, styles.counts, { color: c.mutedInk }]}>
          {accounts.length} Account{accounts.length === 1 ? '' : 's'} · {bankCount} Bank{bankCount === 1 ? '' : 's'}
        </Text>
        <Pressable onPress={onViewAll} style={styles.cta} accessibilityRole="button" accessibilityLabel="View Accounts">
          <Text style={[typography.labelS, { color: c.primary }]}>View Accounts</Text>
          <Ionicons name="chevron-forward" size={14} color={c.primary} />
        </Pressable>
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.ms },
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms },
  balance: { flex: 1, gap: spacing.xs },
  avatarRow: { flexDirection: 'row', paddingRight: 8 },
  avatar: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
    marginRight: -8, borderWidth: 2,
  },
  avatarText: { fontFamily: fonts.bodyBold, fontSize: 10, color: '#FFFFFF' },
  footer: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  counts: { flexShrink: 1 },
  cta: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44 },
});
```

`borderColor: c.card` on the avatar ring is a border, not a `backgroundColor`, so the glass guard does not apply; the existing ring tests pin it.

- [ ] **Step 3: Run**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/AccountsCard.test.tsx
```

Expected: PASS, including every pre-existing test.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/components/dashboard/AccountsCard.tsx mobile/src/components/dashboard/AccountsCard.test.tsx
git commit -m "feat(mobile): full-width balance card with a 44 point View Accounts target"
```

---

### Task 6: KPI tile grid

**Files:**
- Modify: `mobile/src/components/dashboard/LedgerSnapshotCard.tsx`, `mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx`

**Interfaces:** props unchanged: `{ kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string }`. `KpiItem` unchanged.

Behaviour: no outer card. A section header (`title`, with `deltaLabel` as its caption when at least one KPI has a delta) sits above a wrapping grid of tiles. Two tiles per row normally. One tile per row when large text is on, or when any formatted value is longer than 10 characters.

- [ ] **Step 1: Update and extend the tests**

In `LedgerSnapshotCard.test.tsx`:

Rename the first test and keep its assertions:

```tsx
  it('renders one tile per KPI', () => {
    renderCard();
    expect(screen.getByText('Income')).toBeTruthy();
    expect(screen.getByText('Expenses')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
  });
```

Replace the delta test (the chip and the period are now separate text nodes):

```tsx
  it('shows a delta chip for a KPI that has one, and names the comparison once for the section', () => {
    renderCard();
    expect(screen.getByText('▲ 12.5%')).toBeTruthy();
    expect(screen.getByText('▼ 3.2%')).toBeTruthy();
    expect(screen.getAllByText('vs last month')).toHaveLength(1);
  });

  it('colours a fall in expenses as good and a rise as bad', () => {
    renderCard([{ label: 'Expenses', value: 100, delta: 8, invert: true, caption: null, isPercent: false }]);
    expect(screen.getByText('▲ 8.0%')).toHaveStyle({ color: light.dangerInk });
  });

  it('names no comparison when no KPI has a delta', () => {
    renderCard([{ label: 'Total Balance', value: 100000, delta: null, invert: false, caption: 'As of today', isPercent: false }]);
    expect(screen.queryByText('vs last month')).toBeNull();
  });

  it('still reads each tile out as one sentence', () => {
    renderCard();
    expect(screen.getByLabelText('Income: ₹1,45,000, up 12.5 percent versus last month')).toBeTruthy();
  });

  const basis = (label: string) => StyleSheet.flatten(screen.getByTestId(`kpi-tile-${label}`).props.style).flexBasis;

  it('lays tiles out two per row for ordinary amounts', () => {
    renderCard();
    expect(basis('Income')).toBe('47%');
  });

  // Review Focus 1: eleven characters and up does not fit a half-width tile on a 360 point phone.
  it('falls back to one tile per row when an amount is too long for half a row', () => {
    renderCard([
      { label: 'Income', value: 12483200, delta: null, invert: false, caption: null, isPercent: false },
      { label: 'Expenses', value: 500, delta: null, invert: false, caption: null, isPercent: false },
    ]);
    expect(basis('Income')).toBe('100%');
    expect(basis('Expenses')).toBe('100%');
  });
```

Add the imports the new tests need at the top of the file:

```tsx
import { StyleSheet } from 'react-native';
import { light } from '../../theme/palette';
```

Add a large-text test. Read `mobile/src/lib/useLargeFontScale.ts` first to see what it reads, then mock the module the same way `DashboardScreen.test.tsx`'s "large Dynamic Type support" block does (copy that block's mock, do not invent one):

```tsx
  // Review Focus 2.
  it('falls back to one tile per row under large Dynamic Type', () => {
    // apply the same large-font mock DashboardScreen.test.tsx uses, then:
    renderCard();
    expect(basis('Income')).toBe('100%');
  });
```

Run and confirm the new tests fail and the unchanged ones still pass.

- [ ] **Step 2: Rewrite `LedgerSnapshotCard.tsx`**

Keep the `KpiItem` interface. Replace the rest:

```tsx
import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from './DashboardCard';
import { DashboardSectionHeader } from './DashboardSectionHeader';
import { DeltaChip } from './DeltaChip';
import { fmtCurrency } from '../../lib/format';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, typography, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  value: number | null;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

// A half-width tile on a 360 point phone has about 122 points for its number; ten characters of
// Manrope Bold 20 ("₹12,48,320") is the longest that fits. One crore and above gets a full row.
const MAX_HALF_WIDTH_CHARS = 10;

const display = (k: KpiItem) =>
  k.value === null ? '—' : k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);

/**
 * The month's figures as a grid of tiles. `title` and `deltaLabel` are whatever period the caller
 * was given by the server: this file must never assert a month of its own (Bug 05, guarded by
 * scripts/check-reporting-period-labels.py, which is why the grid lives here and not in a new
 * file that script does not scan).
 */
export function LedgerSnapshotCard({
  kpis, title, deltaLabel, deltaSpokenLabel,
}: { kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string }) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const anyDelta = kpis.some((k) => k.delta !== null && k.delta !== undefined);
  const singleColumn = largeText || kpis.some((k) => display(k).length > MAX_HALF_WIDTH_CHARS);

  return (
    <View style={styles.wrap}>
      <DashboardSectionHeader title={title} caption={anyDelta ? deltaLabel : undefined} />
      <View style={styles.grid}>
        {kpis.map((k) => {
          const displayValue = display(k);
          const hasDelta = k.delta !== null && k.delta !== undefined;
          return (
            <DashboardCard
              key={k.label}
              testID={`kpi-tile-${k.label}`}
              padding="compact"
              style={singleColumn ? styles.tileFull : styles.tileHalf}
            >
              <View
                style={styles.tileBody}
                accessible
                accessibilityLabel={
                  hasDelta
                    ? `${k.label}: ${displayValue}, ${k.delta! >= 0 ? 'up' : 'down'} ${Math.abs(k.delta!).toFixed(1)} percent ${deltaSpokenLabel}`
                    : k.caption
                      ? `${k.label}: ${displayValue}, ${k.caption}`
                      : `${k.label}: ${displayValue}`
                }
              >
                <Text style={[typography.eyebrow, { color: c.mutedInk }]} numberOfLines={1}>{k.label}</Text>
                {k.isPercent || k.value === null ? (
                  <Text testID={`kpi-${k.label}`} style={[typography.numberM, { color: c.ink }]}>{displayValue}</Text>
                ) : (
                  <AnimatedNumber testID={`kpi-${k.label}`} value={k.value} style={[typography.numberM, { color: c.ink }]} />
                )}
                {hasDelta ? (
                  <DeltaChip delta={k.delta!} good={k.invert ? k.delta! < 0 : k.delta! >= 0} />
                ) : k.caption ? (
                  <Text style={[typography.caption, { color: c.mutedInk }]}>{k.caption}</Text>
                ) : null}
              </View>
            </DashboardCard>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.ms },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  // 47% plus flexGrow fills a two-up row exactly across the 12 point gap at any phone width.
  tileHalf: { flexBasis: '47%', flexGrow: 1 },
  tileFull: { flexBasis: '100%' },
  tileBody: { gap: spacing.sm },
});
```

- [ ] **Step 3: Run the component test and the hook test**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/LedgerSnapshotCard.test.tsx src/lib/useDashboardKpis.test.ts
```

Expected: PASS.

- [ ] **Step 4: Run the period-label guard**

```bash
python3 scripts/check-reporting-period-labels.py
```

Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/LedgerSnapshotCard.tsx mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx
git commit -m "feat(mobile): month figures as a tile grid that drops to one column for long amounts and large text"
```

---

### Task 7: Cash flow trend card

**Files:**
- Modify: `mobile/src/components/dashboard/CashFlowMiniCard.tsx`, `mobile/src/components/dashboard/CashFlowMiniCard.test.tsx`

**Interfaces:** props unchanged: `{ points: CashFlowPoint[]; deltaPct: number | null; deltaLabel: string }`.

- [ ] **Step 1: Update the tests**

In `CashFlowMiniCard.test.tsx` change only the delta assertions; every other assertion stays:

```tsx
    // first test, replacing getByText('▲ 22.0% vs last month')
    expect(screen.getByText('▲ 22.0%')).toBeTruthy();
    expect(screen.getByText('vs last month')).toBeTruthy();
```

```tsx
    // second test, replacing getByText('▼ 5.0% vs the month before Jun 26')
    expect(screen.getByText('▼ 5.0%')).toBeTruthy();
    expect(screen.getByText('vs the month before Jun 26')).toBeTruthy();
    expect(screen.queryByText(/last month/)).toBeNull();
```

Add:

```tsx
  it('draws the trend even when every month nets to the same amount', () => {
    render(<CashFlowMiniCard points={[{ label: 'May', income: 100, expense: 50 }, { label: 'Jun', income: 100, expense: 50 }]} deltaPct={null} deltaLabel="vs last month" />);
    expect(screen.getByTestId('cash-flow-trend-chart')).toBeTruthy();
    expect(screen.queryByText('vs last month')).toBeNull();
  });
```

Run and confirm the changed and new assertions fail.

- [ ] **Step 2: Rewrite the component**

```tsx
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Defs, LinearGradient, Path, Stop } from 'react-native-svg';
import { DashboardCard } from './DashboardCard';
import { DeltaChip } from './DeltaChip';
import type { CashFlowPoint } from '../charts/CashFlowChart';
import { averageMonthlySavings, deriveNetSavingsSeries } from '../../lib/dashboardMetrics';
import { fmtCurrency } from '../../lib/format';
import { spacing, typography, useTheme } from '../../theme';

const WIDTH = 320;
const HEIGHT = 72;
const PAD = 4; // keeps the 2 point stroke inside the viewBox at the extremes

export function CashFlowMiniCard({
  points, deltaPct, deltaLabel,
}: {
  points: CashFlowPoint[];
  deltaPct: number | null;
  deltaLabel: string;
}) {
  const c = useTheme();

  if (points.length === 0) return null;

  const series = deriveNetSavingsSeries(points);
  const values = series.map((s) => s.net);
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 0);
  const range = max - min || 1;
  const xAt = (i: number) => (series.length <= 1 ? WIDTH / 2 : PAD + (i / (series.length - 1)) * (WIDTH - PAD * 2));
  const yAt = (v: number) => HEIGHT - PAD - ((v - min) / range) * (HEIGHT - PAD * 2);
  const line = series.map((s, i) => `${i === 0 ? 'M' : 'L'} ${xAt(i)} ${yAt(s.net)}`).join(' ');
  const area = `${line} L ${xAt(series.length - 1)} ${HEIGHT} L ${xAt(0)} ${HEIGHT} Z`;
  const average = averageMonthlySavings(points);

  return (
    <DashboardCard style={styles.card}>
      <View style={styles.top}>
        <View style={styles.text}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Cash Flow Trend</Text>
          <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {fmtCurrency(average)}
          </Text>
          {/* States the window this average spans: points.length follows the range chosen on
              the full Cash Flow card (they share that state), so this is never a fixed claim. */}
          <Text style={[typography.caption, { color: c.mutedInk }]}>
            Average Monthly Savings ({points.length} mo{points.length === 1 ? '' : 's'})
          </Text>
        </View>
        {deltaPct !== null ? (
          <View style={styles.delta}>
            <DeltaChip delta={deltaPct} good={deltaPct >= 0} />
            <Text style={[typography.caption, styles.deltaLabel, { color: c.mutedInk }]}>{deltaLabel}</Text>
          </View>
        ) : null}
      </View>
      <Svg
        testID="cash-flow-trend-chart"
        width="100%"
        height={HEIGHT}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        preserveAspectRatio="none"
      >
        <Defs>
          <LinearGradient id="cashFlowTrendFill" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor={c.success} stopOpacity={0.22} />
            <Stop offset="1" stopColor={c.success} stopOpacity={0} />
          </LinearGradient>
        </Defs>
        {series.length > 1 ? <Path d={area} fill="url(#cashFlowTrendFill)" /> : null}
        <Path d={line} fill="none" stroke={c.success} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </Svg>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.md },
  top: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.ms },
  text: { flex: 1, gap: spacing.xs },
  delta: { alignItems: 'flex-end', gap: spacing.xs, maxWidth: 150 },
  deltaLabel: { textAlign: 'right' },
});
```

`stroke={c.success}` is an SVG attribute, not a `color:` style, so the glass guard's text rule does not match it. This is the same colour and role the card had before.

- [ ] **Step 3: Run**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/CashFlowMiniCard.test.tsx src/lib/dashboardMetrics.test.ts
```

```bash
python3 scripts/check-reporting-period-labels.py
```

Expected: both PASS.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/components/dashboard/CashFlowMiniCard.tsx mobile/src/components/dashboard/CashFlowMiniCard.test.tsx
git commit -m "feat(mobile): full-width cash flow trend card with an area chart"
```

---

### Task 8: Goals row and Financial Note

**Files:**
- Modify: `mobile/src/components/dashboard/GoalsRow.tsx`, `mobile/src/components/dashboard/FinancialNoteCard.tsx`
- Test: existing `GoalsRow.test.tsx` unchanged; one test added to `FinancialNoteCard.test.tsx`; one test added to `mobile/src/theme/glassContrast.test.ts`

- [ ] **Step 1: Restyle `GoalsRow.tsx`**

Change only the text styles and the `styles` object. The ring geometry, the `rawPct` / `ringPct` split and the `numberOfLines` logic stay exactly as they are:

```tsx
// imports: replace `fonts, spacing, useTheme` with `spacing, typography, useTheme`

// name
<Text style={[typography.labelM, styles.name, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
  {g.name}
</Text>

// percent inside the ring
<Text style={[typography.labelS, { color: c.ink }]}>{rawPct.toFixed(0)}%</Text>

// amounts
<Text style={[typography.caption, styles.meta, { color: c.mutedInk }]}>
  {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
</Text>
```

Move the ring above the name, as in the design, by reordering the three children inside `<DashboardCard>` to: ring, name, amounts. Pass `padding="compact"` to `DashboardCard`.

```ts
const styles = StyleSheet.create({
  row: { gap: spacing.ms, paddingVertical: spacing.xs },
  card: { width: 164, gap: spacing.ms },
  name: { alignSelf: 'stretch' },
  ringWrap: { width: RING_SIZE, height: RING_SIZE },
  ringCenter: { position: 'absolute', width: RING_SIZE, height: RING_SIZE, alignItems: 'center', justifyContent: 'center' },
  meta: { alignSelf: 'stretch' },
});
```

- [ ] **Step 2: Restyle `FinancialNoteCard.tsx`**

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { GlassSurface } from '../GlassSurface';
import { IconWell } from './IconWell';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { radius, spacing, typography, useTheme } from '../../theme';
import { withAlpha } from '../../theme/glass';

export function FinancialNoteCard({
  factor, potentialGain, onCreateGoal,
}: {
  factor: string | null;
  potentialGain: number | null;
  onCreateGoal: () => void;
}) {
  const c = useTheme();
  // Brass glass: the brass wash at the same alpha as every other glass surface, which GlassSurface
  // moves onto its tint layer. A caller's fill is used as given, so the solid fallback has to be
  // chosen here: opaque brass under Reduce Transparency, or before the setting is known.
  const solid = useReduceTransparency() !== false;
  const wash = solid ? c.brassBg : withAlpha(c.brassBg, c.glassAlpha);
  if (!factor || potentialGain === null) return null;

  return (
    <GlassSurface testID="financial-note" style={[styles.card, { backgroundColor: wash, borderColor: c.brass }]}>
      <IconWell name="sparkles-outline" tone="brass" round size={36} />
      <View style={styles.textWrap}>
        <Text style={[typography.labelM, { color: c.ink }]}>
          {factor} has the most room to improve right now.
        </Text>
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>
          Potential gain: <Text style={[typography.labelS, { color: c.brassInk }]}>+{potentialGain} points</Text>
        </Text>
      </View>
      <Pressable
        onPress={onCreateGoal}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primary }]}
        accessibilityRole="button"
      >
        <Text style={[typography.labelS, { color: c.onPrimary }]}>Create Goal</Text>
      </Pressable>
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.xxl, padding: spacing.md,
    flexDirection: 'row', alignItems: 'center', gap: spacing.ms,
  },
  textWrap: { flex: 1, gap: spacing.xs },
  cta: { minHeight: 44, paddingHorizontal: spacing.md, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
```

`IconWell` with `tone="brass"` paints `brassBg` on the brass glass card; its hairline edge is what shows. If the well disappears in the simulator check (Task 12), change the well's fill for this one call site to `c.card` with a `/* glass-exempt: icon well inside the brass note card; opaque fill is its contrast against the wash */` marker on that line.

The note is brass glass, so its text sits on the brass wash over the backdrop, not on a palette colour. Pin that the same way every other glass text is pinned. In `mobile/src/theme/glassContrast.test.ts`, inside the `describe.each`, after the test "every text token clears AA on a glass card, at every pixel", add:

```ts
  // Financial Note: the brass wash at glass alpha instead of the neutral tint. Measured when
  // added: light 14.45 / 6.14 / 5.14, dark 14.36 / 8.03 / 7.04 at the worst pixel.
  it('the Financial Note text clears AA on brass glass, at every pixel', () => {
    const brassGlass = (px: number[]) => over(rgb(p.brassBg), p.glassAlpha, px);
    expectAA(worst(['ink', 'mutedInk', 'brassInk'], brassGlass), 'brass glass');
  });
```

And add one test to `FinancialNoteCard.test.tsx` (add `import { StyleSheet } from 'react-native';` and `import { light } from '../../theme/palette';`):

```tsx
  // jest does not know the Reduce Transparency setting, which is the solid path.
  it('is opaque brass with a brass edge when transparency is reduced or unknown', () => {
    render(<ThemeProvider><FinancialNoteCard factor="Emergency Fund" potentialGain={14} onCreateGoal={jest.fn()} /></ThemeProvider>);
    const style = StyleSheet.flatten(screen.getByTestId('financial-note').props.style);
    expect(style.backgroundColor).toBe(light.brassBg);
    expect(style.borderColor).toBe(light.brass);
  });
```

- [ ] **Step 3: Run the tests**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/GoalsRow.test.tsx src/components/dashboard/FinancialNoteCard.test.tsx src/theme/glassContrast.test.ts
```

Expected: PASS without editing either test file.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/components/dashboard/GoalsRow.tsx mobile/src/components/dashboard/FinancialNoteCard.tsx mobile/src/components/dashboard/FinancialNoteCard.test.tsx mobile/src/theme/glassContrast.test.ts
git commit -m "feat(mobile): goal tiles and financial note in the redesigned card language"
```

---

### Task 9: Screen layout, header and section order

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx`, `mobile/src/screens/DashboardScreen.test.tsx`, `mobile/.maestro/flows/dashboard.yaml`

This task changes layout only: paddings, header, the order of the balance card, month grid and cash flow trend, and the wrappers around Spending and Goals. Recent Transactions and Quick Actions are Task 10. Everything below Quick Actions keeps its current look until pull request 2.

- [ ] **Step 1: Read the two tests that describe the old row**

Open `DashboardScreen.test.tsx` at `describe('Cash Flow Mini / Accounts row', …)` (near line 746). Read both tests. They assert which cards are present, not that they share a row. Rename the block and tests to say what is now true and keep the assertions:

```tsx
describe('Cash flow trend and balance cards', () => {
  it('does not render the cash flow trend card when there is no monthly data', async () => { /* body unchanged */ });
  it('renders the cash flow trend card and the balance card once monthly data exists', async () => { /* body unchanged */ });
```

If either body asserts a row layout (a shared parent, a `flexDirection`), stop and replace that assertion with presence checks for `'Cash Flow Trend'` and `'Total Balance'`.

- [ ] **Step 2: Add a failing order test**

Add to `DashboardScreen.test.tsx`, using the file's existing render helper and a summary fixture that has monthly data (reuse the fixture the "renders the cash flow trend card" test uses):

```tsx
describe('story-layer order (card redesign)', () => {
  it('puts the balance before the month figures, and the cash flow trend after them', async () => {
    // render with the same fixture as the test above
    await screen.findByText('Cash Flow Trend');
    const json = JSON.stringify(screen.toJSON());
    const at = (s: string) => json.indexOf(`"${s}"`);
    expect(at('Total Balance')).toBeGreaterThan(-1);
    expect(at('Total Balance')).toBeLessThan(at('Income'));
    expect(at('Net Savings')).toBeLessThan(at('Cash Flow Trend'));
    expect(at('Cash Flow Trend')).toBeLessThan(at('Spending by Category'));
  });
});
```

Run and confirm it fails on the first `toBeLessThan` (today the balance comes after the month figures).

- [ ] **Step 3: Update imports in `DashboardScreen.tsx`**

Add:

```tsx
import { DashboardCard } from '../components/dashboard/DashboardCard';
import { DashboardSectionHeader } from '../components/dashboard/DashboardSectionHeader';
import { GlassSurface } from '../components/GlassSurface';
```

Change the theme import to:

```tsx
import { fonts, radius, spacing, typography, useTheme } from '../theme';
```

- [ ] **Step 4: Update the chart width**

Replace:

```tsx
  const chartWidth = width - spacing.md * 2 - spacing.md * 2;
```

with:

```tsx
  // Screen side padding and the card's own padding, both spacing.ml since the card redesign.
  // Stays in step with styles.content and DashboardCard's regular padding; the full Cash Flow
  // card moves onto DashboardCard in pull request 2, so until then it keeps Card's spacing.md.
  const chartWidth = width - spacing.ml * 2 - spacing.md * 2;
```

- [ ] **Step 5: Restyle the header**

Replace the greeting row's two `Text` elements and the search `Pressable`'s visual wrapper. Handlers, labels and `hitSlop` stay:

```tsx
      <View style={styles.greetingRow}>
        <View style={styles.greetingText}>
          <Text style={[typography.screenTitle, { color: c.ink }]}>
            {greeting(settingsQ.data?.timezone)}, {firstName}
          </Text>
          <Text style={[typography.bodyM, styles.subGreeting, { color: c.mutedInk }]}>
            Here's what's happening with your finances.
            {!periodIsCurrent && ` Your latest figures are from ${periodLabel}.`}
          </Text>
        </View>
        <Pressable
          onPress={() => {
            trackNavSearch();
            trackNavigation('transactions', 'search');
            navigation.navigate('Transactions');
          }}
          hitSlop={10}
          accessibilityRole="button"
          accessibilityLabel="Search transactions"
        >
          <GlassSurface style={styles.searchButton}>
            <Ionicons name="search-outline" size={20} color={c.ink} />
          </GlassSurface>
        </Pressable>
      </View>
```

Keep the two existing comments above the `Pressable` (the Phase 5 note and the tracking note) where they are.

- [ ] **Step 6: Reorder the story layer**

Find the block that starts at `<View style={styles.section}>` containing `<LedgerSnapshotCard` and ends after the `summary ? ( showCashFlowMini ? … ) : null` expression. Replace both with:

```tsx
      {/* Card redesign (2026-10-10): the balance is its own full-width card, ahead of the month's
          figures. It used to share a row with Cash Flow Trend, where both were too narrow for
          their own numbers (the old CashFlowMiniCard comment records the overflow). */}
      {summary ? (
        <View style={styles.section}>
          <AccountsCard
            accounts={accountsQ.data ?? []}
            totalBalance={balanceKpi?.value ?? 0}
            caption={balanceKpi?.caption ?? ''}
            onViewAll={() => { trackNavigation('accounts', 'contextual'); navigation.navigate('More', { screen: 'Accounts' }); }}
          />
        </View>
      ) : null}

      <View style={styles.section}>
        {summary ? (
          <LedgerSnapshotCard kpis={snapshotKpis} title={periodTitle} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        ) : (
          <SkeletonCard lines={4} />
        )}
        {/* (keep the existing unresolved-inflow comment and its whole Pressable/Card block here, unchanged) */}
      </View>

      {summary && showCashFlowMini ? (
        <View style={styles.section}>
          <CashFlowMiniCard points={cashFlowPoints} deltaPct={summary.netDeltaPct} deltaLabel={deltaLabel} />
        </View>
      ) : null}
```

The unresolved-inflow banner block is moved verbatim, not rewritten.

- [ ] **Step 7: Move Spending and Goals onto the new header and card**

Spending:

```tsx
      <DashboardCard style={styles.section}>
        <DashboardSectionHeader title="Spending by Category" />
        <View style={styles.cardBody}>
          {/* existing summary ? (donutSlices.length === 0 ? <EmptyState …/> : <DonutChart …/>) : <SkeletonChart variant="donut" /> expression, unchanged */}
        </View>
      </DashboardCard>
```

Goals:

```tsx
      <View style={[styles.section, styles.sectionStack]}>
        <DashboardSectionHeader title="Goals" />
        <GoalsRow goals={goalsQ.data ?? []} />
      </View>
```

Do not add a "See all" action to Goals. A new navigation entry point needs a `trackNavigation` call and a decision about the navigation baseline; it is out of scope.

- [ ] **Step 8: Update the styles**

In the `StyleSheet.create` block change or add these entries and delete `cardRow`, `cardRowItem`, `cardRowSingle`, `greeting`:

```ts
  content: { padding: spacing.ml, paddingBottom: spacing.xl },
  greetingRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.ms },
  greetingText: { flex: 1 },
  subGreeting: { marginTop: 2, marginBottom: spacing.md },
  searchButton: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  section: { marginTop: spacing.md },
  sectionStack: { gap: spacing.ms },
  cardBody: { marginTop: spacing.md },
```

- [ ] **Step 9: Run the screen suite**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/screens/DashboardScreen.test.tsx
```

Expected: PASS, including the new order test. For any other failure, read the failing assertion and the rows it names before changing anything. A failure here means a pinned string, `testID` or label moved; the fix is to restore it, not to edit the test.

- [ ] **Step 10: Update the Maestro flow comment**

In `mobile/.maestro/flows/dashboard.yaml` the steps stay as they are (every asserted string still exists and `scrollUntilVisible` passes at once when its target is already on screen). Replace the sentence that describes the effective order for the seeded account with:

```yaml
# Card redesign (2026-10-10) order for this account: Balance card (Total Balance) ->
# month tiles (Income/Expenses/Net Savings/Savings Rate) -> Spending by Category -> Goals
# (heading only) -> Recent Transactions -> Quick Actions -> Cash Flow (full) -> Budget Progress
# -> Getting Started. Total Balance is now ABOVE the month tiles; the scrollUntilVisible below
# finds it without scrolling.
```

This flow was not run as part of this plan. Say so in the pull request.

- [ ] **Step 11: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx mobile/.maestro/flows/dashboard.yaml
git commit -m "feat(mobile): dashboard story layer in the redesigned order, with the balance card full width"
```

---

### Task 10: Recent Transactions and Quick Actions

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx`, `mobile/src/screens/DashboardScreen.test.tsx`

**Amount colours do not change (Sid, 2026-10-10):** income is `successInk`, an expense is `dangerInk`, exactly as on `main` today. The Figma frame wrote expenses in ordinary ink; Sid reversed that, so red stays. Measured on the real mesh backdrop: `dangerInk` on a glass card is 7.62:1 in the light theme and 7.12:1 in the dark theme at the worst pixel; `successInk` is 6.54:1 and 5.93:1.

- [ ] **Step 1: Add failing tests**

Add to `DashboardScreen.test.tsx`, reusing the file's render helper and a fixture with one income and one expense transaction (read the fixtures at the top of the file and reuse an existing pair; note the two descriptions it uses):

```tsx
describe('Recent Transactions amounts (card redesign)', () => {
  it('keeps income in the success ink and expenses in the danger ink', async () => {
    // render with a fixture containing one INCOME row and one EXPENSE row
    const income = await screen.findByText(/^\+₹/);
    const expense = screen.getByText(/^-₹/);
    expect(income).toHaveStyle({ color: light.successInk });
    expect(expense).toHaveStyle({ color: light.dangerInk });
  });
});

describe('Quick Actions tiles (card redesign)', () => {
  it('gives every tile a target at least 44 points tall', async () => {
    // render with any non-failing fixture
    const tile = await screen.findByLabelText('Import Statement');
    expect(StyleSheet.flatten(tile.props.style).minHeight).toBeGreaterThanOrEqual(44);
  });
});
```

If the regular expressions match more than one node with the chosen fixture, narrow them to the fixture's exact amounts. The amount test pins behaviour that already exists, so it passes before the restyle and must still pass after it; only the tile test fails first. Run and confirm exactly that.

- [ ] **Step 2: Replace the Recent Transactions card**

```tsx
      <DashboardCard style={styles.section}>
        <DashboardSectionHeader title="Recent Transactions" />
        <View style={styles.cardBody}>
          {recentTxnsQ.isLoading ? (
            <>
              <SkeletonTransactionRow />
              <SkeletonTransactionRow />
              <SkeletonTransactionRow />
            </>
          ) : recentTxnsQ.isError ? (
            // (keep the existing comment about a failed request not being an answer of zero)
            <Text style={[typography.bodyM, { color: c.dangerInk }]}>
              Couldn&apos;t load your transactions — pull down to try again.
            </Text>
          ) : recentTxns.length === 0 ? (
            <EmptyState
              message="No transactions yet. Import a statement to get started."
              actionLabel="Import a statement"
              onAction={() => { trackNavigation('import-statement', 'contextual'); navigation.navigate('Import'); }}
            />
          ) : (
            recentTxns.map((t, i) => (
              <View
                key={t.id}
                style={[styles.txnRow, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}
              >
                <IconWell
                  name={t.type === 'INCOME' ? 'arrow-down-outline' : 'arrow-up-outline'}
                  tone={t.type === 'INCOME' ? 'success' : 'neutral'}
                />
                <View style={styles.txnMain}>
                  <Text style={[typography.labelM, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                    {t.description || t.merchant || 'Transaction'}
                  </Text>
                  <Text style={[typography.bodyS, { color: c.mutedInk }]} numberOfLines={1}>
                    {t.categoryName} · {t.date}
                  </Text>
                </View>
                <Text style={[typography.numberS, { color: t.type === 'INCOME' ? c.successInk : c.dangerInk }]}>
                  {t.type === 'INCOME' ? '+' : '-'}
                  {fmtCurrency(Math.abs(t.amount))}
                </Text>
              </View>
            ))
          )}
        </View>
      </DashboardCard>
```

Add the import:

```tsx
import { IconWell } from '../components/dashboard/IconWell';
```

Keep the passbook-reorder comment that sits above this card.

- [ ] **Step 3: Replace the Quick Actions card**

Keep the existing comment above it and the six-entry array exactly as written. Replace the wrapper and the cell:

```tsx
      <View style={[styles.section, styles.sectionStack]}>
        <DashboardSectionHeader title="Quick Actions" />
        <View style={styles.quickActionsGrid}>
          {(
            [ /* the six existing entries, unchanged */ ] as const
          ).map((action) => (
            <Pressable
              key={action.label}
              onPress={action.onPress}
              style={styles.quickActionCell}
              accessibilityRole="button"
              accessibilityLabel={action.label}
            >
              <DashboardCard padding="compact" style={styles.quickActionCard}>
                <IconWell name={action.icon} round />
                <Text style={[typography.labelS, styles.quickActionLabel, { color: c.ink }]} numberOfLines={2}>
                  {action.label}
                </Text>
              </DashboardCard>
            </Pressable>
          ))}
        </View>
      </View>
```

The old cell's `backgroundColor: c.bg … /* glass-exempt */` line is gone with the old cell; nothing replaces it.

- [ ] **Step 4: Update the styles**

```ts
  txnRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms, paddingVertical: 10 },
  txnMain: { flex: 1, gap: 2 },
  quickActionsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  // Three per row at any phone width: 30% each plus flexGrow across two 12 point gaps.
  quickActionCell: { flexBasis: '30%', flexGrow: 1, minHeight: 104 },
  quickActionCard: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10 },
  quickActionLabel: { textAlign: 'center' },
```

Delete `txnDesc`, `txnMeta`, `txnAmount`. Leave `errorText` in place; the Cash Flow card further down still uses it until pull request 2.

- [ ] **Step 5: Run the screen suite and the glass guard**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/screens/DashboardScreen.test.tsx src/theme/glassMigration.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx
git commit -m "feat(mobile): recent transactions rows and quick action tiles in the redesigned card language"
```

---

### Task 11: Full automated verification for pull request 1

**Files:** none, unless a check fails.

- [ ] **Step 1: Whole mobile suite on Node 22**

From `<worktree>/mobile`:

```bash
node -v
```

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit
```

Expected: `v22.x`, then every suite green. Compare suite and test counts with the Task 0 baseline: suites up by 5 (`typography`, `primitives`, `heroGauge`, `heroTones`, and `health` if it was created), no suite missing.

- [ ] **Step 2: Types and lint**

```bash
npm run typecheck
```

```bash
npm run lint
```

Expected: both exit 0 with no warnings.

- [ ] **Step 3: Repository guards, from the repository root**

```bash
python3 scripts/check-reporting-period-labels.py
```

```bash
python3 scripts/check-nav-tracking-coverage.py
```

```bash
python3 scripts/check-nav-taxonomy-drift.py
```

Expected: all exit 0.

- [ ] **Step 4: Read your own diff as a reviewer**

```bash
git diff origin/main...HEAD -- mobile/src
```

Look specifically for: a style key left in `DashboardScreen.tsx` that nothing uses any more; an import that is no longer used; a comment that describes the old row layout; a `Text` whose string changed; a pressable under 44 points. Fix what you find, rerun Steps 1 to 3, and commit the fixes as their own commit.

---

### Task 12: Visual verification on devices

**Files:** none, unless a defect is found.

Automated tests cannot see blur, clipping or overlap. This task is the evidence for "it looks right". Nothing here is assumed; each bullet is a screenshot you take.

- [ ] **Step 1: Find out what can run**

```bash
xcrun simctl list devices booted
```

```bash
xcrun simctl listapps booted | grep -i fynora
```

A fresh worktree has no `GoogleService-Info.plist` or `google-services.json`, so `expo run:ios` fails at prebuild. If a development build of the app is already installed on a simulator, start Metro from the worktree and open that build; it loads this branch's JavaScript:

```bash
npx expo start --dev-client
```

If no development build is installed, stop and ask Sid whether to copy the two Firebase files from the primary checkout into the worktree. Do not copy credential files without that answer.

Which backend the development build talks to, and which account to sign in with, is not established by this plan. Read `mobile/.env` in the primary checkout before assuming, and ask Sid for an account that has imported statements, goals and budgets.

- [ ] **Step 2: Capture the matrix**

For each row, reload the JavaScript bundle after changing appearance or text size (a `simctl` change alone leaves stale layout), then screenshot the Home tab from top to Quick Actions.

| Device | Theme | Text size | What to look for |
|---|---|---|---|
| iPhone 17 Pro (iOS 26) | light | default | matches the Figma frame |
| iPhone 17 Pro (iOS 26) | dark | default | every row of "Dark theme audit": hero is cream glass with a readable score, soft labels and dial; KPI and quick action tiles separate from the backdrop; neutral icon wells visible on cards; chips, bars and the cash flow line readable; no white or light-theme surface anywhere |
| iPhone 17 Pro (iOS 26) | dark, Reduce Transparency on | default | solid `card` on `bg` is 1.25:1, so the hairline edge carries the separation: every card and tile edge visible |
| iPhone 17 Pro | dark | accessibility large | KPI grid in one column; chip and amount text still on its own wash or card |
| iPhone SE 3rd generation (375 wide) | dark | default | same as the light row |
| iPhone SE 3rd generation (375 wide) | light | default | KPI numbers not clipped; factor and goal tiles not cut mid-word |
| iPhone 17 Pro | light | accessibility large | KPI grid in one column; nothing overlaps |
| iPhone 17 Pro | light, Reduce Transparency on | default | solid cards, tiles still distinct |
| Android emulator (360 wide if available) | light and dark | default | same checks; this is the solid-tile path |

- [ ] **Step 3: Confirm the glass, then measure what it costs**

Sid's instruction (2026-10-10): the redesign uses the glass UI. KPI tiles, quick action tiles and the search button are glass `panel` surfaces like every other card, so on iOS they are native effect views. Against `main` that is 13 new ones (4 KPI tiles, 6 quick action tiles, the search button, the hero, the Financial Note) and 2 fewer (the old month card and the old quick actions card).

1. On the iOS 26 screenshot, confirm the hero, the Financial Note, a KPI tile, a quick action tile and the search button show the same glass as a full card. With the React Native debugger attached, check in the native view hierarchy (`argent` `native-find-views`) that each is a `GlassView` (iOS 26) and not a plain view. Do the same on an iOS 18 simulator if one is installed and expect `BlurView`; if none is installed, say so in the pull request instead of claiming it.
2. Record a top-to-bottom scroll of the Home tab with the native profiler (`argent` `native-profiler-start` / `-stop` / `-analyze`) on this branch and on `main`, same simulator, same account. Report dropped or slow frames for both.
3. If this branch drops more frames than `main`, do not quietly switch surfaces. Report both numbers to Sid with the one fallback available: `variant="row"` on the six quick action tiles (tinted glass colour, no native effect), keeping the KPI tiles and every card as real glass. Sid decides.

- [ ] **Step 4: Fix what the screenshots show**

For each defect: read the component, change the source, rerun that component's tests, reload, re-screenshot. A screenshot is the proof; "should be fixed" is not.

- [ ] **Step 5: Stop the app you started**

```bash
xcrun simctl terminate booted com.fynora.app
```

Stop Metro. Leave no temporary harness file in the branch (`git status --short` must show only intended changes).

---

### Task 13: Open pull request 1

- [ ] **Step 1: Check what is and is not on `main`, by content**

```bash
git fetch origin
```

```bash
git log --oneline origin/main..HEAD
```

```bash
git ls-tree -r --name-only origin/main | grep mobile/src/lib/heroTones.ts
```

Expected: your commits listed; the `grep` prints nothing.

- [ ] **Step 2: Bring `main` in if it moved, then rerun Task 11**

Use the app's `sync_with_base_branch` tool if this worktree was created by the app; otherwise merge `origin/main` yourself. Rerun Task 11 Steps 1 to 3 after any merge.

- [ ] **Step 3: Confirm no attribution trailer**

```bash
git log origin/main..HEAD --format=%B | grep -i -c "co-authored-by"
```

Expected: `0`.

- [ ] **Step 4: Push and open the pull request**

```bash
git push -u origin feature/mobile-dashboard-cards
```

```bash
gh repo view --json visibility -q .visibility
```

If `PUBLIC`, open the pull request. If `PRIVATE`, stop and ask Sid before opening one.

The pull request description must include: the six screenshots from Task 12; the list "Deliberate differences from the Figma frame" from this plan; the note "amount colours unchanged: income green, expenses red"; the dark-theme contrast table from "Dark theme audit"; what was not verified (the Maestro flow was not run; iOS below 26 blur path; a physical device); and the release rule (JavaScript only, 1.1.0 runtime only, never 1.0.0).

- [ ] **Step 5: CI**

If the repository is private, add the `full-ci` label once the branch is final and push an empty commit to start the run; skipped jobs prove nothing. Wait for the mobile job's Lint, Type-check and Test steps to actually run and pass. Do not push again between the passing run and the merge.

- [ ] **Step 6: After merge**

```bash
gh pr view <number> --json state,mergedAt,headRefOid
```

```bash
git log --oneline -1
```

`headRefOid` must equal the branch tip. Then confirm by content that `mobile/src/lib/heroTones.ts` is on `origin/main`. Remove the worktree. Do not reuse this branch.

---

# Pull request 2: operational layer and states

Start from a fresh worktree off the updated `origin/main` (`feature/mobile-dashboard-cards-2`). Repeat Task 0 Steps 1 to 4 there.

### Task 14: Empty state and skeleton pieces

**Files:**
- Create: `mobile/src/components/dashboard/DashboardEmptyState.tsx`
- Modify: `mobile/src/components/skeletons/Skeletons.tsx`
- Test: `mobile/src/components/dashboard/DashboardEmptyState.test.tsx`, `mobile/src/components/skeletons/Skeletons.test.tsx`

**Interfaces:**
- Produces: `DashboardEmptyState({ icon: IoniconName, message: string, actionLabel?: string, onAction?: () => void })`; `SkeletonKpiGrid()` with `testID="skeleton-kpi-grid"`.

- [ ] **Step 1: Write the failing tests**

`DashboardEmptyState.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { DashboardEmptyState } from './DashboardEmptyState';
import { ThemeProvider } from '../../theme';

describe('DashboardEmptyState', () => {
  it('shows the message and no button when there is nothing to do about it', () => {
    render(<ThemeProvider><DashboardEmptyState icon="wallet-outline" message="No budgets set. Create one to track your spending." /></ThemeProvider>);
    expect(screen.getByText('No budgets set. Create one to track your spending.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('offers its action by its visible text, with a 44 point target', () => {
    const onAction = jest.fn();
    render(<ThemeProvider><DashboardEmptyState icon="receipt-outline" message="No transactions yet." actionLabel="Import a statement" onAction={onAction} /></ThemeProvider>);
    fireEvent.press(screen.getByText('Import a statement'));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(StyleSheet.flatten(screen.getByRole('button').props.style).minHeight).toBe(44);
  });
});
```

Add to `Skeletons.test.tsx`:

```tsx
  it('SkeletonKpiGrid stands in for four tiles', () => {
    render(<SkeletonKpiGrid />);
    expect(screen.getByTestId('skeleton-kpi-grid').children).toHaveLength(4);
  });
```

(Add `SkeletonKpiGrid` to that file's import.) Run and confirm both fail.

- [ ] **Step 2: Implement `DashboardEmptyState.tsx`**

```tsx
import type { ComponentProps } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { IconWell } from './IconWell';
import { spacing, typography, useTheme } from '../../theme';

type IoniconName = ComponentProps<typeof Ionicons>['name'];

/** The dashboard's "nothing here yet". Card.tsx's EmptyState stays for the rest of the app. */
export function DashboardEmptyState({
  icon, message, actionLabel, onAction,
}: { icon: IoniconName; message: string; actionLabel?: string; onAction?: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.wrap}>
      <IconWell name={icon} round size={48} />
      <Text style={[typography.bodyM, styles.message, { color: c.mutedInk }]}>{message}</Text>
      {actionLabel && onAction ? (
        <Pressable onPress={onAction} style={[styles.action, { backgroundColor: c.primary }]} accessibilityRole="button">
          <Text style={[typography.labelS, { color: c.onPrimary }]}>{actionLabel}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { alignItems: 'center', gap: spacing.ms, paddingVertical: spacing.sm },
  message: { textAlign: 'center' },
  action: { minHeight: 44, paddingHorizontal: spacing.md, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
```

- [ ] **Step 3: Add `SkeletonKpiGrid` to `Skeletons.tsx`**

```tsx
import { DashboardCard } from '../dashboard/DashboardCard';

/** Stands in for LedgerSnapshotCard's four tiles, so the grid does not jump when figures land. */
export function SkeletonKpiGrid() {
  return (
    <View style={styles.kpiGrid} testID="skeleton-kpi-grid">
      {Array.from({ length: 4 }).map((_, i) => (
        <DashboardCard key={i} padding="compact" style={styles.kpiTile}>
          <Shimmer width="50%" height={11} style={styles.line} />
          <Shimmer width="80%" height={22} style={styles.line} />
          <Shimmer width="40%" height={18} borderRadius={9} />
        </DashboardCard>
      ))}
    </View>
  );
}
```

Add to that file's styles:

```ts
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  kpiTile: { flexBasis: '47%', flexGrow: 1 },
```

Check that importing `DashboardCard` here does not create an import cycle (`DashboardCard` imports `GlassSurface` and `theme` only; confirm with `npm run typecheck` and a run of the suite).

- [ ] **Step 4: Run and commit**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/DashboardEmptyState.test.tsx src/components/skeletons
```

```bash
git add mobile/src/components/dashboard/DashboardEmptyState.tsx mobile/src/components/dashboard/DashboardEmptyState.test.tsx mobile/src/components/skeletons
git commit -m "feat(mobile): dashboard empty state and a KPI grid skeleton"
```

---

### Task 15: Operational sections in `DashboardScreen.tsx`

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx`, `mobile/src/screens/DashboardScreen.test.tsx`

Every section below keeps its data, conditions, comments, strings, accessibility labels and handlers. Only the wrapper (`Card` to `DashboardCard`), the heading (`SectionHeading` to `DashboardSectionHeader`), the text styles and the bars change. Do one section, run the screen suite, then the next.

- [ ] **Step 1: Add failing tests for the three behaviours that change**

```tsx
describe('operational layer (card redesign)', () => {
  it('keeps Manage Budgets reachable by its label, now from the section header', async () => {
    // render with the existing budgets fixture
    const button = await screen.findByLabelText('Manage Budgets');
    expect(StyleSheet.flatten(button.props.style).minHeight).toBe(44);
  });

  it('never draws a budget bar past its track for an over-budget category', async () => {
    // render with the existing over-budget fixture (the one the "danger colour" test uses)
    const fill = await screen.findByTestId('budget-bar-0-fill');
    expect(StyleSheet.flatten(fill.props.style).width).toBe('100%');
  });

  it('keeps each cash flow range choice at least 44 points tall', async () => {
    const chip = await screen.findByLabelText('Show 6 months');
    expect(StyleSheet.flatten(chip.props.style).minHeight).toBe(44);
  });
});
```

Use the index of the over-budget row in the fixture for the `budget-bar-<index>` id. Run and confirm the first two fail.

- [ ] **Step 2: Month section skeleton and empty states**

Replace `<SkeletonCard lines={4} />` in the month section with `<SkeletonKpiGrid />`. Replace the three dashboard `EmptyState` uses with `DashboardEmptyState`, keeping message, action label and handler:

| Section | icon |
|---|---|
| Spending by Category | `pie-chart-outline` |
| Recent Transactions | `receipt-outline` |
| Budget Progress | `wallet-outline` |

Remove `EmptyState` from the `Card` import if nothing else in the file uses it.

- [ ] **Step 3: Banners (statement gap, review queue, limited history, money not counted)**

Each keeps its outer `Pressable` and its `Card` with the existing `backgroundColor` where it has one; `GlassSurface` moves a caller tint onto the right layer, so these stay on `Card`. Change only: add `borderRadius: radius.xxl` to the four banner style entries, and replace the font sizes and weights in their text styles with the ramp:

```ts
  nudge: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.md, borderRadius: radius.xxl },
  nudgeText: { flex: 1, marginRight: spacing.sm, gap: 2 },
  coverageBanner: { marginBottom: spacing.md, borderRadius: radius.xxl, gap: 2 },
  limitedHistoryBanner: { marginBottom: spacing.md, borderRadius: radius.xxl, gap: 2 },
  unresolvedBanner: { marginTop: spacing.md, borderRadius: radius.xxl, gap: 2 },
```

In the JSX, titles take `typography.labelM`, bodies take `typography.bodyS`, calls to action take `typography.labelS`. Replace the `›` chevron `Text` in the review nudge with `<Ionicons name="chevron-forward" size={18} color={c.muted} />`, keeping its two accessibility-hiding props on a wrapping `View`. The unresolved banner's last line currently sets `fontWeight: '600'` inline; replace that with `typography.labelS`.

Delete the now-unused entries: `nudgeTitle`, `nudgeBody`, `nudgeChevron`, `coverageBannerTitle`, `coverageBannerBody`, `coverageBannerCta`, `limitedHistoryTitle`, `limitedHistoryBody`, `unresolvedTitle`, `unresolvedBody`.

`StatementRefreshBanner` is its own component with its own tests and is not restyled in this plan.

- [ ] **Step 4: Upcoming**

Wrapper and heading:

```tsx
        <DashboardCard style={styles.section}>
          <DashboardSectionHeader title="Upcoming" />
          <View style={styles.cardBody}>
            {/* existing upcomingRecurring.map and changedAmounts.map, restyled as below */}
          </View>
        </DashboardCard>
```

Inside each row: merchant takes `typography.labelM`; the badge keeps its border and fill and takes `typography.caption`; the amount takes `typography.numberS` and keeps `color: c.dangerInk` as on `main` (amount colours do not change, see Task 10; the existing comment about no "-" prefix stays); the "expected" line takes `typography.caption`. The dismiss button keeps `minWidth: 44, minHeight: 44`. Change the row separator from a bottom border on every row to a top border on every row but the first, so the card does not end on a line:

```tsx
            <View key={r.merchant} style={[styles.recurringItem, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}>
```

(`i` is the map index; add it to both `.map` callbacks. For `changedAmounts` the row has a top border when `upcomingRecurring.length > 0 || i > 0`.)

```ts
  recurringItem: { paddingVertical: 10 },
  recurringBadge: { alignSelf: 'flex-start', marginTop: 4, paddingHorizontal: 8, paddingVertical: 2, borderRadius: 999, overflow: 'hidden' },
```

Delete `recurringMerchant`, `recurringAmount`, `recurringMeta`. `RecurringQuestion` is its own component and is not restyled here.

- [ ] **Step 5: Categorization Confidence**

```tsx
        <DashboardCard style={styles.section}>
          <View style={styles.confidenceTop}>
            <View style={styles.confidenceText}>
              <Text accessibilityRole="header" style={[typography.eyebrow, { color: c.mutedInk }]}>Categorization Confidence</Text>
              <View style={styles.confidenceRow}>
                <Text style={[typography.numberL, { color: c.ink }]}>{summary.categorizationConfidenceScore}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>out of 100</Text>
              </View>
            </View>
            <Text style={[typography.labelS, { color: healthLabelInk(summary.categorizationConfidenceScore, c) }]}>
              {scoreLabel(summary.categorizationConfidenceScore)}
            </Text>
          </View>
          <ProgressBar percent={summary.categorizationConfidenceScore} color={healthBarColor(summary.categorizationConfidenceScore, c)} />
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>
            Based on {summary.categorizationConfidenceTransactionCount} automatically categorized
            transaction{summary.categorizationConfidenceTransactionCount === 1 ? '' : 's'} {periodLabel}.
          </Text>
        </DashboardCard>
```

```ts
  confidenceTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms },
  confidenceText: { gap: spacing.xs },
  confidenceRow: { flexDirection: 'row', alignItems: 'baseline', gap: 6 },
```

Give this card `gap: spacing.ms` through a new `styles.cardStack: { gap: spacing.ms }` added to its style array. Imports: `healthBarColor`, `healthLabelInk`, `scoreLabel` from `../lib/health` (drop `healthColor` if no longer used) and `ProgressBar`. Delete `healthScoreValue`, `healthScoreLabel`, `confidenceCaption`. The existing tests find `'Categorization Confidence'`, `'91'`, `'Excellent'` and the "Based on 23…" sentence by text; all four strings are unchanged.

- [ ] **Step 6: Next Actions**

```tsx
        <DashboardCard style={styles.section}>
          <DashboardSectionHeader title="Next Actions" />
          <View style={styles.cardBody}>
            {summary.notifications.length === 0 ? (
              <Text style={[typography.bodyM, { color: c.mutedInk }]}>Nothing needs your attention right now.</Text>
            ) : (
              <View style={styles.notificationList}>
                {summary.notifications.map((n, i) => (
                  <View key={i} style={styles.notificationRow}>
                    <IconWell name="warning-outline" tone="warning" round size={32} />
                    <Text style={[typography.bodyM, styles.notificationText, { color: c.ink }]}>{n}</Text>
                  </View>
                ))}
              </View>
            )}
          </View>
        </DashboardCard>
```

```ts
  notificationList: { gap: spacing.ms },
  notificationRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms },
  notificationText: { flex: 1 },
```

Delete `notificationIcon`.

- [ ] **Step 7: Detected Issues**

Wrapper and heading become `DashboardCard` and `DashboardSectionHeader title="Detected Issues"` with the body in `styles.cardBody`. The explanation and error lines take `typography.bodyS`; merchant takes `typography.labelM`; the date-and-amount line takes `typography.bodyS`; "Not a duplicate" / "Confirming…" takes `typography.labelS` with `textDecorationLine: 'underline'`. The action keeps `minHeight: 44`. Separator moves to a top border on rows after the first, as in Step 4. Delete `duplicateMerchant`, `duplicateMeta`; change `duplicateAction` to `{ textDecorationLine: 'underline' }`.

- [ ] **Step 8: Cash Flow (full)**

```tsx
      <DashboardCard style={styles.section}>
        <DashboardSectionHeader
          title="Cash Flow"
          accessory={
            <View style={[styles.rangeRow, { borderColor: c.border }]}>
              {(Object.keys(RANGE_MONTHS) as CashFlowRange[]).map((r) => (
                <Pressable
                  key={r}
                  onPress={() => setCashFlowRange(r)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: cashFlowRange === r }}
                  accessibilityLabel={`Show ${RANGE_MONTHS[r]} months`}
                  style={styles.rangeChip}
                >
                  <View style={[styles.rangePill, cashFlowRange === r && { backgroundColor: c.primary }]}>
                    <Text style={[typography.labelS, { color: cashFlowRange === r ? c.onPrimary : c.mutedInk }]}>{r}</Text>
                  </View>
                </Pressable>
              ))}
            </View>
          }
        />
        <View style={styles.cardBody}>
          {/* existing cashFlowSettling / cashFlowUnavailable / chart expression and its comment, unchanged,
              with the two error Text styles changed to typography.bodyM */}
        </View>
      </DashboardCard>
```

```ts
  rangeRow: { flexDirection: 'row', borderWidth: StyleSheet.hairlineWidth, borderRadius: 999, paddingHorizontal: 3 },
  // The pressable is the 44 point target; the pill inside it is the 30 point visual.
  rangeChip: { minHeight: 44, justifyContent: 'center' },
  rangePill: { paddingHorizontal: 12, minHeight: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
```

Now that this card is a `DashboardCard`, update the chart width to match its padding:

```tsx
  // Screen side padding plus DashboardCard's regular padding, both spacing.ml.
  const chartWidth = width - spacing.ml * 2 - spacing.ml * 2;
```

Delete `rangeText` and, if now unused, `errorText`.

- [ ] **Step 9: Budget Progress**

```tsx
      <DashboardCard style={styles.section}>
        <DashboardSectionHeader
          title="Budget Progress"
          actionLabel={budgets.length > 0 ? 'Manage Budgets' : undefined}
          onAction={budgets.length > 0
            ? () => { trackNavigation('budgets', 'contextual'); navigation.navigate('More', { screen: 'Budgets' }); }
            : undefined}
        />
        <View style={[styles.cardBody, styles.budgetList]}>
          {budgetsQ.isLoading ? (
            <SkeletonCard lines={2} />
          ) : budgetsQ.isError ? (
            <Text style={[typography.bodyM, { color: c.dangerInk }]}>Couldn&apos;t load your budgets.</Text>
          ) : budgets.length === 0 ? (
            <DashboardEmptyState icon="wallet-outline" message="No budgets set. Create one to track your spending." />
          ) : (
            budgets.map((b, i) => {
              // (keep the existing rawPct / barPct comment: the label shows the real figure, the bar is capped)
              const rawPct = b.monthlyLimit > 0 ? (b.spentThisMonth / b.monthlyLimit) * 100 : 0;
              const over = b.spentThisMonth > b.monthlyLimit;
              return (
                <View key={b.id} style={styles.budgetRow}>
                  <View style={styles.budgetHeader}>
                    <Text style={[typography.labelM, styles.budgetName, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {b.categoryName}
                    </Text>
                    <Text style={[typography.labelS, { color: over ? c.dangerInk : c.mutedInk }]}>
                      {rawPct.toFixed(0)}%
                    </Text>
                  </View>
                  <ProgressBar testID={`budget-bar-${i}`} percent={rawPct} color={over ? c.danger : c.primary} />
                  <Text style={[typography.caption, { color: c.mutedInk }]}>
                    {fmtCurrency(b.spentThisMonth)} of {fmtCurrency(b.monthlyLimit)}
                  </Text>
                </View>
              );
            })
          )}
        </View>
      </DashboardCard>
```

```ts
  budgetList: { gap: spacing.md },
  budgetRow: { gap: 6 },
  budgetHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  budgetName: { flex: 1 },
```

Delete `budgetPct`, `budgetMeta`, `progressTrack`, `progressFill`, `manageBudgets`, `manageBudgetsText`. The existing test "marks an over-budget category in the danger colour" finds the percentage text and checks `color: light.dangerInk`; that still holds. The existing test "opens the Budgets screen from Manage Budgets" presses `findByLabelText('Manage Budgets')`; the header action carries that label.

- [ ] **Step 10: Insights**

```tsx
        <DashboardCard style={styles.section}>
          <DashboardSectionHeader title="Insights" />
          <View style={[styles.cardBody, styles.insightList]}>
            {sentences.slice(0, 3).map((s) => (
              <View key={s} style={styles.insightRow}>
                {/* brassInk, not brass: brass measured 2.97:1 on a light glass card at the worst
                    backdrop pixel. brassInk is 5.82:1 there and the same value as brass in dark. */}
                <View style={[styles.insightDot, { backgroundColor: c.brassInk }]} />
                <Text style={[typography.bodyM, styles.insightText, { color: c.ink }]}>{s}</Text>
              </View>
            ))}
          </View>
        </DashboardCard>
```

```ts
  insightList: { gap: spacing.sm },
  insightRow: { flexDirection: 'row', gap: spacing.ms },
  insightDot: { width: 6, height: 6, borderRadius: 3, marginTop: 7 },
  insightText: { flex: 1 },
```

Delete `insight`. The old rows rendered `• {s}`. Search the test file for `• ` before removing the bullet character:

```bash
grep -n "• " mobile/src/screens/DashboardScreen.test.tsx
```

If a test matches a sentence with the bullet prefix, keep the `• ` in the string and drop the dot view instead. The string wins over the design.

- [ ] **Step 11: Run after each section, then the whole screen suite**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/screens/DashboardScreen.test.tsx src/theme/glassMigration.test.ts
```

Expected: PASS, including the three tests from Step 1.

- [ ] **Step 12: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx
git commit -m "feat(mobile): dashboard operational sections in the redesigned card language"
```

---

### Task 16: Your Journey and Getting Started

**Files:**
- Modify: `mobile/src/components/dashboard/JourneyWidget.tsx`, `mobile/src/onboarding/ChecklistWidget.tsx`
- Test: existing `JourneyWidget.test.tsx` and `ChecklistWidget.test.tsx`

- [ ] **Step 1: Read both test files first**

```bash
grep -n -E "getBy|queryBy|findBy|toHaveStyle" mobile/src/components/dashboard/JourneyWidget.test.tsx mobile/src/onboarding/ChecklistWidget.test.tsx
```

Note every string and label they pin. In particular check whether `ChecklistWidget.test.tsx` matches the `✅` / `⬜` characters. If it does, keep those characters as the row's accessible text and render the new indicator beside them only if both can coexist; otherwise keep the characters and skip the indicator. The string wins over the design.

- [ ] **Step 2: Restyle `JourneyWidget.tsx`**

Replace the returned JSX and styles; the two queries and the `highlight` guard stay:

```tsx
  return (
    <DashboardCard style={styles.card}>
      <DashboardSectionHeader title="Your Journey" />
      <Text style={[typography.labelM, { color: c.ink }]}>{highlight.title}</Text>
      {momentum && momentum.activeMonths > 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>
          Active {momentum.activeMonths} of the last {momentum.windowMonths} months
        </Text>
      ) : null}
      <Pressable onPress={onViewJourney} accessibilityRole="button" style={styles.link}>
        <Text style={[typography.labelS, { color: c.primary }]}>View your journey</Text>
        <Ionicons name="chevron-forward" size={14} color={c.primary} />
      </Pressable>
    </DashboardCard>
  );
```

```ts
const styles = StyleSheet.create({
  card: { marginTop: spacing.md, gap: spacing.sm },
  link: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44, alignSelf: 'flex-start' },
});
```

Imports: `DashboardCard`, `DashboardSectionHeader`, `Ionicons`, and `spacing, typography, useTheme` from the theme; drop `Card, SectionHeading`. The old card had no top margin of its own; check in the simulator that the gap above it equals the gap above every other card, and remove `marginTop` here if the screen already supplies it.

- [ ] **Step 3: Restyle `ChecklistWidget.tsx`**

Keep the query, the collapse state, the header `Pressable` with its accessibility props, and `CHECKLIST_ITEMS`. Change the wrapper to `DashboardCard`, the title to `typography.cardTitle`, the progress line to `typography.labelS`, the bar to `ProgressBar`, and each row to an indicator plus label:

```tsx
          {CHECKLIST_ITEMS.map((item) => {
            const done = completedKeys.has(item.key);
            return (
              <View key={item.key} style={styles.row}>
                <View style={[styles.indicator, done ? { backgroundColor: c.success, borderColor: c.success } : { borderColor: c.muted }]}>
                  {done ? <Ionicons name="checkmark" size={14} color={c.onPrimary} /> : null}
                </View>
                <Text style={[typography.bodyM, { color: done ? c.mutedInk : c.ink }]}>{item.label}</Text>
              </View>
            );
          })}
```

```ts
const styles = StyleSheet.create({
  card: { marginTop: spacing.md, marginBottom: spacing.md },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 44 },
  body: { marginTop: spacing.ms, gap: spacing.ms },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms },
  indicator: { width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center' },
});
```

A row no longer says "done" in text once the `✅` is gone, so give each row `accessible` and `accessibilityLabel={`${item.label}, ${done ? 'done' : 'not done'}`}`. Add a test for that label to `ChecklistWidget.test.tsx` before changing the component, and watch it fail first.

The tick is `c.onPrimary` on `c.success`: white on `#16a34a` in the light theme and `#15171C` on `#22c55e` in the dark theme. A tick is a graphic and needs 3:1. Measured from the palette: 3.30:1 in the light theme and 7.87:1 in the dark theme, so `c.onPrimary` stays. The light value is close to the floor; look at it in the simulator at default size as part of the matrix.

- [ ] **Step 4: Run and commit**

```bash
NODE_OPTIONS=--experimental-vm-modules node ./node_modules/jest/bin/jest.js --forceExit src/components/dashboard/JourneyWidget.test.tsx src/onboarding/ChecklistWidget.test.tsx
```

```bash
git add mobile/src/components/dashboard/JourneyWidget.tsx mobile/src/onboarding/ChecklistWidget.tsx mobile/src/onboarding/ChecklistWidget.test.tsx
git commit -m "feat(mobile): journey and getting started cards in the redesigned card language"
```

---

### Task 17: Verify and open pull request 2

- [ ] **Step 1:** Repeat Task 11 (whole suite on Node 22, typecheck, lint, the three repository guards, reviewer read of the diff). In the diff read, also search `DashboardScreen.tsx` for style keys no JSX references:

```bash
grep -o -E "styles\.[A-Za-z]+" mobile/src/screens/DashboardScreen.tsx | sort -u
```

Compare that list with the keys defined in the file's `StyleSheet.create` and delete any defined key that is not in the list.

- [ ] **Step 2:** Repeat Task 12 for the lower half of the screen: scroll from Quick Actions to the end on the same device matrix, dark rows included. Add three state captures, each in light and in dark: a brand-new account (locked hero, empty Recent Transactions, empty Budgets, empty Spending), the cold-start skeletons, and an account with a statement gap and a review queue (banners).

- [ ] **Step 3:** Repeat Task 13 for this branch. The content check uses `mobile/src/components/dashboard/DashboardEmptyState.tsx`.

- [ ] **Step 4:** After merge, remove the worktree and confirm the merge included the branch tip (`headRefOid` equals `git log --oneline -1`).

---

## Out of scope, stated so it is not mistaken for done

- Dark-mode Figma frames (the Figma plan allows one variable mode; dark is derived from tokens and verified on device).
- The tab bar, `StatementRefreshBanner`, `RecurringQuestion`, `DonutChart`, `CashFlowChart` and the chart palette.
- The web dashboard. Mobile and web already differ in surface treatment; this plan does not widen or narrow that.
- Any over-the-air publish or store submission.
- New navigation entry points ("See all" on Goals or Recent Transactions).

## Self-review record

- **Spec coverage:** every section in the Figma frames maps to a task (hero 3, factors 4, balance 5, month tiles 6, cash flow trend 7, goals and note 8, layout and Spending 9, transactions and quick actions 10, banners and operational sections 15, journey and checklist 16, empty and loading states 14 and 15). The States frame's "statement refresh" banner is the one item deliberately not restyled, and is listed under out of scope.
- **Type consistency:** `DashboardCard` takes `padding: 'regular' | 'compact'` and `variant` everywhere it is used; `ProgressBar`'s fill id is `<testID>-fill` in its test and in the budget test; `heroTones(...).arc` is a function of the score in the test and in `HealthHero`; `typography` keys used in tasks all exist in Task 1.
- **Review Focus:** items 1 and 2 are pinned in Task 6, item 3 in Task 3, item 4 in Tasks 2 and 3 and Task 15 Step 1, item 5 in Task 2 and checked on device in Task 12.
- **Dark theme (added 2026-10-10 at Sid's request):** every colour pair in the plan was measured on both themes, see "Dark theme audit". Two values changed as a result (neutral icon well, insight bullet), dark render tests were added to Task 2, and dark rows were added to the device matrix in Tasks 12 and 17. One light-theme figure under its floor (`warning` bar, 2.92:1 at the worst pixel) is on `main` today and is listed there as not fixed by this plan.
- **Glass UI (checked 2026-10-10 at Sid's request):** every card and tile is a `GlassSurface` panel; the three places the first draft used the tinted `row` surface (KPI tiles, quick action tiles, search button) are now panels. The hero and the Financial Note, solid on `main` and in the first draft, are glass too ("make everything glass"): Sid chose "option B" for the hero: `primaryDark`-tinted glass at 0.86, measured on every backdrop pixel in `heroTones.test.ts`. The note is brass glass, measured in `glassContrast.test.ts`. See "Glass UI".
- **Amount colours (changed 2026-10-10 at Sid's request):** expenses stay `dangerInk`; the first draft's switch to ordinary ink is removed from Tasks 10, 13 and 15.
- **Known soft spots an executor must resolve by reading, not guessing:** the large-font mock in Task 6 Step 1 and the fixtures in Tasks 9, 10 and 15 are copied from `DashboardScreen.test.tsx` rather than reproduced here, because that file is 1,700 lines and its helpers must be reused as they are.

## Execution record, pull request 1 (Tasks 0 to 13)

What happened when the plan met the code, copied from the execution ledger. Where a line here disagrees with a task above, this line is what was built.

- Task 2: Ruling: the plan assumed "in jest Reduce Transparency is unknown, so GlassSurface is solid". Measured: the real hook starts at null and flips to false a tick after the first render in a file (borderColor read back as glassEdge, plus an act() warning). Tests that assert a surface's colours mock ../../lib/useReduceTransparency explicitly (null = solid), the way GlassSurface.test.tsx already does — cost if wrong: none, it is the repo's existing pattern. Applies to Tasks 3 and 8 (hero and note fallback tests) too.
- Task 2: Ruling: bars and icon wells are hidden from accessibility, and RNTL queries skip hidden elements; tests query them with { includeHiddenElements: true } — cost if wrong: none. Applies to every later test that reads a bar's fill (budget bars, Task 15).
- Task 3: Ruling: StyleSheet.absoluteFillObject does not exist in this React Native's types (tsc TS2551); the gauge label uses explicit position/top/right/bottom/left instead; plan line corrected — cost if wrong: none.
- Task 3: Ruling: the plan's rewrite dropped the hero's history comments (why the arc rebuilds `d` instead of the dash trick, why the pill says "vs last score"); restored in shortened form because they record measured bugs — cost if wrong: none.
- Task 3: Ruling: hero tests mock useReduceTransparency (see Task 2 ruling) and cover three paths: true and null (opaque primaryDark, edge in the same colour) and false (glass-tint layer carries heroTones.surface). The glass-path test needed no extra native mocks.
- Task 7: Ruling: with a single month the plan's path was one "M" and drew nothing, leaving a 72 point empty box. Added a dot for the one-point case (testID cash-flow-trend-dot) with a test — cost if wrong: one small circle on a one-month account.
- Task 8: Ruling: note tests mock useReduceTransparency (Task 2 ruling) and cover true, null and false; added a 44 point target test for Create Goal (the plan changed minHeight 36 -> 44 without a test). The brass-glass contrast test in glassContrast.test.ts passed on first run by design: it is a measurement of existing palette values on a new surface, with nothing to implement.
- Task 9: Ruling: the plan's order test serialised the rendered tree with JSON.stringify, which throws (props hold circular React contexts). The test walks children and compares text positions instead — cost if wrong: none.
- Task 9: Ruling: three layout gaps the plan did not cover, fixed here because the story layer is this task: (1) the factor row had 4 points between it and the hero, now spacing.md; (2) factor and goal rows were cut at the screen's padding line, now edge to edge (negative margin = screen padding, same padding inside); (3) the Financial Note had no top margin, now spacing.md on the card itself because the card can render nothing. All visual; to be confirmed on device in Task 12 — cost if wrong: spacing tweaks.
- Task 9: Ruling: added a search-button test (44 point circle, label kept) and testID dashboard-search-button; Maestro flow comments updated, flow not run (as the plan says).
- Task 10: Ruling: added two tests the plan did not have for behaviour it changed (direction icon per row with its tone; separator between rows and never after the last) with testIDs txn-row-<id> and txn-icon-<id>; the plan's amount test pins existing behaviour and passed before the change, as the plan says — cost if wrong: none.
- Task 11: full suite on Node 22 after Task 10: 234/235 suites, 2801/2802 tests; the one failure was GlassSurface.test.tsx pinning DashboardCard padding 24 (the old design value, which the plan's Task 2 Step 8 anticipated); updated to 20 and the two glass suites pass (24 tests). Baseline before any change was 231 suites / 2720 tests, all passing. The 'require after teardown' message from ProfileScreen.test.tsx is in the baseline log too (8 there, 7 here): not this branch's. Guards: reporting-period labels, nav tracking coverage, nav taxonomy drift all clean. Typecheck and lint clean.
- Task 12: device = iPhone 17 Pro simulator, iOS 26.5, the dev client already installed there (v1.1.0, built 2026-10-09, contains ExpoGlassEffect; no rebuild was needed), Metro from this worktree on port 8083, signed in as Sid against api.fynora.net (read only; nothing changed on the account). mobile/.env.local was copied from the primary checkout into the worktree (git-ignored, EXPO_PUBLIC_* values) to run Metro.
- Task 12: found and fixed on device, each test-first: (1) "Needs Attention" overflowed the dial -> two lines inside the dial's inner width (commit: keep a long tier label inside the hero dial); (2) the trend card's chip sat at the start of a right-aligned column -> DeltaChip align="end"; (3) at accessibility-medium text the 56 point score grew into its neighbours and the label left the dial -> score capped at 1.3x, label moves under the score at large sizes; (4) factor name cut to one line at large text -> two lines.
- Task 12: verified on device: light theme top to Quick Actions; dark theme (forced by a temporary local edit to ThemeContext, reverted, never committed: the account's theme setting is "light" and changing it would write to the production account); large text (KPI grid one column, nothing overlapping after fix 3); native view check: `ExpoGlassEffect.GlassView` for health-hero, financial-note, all four kpi-tile-*, six quick action tiles, dashboard-search-button and every card (33 GlassViews on the screen).
- Task 12: Ruling: scroll measurement. Same 12-swipe scroll, same simulator, same account, native profiler (Instruments via argent): this branch 0 hangs, 34,039 CPU sample rows; main (4f0afc7bc, served from a temporary second worktree, since removed) 0 hangs, 35,198 CPU sample rows. No measurable cost from the extra glass views on this simulator. Limits: simulator on a Mac GPU, debug JS bundle, hang detection and CPU samples rather than a frame-by-frame count. The plan's fallback (quick action tiles back to the tinted row surface) is not needed on this evidence — cost if wrong: a physical older iPhone could still drop frames; needs a device check before a store build.
- Task 12: NOT verified on device: Reduce Transparency (the simulator ignores the defaults key; covered by unit tests for hero, note and card), iPhone SE-size phone (no such simulator checked), Android emulator, iOS below 26 (BlurView path), the Maestro flow, a brand-new empty account.
- Task 12: Ruling: the plan said the pull request must carry the screenshots. The repository is public and every screenshot shows Sid's real balances and transactions, so none go in the pull request; they are sent to Sid directly — cost if wrong: reviewers on GitHub see no pictures.


## Execution record, comparison against the Figma frame (after pull request 1 was opened)

Sid, looking at the device screenshots: "not looking like Figma ones". The Figma export and the device screenshot were compared pixel by pixel. What that found, and what was done:

- **Card shadows were declared and never drawn.** The Figma cards carry two drop shadows; `DashboardCard` has declared a shadow since before this plan. On the device the pixels directly under the hero and under a tile were the bare page colour (`#f8fafc`, identical to a spot with no card near it). Cause: `GlassSurface`'s native path clips the effect view to its corner radius, and on iOS a view that clips also clips its own shadow. The plan's "Design reference" table had no shadow row, so no task checked for one. Fix: a surface whose style carries a `boxShadow` is built as a plain unclipped view (caller's whole style, shadow included) with the glass as a clipped layer behind the children; the hero, which must clip its glow, casts its shadow from a wrapper instead. Shadow values are the Figma frame's own: card `0 10 28 -6 rgba(15,23,41,0.07)` plus `0 1 2 0 rgba(15,23,41,0.04)`, hero `0 18 36 -12 rgba(20,23,28,0.28)`, in `mobile/src/theme/shadows.ts`. After: the page under the hero reads `#d1d4d7` fading to `#e6e8eb` and card fills are unchanged (`#fcfdfe`), so the shadow is outside the card only. iOS only; Android keeps the elevation it had.
- **The two sideways tile rows cut the shadow off 4 points under the tiles** (a scroll view clips to its bounds). They now pad their content by the shadow's reach and take the same amount back in margin; tile positions did not move (same edge row in the before and after screenshots).
- **The trend card had no month labels and no end marker**; the Figma frame has both. Added: a label under each point (every other one past six months, the latest always named), hidden from assistive tech because the full Cash Flow card already reads the same months with their amounts, and a dot on the latest month.
- **Scroll cost, measured again, and a correction to the Task 12 ruling above.** The "CPU sample rows" quoted there count every process on the Mac, not the app, so they do not show what that ruling says they show (three runs of this branch ranged from 38,916 to 236,442 rows purely with what else was running). Counted for the app alone, same 12-swipe scroll, same simulator and account: before this change main thread 2,048 samples and JavaScript thread 1,385; after, 2,063 and 1,292 on a long-running session, 1,752 and 1,279 on a fresh launch. Render server (`SimMetalHost`) 4,929 before, 5,136 and 4,195 after. Zero hangs in all three. No measurable cost on this simulator; the limits stated in Task 12 still apply, and so does the device check before a store build.
- **Differences that remain, and why.** The hero is dark grey, not the frame's near-black: that is "option B" (glass tinted at 0.86), chosen by Sid from a picture that showed it grey; the frame's look is the solid hero. The page backdrop is paler than the frame's: the app's real backdrop is flat `#f8fafc` over most of the screen, while the frame was drawn with colour edge to edge, and the backdrop is shared by every screen in the app. The balance card keeps its second row and "View Accounts", Recent Transactions has no "See all", and the donut keeps the app's category colours: each is existing behaviour, copy or navigation that this plan's goal says not to change. The banners (`Card`, not `DashboardCard`) carry no shadow.
- Verified after the change: 235 suites, 2,826 tests on Node 22; typecheck; lint; `expo export --platform android`; the three repository guards; iPhone 17 Pro simulator (iOS 26.5) in light from the top to Quick Actions and in dark from the top to the trend card (temporary local theme edit, reverted, tree checked). Not verified: Android (unchanged by design, but not run), iOS below 26 (the blur layer path is unit-tested only), Reduce Transparency on a device, large text with the new month labels (capped at 1.3 in code, unit-tested only), a physical phone.
