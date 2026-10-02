# Person flows on the dashboard — design

Date: 2026-10-02. Status: approved by Sid 2026-10-02.

## Problem (measured on a real test account, not assumed)

A six-month PNB savings import on a tester's account, read from production by the owner:

| Flow | Dashboard before this change |
|---|---|
| Debits to people, category Personal Transfer (about 90 rows, a few lakh) | expense |
| Credits from people the user filed as Friend Repayment (about 50 rows, more than the debits) | unresolved, not income |
| Credits from people, category Personal Transfer (about 20 rows) | unresolved, not income |
| Credits from people in a category the user created (3 rows) | unresolved, not income |

Result: counted income of a few hundred rupees against lakhs of expenses, and a savings rate with
six digits before the percent sign.

Two mechanisms produce this, traced in `FlowClassifier`:

1. A credit from a person is resolved only by an inflow kind (Plan 2) or the Salary category. The
   category the user puts the credit in is ignored, so a user who files money as "Friend Repayment"
   still sees it as "needs classification".
2. `PAID_BACK` maps to `ADJUSTMENT`: removed from income but never offsets spend. Lending is
   counted as spending forever, even once repaid.

## Decisions (Sid, 2026-10-02)

- Money paid back reduces expenses, like a refund (delivered by per-person matching, see §2).
- A category resolves a credit only when the user chose it (manual pick, user rule, learned
  pattern). A category the app guessed does not.

## Design

### 1. Category answers the inflow question (backend, `FlowClassifier`)

Precedence, highest first: reconciliation (transfer/refund/reversal), chosen inflow kind, then the
Friend Repayment rule, then today's automatic rules (the Salary rule keeps its existing place).

The category rule applies only when the category is the user's choice (`categoryIsTheUsersChoice`:
manually set, or decision source MANUAL / USER_RULE / LEARNED_PATTERN):

| User's category on a credit | Reading |
|---|---|
| Salary | INCOME / SALARY (unchanged) |
| Friend Repayment | ADJUSTMENT / PAID_BACK: not income, not unresolved, does not yet lower spend (below) |
| Any other system category | unchanged (automatic rules decide) |
| A category the user created | unchanged in this PR; PR 2 asks once "does this count as income?" |

`FlowClassifier.VERSION` 6 -> 7.

### 2. Paid back lowering spend: deferred to per-person matching

Built first as "a repayment lowers the whole Personal Transfer category", then measured on the
tester's data and withdrawn before merge:

- Each month floors at zero, so repayments arriving in a later month than the lending lowered the
  six-month figure but not the monthly ones: the two disagreed by tens of thousands of rupees.
- Credits from the user's own accounts filed as Friend Repayment would have cancelled real spending.
- Matching by person was not possible yet: only a small fraction of repayments found a payment to the same
  counterparty key, because one person was split across several keys (wrapped UPI ids, linked-account
  suffixes, ids cut before the "@") and the user's own accounts were not recognised.

Sid's decision for the follow-up: match each repayment to payments to and from the same person
(lending and borrowing, either order), in the payment's own month and category, so monthly and range
figures agree. It is built on the person-recognition fix (PR #1884).

### 3. Savings rate only when meaningful (backend DTOs + web + mobile)

`SavingsRate.of(income, expense, unresolvedInflow)`: `savingsRatePct` is null, with
`savingsRateGateReason`, when income is zero (`NO_INCOME`) or when unresolved inflow is larger than
counted income (`UNRESOLVED_EXCEEDS_INCOME`). Equal is still shown. Web and mobile show "—" and a
caption instead of a percentage. The health score's savings component keeps its score; only its
explanatory sentence follows the gate.

### 4. Net worth label (web)

The web range card shows net worth (assets minus card dues), labelled "Balance". Label becomes
"Net worth". Mobile's "Total Balance" is `currentBalance` (liquid balance), already correctly
named, and is unchanged.

## Out of scope (separate items)

- A credit card bill paid from savings while the card's own purchases are also imported: possible
  double count, needs measuring first.
- PR 2: ask-once "counts as income?" for user-created categories.
- Health score weighting when the savings rate is withheld.

## Verification

- Unit tests for every row of the table in §1, the offset and floor in §2, the gate boundaries in §3.
- Full backend `verify`, web and mobile suites, lint and type-check on Node 22.
- Local end-to-end through the real API on a throwaway database.
- After deploy, the owner compares the dashboard with the expected effect on the test account.
