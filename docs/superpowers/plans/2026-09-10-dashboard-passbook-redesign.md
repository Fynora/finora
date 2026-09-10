# Dashboard Passbook Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the mobile Dashboard a distinct "passbook" visual identity (whitespace over borders,
a brass accent, finished Manrope/Inter typography, one signature motion moment) without touching
any shared design-system primitive or any other screen.

**Architecture:** One new Dashboard-only card wrapper (`DashboardCard`) replaces the shared `Card`
for the 3 Dashboard components that use it today; the 3 components that already have local card
styles get restyled directly. Two components (`MonthlySnapshotGrid`, `GoalsRow`) are replaced
outright with new components matching their new visual shape. All backend data contracts, query
keys, and business logic (`healthImprovementSuggestion`, `scoreLabel`, duplicate/review logic,
etc.) are reused unmodified.

**Tech Stack:** React Native / Expo SDK 57, TypeScript, `react-native-svg`, `react-native-
reanimated` (already used by `AnimatedNumber`/`AnimatedHealthScoreNumber`), Jest +
`@testing-library/react-native`.

**Spec:** [docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md](../specs/2026-09-10-dashboard-passbook-redesign-design.md)

## Global Constraints

- Do not modify `mobile/src/components/Card.tsx`, `TextField.tsx`, `DateField.tsx`, or any shared
  primitive used outside `mobile/src/components/dashboard/` and `DashboardScreen.tsx`.
- Do not modify backend contracts, query keys, or any `*Api` call in `mobile/src/api/endpoints.ts`.
- Brass (`#B8862E`) is used only for: the Health Seal frame, goal progress rings, and the Financial
  Note accent. Never on buttons, nav, general cards, charts, or backgrounds.
- The Health Seal's animation (arc draw-in + count-up) runs once per Dashboard mount — no
  `AsyncStorage`, no once-ever persistence.
- `HealthFactorsRow`'s existing logic (`healthImprovementSuggestion`, `scoreLabel`,
  `healthToneBg`/`healthBarColor`, the `Why?`/`Hide` toggle, top-opportunity highlight) is reused
  verbatim — this redesign is a chrome pass on it, not a rebuild.
- `DashboardScreen.test.tsx`'s existing pinned assertions (see Task 8) must still pass; run the
  full file after every task that touches `DashboardScreen.tsx`.

## Status

All 3 open questions from the spec are resolved (2026-09-10) — every task below is unblocked and
ready to implement in order. Bank logo work is explicitly **not** part of this plan — it's a
separate cross-app initiative (see spec's "Resolved decisions §3"); do not add `BankLogo` calls to
any task here.

---

### Task 1: Add the `brass` token to the theme

**Files:**
- Modify: `mobile/src/theme/palette.ts`

**Interfaces:**
- Produces: `Palette.brass: string`, `Palette.brassBg: string`, `Palette.brassInk: string` —
  consumed by Tasks 2-8. `brass`/`brassBg` for strokes/decorative fills; `brassInk` for any TEXT
  colored brass (see Step 3 — plain `brass` fails WCAG AA as text on more than one surface it's
  used on).

- [x] **Step 1: Add the tokens to both palettes**

In `light` (after `warningInk`, before `inputBg`):
```ts
  // Passbook redesign's one new accent -- Financial Health Seal frame, goal progress rings,
  // Financial Note accent only. See docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md.
  brass: '#B8862E',
  brassBg: '#F5EBD8',
```
In `dark` (same position):
```ts
  brass: '#C9A254',
  brassBg: '#2E2712',
```
- [x] **Step 2: Type-check**

Run: `cd mobile && npx tsc --noEmit` — clean, no errors.

- [x] **Step 3: Verify contrast, then commit — real bug found and fixed**

Computed actual contrast ratios (Node script, standard relative-luminance formula), not assumed:

- Light `brass` (#B8862E) on `card` (#ffffff): **3.24:1** — fails WCAG AA (4.5:1).
- Light `brass` on `brassBg` (#F5EBD8): **2.74:1** — fails.
- Dark `brass` (#C9A254) on every surface it's used on: 6.20-7.83:1 — passes comfortably.

Same shape of bug this file's own `warningInk`/`successInk` tokens already exist to fix. Added a
third token, `brassInk` (`#7A5A1E` light / same as `brass` in dark, since dark already passes),
for any TEXT use — clears 6.35:1 on `card`, 5.37:1 on `brassBg`. `brass`/`brassBg` stay as
originally planned for strokes and decorative fills (the arc frame ring, goal progress ring,
border/icon tints) where AA text contrast doesn't apply. Every later task in this plan that colors
TEXT brass uses `brassInk`, not `brass` — see Tasks 3 and 4, updated accordingly.

```bash
git add mobile/src/theme/palette.ts
git commit -m "feat(mobile): add brass/brassInk accent tokens for Dashboard passbook redesign"
```

---

### Task 2: `DashboardCard` — new Dashboard-only whitespace surface

**Files:**
- Create: `mobile/src/components/dashboard/DashboardCard.tsx`
- Test: `mobile/src/components/dashboard/DashboardCard.test.tsx`

**Interfaces:**
- Consumes: `Palette` from `../../theme` (Task 1's tokens not required here — plain surface, no
  brass by default).
- Produces: `DashboardCard({ children, style, testID }): JSX.Element` — same prop shape as
  `Card` (`mobile/src/components/Card.tsx`) so call sites swap in with no other changes. Consumed
  by Tasks 4, 5, 6, 7.

- [x] **Step 1: Write the failing test**

```tsx
// mobile/src/components/dashboard/DashboardCard.test.tsx
import { render, screen } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';
import { DashboardCard } from './DashboardCard';

describe('DashboardCard', () => {
  it('renders its children', () => {
    render(<DashboardCard><Text>hello</Text></DashboardCard>);
    expect(screen.getByText('hello')).toBeTruthy();
  });

  it('uses a hairline border, not Card\'s full 1px border (subtle-not-zero, per correction)', () => {
    render(<DashboardCard testID="card"><Text>x</Text></DashboardCard>);
    const flatStyle = Array.isArray(screen.getByTestId('card').props.style)
      ? Object.assign({}, ...screen.getByTestId('card').props.style)
      : screen.getByTestId('card').props.style;
    expect(flatStyle.borderWidth).toBe(StyleSheet.hairlineWidth);
  });
});
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest DashboardCard -t "hairline" 2>&1 | tail -20`
Expected: FAIL — `DashboardCard` module not found.

- [x] **Step 3: Write the component**

```tsx
// mobile/src/components/dashboard/DashboardCard.tsx
import type { ReactNode } from 'react';
import { Platform, StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Dashboard-only surface for the passbook redesign -- a hairline border (not `Card`'s full 1px)
 * plus more internal whitespace and a soft shadow, not zero separation. Corrected from an
 * initial fully-borderless design: the app has no elevation/shadow token anywhere (see the
 * spec's scope-boundary section), so removing every visual separator risked cards merging
 * together -- "quiet, not flat." Deliberately NOT a change to `Card.tsx` itself. Same
 * `{children, style, testID}` shape as `Card` so a call site swaps between them with no other
 * change.
 */
export function DashboardCard({
  children, style, testID,
}: { children: ReactNode; style?: ViewStyle; testID?: string }) {
  const c = useTheme();
  return (
    <View testID={testID} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.lg,
    padding: spacing.lg, // more breathing room than Card's spacing.md
    // Soft, deliberately subtle -- separation without a hard edge. iOS/Android render shadow
    // props differently (Android ignores shadowColor/Offset/Opacity/Radius and uses elevation
    // instead), so both are set to the same visual intent rather than picking one platform.
    ...Platform.select({
      ios: {
        shadowColor: '#000000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 6,
      },
      android: { elevation: 2 },
    }),
  },
});
```

- [x] **Step 4: Run tests to verify they pass**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest DashboardCard 2>&1 | tail -20`
Expected: PASS, both tests.

- [x] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/DashboardCard.tsx mobile/src/components/dashboard/DashboardCard.test.tsx
git commit -m "feat(mobile): add DashboardCard, the Dashboard-only passbook surface wrapper"
```

---

### Task 3: HealthHero — typography + Health Seal motion

Resolved: arc stays health-semantic (red/amber/green via `healthBarColor`), brass goes on the
frame/ring, the score plate, and the delta badge.

**Files:**
- Modify: `mobile/src/components/dashboard/HealthHero.tsx`
- Modify: `mobile/src/components/dashboard/HealthHero.test.tsx` (add motion-related assertions only
  — existing 5 tests must keep passing unmodified)

**Interfaces:**
- Consumes: `Palette.brass` (Task 1), existing `healthColor`/`healthBarColor` from `../../lib/health`
  (unchanged), `AnimatedHealthScoreNumber` (unchanged, already animates on `value` change).
- Produces: no prop/signature change — `HealthHero`'s existing `Props` interface is unchanged, so
  `DashboardScreen.tsx`'s call site (lines 508-517) needs no edit.

- [x] **Step 1: Add brass to the frame, the score plate, and the delta badge**

In the `available: true` branch's `card` View, add a thin brass ring as a decorative backdrop
behind the `Svg` gauge (e.g. a second `Svg`/`Path` or a bordered `View` positioned behind
`gaugeWrap`, radius matching `GAUGE_R + GAUGE_STROKE`, `stroke={c.brass}`, `strokeWidth={1}`,
low-opacity). Keep `arcPath(0, healthScore)`'s `stroke={progressColor}` (health-semantic) exactly
as today — this is the one piece explicitly NOT touched.

Give `gaugeScoreWrap` a subtle `backgroundColor: c.brassBg` plate (rounded, sized to fit the score
number + label) so the score reads as set into a "seal," not floating over the gauge.

Change `deltaPill`'s `backgroundColor` from `deltaPositive ? c.successBg : c.dangerBg` to
`c.brassBg`, and `deltaPillText`'s color from `deltaPositive ? c.successInk : c.danger` to
`c.brassInk` (not `c.brass` — this is text; plain `brass` only reaches 2.74:1 on `brassBg` in
light theme, under WCAG AA, confirmed by computing contrast during Task 1 — `brassInk` clears
5.37:1) — confirmed decision, not a guess: Sid chose brass here specifically, distinct from the
arc, since the `+`/`-` sign and number already carry the direction as text.

- [x] **Step 2: Apply Manrope/Inter**

`styles.title` → `fontFamily: fonts.displaySemibold` (import `fonts` from `../../theme`).
`styles.scoreValue` → `fontFamily: fonts.display`. `styles.scoreLabel` → `fontFamily: fonts.bodyBold`.
`styles.emptyTitle` → `fonts.displaySemibold`. Body text (`emptyBody`, `sparklineLabel`,
`deltaPillText`) → `fonts.body`/`fonts.bodySemibold` as weight requires. Remove any now-redundant
`fontWeight` on these styles per `fonts.ts`'s own doc comment (RN synthetically bolds on top of a
named-weight font file if `fontWeight` disagrees).

- [x] **Step 3: Draw-in + count-up on first mount only**

Wrap the progress `Path`'s `strokeDasharray`/`strokeDashoffset` in a `react-native-reanimated`
`useSharedValue` + `withTiming` (~700ms, `Easing.out(Easing.cubic)`, matching `AnimatedNumber`'s
existing easing choice), animating from full-offset (0% drawn) to 0 (100% drawn) once on mount —
use `useEffect(() => { ... }, [])` with an empty dependency array so it never re-fires on a
`healthScore` change from a pull-to-refresh. `AnimatedHealthScoreNumber` already animates on
`value` change (per its own file, not read in this pass but referenced by `HealthHero.tsx:128`) —
confirm during implementation whether it also skips first-mount animation like `AnimatedNumber`
does (see that component's own "No animation on first mount" comment) and, if so, add a
first-mount-only override so the score visibly counts up here specifically, not just ticks
instantly to the right value.

- [x] **Step 4: Run existing tests, verify still green**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest HealthHero.test.tsx 2>&1 | tail -30`
Expected: PASS, all 5 existing tests unmodified.

- [x] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/HealthHero.tsx mobile/src/components/dashboard/HealthHero.test.tsx
git commit -m "feat(mobile): Health Seal brass frame, typography, and once-per-mount draw-in motion"
```

---

### Task 4: `FinancialHealthFactorCard` + `HealthFactorsRow` chrome pass

**Files:**
- Create: `mobile/src/components/dashboard/FinancialHealthFactorCard.tsx`
- Modify: `mobile/src/components/dashboard/HealthFactorsRow.tsx`
- Modify: `mobile/src/components/dashboard/HealthFactorsRow.test.tsx` (only if a `testID`/query
  changes — the 5 existing tests' text assertions must otherwise keep passing unmodified)

**Interfaces:**
- Consumes: `DashboardCard` (Task 2), `healthBarColor`/`healthImprovementSuggestion`/
  `healthToneBg`/`scoreLabel` from `../../lib/health` (all unchanged, imported by the new file
  instead of `HealthFactorsRow.tsx` directly).
- Produces: `FinancialHealthFactorCard({ name, score, detail, isExpanded, onToggle,
  isTopOpportunity, topOpportunityPotentialGain }): JSX.Element` — one factor's card, extracted
  from `HealthFactorsRow`'s current inline `.map()` body (lines 36-64) verbatim, only the outer
  `View`'s style changing from the local `styles.card` to `DashboardCard`.

- [x] **Step 1: Extract the per-card render into the new file, unchanged logic**

Move `HealthFactorsRow.tsx`'s lines 36-64 (the per-factor `<View key={name}>...</View>` block) into
`FinancialHealthFactorCard.tsx` as a named-export component, taking the same values as props
(`name`, `score`, `detail`, `isExpanded`, `onToggle`, `isTopOpportunity`,
`topOpportunityPotentialGain`) instead of closing over `HealthFactorsRow`'s local state — swap the
outer `<View style={[styles.card, {backgroundColor: c.card, borderColor: c.border}]}>` for
`<DashboardCard>`, keep every other line (headerRow/pill/scoreRow/why-toggle/detail/suggestion/
opportunity) byte-identical.

- [x] **Step 2: Update `HealthFactorsRow.tsx` to render the new component**

Replace the inline `.map()` body with a call to `<FinancialHealthFactorCard key={name} ... />`,
passing through the same `expanded`/`setExpanded` state it already owns. `available`/`breakdown`/
`breakdownDetail`/`topOpportunityFactor`/`topOpportunityPotentialGain` props and the `if
(!available) return null;` guard are unchanged.

- [x] **Step 3: Apply typography, and brass to the opportunity highlight**

`name` style → `fonts.bodySemibold`. `score` style → `fonts.displayBold`. `pillText` → `fonts.bodyBold`.
`suggestion`/`detail` → `fonts.body`. `opportunity`'s color (the "↑ +N point opportunity" line,
currently `c.primary`) → `c.brassInk` (text — same WCAG reasoning as the delta pill above; plain
`brass` on a white `DashboardCard` only reaches 3.24:1), per the resolved decision — this is the
`healthTopOpportunityFactor` highlight, the same "opportunity" concept the spec's brass list
names.

- [x] **Step 4: Run existing tests, verify still green**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest HealthFactorsRow 2>&1 | tail -40`
Expected: PASS, all 5 tests, including the one asserting `Debt Score`/`100%`/`Hide`.

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest DashboardScreen.test.tsx -t "shows the score and breakdown" 2>&1 | tail -30`
Expected: PASS — this is the pinned cross-screen test named in the spec.

- [x] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/FinancialHealthFactorCard.tsx mobile/src/components/dashboard/HealthFactorsRow.tsx mobile/src/components/dashboard/HealthFactorsRow.test.tsx
git commit -m "refactor(mobile): extract FinancialHealthFactorCard, move HealthFactorsRow onto DashboardCard"
```

---

### Task 5: `LedgerSnapshotCard` replaces `MonthlySnapshotGrid`

**Files:**
- Create: `mobile/src/components/dashboard/LedgerSnapshotCard.tsx`
- Create: `mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx`
- Modify: `mobile/src/screens/DashboardScreen.tsx` (swap the import and the render call at
  line 19 and lines 528-536)
- Delete: `mobile/src/components/dashboard/MonthlySnapshotGrid.tsx`,
  `mobile/src/components/dashboard/MonthlySnapshotGrid.test.tsx` (once `LedgerSnapshotCard` is
  proven to fully replace it — do this in Step 6, not before)

**Interfaces:**
- Consumes: `KpiItem` type — **keep this type exported from the new file** (currently exported
  from `MonthlySnapshotGrid.tsx`, imported by `DashboardScreen.tsx:19` as `type KpiItem`; moving it
  means updating that import line too). `AnimatedNumber` (unchanged), `fmtCurrency` from
  `../../lib/format` (unchanged).
- Produces: `LedgerSnapshotCard({ kpis, deltaLabel, deltaSpokenLabel }): JSX.Element` — same 3 props
  `MonthlySnapshotGrid` takes today, so `DashboardScreen.tsx`'s call site only needs the component
  name and import path changed, not its props.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx
import { render, screen } from '@testing-library/react-native';
import { LedgerSnapshotCard, type KpiItem } from './LedgerSnapshotCard';

const KPIS: KpiItem[] = [
  { label: 'Income', value: 145000, delta: 12.5, invert: false, caption: null, isPercent: false },
  { label: 'Expenses', value: 12831, delta: -3.2, invert: true, caption: null, isPercent: false },
];

describe('LedgerSnapshotCard', () => {
  it('renders one row per KPI, in a single list, not a grid of separate cards', () => {
    render(<LedgerSnapshotCard kpis={KPIS} deltaLabel="vs last month" deltaSpokenLabel="versus last month" />);
    expect(screen.getByText('Income')).toBeTruthy();
    expect(screen.getByText('Expenses')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
  });

  it('shows a delta for a KPI that has one', () => {
    render(<LedgerSnapshotCard kpis={KPIS} deltaLabel="vs last month" deltaSpokenLabel="versus last month" />);
    expect(screen.getByText(/12\.5% vs last month/)).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest LedgerSnapshotCard 2>&1 | tail -20`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the component**

Base it on `MonthlySnapshotGrid.tsx`'s existing `.map()` body (same `accessibilityLabel`
construction, same `AnimatedNumber`/percent-value branch, same delta color logic —
`(k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger` — copied verbatim, this is a
layout change, not a logic change) but render one `DashboardCard` wrapping a vertical list of rows
instead of one `Card` per KPI in a wrapped grid:

```tsx
// mobile/src/components/dashboard/LedgerSnapshotCard.tsx
import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from './DashboardCard';
import { SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { fonts, spacing, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  value: number;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

/**
 * Replaces MonthlySnapshotGrid's 4-separate-cards grid with one "This Month" list -- passbook
 * framing (a ledger entry per line) instead of a dashboard-widget grid. Same KpiItem shape, same
 * accessibility-label construction, same delta color logic as the component this replaces.
 */
export function LedgerSnapshotCard({
  kpis, deltaLabel, deltaSpokenLabel,
}: { kpis: KpiItem[]; deltaLabel: string; deltaSpokenLabel: string }) {
  const c = useTheme();
  return (
    <DashboardCard>
      <SectionHeading title="This Month" />
      {kpis.map((k, i) => {
        const displayValue = k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);
        return (
          <View
            key={k.label}
            style={[styles.row, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}
            accessible
            accessibilityLabel={
              k.delta !== null && k.delta !== undefined
                ? `${k.label}: ${displayValue}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                : k.caption
                  ? `${k.label}: ${displayValue}, ${k.caption}`
                  : `${k.label}: ${displayValue}`
            }
          >
            <Text style={[styles.label, { color: c.mutedInk, fontFamily: fonts.body }]}>{k.label}</Text>
            <View style={styles.valueCol}>
              {k.isPercent ? (
                <Text testID={`kpi-${k.label}`} style={[styles.value, { color: c.ink, fontFamily: fonts.displayBold }]}>
                  {displayValue}
                </Text>
              ) : (
                <AnimatedNumber
                  testID={`kpi-${k.label}`}
                  value={k.value}
                  style={[styles.value, { color: c.ink, fontFamily: fonts.displayBold }]}
                />
              )}
              {k.delta !== null && k.delta !== undefined ? (
                <Text style={[styles.delta, { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger, fontFamily: fonts.bodySemibold }]}>
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : k.caption ? (
                <Text style={[styles.delta, { color: c.mutedInk, fontFamily: fonts.body }]}>{k.caption}</Text>
              ) : null}
            </View>
          </View>
        );
      })}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: spacing.sm },
  label: { fontSize: 13 },
  valueCol: { alignItems: 'flex-end' },
  value: { fontSize: 17 },
  delta: { fontSize: 11, marginTop: 2 },
});
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest LedgerSnapshotCard 2>&1 | tail -20`
Expected: PASS, both tests.

- [ ] **Step 5: Wire into DashboardScreen.tsx**

Line 19: `import { MonthlySnapshotGrid, type KpiItem } from '../components/dashboard/MonthlySnapshotGrid';`
→ `import { LedgerSnapshotCard, type KpiItem } from '../components/dashboard/LedgerSnapshotCard';`

Lines 528-536:
```tsx
      <View style={styles.section}>
        {summary ? (
          <MonthlySnapshotGrid kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        ) : (
          <View style={styles.kpiGrid}>
            {[0, 1, 2, 3].map((i) => <SkeletonCard key={i} style={styles.kpiCard} lines={1} />)}
          </View>
        )}
      </View>
```
→
```tsx
      <View style={styles.section}>
        {summary ? (
          <LedgerSnapshotCard kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        ) : (
          <SkeletonCard lines={4} />
        )}
      </View>
```
(The `styles.kpiGrid`/`styles.kpiCard` 4-skeleton-card skeleton no longer matches the new single-
card layout — one taller `SkeletonCard` reads correctly against a `DashboardCard` shape. Confirm
`SkeletonCard`'s `lines` prop renders proportionally before finalizing this — read
`mobile/src/components/skeletons/Skeletons.tsx` if its shape is unfamiliar.)

- [ ] **Step 6: Run the full Dashboard test file, then delete the old component**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest DashboardScreen.test.tsx 2>&1 | tail -60`
Expected: PASS. If any test queries `MonthlySnapshotGrid`-specific structure (e.g. a `kpiGrid`
testID or the 4-separate-card layout), update that assertion to match the new single-list
structure — do not leave it silently broken.

Then:
```bash
git rm mobile/src/components/dashboard/MonthlySnapshotGrid.tsx mobile/src/components/dashboard/MonthlySnapshotGrid.test.tsx
```

- [ ] **Step 7: Commit**

```bash
git add mobile/src/components/dashboard/LedgerSnapshotCard.tsx mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx mobile/src/screens/DashboardScreen.tsx
git commit -m "feat(mobile): replace MonthlySnapshotGrid with LedgerSnapshotCard (This Month list)"
```

---

### Task 6: `AccountsCard` and `CashFlowMiniCard` — onto `DashboardCard`, typography pass

**Files:**
- Modify: `mobile/src/components/dashboard/AccountsCard.tsx`
- Modify: `mobile/src/components/dashboard/CashFlowMiniCard.tsx`
- Modify: `mobile/src/components/dashboard/AccountsCard.test.tsx`,
  `mobile/src/components/dashboard/CashFlowMiniCard.test.tsx` (only if a query breaks — both
  existing test files' assertions should otherwise keep passing unmodified)

**Interfaces:** No prop/signature change to either component — `DashboardScreen.tsx`'s call sites
(lines 578, 581-586, 591-596) need no edit.

- [ ] **Step 1: `AccountsCard.tsx` — swap `Card` for `DashboardCard`**

Change the import (`import { Card, SectionHeading } from '../Card';` → `import { SectionHeading }
from '../Card'; import { DashboardCard } from './DashboardCard';`) and the JSX root
(`<Card style={styles.card}>` → `<DashboardCard style={styles.card}>`, closing tag to match). Keep
avatar-circle rendering exactly as today (bank logo port is out of scope — see spec's open
question 3). Typography: `balanceValue` → `fonts.display`, `balanceLabel`/`counts`/`caption` →
`fonts.body`, `ctaText` → `fonts.bodyBold`.

- [ ] **Step 2: `CashFlowMiniCard.tsx` — same swap**

Change the import (`import { Card, SectionHeading } from '../Card';` → same split as above) and
the JSX root (`<Card style={styles.card}>` → `<DashboardCard style={styles.card}>`). Typography:
`value` → `fonts.displayBold`, `label` → `fonts.body`, `delta` → `fonts.bodyBold`.

- [ ] **Step 3: Run both component test files**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest AccountsCard CashFlowMiniCard 2>&1 | tail -40`
Expected: PASS, all existing tests in both files.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/components/dashboard/AccountsCard.tsx mobile/src/components/dashboard/CashFlowMiniCard.tsx mobile/src/components/dashboard/AccountsCard.test.tsx mobile/src/components/dashboard/CashFlowMiniCard.test.tsx
git commit -m "style(mobile): move AccountsCard and CashFlowMiniCard onto DashboardCard, apply Manrope/Inter"
```

---

### Task 7: `GoalsRow` replaced with brass-ring goal cards

**Files:**
- Modify: `mobile/src/components/dashboard/GoalsRow.tsx`
- Modify: `mobile/src/components/dashboard/GoalsRow.test.tsx`

**Interfaces:** No prop/signature change — `GoalsRow({ goals: Goal[] })` stays the same, so
`DashboardScreen.tsx:673` needs no edit.

- [ ] **Step 1: Read the existing test file first**

Run: `cd mobile && cat src/components/dashboard/GoalsRow.test.tsx` — its 2 existing tests assert
against the current text-percentage layout; know exactly what they check before changing the
render, so Step 3's edits keep them meaningful rather than accidentally vacuous.

- [ ] **Step 2: Replace the linear progress bar with an SVG ring**

Same circular-progress technique `HealthHero.tsx` already uses (`Svg`+`Path`, `arcPath`-style helper
generating a circle instead of a semi-circle — a full 360° sweep from 0 to `pct/100 * 360`
degrees), stroked with `c.brass`, track stroked with `c.border` at low width. Replace the current
`track`/`fill` `View` pair (lines 22-24) with this SVG ring, percentage text centered inside it
(same `AnimatedHealthScoreNumber`-style approach optional — plain `Text` is fine here, this is a
smaller in-list figure, not the page's hero number). Swap the outer card `View`'s
`borderColor: c.border, backgroundColor: c.card` for `DashboardCard`. Typography: `name` →
`fonts.bodySemibold`, `pct`/`meta` → `fonts.body`.

- [ ] **Step 3: Update the test file for the new structure, keep behavioral intent**

If the existing tests query specific style props of the old track/fill `View`s, replace those
assertions with equivalent ones against the new ring (e.g. still assert the percentage text
renders, still assert `g.name` renders, still assert the empty-goals `return null` behavior is
unchanged).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest GoalsRow 2>&1 | tail -30`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/GoalsRow.tsx mobile/src/components/dashboard/GoalsRow.test.tsx
git commit -m "feat(mobile): replace GoalsRow's linear progress bar with brass progress rings"
```

---

### Task 8: `AIInsightCard` → "Financial Note", rename and retone

**Files:**
- Modify: `mobile/src/components/dashboard/AIInsightCard.tsx`
- Modify: `mobile/src/components/dashboard/AIInsightCard.test.tsx`
- Modify: `mobile/src/screens/DashboardScreen.tsx` (import + call site, lines 13, 676-680)

**Interfaces:** Props unchanged (`factor`, `potentialGain`, `onCreateGoal`) — only the component
and file name change, so this is a rename, not a redesign of its data flow.

- [ ] **Step 1: Verify the "deterministic, not AI" claim before writing new copy**

The component's own doc comment (lines 6-11) already states this is built from
`healthTopOpportunityFactor`/`healthTopOpportunityPotentialGain`, "a real, deterministic
computation, not an LLM call." Confirm this against `DashboardService`'s backend implementation
(`backend/src/main/java/.../DashboardService.java` — grep for
`healthTopOpportunityFactor`) before finalizing copy that explicitly says "factual, not AI
generated" anywhere in the UI — the comment is good evidence but the copy should match what the
backend actually does, not just what a 2-session-old comment says it does.

- [ ] **Step 2: Rename the file and component**

`git mv mobile/src/components/dashboard/AIInsightCard.tsx mobile/src/components/dashboard/FinancialNoteCard.tsx`
`git mv mobile/src/components/dashboard/AIInsightCard.test.tsx mobile/src/components/dashboard/FinancialNoteCard.test.tsx`
Rename the exported function `AIInsightCard` → `FinancialNoteCard`, update the test file's import
and `describe` block accordingly.

- [ ] **Step 3: Retone the copy, apply brass**

Replace `styles.card`'s `borderColor: c.primary` with a brass-accented left border or icon tint
(`c.brass`), keeping `backgroundColor: c.primaryLight` from the existing tone system. Replace the
headline copy (`Your {factor} is the biggest opportunity to improve your score.`) with a calmer,
factual register per the spec's writing guidance — e.g. `{factor} is where you have the most room
to improve.` — and keep the `+{potentialGain} points` figure exactly as computed. Typography:
`headline` → `fonts.bodySemibold`, `gain` → `fonts.body`, `ctaText` → `fonts.bodyBold`.

- [ ] **Step 4: Update `DashboardScreen.tsx`'s import and call site**

Line 13: `import { AIInsightCard } from '../components/dashboard/AIInsightCard';` →
`import { FinancialNoteCard } from '../components/dashboard/FinancialNoteCard';`
Lines 676-680: `<AIInsightCard ... />` → `<FinancialNoteCard ... />` (same 3 props, unchanged).

- [ ] **Step 5: Run tests**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest FinancialNoteCard DashboardScreen.test.tsx 2>&1 | tail -60`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/components/dashboard/FinancialNoteCard.tsx mobile/src/components/dashboard/FinancialNoteCard.test.tsx mobile/src/screens/DashboardScreen.tsx
git rm mobile/src/components/dashboard/AIInsightCard.tsx mobile/src/components/dashboard/AIInsightCard.test.tsx 2>/dev/null || true
git commit -m "feat(mobile): rename AIInsightCard to FinancialNoteCard, retone copy, apply brass"
```

---

### Task 9: Section reorder

Resolved order (coverage-gap banner, review-queue nudge, and limited-history banner stay above the
Health Seal, unchanged — they're conditional data-integrity warnings, not part of either the
narrative or operational layer):

Health Seal → Health Factors → Monthly Snapshot → Cash Flow Mini + Accounts → Spending by Category
→ Goals → Financial Note → Recent Transactions → Quick Actions → Upcoming/Recurring →
Categorization Confidence → Next Actions → Detected Issues → full Cash Flow Chart → Budget
Progress → Insights → Getting Started.

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx` (JSX reorder only — no logic change)

**Interfaces:** None — this task reorders existing JSX blocks, touching no props or state.

- [ ] **Step 1: Confirm the current section order matches this plan's spec excerpt**

Re-read `DashboardScreen.tsx` top to bottom immediately before starting (other tasks in this plan
touch it too — confirm no drift since this plan was written).

- [ ] **Step 2: Reorder JSX blocks to match the resolved placement above**

Move each section's JSX block to its resolved position. This is a cut/paste of existing
`<Card>`/`<View style={styles.section}>` blocks — no block's internal content changes. Sections
already in the right relative order (Health Hero → Health Factors → Monthly Snapshot/
`LedgerSnapshotCard` → Cash Flow mini/Accounts row → Spending by Category → Goals → Financial Note)
need no move. Recent Transactions moves earlier (currently after the full Cash Flow chart; resolved
order puts it right after Financial Note, before Quick Actions).

- [ ] **Step 3: Run the full Dashboard test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest DashboardScreen.test.tsx 2>&1 | tail -80`
Expected: PASS. A reorder should not change any test's pass/fail outcome unless a test asserts
relative DOM order specifically (`getAllByText` ordering, etc.) — if one does, that is a real
signal the reorder changed observable behavior a test cares about; read the failure before
adjusting either the test or the order.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx
git commit -m "refactor(mobile): reorder Dashboard sections to the passbook narrative order"
```

---

## Final verification (after all tasks)

- [ ] Full mobile test suite: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest 2>&1 | tail -100` — no regressions outside the files this plan touched.
- [ ] Type-check: `cd mobile && npx tsc --noEmit`
- [ ] Lint: `cd mobile && npx eslint src/components/dashboard src/screens/DashboardScreen.tsx`
- [ ] Manual verification in the iOS Simulator (per this repo's "no guessing" rule and the
      mandatory post-implementation verification loop) — screenshot the Dashboard, confirm: no
      borders on Dashboard cards, Manrope/Inter visibly applied, brass appears only in the 3
      specified places, Health Seal animates once on a cold navigation to the tab and does not
      re-animate on pull-to-refresh, dark mode still contrast-passes (Task 1 Step 3).
- [ ] Confirm no other screen changed — `git diff --stat origin/main` should show only files under
      `mobile/src/components/dashboard/`, `mobile/src/screens/DashboardScreen.tsx`,
      `mobile/src/theme/palette.ts`, and this plan/spec's own doc files.
