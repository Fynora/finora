# Platform Analytics by period

Status: approved by Sid, 2026-11-18 ("ok build it"), from a mockup with sample numbers. Sid left the
choice of design to Claude ("choose yourself what will best for Admin").

## Why

The page showed all-time totals only, ranked by total spend summed across users with no user
count, and left out every expense row that had no category. So it could not show change, one large
spender could top a list, the unsorted share of spend was invisible, and a merchant only one
customer uses showed that customer's total to every admin.

## What

- **Period picker:** a single month (default: last full month), the last 3 full months, or all time.
  A month or 3-month period is compared with the period of the same length just before it; all time
  has no comparison.
- **Summary:** customers with spend, spend per customer (median of each customer's period total),
  share of spend still to sort, expense transactions.
- **Top categories and top merchants:** ranked by number of customers, then spend. Columns:
  customers, spend, spend per customer (median), change in spend against the previous period.
- **Privacy floor:** a category or merchant from fewer than 3 customers is not listed; its change
  is not shown when the previous period had fewer than 3; the summary's money figures are not shown
  for a period with fewer than 3 customers.
- **Setup answers** ("How users tracked spending before Fynora") move to Activation, which already
  shows those answers by step and uses the same permission.

## Rules

- Same exclusions as before: no duplicates, no linked transfers, no REFUND-status rows, EXPENSE only.
- Customers only: account scope USER and not deleted (the setup-answers count already uses this).
  Staff accounts' own transactions are no longer counted.
- "Still to sort" = expense rows with no category or flagged `needsCategoryReview`, the same flag
  the customer's own "needs review" badge uses. A row filed under "Other" with confidence is sorted.
- Names are still grouped by name across customers' private category and merchant rows.

## Approaches

- **Small fix** (period + user count): cheapest, still no unsorted share or privacy floor.
- **Trend charts per category over 12 months:** with about 60 customers the lines are mostly noise.
- **Period page with per-customer measures (chosen).**

## Calls made while building (Claude's, Sid can change them)

- Minimum of 3 customers per listed row.
- Change shown for spend only, as a percentage.
- API takes `from` (a month, `YYYY-MM`) and `months` (1 to 12); no `from` means all time.
