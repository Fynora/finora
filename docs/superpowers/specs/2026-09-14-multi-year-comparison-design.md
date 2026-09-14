# Multi-Year Comparison (premium) — Design

**Status:** Design only. No code written. Answers issue #1455.

**Governing rule (from the issue):** "never monetize completeness, monetize interpretation." Gate
the comparison **view**, never the underlying data. Raw transactions and per-transaction history
stay fully visible and exportable on every plan, unchanged, before and after this feature ships.

## 1. What "before estimating" turned up

The issue asked to check whether `AnalyticsController`'s existing `/trend` already supports
multi-year ranges before committing to scope. It does not: `AnalyticsService.merchantTrend`
(`backend/src/main/java/com/finora/service/AnalyticsService.java:111`) uses a hardcoded
`TREND_MONTHS = 6` trailing window anchored on one optional month. This feature needs real,
net-new aggregation, not an extension of `/trend`.

## 2. The four views, defined

All four are bucketed by **calendar year** and computed from the same EXPENSE/INCOME transaction
data every other `AnalyticsService` method already reads (refund-netted via `refundsFor()`,
scoped to live accounts).

1. **Income progression** — total refund-netted `INCOME`-type transaction value per calendar year.
2. **Spending drift** — total refund-netted `EXPENSE` spend per calendar year, plus year-over-year
   % change (full years only — see §3).
3. **Category evolution** — per-category `EXPENSE` spend per calendar year (same grouping
   `topCategories` already does for one month, extended across years).
4. **Lifestyle inflation** — `(total EXPENSE / total INCOME)` ratio per calendar year. A rising
   ratio means spend is eating a growing share of income, independent of whether income itself is
   flat, growing, or shrinking. This is deliberately a different signal from "spending drift"
   (#2), which only tracks raw spend — making both would be redundant if they measured the same
   thing.

## 3. Partial years: never hide, never extrapolate

Every calendar year with any transaction history is shown, always, with its real total. No year is
suppressed and no year's total is projected/annualized from partial data — both would either hide
real financial history or fabricate a number the user could catch as wrong (a user who spent
₹150k in January knows a "₹1.8M projected" annualization is nonsense, and that kind of guess costs
more trust than an admittedly incomplete number).

Each year carries an explicit **coverage** figure: `X/12 months`. **Year-over-year % / trend
arrows are only computed between years that are both `12/12`** ("full years"). A partial year
shows its raw total (and, for lifestyle inflation, its ratio — valid on its own terms even for a
partial year) but no fabricated comparison against a full year.

### Coverage is month-completeness, not date-span

A naive "coverage = months between first transaction and today" is wrong: it can't tell "no
transactions because nothing to import yet" apart from "no transactions because this month's
statement hasn't been imported." Finora already has the right primitive for this distinction:
`StatementCoverageAnalyzer` / `AccountCoverageService`
(`backend/src/main/java/com/finora/imports/StatementCoverageAnalyzer.java`,
`backend/src/main/java/com/finora/service/AccountCoverageService.java`) already does real,
day-level gap detection between a single account's imported statements — the same analyzer behind
`GET /api/v1/accounts/{accountId}/coverage`.

**Definition:** calendar month M is *complete* for a user if **no live account has a coverage gap
(per `StatementCoverageAnalyzer.analyze`) overlapping any day in M.** A gap is only ever reported
*between* two of an account's own statement periods — days before an account's first statement or
after its last are never flagged as gaps by the analyzer, so an account that simply didn't exist
yet correctly contributes nothing to "incomplete," while a genuinely missing statement between two
imported ones correctly does.

This requires one new small method — `gapsForUser(userId)` on (or alongside)
`AccountCoverageService` — that runs the existing per-account analyzer for every live account and
unions the resulting gap ranges. No new gap-detection logic; this is composing an existing pure
function over more accounts.

A calendar month also needs a lower bound: the analyzer only reports gaps *between* statements, so
months before the user has any data at all are never "gaps" — they must be excluded from the
coverage count on their own terms, not accidentally counted as complete. That lower bound is
`TransactionRepository.findEarliestTxnDate` (already exists, scoped to live accounts) — the
calendar month of the user's very first transaction — rather than a statement-period-derived date,
so this works the same for a CSV-imported account as a PDF one.

**Known, inherited limitation, not introduced here:** `StatementCoverageAnalyzer`'s input
(`StatementImportRepository.findMetadataWithPeriodByUserIdAndAccountId`) already excludes
statements with no printed period — "CSV coverage is explicitly out of scope for this phase," per
that repository method's own comment. A CSV-only account therefore never contributes a detected
gap, meaning a real missing month on a CSV-only account will not be caught by this feature's
coverage badge — the same blind spot `GET /api/v1/accounts/{accountId}/coverage` already has today,
not a new one. `coverageMonths` for such an account counts every month from its first transaction
onward as complete, absent gap detection to say otherwise.

## 4. "Full Years" vs "This Year So Far"

Two named modes per view (not a custom date-range picker — see §4.3 for the scope line):

### 4.1 Full Years
Every calendar year with data, its raw total, its coverage badge, and YoY % only where both the
year and the prior year are `12/12`.

### 4.2 This Year So Far
Anchored **only** to the current (most recent) calendar year — never an arbitrary historical
window.

- `windowStart` = the later of January of the current year, or the calendar month of the user's
  earliest transaction (`TransactionRepository.findEarliestTxnDate`, scoped to live accounts —
  already exists, reused as-is). This matters for a user in their first calendar year: "This Year
  So Far" means "since I joined," not "since January whether I existed yet or not" — a June-joiner
  with two genuinely complete months (June, July) should see those two months, not an empty window
  because January-May predate them.
- `windowEnd` = the latest calendar month that is (a) strictly before the current calendar month
  (a month that hasn't finished is never "complete"), and (b) part of an unbroken complete-month
  run starting from `windowStart` (per §3's definition). If `windowStart` itself is incomplete
  (e.g. its statement hasn't been imported), the window is empty.
- For every **prior** year, check whether *that* year is complete for the exact same
  relative range — same start-month-of-year through same end-month-of-year (e.g. windowStart=June
  means checking each prior year's June–windowEnd). If yes, compute that year's total over the same
  range too — a real apples-to-apples number, with a real YoY %. If no (that year has no data at
  all for the window, e.g. the user joined in a later month that year, or didn't have an account
  yet), that year is excluded from this comparison — not shown with a fabricated or partial figure.
- Empty window (e.g. it's early January, or shortly after joining, and not even the first month is
  complete yet) → "This Year So Far" shows a "not enough data yet this year" empty state, not a
  zero or a guess.

This mode is named "This Year So Far" in-product, not "YTD-comparable" — plainer, and distinct
enough from "Full Years" that showing two different totals per year (a full-year total and a
This-Year-So-Far total) reads as two clearly separate views rather than two competing numbers next
to each other. They are rendered as two tabs, never merged into one table.

### 4.3 Explicit scope boundary
No custom/arbitrary date-range comparison (e.g., "compare March–July across years"). That's a
materially larger feature — a range picker, its own validation, its own edge cases — with no
demand signal behind it yet. "This Year So Far" covers the actual need (a partial year still gets a
real comparison) without that surface area.

## 5. Entitlement

Reuses the existing `FeatureEntitlement.ADVANCED_REPORTS` key — the same gate already covering
`/trend`, `/top-merchants`, `/category-confidence`, `/top-categories`, `/learning-growth` in
`AnalyticsController`. No new entitlement key.

## 6. API shape

New endpoints on the existing `AnalyticsController` (`backend/src/main/java/com/finora/controller/
AnalyticsController.java`), following its existing `requireAdvancedReports()` gate:

```
GET /api/v1/analytics/multi-year/income
GET /api/v1/analytics/multi-year/spend
GET /api/v1/analytics/multi-year/categories
GET /api/v1/analytics/multi-year/lifestyle-inflation
```

Common response shape (per metric; `categories` nests a `categories: [...]` breakdown per year
instead of a single `total`):

```json
{
  "fullYears": [
    { "year": 2024, "coverageMonths": 12, "isComplete": true,  "total": 1234567 },
    { "year": 2025, "coverageMonths": 12, "isComplete": true,  "total": 1345678 },
    { "year": 2026, "coverageMonths": 3,  "isComplete": false, "total": 320000 }
  ],
  "thisYearSoFar": {
    "windowEndMonth": "2026-02",
    "years": [
      { "year": 2026, "total": 180000 },
      { "year": 2025, "total": 160000 },
      { "year": 2024, "total": 150000 }
    ]
  }
}
```

`lifestyle-inflation` adds `income`/`expense`/`ratio` fields alongside `total` (or replaces it —
finalized during implementation, not a design-level decision). `thisYearSoFar.years` only lists
years that fully cover the window (§4.2) — a year excluded for insufficient coverage is simply
absent from the array, not present with a null.

A new `AnalyticsDto` record set follows the existing file's pattern (`TrendPoint`, `TopCategory`,
etc. in `backend/src/main/java/com/finora/dto/AnalyticsDto.java`).

## 7. Frontend / mobile

Both platforms already have an Advanced Reports surface gated the same way this feature will be:
`frontend/src/pages/AdvancedReports.tsx` (fetches `analyticsApi.trend()` etc. under
`PremiumFeatureGate`) and `mobile/src/screens/AdvancedReportsScreen.tsx`. Multi-Year Comparison
adds a new section to both, following their existing query/chart patterns (`useQuery` +
`react-chartjs-2` on web; the mobile screen's own existing chart component) — not a new page, not a
new route.

## 8. Testing / acceptance

Maps directly to the issue's own acceptance criteria:

- All four views verified against real multi-year account data — needs a seeded test account (or
  fixture) with 2+ years of transaction history, including at least one deliberate statement gap,
  to exercise §3's completeness logic (not just the happy "no gaps" path).
- Confirm no existing free-tier view loses access to any current data as a side effect — this
  feature only adds new gated endpoints; nothing existing changes.
- Backend: `AccountCoverageService.gapsForUser` unit-tested against the gap/no-gap/multi-account
  cases; each `AnalyticsService.multiYear*` method tested for full-year totals, partial-year
  coverage badges, and "This Year So Far" window computation (including the empty-window and
  excluded-year cases from §4.2).
- Frontend/mobile: existing `AdvancedReports`/`AdvancedReportsScreen` test files extended for the
  new section, same pattern as their existing query tests.

## 9. Explicitly deferred (not built now)

- Arbitrary custom date-range comparison (§4.3).
- Onboarding-cohort windows (e.g., "first 90 days") — a real idea raised during design review, but
  a different feature (cohort-relative, not calendar-relative) with no issue tracking it yet.
