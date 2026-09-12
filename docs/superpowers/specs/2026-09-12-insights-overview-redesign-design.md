# Insights Overview Redesign — Design

**Goal:** Redesign the mobile Insights screen (`mobile/src/screens/InsightsScreen.tsx`) into a
richer "Overview" — a motivational banner, a 4-stat "This Month at a Glance" row, a restyled,
tappable "Key Insights" list, a "Spending by Category" donut, and a compact Recurring Payments
summary — extending the passbook redesign's narrative framing (already shipped on Dashboard and
the Ledger's "This Month" card) into Insights.

**Scope:** The Overview content only. The reference mockup shows a 5-tab pill bar (Overview /
Spending / Income / Recurring / Trends) and a promoted bottom-tab position for Insights; both are
explicitly out of scope — see Global Constraints.

## Context

Current `InsightsScreen.tsx` (285 lines) is a plain vertical `ScrollView`: a static disclaimer
banner, "This Month's Observations" (a list of opaque prose `sentences: string[]`), "Recurring
Payments & Subscriptions" (a full list with a dismiss action), and "Category Movers" (a
structured `CategoryMover[]` list, tap-to-filter into Transactions). It's reached via
More → Insights, a pushed screen inside `MoreStack` that currently gets React Navigation's default
native header (title "Insights", back button — no `headerShown: false` override).

## Architecture

Four pieces, in the order below because 2 and 3 both touch the header/query-setup area and are
easiest to review separately from the later sections:

1. **Backend**: `InsightsDto` gains two new nullable structured fields, `biggestCategory` and
   `topMerchant` — both values `InsightsService.build()` already computes internally (used today
   only to format a sentence), now also returned as `{name, amount}` so the client can build a
   tappable, icon-bearing row instead of parsing prose. Requires regenerating
   `backend/openapi/openapi.json` and all three clients' `generated-types.ts` (mobile, frontend,
   admin-portal) — this repo's CI blocks on that drift (`.github/workflows/ci.yml`'s
   `openapi/openapi.json` staleness check).
2. **Header + data wiring**: `InsightsScreen` gets `headerShown: false` (matching the
   Referrals/Profile pattern already used elsewhere in `MoreStack`) and its own custom header
   (title, subtitle, settings gear → navigates to the existing `Settings` screen, same-stack).
   Adds two new queries: `dashboardApi.summary()` under the existing shared `['dashboard-summary']`
   key (already warmed if the user's visited Dashboard or Ledger this session), and
   `categoriesApi.list()` under the existing shared `['categories']` key — both free/cheap re-fetches
   in the common case, not new network cost.
3. **New sections**: the banner, KPI glance row, restyled Key Insights, donut, and Recurring
   summary card — see Sections below.
4. **Ledger drill-through by keyword**: `LedgerDrillThroughFilters` (`navigation/types.ts`) has no
   `keyword` field today — only `accountId`/`categoryId`/`categoryName`/`dateFrom`/`dateTo`. Tapping
   "Top Merchant" needs one, since a merchant isn't a category `LedgerScreen` can filter by any
   other way. Small, additive: one new optional field on the type, and one new line in
   `LedgerScreen.tsx`'s existing `if (incomingFilters && incomingFilters.nonce !== consumedNonce)`
   block (the same place manualDateFrom/manualDateTo already get reset on a fresh drill-through) —
   `setKeywordInput(incomingFilters.keyword ?? '')`.
5. **Bottom banner**: a redundant-with-the-top-banner "You're spending N% less" card, kept per
   explicit decision, with "View Details" navigating to the existing `Reports` screen (same-stack,
   no new screen).

**Tech Stack:** React Native / Expo SDK 57, TypeScript, TanStack Query, `react-native-svg`
(already a dependency, used by `DonutChart`) for the banner illustration, existing `theme.ts`
tokens. Backend: Spring Boot record DTOs, no new endpoint (existing `GET /api/v1/insights`).

## Global Constraints

- Do not touch the 5-tab pill bar (Overview/Spending/Income/Recurring/Trends) or promote Insights
  to a primary bottom tab. Both are separate, deliberate navigation decisions explicitly deferred
  — confirmed with the repo owner during brainstorming.
- Do not modify `Category Movers`' existing tap-to-filter behavior or `DonutChart`/`bucketTopSlices`
  (`lib/chartGeometry.ts`) — both are reused as-is.
- The existing full "Recurring Payments & Subscriptions" list (with its dismiss mutation) stays on
  this screen, unchanged, below the new compact summary card — no navigation to a dedicated
  Recurring screen exists yet.
- Every new backend field must round-trip through the real OpenAPI regen pipeline
  (`backend/scripts/generate-openapi-spec.sh` + `npm run generate:types` in `mobile/`, `frontend/`,
  `admin-portal/`), not just a hand-edited `InsightsData` interface in `mobile/src/api/endpoints.ts`
  — this repo's CI (`OpenAPI contract drift` job) blocks on exactly this drift.
- `useLargeFontScale`'s `numberOfLines={largeText ? 2 : 1}` pattern (already applied to the
  Recurring row's title in this file) carries over to every new piece of text that can wrap:
  Key Insights row titles, KPI glance labels.
- Debit/credit and up/down color convention (`c.success`/`c.danger`) stays exactly as this screen's
  own Category Movers section already uses it (spending UP is `c.danger`, the inverse of Dashboard's
  income KPI) — the new KPI glance row and banner reuse the identical convention via
  `useDashboardKpis`'s existing `invert` flag.

## Sections

### 1. Header

Replace the native stack header with a custom one: "Insights" title, "Understand your money. Make
better decisions." subtitle, a settings gear icon-button navigating to `Settings`
(`navigation.navigate('Settings')`, same `MoreStack` — no `getParent()` needed, unlike the
cross-tab Category Movers navigation).

### 2. "You're on track" banner

An SVG illustration (layered hills + a circle for the sun, built with `react-native-svg` primitives
— no new asset pipeline, matching how `DonutChart` is already hand-built), with headline text
driven by `useDashboardKpis(summary).snapshotKpis` (the `Expenses` KPI's `delta`/`invert`): e.g.
"You're on track!" / "Your spending is N% lower than last month. Keep it up!" when expense delta is
negative (good), a different framing when it's positive (see Task 4 in the plan for the exact
copy branches).

### 3. This Month at a Glance

Four stats in one row (a new compact layout, not `LedgerSnapshotCard`'s vertical list):
- **Income** — `summary.monthlyIncome` / `incomeDeltaPct`, from `useDashboardKpis`.
- **Expenses** — `summary.monthlyExpense` / `expenseDeltaPct`, `invert: true` (already set).
- **Categories** — `Object.keys(summary.spendByCategory).length`, a trivial derived count, no new
  field.
- **Net Savings** — `summary.netCashFlow` / `netDeltaPct`.

All four numbers already exist on `DashboardSummary`; nothing new to fetch.

### 4. Key Insights

Up to 5 tappable rows, each with a category-derived icon (`iconNameFor`/`colorHexFor` from
`lib/categoryIcons.ts`, resolved via the category's `icon`/`color` token looked up by name against
`categoriesApi.list()`), a bold highlight in the text, and a chevron:
- **Biggest category** — from the new `insightsQ.data.biggestCategory` field. Tap navigates to
  Transactions filtered to that category (same `getParent().navigate('Transactions', ...)` pattern
  Category Movers already uses).
- **Top merchant** — from the new `insightsQ.data.topMerchant` field. Tap navigates to Transactions
  with the new `keyword` drill-through field set to the merchant name (see Architecture piece 4) —
  `LedgerScreen`'s own search already supports a `keyword` server-side filter, just not yet seeded
  from an incoming drill-through.
- **Up to 3 category movers** — built directly from the existing `movers` array (already fetched,
  already structured), not string-parsed from `sentences`. Same tap-to-filter as today's Category
  Movers section.
"See all insights" links to the existing `sentences` list in full — kept as a collapsed/expandable
section on this same screen (not a new destination), so no sentence this backend already produces
is lost from the redesign.

### 5. Spending by Category

Reuses `DonutChart` + `bucketTopSlices` + `CHART_PALETTE` verbatim from `DashboardScreen.tsx`'s own
"Spending by Category" card — identical bucketing (top-N + Other), identical tap-to-filter
behavior, over `summary.spendByCategory`.

### 6. Recurring Payments summary card

A new compact card above the existing full list: active count (`recurring.length`) and total
monthly cost (`recurring.reduce((s, r) => s + r.averageAmount, 0)`) — both computed from the
`recurring` array this screen already fetches, no new query. "View Recurring" scrolls to the
existing full list further down this same screen (an anchor/scroll, not a navigation) since no
dedicated Recurring screen exists.

### 7. Bottom banner

"You're spending N% less than last month" (same `expenseDeltaPct` data as the top banner and the
KPI glance — deliberately repeated per the repo owner's explicit choice) with a "View Details" link
navigating to the existing `Reports` screen (`navigation.navigate('Reports')`, same-stack).

## Verification

- `npx tsc --noEmit` (mobile), `eslint` on every touched file, backend `mvn test` for
  `InsightsServiceTest`.
- `InsightsScreen.test.tsx` — every existing test must keep passing unchanged (this is additive,
  not a rewrite of the current sections' behavior) plus new coverage for the header, KPI glance,
  Key Insights rows (including the two new backend-sourced ones), donut, and Recurring summary
  card.
- Backend: new `InsightsServiceTest` cases for `biggestCategory`/`topMerchant` null-when-absent and
  populated-when-present, mirroring the existing `biggestCategorySentence_...`/
  `topMerchantSentence_...` test style in the same file.
- OpenAPI regen actually run (`backend/scripts/generate-openapi-spec.sh`, then `npm run
  generate:types` in all three clients) and the diff committed — not just described.
