# Insights Spending Tab + Navigation Promotion — Design

**Goal:** Add the pill tab bar (Overview / Spending / Income / Recurring / Trends) to the mobile
Insights screen that PR #1363's Overview redesign deliberately deferred, fully build the
"Spending" tab, and promote Insights from a `MoreStack` row to its own primary bottom tab —
swapping places with Goals, which moves into `MoreStack`.

**Scope:** Bottom-tab restructure (Insights ↔ Goals swap), the pill tab bar shell on
`InsightsScreen.tsx`, full "Spending" tab content, a `month` query param on `GET /api/v1/insights`
to back Spending's month picker, and "coming soon" placeholders for Income/Recurring/Trends.
Building out Income/Recurring/Trends' real content is explicitly out of scope — separate,
later work.

## Context

`InsightsScreen.tsx` (569 lines, current `origin/main`) is a single "Overview" screen after PR
#1363: custom header, "You're on track" banner, "This Month at a Glance", "Key Insights" (biggest
category / top merchant / top-3 movers, with a "See all insights" toggle for the full sentence
list), "Spending by Category" donut, a compact Recurring summary card, the full "Recurring
Payments & Subscriptions" list, and a bottom "spending N% less" banner. That PR's own design spec
explicitly listed the 5-tab pill bar and promoting Insights to a bottom tab as out of scope,
"confirmed with the repo owner during brainstorming" — this spec is that deferred decision, now
in scope.

Today's bottom tabs (`AppTabs.tsx`): Home, Transactions, Import (FAB), Goals, More. Insights lives
inside `MoreStack` (`MoreScreen.tsx` row → pushed screen, `headerShown: false`, its own custom
header). Goals was itself promoted from a `MoreStack` row to a top-level tab in #1306 — this spec
reverses that specific swap (Goals back into `MoreStack`, Insights takes its tab slot), following
that commit's own diff as a template in the opposite direction.

## Architecture

Three independent pieces, ordered so the navigation swap (which nothing else here depends on) is
isolated from the two content pieces:

1. **Bottom-tab swap** — `AppTabs.tsx`, `types.ts`, `MoreScreen.tsx`, `tourSteps.ts`, and every
   `navigation.navigate('Goals')` call site.
2. **Backend month param** — `GET /api/v1/insights?month=yyyy-MM`, additive and
   backward-compatible.
3. **`InsightsScreen.tsx` pill tab bar** — local tab state, Overview trimmed of the full Recurring
   list, Spending built out, Income/Recurring/Trends stubbed.

**Tech Stack:** Unchanged — React Native/Expo SDK 57, TypeScript, TanStack Query, React Navigation
bottom-tabs + native-stack, `OptionPickerModal` (existing shared component). Backend: Spring Boot,
existing `GET /api/v1/insights` endpoint gains a request param, no new endpoint.

## Section 1: Bottom-tab swap

Exact mirror of commit `ee34a4f2` ("promote Goals to its own bottom tab"), reversed:

- **`AppTabs.tsx`**: `Tab.Screen name="Goals"` replaced with `Tab.Screen name="Insights"
  component={InsightsScreen}`. `TAB_ICON.Insights = { active: 'stats-chart', inactive:
  'stats-chart-outline' }` (`TAB_ICON.Goals` entry removed). `GoalsScreen` added to `MoreNavigator`
  as `<MoreStack.Screen name="Goals" component={GoalsScreen} options={{ headerShown: false }} />`
  — `headerShown: false` because `GoalsScreen` already renders its own title/top-inset from its
  own prior promotion (confirmed by reading the file — no revert needed there). `registerGoals`
  (currently in `AppTabs.tsx`, feeding `registerByTab`) moves to `MoreScreen.tsx` as
  `registerGoals` in `registerByRoute`; `registerInsights` (currently in `MoreScreen.tsx`) moves to
  `AppTabs.tsx`'s `registerByTab`.
- **`types.ts`**: `AppTabParamList` loses `Goals: undefined`, gains `Insights: undefined`.
  `MoreStackParamList` loses `Insights: undefined`, gains `Goals: undefined`.
- **`MoreScreen.tsx`**: `MENU_ITEMS` drops the `{ label: 'Insights', route: 'Insights' }` row, adds
  `{ label: 'Goals', route: 'Goals' }` immediately after `Budgets` — the exact position `Goals`
  held before #1306 (verified via `git show fc989ae6^:mobile/src/screens/MoreScreen.tsx`).
- **`tourSteps.ts`**: `TourStep.tab` union loses `'Goals'`, gains `'Insights'`. The `'goals'` step's
  `tab` becomes `'More'`; the `'insights'` step's `tab` becomes `'Insights'`.
- **Nested-navigate fixes** — `navigation.navigate('Goals')` becomes `navigation.navigate('More',
  { screen: 'Goals' })` (same pattern as `DashboardScreen.tsx`'s existing `navigate('More', {
  screen: 'Reports' })` for "View Reports") at:
  - `DashboardScreen.tsx` line ~559 (`onCreateGoal`)
  - `DashboardScreen.tsx` line ~624 (`Manage Goals` quick action)
  - `AppTabs.tsx`'s `QuickActionSheet onAddGoal` prop
- **Tests**: `AppTabs.test.tsx` and `DashboardScreen.test.tsx` updated to match (mirroring #1306's
  own test diff in reverse) — assert `Insights` renders under the tab bar and `Goals` is reachable
  via `More`, not the inverse.

No change to `GoalsScreen.tsx` itself, `InsightsScreen.tsx`'s own header (already self-contained,
already correct for a top-level tab), or `InsightsExplorerService`/any other backend caller — the
only backend caller of `InsightsService.build` today is `InsightsController` (confirmed by repo
search).

## Section 2: Backend month param

`InsightsService.pipeline(UUID userId)` (package-private, also called directly by
`InsightsExplorerService`) is generalized to `pipeline(UUID userId, String requestedMonth)`,
nullable. `pipeline(UUID userId)` becomes a one-line delegation to `pipeline(userId, null)` —
`InsightsExplorerService`'s existing call site is untouched.

Inside the generalized method:
- `currentMonth = requestedMonth != null ? requestedMonth : months.get(months.size() - 1)` (today's
  behavior when `requestedMonth` is null).
- `priorMonths` computed relative to `currentMonth` generically — `months.stream().filter(m ->
  m.compareTo(currentMonth) < 0).toList()`, then take the last `PRIOR_MONTHS_WINDOW` of those —
  rather than today's index-based `subList` off the end of `months`, which silently assumed
  `currentMonth` was always the newest element.
- `reportingMonthIsCurrent` unchanged: compares `currentMonth` against the user's real calendar
  "today" — already correctly produces "in <month>" vs "this month" wording for a
  non-current month (this branch already existed for the "haven't imported this month yet" case).
- A `requestedMonth` with zero transactions (a real month, just no data in it) is **not** an error
  — `groupByCategory` naturally returns an empty map, producing a `total: 0` sentence and empty
  movers, same as any other genuinely quiet month. Only `pipeline()`'s existing all-time-empty
  early return (`txns.isEmpty()`) still short-circuits to the "Upload or add transactions" message,
  unchanged.
- `build(UUID userId, String month)` new overload; `build(UUID userId)` delegates to
  `build(userId, null)`.

`InsightsController`: `@GetMapping` method gains `@RequestParam(required = false) String month`,
passed straight through. No format validation beyond what `YearMonth.parse` inside the service
already throws (a malformed string surfaces as the existing generic 400 handler — same as
`AnalyticsController`'s identical `month` param today, no new precedent needed).

Client-side: `insightsApi.get(month?: string) => api.get<InsightsData>('/insights', { params: {
month } })`, mirroring `analyticsApi.topMerchants(month)` exactly. OpenAPI regen
(`backend/scripts/generate-openapi-spec.sh` + `npm run generate:types` in mobile/frontend/
admin-portal) — required, this repo's CI blocks on the drift.

New `InsightsServiceTest` cases: explicit past month with data, explicit month with zero
transactions but other months populated, and the existing null-month tests re-asserted unchanged
(regression guard on the generalization).

## Section 3: `InsightsScreen.tsx` pill tab bar

Local `useState<'overview' | 'spending' | 'income' | 'recurring' | 'trends'>('overview')` — no
nested navigator; only two tabs have real content this round, and React Navigation's per-route
overhead (its own screens, params, headers) buys nothing YAGNI wouldn't rather skip. The custom
header (title/subtitle/gear) and the static disclaimer banner render once, above the pill row,
shared by every tab. The pill row itself: five `Pressable`s in a horizontal scroll (in case a
larger Dynamic Type setting doesn't fit five labels on one line — same defensive pattern
`useLargeFontScale` is already applied for elsewhere on this screen), active tab styled with
`c.primaryLight` fill / `c.primary` text, matching the mockup's pill styling.

**Overview** (existing content, two changes):
- The full "Recurring Payments & Subscriptions" list (currently rendered unconditionally below the
  compact summary card) is removed from Overview — it moves to Spending, avoiding the duplication
  the mockup would otherwise create between the two tabs.
- The compact Recurring summary card's "View Recurring →" link changes from
  `scrollRef.current?.scrollTo(...)` (scrolling to an anchor further down this same screen) to
  `setTab('spending')` followed by the same scroll-to-anchor once Spending's own list has mounted
  (Spending gets its own `recurringListY`/`scrollRef` pair, same pattern, reset per tab).

**Spending** (new): disclaimer banner is the shared one above the pill row, not repeated.
Below the pill row, in mockup order:
1. **"This Month's Observations"** — full `sentences` array, always rendered (no "See
   all"/collapse toggle — unlike Overview's Key Insights, which folds this behind a toggle; this
   tab's whole purpose is the detailed view). Header row carries the month picker: an
   `OptionPickerModal` fed by `reportsApi.availableMonths()` (existing endpoint/query, exact
   pattern copied from `AdvancedReportsScreen.tsx`), driving a `month` state that's passed to
   `insightsApi.get(month)` — a **second**, independent `useQuery` under `['insights', month]` (not
   reusing Overview's `['insights']` cache, since Overview always wants the current month
   regardless of what's picked here).
2. **"Recurring Payments & Subscriptions"** (moved from Overview, verbatim — same dismiss
   mutation, same empty state, same accessibility pattern) — reads the screen's existing single
   `recurringQ` / `dismissRecurring`, unaffected by the month picker. Recurring payments are not
   month-scoped in the backend (`recurringApi.list()` has no month param, and detecting a
   recurring pattern is inherently cross-month), so this section does not move when the month
   picker changes.
3. **"Category Movers vs. Recent Average"** — from the **month-scoped** insights query's `movers`
   array (uncapped at the backend, unlike Overview's top-3), same tap-to-filter as Overview's
   version. Shows the first 5 by default, "See All" expands the rest — same
   `showAllInsights`-style boolean toggle pattern already used on Overview, a sibling piece of
   state (`showAllMovers`) rather than reusing Overview's own toggle (independent tabs, independent
   expand state).

**Income / Recurring / Trends**: a single shared `EmptyState`-style "Coming soon" card (reusing
the existing `EmptyState` component with a fixed message per tab, e.g. "Income breakdown is coming
soon."), no data fetching.

## Data flow summary

- Overview: `['insights']` (no month), `['recurring']`, `['dashboard-summary']`, `['categories']`
  — unchanged queries, just less rendered from `['recurring']`'s result.
- Spending: `['insights', month]` (new, month-scoped) for Observations + Category Movers;
  `['recurring']` (shared with Overview's query cache) for the Recurring list; `['report-months']`
  (existing, shared with `AdvancedReportsScreen`/`ReportsScreen`'s own month pickers) for the
  picker's options.

## Testing

- `InsightsServiceTest`: new month-param cases (see Section 2).
- `InsightsScreen.test.tsx`: existing Overview assertions updated only where the Recurring list
  moved out; new coverage for the pill tab bar (tab switching renders the right content), Spending
  tab's Observations/month-picker/Recurring/Category-Movers sections, and the three placeholder
  tabs.
- `AppTabs.test.tsx` / `DashboardScreen.test.tsx`: updated per Section 1.
- `npx tsc --noEmit`, `eslint` on every touched file, backend `mvn test` for
  `InsightsServiceTest` (+ full suite for regressions given `pipeline()`'s signature change).
- OpenAPI regen actually run and the diff committed, not just described.
- Manual verification: iOS simulator run confirming tab switching, month picker, and the promoted
  bottom tab all work end to end — per this repo's mandatory post-implementation verification
  standard (no guessing, real evidence).
