# Learning Engine page: more detail, drawn as charts

Date: 2026-11-18. Sid asked for "more details" on the admin Learning Engine page and to "visualize
properly", and left the choice of what to show to the builder. He approved the mockup and the
approach on 2026-11-18 and asked to build without a separate review of this document.

## What the page answers

1. Is the engine getting better? The correction rate (corrections out of all confirmations) for the
   last 30 days against the 30 days before, and month by month.
2. How much of the sorting does learning do? The share of transactions added in the last 30 days
   whose category came from a learned pattern, beside every other source.
3. Where is it wrong? The most corrected merchants (with their usual change) and the most common
   category changes.
4. Who was affected? Recent learning events, newest first, each linking to the customer's page.

## Data it rests on

- `merchant_category_learning`: learned (merchant, category) pairs, per account.
- `merchant_learning_audit`: one row per LEARNED, CORRECTED, UNDONE, MERGED or RESET event, with the
  account, merchant, old and new category and time. CORRECTED means the confirmed category was not
  the merchant's top category going in.
- `transactions.decision_source`: what picked a transaction's category. A category a customer set or
  changed becomes MANUAL, so what picked it first is not kept.

Not stored: whether a learning event came from import review or a Ledger edit. Not shown.

## Decisions

- Customer accounts only: internal and admin accounts are left out, as on the activation and reading
  quality pages. This changes the existing tiles' totals too. (Builder's call; Sid handed over the
  "what".)
- Merchants are per account, so the most-corrected list groups them by name, ignoring case and outer
  spaces. Category changes are grouped by category name.
- Two endpoints, as approved: `GET /admin/learning/stats` (counts and names only, no customer
  identities; not audited as a customer-data read) and a new paged
  `GET /admin/learning/events` (customer emails; audited as a many-customer read). Both need
  MERCHANT_MANAGE, as before.
- Every figure is a grouped query in Postgres. The old trend loaded the whole audit table into memory.
- The monthly trend keeps quiet months as zeros and now also counts undos and resets.

## Approaches considered

1. One bigger stats call plus a paged events call (chosen): follows the existing pattern; two calls.
2. One call per section: each section fails on its own, but six endpoints for a page with small
   numbers today.

## Measured

On made-up load data (50,000 accounts, 1,000,000 learning events, 2,000,000 transactions) each stats
query took 64-440 ms and an events page about 300 ms, with no new index. No migration.
