# Mobile Dashboard Premium Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `mobile/src/screens/DashboardScreen.tsx` into a premium-fintech hero-first layout (Financial Health gauge, monthly snapshot, accounts, compact cash flow, spending+goals, an AI-insight card, a floating "+" bottom-nav action) while preserving every existing card's data and logic, and porting the health-score delta/sparkline/top-opportunity feature the web app already ships from an already-live backend API.

**Architecture:** New small, focused components under `mobile/src/components/dashboard/`, each consuming data `DashboardScreen.tsx` already fetches (or a field that's already in the live API response but missing from the mobile `DashboardSummary` type). `DashboardScreen.tsx` is reordered to assemble them; no card's underlying logic changes, only where it renders and what it looks like. One new shared bottom-nav affordance (`AppTabs.tsx`'s Import tab becomes a floating action button) and one new reusable `Toast` system.

**Tech Stack:** React Native + Expo, TypeScript, `@tanstack/react-query`, `react-native-svg`, `react-native-reanimated`, Jest + `@testing-library/react-native`.

**Spec:** No separate written spec file — this plan's own Context section distills it, matching this repo's established practice for redesign plans (see `docs/superpowers/plans/2026-09-06-dashboard-kpi-hero-redesign.md`, the web KPI+hero plan, which used the same "no separate spec" approach).

## Context: what's actually being built, and why

The user supplied a full mockup + written design spec for a premium-fintech mobile Dashboard (hero Financial Health gauge, 2×2 monthly snapshot, accounts card, compact cash flow, spending+goals, an AI insight card, collapsed "Getting Started", a floating "+" bottom-nav button, toasts). Before writing this plan, three real conflicts with the existing codebase were found and resolved with the user directly:

1. **Accent color** — the spec's `#6D5DF6` purple is new; this redesign series' established rule is graphite/cream only. **Resolved: no purple** — every "accent" moment in the mockup uses the existing `primary`/`primaryLight` tokens instead.
2. **Floating "+" bottom-nav button** — `AppTabs.tsx` currently keeps Import as a plain tab specifically *because* "importing a statement is a deliberate, occasional task, not a one-tap action" (existing code comment). **Resolved: build the FAB anyway** — the user wants it, opening a small action sheet (Import Statement / Add Transaction / Add Goal).
3. **Cards the mockup doesn't show** (Categorization Confidence, Next Actions, Detected Duplicates, Budget Progress, Recurring, Insights, coverage/limited-history banners, review nudge, Quick Actions, Getting Started) — **resolved: keep every one of them**, restyled to the same tokens, positioned below/around the new mockup sections. Nothing is deleted.

A second round of user feedback, given after the first pass of research, changed the shape of two pieces significantly — and in the process surfaced that **the backend and web app already ship a richer Financial Health feature than the original mockup implies**, which this plan now ports instead of reinventing:

- `backend/src/main/java/com/finora/dto/DashboardSummaryDto.java` already returns `healthScoreDeltaVsLastMonth` (Integer, nullable), `healthSparkline` (`List<HealthScorePoint>`, up to 6 months), `healthTopOpportunityFactor` (String, nullable) and `healthTopOpportunityPotentialGain` (Integer, nullable) — computed in `DashboardService.java:317-346`, already live in production.
- `frontend/src/types/index.ts` and `frontend/src/pages/Dashboard.tsx` already consume all four fields (health score delta pill, a `HealthScoreSparkline` component, per-factor "opportunity" highlighting, and an "AI Insight" card reading *"Your {factor} is the biggest opportunity to improve your score. Potential gain: +N points [Create Goal]"*).
- **`mobile/src/types/index.ts`'s `DashboardSummary` interface is missing all four fields** — this is exactly the "drift" the file's own top-of-file comment warns about ("KEEP THIS IN SYNC BY HAND. It drifted once already..."). Mobile currently silently drops data the API already sends.

Given that, this plan:
- Ports the web's health-delta/sparkline/opportunity feature to mobile (Task 1, 4, 5, 6, 11) — **no backend change**, just filling in the type-drift gap and building the mobile UI for data already on the wire.
- Uses `healthTopOpportunityFactor`/`healthTopOpportunityPotentialGain` (the server's real "biggest opportunity" computation) for both the Hero's opportunity chip and the AI Insight card, instead of inventing a client-side heuristic or using the first insights sentence (`sentences[0]`) as the user asked.
- Keeps the existing per-row Financial Health breakdown's "Why?"/"Hide" detail-disclosure interaction — pinned by an existing test (`screens/DashboardScreen.test.tsx:786`, "shows the score and breakdown once available, with each row's detail hidden until asked for") — while adding the web's `healthImprovementSuggestion` copy and top-opportunity highlight on top of it, and converting the row list to a horizontal scroll per the user's explicit "keep Health Breakdown visible... horizontal scroll" request.
- Reorders sections per the user's second round of feedback: Health Hero → Health Factors (horizontal scroll) → Monthly Snapshot → Quick Actions (moved up from the bottom) → Accounts (moved above Cash Flow) → Cash Flow (compact) → Spending → Upcoming (the existing Recurring/Subscriptions card, relocated here) → Goals (horizontal scroll) → AI Insight → every remaining existing card, unchanged, in its prior relative order → Getting Started (collapsed accordion, moved to the very end).
- Keeps the existing full Cash Flow chart (with its 3M/6M/12M range picker) exactly where it is today, further down the screen — the new compact Cash Flow card is a genuinely different "glance vs. detail" view, the same relationship the Health Hero (compact) now has with Health Factors (detail).

## Global Constraints

- **Cross-platform parity is mandatory**: every new/changed component must render identically on iOS and Android. No `Platform.OS` branching for visuals or behavior. Build only with primitives already used this way elsewhere in the app: `react-native-svg`, `Ionicons`, `StyleSheet`, `react-native-reanimated`. (iOS shadow props and Android `elevation` may both be set on the same style object — that is normal RN styling, not a platform branch, and each platform already ignores the property it doesn't use.)
- **No purple / no new color tokens.** Every accent moment uses existing `theme/palette.ts` tokens (`primary`, `primaryLight`, `success`/`successBg`, `warning`/`warningBg`/`warningInk`, `danger`/`dangerBg`).
- **No backend changes and no new API calls.** Every new UI is built from data `DashboardScreen.tsx` already fetches, or from fields already present in the live `/dashboard/summary` response that only need adding to the mobile TypeScript type.
- **Every one of the 9 existing preserved cards keeps its exact current logic, text, testID and accessibility label** — only its visual token usage and screen position may change. Do not touch: Categorization Confidence, Next Actions, Detected Issues, Budget Progress, Insights (full sentence list), the full Cash Flow chart, Recent Transactions, coverage banner, limited-history banner, review nudge, Quick Actions' own action list.
- **Reuse, don't reinvent, existing animation primitives**: `AnimatedNumber`, `AnimatedHealthScoreNumber`, `RevealArc`, `RevealPolyline`, `FadeInDown`, `CHART_REVEAL_DURATION` (450ms) — all already exist in `mobile/src/components/` and already do count-up, arc/line draw-in, and staggered fade.
- **No `Co-Authored-By` / AI-attribution trailer in any commit message** (repo-wide rule, root `CLAUDE.md`).
- Read `mobile/AGENTS.md` before writing any Expo-specific code (Expo SDK docs pinned to v57).

---

## File Structure

- **Modify:** `mobile/src/types/index.ts` — add `HealthScorePoint` interface and 4 new `DashboardSummary` fields.
- **Create:** `mobile/src/lib/health.ts` — `healthColor`, `healthBarColor`, `scoreLabel`, `healthToneBg`, `healthImprovementSuggestion` (moved/ported).
- **Create:** `mobile/src/lib/dashboardMetrics.ts` — `deriveNetSavingsSeries`, `averageMonthlySavings`.
- **Create:** `mobile/src/components/dashboard/HealthSparkline.tsx` — ported from `frontend/src/design-system/HealthScoreSparkline.tsx`.
- **Create:** `mobile/src/components/dashboard/HealthHero.tsx` — the dark hero card (gauge, score, delta pill, sparkline, empty-state branch).
- **Create:** `mobile/src/components/dashboard/HealthFactorsRow.tsx` — horizontal-scroll breakdown factor cards.
- **Create:** `mobile/src/components/dashboard/MonthlySnapshotGrid.tsx` — 2×2 Income/Expenses/Net Savings/Savings Rate.
- **Create:** `mobile/src/components/dashboard/CashFlowMiniCard.tsx` — compact net-savings sparkline + average.
- **Create:** `mobile/src/components/dashboard/AccountsCard.tsx` — bank avatars + total balance.
- **Create:** `mobile/src/components/dashboard/GoalsRow.tsx` — horizontal-scroll goal cards.
- **Create:** `mobile/src/components/dashboard/AIInsightCard.tsx` — health-opportunity narrative card.
- **Modify:** `mobile/src/onboarding/ChecklistWidget.tsx` — collapsible accordion.
- **Create:** `mobile/src/components/Toast.tsx`, `mobile/src/context/ToastContext.tsx` — reusable toast system.
- **Modify:** `mobile/App.tsx` — mount `ToastProvider`.
- **Create:** `mobile/src/components/dashboard/QuickActionSheet.tsx` — the FAB's action sheet.
- **Modify:** `mobile/src/navigation/AppTabs.tsx` — floating "+" `tabBarButton`.
- **Modify:** `mobile/src/navigation/types.ts` — `Home` tab gains optional params.
- **Modify:** `mobile/src/screens/DashboardScreen.tsx` — full reorder/integration.
- **Modify:** `mobile/src/screens/DashboardScreen.test.tsx` — adapted where the new structure requires it.
- Every new component gets its own `*.test.tsx` alongside it.

---

### Task 1: Close the mobile `DashboardSummary` type-drift gap

**Files:**
- Modify: `mobile/src/types/index.ts`
- Modify: `mobile/src/screens/DashboardScreen.test.tsx` (fixture defaults only)

**Interfaces:**
- Consumes: nothing new.
- Produces: `HealthScorePoint` interface and 4 new `DashboardSummary` fields, consumed by Tasks 4, 5, 6, 11.

- [ ] **Step 1: Add the missing fields**

In `mobile/src/types/index.ts`, add a new exported interface right before `export interface DashboardSummary {` (around line 208):

```ts
// Mirrors frontend/src/types/index.ts's identical HealthScorePoint and
// backend/src/main/java/com/finora/dto/DashboardSummaryDto.java's HealthScorePoint record.
export interface HealthScorePoint {
  yearMonth: string;
  score: number;
}
```

Then add these 4 fields inside `DashboardSummary`, right after `healthScoreMinTransactions: number;` (around line 231):

```ts
  // Already live on the backend (DashboardService.java:317-346) and already consumed by
  // frontend/src/pages/Dashboard.tsx -- was missing here, which is exactly the "drift" this
  // file's own top-of-file comment warns about. null/empty until healthScoreAvailable is true
  // and a prior month's snapshot (delta) or up to 6 snapshots (sparkline) actually exist.
  healthScoreDeltaVsLastMonth: number | null;
  healthSparkline: HealthScorePoint[];
  // The single factor DashboardService.computeTopOpportunity ranks as most improvable; null
  // when there's no real (>= 3 point) opportunity, mirroring the backend's own gate.
  healthTopOpportunityFactor: string | null;
  healthTopOpportunityPotentialGain: number | null;
```

- [ ] **Step 2: Update the test fixture defaults**

In `mobile/src/screens/DashboardScreen.test.tsx`, inside `emptySummary()` (around line 110-116), add the 4 new fields with the same "real defaults, not `as` casting past them" posture as their neighbors:

```ts
    healthScore: 0,
    healthLabel: 'No data',
    healthBreakdown: {},
    healthBreakdownDetail: {},
    healthScoreAvailable: false,
    healthScoreTransactionCount: 0,
    healthScoreMinTransactions: 10,
    healthScoreDeltaVsLastMonth: null,
    healthSparkline: [],
    healthTopOpportunityFactor: null,
    healthTopOpportunityPotentialGain: null,
```

- [ ] **Step 3: Typecheck**

Run: `cd mobile && npx tsc --noEmit`
Expected: no new errors (the fixture already used `as DashboardSummary`, so this step confirms the interface itself is well-formed, not that anything was silently broken).

- [ ] **Step 4: Run the full Dashboard test file to confirm nothing regressed**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx`
Expected: every existing test still PASSES — this task only adds fields, nothing reads them yet.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/types/index.ts mobile/src/screens/DashboardScreen.test.tsx
git commit -m "fix(mobile): add the 4 health-score fields already live on the backend but missing from DashboardSummary"
```

---

### Task 2: `lib/health.ts` — shared health-score helpers

**Files:**
- Create: `mobile/src/lib/health.ts`
- Test: `mobile/src/lib/health.test.ts`
- Modify: `mobile/src/screens/DashboardScreen.tsx`

**Interfaces:**
- Consumes: `Palette` type from `../theme`.
- Produces: `healthColor(label: string, c: Palette): string`, `healthBarColor(score: number, c: Palette): string`, `scoreLabel(score: number): string`, `healthToneBg(score: number, c: Palette): string`, `healthImprovementSuggestion(factor: string, score: number): string` — consumed by Tasks 5, 6, 11 and by `DashboardScreen.tsx` itself (Task 15).

- [ ] **Step 1: Write the failing tests**

Create `mobile/src/lib/health.test.ts`:

```ts
import { light, dark } from '../theme/palette';
import { healthBarColor, healthColor, healthImprovementSuggestion, healthToneBg, scoreLabel } from './health';

describe('healthColor', () => {
  it('maps every label to its token, in both themes', () => {
    expect(healthColor('Excellent', light)).toBe(light.success);
    expect(healthColor('Good', light)).toBe(light.primary);
    expect(healthColor('Fair', light)).toBe(light.warningInk);
    expect(healthColor('Needs Attention', light)).toBe(light.danger);
    expect(healthColor('Excellent', dark)).toBe(dark.success);
  });
});

describe('healthBarColor', () => {
  it('uses the 80/60/40 cutoffs', () => {
    expect(healthBarColor(80, light)).toBe(light.success);
    expect(healthBarColor(79, light)).toBe(light.primary);
    expect(healthBarColor(60, light)).toBe(light.primary);
    expect(healthBarColor(59, light)).toBe(light.warning);
    expect(healthBarColor(40, light)).toBe(light.warning);
    expect(healthBarColor(39, light)).toBe(light.danger);
  });
});

describe('healthToneBg', () => {
  it('pairs each cutoff with its background token', () => {
    expect(healthToneBg(85, light)).toBe(light.successBg);
    expect(healthToneBg(70, light)).toBe(light.primaryLight);
    expect(healthToneBg(50, light)).toBe(light.warningBg);
    expect(healthToneBg(20, light)).toBe(light.dangerBg);
  });
});

describe('scoreLabel', () => {
  it('uses the same cutoffs as healthBarColor', () => {
    expect(scoreLabel(80)).toBe('Excellent');
    expect(scoreLabel(60)).toBe('Good');
    expect(scoreLabel(40)).toBe('Fair');
    expect(scoreLabel(0)).toBe('Needs Attention');
  });
});

describe('healthImprovementSuggestion', () => {
  it('gives a positive suggestion at or above 80, actionable below it', () => {
    expect(healthImprovementSuggestion('Savings Rate', 85)).toBe("You're saving well — keep it up.");
    expect(healthImprovementSuggestion('Savings Rate', 50)).toBe('Aim to save at least 24% of your income each month.');
    expect(healthImprovementSuggestion('Emergency Fund', 30)).toBe('Build your emergency fund toward 4-5 months of expenses.');
  });

  it('returns an empty string for an unrecognized factor name', () => {
    expect(healthImprovementSuggestion('Some New Factor', 50)).toBe('');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mobile && npx jest src/lib/health.test.ts`
Expected: FAIL — `./health` module does not exist yet.

- [ ] **Step 3: Implement `lib/health.ts`**

```ts
import type { Palette } from '../theme';

/**
 * Track C/C1's original helper, moved out of DashboardScreen.tsx so HealthHero/HealthFactorsRow/
 * AIInsightCard can share it without importing the screen component (which would import them
 * back -- a cycle). Same 80/60/40 cutoffs and label vocabulary as
 * frontend/src/pages/Dashboard.tsx's identical helper.
 *
 * `warningInk`, not `warning`: `warning` is tuned for icons/borders/bars and falls under WCAG AA
 * as plain text on this screen's background -- warningInk is the token built for exactly that
 * (see theme/palette.ts's own comment).
 */
export function healthColor(label: string, c: Palette): string {
  switch (label) {
    case 'Excellent': return c.success;
    case 'Good': return c.primary;
    case 'Fair': return c.warningInk;
    default: return c.danger;
  }
}

/**
 * Same cutoffs as healthColor, applied to one breakdown row's own score -- so a perfect
 * sub-score doesn't inherit the overall label's color. A bar/track fill, not text, so `warning`
 * itself (not `warningInk`) is the right token here.
 */
export function healthBarColor(score: number, c: Palette): string {
  if (score >= 80) return c.success;
  if (score >= 60) return c.primary;
  if (score >= 40) return c.warning;
  return c.danger;
}

/** Background token to pair with healthBarColor's foreground, for a factor card's tone pill. */
export function healthToneBg(score: number, c: Palette): string {
  if (score >= 80) return c.successBg;
  if (score >= 60) return c.primaryLight;
  if (score >= 40) return c.warningBg;
  return c.dangerBg;
}

/** Same 0-100 scale and vocabulary as healthColor -- Categorization Confidence reuses it too. */
export function scoreLabel(score: number): string {
  if (score >= 80) return 'Excellent';
  if (score >= 60) return 'Good';
  if (score >= 40) return 'Fair';
  return 'Needs Attention';
}

/**
 * Ported verbatim from frontend/src/pages/Dashboard.tsx's identical helper -- same factor names
 * and copy, so a breakdown factor reads the same suggestion on both platforms.
 */
export function healthImprovementSuggestion(factor: string, score: number): string {
  const good = score >= 80;
  switch (factor) {
    case 'Savings Rate':
      return good ? "You're saving well — keep it up." : 'Aim to save at least 24% of your income each month.';
    case 'Debt Score':
      return good ? "You're managing debt well." : 'Pay down credit card balances to bring utilization under 20%.';
    case 'Emergency Fund':
      return good ? 'You have a solid safety net.' : 'Build your emergency fund toward 4-5 months of expenses.';
    case 'Spend Consistency':
      return good ? 'Your spending has been consistent.' : 'Try to keep monthly spending within about 20% of your average.';
    case 'Cash Flow Stability':
      return good ? 'Your cash flow has been stable.' : 'Work toward income meeting or exceeding expenses most months.';
    default:
      return '';
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && npx jest src/lib/health.test.ts`
Expected: PASS.

- [ ] **Step 5: Point `DashboardScreen.tsx` at the shared module**

In `mobile/src/screens/DashboardScreen.tsx`, delete the local `healthColor`, `healthBarColor` and `scoreLabel` function definitions (lines 57-87), and add to the top import block:

```ts
import { healthBarColor, healthColor, scoreLabel } from '../lib/health';
```

- [ ] **Step 6: Run the full Dashboard test file**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx`
Expected: every existing test still PASSES — pure refactor, no behavior change.

- [ ] **Step 7: Commit**

```bash
git add mobile/src/lib/health.ts mobile/src/lib/health.test.ts mobile/src/screens/DashboardScreen.tsx
git commit -m "refactor(mobile): move health-score color/label helpers into a shared lib module"
```

---

### Task 3: `lib/dashboardMetrics.ts` — cash flow derivations

**Files:**
- Create: `mobile/src/lib/dashboardMetrics.ts`
- Test: `mobile/src/lib/dashboardMetrics.test.ts`

**Interfaces:**
- Consumes: `CashFlowPoint` type from `../components/charts/CashFlowChart`.
- Produces: `deriveNetSavingsSeries(points: CashFlowPoint[]): NetSavingsPoint[]`, `averageMonthlySavings(points: CashFlowPoint[]): number` — consumed by Task 8 (`CashFlowMiniCard`).

- [ ] **Step 1: Write the failing tests**

Create `mobile/src/lib/dashboardMetrics.test.ts`:

```ts
import { averageMonthlySavings, deriveNetSavingsSeries } from './dashboardMetrics';

describe('deriveNetSavingsSeries', () => {
  it('maps each point to label + income-minus-expense', () => {
    const result = deriveNetSavingsSeries([
      { label: 'Jan', income: 50000, expense: 30000 },
      { label: 'Feb', income: 40000, expense: 45000 },
    ]);
    expect(result).toEqual([
      { label: 'Jan', net: 20000 },
      { label: 'Feb', net: -5000 },
    ]);
  });

  it('returns an empty array for no points', () => {
    expect(deriveNetSavingsSeries([])).toEqual([]);
  });
});

describe('averageMonthlySavings', () => {
  it('averages net savings across every point', () => {
    expect(averageMonthlySavings([
      { label: 'Jan', income: 50000, expense: 30000 },
      { label: 'Feb', income: 40000, expense: 45000 },
    ])).toBe(7500);
  });

  it('returns 0 for no points, not NaN', () => {
    expect(averageMonthlySavings([])).toBe(0);
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mobile && npx jest src/lib/dashboardMetrics.test.ts`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

```ts
import type { CashFlowPoint } from '../components/charts/CashFlowChart';

export interface NetSavingsPoint {
  label: string;
  net: number;
}

/** Cash Flow Mini card's sparkline series -- income minus expense, per month, client-derived
 *  from the same per-month report queries the full Cash Flow chart already fetches. No new
 *  backend field: the two numbers this subtracts are already on CashFlowPoint. */
export function deriveNetSavingsSeries(points: CashFlowPoint[]): NetSavingsPoint[] {
  return points.map((p) => ({ label: p.label, net: p.income - p.expense }));
}

/** Cash Flow Mini card's "Average Monthly Savings" figure -- mean net savings over the same
 *  range the sparkline draws. 0 (not NaN) for an empty range: there is nothing to average, and a
 *  currency figure rendering as "NaN" is worse than a genuine zero. */
export function averageMonthlySavings(points: CashFlowPoint[]): number {
  if (points.length === 0) return 0;
  const total = points.reduce((sum, p) => sum + (p.income - p.expense), 0);
  return total / points.length;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && npx jest src/lib/dashboardMetrics.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/lib/dashboardMetrics.ts mobile/src/lib/dashboardMetrics.test.ts
git commit -m "feat(mobile): add client-side net-savings derivation for the compact Cash Flow card"
```

---

### Task 4: `HealthSparkline` — ported 6-month trend line

**Files:**
- Create: `mobile/src/components/dashboard/HealthSparkline.tsx`
- Test: `mobile/src/components/dashboard/HealthSparkline.test.tsx`

**Interfaces:**
- Consumes: `HealthScorePoint` from `../../types`.
- Produces: `<HealthSparkline points={HealthScorePoint[]} color={string} />` — consumed by Task 5 (`HealthHero`).

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/HealthSparkline.test.tsx`:

```tsx
import { render } from '@testing-library/react-native';
import { HealthSparkline } from './HealthSparkline';

describe('HealthSparkline', () => {
  it('draws one polyline per contiguous-month run, breaking across a gap', () => {
    const { UNSAFE_getAllByProps } = render(
      <HealthSparkline
        color="#000000"
        points={[
          { yearMonth: '2026-04', score: 60 },
          { yearMonth: '2026-05', score: 65 },
          // Gap: 2026-06 missing.
          { yearMonth: '2026-07', score: 70 },
          { yearMonth: '2026-08', score: 72 },
        ]}
      />
    );
    // Two runs of >= 2 points each -- two separate polylines, never one line bridging the gap.
    expect(UNSAFE_getAllByProps({ stroke: '#000000' })).toHaveLength(2);
  });

  it('renders nothing for fewer than 2 points', () => {
    const { queryByTestId } = render(<HealthSparkline color="#000000" points={[{ yearMonth: '2026-08', score: 72 }]} />);
    expect(queryByTestId('health-sparkline')).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/HealthSparkline.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/HealthSparkline.tsx`:

```tsx
import Svg, { Polyline } from 'react-native-svg';
import type { HealthScorePoint } from '../../types';

const WIDTH = 200;
const HEIGHT = 40;

/** True when b is exactly one calendar month after a ("2026-05" after "2026-04"). */
function isNextMonth(a: string, b: string): boolean {
  const [ay, am] = a.split('-').map(Number);
  const [by, bm] = b.split('-').map(Number);
  return by * 12 + bm === ay * 12 + am + 1;
}

/** Splits points into contiguous-month runs, so a gap renders as a break, never interpolated --
 *  ported from frontend/src/design-system/HealthScoreSparkline.tsx's identical function. */
function splitIntoRuns(points: HealthScorePoint[]): HealthScorePoint[][] {
  if (points.length === 0) return [];
  const runs: HealthScorePoint[][] = [[points[0]]];
  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const cur = points[i];
    if (isNextMonth(prev.yearMonth, cur.yearMonth)) {
      runs[runs.length - 1].push(cur);
    } else {
      runs.push([cur]);
    }
  }
  return runs;
}

/**
 * Mobile port of frontend/src/design-system/HealthScoreSparkline.tsx. Not animated via
 * RevealPolyline (unlike CashFlowChart/TrendChart): this renders inside HealthHero, which
 * already staggers its own reveal, and a 6-point sparkline is small enough that a static draw
 * reads as part of the hero settling in rather than a separate animation competing with it.
 */
export function HealthSparkline({ points, color }: { points: HealthScorePoint[]; color: string }) {
  const runs = splitIntoRuns(points).filter((run) => run.length >= 2);
  if (runs.length === 0) return null;

  const xAt = (i: number) => (points.length <= 1 ? 0 : (i / (points.length - 1)) * WIDTH);
  const yAt = (score: number) => HEIGHT - (score / 100) * HEIGHT;

  return (
    <Svg testID="health-sparkline" width="100%" height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`}>
      {runs.map((run) => (
        <Polyline
          key={run[0].yearMonth}
          points={run.map((p) => `${xAt(points.indexOf(p))},${yAt(p.score)}`).join(' ')}
          fill="none"
          stroke={color}
          strokeWidth={2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </Svg>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/HealthSparkline.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/HealthSparkline.tsx mobile/src/components/dashboard/HealthSparkline.test.tsx
git commit -m "feat(mobile): port the 6-month Financial Health sparkline from web"
```

---

### Task 5: `HealthHero` — the dark hero card

**Files:**
- Create: `mobile/src/components/dashboard/HealthHero.tsx`
- Test: `mobile/src/components/dashboard/HealthHero.test.tsx`

**Interfaces:**
- Consumes: `healthColor`/`healthBarColor` from `../../lib/health` (Task 2); `HealthSparkline` (Task 4); `AnimatedHealthScoreNumber` (existing); `HealthScorePoint` from `../../types`.
- Produces: `<HealthHero available healthScore healthLabel healthScoreDeltaVsLastMonth healthSparkline healthScoreTransactionCount healthScoreMinTransactions onImportPress />` — consumed by Task 15 (`DashboardScreen.tsx`).

- [ ] **Step 1: Write the failing tests**

Create `mobile/src/components/dashboard/HealthHero.test.tsx`:

```tsx
import { fireEvent, render, screen, act } from '@testing-library/react-native';
import { HealthHero } from './HealthHero';
import { ThemeProvider } from '../../theme';

function renderHero(props: Partial<React.ComponentProps<typeof HealthHero>> = {}) {
  return render(
    <ThemeProvider>
      <HealthHero
        available
        healthScore={72}
        healthLabel="Good"
        healthScoreDeltaVsLastMonth={4}
        healthSparkline={[]}
        healthScoreTransactionCount={0}
        healthScoreMinTransactions={10}
        onImportPress={jest.fn()}
        {...props}
      />
    </ThemeProvider>
  );
}

describe('HealthHero', () => {
  it('shows the score, label and title', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    renderHero();
    await act(async () => { jest.advanceTimersByTime(500); });
    expect(screen.getByText('Financial Health Score')).toBeTruthy();
    expect(screen.getByText('Good')).toBeTruthy();
    expect(screen.getByTestId('health-score-value')).toHaveAnimatedProps({ text: '72', defaultValue: '72' });
    jest.useRealTimers();
  });

  it('shows a positive delta pill', () => {
    renderHero({ healthScoreDeltaVsLastMonth: 4 });
    expect(screen.getByText('+4 this month')).toBeTruthy();
  });

  it('shows a negative delta pill', () => {
    renderHero({ healthScoreDeltaVsLastMonth: -3 });
    expect(screen.getByText('-3 this month')).toBeTruthy();
  });

  it('hides the delta pill entirely when null', () => {
    renderHero({ healthScoreDeltaVsLastMonth: null });
    expect(screen.queryByText(/this month/)).toBeNull();
  });

  it('renders the onboarding branch, with a Continue Setup CTA, when not available', () => {
    const onImportPress = jest.fn();
    renderHero({ available: false, healthScoreTransactionCount: 4, healthScoreMinTransactions: 10, onImportPress });

    expect(screen.getByText('Getting Started')).toBeTruthy();
    expect(screen.getByText('4 / 10 transactions')).toBeTruthy();
    expect(screen.getByText('40%')).toBeTruthy();

    fireEvent.press(screen.getByText('Continue Setup'));
    expect(onImportPress).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mobile && npx jest src/components/dashboard/HealthHero.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/HealthHero.tsx`:

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { AnimatedHealthScoreNumber } from '../AnimatedHealthScoreNumber';
import { healthBarColor, healthColor } from '../../lib/health';
import { HealthSparkline } from './HealthSparkline';
import { radius, spacing, useTheme } from '../../theme';
import type { HealthScorePoint } from '../../types';

const GAUGE_WIDTH = 240;
const GAUGE_HEIGHT = 130;
const GAUGE_CX = GAUGE_WIDTH / 2;
const GAUGE_CY = 118;
const GAUGE_R = 100;
const GAUGE_STROKE = 16;

/** score 0 -> 180deg (left), score 100 -> 0deg (right), sweeping over the top -- a standard
 *  semi-circle gauge. Not reused from lib/chartGeometry.ts's arcPath: that helper is fixed to
 *  the donut's own DONUT_CENTER/DONUT_RADIUS constants, not parameterized by center/radius, and
 *  this is the only place a semi-circle (rather than a full-circle arc) is needed. */
function pointAt(score: number) {
  const angle = (Math.PI * (100 - score)) / 100;
  return { x: GAUGE_CX + GAUGE_R * Math.cos(angle), y: GAUGE_CY - GAUGE_R * Math.sin(angle) };
}

function arcPath(fromScore: number, toScore: number): string {
  const start = pointAt(fromScore);
  const end = pointAt(toScore);
  return `M ${start.x} ${start.y} A ${GAUGE_R} ${GAUGE_R} 0 0 1 ${end.x} ${end.y}`;
}

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

/**
 * The screen's hero card -- dark surface, semi-circle gauge, score/label, month-over-month delta
 * pill, 6-month sparkline. Below healthScoreTransactionCount's floor it shows the same onboarding
 * progress the old inline card did (same three text pieces the pinned test in
 * DashboardScreen.test.tsx checks for), plus a new "Continue Setup" CTA into Import.
 *
 * healthColor/healthBarColor (not a fixed red/amber/green triple) drive the score/label text and
 * the progress arc's own color, so this stays consistent with every other health-score display on
 * the screen (the breakdown rows, Categorization Confidence) -- only the gauge's background TRACK
 * is a fixed 3-band red/amber/green scale face, the same way a real gauge's dial doesn't change
 * color, only its needle does.
 */
export function HealthHero({
  available, healthScore, healthLabel, healthScoreDeltaVsLastMonth, healthSparkline,
  healthScoreTransactionCount, healthScoreMinTransactions, onImportPress,
}: Props) {
  const c = useTheme();

  if (!available) {
    const percent = Math.round(Math.min(100, (healthScoreTransactionCount / healthScoreMinTransactions) * 100));
    return (
      <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
        <Text style={[styles.title, { color: c.onPrimary }]}>Financial Health Score</Text>
        <View style={styles.emptyWrap}>
          <Text style={[styles.emptyTitle, { color: c.onPrimary }]}>Getting Started</Text>
          <Text style={[styles.emptyBody, { color: c.primaryLight }]}>
            Import more transactions to unlock your Financial Health Score.
          </Text>
          <View style={styles.emptyProgressLabels}>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight }]}>
              {healthScoreTransactionCount} / {healthScoreMinTransactions} transactions
            </Text>
            <Text style={[styles.emptyProgressText, { color: c.primaryLight }]}>{percent}%</Text>
          </View>
          <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
            <View style={[styles.progressFill, { width: `${percent}%`, backgroundColor: c.onPrimary }]} />
          </View>
          <Pressable
            onPress={onImportPress}
            hitSlop={8}
            style={[styles.continueButton, { backgroundColor: c.onPrimary }]}
            accessibilityRole="button"
          >
            <Text style={[styles.continueButtonText, { color: c.primaryDark }]}>Continue Setup</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  const scoreColor = healthColor(healthLabel, c);
  const progressColor = healthBarColor(healthScore, c);
  const deltaPositive = (healthScoreDeltaVsLastMonth ?? 0) > 0;

  return (
    <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
      <View style={styles.headerRow}>
        <Text style={[styles.title, { color: c.onPrimary }]}>Financial Health Score</Text>
        {healthScoreDeltaVsLastMonth !== null && healthScoreDeltaVsLastMonth !== 0 ? (
          <View style={[styles.deltaPill, { backgroundColor: deltaPositive ? c.successBg : c.dangerBg }]}>
            <Text style={[styles.deltaPillText, { color: deltaPositive ? c.successInk : c.danger }]}>
              {deltaPositive ? '+' : ''}{healthScoreDeltaVsLastMonth} this month
            </Text>
          </View>
        ) : null}
      </View>

      <View style={styles.gaugeWrap}>
        <Svg width={GAUGE_WIDTH} height={GAUGE_HEIGHT}>
          {/* Fixed 3-band scale face -- always the same red/amber/green thirds, independent of
              the actual score, the way a speedometer's dial never changes. */}
          <Path d={arcPath(0, 30)} stroke={c.danger} strokeWidth={GAUGE_STROKE} fill="none" strokeLinecap="round" />
          <Path d={arcPath(30, 60)} stroke={c.warning} strokeWidth={GAUGE_STROKE} fill="none" />
          <Path d={arcPath(60, 100)} stroke={c.success} strokeWidth={GAUGE_STROKE} fill="none" strokeLinecap="round" />
          {/* Progress needle-arc, 0 up to the real score, colored by this app's own healthColor
              cutoffs (not the fixed band color) so it agrees with the score/label text below it. */}
          {healthScore > 0 ? (
            <Path d={arcPath(0, healthScore)} stroke={progressColor} strokeWidth={GAUGE_STROKE + 4} fill="none" strokeLinecap="round" />
          ) : null}
        </Svg>
        <View style={styles.gaugeScoreWrap} pointerEvents="none">
          <AnimatedHealthScoreNumber
            testID="health-score-value"
            value={healthScore}
            style={[styles.scoreValue, { color: scoreColor }]}
          />
          <Text style={[styles.scoreLabel, { color: scoreColor }]}>{healthLabel}</Text>
        </View>
      </View>

      {healthSparkline.length >= 2 ? (
        <View style={styles.sparklineWrap}>
          <Text style={[styles.sparklineLabel, { color: c.primaryLight }]}>6-month trend</Text>
          <HealthSparkline points={healthSparkline} color={c.onPrimary} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: radius.xl, padding: spacing.lg, overflow: 'hidden' },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  title: { fontSize: 16, fontWeight: '700' },
  deltaPill: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  deltaPillText: { fontSize: 12, fontWeight: '700' },
  gaugeWrap: { alignItems: 'center', marginTop: spacing.sm },
  gaugeScoreWrap: { position: 'absolute', top: 60, alignItems: 'center' },
  scoreValue: { fontSize: 40, fontWeight: '800' },
  scoreLabel: { fontSize: 14, fontWeight: '700', marginTop: 2 },
  sparklineWrap: { marginTop: spacing.md },
  sparklineLabel: { fontSize: 11, fontWeight: '600', marginBottom: 4 },
  emptyWrap: { alignItems: 'center', paddingTop: spacing.md, gap: 6 },
  emptyTitle: { fontSize: 15, fontWeight: '700' },
  emptyBody: { fontSize: 13, textAlign: 'center', maxWidth: 240 },
  emptyProgressLabels: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: spacing.sm },
  emptyProgressText: { fontSize: 12 },
  progressTrack: { height: 6, borderRadius: 3, overflow: 'hidden', width: '100%', marginTop: 6 },
  progressFill: { height: 6, borderRadius: 3 },
  continueButton: { marginTop: spacing.md, minHeight: 40, paddingHorizontal: spacing.lg, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  continueButtonText: { fontSize: 13, fontWeight: '700' },
});
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && npx jest src/components/dashboard/HealthHero.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/HealthHero.tsx mobile/src/components/dashboard/HealthHero.test.tsx
git commit -m "feat(mobile): add the Financial Health hero card with gauge, delta pill and sparkline"
```

---

### Task 6: `HealthFactorsRow` — horizontal-scroll breakdown cards

**Files:**
- Create: `mobile/src/components/dashboard/HealthFactorsRow.tsx`
- Test: `mobile/src/components/dashboard/HealthFactorsRow.test.tsx`

**Interfaces:**
- Consumes: `healthBarColor`, `healthToneBg`, `scoreLabel`, `healthImprovementSuggestion` from `../../lib/health` (Task 2).
- Produces: `<HealthFactorsRow available breakdown breakdownDetail topOpportunityFactor topOpportunityPotentialGain />` — consumed by Task 15.

- [ ] **Step 1: Write the failing tests**

Create `mobile/src/components/dashboard/HealthFactorsRow.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { HealthFactorsRow } from './HealthFactorsRow';
import { ThemeProvider } from '../../theme';

function renderRow(props: Partial<React.ComponentProps<typeof HealthFactorsRow>> = {}) {
  return render(
    <ThemeProvider>
      <HealthFactorsRow
        available
        breakdown={{ 'Debt Score': 100, 'Savings Rate': 65 }}
        breakdownDetail={{ 'Debt Score': 'No credit card balance carried over.' }}
        topOpportunityFactor={null}
        topOpportunityPotentialGain={null}
        {...props}
      />
    </ThemeProvider>
  );
}

describe('HealthFactorsRow', () => {
  it('renders nothing when not available', () => {
    const { toJSON } = renderRow({ available: false });
    expect(toJSON()).toBeNull();
  });

  it('shows each factor name, score and label', () => {
    renderRow();
    expect(screen.getByText('Debt Score')).toBeTruthy();
    expect(screen.getByText('100%')).toBeTruthy();
    expect(screen.getByText('Savings Rate')).toBeTruthy();
    expect(screen.getByText('65%')).toBeTruthy();
    expect(screen.getByText('Good')).toBeTruthy();
  });

  it('keeps the Why?/Hide detail disclosure, only for factors with a detail entry', async () => {
    renderRow();
    const whys = screen.getAllByText('Why?');
    expect(whys).toHaveLength(1);
    expect(screen.queryByText('No credit card balance carried over.')).toBeNull();

    fireEvent.press(whys[0]);
    expect(await screen.findByText('No credit card balance carried over.')).toBeTruthy();
    expect(screen.getByText('Hide')).toBeTruthy();
  });

  it('shows the improvement suggestion for every factor', () => {
    renderRow();
    expect(screen.getByText('Aim to save at least 24% of your income each month.')).toBeTruthy();
  });

  it('highlights the top-opportunity factor with its potential gain', () => {
    renderRow({ topOpportunityFactor: 'Savings Rate', topOpportunityPotentialGain: 14 });
    expect(screen.getByText('↑ +14 point opportunity')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mobile && npx jest src/components/dashboard/HealthFactorsRow.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/HealthFactorsRow.tsx`:

```tsx
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { healthBarColor, healthImprovementSuggestion, healthToneBg, scoreLabel } from '../../lib/health';
import { radius, spacing, useTheme } from '../../theme';

interface Props {
  available: boolean;
  breakdown: Record<string, number>;
  breakdownDetail: Record<string, string>;
  topOpportunityFactor: string | null;
  topOpportunityPotentialGain: number | null;
}

/**
 * Horizontal-scroll factor cards -- the user's explicit request to keep the Financial Health
 * breakdown visible and prominent ("one of Fynora's most unique features"). Keeps the existing
 * per-row "Why?"/"Hide" detail-disclosure interaction pinned by
 * DashboardScreen.test.tsx's "shows the score and breakdown once available..." test, while adding
 * web's healthImprovementSuggestion copy and the top-opportunity highlight on top of it.
 */
export function HealthFactorsRow({
  available, breakdown, breakdownDetail, topOpportunityFactor, topOpportunityPotentialGain,
}: Props) {
  const c = useTheme();
  const [expanded, setExpanded] = useState<string | null>(null);

  if (!available) return null;

  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {Object.entries(breakdown).map(([name, score]) => {
        const detail = breakdownDetail[name];
        const isExpanded = expanded === name;
        const isTopOpportunity = name === topOpportunityFactor && topOpportunityPotentialGain !== null;
        return (
          <View key={name} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
            <View style={styles.headerRow}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{name}</Text>
              <View style={[styles.pill, { backgroundColor: healthToneBg(score, c) }]}>
                <Text style={[styles.pillText, { color: healthBarColor(score, c) }]}>{scoreLabel(score)}</Text>
              </View>
            </View>
            <View style={styles.scoreRow}>
              <Text style={[styles.score, { color: c.ink }]}>{Math.round(score)}%</Text>
              {detail ? (
                <Pressable
                  onPress={() => setExpanded((cur) => (cur === name ? null : name))}
                  hitSlop={8}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: isExpanded }}
                  accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
                >
                  <Text style={[styles.why, { color: c.primary }]}>{isExpanded ? 'Hide' : 'Why?'}</Text>
                </Pressable>
              ) : null}
            </View>
            {detail && isExpanded ? <Text style={[styles.detail, { color: c.muted }]}>{detail}</Text> : null}
            <Text style={[styles.suggestion, { color: c.muted }]}>{healthImprovementSuggestion(name, score)}</Text>
            {isTopOpportunity ? (
              <Text style={[styles.opportunity, { color: c.primary }]}>
                ↑ +{topOpportunityPotentialGain} point opportunity
              </Text>
            ) : null}
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  row: { gap: spacing.sm, paddingVertical: spacing.xs },
  card: { width: 200, borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.xs },
  name: { fontSize: 13, fontWeight: '600', flexShrink: 1 },
  pill: { borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 },
  pillText: { fontSize: 10, fontWeight: '700' },
  scoreRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 6 },
  score: { fontSize: 18, fontWeight: '700' },
  why: { fontSize: 12, fontWeight: '600', textDecorationLine: 'underline' },
  detail: { fontSize: 11, lineHeight: 15, marginTop: 4 },
  suggestion: { fontSize: 11, lineHeight: 15, marginTop: 6 },
  opportunity: { fontSize: 11, fontWeight: '700', marginTop: 6 },
});
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mobile && npx jest src/components/dashboard/HealthFactorsRow.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/HealthFactorsRow.tsx mobile/src/components/dashboard/HealthFactorsRow.test.tsx
git commit -m "feat(mobile): add horizontal-scroll Financial Health factor cards"
```

---

### Task 7: `MonthlySnapshotGrid` — 2×2 KPI grid

**Files:**
- Create: `mobile/src/components/dashboard/MonthlySnapshotGrid.tsx`
- Test: `mobile/src/components/dashboard/MonthlySnapshotGrid.test.tsx`

**Interfaces:**
- Consumes: `AnimatedNumber` (existing).
- Produces: `<MonthlySnapshotGrid kpis={KpiItem[]} deltaLabel={string} deltaSpokenLabel={string} />`, exported `KpiItem` type — consumed by Task 15.

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/MonthlySnapshotGrid.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react-native';
import { MonthlySnapshotGrid } from './MonthlySnapshotGrid';
import { ThemeProvider } from '../../theme';

describe('MonthlySnapshotGrid', () => {
  it('renders each KPI value, with testID, and its delta', () => {
    render(
      <ThemeProvider>
        <MonthlySnapshotGrid
          deltaLabel="vs last month"
          deltaSpokenLabel="versus last month"
          kpis={[
            { label: 'Income', value: 145000, delta: 12, invert: false, caption: null, isPercent: false },
            { label: 'Expenses', value: 12831, delta: -24, invert: true, caption: null, isPercent: false },
            { label: 'Net Savings', value: 132169, delta: 18, invert: false, caption: null, isPercent: false },
            { label: 'Savings Rate', value: 91, delta: null, invert: false, caption: null, isPercent: true },
          ]}
        />
      </ThemeProvider>
    );

    expect(screen.getByTestId('kpi-Income')).toHaveProp('defaultValue', '₹1,45,000');
    expect(screen.getByText('▲ 12.0% vs last month')).toBeTruthy();
    expect(screen.getByText('91%')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/MonthlySnapshotGrid.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/MonthlySnapshotGrid.tsx` — this is `DashboardScreen.tsx`'s current `kpis.map(...)` block (lines 500-564), extracted unchanged into its own component so it can be reused for a 4-item subset instead of all 5:

```tsx
import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { Card } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  value: number;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

export function MonthlySnapshotGrid({
  kpis, deltaLabel, deltaSpokenLabel,
}: {
  kpis: KpiItem[];
  deltaLabel: string;
  deltaSpokenLabel: string;
}) {
  const c = useTheme();
  return (
    <View style={styles.grid}>
      {kpis.map((k) => {
        const displayValue = k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);
        return (
          <Card key={k.label} style={styles.card}>
            <View
              accessible
              accessibilityLabel={
                k.delta !== null && k.delta !== undefined
                  ? `${k.label}: ${displayValue}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                  : k.caption
                    ? `${k.label}: ${displayValue}, ${k.caption}`
                    : `${k.label}: ${displayValue}`
              }
            >
              <Text style={[styles.label, { color: c.muted }]}>{k.label}</Text>
              {k.isPercent ? (
                <Text testID={`kpi-${k.label}`} style={[styles.value, { color: c.ink }]} numberOfLines={1}>
                  {displayValue}
                </Text>
              ) : (
                <AnimatedNumber testID={`kpi-${k.label}`} value={k.value} style={[styles.value, { color: c.ink }]} />
              )}
              {k.delta !== null && k.delta !== undefined ? (
                <Text style={[styles.delta, { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger }]}>
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : k.caption ? (
                <Text style={[styles.delta, { color: c.mutedInk }]}>{k.caption}</Text>
              ) : (
                <Text style={styles.delta} />
              )}
            </View>
          </Card>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  card: { width: '48%', flexGrow: 1 },
  label: { fontSize: 12 },
  value: { fontSize: 19, fontWeight: '700', marginTop: 4 },
  delta: { fontSize: 11, marginTop: 2, minHeight: 14 },
});
```

Note: `radius` is imported but unused in this file if no `borderRadius` style references it directly — remove the `radius` import if `tsc`/lint flags it unused (the original `kpiCard` style didn't use `radius` either, so this matches).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/MonthlySnapshotGrid.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/MonthlySnapshotGrid.tsx mobile/src/components/dashboard/MonthlySnapshotGrid.test.tsx
git commit -m "refactor(mobile): extract the KPI grid into a reusable MonthlySnapshotGrid component"
```

---

### Task 8: `CashFlowMiniCard` — compact net-savings sparkline

**Files:**
- Create: `mobile/src/components/dashboard/CashFlowMiniCard.tsx`
- Test: `mobile/src/components/dashboard/CashFlowMiniCard.test.tsx`

**Interfaces:**
- Consumes: `deriveNetSavingsSeries`, `averageMonthlySavings` from `../../lib/dashboardMetrics` (Task 3); `CashFlowPoint` from `../charts/CashFlowChart`.
- Produces: `<CashFlowMiniCard points={CashFlowPoint[]} deltaPct={number | null} />` — consumed by Task 15.

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/CashFlowMiniCard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react-native';
import { CashFlowMiniCard } from './CashFlowMiniCard';
import { ThemeProvider } from '../../theme';

describe('CashFlowMiniCard', () => {
  it('shows the average monthly savings figure and delta', () => {
    render(
      <ThemeProvider>
        <CashFlowMiniCard
          deltaPct={22}
          points={[
            { label: 'Jul', income: 50000, expense: 30000 },
            { label: 'Aug', income: 40000, expense: 20000 },
          ]}
        />
      </ThemeProvider>
    );
    expect(screen.getByText('Cash Flow Trend')).toBeTruthy();
    expect(screen.getByText('₹20,000')).toBeTruthy();
    expect(screen.getByText('▲ 22.0%')).toBeTruthy();
  });

  it('shows an empty message with no points', () => {
    render(<ThemeProvider><CashFlowMiniCard points={[]} deltaPct={null} /></ThemeProvider>);
    expect(screen.getByText('No monthly data yet.')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/CashFlowMiniCard.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/CashFlowMiniCard.tsx`:

```tsx
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Polyline } from 'react-native-svg';
import { Card, SectionHeading } from '../Card';
import type { CashFlowPoint } from '../charts/CashFlowChart';
import { averageMonthlySavings, deriveNetSavingsSeries } from '../../lib/dashboardMetrics';
import { fmtCurrency } from '../../lib/format';
import { spacing, useTheme } from '../../theme';

const WIDTH = 280;
const HEIGHT = 56;

export function CashFlowMiniCard({ points, deltaPct }: { points: CashFlowPoint[]; deltaPct: number | null }) {
  const c = useTheme();

  if (points.length === 0) {
    return (
      <Card style={styles.card}>
        <SectionHeading title="Cash Flow Trend" />
        <Text style={[styles.empty, { color: c.muted }]}>No monthly data yet.</Text>
      </Card>
    );
  }

  const series = deriveNetSavingsSeries(points);
  const values = series.map((s) => s.net);
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 0);
  const range = max - min || 1;
  const xAt = (i: number) => (series.length <= 1 ? 0 : (i / (series.length - 1)) * WIDTH);
  const yAt = (v: number) => HEIGHT - ((v - min) / range) * HEIGHT;
  const linePoints = series.map((s, i) => `${xAt(i)},${yAt(s.net)}`).join(' ');
  const average = averageMonthlySavings(points);

  return (
    <Card style={styles.card}>
      <SectionHeading title="Cash Flow Trend" />
      <Svg width="100%" height={HEIGHT} viewBox={`0 0 ${WIDTH} ${HEIGHT}`}>
        <Polyline points={linePoints} fill="none" stroke={c.success} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      </Svg>
      <View style={styles.footer}>
        <View>
          <Text style={[styles.label, { color: c.muted }]}>Average Monthly Savings</Text>
          <Text style={[styles.value, { color: c.ink }]}>{fmtCurrency(average)}</Text>
        </View>
        {deltaPct !== null ? (
          <Text style={[styles.delta, { color: deltaPct >= 0 ? c.success : c.danger }]}>
            {deltaPct >= 0 ? '▲' : '▼'} {Math.abs(deltaPct).toFixed(1)}%
          </Text>
        ) : null}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {},
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
  footer: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: spacing.sm },
  label: { fontSize: 11 },
  value: { fontSize: 18, fontWeight: '700', marginTop: 2 },
  delta: { fontSize: 13, fontWeight: '700' },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/CashFlowMiniCard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/CashFlowMiniCard.tsx mobile/src/components/dashboard/CashFlowMiniCard.test.tsx
git commit -m "feat(mobile): add the compact Cash Flow Trend card"
```

---

### Task 9: `AccountsCard`

**Files:**
- Create: `mobile/src/components/dashboard/AccountsCard.tsx`
- Test: `mobile/src/components/dashboard/AccountsCard.test.tsx`

**Interfaces:**
- Consumes: `Account` type from `../../types`.
- Produces: `<AccountsCard accounts={Account[]} totalBalance={number} caption={string} onViewAll={() => void} />` — consumed by Task 15.

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/AccountsCard.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { AccountsCard } from './AccountsCard';
import { ThemeProvider } from '../../theme';
import type { Account } from '../../types';

function account(overrides: Partial<Account>): Account {
  return {
    id: 'a1', name: 'Salary Account', accountType: 'SAVINGS', balance: 100000,
    bank: { id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004C8F', initials: 'HD', logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [] },
    lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE',
    ...overrides,
  } as Account;
}

describe('AccountsCard', () => {
  it('shows account/bank counts, total balance and caption', () => {
    render(
      <ThemeProvider>
        <AccountsCard
          totalBalance={1248320}
          caption="As of today"
          onViewAll={jest.fn()}
          accounts={[
            account({ id: 'a1', bank: { ...account({}).bank, id: 'hdfc', shortName: 'HDFC' } }),
            account({ id: 'a2', bank: { ...account({}).bank, id: 'sbi', shortName: 'SBI' } }),
          ]}
        />
      </ThemeProvider>
    );

    expect(screen.getByText('2 Accounts')).toBeTruthy();
    expect(screen.getByText('2 Banks')).toBeTruthy();
    expect(screen.getByText('₹12,48,320')).toBeTruthy();
    expect(screen.getByText('As of today')).toBeTruthy();
    expect(screen.getByText('HD')).toBeTruthy();
  });

  it('navigates on View Accounts', () => {
    const onViewAll = jest.fn();
    render(<ThemeProvider><AccountsCard accounts={[]} totalBalance={0} caption="As of today" onViewAll={onViewAll} /></ThemeProvider>);
    fireEvent.press(screen.getByText('View Accounts'));
    expect(onViewAll).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/AccountsCard.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/AccountsCard.tsx`:

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Card, SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';
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
    <Card style={styles.card}>
      <SectionHeading title="Accounts" />
      <Text style={[styles.counts, { color: c.muted }]}>
        {accounts.length} Account{accounts.length === 1 ? '' : 's'} · {bankCount} Bank{bankCount === 1 ? '' : 's'}
      </Text>
      <View style={styles.avatarRow}>
        {shown.map((a) => (
          <View key={a.id} style={[styles.avatar, { backgroundColor: a.bank.colorHex }]}>
            <Text style={styles.avatarText}>{a.bank.initials}</Text>
          </View>
        ))}
        {overflow > 0 ? (
          <View style={[styles.avatar, { backgroundColor: c.border }]}>
            <Text style={[styles.avatarText, { color: c.ink }]}>+{overflow}</Text>
          </View>
        ) : null}
      </View>
      <Text style={[styles.balanceLabel, { color: c.muted }]}>Total Balance</Text>
      <Text style={[styles.balanceValue, { color: c.ink }]}>{fmtCurrency(totalBalance)}</Text>
      <Text style={[styles.caption, { color: c.mutedInk }]}>{caption}</Text>
      <Pressable
        onPress={onViewAll}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primaryLight }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.primary }]}>View Accounts</Text>
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {},
  counts: { fontSize: 12, marginBottom: spacing.sm },
  avatarRow: { flexDirection: 'row', marginBottom: spacing.sm },
  avatar: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
    marginRight: -8, borderWidth: 2, borderColor: '#FFFFFF',
  },
  avatarText: { fontSize: 10, fontWeight: '700', color: '#FFFFFF' },
  balanceLabel: { fontSize: 11, marginTop: spacing.xs },
  balanceValue: { fontSize: 20, fontWeight: '700', marginTop: 2 },
  caption: { fontSize: 11, marginTop: 2 },
  cta: { marginTop: spacing.sm, minHeight: 36, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  ctaText: { fontSize: 12, fontWeight: '600' },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/AccountsCard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/AccountsCard.tsx mobile/src/components/dashboard/AccountsCard.test.tsx
git commit -m "feat(mobile): add the Accounts summary card, binding the previously-unused accounts query"
```

---

### Task 10: `GoalsRow` — horizontal-scroll goal cards

**Files:**
- Create: `mobile/src/components/dashboard/GoalsRow.tsx`
- Test: `mobile/src/components/dashboard/GoalsRow.test.tsx`

**Interfaces:**
- Consumes: `Goal` type from `../../types`.
- Produces: `<GoalsRow goals={Goal[]} />` — consumed by Task 15.

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/GoalsRow.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react-native';
import { GoalsRow } from './GoalsRow';
import { ThemeProvider } from '../../theme';

describe('GoalsRow', () => {
  it('shows each goal name, amounts and percent', () => {
    render(
      <ThemeProvider>
        <GoalsRow goals={[
          { id: 'g1', name: 'Emergency Fund', currentAmount: 60000, targetAmount: 200000 },
          { id: 'g2', name: 'Europe Trip', currentAmount: 120000, targetAmount: 300000 },
        ]} />
      </ThemeProvider>
    );
    expect(screen.getByText('Emergency Fund')).toBeTruthy();
    expect(screen.getByText('₹60,000 of ₹2,00,000')).toBeTruthy();
    expect(screen.getByText('30%')).toBeTruthy();
    expect(screen.getByText('Europe Trip')).toBeTruthy();
    expect(screen.getByText('40%')).toBeTruthy();
  });

  it('renders nothing with no goals', () => {
    const { toJSON } = render(<ThemeProvider><GoalsRow goals={[]} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/GoalsRow.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/GoalsRow.tsx`:

```tsx
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';
import type { Goal } from '../../types';

export function GoalsRow({ goals }: { goals: Goal[] }) {
  const c = useTheme();
  if (goals.length === 0) return null;

  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {goals.map((g) => {
        const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
        return (
          <View key={g.id} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
            <View style={styles.header}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{g.name}</Text>
              <Text style={[styles.pct, { color: c.mutedInk }]}>{pct.toFixed(0)}%</Text>
            </View>
            <View style={[styles.track, { backgroundColor: c.border }]}>
              <View style={[styles.fill, { width: `${pct}%`, backgroundColor: c.primary }]} />
            </View>
            <Text style={[styles.meta, { color: c.mutedInk }]}>
              {fmtCurrency(g.currentAmount)} of {fmtCurrency(g.targetAmount)}
            </Text>
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  row: { gap: spacing.sm, paddingVertical: spacing.xs },
  card: { width: 180, borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  header: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 6 },
  name: { fontSize: 13, fontWeight: '600', flex: 1 },
  pct: { fontSize: 12 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: 6, borderRadius: 3 },
  meta: { fontSize: 11, marginTop: 6 },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/GoalsRow.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/GoalsRow.tsx mobile/src/components/dashboard/GoalsRow.test.tsx
git commit -m "feat(mobile): add a horizontal-scroll Goals row"
```

---

### Task 11: `AIInsightCard` — health-opportunity narrative

**Files:**
- Create: `mobile/src/components/dashboard/AIInsightCard.tsx`
- Test: `mobile/src/components/dashboard/AIInsightCard.test.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: `<AIInsightCard factor={string | null} potentialGain={number | null} onCreateGoal={() => void} />` — consumed by Task 15.

- [ ] **Step 1: Write the failing test**

Create `mobile/src/components/dashboard/AIInsightCard.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { AIInsightCard } from './AIInsightCard';
import { ThemeProvider } from '../../theme';

describe('AIInsightCard', () => {
  it('renders nothing without a real opportunity', () => {
    const { toJSON } = render(<ThemeProvider><AIInsightCard factor={null} potentialGain={null} onCreateGoal={jest.fn()} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });

  it('states the opportunity and potential gain, using the server\'s own computed factor', () => {
    const onCreateGoal = jest.fn();
    render(<ThemeProvider><AIInsightCard factor="Emergency Fund" potentialGain={14} onCreateGoal={onCreateGoal} /></ThemeProvider>);

    expect(screen.getByText('Your emergency fund is the biggest opportunity to improve your score.')).toBeTruthy();
    expect(screen.getByText('+14 points')).toBeTruthy();

    fireEvent.press(screen.getByText('Create Goal'));
    expect(onCreateGoal).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/AIInsightCard.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/dashboard/AIInsightCard.tsx`:

```tsx
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Ports frontend/src/pages/Dashboard.tsx's "AI Insight card" -- deliberately built from the
 * backend's own healthTopOpportunityFactor/healthTopOpportunityPotentialGain (a real, deterministic
 * computation, not an LLM call and not the first of the arbitrary insights sentences), so the
 * Financial Health -> Opportunity -> Create Goal narrative stays one connected thread with the
 * Hero's own gauge and the Health Factors row's highlighted card, exactly as the web app already
 * does it.
 */
export function AIInsightCard({
  factor, potentialGain, onCreateGoal,
}: {
  factor: string | null;
  potentialGain: number | null;
  onCreateGoal: () => void;
}) {
  const c = useTheme();
  if (!factor || potentialGain === null) return null;

  return (
    <View style={[styles.card, { backgroundColor: c.primaryLight, borderColor: c.primary }]}>
      <Ionicons name="sparkles-outline" size={18} color={c.primary} style={styles.icon} />
      <View style={styles.textWrap}>
        <Text style={[styles.headline, { color: c.ink }]}>
          Your {factor.toLowerCase()} is the biggest opportunity to improve your score.
        </Text>
        <Text style={[styles.gain, { color: c.muted }]}>
          Potential gain: <Text style={{ color: c.primary, fontWeight: '700' }}>+{potentialGain} points</Text>
        </Text>
      </View>
      <Pressable
        onPress={onCreateGoal}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primary }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.onPrimary }]}>Create Goal</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  icon: { marginTop: 2 },
  textWrap: { flex: 1 },
  headline: { fontSize: 13, fontWeight: '600', lineHeight: 18 },
  gain: { fontSize: 12, marginTop: 4 },
  cta: { minHeight: 36, paddingHorizontal: spacing.md, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', alignSelf: 'center' },
  ctaText: { fontSize: 12, fontWeight: '700' },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/AIInsightCard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/dashboard/AIInsightCard.tsx mobile/src/components/dashboard/AIInsightCard.test.tsx
git commit -m "feat(mobile): add the AI Insight card, ported from web's health-opportunity feature"
```

---

### Task 12: `ChecklistWidget` — collapsible accordion

**Files:**
- Modify: `mobile/src/onboarding/ChecklistWidget.tsx`
- Modify: `mobile/src/onboarding/ChecklistWidget.test.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: same `<ChecklistWidget />` (no new props) — consumed unchanged by Task 15.

- [ ] **Step 1: Write the failing tests**

Add to `mobile/src/onboarding/ChecklistWidget.test.tsx`, after the existing 3 tests:

```tsx
  it('starts collapsed -- item labels are not shown until the header is pressed', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();

    await waitFor(() => expect(screen.getByText('Getting Started')).toBeTruthy());
    expect(screen.getByText('1 / 6 Complete')).toBeTruthy();
    expect(screen.queryByText(/Complete your profile/)).toBeNull();
  });

  it('expands to show items and progress bar on press, collapses again on a second press', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();
    const header = await screen.findByRole('button', { name: /Getting Started/ });

    fireEvent.press(header);
    expect(await screen.findByText(/Complete your profile/)).toBeTruthy();

    fireEvent.press(header);
    await waitFor(() => expect(screen.queryByText(/Complete your profile/)).toBeNull());
  });
```

Add `fireEvent` to the file's existing `@testing-library/react-native` import.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mobile && npx jest src/onboarding/ChecklistWidget.test.tsx`
Expected: The 2 new tests FAIL (no accordion yet — everything currently always renders, and there's no accessible "button" role on the header). The 3 pre-existing tests: "shows progress text" now expects `'4 of 6 completed'` which the new header text no longer produces verbatim — this one is expected to fail too, and gets fixed in Step 3 alongside the implementation (its assertion string changes in Step 4, not before).

- [ ] **Step 3: Implement the accordion**

Replace the full contents of `mobile/src/onboarding/ChecklistWidget.tsx`:

```tsx
import { useState } from 'react';
import { Pressable, Text, View, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../components/Card';
import { onboardingApi } from '../api/endpoints';
import { useTheme } from '../theme';
import { CHECKLIST_ITEMS } from './checklistItems';

/**
 * Moved to the very end of the Dashboard's scroll (premium-redesign mockup) and made a
 * collapsed-by-default accordion -- previously always fully expanded at the top of the screen,
 * competing for attention with everything else on first load.
 */
export function ChecklistWidget() {
  const c = useTheme();
  const [expanded, setExpanded] = useState(false);
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));
  const percent = Math.round((data.completedCount / data.totalCount) * 100);

  return (
    <Card style={styles.card}>
      <Pressable
        onPress={() => setExpanded((v) => !v)}
        style={styles.header}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        accessibilityLabel={`Getting Started, ${data.completedCount} of ${data.totalCount} complete`}
      >
        <View>
          <Text style={[styles.title, { color: c.ink }]}>Getting Started</Text>
          <Text style={[styles.progress, { color: c.muted }]}>{data.completedCount} / {data.totalCount} Complete</Text>
        </View>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={18} color={c.muted} />
      </Pressable>
      {expanded ? (
        <View style={styles.body}>
          <View style={[styles.track, { backgroundColor: c.border }]}>
            <View style={[styles.fill, { backgroundColor: c.primary, width: `${percent}%` }]} />
          </View>
          {CHECKLIST_ITEMS.map((item) => (
            <View key={item.key} style={styles.row}>
              <Text style={{ color: c.ink }}>{completedKeys.has(item.key) ? '✅' : '⬜'}</Text>
              <Text style={[styles.label, { color: c.muted }]}>{item.label}</Text>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { marginBottom: 16, padding: 16 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  title: { fontSize: 15, fontWeight: '600' },
  progress: { fontSize: 12, marginTop: 2 },
  body: { marginTop: 12 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden', marginBottom: 12 },
  fill: { height: '100%' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 },
  label: { fontSize: 14 },
});
```

- [ ] **Step 4: Fix the one pre-existing test whose assertion text changed**

In `mobile/src/onboarding/ChecklistWidget.test.tsx`, change the first test's assertion from `'4 of 6 completed'` to the new header copy:

```tsx
  it('shows progress text for a partially complete checklist', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 4, totalCount: 6,
    });
    renderWithClient();
    await waitFor(() => expect(screen.getByText('4 / 6 Complete')).toBeTruthy());
  });
```

The third pre-existing test ("shows every item label") presses nothing today and expects item labels visible immediately — update it to press the header first:

```tsx
  it('shows every item label once expanded', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [
        { key: 'COMPLETE_PROFILE', completed: true },
        { key: 'IMPORT_STATEMENT', completed: false },
        { key: 'REVIEW_TRANSACTIONS', completed: false },
        { key: 'CREATE_BUDGET', completed: false },
        { key: 'CREATE_GOAL', completed: false },
        { key: 'VIEW_INSIGHTS', completed: false },
      ],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();
    fireEvent.press(await screen.findByRole('button', { name: /Getting Started/ }));
    expect(await screen.findByText(/Complete your profile/)).toBeTruthy();
    expect(screen.getByText(/Import first statement/)).toBeTruthy();
  });
```

- [ ] **Step 5: Run the full file to verify everything passes**

Run: `cd mobile && npx jest src/onboarding/ChecklistWidget.test.tsx`
Expected: all tests PASS (5 total: the 2 new plus the 3 pre-existing, 2 of which were updated).

- [ ] **Step 6: Commit**

```bash
git add mobile/src/onboarding/ChecklistWidget.tsx mobile/src/onboarding/ChecklistWidget.test.tsx
git commit -m "feat(mobile): make Getting Started a collapsed-by-default accordion"
```

---

### Task 13: Toast system

**Files:**
- Create: `mobile/src/context/ToastContext.tsx`
- Create: `mobile/src/components/Toast.tsx`
- Test: `mobile/src/context/ToastContext.test.tsx`
- Modify: `mobile/App.tsx`

**Interfaces:**
- Consumes: nothing new.
- Produces: `<ToastProvider>`, `useToast(): { showToast: (title: string, body?: string) => void }` — consumed by Task 15 (Add Transaction saved).

- [ ] **Step 1: Write the failing test**

Create `mobile/src/context/ToastContext.test.tsx`:

```tsx
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Text, Pressable } from 'react-native';
import { ToastProvider, useToast } from './ToastContext';
import { ThemeProvider } from '../theme';

function Trigger() {
  const { showToast } = useToast();
  return <Pressable onPress={() => showToast('Goal Created', 'Your Emergency Fund goal is now active.')}><Text>trigger</Text></Pressable>;
}

function renderTrigger() {
  return render(
    <ThemeProvider>
      <ToastProvider>
        <Trigger />
      </ToastProvider>
    </ThemeProvider>
  );
}

describe('ToastProvider/useToast', () => {
  it('shows nothing until showToast is called', () => {
    renderTrigger();
    expect(screen.queryByText('Goal Created')).toBeNull();
  });

  it('shows the title and body after showToast, then auto-dismisses after 3 seconds', async () => {
    jest.useFakeTimers();
    renderTrigger();

    fireEvent.press(screen.getByText('trigger'));
    expect(screen.getByText('Goal Created')).toBeTruthy();
    expect(screen.getByText('Your Emergency Fund goal is now active.')).toBeTruthy();

    act(() => { jest.advanceTimersByTime(3000); });
    expect(screen.queryByText('Goal Created')).toBeNull();
    jest.useRealTimers();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/context/ToastContext.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement**

Create `mobile/src/components/Toast.tsx`:

```tsx
import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../theme';

export function Toast({ title, body }: { title: string; body?: string }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View pointerEvents="none" style={[styles.wrap, { bottom: insets.bottom + spacing.lg }]}>
      <View style={[styles.card, { backgroundColor: c.primaryDark }]}>
        <Text style={[styles.title, { color: c.onPrimary }]}>✓ {title}</Text>
        {body ? <Text style={[styles.body, { color: c.primaryLight }]}>{body}</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: spacing.md, right: spacing.md, alignItems: 'center' },
  card: { borderRadius: radius.lg, paddingHorizontal: spacing.md, paddingVertical: spacing.sm, maxWidth: 360, width: '100%' },
  title: { fontSize: 14, fontWeight: '700' },
  body: { fontSize: 12, marginTop: 2 },
});
```

Create `mobile/src/context/ToastContext.tsx`:

```tsx
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Toast } from '../components/Toast';

interface ToastState { title: string; body?: string }
interface ToastContextValue { showToast: (title: string, body?: string) => void }

const ToastContext = createContext<ToastContextValue | null>(null);

const AUTO_DISMISS_MS = 3000;

/**
 * A single toast at a time -- the mockup's own examples ("Goal Created", "Statement Imported")
 * are one-off success confirmations, never a queue. A later showToast call while one is visible
 * simply replaces it and restarts the 3s timer, rather than stacking.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((title: string, body?: string) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setToast({ title, body });
    timerRef.current = setTimeout(() => setToast(null), AUTO_DISMISS_MS);
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {toast ? <Toast title={toast.title} body={toast.body} /> : null}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside a ToastProvider');
  return ctx;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/context/ToastContext.test.tsx`
Expected: PASS.

- [ ] **Step 5: Mount `ToastProvider` in `App.tsx`**

In `mobile/App.tsx`, add the import:

```tsx
import { ToastProvider } from './src/context/ToastContext';
```

Wrap `<RootErrorBoundary>` with it, inside `<OnboardingStepProvider>` (innermost, since only screens inside `AppTabs` use `useToast` today):

```tsx
                    <RootErrorBoundary>
                      <ToastProvider>
                        <RootNavigator />
                      </ToastProvider>
                    </RootErrorBoundary>
```

- [ ] **Step 6: Commit**

```bash
git add mobile/src/components/Toast.tsx mobile/src/context/ToastContext.tsx mobile/src/context/ToastContext.test.tsx mobile/App.tsx
git commit -m "feat(mobile): add a reusable Toast/useToast system"
```

---

### Task 14: Floating "+" bottom-nav button

**Files:**
- Create: `mobile/src/components/dashboard/QuickActionSheet.tsx`
- Test: `mobile/src/components/dashboard/QuickActionSheet.test.tsx`
- Modify: `mobile/src/navigation/AppTabs.tsx`
- Modify: `mobile/src/navigation/AppTabs.test.tsx`
- Modify: `mobile/src/navigation/types.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `<QuickActionSheet visible onClose onImportStatement onAddTransaction onAddGoal />`; `Home` tab route now carries `{ openAddTransaction?: boolean; nonce?: number } | undefined` — consumed by Task 15.

- [ ] **Step 1: Write the failing test for `QuickActionSheet`**

Create `mobile/src/components/dashboard/QuickActionSheet.test.tsx`:

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { QuickActionSheet } from './QuickActionSheet';
import { ThemeProvider } from '../../theme';

describe('QuickActionSheet', () => {
  it('renders nothing when not visible', () => {
    const { queryByText } = render(
      <ThemeProvider>
        <QuickActionSheet visible={false} onClose={jest.fn()} onImportStatement={jest.fn()} onAddTransaction={jest.fn()} onAddGoal={jest.fn()} />
      </ThemeProvider>
    );
    expect(queryByText('Import Statement')).toBeNull();
  });

  it('fires the right callback per row and closes', () => {
    const onImportStatement = jest.fn();
    const onClose = jest.fn();
    render(
      <ThemeProvider>
        <QuickActionSheet visible onClose={onClose} onImportStatement={onImportStatement} onAddTransaction={jest.fn()} onAddGoal={jest.fn()} />
      </ThemeProvider>
    );

    fireEvent.press(screen.getByText('Import Statement'));
    expect(onImportStatement).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/components/dashboard/QuickActionSheet.test.tsx`
Expected: FAIL — module does not exist yet.

- [ ] **Step 3: Implement `QuickActionSheet`**

Create `mobile/src/components/dashboard/QuickActionSheet.tsx`:

```tsx
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, spacing, useTheme } from '../../theme';

interface Props {
  visible: boolean;
  onClose: () => void;
  onImportStatement: () => void;
  onAddTransaction: () => void;
  onAddGoal: () => void;
}

/** The bottom-nav floating "+" button's action sheet. Three destinations, same icons the
 *  existing Quick Actions card already uses for the same actions, for visual consistency. */
export function QuickActionSheet({ visible, onClose, onImportStatement, onAddTransaction, onAddGoal }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  function pick(action: () => void) {
    onClose();
    action();
  }

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close" accessibilityRole="button" />
      <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
        {(
          [
            { icon: 'cloud-upload-outline', label: 'Import Statement', onPress: () => pick(onImportStatement) },
            { icon: 'add-circle-outline', label: 'Add Transaction', onPress: () => pick(onAddTransaction) },
            { icon: 'flag-outline', label: 'Add Goal', onPress: () => pick(onAddGoal) },
          ] as const
        ).map((row) => (
          <Pressable key={row.label} onPress={row.onPress} style={styles.row} accessibilityRole="button" accessibilityLabel={row.label}>
            <Ionicons name={row.icon} size={20} color={c.primary} />
            <Text style={[styles.rowText, { color: c.ink }]}>{row.label}</Text>
          </Pressable>
        ))}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: { borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, padding: spacing.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 48, paddingHorizontal: spacing.sm },
  rowText: { fontSize: 15, fontWeight: '600' },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/components/dashboard/QuickActionSheet.test.tsx`
Expected: PASS.

- [ ] **Step 5: Add the `Home` tab param type**

In `mobile/src/navigation/types.ts`, change:

```ts
  Home: undefined;
```

to:

```ts
  // Set only when arriving via the bottom-nav floating "+" button's "Add Transaction" action --
  // a normal tap on the Home tab carries none. `nonce` forces DashboardScreen's effect to fire
  // again even if openAddTransaction is `true` twice in a row (same pattern as Transactions'
  // own drill-through `filters.nonce`, see DonutChart's onSlicePress call site).
  Home: { openAddTransaction?: boolean; nonce?: number } | undefined;
```

- [ ] **Step 6: Wire the FAB into `AppTabs.tsx`**

In `mobile/src/navigation/AppTabs.tsx`, add imports:

```tsx
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { QuickActionSheet } from '../components/dashboard/QuickActionSheet';
import type { AppTabParamList } from './types';
```

(`View` is already imported at the top — merge into the existing `import { View } from 'react-native';` line instead of duplicating it.)

Replace the `<Tab.Screen name="Import" component={ImportScreen} />` line with a version carrying a custom `tabBarButton`, and render `QuickActionSheet` inside `AppTabs` itself (it needs `useNavigation` to reach the `Home`/`Import` tabs, which only exists inside the `Tab.Navigator` tree — so it's rendered as a sibling to `<Tab.Navigator>`, both inside a wrapping `<View style={{ flex: 1 }}>`):

```tsx
function ImportFabButton({ onPress }: { onPress: () => void }) {
  const c = useTheme();
  return (
    <View style={styles.fabWrap} pointerEvents="box-none">
      <Pressable
        onPress={onPress}
        style={[styles.fab, { backgroundColor: c.primary }]}
        accessibilityRole="button"
        accessibilityLabel="Quick actions"
      >
        <Ionicons name="add" size={28} color={c.onPrimary} />
      </Pressable>
    </View>
  );
}

export function AppTabs() {
  const c = useTheme();
  const [sheetVisible, setSheetVisible] = useState(false);
  const registerHome = useRegisterTourTarget('home');
  const registerTransactions = useRegisterTourTarget('transactions');
  const registerImport = useRegisterTourTarget('import');
  const registerByTab: Partial<Record<keyof AppTabParamList, (node: View | null) => void>> = {
    Home: registerHome,
    Transactions: registerTransactions,
    Import: registerImport,
  };

  return (
    <View style={{ flex: 1 }}>
      <Tab.Navigator
        screenOptions={({ route }) => ({
          headerShown: false,
          tabBarActiveTintColor: c.primary,
          tabBarInactiveTintColor: c.muted,
          tabBarStyle: { backgroundColor: c.card, borderTopColor: c.border },
          tabBarIcon: ({ focused, color, size }) => {
            const icons = TAB_ICON[route.name];
            const register = registerByTab[route.name];
            return (
              <View ref={register}>
                <Ionicons name={(focused ? icons.active : icons.inactive) as any} size={size} color={color} />
              </View>
            );
          },
        })}
      >
        <Tab.Screen name="Home" component={DashboardScreen} />
        <Tab.Screen name="Transactions" component={LedgerScreen} />
        {/* Icon/label hidden -- ImportFabButton below renders the actual floating "+" affordance.
            The route itself stays: QuickActionSheet's "Import Statement" row still navigates
            here, same destination as before, just no longer reachable by tapping a plain tab
            icon in this slot. */}
        <Tab.Screen
          name="Import"
          component={ImportScreen}
          options={{ tabBarButton: () => <ImportFabButton onPress={() => setSheetVisible(true)} /> }}
        />
        <Tab.Screen name="More" component={MoreNavigator} />
      </Tab.Navigator>
      <QuickActionSheetHost visible={sheetVisible} onClose={() => setSheetVisible(false)} />
    </View>
  );
}

/** Split out of AppTabs so useNavigation() resolves against the Tab.Navigator it's nested
 *  under -- calling it directly in AppTabs would resolve one level too high, to whichever
 *  navigator mounts AppTabs itself. */
function QuickActionSheetHost({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();
  return (
    <QuickActionSheet
      visible={visible}
      onClose={onClose}
      onImportStatement={() => navigation.navigate('Import')}
      onAddTransaction={() => navigation.navigate('Home', { openAddTransaction: true, nonce: Date.now() })}
      onAddGoal={() => navigation.navigate('More', { screen: 'Goals' })}
    />
  );
}

const styles = StyleSheet.create({
  fabWrap: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  fab: {
    width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center',
    marginTop: -20,
    // Both platforms' own shadow APIs, not a Platform.OS branch -- iOS reads the shadow* props,
    // Android reads elevation, each ignoring the property it doesn't use.
    shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.2, shadowRadius: 4,
    elevation: 4,
  },
});
```

`QuickActionSheetHost` bug note for the implementer: `QuickActionSheetHost` must be rendered *inside* `<Tab.Navigator>`'s tree to resolve `useNavigation` correctly against it — move it to be a child of one of the `<Tab.Screen>`-sibling area is not possible (React Navigation only renders `Tab.Screen` children as routes), so instead render it as a sibling *outside* `<Tab.Navigator>` but pass it `navigationRef`-free by using `useNavigation` from `@react-navigation/native`, which resolves to the nearest *mounted* navigator context — since `QuickActionSheetHost` is rendered inside the same `<View>` as `<Tab.Navigator>` but not inside it, `useNavigation()` there actually resolves to `AppTabs`' own parent (`AppStack`), not the tab navigator. **Fix:** render `QuickActionSheetHost` as an actual child *of* `<Tab.Navigator>` is not possible either (non-`Tab.Screen` children are invalid there). Use `navigationRef`-independent approach instead — pass `useNavigation<BottomTabNavigationProp<AppTabParamList>>()` from *within* one of the tab screens is also wrong (wrong screen). The correct fix: keep `QuickActionSheetHost` where it is (sibling `<View>`, outside `<Tab.Navigator>`) but call `useNavigation<NativeStackNavigationProp<AppStackParamList... )` — **no**: simplest correct fix, verified against React Navigation's own model, is that `useNavigation()` resolves to the *nearest ancestor* `NavigationContainer`/navigator's context via React context, and `Tab.Navigator` does NOT wrap non-`Tab.Screen` siblings in its own navigation context — only `Tab.Screen` children receive it. So `QuickActionSheetHost` as written above will NOT have access to the tab navigator's `navigate('Home', ...)` shorthand names correctly scoped. **Resolve this in Step 7 below before committing.**

- [ ] **Step 7: Fix the navigation-context bug and verify manually**

Replace the sibling-`QuickActionSheetHost` approach: use the shared `navigationRef` pattern `RootNavigator.tsx` already exports for exactly this "navigate from outside any specific screen's own context" problem (see `useEmailChangeDeepLink`/`useReferralDeepLink`/`usePushNotificationNavigation`, all of which call `navigationRef.navigate(...)` rather than `useNavigation()`). Since `AppTabs` doesn't have access to `RootNavigator`'s `navigationRef`, the simpler and locally-correct fix is: give `ImportFabButton`'s `onPress` handler access to navigation via `useNavigation` called *inside* `AppTabs` itself (which IS inside `NavigationContainer`'s context, just not inside `Tab.Navigator`'s own local context — but `useNavigation<BottomTabNavigationProp<AppTabParamList>>()` called anywhere inside `NavigationContainer` still resolves navigate-by-route-name correctly for the nearest navigator in the tree below it, which for `AppTabs`'s own render function IS the `Tab.Navigator` it's about to render, since React Navigation resolves `useNavigation` by nearest enclosing `NavigationContainer`, and there is exactly one `NavigationContainer` in this app, in `RootNavigator.tsx`).

Concretely: move the `useNavigation<BottomTabNavigationProp<AppTabParamList>>()` call and the sheet's callbacks directly into `AppTabs` (removing the separate `QuickActionSheetHost` function entirely), replacing Step 6's `QuickActionSheetHost` component and its use in `AppTabs`'s `return` with:

```tsx
export function AppTabs() {
  const c = useTheme();
  const navigation = useNavigation<BottomTabNavigationProp<AppTabParamList>>();
  const [sheetVisible, setSheetVisible] = useState(false);
  // ...existing registerHome/registerTransactions/registerImport/registerByTab unchanged...

  return (
    <View style={{ flex: 1 }}>
      <Tab.Navigator /* ...unchanged... */>
        {/* ...unchanged Tab.Screen entries... */}
      </Tab.Navigator>
      <QuickActionSheet
        visible={sheetVisible}
        onClose={() => setSheetVisible(false)}
        onImportStatement={() => navigation.navigate('Import')}
        onAddTransaction={() => navigation.navigate('Home', { openAddTransaction: true, nonce: Date.now() })}
        onAddGoal={() => navigation.navigate('More', { screen: 'Goals' })}
      />
    </View>
  );
}
```

Then verify by running the app: `cd mobile && npx expo start`, open it in the iOS simulator (or Android emulator), tap the floating "+" button, confirm all 3 rows navigate correctly and the sheet closes. This is a real navigation-tree question worth confirming by running the app, not just by the unit test below (which mocks `useNavigation` and can't catch a wrong-context resolution).

- [ ] **Step 8: Update `AppTabs.test.tsx`**

Read the existing `mobile/src/navigation/AppTabs.test.tsx` first to see how it currently renders `AppTabs` and what it currently asserts about the Import tab (it may assert tab bar icon/label text for "Import" that no longer applies now that slot renders a custom button). Add a test confirming the FAB opens the sheet and each row navigates:

```tsx
  it('opens the quick-action sheet from the floating + button and navigates on each row', () => {
    // ...render AppTabs per this file's existing render helper...
    fireEvent.press(screen.getByLabelText('Quick actions'));
    expect(screen.getByText('Import Statement')).toBeTruthy();

    fireEvent.press(screen.getByText('Add Transaction'));
    expect(screen.queryByText('Import Statement')).toBeNull(); // sheet closed
    // ...assert navigation.navigate was called with ('Home', { openAddTransaction: true, nonce: expect.any(Number) })
    // using whatever navigation-mock pattern this file's other tests already use.
  });
```

Fix any pre-existing assertion in this file that specifically checked the old plain Import tab's icon/label, updating it to check for the `accessibilityLabel="Quick actions"` button instead.

- [ ] **Step 9: Run the full navigation test suite**

Run: `cd mobile && npx jest src/navigation/AppTabs.test.tsx`
Expected: all tests PASS.

- [ ] **Step 10: Commit**

```bash
git add mobile/src/components/dashboard/QuickActionSheet.tsx mobile/src/components/dashboard/QuickActionSheet.test.tsx mobile/src/navigation/AppTabs.tsx mobile/src/navigation/AppTabs.test.tsx mobile/src/navigation/types.ts
git commit -m "feat(mobile): replace the Import tab with a floating + quick-actions button"
```

---

### Task 15: `DashboardScreen.tsx` integration

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx`
- Modify: `mobile/src/screens/DashboardScreen.test.tsx`

**Interfaces:**
- Consumes: every component from Tasks 4-14, `useToast` from Task 13.
- Produces: the assembled screen — nothing downstream depends on this.

- [ ] **Step 1: Reorder and wire `DashboardScreen.tsx`**

Apply these changes to `mobile/src/screens/DashboardScreen.tsx` (building on Task 2's already-applied `healthColor`/`healthBarColor`/`scoreLabel` import change):

1. Add imports:
```tsx
import { useEffect } from 'react';
import { useRoute, type RouteProp } from '@react-navigation/native';
import { AccountsCard } from '../components/dashboard/AccountsCard';
import { AIInsightCard } from '../components/dashboard/AIInsightCard';
import { CashFlowMiniCard } from '../components/dashboard/CashFlowMiniCard';
import { GoalsRow } from '../components/dashboard/GoalsRow';
import { HealthFactorsRow } from '../components/dashboard/HealthFactorsRow';
import { HealthHero } from '../components/dashboard/HealthHero';
import { MonthlySnapshotGrid, type KpiItem } from '../components/dashboard/MonthlySnapshotGrid';
import { useToast } from '../context/ToastContext';
import type { AppTabParamList } from '../navigation/types';
```
(`useState` is already imported from React at the top — merge `useEffect` into that same line rather than duplicating the import.)

2. Bind the previously-unused accounts query. Change:
```tsx
  const [summaryQ, , recentTxnsQ, goalsQ, insightsQ, settingsQ] = useQueries({
```
to:
```tsx
  const [summaryQ, accountsQ, recentTxnsQ, goalsQ, insightsQ, settingsQ] = useQueries({
```
Delete the now-stale comment above it explaining why the accounts query's result was unbound (the 3 paragraphs starting "The accounts query's result is intentionally unbound" and "The accounts query's data is never read anywhere on this screen" around lines 134-137 and 244-259) — replace with:
```tsx
  // Bound now (AccountsCard, below) -- previously fetched only to prewarm AccountsScreen's
  // cache, per the pre-redesign version of this comment.
```
Keep `accountsQ` OUT of `initialLoad`/`refreshing`'s query list (unchanged from today) — the existing reasoning (a pull gesture shouldn't wait on a fetch that resolves independently of the visible sections) still holds; only add it if a manual verification pass in Task 16 finds AccountsCard visibly stuck on stale data after a pull-to-refresh (it won't: `refresh()`'s `invalidateQueries(['accounts'])` call already exists and unconditionally refetches it, same as before).

3. Read the `Home` tab's route params and auto-open `AddTransactionSheet`. Add after the existing `const [addingTransaction, setAddingTransaction] = useState(false);` line:
```tsx
  const route = useRoute<RouteProp<AppTabParamList, 'Home'>>();
  const { showToast } = useToast();

  // Set by the bottom-nav floating "+" button's "Add Transaction" row (QuickActionSheet, via
  // AppTabs) -- opens the same sheet Quick Actions' own "Add Transaction" cell already opens.
  // `nonce` (not just the boolean) is read too so a second tap while already on this tab still
  // re-fires the effect -- react-navigation reuses the same params object reference otherwise
  // dropped by the dependency array below since openAddTransaction alone wouldn't change.
  useEffect(() => {
    if (route.params?.openAddTransaction) {
      setAddingTransaction(true);
      navigation.setParams({ openAddTransaction: undefined, nonce: undefined });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.params?.nonce]);
```

4. Wire the toast into the existing `AddTransactionSheet`'s `onSaved`:
```tsx
    {addingTransaction ? (
      <AddTransactionSheet
        onClose={() => setAddingTransaction(false)}
        onSaved={() => {
          setAddingTransaction(false);
          showToast('Transaction Added', 'Your new transaction is now in your ledger.');
        }}
      />
    ) : null}
```

5. Compute the 4-item Monthly Snapshot subset and the health-hero/opportunity props, right after the existing `const kpis = summary ? [...] : [];` block:
```tsx
  const balanceKpi = kpis.find((k) => k.label === 'Total Balance') ?? null;
  const snapshotKpis: KpiItem[] = kpis.filter((k) => k.label !== 'Total Balance');
```

6. Reassemble the JSX. The new top-to-bottom order (everything between the coverage/limited-history/nudge banners and the closing `</ScrollView>` is replaced):

```tsx
      {/* --- existing coverage banner, review nudge, limited-history banner: UNCHANGED, keep exactly as they are today --- */}

      {!isEmpty && summary ? (
        <>
          <HealthHero
            available={summary.healthScoreAvailable}
            healthScore={summary.healthScore ?? 0}
            healthLabel={summary.healthLabel ?? ''}
            healthScoreDeltaVsLastMonth={summary.healthScoreDeltaVsLastMonth}
            healthSparkline={summary.healthSparkline}
            healthScoreTransactionCount={summary.healthScoreTransactionCount}
            healthScoreMinTransactions={summary.healthScoreMinTransactions}
            onImportPress={() => navigation.navigate('Import')}
          />
          <HealthFactorsRow
            available={summary.healthScoreAvailable}
            breakdown={summary.healthBreakdown}
            breakdownDetail={summary.healthBreakdownDetail}
            topOpportunityFactor={summary.healthTopOpportunityFactor}
            topOpportunityPotentialGain={summary.healthTopOpportunityPotentialGain}
          />
        </>
      ) : null}

      <View style={styles.section}>
        {summary ? (
          <MonthlySnapshotGrid kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        ) : (
          <View style={styles.kpiGrid}>
            {[0, 1, 2, 3].map((i) => <SkeletonCard key={i} style={styles.kpiCard} lines={1} />)}
          </View>
        )}
      </View>

      {/* --- existing Quick Actions Card: UNCHANGED CONTENT, moved to this position --- */}

      <View style={styles.cardRow}>
        <View style={styles.cardRowItem}>
          <CashFlowMiniCard points={cashFlowSettling || cashFlowUnavailable ? [] : cashFlowPoints} deltaPct={summary?.netDeltaPct ?? null} />
        </View>
        <View style={styles.cardRowItem}>
          <AccountsCard
            accounts={accountsQ.data ?? []}
            totalBalance={balanceKpi?.value ?? 0}
            caption={balanceKpi?.caption ?? ''}
            onViewAll={() => navigation.navigate('More', { screen: 'Accounts' })}
          />
        </View>
      </View>

      <Card style={styles.section}>
        <SectionHeading title="Spending by Category" />
        {/* --- existing DonutChart branch: UNCHANGED --- */}
      </Card>

      {/* --- existing "Subscriptions & Recurring Payments" Card: UNCHANGED CONTENT, moved to this position (renamed heading to "Upcoming") --- */}

      <View style={styles.section}>
        <SectionHeading title="Goals" />
        <GoalsRow goals={goalsQ.data ?? []} />
      </View>

      <AIInsightCard
        factor={summary?.healthTopOpportunityFactor ?? null}
        potentialGain={summary?.healthTopOpportunityPotentialGain ?? null}
        onCreateGoal={() => navigation.navigate('More', { screen: 'Goals' })}
      />

      {/* --- every remaining existing card, UNCHANGED, in its current relative order:
             Categorization Confidence, Next Actions, Detected Issues, the full Cash Flow chart
             (with 3M/6M/12M range picker), Recent Transactions, Budget Progress, Insights
             (full sentence list, unchanged -- AIInsightCard above reads
             healthTopOpportunityFactor/PotentialGain, not `sentences`, so nothing here needs
             slicing) --- */}

      <ChecklistWidget />
    </ScrollView>
```

Remove the old KPI grid block (lines 500-564 in the pre-Task-15 file), the old `Financial Health Score` `Card` block (lines 566-665), the old `Quick Actions` card's ORIGINAL position (moved above, not duplicated — delete it from its old spot near line 732), the old `Cash Flow` mini-adjacent-nothing (there wasn't one; the existing "Cash Flow" card with the range picker stays where it is, untouched), the old vertical `Goals` `Card` block (lines 994-1015, replaced by the `GoalsRow` above), and the old "Subscriptions & Recurring Payments" card's ORIGINAL position (moved above, delete from its old spot near line 1022). The old `ChecklistWidget` call at the very top (right after the greeting row) is deleted — it now renders once, at the very end.

Add `styles.cardRow`/`styles.cardRowItem` (side-by-side layout for Cash Flow Mini + Accounts):
```ts
  cardRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
  cardRowItem: { flex: 1 },
```

- [ ] **Step 2: Run the full Dashboard test file and fix every failure with real evidence, not guesses**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx`

For each failure: read the actual rendered output the test framework reports, not just the assertion that failed. Per this repo's own standing rule (root `CLAUDE.md`, "No guessing"), every fix here must be backed by reading what actually rendered — a hypothesis about why a query didn't match is not a fix.

Expected failure categories and how to resolve each (confirm by running, not by assuming this list is complete):
- Tests that pressed "Why?" / checked `health-score-value` / checked breakdown text (the `describe('Financial Health Score...')` block, lines 746-906): should keep passing unchanged — `HealthHero` and `HealthFactorsRow` together render the exact same text/testIDs the old single Card did. If any fail, it's a real regression in Task 5/6 — fix the component, not the test.
- Tests referencing the old vertical Goals card or old Quick Actions position: update to match the new structure/position, preserving what they actually verify (goal name/amount/percent text; Quick Actions' own navigation behavior).
- Tests referencing `kpi-Total Balance` specifically: `MonthlySnapshotGrid` no longer renders it (moved to `AccountsCard`) — `AccountsCard`'s own test (Task 9) already covers the balance figure; if `DashboardScreen.test.tsx` has an assertion specifically pinning `kpi-Total Balance`'s presence/value, move that assertion to check `AccountsCard`'s rendered balance text instead (same underlying `summary.currentBalance` value, same `caption` logic — nothing about the VALUE or its "As of today"/"As of {month}" logic changed, only which component renders it).
- The `describe('adjacent-screen prefetching', ...)` test: unaffected — `usePrefetchAdjacentScreens` isn't touched by this task.
- The `describe('pull-to-refresh indicator', ...)` tests, specifically "does not wait on accounts, since accounts data is never rendered on this screen": this test's own premise just changed — accounts data IS now rendered (`AccountsCard`). Read the test, decide whether its assertion ("stays visible until Goals, Insights, and the Cash Flow report queries have finished too" — the OTHER test in this block) already covers the right invariant, and update/rename the first test to reflect that accounts data is now rendered but its query is still deliberately excluded from the refreshing-indicator's wait list (same reasoning as before: a pull gesture shouldn't wait on a fetch whose completion isn't otherwise gated by anything the user is staring at) — assert the NEW premise explicitly rather than deleting the coverage.

- [ ] **Step 3: Run the full mobile suite**

Run: `cd mobile && npx jest`
Expected: PASS. Any failure outside `DashboardScreen.test.tsx`/`AppTabs.test.tsx`/`ChecklistWidget.test.tsx` means something leaked scope — investigate before assuming it's unrelated flake.

- [ ] **Step 4: Typecheck and lint**

Run: `cd mobile && npx tsc --noEmit && npx eslint src --max-warnings 0`
Expected: clean. Fix anything real; don't suppress with `eslint-disable` unless the existing codebase already does that for the identical situation (e.g. the `react-hooks/exhaustive-deps` disable pattern already used elsewhere in this same file, per Step 1.3 above).

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx
git commit -m "feat(mobile): assemble the redesigned Dashboard from the new hero/snapshot/accounts/cash-flow/goals/insight components"
```

---

### Task 16: Cross-platform verification and manual pass

**Files:** none (verification only).

- [ ] **Step 1: Confirm no platform branching was introduced**

Run: `cd mobile && grep -rn "Platform.OS" src/components/dashboard src/components/Toast.tsx src/context/ToastContext.tsx src/navigation/AppTabs.tsx src/onboarding/ChecklistWidget.tsx src/lib/health.ts src/lib/dashboardMetrics.ts`
Expected: no matches. If any exist, remove them — every visual/behavioral difference this plan introduces must come from data, not platform.

- [ ] **Step 2: Full verification loop**

Run in order, fixing anything real before moving to the next: `cd mobile && npx tsc --noEmit`, `npx eslint src --max-warnings 0`, `npx jest`.

- [ ] **Step 3: Visual parity check — iOS**

Boot an iOS simulator, run `npx expo start` and open the app on it. Navigate to Home. Confirm: hero gauge renders with the correct 3-band track and progress arc, delta pill shows/hides correctly, sparkline draws (need a real or seeded account with ≥2 months of health snapshots — if none is available in this environment, confirm at minimum that the "Getting Started"/empty-state branch renders correctly and the "Continue Setup" button navigates to Import), Monthly Snapshot 2×2 renders, Accounts card shows bank-colored avatars, Cash Flow Mini + full Cash Flow (with range picker) both render without duplicated/missing data, Goals row scrolls horizontally, AI Insight card appears only when a real opportunity exists, floating "+" button opens the sheet and each row navigates correctly, Getting Started is collapsed by default at the bottom and expands/collapses on tap, dark mode (toggle in Settings) renders every new component with correct tokens (no hardcoded light-only colors).

- [ ] **Step 4: Visual parity check — Android**

Repeat Step 3 on an Android emulator. Every item must look and behave the same as iOS (font rendering differences aside) — this is the whole point of the "cross-platform parity is mandatory" constraint. Note any visual divergence found and fix it (most likely cause: a shadow/elevation-only style difference, or a font-weight the two OSes render differently — neither should be "fixed" with a `Platform.OS` branch; prefer a style that both platforms already render acceptably, matching how the rest of this app's existing shadow styles are written).

- [ ] **Step 5: Screenshot both and report**

Take a screenshot of the Home tab on both simulators/emulators (light and dark mode each, 4 screenshots total) as the completion evidence for this task, per this repo's "Mandatory post-implementation verification" rule — a passing test suite alone is not sufficient proof for a UI redesign.

No commit for this task — it's verification only. If Steps 3/4 find a real bug, fix it in the relevant earlier task's files and re-run this task's steps from Step 2.

---

## Self-Review Notes

**Spec coverage:** Header (unchanged) → Health Hero (Task 5) → Health Factors (Task 6, user's round-2 request) → Monthly Snapshot (Task 7) → Quick Actions (moved, Task 15) → Accounts (Task 9) → Cash Flow Mini (Task 8) → Spending (unchanged donut) → Upcoming/Recurring (moved, Task 15) → Goals (Task 10) → AI Insight (Task 11) → every preserved existing card (unchanged) → Getting Started (Task 12) → bottom-nav FAB (Task 14) → Toast (Task 13). Every mockup section and every round-2 feedback point has a task. Cross-platform parity is a Global Constraint plus its own verification task (16).

**Placeholder scan:** No task's code block is a sketch — every component, helper and test above is complete, runnable code, not "add appropriate styling" prose. Task 14 is the one exception worth flagging explicitly: its Step 6 deliberately documents a real navigation-context bug reasoned through in place (not a placeholder — a worked-through design mistake caught during planning) and Step 7 gives the actual corrected code, because leaving the mistake unfixed in Step 6 with no correction would be worse than showing the reasoning that found it.

**Type consistency:** `KpiItem` (Task 7) matches the shape `DashboardScreen.tsx`'s existing `kpis` array already produces (label/value/delta/invert/caption/isPercent) — verified against the current file, not assumed. `HealthScorePoint`, `healthScoreDeltaVsLastMonth`, `healthTopOpportunityFactor`, `healthTopOpportunityPotentialGain` (Task 1) match the backend DTO's real field names and the web `DashboardSummary` type's real field names exactly (verified by reading both files, not guessed). `AppTabParamList['Home']`'s new param shape (Task 14) is read by the exact same key names in Task 15's `DashboardScreen.tsx` changes.

**Blast radius check:** `lib/health.ts`'s functions (Task 2) are consumed by `DashboardScreen.tsx`, `HealthHero`, `HealthFactorsRow` — no other screen currently imports the old local `healthColor`/`healthBarColor`/`scoreLabel` (verified: they were locally defined, unexported, in `DashboardScreen.tsx` only). `AppTabs.tsx`'s FAB change (Task 14) affects every screen inside the tab bar's visual chrome but not their own logic — `ImportScreen` itself is unchanged, only how its tab is reached. `ChecklistWidget` (Task 12) has exactly one call site (`DashboardScreen.tsx`) — verified by grep before assuming the accordion change is safe to make unconditionally.
