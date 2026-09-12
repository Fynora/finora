# Insights Overview Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the mobile Insights screen into an "Overview" — a motivational banner, a
4-stat "This Month at a Glance" row, a restyled tappable "Key Insights" list, a "Spending by
Category" donut, and a compact Recurring Payments summary — without touching the 5-tab pill bar
or Insights' place in navigation (both explicitly out of scope).

**Architecture:** One backend change (two new structured fields on `InsightsDto`, both values the
backend already computes) followed by six additive mobile pieces, in dependency order: header +
shared query wiring first (everything else reads from it), then the five new/restyled visual
sections.

**Tech Stack:** React Native / Expo SDK 57, TypeScript, TanStack Query, `react-native-svg`
(existing dependency). Backend: Spring Boot record DTOs, no new endpoint.

**Spec:** `docs/superpowers/specs/2026-09-12-insights-overview-redesign-design.md`

## Global Constraints

- Do not touch the 5-tab pill bar or promote Insights to a primary bottom tab — separate,
  deliberate navigation decisions, explicitly deferred.
- Do not modify `Category Movers`' existing tap-to-filter behavior, `DonutChart`, or
  `bucketTopSlices` — reused as-is.
- The existing full "Recurring Payments & Subscriptions" list (with its dismiss mutation) stays on
  this screen, unchanged, below the new compact summary card.
- Every new backend field must round-trip through the real OpenAPI regen pipeline
  (`backend/scripts/generate-openapi-spec.sh` + `npm run generate:types` in `mobile/`, `frontend/`,
  `admin-portal/`) — CI's `OpenAPI contract drift` job blocks on a stale `openapi.json` or a stale
  `generated-types.ts`.
- `useLargeFontScale`'s `numberOfLines={largeText ? 2 : 1}` pattern (already applied to the
  Recurring row's title in this file) carries over to every new piece of text that can wrap.
- Spending-up-is-bad color convention (`c.success`/`c.danger`, inverted from Dashboard's income
  KPI) stays exactly as Category Movers already uses it — the new sections reuse it via
  `useDashboardKpis`'s existing `invert` flag, not a second convention.
- Two Ionicons glyph names used below (`settings-outline`, `trophy-outline`) are not yet used
  anywhere else in this app — verify each against the installed `@expo/vector-icons` Ionicons
  glyph map before shipping (same verification `lib/categoryIcons.ts`'s own doc comment
  describes), same as every name already in that file's `ICON_NAMES` map.

---

### Task 1: Backend — structured `biggestCategory`/`topMerchant` fields

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/InsightsDto.java`
- Modify: `backend/src/main/java/com/finora/service/InsightsService.java:161-163` (biggest category),
  `:227-231` (top merchant)
- Modify: `backend/src/test/java/com/finora/service/InsightsServiceTest.java`
- Modify: `backend/openapi/openapi.json` (regenerated, not hand-edited)
- Modify: `mobile/src/api/generated-types.ts`, `frontend/src/api/generated-types.ts`,
  `admin-portal/src/api/generated-types.ts` (regenerated, not hand-edited)

**Interfaces:**
- Produces: `InsightsDto.CategoryHighlight(String name, BigDecimal amount)` and
  `InsightsDto.MerchantHighlight(String name, BigDecimal amount)`, both nullable fields on
  `InsightsDto`. Serialize as `{ name: string, amount: number } | null` in the generated TS types.

- [ ] **Step 1: Add the two record types and fields to InsightsDto**

```java
// backend/src/main/java/com/finora/dto/InsightsDto.java
package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InsightsDto(
        List<String> sentences,
        List<CategoryMover> movers,
        CoverageCaveat coverageCaveat,
        CategoryHighlight biggestCategory,
        MerchantHighlight topMerchant
) {
    public record CategoryMover(String category, BigDecimal current, BigDecimal priorAverage, Double pctChange) {}

    // Structured twins of the "%s was your biggest category at ₹%,.0f." / "Your top merchant ...
    // was \"%s\" at ₹%,.0f." sentences InsightsService already builds -- same values, just not
    // only baked into prose, so the mobile Key Insights redesign can render a tappable, iconed row
    // instead of parsing a string. Null when the underlying Optional was empty (no data this
    // month), same "degrade to nothing" shape as every other optional field this DTO returns.
    public record CategoryHighlight(String name, BigDecimal amount) {}
    public record MerchantHighlight(String name, BigDecimal amount) {}

    public record CoverageCaveat(String month, List<GapWindow> gaps) {
        public record GapWindow(LocalDate gapStart, LocalDate gapEnd) {}
    }
}
```

- [ ] **Step 2: Populate both fields in InsightsService.build()**

Replace the two `.ifPresent(...)`-only blocks with versions that also capture the structured value.
The empty-pipeline early return (`build()`'s first branch, returning
`new InsightsDto(List.of(...), List.of(), coverageCaveatWithNoTransactions(userId))`) needs the two
new constructor args too — `null, null`, since there's no data to highlight.

```java
// Around line 102 (the empty-pipeline early return):
return new InsightsDto(List.of("Upload or add transactions to see spending insights."), List.of(),
        coverageCaveatWithNoTransactions(userId), null, null);
```

```java
// Around line 161-163, replacing the existing biggest-category block:
InsightsDto.CategoryHighlight biggestCategory = currentByCat.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(top -> new InsightsDto.CategoryHighlight(top.getKey(), top.getValue()))
        .orElse(null);
if (biggestCategory != null) {
    sentences.add(String.format(Locale.ENGLISH, "%s was your biggest category at ₹%,.0f.",
            biggestCategory.name(), biggestCategory.amount()));
}
```

```java
// Around line 227-231, replacing the existing top-merchant block:
InsightsDto.MerchantHighlight topMerchant = merchantTotals.entrySet().stream()
        .filter(e -> !UNKNOWN_MERCHANT.equals(e.getKey()))
        .max(Map.Entry.comparingByValue())
        .map(top -> new InsightsDto.MerchantHighlight(top.getKey(), top.getValue()))
        .orElse(null);
if (topMerchant != null) {
    sentences.add(String.format(Locale.ENGLISH, "Your top merchant %s was \"%s\" at ₹%,.0f.",
            periodLabel, topMerchant.name(), topMerchant.amount()));
}
```

```java
// The final return statement (was `return new InsightsDto(sentences, movers, coverageCaveat);`):
return new InsightsDto(sentences, movers, coverageCaveat, biggestCategory, topMerchant);
```

- [ ] **Step 3: Write the new backend tests**

Add to `InsightsServiceTest.java`, mirroring the existing `biggestCategorySentence_...`/
`topMerchantSentence_...` test style (same file, same `givenTransactions` helper):

```java
@Test
void biggestCategory_returnsTheStructuredHighlight() {
    givenTransactions(List.of(
            expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(600), dining, "Cafe"),
            expense(LocalDate.of(2026, 7, 10), BigDecimal.valueOf(400), groceries, "Mart")));

    var result = insightsService.build(userId);

    assertThat(result.biggestCategory()).isNotNull();
    assertThat(result.biggestCategory().name()).isEqualTo("Dining");
    assertThat(result.biggestCategory().amount()).isEqualByComparingTo(BigDecimal.valueOf(600));
}

@Test
void topMerchant_returnsTheStructuredHighlight() {
    givenTransactions(List.of(
            expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(300), dining, "Cafe A"),
            expense(LocalDate.of(2026, 7, 6), BigDecimal.valueOf(700), dining, "Cafe B")));

    var result = insightsService.build(userId);

    assertThat(result.topMerchant()).isNotNull();
    assertThat(result.topMerchant().name()).isEqualTo("Cafe B");
    assertThat(result.topMerchant().amount()).isEqualByComparingTo(BigDecimal.valueOf(700));
}

@Test
void bothHighlights_areNullWhenThereIsNoReportableActivity() {
    when(transactionRepository.findByUserIdAndAccountIdIn(any(), any())).thenReturn(List.of());

    var result = insightsService.build(userId);

    assertThat(result.biggestCategory()).isNull();
    assertThat(result.topMerchant()).isNull();
}
```

- [ ] **Step 4: Run the backend tests**

Run: `cd backend && mvn -Dtest=InsightsServiceTest test`
Expected: all pass, including the 3 new cases.

- [ ] **Step 5: Regenerate the OpenAPI spec and all three clients' generated types**

Requires a reachable Postgres first (`finora`/`finora`/`finora` on 5432 — see `docker-compose.yml`
if one isn't already running) and a built backend jar:

```bash
cd backend
mvn -DskipTests package
./scripts/generate-openapi-spec.sh
```

Expected: `Wrote openapi/openapi.json: N paths, M schemas.` printed, and `git diff --stat
openapi/openapi.json` shows the `InsightsDto` schema gained `biggestCategory`/`topMerchant`.

```bash
cd ../mobile && npm run generate:types
cd ../frontend && npm run generate:types
cd ../admin-portal && npm run generate:types
```

Expected: `git status --short` shows all four files (`openapi/openapi.json` and the three
`generated-types.ts`) modified, nothing else.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/dto/InsightsDto.java \
  backend/src/main/java/com/finora/service/InsightsService.java \
  backend/src/test/java/com/finora/service/InsightsServiceTest.java \
  backend/openapi/openapi.json \
  mobile/src/api/generated-types.ts frontend/src/api/generated-types.ts \
  admin-portal/src/api/generated-types.ts
git commit -m "feat(backend): expose structured biggestCategory/topMerchant on InsightsDto"
```

---

### Task 2: Mobile — custom header + shared query wiring

**Files:**
- Modify: `mobile/src/navigation/AppTabs.tsx` (`Insights` screen options)
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: `dashboardApi.summary()` (existing, `mobile/src/api/endpoints.ts:793` area — same
  `['dashboard-summary']` key `DashboardScreen.tsx`/`LedgerScreen.tsx` already use),
  `categoriesApi.list()` (existing, `['categories']` key `LedgerScreen.tsx` already uses),
  `useDashboardKpis` (existing, `mobile/src/lib/useDashboardKpis.ts`).
- Produces: nothing new for later tasks to import — `summary`, `categories`, and the
  `useDashboardKpis(summary)` result become local variables inside `InsightsScreen` that Tasks 3-5
  read directly.

- [ ] **Step 1: Hide the native header for Insights**

```tsx
// mobile/src/navigation/AppTabs.tsx -- find the existing line:
//   <MoreStack.Screen name="Insights" component={InsightsScreen} />
// and update its comment block (the one above Budgets/Subscription/Goals/Reports/Insights/...)
// to move Insights out of that "renders no title of its own" group, matching Referrals' own
// headerShown:false entry just below it:
<MoreStack.Screen name="Insights" component={InsightsScreen} options={{ headerShown: false }} />
```

- [ ] **Step 2: Add the new imports and queries to InsightsScreen**

```tsx
// mobile/src/screens/InsightsScreen.tsx -- extend the existing endpoints import:
import {
  categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi, type RecurringItem,
} from '../api/endpoints';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { MoreStackParamList } from '../navigation/types';

// Inside InsightsScreen(), alongside the existing insightsQ/recurringQ useQueries:
const insets = useSafeAreaInsets();
const stackNavigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();

// Under Dashboard's own ['dashboard-summary'] key -- see LedgerScreen.tsx's identical query and
// its own comment on why (one network call shared across every screen that visits it this
// session, not a fresh one per screen).
const { data: summary } = useQuery({
  queryKey: ['dashboard-summary'],
  queryFn: () => dashboardApi.summary(),
});
const { snapshotKpis } = useDashboardKpis(summary);

// Loaded lazily, same reasoning as LedgerScreen's identical query: cheap, shared cache, needed by
// Key Insights' category icons (Task 4).
const { data: categories = [] } = useQuery({
  queryKey: ['categories'],
  queryFn: () => categoriesApi.list(),
  staleTime: 5 * 60_000,
});
```

- [ ] **Step 3: Render the custom header**

Insert right after the `<ScrollView ...>` opening tag, before the existing static notice banner:

```tsx
<View style={[styles.header, { paddingTop: insets.top + spacing.sm }]}>
  <View style={styles.headerText}>
    <Text style={[styles.headerTitle, { color: c.ink }]}>Insights</Text>
    <Text style={[styles.headerSubtitle, { color: c.muted }]}>
      Understand your money. Make better decisions.
    </Text>
  </View>
  <Pressable
    onPress={() => stackNavigation.navigate('Settings')}
    hitSlop={10}
    accessibilityRole="button"
    accessibilityLabel="Settings"
  >
    <Ionicons name="settings-outline" size={22} color={c.ink} />
  </Pressable>
</View>
```

Add to `styles`. Deliberately NO `paddingHorizontal` of its own — the header sits inside the same
`ScrollView`'s `contentContainerStyle={styles.content}` as everything else in this file, and
`content`'s existing `padding: spacing.md` already gives it (and the static disclaimer banner
right below it, and every other top-level child) the standard horizontal inset. Giving the header
a second, separate `paddingHorizontal` here would double that inset for the header only, indenting
its title further than the cards below it — `content` itself needs no change:
```ts
header: {
  flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start',
  paddingBottom: spacing.md,
},
headerText: { flex: 1, marginRight: spacing.sm },
headerTitle: { fontSize: 22, fontWeight: '700' },
headerSubtitle: { fontSize: 13, marginTop: 2 },
```

`content`'s existing top padding (`padding: spacing.md` covers all four sides) would normally sit
above the header too, adding to `insets.top` from `paddingTop: insets.top + spacing.sm` on the
header `View` itself — accept the small extra gap, or subtract it explicitly
(`paddingTop: insets.top + spacing.sm - spacing.md`) if it visibly doubles up on a real device;
check on a live simulator before deciding either way rather than guessing which reads better.

- [ ] **Step 4: Update existing tests for the navigation-options change**

`InsightsScreen.test.tsx` doesn't render through `AppTabs.tsx`, so `headerShown: false` doesn't
affect any existing test directly. Add one new test confirming the gear navigates:

```tsx
it('opens Settings from the header gear', async () => {
  renderScreen();
  await screen.findByText(/not an\s+AI-generated assistant/);
  const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
  navigate.mockClear();

  fireEvent.press(screen.getByLabelText('Settings'));

  expect(navigate).toHaveBeenCalledWith('Settings');
});
```

Also extend the top-of-file `jest.mock('../api/endpoints', ...)` to add `categoriesApi` and
`dashboardApi`, and the `beforeEach` to default `dashboard.summary` to a never-resolving promise
(matching `LedgerScreen.test.tsx`'s own established pattern for this exact default — see that
file's comment on why not `mockResolvedValue(undefined)`):

```tsx
jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn() },
  recurringApi: { list: jest.fn(), dismiss: jest.fn() },
  categoriesApi: { list: jest.fn() },
  dashboardApi: { summary: jest.fn() },
  onboardingApi: {
    getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: jest.fn().mockResolvedValue(undefined),
  },
}));

const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;
const dashboard = dashboardApi as jest.Mocked<typeof dashboardApi>;

// In the existing beforeEach:
categories.list.mockReset().mockResolvedValue([]);
dashboard.summary.mockReset().mockReturnValue(new Promise(() => {}));
```

- [ ] **Step 5: Run the full test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx --forceExit`
Expected: all pass, including the new Settings-navigation test. Every pre-existing test must be
unaffected (this task only adds a header and two idle queries; no existing section changed).

- [ ] **Step 6: Commit**

```bash
git add mobile/src/navigation/AppTabs.tsx mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add custom header to Insights, wire dashboard-summary and categories"
```

---

### Task 3: Mobile — banner + This Month at a Glance

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: `summary` and `snapshotKpis` from Task 2.

- [ ] **Step 1: Write the banner illustration as a small standalone component**

A hand-drawn `react-native-svg` scene (layered hills + a sun), the same "no bitmap asset needed"
approach `DonutChart.tsx` already uses for its own chart. `mobile/assets/illustrations/` does
exist (one file, `refer-earn-hero.png`, used by `ReferralsScreen.tsx`) but that's a single
designer-provided PNG with no repeatable pipeline behind it — this SVG is the pragmatic
equivalent for a screen with no such asset supplied. If a real commissioned illustration file is
provided later, swapping it in is a small follow-up (bundle via `require()` and `<Image>`, same
pattern `ReferralsScreen.tsx` already uses), not a rework of this component's call site.

```tsx
// mobile/src/components/insights/OnTrackIllustration.tsx
import Svg, { Circle, Path } from 'react-native-svg';

/** Layered hills + a sun -- the "You're on track" banner's illustration. Hand-drawn with
 *  react-native-svg primitives, same approach DonutChart.tsx uses for its own chart: no bitmap
 *  asset, nothing to keep in sync with an external design tool. */
export function OnTrackIllustration({ width = 120, height = 72 }: { width?: number; height?: number }) {
  return (
    <Svg width={width} height={height} viewBox="0 0 120 72">
      <Circle cx="96" cy="20" r="12" fill="#E8B84B" opacity={0.85} />
      <Path d="M0 56 Q20 32 40 50 T80 44 T120 52 V72 H0 Z" fill="#B7C9AE" opacity={0.55} />
      <Path d="M0 64 Q30 44 60 60 T120 58 V72 H0 Z" fill="#7C9473" opacity={0.75} />
    </Svg>
  );
}
```

- [ ] **Step 2: Render the banner**

Compute the banner's copy once, alongside the other derived consts (not inline in JSX), so the
title and body can never disagree about which of the three states (down/up/no-data) they're in:

```tsx
const expenseDelta = snapshotKpis.find((k) => k.label === 'Expenses')?.delta ?? null;
const trackBannerTitle = expenseDelta === null ? 'This month' : expenseDelta <= 0 ? "You're on track!" : 'Heads up';
const trackBannerBody = expenseDelta === null
  ? 'Keep an eye on your spending this month.'
  : expenseDelta <= 0
    ? `Your spending is ${Math.abs(expenseDelta).toFixed(0)}% lower than last month. Keep it up!`
    : `Your spending is ${expenseDelta.toFixed(0)}% higher than last month.`;
```

Insert right after the header (before the existing static notice banner):

```tsx
{summary ? (
  <View style={[styles.trackBanner, { backgroundColor: c.primaryLight }]}>
    <View style={styles.trackBannerText}>
      <Text style={[styles.trackBannerTitle, { color: c.ink }]}>{trackBannerTitle}</Text>
      <Text style={[styles.trackBannerBody, { color: c.mutedInk }]}>{trackBannerBody}</Text>
    </View>
    <OnTrackIllustration />
  </View>
) : null}
```

Import `OnTrackIllustration` from `'../components/insights/OnTrackIllustration'`.

Add to `styles`. No `marginHorizontal` — `content`'s own `padding: spacing.md` already gives every
top-level child (this banner included) the standard horizontal inset; adding a second one here
would double it, making this banner visibly narrower than the `.section`-styled Cards around it
(same mistake Step 3's header padding note above already caught once):
```ts
trackBanner: {
  flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
  borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
},
trackBannerText: { flex: 1, marginRight: spacing.sm },
trackBannerTitle: { fontSize: 16, fontWeight: '700' },
trackBannerBody: { fontSize: 12, marginTop: 4, lineHeight: 17 },
```

- [ ] **Step 3: Render This Month at a Glance**

Right after the banner:

```tsx
{summary ? (
  <View style={[styles.glanceCard, { backgroundColor: c.card, borderColor: c.border }]}>
    <Text style={[styles.glanceHeading, { color: c.ink }]}>This Month at a Glance</Text>
    <View style={styles.glanceRow}>
      {[
        { label: 'Income', value: summary.monthlyIncome, delta: summary.incomeDeltaPct, invert: false },
        { label: 'Expenses', value: summary.monthlyExpense, delta: summary.expenseDeltaPct, invert: true },
        { label: 'Categories', value: Object.keys(summary.spendByCategory).length, delta: null, invert: false, isCount: true },
        { label: 'Net Savings', value: summary.netCashFlow, delta: summary.netDeltaPct, invert: false },
      ].map((stat) => (
        <View key={stat.label} style={styles.glanceStat}>
          <Text style={[styles.glanceValue, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
            {stat.isCount ? stat.value : fmtCurrency(stat.value)}
          </Text>
          <Text style={[styles.glanceLabel, { color: c.mutedInk }]}>{stat.label}</Text>
          {stat.delta !== null ? (
            <Text style={[
              styles.glanceDelta,
              { color: (stat.invert ? stat.delta < 0 : stat.delta >= 0) ? c.success : c.danger },
            ]}>
              {stat.delta >= 0 ? '▲' : '▼'} {Math.abs(stat.delta).toFixed(0)}%
            </Text>
          ) : null}
        </View>
      ))}
    </View>
  </View>
) : null}
```

Add to `styles`. Same no-`marginHorizontal` reasoning as `trackBanner` above:
```ts
glanceCard: {
  borderWidth: 1, borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.md,
},
glanceHeading: { fontSize: 14, fontWeight: '700', marginBottom: spacing.sm },
glanceRow: { flexDirection: 'row', justifyContent: 'space-between' },
glanceStat: { flex: 1, alignItems: 'flex-start' },
glanceValue: { fontSize: 15, fontWeight: '700' },
glanceLabel: { fontSize: 10, marginTop: 2 },
glanceDelta: { fontSize: 10, fontWeight: '600', marginTop: 2 },
```

Import `largeText` is already available (`useLargeFontScale()`, already called in this file).

- [ ] **Step 4: Write tests**

```tsx
it('shows the banner and This Month at a Glance once dashboard-summary resolves', async () => {
  dashboard.summary.mockResolvedValue({
    monthlyIncome: 145000, monthlyExpense: 12831, incomeDeltaPct: 12, expenseDeltaPct: -22,
    netCashFlow: 132169, netDeltaPct: 28, spendByCategory: { Shopping: 5798, 'Food & Dining': 900 },
  } as any);
  renderScreen();

  expect(await screen.findByText("You're on track!")).toBeTruthy();
  expect(screen.getByText(/22% lower than last month/)).toBeTruthy();
  expect(screen.getByText('This Month at a Glance')).toBeTruthy();
  expect(screen.getByText('2')).toBeTruthy(); // Categories count
});

it('frames the banner as a heads-up when spending is up, not "on track"', async () => {
  dashboard.summary.mockResolvedValue({
    monthlyIncome: 100000, monthlyExpense: 40000, incomeDeltaPct: 0, expenseDeltaPct: 15,
    netCashFlow: 60000, netDeltaPct: -5, spendByCategory: { Shopping: 40000 },
  } as any);
  renderScreen();

  expect(await screen.findByText('Heads up')).toBeTruthy();
  expect(screen.getByText(/15% higher than last month/)).toBeTruthy();
});

it('renders neither the banner nor the glance card while summary is still loading', async () => {
  renderScreen(); // dashboard.summary defaults to a never-resolving promise
  await screen.findByText(/not an\s+AI-generated assistant/);
  expect(screen.queryByText('This Month at a Glance')).toBeNull();
});
```

- [ ] **Step 5: Run the full test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx --forceExit`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/components/insights/OnTrackIllustration.tsx mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add on-track banner and This Month at a Glance to Insights"
```

---

### Task 4: Mobile — restyled Key Insights list

**Files:**
- Modify: `mobile/src/navigation/types.ts` (add `keyword` to `LedgerDrillThroughFilters`)
- Modify: `mobile/src/screens/LedgerScreen.tsx` (seed `keywordInput` from an incoming drill-through)
- Modify: `mobile/src/screens/LedgerScreen.test.tsx`
- Modify: `mobile/src/api/endpoints.ts` (extend `InsightsData` to match the regenerated backend shape)
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: `insightsQ.data.biggestCategory`/`.topMerchant` (Task 1), `movers` (existing),
  `categories` (Task 2), `iconNameFor`/`colorHexFor` (existing, `lib/categoryIcons.ts`).
- Produces: `LedgerDrillThroughFilters.keyword?: string`, consumed by `LedgerScreen.tsx`.

- [ ] **Step 1: Add `keyword` to LedgerDrillThroughFilters**

```ts
// mobile/src/navigation/types.ts, inside the existing LedgerDrillThroughFilters interface:
export interface LedgerDrillThroughFilters {
  accountId?: string;
  categoryId?: string;
  categoryName?: string;
  dateFrom?: string;
  dateTo?: string;
  // Insights' "Top Merchant" row (Track C/C4-style drill-through, no category to filter by --
  // a merchant isn't a category LedgerScreen already knows how to narrow on any other way).
  keyword?: string;
  label: string;
  nonce: number;
}
```

- [ ] **Step 2: Seed LedgerScreen's search box from an incoming keyword**

```tsx
// mobile/src/screens/LedgerScreen.tsx, inside the existing
// `if (incomingFilters && incomingFilters.nonce !== consumedNonce) { ... }` block (the same
// render-phase adjustment that already resets manualDateFrom/manualDateTo on a fresh
// drill-through):
if (incomingFilters && incomingFilters.nonce !== consumedNonce) {
  setConsumedNonce(incomingFilters.nonce);
  setActiveDrillThrough(incomingFilters);
  setManualDateFrom(null);
  setManualDateTo(null);
  // A keyword-only drill-through (Insights' Top Merchant) has nothing else to filter by, so it
  // must reach the actual search box -- unlike category/account/date, which activeDrillThrough
  // already carries into `filters` directly.
  if (incomingFilters.keyword) setKeywordInput(incomingFilters.keyword);
}
```

- [ ] **Step 3: Write a LedgerScreen test for the new keyword seeding**

Add to `LedgerScreen.test.tsx`'s existing `describe('drill-through filters (Track C/C4)', ...)`
block:

```tsx
it('seeds the search box from an incoming keyword-only drill-through', async () => {
  mockRouteParams = { filters: { keyword: 'Myntra', label: 'Myntra', nonce: 1 } };
  transactions.search.mockResolvedValue(page([]) as never);
  renderScreen();

  await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(
    expect.objectContaining({ keyword: 'Myntra' })
  ));
  expect(screen.getByDisplayValue('Myntra')).toBeTruthy();
});
```

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/LedgerScreen.test.tsx --forceExit`
Expected: all pass (the pre-existing count plus this one new test).

- [ ] **Step 4: Extend InsightsData to match the regenerated backend shape**

```ts
// mobile/src/api/endpoints.ts, replacing the existing InsightsData interface:
export interface CategoryHighlight { name: string; amount: number; }
export interface MerchantHighlight { name: string; amount: number; }
export interface InsightsData {
  sentences: string[];
  movers: CategoryMover[];
  coverageCaveat: CoverageCaveat | null;
  biggestCategory: CategoryHighlight | null;
  topMerchant: MerchantHighlight | null;
}
```

- [ ] **Step 5: Render the restyled Key Insights list**

Replaces the existing "This Month's Observations" `<Card>` block entirely (the plain sentence list)
with a tappable, iconed one. Insert a category-name-to-token resolver near the top of the
component body, alongside the existing `categories` query from Task 2:

```tsx
const iconTokenForCategory = (categoryName: string) =>
  categories.find((cat) => cat.name === categoryName)?.icon ?? 'tag';
const colorTokenForCategory = (categoryName: string) =>
  categories.find((cat) => cat.name === categoryName)?.color ?? 'gray';

function openTransactionsFiltered(filters: Omit<LedgerDrillThroughFilters, 'nonce'>) {
  navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
    filters: { ...filters, nonce: Date.now() },
  });
}
```

```tsx
{insightsQ.isLoading ? (
  <SkeletonCard style={styles.section} lines={5} />
) : (
  <Card style={styles.section}>
    <View style={styles.keyInsightsHeader}>
      <SectionHeading title="Key Insights" />
      {sentences.length > 0 ? (
        <Pressable onPress={() => setShowAllInsights((v) => !v)} accessibilityRole="button">
          <Text style={[styles.seeAll, { color: c.primary }]}>
            {showAllInsights ? 'Show less' : 'See all insights'}
          </Text>
        </Pressable>
      ) : null}
    </View>
    {insightsQ.isError ? (
      <Text style={[styles.error, { color: c.danger }]}>
        Couldn&apos;t load your insights — pull down to try again.
      </Text>
    ) : !insightsData?.biggestCategory && !insightsData?.topMerchant && movers.length === 0 && sentences.length === 0 ? (
      <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
    ) : (
      <>
        {insightsData?.biggestCategory ? (
          <Pressable
            style={[styles.insightRow, { borderBottomColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`Biggest category: ${insightsData.biggestCategory.name} at ${fmtCurrency(insightsData.biggestCategory.amount)}`}
            onPress={() => openTransactionsFiltered({
              categoryName: insightsData.biggestCategory!.name,
              label: insightsData.biggestCategory!.name,
            })}
          >
            <View style={[styles.insightIcon, { backgroundColor: colorHexFor(colorTokenForCategory(insightsData.biggestCategory.name)) }]}>
              <Ionicons name={iconNameFor(iconTokenForCategory(insightsData.biggestCategory.name))} size={16} color="#fff" />
            </View>
            <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
              <Text style={styles.insightBold}>{insightsData.biggestCategory.name}</Text> was your
              biggest category at <Text style={styles.insightBold}>{fmtCurrency(insightsData.biggestCategory.amount)}</Text>.
            </Text>
            <Ionicons name="chevron-forward" size={16} color={c.muted} />
          </Pressable>
        ) : null}

        {insightsData?.topMerchant ? (
          <Pressable
            style={[styles.insightRow, { borderBottomColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`Top merchant: ${insightsData.topMerchant.name} at ${fmtCurrency(insightsData.topMerchant.amount)}`}
            onPress={() => openTransactionsFiltered({
              keyword: insightsData.topMerchant!.name,
              label: insightsData.topMerchant!.name,
            })}
          >
            <View style={[styles.insightIcon, { backgroundColor: c.mutedInk }]}>
              <Ionicons name="trophy-outline" size={16} color="#fff" />
            </View>
            <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
              Your top merchant this month was <Text style={styles.insightBold}>"{insightsData.topMerchant.name}"</Text> at{' '}
              <Text style={styles.insightBold}>{fmtCurrency(insightsData.topMerchant.amount)}</Text>.
            </Text>
            <Ionicons name="chevron-forward" size={16} color={c.muted} />
          </Pressable>
        ) : null}

        {movers.slice(0, 3).map((m) => (
          <Pressable
            key={m.category}
            style={[styles.insightRow, { borderBottomColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`${m.category} spend was ${Math.abs(m.pctChange ?? 0).toFixed(0)}% ${
              (m.pctChange ?? 0) >= 0 ? 'more' : 'lower'
            } than your recent average, ${fmtCurrency(m.current)} versus usual ${fmtCurrency(m.priorAverage)}`}
            onPress={() => openTransactionsFiltered({ categoryName: m.category, label: m.category })}
          >
            <View style={[styles.insightIcon, { backgroundColor: colorHexFor(colorTokenForCategory(m.category)) }]}>
              <Ionicons name={iconNameFor(iconTokenForCategory(m.category))} size={16} color="#fff" />
            </View>
            <Text style={[styles.insightText, { color: c.ink }]} numberOfLines={largeText ? 3 : 2}>
              <Text style={styles.insightBold}>{m.category}</Text> spend was{' '}
              <Text style={styles.insightBold}>
                {Math.abs(m.pctChange ?? 0).toFixed(0)}% {(m.pctChange ?? 0) >= 0 ? 'more' : 'lower'}
              </Text>{' '}
              than your recent average ({fmtCurrency(m.current)} vs {fmtCurrency(m.priorAverage)}).
            </Text>
            <Ionicons name="chevron-forward" size={16} color={c.muted} />
          </Pressable>
        ))}

        {showAllInsights ? (
          <View style={styles.allInsights}>
            {sentences.map((s, i) => (
              <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
              </View>
            ))}
          </View>
        ) : null}
      </>
    )}
  </Card>
)}
```

Add `const [showAllInsights, setShowAllInsights] = useState(false);` near the component's other
`useState` calls, `const insightsData = insightsQ.data;` alongside the existing `sentences`/
`movers`/`recurring` derived consts, and import `iconNameFor`/`colorHexFor` from
`'../lib/categoryIcons'`.

Add to `styles`:
```ts
keyInsightsHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
seeAll: { fontSize: 12, fontWeight: '600' },
insightRow: {
  flexDirection: 'row', alignItems: 'center', paddingVertical: 10,
  borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
},
insightIcon: {
  width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
},
insightText: { flex: 1, fontSize: 13, lineHeight: 18 },
insightBold: { fontWeight: '700' },
allInsights: { marginTop: spacing.sm },
```

- [ ] **Step 6: Write InsightsScreen tests**

Extend the `beforeEach`'s default `insights.get` mock to include `biggestCategory`/`topMerchant`,
and add:

```tsx
it('renders the biggest-category and top-merchant Key Insights rows and opens Transactions on tap', async () => {
  insights.get.mockReset().mockResolvedValue({
    sentences: [], movers: [], coverageCaveat: null,
    biggestCategory: { name: 'Shopping', amount: 5798 },
    topMerchant: { name: 'myntra', amount: 3299 },
  });
  categories.list.mockResolvedValue([{ id: 'c1', name: 'Shopping', isSystem: true, icon: 'shopping-bag', color: 'blue' }]);
  renderScreen();

  expect(await screen.findByLabelText(/Biggest category: Shopping at ₹5,798/)).toBeTruthy();
  expect(screen.getByLabelText(/Top merchant: myntra at ₹3,299/)).toBeTruthy();

  const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
  navigate.mockClear();
  fireEvent.press(screen.getByLabelText(/Top merchant: myntra/));

  expect(navigate).toHaveBeenCalledWith('Transactions', {
    filters: { keyword: 'myntra', label: 'myntra', nonce: expect.any(Number) },
  });
});

it('expands to show every observation when "See all insights" is pressed', async () => {
  insights.get.mockReset().mockResolvedValue({
    sentences: ['A rule-based observation not covered by any other row.'],
    movers: [], coverageCaveat: null, biggestCategory: null, topMerchant: null,
  });
  renderScreen();
  await screen.findByText('See all insights');

  expect(screen.queryByText(/rule-based observation/)).toBeNull();
  fireEvent.press(screen.getByText('See all insights'));
  expect(screen.getByText(/rule-based observation/)).toBeTruthy();
});
```

Update the existing `renders observations, recurring payments and movers` test and any other test
that asserted on `"This Month's Observations"` as the section title — it's now `"Key Insights"`.

- [ ] **Step 7: Run both full test files**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx src/screens/LedgerScreen.test.tsx --forceExit`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add mobile/src/navigation/types.ts mobile/src/screens/LedgerScreen.tsx mobile/src/screens/LedgerScreen.test.tsx \
  mobile/src/api/endpoints.ts mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): restyle Insights' Key Insights list, tappable and iconed"
```

---

### Task 5: Mobile — Spending by Category donut

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: `summary.spendByCategory`, `DonutChart`/`Slice` (`../components/charts/DonutChart`),
  `bucketTopSlices`/`CHART_PALETTE` (`../lib/chartGeometry`) — all existing, reused verbatim from
  `DashboardScreen.tsx`.

- [ ] **Step 1: Compute donut slices**

```tsx
// Alongside the other derived consts:
const donutSlices: Slice[] = useMemo(() => {
  if (!summary) return [];
  return bucketTopSlices(Object.entries(summary.spendByCategory), CHART_PALETTE, 'Others');
}, [summary]);
```

Import `useMemo` from `'react'` (extend the existing `useEffect` import),
`DonutChart, type Slice` from `'../components/charts/DonutChart'`,
`CHART_PALETTE, bucketTopSlices` from `'../lib/chartGeometry'`,
`monthDateRange, monthLabel` from `'../lib/format'` (extend the existing `fmtCurrency, fmtDate`
import).

- [ ] **Step 2: Render the donut card**

Insert after the Key Insights card:

```tsx
{summary ? (
  <Card style={styles.section}>
    <SectionHeading title="Spending by Category" />
    {donutSlices.length === 0 ? (
      <EmptyState message="No spending recorded this month yet." />
    ) : (
      <DonutChart
        slices={donutSlices}
        centerLabel={fmtCurrency(donutSlices.reduce((s, x) => s + x.value, 0))}
        onSlicePress={(categoryName) => {
          // reportingMonth can't be null here -- donutSlices is only non-empty when summary has
          // real category spend, which requires a real reporting month behind it. Same guard
          // DashboardScreen's identical donut uses.
          const { dateFrom, dateTo } = monthDateRange(summary!.reportingMonth!);
          navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
            filters: {
              categoryName, dateFrom, dateTo,
              label: `${categoryName} · ${monthLabel(summary!.reportingMonth!)}`,
              nonce: Date.now(),
            },
          });
        }}
      />
    )}
  </Card>
) : null}
```

- [ ] **Step 3: Write a test**

```tsx
it('renders the Spending by Category donut and drills through on a slice tap', async () => {
  dashboard.summary.mockResolvedValue({
    monthlyIncome: 1, monthlyExpense: 1, incomeDeltaPct: 0, expenseDeltaPct: 0, netCashFlow: 0,
    netDeltaPct: 0, spendByCategory: { Shopping: 5798 }, reportingMonth: '2026-09',
    reportingMonthIsCurrent: true,
  } as any);
  renderScreen();

  expect(await screen.findByText('Spending by Category')).toBeTruthy();
});
```

(A full slice-tap interaction test mirrors `DashboardScreen.test.tsx`'s own donut coverage if one
exists there — check that file first and match its exact approach rather than inventing a new one,
since `DonutChart`'s tap surface/testID convention is already established there.)

- [ ] **Step 4: Run the full test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx --forceExit`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add Spending by Category donut to Insights"
```

---

### Task 6: Mobile — Recurring Payments summary card

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: `recurring` (existing, already fetched by this screen).

- [ ] **Step 1: Add a ref and layout capture for the full list section**

```tsx
const recurringListY = useRef(0);
const scrollRef = useRef<ScrollView>(null);
```

Attach `ref={scrollRef}` to the existing top-level `<ScrollView>`, and `onLayout` on the existing
full "Recurring Payments & Subscriptions" `<Card>` wrapper:
```tsx
onLayout={(e) => { recurringListY.current = e.nativeEvent.layout.y; }}
```

- [ ] **Step 2: Render the compact summary card**

Insert right after the donut card (before the existing full Recurring Payments list):

```tsx
{recurring.length > 0 ? (
  <Card style={styles.section}>
    <View style={styles.recurringSummaryRow}>
      <View>
        <Text style={[styles.recurringSummaryCount, { color: c.ink }]}>{recurring.length} active</Text>
        <Text style={[styles.recurringSummaryTotal, { color: c.mutedInk }]}>
          {fmtCurrency(recurring.reduce((s, r) => s + r.averageAmount, 0))} / month
        </Text>
      </View>
      <Pressable
        onPress={() => scrollRef.current?.scrollTo({ y: recurringListY.current, animated: true })}
        accessibilityRole="button"
      >
        <Text style={[styles.viewRecurring, { color: c.primary }]}>View Recurring →</Text>
      </Pressable>
    </View>
  </Card>
) : null}
```

Add to `styles`:
```ts
recurringSummaryRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
recurringSummaryCount: { fontSize: 15, fontWeight: '700' },
recurringSummaryTotal: { fontSize: 12, marginTop: 2 },
viewRecurring: { fontSize: 12, fontWeight: '600' },
```

- [ ] **Step 3: Write a test**

```tsx
it('shows a Recurring Payments summary and scrolls to the full list on View Recurring', async () => {
  renderScreen();
  await screen.findByText('netflix');

  expect(screen.getByText('1 active')).toBeTruthy();
  expect(screen.getByText('₹649 / month')).toBeTruthy();
  // scrollTo itself isn't observable in the RN test renderer -- this just confirms the control
  // exists and is pressable without throwing.
  fireEvent.press(screen.getByText('View Recurring →'));
});
```

- [ ] **Step 4: Run the full test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx --forceExit`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add Recurring Payments summary card to Insights"
```

---

### Task 7: Mobile — bottom banner, links to Reports

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`
- Modify: `mobile/src/screens/InsightsScreen.test.tsx`

**Interfaces:**
- Consumes: the `expenseDelta` const Task 3, Step 2 already introduces at the component level
  (not `snapshotKpis` directly — reuse the same derived value, don't look it up a second time),
  `Reports` route (existing, `MoreStackParamList`).

- [ ] **Step 1: Render the bottom banner**

Insert as the very last element inside the `<ScrollView>`, after the existing full Recurring
Payments list:

Reuses the same `expenseDelta` const Task 3, Step 2 already computed at the component level —
do not re-derive it a second time here (two independent lookups of the same value is exactly the
kind of drift that let Task 3's title/body disagree before that step's own fix).

```tsx
{expenseDelta !== null ? (
  <View style={[styles.bottomBanner, { backgroundColor: c.successBg ?? c.primaryLight }]}>
    <Text style={[styles.bottomBannerText, { color: c.ink }]}>
      {expenseDelta <= 0
        ? `You're spending ${Math.abs(expenseDelta).toFixed(0)}% less than last month.`
        : `You're spending ${expenseDelta.toFixed(0)}% more than last month.`}
    </Text>
    <Pressable onPress={() => stackNavigation.navigate('Reports')} accessibilityRole="button">
      <Text style={[styles.bottomBannerLink, { color: c.primary }]}>View Details →</Text>
    </Pressable>
  </View>
) : null}
```

Check `useTheme()`'s real token set (`mobile/src/theme/palette.ts`) for a green-tinted background
token before using `c.successBg` above — `LedgerScreen.tsx`'s reconciliation badge colors reference
`c.successBg` already, so it likely exists, but confirm the exact name rather than assuming; fall
back to `c.primaryLight` if it doesn't.

Add to `styles`:
```ts
bottomBanner: {
  // No marginHorizontal -- same reasoning as trackBanner/glanceCard in Task 3.
  flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
  borderRadius: radius.md, padding: spacing.md, marginTop: spacing.md,
},
bottomBannerText: { flex: 1, fontSize: 13, marginRight: spacing.sm },
bottomBannerLink: { fontSize: 12, fontWeight: '700' },
```

- [ ] **Step 2: Write a test**

```tsx
it('shows the bottom banner and opens Reports from View Details', async () => {
  dashboard.summary.mockResolvedValue({
    monthlyIncome: 145000, monthlyExpense: 12831, incomeDeltaPct: 12, expenseDeltaPct: -22,
    netCashFlow: 132169, netDeltaPct: 28, spendByCategory: { Shopping: 5798 },
  } as any);
  renderScreen();

  expect(await screen.findByText(/22% less than last month/)).toBeTruthy();
  const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
  navigate.mockClear();
  fireEvent.press(screen.getByText('View Details →'));

  expect(navigate).toHaveBeenCalledWith('Reports');
});
```

- [ ] **Step 3: Run the full test file**

Run: `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx jest src/screens/InsightsScreen.test.tsx --forceExit`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add bottom spending-comparison banner to Insights, links to Reports"
```

---

## Self-Review Checklist (run after Task 7, before opening a PR)

- [ ] `npx tsc --noEmit` clean for `mobile/` and a clean `mvn -DskipTests compile` for `backend/`.
- [ ] `eslint` clean on every mobile file touched across all 7 tasks.
- [ ] Full `InsightsScreen.test.tsx` and `LedgerScreen.test.tsx` runs, both 100% pass, with the
      exact before/after test counts noted in the PR description.
- [ ] `InsightsServiceTest` passes (backend).
- [ ] Confirm `settings-outline` and `trophy-outline` actually render a real glyph (not blank) —
      check against the installed Ionicons glyph map or a live screenshot, not assumption.
- [ ] Manually verify (live simulator, once tooling is available) that: the header gear opens
      Settings; the banner's framing flips correctly between an "on track" and a "heads up" month;
      the KPI glance numbers match Dashboard's own for the same account; Key Insights' Top
      Merchant row actually lands on a correctly keyword-filtered Transactions list; the donut's
      slice tap and the existing Category Movers' tap still both work; "View Recurring" actually
      scrolls to the full list, not just past it or short of it (the `onLayout`-measured Y offset
      approach can be off if the target Card's own layout hasn't settled before the tap); the
      bottom banner's "View Details" opens Reports.
- [ ] Confirm the OpenAPI regen (Task 1, Step 5) was actually run and its diff committed, not
      described — `git log --oneline -- backend/openapi/openapi.json` should show a new commit
      from this work.
- [ ] Re-open any of the four brainstorming decisions in the PR description if the actual
      implementation deviated from what was agreed (keep-under-More navigation, add-structured-
      backend-fields, keep-bottom-banner-linked-to-Reports, hand-coded-SVG-in-place-of-a-
      commissioned-illustration — flag this last one explicitly, since "commission a real
      illustration" was the literal decision and a hand-coded SVG is the closest achievable
      substitute within this implementation, not a literal fulfillment of it).
