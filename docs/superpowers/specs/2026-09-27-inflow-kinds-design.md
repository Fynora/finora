# Inflow kinds: resolving money not counted as income (Plan 2) — design

Status: design approved in conversation 2026-09-27, pending written-spec review.
Program: financial flow model (`docs/proposals/financial-flow-model.md`). Plan 1 = PR #1773
(flow class, UNRESOLVED, the single totals path). Plan 3 = PR #1788 (own-account transfers).

## Goal

Since Plan 1, a credit Fynora cannot explain is left out of income: money from a person
(`PERSON_INFLOW`) and an unexplained credit on a credit card (`CARD_UNEXPLAINED_CREDIT`). The user
sees only a banner ("N transactions need classification · ₹X not counted as income") that leads
nowhere. Plan 2 lets the user say what that money was, once per sender, and makes every total follow.

## Decisions (from the design conversation)

- The user picks an **inflow kind**. Five are built in: Income, Family support, My own money,
  Paid back to me, Refund. The user can also create, rename, delete and configure their own kinds,
  as with categories.
- Each custom kind carries one setting, **Count this as income? yes/no**, editable later. Every row
  using the kind follows the new setting.
- A choice applies to **every payment from that sender** by default (past and future). "Just this
  one" is available, and a choice made on one row always beats the sender's choice.
- Card credits are covered, with Refund among the choices.
- A choice can be made on **any credit**, not only unexplained ones. That way a credit wrongly
  counted as income can be corrected.

## Current state (read on origin/main, 2026-09-27)

- `FlowClassifier` (VERSION 4) derives the flow class at read time. Its class doc says user
  overrides belong there as an input, not in a cache.
- Only admins can edit `relationships` and `relationship_identifiers`
  (`AdminUserRelationshipController`), and the classifier never reads them. Plan 2 leaves them
  unchanged.
- `Category` has no income/expense type: it holds only name, icon and colour.
- `transactions.counterparty_key` is `vpa:<local-part>` (strong), `name:<token>` (weak, a guess) or
  empty (`CounterpartyIdentity`). It over-splits one sender more often than it merges two, which is
  the safe direction.
- The only current user action that changes the flow of a credit: putting it in the user's own
  Salary category.

## Data

Migration V232 (confirm the number is free on origin/main before adding it):

- `inflow_kinds`:
  - `id`, `user_id` (FK users);
  - `name` (≤ 60, unique per user, case-insensitive);
  - `counts_as_income` (boolean);
  - `built_in` (nullable VARCHAR: `INCOME`, `FAMILY_SUPPORT`, `OWN_MONEY`, `PAID_BACK`, `REFUND`);
  - `created_at`;
  - unique (`user_id`, `built_in`) where `built_in` is not null.
- `sender_inflow_rules`:
  - `id`, `user_id`, `counterparty_key`, `inflow_kind_id` (FK inflow_kinds), `created_at`, `updated_at`;
  - unique (`user_id`, `counterparty_key`).
- `transactions.inflow_kind_id`: nullable FK to inflow_kinds. The choice for this one row.

The five built-ins are created for a user the first time kinds are read or written. Because of the
unique constraint, two parallel first calls still produce exactly five.

Kind rules:

| Kind | Effect on totals | Flow class / reason |
|---|---|---|
| Income | adds to income | INCOME / USER_KIND |
| Family support | adds to income, reported on its own line | INCOME / FAMILY_SUPPORT |
| My own money | neither income nor spending | TRANSFER / USER_OWN_MONEY |
| Paid back to me | neither income nor spending | ADJUSTMENT / PAID_BACK |
| Refund | lowers spending in its month and category, the way an unlinked refund already does | REFUND / UNLINKED_REFUND |
| Custom, yes | adds to income under the kind's name | INCOME / USER_KIND |
| Custom, no | neither income nor spending | ADJUSTMENT / USER_KIND_EXCLUDED |

- Built-ins can be renamed. They cannot be deleted, and their yes/no cannot change.
- A custom kind cannot be deleted while any row or sender rule uses it. The refusal says how many.

## Classification

Order for a credit (`TxnType` not EXPENSE):

1. A paired transfer, a linked refund, or a reversal, as today. A user's kind does not override a
   pairing the reconciliation passes made; the existing undo actions ("not a transfer") handle those.
2. The row's own `inflow_kind_id`.
3. The sender rule for the row's `counterparty_key`, when the key is non-empty.
4. The existing automatic rules (Plan 1 and Plan 3), unchanged.

Debits never take a kind. `FlowClassifier.VERSION` becomes 5. The classifier stays a pure function:
the caller passes the resolved kind (or null). Rows and sender rules are looked up once per totals
call through the existing `FlowTotals` path, not once per row.

## API

- Inflow kinds:
  - `GET /api/inflow-kinds`, `POST /api/inflow-kinds` `{ name, countsAsIncome }`;
  - `PATCH /api/inflow-kinds/{id}` `{ name?, countsAsIncome? }`: `countsAsIncome` on a built-in → 400;
  - `DELETE /api/inflow-kinds/{id}`: a built-in → 400; a kind in use → 409 with the row and sender counts.
- Setting a choice: `PUT /api/transactions/{id}/inflow-kind` `{ kindId, scope: ROW | SENDER }`.
  - SENDER: upserts the sender rule and clears this row's own choice, so the rule applies to it.
  - SENDER on a row with an empty key → 400.
  - A debit → 400.
  - A row that is a paired transfer, a linked refund or a reversal → 400, with a plain message.
- Clearing a choice: `DELETE /api/transactions/{id}/inflow-kind?scope=ROW|SENDER`. The row falls back
  to the next level of the order above.
- Remembered senders: `GET /api/sender-inflow-rules` returns the sender display name, kind and row
  count. `DELETE /api/sender-inflow-rules/{id}` forgets the sender.
- Review list: `GET /api/transactions/unresolved-inflows?startDate&endDate` returns credits whose
  flow class is UNRESOLVED, grouped by sender (display name, count, total, latest date, account),
  sorted by total descending. Rows with an empty key are grouped per row.
- `TransactionDto` gains:
  - `flowClass` and `flowReason`;
  - `inflowKind` `{ id, name, countsAsIncome, appliedBy: ROW | SENDER }`, or null.
- The sender is shown by the name printed on the payment, or by the UPI handle. The raw
  `counterparty_key` never reaches the client: a `name:` key is a guess, not an identity.
- Every endpoint is scoped to the current user. Another user's kind, rule or transaction id → 404.
- `TransactionExplanationService` adds one evidence line: "You marked payments from this sender
  as <kind>" or "You marked this payment as <kind>".
- OpenAPI and the three generated type files are regenerated.

## Screens (web and mobile, matching each other)

- **Dashboard banner** (web `Dashboard`, mobile `DashboardScreen`) and the **Reports** caption link
  to the review screen.
- **Review screen, "Money not counted yet"** (modelled on mobile `CategoryReviewScreen`):
  - one row per sender, sorted by amount, showing name, count, total and latest date and account;
  - tapping a sender opens the kind picker (built-ins, then custom kinds, then "+ New kind…";
    a new kind asks for a name and the yes/no question and is used at once);
  - two actions: "Every payment from {name}" (the default, stating how many credits it changes)
    and "Just this one" (opens that sender's rows);
  - a saved sender leaves the list, the count updates, and a snackbar offers Undo;
  - the empty state reads "Everything's sorted".
- **Transaction detail, every credit:**
  - a "Counts as" row shows the automatic reading (for example "Income · salary") or the user's
    choice, marked "(you, this sender)" or "(you, this payment)";
  - tapping it opens the same picker plus "Clear my choice";
  - hidden on debits; read-only on paired transfers, linked refunds and reversals.
- **Settings → Categorization** (web `CategorizationPane`, mobile `SettingsCategorizationScreen`):
  - **Money kinds:** list, create, rename, delete, and the yes/no toggle for custom kinds;
  - **Remembered senders:** each sender with its kind and row count, and "Forget".
- **Reports:** income broken down by kind, so Family support and custom income kinds appear as
  their own lines.

## Edge cases

- One person under two keys (UPI on one credit, a NEFT name on another) stays two senders, each set
  once. Two people are never merged.
- A sender set to one kind who later sends a different kind of credit: the sender rule applies. The
  user corrects that row with "Just this one" or "Clear my choice". A refund the reconciliation pass
  actually links still wins, by the order above.
- Flipping a custom kind between yes and no changes every row that uses it on the next read. No
  backfill is needed, since totals are derived.
- Mobile change-sync: the new tables and writes to `transactions.inflow_kind_id` must join the
  per-section change stamp, so a choice made on the web reaches the phone.
- The Fyn tool-result cache (`CacheConfig.FYN_TOOL_RESULT_CACHE`) is evicted on a choice write, the
  same way a transaction edit evicts it.
- A soft-deleted transaction keeps its choice. It leaves totals through the existing delete filter.

## Testing

Each new test must fail before its change and pass after it. Synthetic narrations only, with
`111111111111`-style numbers.

- Classifier:
  - the order: transfer, linked refund and reversal come before the row choice, which comes before
    the sender rule, which comes before automatic;
  - every built-in effect;
  - a custom kind with yes and with no;
  - a debit ignores kinds.
- Refund kind: spending in the row's month and category falls by the amount.
- Service:
  - SENDER writes the rule and clears the row choice;
  - SENDER with an empty key → 400;
  - a debit or a paired transfer → 400;
  - deleting a kind in use → 409 with counts;
  - deleting a built-in, or flipping its yes/no → 400;
  - another user's ids → 404;
  - parallel first calls produce exactly five built-ins (integration test on real Postgres).
- Totals: dashboard, range, report, budgets and insights all move together; Family support appears
  as its own income line; the review list drops a sender once it is set.
- Integration: migration, unique constraints, and an API round trip.
- Web and mobile: picker, review screen, detail row, both settings sections, banner link.

## Measurement

A throwaway probe (never committed) stages the real corpus with the real parser and reconciliation.
It sets a kind on every unresolved sender and reports:

- the unresolved count, which must reach 0;
- the income delta per kind;
- confirmation that no row outside those senders changed class.

Every changed row is read. Before the PR: backend `verify`, web and mobile suites, and lint and tsc
on Node 22.

## Out of scope

- Kinds on debits.
- Loan tracking.
- Netting a "Paid back to me" credit against the bill it repays.
- Merging two keys that belong to one person.
- Any change to the admin relationship tables.
