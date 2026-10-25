# Ask for a card's amount owed when no statement states it

Date: 2026-10-26. Status: draft for Sid's sign-off.

## Why

A credit-card statement with no closing balance (a CSV export, typically) is imported ADDITIVE: the
card's balance starts at zero and adds up the rows. A card almost never starts at zero. Payments at
the start of the file clear the previous bill, whose spends are not in the file, so the summed figure
can be far off. It can even come out negative, which reads as "the card is in credit".

Traced in production on 2026-10-26: one customer's ICICI card (a year of rows, no opening or closing
balance) shows a large credit balance that is almost certainly wrong. Fynora cannot know the true
figure. Only the customer can. `AccountBalanceConvention.looksLikeASignError` already flags negative
card balances, but nothing acts on it.

A production query (Sid, 2026-10-26) found exactly **one** live card with no stated figure, and it is
that customer's card. No older or hand-edited cards fall under the rule below.

## Goals

1. At import review, when a card statement gives no closing balance and the card has no stated figure,
   ask: **"How much do you owe on this card today?"**
2. An existing card with no stated figure shows **"Amount owed not known"** with a way to add it, on
   web and mobile.
3. Until the customer gives the figure, the card's summed balance is **not shown and not counted** in
   any total.
4. Admin user page: coverage with no statements on file reads as unknown, not COMPLETE.
5. Admin balances: the minus sign goes before the ₹ sign.

## Non-goals

- Changing the affected customer's data. They enter the figure themselves.
- Asking when the statement gives a closing balance. That figure already sets the card.
- Asking for savings accounts or wallets.
- Estimating the figure. No guessing from Total Amount Due, minimum due or payments.
- Prompting cards created by hand. Sid, 2026-10-26: only cards with imports.

## Anti-goals (these would count as failure even if the feature technically works)

- A summed-from-zero card figure shown or totalled anywhere with a soft "may be incomplete" note.
  Sid's rule (2026-10-11): unproven amounts are not shown with a softer label.
- A typed figure that later rows, later statements or a statement refresh quietly move away from.
- Asking again once the customer has answered, or asking when a statement already stated the figure.

## Decisions made with Sid (2026-10-26)

- **Skipping is allowed.** The import goes through. The card then shows "Amount owed not known · Add
  it" instead of a number, and is left out of net worth and every total until they enter it.
- **Only cards with imports get the prompt.** Hand-made cards are left alone.

## The rule: when a card's amount owed is "not known"

Derived on the server, never stored. A live `CREDIT_CARD` account is **not known** when all of these
hold:

- no SET anchor (`last_absolute_set_statement_id IS NULL`);
- no typed balance (`balance_typed_at IS NULL`);
- no baseline date (`balance_baseline_date IS NULL`). This date is set when an import creates the
  account with a stated opening balance, and when a balance is typed;
- at least one live statement import on the account;
- none of its live statement imports are `ABSOLUTE` or `UNKNOWN_LEGACY`. An old SET that left no
  anchor is not second-guessed. Production has none today; the condition only stops a future
  misfire.

The rule is derived rather than stored, so it follows every existing path with no new bookkeeping.
Typing a balance, a statement whose closing balance SETs the card, or deleting the only import each
flip it the right way on their own.

Every other account is "known", as today.

## Behaviour

### Import review (web `Import.tsx`, mobile `ImportScreen.tsx`)

Ask when the target is a card and the statement's `closingBalance` is null, and either:

- the import goes to an existing account whose amount owed is not known; or
- the import creates a new card with no opening balance entered. The new-account form already has an
  opening-balance field. When it is filled, the account gets a baseline and is known, so no question.

The question: "How much do you owe on this card today?". It has a ₹ amount field (0 or more) and a
"Skip for now" choice. A short line underneath explains: "This statement doesn't show your balance.
Your card app or latest bill has it."

Multi-account uploads ask once per card section that qualifies.

The confirm request gains an optional `amountOwedToday` (per section). On the server, after the
section's rows are written:

- applied only when the account is a card and the request's `statementClosingBalance` is null;
  otherwise ignored;
- sets `Account.balance` to the figure (positive = owed, per `AccountBalanceConvention`);
- clears the SET anchor; stamps `balance_typed_at = now` and `balance_baseline_date = yesterday` in
  the user's zone; rebases duplicate marks. This goes through the same code `AccountService.update`
  uses for a balance edit, moved into one shared method, so the two cannot drift;
- writes an audit entry with the previous and new balance and the source (import confirm).

Effect, from the existing rules in `RowBalanceEffect` and `BalanceCoverage`:

- this statement's rows were created before `balance_typed_at`, so editing or deleting them later does
  not move the figure;
- a statement refresh re-reads rows as of the original import time, so it does not move it either;
- a later statement for an earlier period is covered by the baseline date, so it does not move it;
- a later statement with rows after yesterday moves it by those rows, as it should;
- a later statement with a closing balance that SETs the card replaces it, as it should.

A negative figure (card in credit) is not taken at review. That rare case goes through the existing
account edit. *My call.*

### Card with "not known" (web accounts page, mobile `AccountsScreen`, dashboard account lists)

- No figure is shown. The card shows "Amount owed not known" and an "Add it" action.
- "Add it" opens the same question: the ₹ field and Save. Save calls the existing account update with
  the balance, which already marks it typed. No new endpoint.
- The card is left out of net worth, total liabilities, credit utilisation and the "payment due"
  notification text (`DashboardService`, `DashboardRangeService`, `NetWorthService` current and daily
  snapshot). Where a total is shown and a card was left out, one muted line says so: "Leaves out 1
  card whose amount owed isn't known."
- `AccountDto` gains `amountOwedKnown` (boolean; always true for non-cards). The dashboard and net
  worth DTOs gain `cardsWithUnknownAmountOwed` (int).

### Admin

- Coverage: `StatementCoverageAnalyzer.CoverageReport.coverageStatus()` returns `NO_STATEMENTS` when
  there are no segments. The admin panel shows a neutral chip "No statements on file" and drops the
  "0d covered · 0d missing" line.
- Balances: one formatter puts the sign first ("−₹1,00,396.53"). Applied to the account balance and
  the coverage gap "balance delta" in `AccountsSection.tsx`.
- An admin account row whose card is not known shows a small "amount owed not known" tag next to the
  stored figure. Admins still see the number. *My call.*

## Delivery

- **PR 1, backend:** the derived rule, `amountOwedKnown`, the totals exclusions and counts,
  `amountOwedToday` at confirm, the shared "balance stated" method, and the coverage status. No
  migration: every column needed already exists (V121, V229, V231).
- **PR 2, web + admin:** the review question, the card state and "Add it", the totals line, and the
  admin coverage and sign fixes.
- **PR 3, mobile:** the review question, the card state and "Add it", and the totals line. Ships in
  the 1.1.0 store build. Never OTA to 1.0.0.

Old clients keep working. They do not send `amountOwedToday` and ignore the new fields. The excluded
totals reach 1.0.0 users through the server alone, which is the correct figure.

## Tests (value level)

Backend integration tests, each asserting the exact `Account.balance` and the totals:

1. Card CSV with no closing balance, new account, `amountOwedToday` = X: balance = X, typed stamped,
   known.
2. Same without the figure: balance not shown in totals, `amountOwedKnown=false`, count = 1.
3. After (1), delete or edit one of that statement's rows: balance stays X.
4. After (1), statement refresh that adds or removes rows: balance stays X.
5. After (1), a later statement with rows after yesterday: balance moves by exactly those rows. An
   earlier-period statement: balance stays X.
6. Card statement **with** a closing balance: client does not ask. The server ignores a stray
   `amountOwedToday`, and the closing balance SETs the card.
7. Existing card with a SET anchor or typed balance: known; no prompt.
8. Savings account: never affected.
9. Legacy or ABSOLUTE import on the card: known.

Web and mobile: component tests for when the question shows (the four cases above), Skip, Save
payload, the card "not known" state and "Add it", and the totals line.

Admin: the coverage chip with no statements, and the negative balance format.

## To confirm in the plan, from the code

- Every confirm entry point carries `amountOwedToday`: single, multi-section, session, and
  send-from-computer (Gate 2b). The trust hold appears to run at staging, before review. If so, a
  held statement is asked when the customer reviews it after release. The plan must show this from
  the code, not assume it.
- Every other place a card balance is read for display or advice: AI insights context, reports, and
  bill reminders. Each gets the same "not known" treatment, or the plan says why not.

## Left to the builder

Exact copy polish within the wording above, component placement on each screen, and how the
derived rule is queried efficiently. One query per user for the set of accounts with live imports and
their modes is enough; it must not be one query per account in list views.
