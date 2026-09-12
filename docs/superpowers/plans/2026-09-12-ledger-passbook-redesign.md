# Ledger "Passbook" Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the passbook redesign's narrative framing from Dashboard into the mobile
Transactions tab (`LedgerScreen.tsx`): a "This Month" income/expense summary at the top, rows
grouped by day (Today / Yesterday / dated) with a per-day subtotal, and real merchant logos —
without touching any existing filter, search, pagination, or row action.

**Architecture:** Three independent, additive pieces, done in this order because 2 and 3 both
touch `LedgerScreen.tsx`'s render and are easiest to review separately:

1. A new standalone `mobile/src/components/MerchantLogo.tsx` — a straight port of
   `frontend/src/components/MerchantLogo.tsx`'s Logo.dev `name/`-lookup + colored-initials
   fallback chain, swapping `<img>` for React Native's `Image`. No dependency on `LedgerScreen`
   or on the separate, still-unstarted `BankLogo` cross-app initiative (see Global Constraints).
2. A "This Month" summary card at the top of `LedgerScreen`, reusing the **existing**
   `LedgerSnapshotCard` component and `dashboardApi.summary()` query **under Dashboard's own
   `['dashboard-summary']` cache key** — so visiting both tabs in one session costs one network
   call, not two. The `kpis`/`deltaLabel` construction currently lives inline in
   `DashboardScreen.tsx`; this plan extracts it into a shared hook so `LedgerScreen` doesn't
   duplicate ~20 lines of logic.
3. Date-grouped rows: `LedgerScreen`'s already-flattened `txns` array (all fetched pages merged)
   gets grouped into `{type: 'header', ...} | {type: 'row', ...}` items via `useMemo`, rendered by
   the **same** `FlatList` with a two-branch `renderItem`. No `SectionList` migration — grouping
   runs on the client-merged array, so it's correct regardless of where a day falls across
   `page`=20-sized server pages.

**Tech Stack:** React Native / Expo SDK 57, TypeScript, TanStack Query, existing `theme.ts`
tokens, RN's built-in `Image` (no new dependency). No backend changes.

## Global Constraints

- No new backend endpoint or DTO field. Every number this plan renders (`monthlyIncome`,
  `monthlyExpense`, `incomeDeltaPct`, `expenseDeltaPct`) already exists on `DashboardSummary`
  and is already fetched by `dashboardApi.summary()` — confirmed by reading
  `DashboardScreen.tsx:362-378` directly, not assumed.
- Do not touch `mobile/src/navigation/AppTabs.tsx` or any bottom-tab structure. Goals keeps its
  own tab (PR #1306). Explicitly descoped in the conversation that produced this plan.
- Do not modify `mobile/src/components/dashboard/LedgerSnapshotCard.tsx`'s existing behavior —
  Dashboard already renders it; Task 2 below reuses it as-is via props, not a fork.
- `useLargeFontScale`'s `numberOfLines={largeText ? 2 : 1}` pattern (already applied to `.desc` in
  `LedgerScreen.tsx:602`) carries over to every new piece of financial-identity text this plan
  adds (day-group headers showing a merchant-adjacent label are not affected; there are none).
- Debit/credit color convention (`c.success`/`c.danger` + sign prefix) is already correct on this
  screen's rows and in `LedgerSnapshotCard`'s delta arrows — don't change either.
- **`BankLogo` is a separate, already-scoped, still-unstarted cross-app initiative**
  (`docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md:134-143`) spanning
  ~10 screens. This plan's `MerchantLogo` is independent of it (different Logo.dev identifier
  type — `name/` vs `domain/`, different source field — `Transaction.merchant` vs `Account.bank`)
  and does not block on it or fold into it.
- **Logo.dev attribution is a pre-existing, still-open compliance gap**, not something this plan
  introduces or resolves. `BankLogo.tsx:118-122` already flags it: Logo.dev's free tier requires a
  visible attribution link for commercial use, and Finora doesn't have one anywhere yet. Task 1
  extends the same tracked env-var documentation (`deployment-guide.md`'s table,
  `.env.example`'s comment) to mobile rather than silently duplicating an undocumented gap —
  actually *resolving* the attribution link is out of scope for this plan and should be its own
  small task, tracked separately (flag to Sid, don't build it here).

## Open Decisions

Both are about presentation, not mechanism — everything below is buildable either way, so start
with Task 1 (blocked by neither) while these get resolved.

1. **Summary card scope** (affects Task 2 only): show all 3 of Dashboard's KPIs (Income,
   Expenses, Net Savings — reuse `LedgerSnapshotCard` completely unchanged, most consistent with
   Dashboard) or filter to just Income/Expenses (matches the reference mockup image exactly, needs
   a `kpis.filter(...)` one-liner and nothing else). **Recommendation: all 3**, for one consistent
   "This Month" card language app-wide — the mockup showing 2 wasn't drawn against this app's real
   KPI set.
2. **Visual depth** (affects Task 2 and Task 3's styling only, not their logic): light-touch (new
   elements adopt the existing `DashboardCard`-style hairline border + soft shadow, nothing else
   on the screen changes) vs a fuller passbook re-skin of the whole Ledger screen (graphite/cream,
   brass accents, matching Dashboard's redesigned look). **Recommendation: light-touch** — the
   reference mockup itself isn't in the passbook palette either, and a fuller re-skin is a bigger,
   separate call Sid should make deliberately, not inherit as a side effect of this plan.

---

### Task 1: `MerchantLogo` — mobile port

**Files:**
- Create: `mobile/src/components/MerchantLogo.tsx`
- Create: `mobile/src/components/MerchantLogo.test.tsx`
- Modify: `mobile/.env.example` (add `EXPO_PUBLIC_LOGODEV_TOKEN`)
- Modify: `docs/operations/deployment/deployment-guide.md` (add mobile's row to the env var table)

**Interfaces:**
- Produces: `MerchantLogo({ merchant: string; size?: number; fallback?: ReactNode; style?: StyleProp<ViewStyle> })` — a React Native component. `size` defaults to 32 (matches web). No `className` prop (RN has none); `style` replaces it, applied to the `Image`/fallback `View`.

- [ ] **Step 1: Write the component**

Direct port of `frontend/src/components/MerchantLogo.tsx`, translating web/DOM primitives to RN
ones 1:1 — same Logo.dev URL builder, same stage machine, same "no circuit breaker" reasoning
(copy that comment verbatim, it's still true here), same deterministic initials/color fallback.

```tsx
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Image, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

interface MerchantLogoProps {
  merchant: string;
  size?: number;
  /** Custom content to show when Logo.dev has no logo for this merchant (or is unconfigured).
   *  Rendered as-is, with no wrapper of its own -- the caller owns its container. Omit to get
   *  this component's own self-contained colored-initials badge instead. */
  fallback?: ReactNode;
  style?: StyleProp<ViewStyle>;
}

const LOGODEV_TOKEN = process.env.EXPO_PUBLIC_LOGODEV_TOKEN;
// Same budget as web's MerchantLogo/BankLogo -- see those components' own comments for why.
const LOGODEV_TIMEOUT_MS = 1500;

export function logoDevUrl(merchant: string, sizePx: number, token: string | undefined): string | null {
  const name = merchant?.trim();
  if (!token || !name) return null;
  // https://www.logo.dev/docs/logo-images/get -- `name/` is the explicit identifier type for a
  // bare company name (unlike a domain, which needs no prefix).
  return `https://img.logo.dev/name/${encodeURIComponent(name)}?token=${token}&size=${sizePx}&format=png&fallback=404`;
}

type Stage = 'logodev' | 'fallback';

function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '?';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

// Deterministic name -> color, so the same merchant always gets the same badge color across rows
// rather than a new random one on every render.
function colorFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0;
  return `hsl(${Math.abs(hash) % 360}, 55%, 40%)`;
}

/**
 * Merchant-name logo resolution: Logo.dev (looked up by `Transaction.merchant`, a free-text name
 * with no domain field anywhere on the transaction) -> a colored-initials badge, or a
 * caller-supplied fallback. Mobile port of frontend/src/components/MerchantLogo.tsx -- same
 * mechanism, same reasoning, RN's Image in place of <img>.
 *
 * Deliberately no circuit breaker, unlike a future mobile BankLogo. Most rows in a real ledger are
 * cash withdrawals, UPI/IMPS references, or small vendors Logo.dev's catalog was never going to
 * have -- a miss there is the ordinary, expected outcome for THAT merchant, not evidence the whole
 * integration is broken. Each row's Image loads independently and falls back on its own.
 */
export function MerchantLogo({ merchant, size = 32, fallback, style }: MerchantLogoProps) {
  const sizePx = Math.max(64, Math.round(size * 2));
  const src = logoDevUrl(merchant, sizePx, LOGODEV_TOKEN);

  const [stage, setStage] = useState<Stage>(() => (src ? 'logodev' : 'fallback'));
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reset whenever the merchant itself changes -- e.g. scrolling a transaction list, each row a
  // different merchant -- otherwise a row that previously fell back for one merchant would
  // incorrectly start there for the next.
  useEffect(() => {
    setStage(src ? 'logodev' : 'fallback');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [merchant]);

  useEffect(() => {
    if (stage !== 'logodev') return undefined;
    timeoutRef.current = setTimeout(() => setStage('fallback'), LOGODEV_TIMEOUT_MS);
    return () => { if (timeoutRef.current) clearTimeout(timeoutRef.current); };
  }, [stage, merchant]);

  function clearLogoTimeout() {
    if (timeoutRef.current) { clearTimeout(timeoutRef.current); timeoutRef.current = null; }
  }

  if (stage === 'logodev' && src) {
    return (
      <Image
        source={{ uri: src }}
        accessibilityLabel={merchant}
        style={[{ width: size, height: size, borderRadius: 12 }, style]}
        resizeMode="contain"
        onLoad={clearLogoTimeout}
        onError={() => { clearLogoTimeout(); setStage('fallback'); }}
      />
    );
  }

  if (fallback !== undefined) return <>{fallback}</>;

  return (
    <View
      style={[
        styles.fallback,
        { width: size, height: size, backgroundColor: colorFor(merchant || '?') },
        style,
      ]}
      accessibilityLabel={merchant}
    >
      <Text style={[styles.fallbackText, { fontSize: Math.max(9, size * 0.34) }]}>
        {initialsOf(merchant || '')}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  fallback: { borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  fallbackText: { fontWeight: '700', color: '#FFFFFF' },
});
```

- [ ] **Step 2: Write the tests**

Mirror `frontend/src/components/MerchantLogo.test.tsx`'s coverage: URL construction (name encoded,
token required), initials fallback for 0/1/2-word names, deterministic color (same name -> same
color across two renders), and that an `onError` on the `Image` flips to the fallback.

```tsx
import { render, screen, fireEvent } from '@testing-library/react-native';
import { MerchantLogo, logoDevUrl } from './MerchantLogo';

describe('logoDevUrl', () => {
  it('builds a name/ lookup URL with the merchant encoded', () => {
    expect(logoDevUrl('Swiggy', 64, 'tok')).toBe(
      'https://img.logo.dev/name/Swiggy?token=tok&size=64&format=png&fallback=404'
    );
  });

  it('returns null without a token', () => {
    expect(logoDevUrl('Swiggy', 64, undefined)).toBeNull();
  });

  it('returns null for a blank merchant', () => {
    expect(logoDevUrl('  ', 64, 'tok')).toBeNull();
  });
});

describe('MerchantLogo', () => {
  it('renders initials for a two-word merchant name when unconfigured (no token)', () => {
    render(<MerchantLogo merchant="Big Basket" />);
    expect(screen.getByText('BB')).toBeTruthy();
  });

  it('renders the first two letters for a one-word merchant name', () => {
    render(<MerchantLogo merchant="Swiggy" />);
    expect(screen.getByText('SW')).toBeTruthy();
  });

  it('renders a caller-supplied fallback instead of initials when given one', () => {
    render(<MerchantLogo merchant="Swiggy" fallback={<>Custom</>} />);
    expect(screen.getByText('Custom')).toBeTruthy();
    expect(screen.queryByText('SW')).toBeNull();
  });
});
```

- [ ] **Step 3: Run the new tests**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/components/MerchantLogo.test.tsx`
Expected: all pass. (Logo.dev-image-load tests are skipped here, same as web's own file, since
jsdom/RN test renderer doesn't fire real image network events — the URL-builder tests above cover
the only pure logic worth unit-testing.)

- [ ] **Step 4: Document the env var**

Add to `mobile/.env.example`, matching the existing comment style (see
`EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID`'s block just above it for tone/length):

```
# Merchant-logo lookups in the Transactions list (src/components/MerchantLogo.tsx) via Logo.dev.
# Optional -- unset just skips straight to the colored-initials fallback, same "unconfigured is a
# supported state" posture as everywhere else in this app. Free-tier commercial use requires a
# visible attribution link back to Logo.dev, which Finora does not have anywhere yet (same
# still-open gap as frontend's identical VITE_LOGODEV_TOKEN -- see deployment-guide.md).
EXPO_PUBLIC_LOGODEV_TOKEN=
```

Add a row to `docs/operations/deployment/deployment-guide.md`'s env var table, next to the
existing `frontend/` `VITE_LOGODEV_TOKEN` row:

```
| `mobile/` | `EXPO_PUBLIC_LOGODEV_TOKEN` | No | Merchant-logo lookups (MerchantLogo.tsx) via Logo.dev; unset just skips that step. Same free-tier attribution requirement as frontend's VITE_LOGODEV_TOKEN -- still unresolved on both platforms. |
```

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/MerchantLogo.tsx mobile/src/components/MerchantLogo.test.tsx mobile/.env.example docs/operations/deployment/deployment-guide.md
git commit -m "feat(mobile): add MerchantLogo, ported from web"
```

---

### Task 2: Shared KPI hook + "This Month" summary on Ledger

**Files:**
- Create: `mobile/src/lib/useDashboardKpis.ts`
- Create: `mobile/src/lib/useDashboardKpis.test.ts`
- Modify: `mobile/src/screens/DashboardScreen.tsx:345-390` (replace the inline `kpis`/`deltaLabel`/`deltaSpokenLabel`/`periodLabel` block with a call to the new hook)
- Modify: `mobile/src/screens/LedgerScreen.tsx` (add the summary card)
- Modify: `mobile/src/screens/DashboardScreen.test.tsx` (re-run only — the hook extraction must not change any existing assertion)
- Modify: `mobile/src/screens/LedgerScreen.test.tsx` (add summary-card coverage)

**Interfaces:**
- Consumes: `dashboardApi.summary()` — already exists (`mobile/src/api/endpoints.ts`), returns `DashboardSummary` with `monthlyIncome`, `monthlyExpense`, `incomeDeltaPct`, `expenseDeltaPct`, `netCashFlow`, `netDeltaPct`, `currentBalance`, `savingsRatePct`, `reportingMonth`, `reportingMonthIsCurrent` (all already read by `DashboardScreen.tsx`).
- Produces: `useDashboardKpis(summary: DashboardSummary | undefined): { kpis: KpiItem[]; snapshotKpis: KpiItem[]; balanceKpi: KpiItem | null; periodLabel: string; deltaLabel: string; deltaSpokenLabel: string }` — `KpiItem` is the existing type exported from `mobile/src/components/dashboard/LedgerSnapshotCard.tsx`.

- [ ] **Step 1: Extract the hook**

Move `DashboardScreen.tsx:345-390`'s block (the `periodIsCurrent`/`periodLabel`/`deltaLabel`/
`deltaSpokenLabel`/`kpis`/`balanceKpi`/`snapshotKpis` computation) into
`mobile/src/lib/useDashboardKpis.ts` verbatim — this is a pure extraction, no logic change:

```ts
import { useMemo } from 'react';
import type { KpiItem } from '../components/dashboard/LedgerSnapshotCard';
import { monthLabel } from './format';
import type { DashboardSummary } from '../types';

export function useDashboardKpis(summary: DashboardSummary | undefined) {
  return useMemo(() => {
    const periodIsCurrent = summary ? (summary.reportingMonthIsCurrent || !summary.reportingMonth) : true;
    const periodLabel = periodIsCurrent ? 'this month' : monthLabel(summary!.reportingMonth!);
    const deltaLabel = periodIsCurrent
      ? 'vs last month'
      : `vs the month before ${monthLabel(summary!.reportingMonth!)}`;
    const deltaSpokenLabel = periodIsCurrent
      ? 'versus last month'
      : `versus the month before ${monthLabel(summary!.reportingMonth!)}`;

    const kpis: KpiItem[] = summary
      ? [
          {
            label: 'Total Balance', value: summary.currentBalance, delta: null as number | null, invert: false,
            caption: periodIsCurrent ? 'As of today' : `As of ${monthLabel(summary.reportingMonth!)}`,
            isPercent: false,
          },
          { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false, caption: null as string | null, isPercent: false },
          { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true, caption: null as string | null, isPercent: false },
          { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false, caption: null as string | null, isPercent: false },
          { label: 'Savings Rate', value: summary.savingsRatePct, delta: null as number | null, invert: false, caption: 'Share of income kept', isPercent: true },
        ]
      : [];

    const balanceKpi = kpis.find((k) => k.label === 'Total Balance') ?? null;
    const snapshotKpis = kpis.filter((k) => k.label !== 'Total Balance');

    return { kpis, balanceKpi, snapshotKpis, periodLabel, deltaLabel, deltaSpokenLabel };
  }, [summary]);
}
```

`DashboardSummary` is declared and exported from `mobile/src/types/index.ts:215` (confirmed by
reading it directly) — `endpoints.ts` only imports and re-uses it for `dashboardApi.summary()`'s
return type, so the hook's own import comes from `../types`, not `../api/endpoints`.

- [ ] **Step 2: Wire the hook into DashboardScreen, delete the inline block**

Replace the block this hook now owns with:

```ts
const { snapshotKpis, balanceKpi, periodLabel, deltaLabel, deltaSpokenLabel } = useDashboardKpis(summary);
```

Remove the old inline `kpis`/`balanceKpi`/`snapshotKpis`/`periodLabel`/`deltaLabel`/
`deltaSpokenLabel`/`periodIsCurrent` declarations. Leave every other line of
`DashboardScreen.tsx` untouched.

- [ ] **Step 3: Run DashboardScreen's full test file — must be unchanged**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/DashboardScreen.test.tsx`
Expected: same pass count as before this task (74/74, per the last full run of this file this
session — re-confirm the exact number by running it once before Step 1 if starting fresh, so a
regression here is unambiguous). This is a pure refactor; any assertion that changes means the
extraction introduced a behavior difference, which must be found and fixed before continuing.

- [ ] **Step 4: Write the hook's own unit tests**

```ts
import { renderHook } from '@testing-library/react-native';
import { useDashboardKpis } from './useDashboardKpis';

const BASE_SUMMARY = {
  currentBalance: 50000, monthlyIncome: 145000, monthlyExpense: 18672,
  incomeDeltaPct: 12, expenseDeltaPct: -8, netCashFlow: 126328, netDeltaPct: 15,
  savingsRatePct: 87, reportingMonth: '2026-09', reportingMonthIsCurrent: true,
} as const;

describe('useDashboardKpis', () => {
  it('returns an empty kpis array when summary is undefined', () => {
    const { result } = renderHook(() => useDashboardKpis(undefined));
    expect(result.current.kpis).toEqual([]);
  });

  it('separates Total Balance from the other four KPIs into balanceKpi/snapshotKpis', () => {
    const { result } = renderHook(() => useDashboardKpis(BASE_SUMMARY as any));
    expect(result.current.balanceKpi?.label).toBe('Total Balance');
    expect(result.current.snapshotKpis.map((k) => k.label)).toEqual([
      'Income', 'Expenses', 'Net Savings', 'Savings Rate',
    ]);
  });

  it('labels the delta "vs last month" when the reporting month is current', () => {
    const { result } = renderHook(() => useDashboardKpis(BASE_SUMMARY as any));
    expect(result.current.deltaLabel).toBe('vs last month');
  });

  it('names the actual month when the reporting month is not current', () => {
    const { result } = renderHook(() =>
      useDashboardKpis({ ...BASE_SUMMARY, reportingMonthIsCurrent: false, reportingMonth: '2026-07' } as any)
    );
    expect(result.current.deltaLabel).toBe('vs the month before July 2026');
  });
});
```

Check `monthLabel`'s real output format in `mobile/src/lib/format.ts` before asserting the exact
string above — adjust the expected text to match what that function actually returns for
`'2026-07'`, rather than assuming "July 2026".

- [ ] **Step 5: Run the hook tests**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/lib/useDashboardKpis.test.ts`
Expected: all pass.

- [ ] **Step 6: Add the summary card to LedgerScreen**

In `LedgerScreen.tsx`, add the summary query and hook call alongside the existing `categories`
query near the top of the component body:

Add `dashboardApi` to the existing `import { categoriesApi, onboardingApi, transactionsApi, ... }
from '../api/endpoints';` line at the top of the file (a second, separate import from the same
module is a duplicate-import lint error — extend the existing one, don't add a new line):

```ts
import {
  categoriesApi, dashboardApi, onboardingApi, transactionsApi, type PagedResponse, type TransactionFilters,
} from '../api/endpoints';
import { LedgerSnapshotCard } from '../components/dashboard/LedgerSnapshotCard';
import { useDashboardKpis } from '../lib/useDashboardKpis';
// ...
const { data: summary } = useQuery({ queryKey: ['dashboard-summary'], queryFn: () => dashboardApi.summary() });
const { snapshotKpis, deltaLabel, deltaSpokenLabel } = useDashboardKpis(summary);
```

Render it right below the header row, above the search field:

```tsx
{summary ? (
  <View style={styles.summaryWrap}>
    <LedgerSnapshotCard kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
  </View>
) : null}
```

Add `summaryWrap: { paddingHorizontal: spacing.md, paddingTop: spacing.sm }` to `styles`. No
loading skeleton for this card specifically — the screen's existing full-screen skeleton
(`isLoading` branch) already covers first paint; the summary card simply appears once `summary`
resolves, same "renders nothing while absent" pattern `LedgerSnapshotCard`'s own Dashboard caller
uses during its own loading state.

**If Open Decision 1 resolves to "Income/Expenses only" instead of all 5:** change
`snapshotKpis` at the call site to `snapshotKpis.filter((k) => k.label === 'Income' || k.label === 'Expenses')` — everything else in this step is unaffected.

- [ ] **Step 7: Write LedgerScreen's new test coverage**

Add a `describe('LedgerScreen "This Month" summary')` block to `LedgerScreen.test.tsx`, following
this file's own existing `renderScreen()`/mock-setup conventions (read the top of the file for the
exact `jest.mock('../api/endpoints', ...)` shape before writing this, since the mock object needs
a `dashboardApi.summary` entry added to it):

```tsx
it('shows Income and Expenses from the shared dashboard-summary query', async () => {
  dashboard.summary.mockResolvedValue({
    monthlyIncome: 145000, monthlyExpense: 18672, incomeDeltaPct: 12, expenseDeltaPct: -8,
    netCashFlow: 126328, netDeltaPct: 15, savingsRatePct: 87, currentBalance: 50000,
    reportingMonth: '2026-09', reportingMonthIsCurrent: true,
  } as any);
  renderScreen();

  expect(await screen.findByText('This Month')).toBeTruthy();
  expect(screen.getByTestId('kpi-Income')).toBeTruthy();
  expect(screen.getByTestId('kpi-Expenses')).toBeTruthy();
});

it('renders nothing extra while the summary is still loading', async () => {
  dashboard.summary.mockReturnValue(new Promise(() => {})); // never resolves
  renderScreen();
  expect(screen.queryByText('This Month')).toBeNull();
});
```

- [ ] **Step 8: Run LedgerScreen's full test file**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/LedgerScreen.test.tsx`
Expected: all pass, including the new tests and every pre-existing one (69 pre-existing per this
session's last run of this file, plus the new ones added here).

- [ ] **Step 9: Commit**

```bash
git add mobile/src/lib/useDashboardKpis.ts mobile/src/lib/useDashboardKpis.test.ts mobile/src/screens/DashboardScreen.tsx mobile/src/screens/LedgerScreen.tsx mobile/src/screens/LedgerScreen.test.tsx
git commit -m "feat(mobile): add This Month summary card to the Transactions tab"
```

---

### Task 3: Date-grouped rows with per-day subtotal

**Files:**
- Modify: `mobile/src/screens/LedgerScreen.tsx`
- Modify: `mobile/src/screens/LedgerScreen.test.tsx`

**Interfaces:**
- Consumes: `txns: Transaction[]` — the existing flattened, already-filtered array (`LedgerScreen.tsx:223`), and `t.date`, a `'YYYY-MM-DD'` string per `Transaction`'s existing shape (confirm the exact format the backend sends by checking one real `t.date` usage already in this file — e.g. the row's own `{t.date}` render at line 613 — rather than assuming).
- Produces: a `GroupedRow = { kind: 'header'; date: string; label: string; subtotal: number } | { kind: 'row'; transaction: Transaction }` union, consumed only inside this screen.

- [ ] **Step 1: Write the grouping function as a pure, separately-testable unit**

Add near the top of `LedgerScreen.tsx`, alongside the existing `statusBadges` helper:

```ts
type GroupedRow =
  | { kind: 'header'; date: string; label: string; subtotal: number }
  | { kind: 'row'; transaction: Transaction };

/**
 * Groups an already-sorted (date desc), already-merged list of transactions into day sections
 * with a per-day net subtotal (income minus expense for that day, signed the same way a single
 * row's own amount is -- positive shows in c.success, negative in c.danger, same convention as
 * every other signed figure in this app).
 *
 * Runs on the FULLY MERGED txns array (every fetched page flattened), not per-page -- grouping
 * a day that happens to straddle two 20-row server pages still produces one header for that day,
 * since by the time this runs both pages are already concatenated.
 *
 * "Today"/"Yesterday" are computed against the device's own clock at render time, matching how
 * every other relative-date label in this app already works (see fmtRelativeTime) -- not memoized
 * across a very long-lived mount, since a day-boundary crossing mid-session on an open Transactions
 * tab is exactly the same edge case fmtRelativeTime already tolerates.
 */
export function groupTransactionsByDay(txns: Transaction[]): GroupedRow[] {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1);
  const toKey = (d: Date) => d.toISOString().slice(0, 10);
  const todayKey = toKey(today);
  const yesterdayKey = toKey(yesterday);

  function labelFor(dateStr: string): string {
    if (dateStr === todayKey) return 'Today';
    if (dateStr === yesterdayKey) return 'Yesterday';
    // Matches this app's existing long-date convention (see fmtDate) -- e.g. "Monday, 8 Sep 2026".
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'short', year: 'numeric' });
  }

  const result: GroupedRow[] = [];
  let currentDate: string | null = null;
  let currentSubtotal = 0;
  let headerIndex = -1;

  for (const t of txns) {
    if (t.date !== currentDate) {
      currentDate = t.date;
      currentSubtotal = 0;
      headerIndex = result.length;
      result.push({ kind: 'header', date: t.date, label: labelFor(t.date), subtotal: 0 });
    }
    currentSubtotal += t.type === 'INCOME' ? t.amount : -Math.abs(t.amount);
    (result[headerIndex] as { kind: 'header'; subtotal: number }).subtotal = currentSubtotal;
    result.push({ kind: 'row', transaction: t });
  }
  return result;
}
```

Check `Transaction.date`'s real format (the field this loop groups on) against
`mobile/src/types.ts` or wherever `Transaction` is actually declared before finalizing — the
`toKey`/`labelFor` comparison only works if `t.date` is already a plain `'YYYY-MM-DD'` string with
no time component, which is very likely true (the row already renders `{t.date}` directly with no
formatting call at line 613) but must be confirmed by reading the type, not assumed from the
render call alone.

- [ ] **Step 2: Write the grouping function's unit tests**

```ts
import { groupTransactionsByDay } from './LedgerScreen';
import type { Transaction } from '../types';

function txn(over: Partial<Transaction>): Transaction {
  return {
    id: 't1', accountId: 'a1', categoryId: 'c1', categoryName: 'Shopping', date: '2026-09-01',
    description: 'Test', merchant: 'Test', paymentMethod: 'CARD', amount: 100, type: 'EXPENSE',
    tags: [], notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
    categoryManuallySet: false, ...over,
  } as Transaction;
}

describe('groupTransactionsByDay', () => {
  it('returns one header per distinct date, in the order the input already carries', () => {
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10' }),
      txn({ id: 't2', date: '2026-09-10' }),
      txn({ id: 't3', date: '2026-09-08' }),
    ]);
    const headers = rows.filter((r) => r.kind === 'header');
    expect(headers.map((h) => (h as any).date)).toEqual(['2026-09-10', '2026-09-08']);
  });

  it('sums a day\'s subtotal as income minus expense, signed', () => {
    const rows = groupTransactionsByDay([
      txn({ id: 't1', date: '2026-09-10', type: 'INCOME', amount: 1000 }),
      txn({ id: 't2', date: '2026-09-10', type: 'EXPENSE', amount: 300 }),
    ]);
    const header = rows.find((r) => r.kind === 'header') as any;
    expect(header.subtotal).toBe(700);
  });

  it('returns an empty array for an empty input', () => {
    expect(groupTransactionsByDay([])).toEqual([]);
  });
});
```

- [ ] **Step 3: Run the grouping tests**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/LedgerScreen.test.tsx -t "groupTransactionsByDay"`
Expected: all pass.

- [ ] **Step 4: Wire grouping into the FlatList**

Replace the existing `<FlatList data={txns} ...>` with `data={groupTransactionsByDay(txns)}` (wrap
in a `useMemo` keyed on `txns` — grouping is O(n) but there is no reason to redo it every
unrelated re-render), and give `renderItem` a `kind` branch at its very top:

```tsx
const groupedRows = useMemo(() => groupTransactionsByDay(txns), [txns]);
// ...
<FlatList
  testID="ledger-list"
  data={groupedRows}
  keyExtractor={(item) => (item.kind === 'header' ? `header-${item.date}` : item.transaction.id)}
  // ...unchanged props (initialNumToRender, windowSize, removeClippedSubviews, onEndReached, etc.)
  renderItem={({ item }) => {
    if (item.kind === 'header') {
      return (
        <View style={styles.dayHeader}>
          <Text style={[styles.dayHeaderLabel, { color: c.mutedInk }]}>{item.label}</Text>
          <Text style={[styles.dayHeaderSubtotal, { color: item.subtotal >= 0 ? c.success : c.danger }]}>
            {item.subtotal >= 0 ? '+' : '-'}{fmtCurrency(Math.abs(item.subtotal))}
          </Text>
        </View>
      );
    }
    const t = item.transaction;
    // ...rest of the existing renderItem body, completely unchanged, just reading `t` from
    // `item.transaction` instead of the old `{ item: t }` destructure.
  }}
/>
```

Add to `styles`:
```ts
dayHeader: {
  flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline',
  paddingTop: spacing.md, paddingBottom: spacing.xs,
},
dayHeaderLabel: { fontSize: 13, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.3 },
dayHeaderSubtotal: { fontSize: 12, fontWeight: '600' },
```

Every existing row body (accessibility actions, badges, edit/delete/explain/transfer buttons,
`onEndReached` pagination, skeleton/error/empty branches) is untouched — this step only wraps the
existing `renderItem` body in the `kind === 'row'` branch and adds the `kind === 'header'` branch
above it.

- [ ] **Step 5: Run LedgerScreen's full test file**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/LedgerScreen.test.tsx`
Expected: all pass. Pay particular attention to any existing test that indexes into the FlatList's
`data` by position or count (e.g. asserting a specific row is the Nth item) — those need updating
to account for interleaved header rows, since the list's `data` array is now longer than `txns`
itself. Search the file for `getAllByTestId` or similar before this step to know if any exist.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/LedgerScreen.tsx mobile/src/screens/LedgerScreen.test.tsx
git commit -m "feat(mobile): group Transactions rows by day with a per-day subtotal"
```

---

### Task 4: Merchant logo in each transaction row

**Files:**
- Modify: `mobile/src/screens/LedgerScreen.tsx`
- Modify: `mobile/src/screens/LedgerScreen.test.tsx`

**Interfaces:**
- Consumes: `MerchantLogo` from Task 1.

- [ ] **Step 1: Add the logo to the row layout**

In the `kind === 'row'` branch's JSX, insert a `MerchantLogo` before `styles.rowMain`:

```tsx
<View style={styles.logoWrap}>
  <MerchantLogo merchant={t.merchant || t.description || '?'} size={40} />
</View>
<View style={styles.rowMain}>
  {/* unchanged */}
</View>
```

Add to `styles`: `logoWrap: { marginRight: spacing.sm }`. Import `MerchantLogo` from
`../components/MerchantLogo` at the top of the file.

`t.merchant || t.description || '?'` matches this row's own existing fallback chain for its
description text (`t.description || t.merchant || 'Transaction'`, just merchant-first here since
the logo lookup is specifically about merchant identity — a transaction with only a free-text
description and no merchant renders `MerchantLogo`'s own `'?'`-initials fallback, which is the
correct, honest outcome for a row Logo.dev could never resolve anyway).

- [ ] **Step 2: Add row-rendering coverage**

Add to `LedgerScreen.test.tsx`:

```tsx
it('renders a MerchantLogo for each transaction row, keyed by merchant', async () => {
  transactions.search.mockResolvedValue({
    content: [{ ...baseTxn, id: 't1', merchant: 'Swiggy' }],
    page: 0, size: 20, totalElements: 1, totalPages: 1,
  } as any);
  renderScreen();
  expect(await screen.findByLabelText('Swiggy')).toBeTruthy();
});
```

Adjust `baseTxn`/mock shape to match whatever fixture helper this test file already uses (read the
file first — do not invent a new fixture shape if one already exists).

- [ ] **Step 3: Run LedgerScreen's full test file**

Run: `NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/LedgerScreen.test.tsx`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/screens/LedgerScreen.tsx mobile/src/screens/LedgerScreen.test.tsx
git commit -m "feat(mobile): show MerchantLogo on each Transactions row"
```

---

## Self-Review Checklist (run after Task 4, before opening a PR)

- [ ] `npx tsc --noEmit` clean for `mobile/`.
- [ ] `eslint` clean on every file touched across all 4 tasks.
- [ ] Full `DashboardScreen.test.tsx` and `LedgerScreen.test.tsx` runs, both 100% pass, with the
      exact before/after test counts noted in the PR description (per this repo's "never claim
      verified without the command that proves it" rule).
- [ ] Manually verify (live simulator, once tooling is available — see the "no local Android/
      Maestro setup" caveat noted elsewhere this session) that: the summary card appears above the
      search field and matches Dashboard's own numbers for the same account; day headers read
      "Today"/"Yesterday" correctly against the device clock, not just a hardcoded date; a
      merchant with no Logo.dev match falls back to initials, not a broken image icon; existing
      filters (type/status/date-range/drill-through/search) still narrow the grouped list
      correctly, including a filtered result whose grouping still shows the right subtotals for
      the SUBSET now shown, not the unfiltered total.
- [ ] Confirm `groupTransactionsByDay` is exported only for its own test file's `import` (not
      accidentally used anywhere else) and doesn't leak into `LedgerScreen`'s public surface
      unexpectedly.
- [ ] Re-open the two "Open Decisions" above explicitly in the PR description if either was
      resolved during implementation rather than before it started.
