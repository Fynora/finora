# Advanced Reports redesign

Status: decided 2026-10-22 (Sid delegated the design decisions; the brief asked for working web and
mobile, not a proposal). Three PRs: backend endpoint, web, mobile.

## What it is for

Reports answers "what happened to my money this month?". Advanced Reports answers "what patterns
define my finances, how have they changed, and what should I understand about them?". Anything that
is only a month's summary stays in Reports; Advanced Reports works over a period of months and
always against the period before it.

Recurring payments are not here: the Insights screen already detects and explains them, and a second
copy would disagree with it.

## What the audit found (all from the code on `main`, 2026-10-22)

1. The Period picker drives three panels (top merchants, top categories, international spend) and only
   as one month or all time. Spend trend, multi-year, lifestyle inflation, confidence and learning
   ignore it, and the page said so in a footnote.
2. Spend Trend (`AnalyticsService.merchantTrend`) is spend *with a merchant attached* over six months
   ending at the **calendar** month, not the newest month with data. It is not the Reports expense,
   and for a user whose last statement is old it plots empty months.
3. Top Categories drops uncategorised spend, stops at ten, and keeps the Investments category.
4. Multi-year spend and lifestyle inflation sum `activeSpend` rows, which still contain investment
   transfers. Reports and the Dashboard leave investment transfers out of Expenses
   (`RefundNetting.excludingInvestmentTransfers`). The same year therefore reads differently on the
   two screens. (Proved by a test in this change before it was fixed.)
5. Lifestyle inflation is expense / income per calendar year with no withheld case other than zero
   income. Credits Fynora has not yet called income (`unresolvedInflow`) are not considered, so a user
   with unclassified credits reads a high ratio. The Dashboard's savings rate withholds in that case
   (`SavingsRate`).
6. Category confidence is the unweighted mean of the engine's confidence in each merchant's current
   top category; a category backed by one merchant counts as much as one backed by forty. Learning
   growth counts `LEARNED` and `CORRECTED` audit rows per month. Both describe the categorisation
   engine, not the user's money.
7. Web draws charts, mobile draws bar lists, and the panel order differs.
8. Entitlement is checked per request in `AnalyticsController.requireAdvancedReports()`, and on the
   clients by `PremiumFeatureGate`. The More-menu entry is visible to everyone and opens the upgrade
   prompt. All of that stays as it is.

## Decisions

### One period, month-aligned, ending at the last whole month

A single period picker drives every module on the Overview and Spending tabs: last 3, 6 or 12
months, this year, last year, all time, or a custom run of months.

- The period ends at the newest month with data **if that month is whole**, otherwise at the month
  before. A month is whole by the same three tests Reports uses (`MonthComparisonGate`): not the
  ragged edge of the history, not overlapping a gap between statements, and not behind an account's
  latest statement. A half-finished month is left out of a long-term comparison rather than plotted
  as if it were a normal month, and the page says which month was left out and why.
- "This year" and "last year" are relative to that end month, not to the clock, so a user whose last
  import was in December still sees a meaningful "this year".
- The comparison period is the same number of months immediately before (for this year: the same
  months of the year before; last year: the year before it). All time has none.
- The comparison is shown as a figure only when `DashboardRangeService`'s rule allows it (the earlier
  period lies inside the history and holds at least three transactions) and no statement gap touches
  either period. Otherwise the response carries a reason and the clients explain it.
- Categorisation diagnostics are the one place that ignores the period, because the engine's
  learning history is not a period of the user's money. The tab says so in its first line.

### Definitions: the same as Reports, not new ones

Income, expense and net come from the same code Reports and the Dashboard range summary use
(`ReportService`'s totals: reportable rows, refunds netted, investment transfers left out, income
only when `FlowTotals` calls it income). The period totals are computed over the whole period, as
`DashboardRangeService` does, so this page and the Dashboard agree on the same range. Monthly figures
are computed per month; in the rare case that refunds cross months the months can differ from the
period total by that refund, and the clients never add the months up themselves.

- Savings rate: `SavingsRate`, withheld with its reason when income is zero or unclassified credits
  exceed income. Per month and for the period.
- Spend-to-income: expense / income, shown per year only when the same savings-rate rule allows it
  and the year has income. It is described as "how much of income went on spending", not as proof of
  lifestyle inflation. The old "lifestyle inflation" name is dropped for that reason.
- Categories: by name, refund-netted per category and floored at zero (as Reports), investment
  transfers left out so they add up to Expenses.
- Merchants: purchases only (a refund is not a purchase), as the old Top Merchants did. Spend with no
  merchant attached is reported as a share, so the ranking says how much of the spending it covers.
- Unusual transactions: a purchase of at least three times the median of that category's other
  purchases in the period and the twelve months before it, at least five of them to compare with, and
  at least 5% of the period's spending. The thresholds are a product choice; the language says
  "larger than usual for", never "wrong".

### Insights

Built on the server from the figures above with fixed templates, so web and mobile say the same
thing. An insight carries its figures, the comparison period and the filter that opens the
transactions behind it. Nothing is invented: an insight that its data cannot support is not
produced. At most five.

### Tabs

Reference mockup from Sid (2026-10-22): a header with a period picker, a "Compare with" picker and
Export; eight tabs; a KPI row (income, expenses, net cash flow, average monthly spend, savings rate,
each against the earlier period); income vs expenses by month with a net line and a table view; top
categories; top merchants; spending trend by category; payment methods; on mobile a net-cash-flow
header, three KPIs and "Key insights" cards. This is built, with these differences:

- Five tabs, not eight: **Overview** (KPI row, income vs expenses, key insights, savings),
  **Spending** (categories, category trend, payment methods, unusual transactions, international),
  **Income** (income by source and by month, how steady it is), **Merchants** (ranking, frequency,
  concentration), **Category insights** (the engine's diagnostics, quiet, in plain words). On mobile
  the last two sit under "More", as in the mockup.
- **Recurring** is not a tab (Insights owns it). **Financial health** and **Trends** are not tabs:
  savings rate is a measure on Overview and trends are a section of Spending.
- The mockup's "Most of your spending was on essentials" insight is not made: nothing in the data
  marks a category as essential, and a guessed split would be an invented figure.
- Category bars use the theme's own graphite/cream (`primary`), not the mockup's gold (decision of
  2026-10-16, see Colour).
- Payment methods reuse the report-time classifier (`PaymentMethodClassifier`) over the whole
  period, with the same rule as the monthly card for when it is too unidentified to show.
- "Compare with" offers the previous period and the same period a year earlier. For a 12-month
  period they are the same window, so the picker offers one.
- Export (CSV on both, PDF on mobile as Reports does) comes after the screens.

### Colour

The app already has the tokens: graphite is `primary` / `fixed-dark`, cream is `primary-light` /
`fixed-light`. Web has no brass token and Sid decided on 2026-10-16 not to add one, so web uses
`primary` for emphasis and selection and `success` / `danger` ink for changes. Mobile already
carries `brass` (strokes) and `brassInk` (text) and uses them for insight markers; Advanced Reports
uses them the same way. Changes are never shown by colour alone: they carry an arrow and a sign.

## API

`GET /api/v1/analytics/advanced?range=LAST_12_MONTHS` (also `startMonth` / `endMonth` for CUSTOM).
Entitlement-gated like its neighbours. Additive: no existing endpoint changes shape.

Old endpoints (`top-merchants`, `trend`, `top-categories`, `international`, `multi-year/*`) stay,
because the admin portal reads the same service. Multi-year spend and lifestyle now leave investment
transfers out (finding 4), which is the one change to an existing meaning, made to match Reports.

## Not done / backend still required

- Per-category budgets or targets, essential-versus-discretionary spend and income-source
  consistency over time: no grouping or targets exist in the data model, so none are shown.
- Payment methods over time (a month-by-month series): only the period's split is shown.
