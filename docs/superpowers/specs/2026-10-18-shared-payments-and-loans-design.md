# Shared payments and loans — design

Date: 2026-10-18. Status: approved by Sid 2026-10-18.

Builds on the 2026-10-02 person-flows design (`2026-10-02-person-flows-dashboard-design.md`), whose
§2 decided that money paid back should lower spending, matched per person, in the payment's own
month, and deferred the build. This is that build, widened to bills shared with other people.

## 1. Intent

Fynora should count what a user actually spent. Today, when people pay a user back, the user still
carries the whole amount as their own spending:

- A user pays the full rent to the owner and flatmates send their parts. Reports, Insights and budgets
  count the full rent as the user's spend, and each flatmate's credit lands in "needs classification".
- A user pays a group dinner and friends send their share the next day: the same.
- A user lends a friend money and it comes back in parts. The lending counts as spending forever; the
  repayment only leaves income ("Paid back to me" / Friend Repayment = `ADJUSTMENT / PAID_BACK`, which
  `FlowTotals.offsetsSpend` deliberately does not net).

It must work for every user and the situations they meet (§3), not one household. Sid's direction
(2026-10-18): "make our product this in general for all users with different scenarios".

### What was measured (production, read-only, 2026-10-18)

Counts only; no names, ids or amounts left the database. Nine users have transactions, seven have
person-to-person rows, so these are **shapes, not proportions**.

- **Users already say what a credit is, by category.** Most person credits carry a category the user
  set by hand: Friend Repayment (dozens of rows, almost all manual), and also Rent and Food (all
  manual), plus user-made categories for family money and loans. No inflow *kind* was chosen on any
  person credit: users answer with categories.
- **A same-amount loan round trip is rare.** About 150 person credits: only about a third have any
  earlier payment to the same person within 6 months, and only 3 came back for exactly that amount.
  Larger-than and smaller-than are both common: money comes back in parts, or one sum settles several
  things.
- **Most "Friend Repayment" credits have no earlier payment to that person.** The money the friend is
  repaying was mostly spent elsewhere (a restaurant, a ticket, rent): they are **shares of a bill**,
  more often than loans.
- **Shares arrive after the bill.** Food shares: several senders, all within a day, equal amounts.
  Rent shares: three senders, mostly after the bill, up to 11 days later, unequal amounts.
- **Today those shares count against the user.** A person credit the user filed under Rent or Food is
  `UNRESOLVED / PERSON_INFLOW` (`FlowClassifier.inflow`): it never lowers the bill.
- **Nearest-bill-by-date picks the wrong bill.** A naive "nearest Rent debit" match attached senders
  to the wrong rent payment. Links must be confirmed by the user the first time.
- **Weak identities rarely match.** About 38% of person rows carry a weak key (`cut:` or `name:`);
  credits on `cut:` keys almost never matched a payment.

## 2. Goals, non-goals, anti-goals

**Goals**
1. A credit that is someone's share of a bill lowers that bill, in the bill's month and category.
2. Money lent is not spending while it is owed; money borrowed is not income; repaying either is
   neither.
3. One balance per person: who owes the user, and whom the user owes.
4. The app suggests links from evidence; the user confirms once; confirmed patterns repeat on their own.
5. Works backwards over history: a rule set today also links past months.
6. Every link can be undone, and the figures go back exactly.

**Non-goals (this design)**
- Typing in splits for bills that are not in the user's statements (Splitwise-style manual ledger).
- Cash that never touches a statement, beyond "paid in cash" to close a balance.
- Sending anything on the user's behalf. "Ask for it" builds a UPI payment link the user shares
  themselves.
- AI anywhere in matching (it is the next project, and a guessed link changes someone's spending).
- The website. It follows in its own PR, as Insights did.

**Anti-goals (failure even if it "works")**
- A link the user did not confirm, directly or through a rule they set, changing their spending.
- Treating family money as debts.
- Prompting a user who never splits anything; repeated reminders about the same person.
- A "who owes you" list that reads as shaming.

## 3. Situations

| # | Situation | Shape | In this design |
|---|---|---|---|
| 1 | User pays a shared bill in full, others send their parts (rent, electricity, wifi) | share → bill | Yes |
| 2 | Group spend: trip, dinner, cab; friends send their share, often unequal | share → bill | Yes |
| 3 | A friend used the user's card and paid it back | share → the card purchase | Yes (bill is a card row) |
| 4 | Employer repays a work expense, sometimes several bills in one credit | share → several bills | Yes (one credit may link to several bills) |
| 5 | A rent deposit comes back months later | share → the deposit payment | Yes, via "Something else" (outside the suggestion window) |
| 6 | User lent money | balance | Yes |
| 7 | User borrowed money | balance | Yes |
| 8 | User sends their share to the flatmate who pays the owner | the debit is the user's own Rent | Yes (category on the debit; no link needed) |
| 9 | Settling up: a friend covered dinner, the user sends their part | the debit is Food | Yes (same as 8) |
| 10 | A share arrives before the bill (trip advance) | share → later bill | Yes (the picker also lists bills up to 14 days after) |
| 11 | One payment settles many small shared costs | share → several bills, or balance | Yes (several bills, or "they owed you") |
| 12 | Bill on a card, shares into savings | share → bill on another account | Yes (links are per user, not per account) |
| 13 | A flatmate moves out or the rent changes | rule stops matching / new rule | Yes (rules match a person + a bill's identity, not an amount) |
| 14 | More comes back than was lent (interest, rounding) | balance flips | Yes: the excess is owed back to them, or "keep it" (§5) |
| 15 | Lending through a card EMI | — | Later: needs the EMI-to-purchase link (EMI double-count decision) |
| 16 | Advance to household help, recovered from wages | — | Out: no repayment ever arrives |
| 17 | Couples who settle household spend monthly | — | Covered only as 11 (several bills) or a balance; no household view |
| 18 | Family money | — | Never a loan unless the user marks one (existing Family support kind) |

## 4. Concepts

- **Share.** A link from one credit to one bill (an expense row), with an amount. A credit can link to
  several bills (amounts sum to at most the credit); a bill can have several shares.
- **Your share of a bill** = the bill minus its shares, floored at zero. This is what every spend total
  counts. The bill's full amount stays visible on the bill.
- **Person.** A `counterparty_key` that `CounterpartyIdentity.identifiesOnePayee` accepts. Weak keys
  (`cut:`, `name:`, gateway keys) can take one-off links but never a rule or a balance of their own:
  the UI says "this sender" and does not group them.
- **Balance.** Per person, from loan entries:
  `lent − paid back to the user − settled in cash − let go` and the mirror for borrowing. Positive: they
  owe the user. Negative: the user owes them.
- **Split rule.** "Shares from this person against this bill, every time." Matches a sender's credit to
  a bill with the same identity: the bill's category plus its payee key (when that key identifies one
  payee) or, for a payee with a weak key, the recurring item the bill belongs to (`RecurringService`
  groups by merchant name). A rule never matches
  by amount.
- **Repayment rule.** "Money from this person always goes toward what they owe." Reuses
  `SenderInflowRule` with the built-in Paid back kind, plus applying it to the balance.

## 5. How the figures change

| What the user confirms | The credit | The debit | Month and category |
|---|---|---|---|
| Credit is a share of bill B | Leaves income and "needs classification"; not a row in any total | B counts at B − shares | B's month, B's category |
| Debit to person P is **Lent** | — | Leaves spending; P's balance + amount | — |
| Credit from P is **paid back** | Leaves income (as today, `PAID_BACK`); P's balance − amount | — | — |
| Lent amount **paid in cash** | — | Stays out of spending; balance closes | — |
| Lent amount **let go** | — | Becomes spending (category Gifts & Donations), in the month it was lent | Lent month |
| Credit from P is **Borrowed** | Leaves income; balance − amount (the user owes P) | — | — |
| Debit to P **repays a borrowing** | — | Leaves spending; balance + amount | — |
| More paid back than owed | The excess flips the balance: the user owes P | — | — |

Rules that follow from it, stated so a builder does not have to choose:

- **The share is counted in the bill's period**, as a linked refund is (`RefundNetting` class comment):
  a closed month's spend can go down when a share arrives later. Monthly and range figures agree.
- **A share never takes a bill below zero.** A link's amount is capped at what is left of the bill;
  the part of a credit that does not fit stays unlinked (and is offered as "they owed you", §6).
- **Reports, Dashboard, Insights, Budgets and exports all read the same netting.** Today
  `RefundNetting.from` builds expense id → refunded total from refund legs, and `RefundNetting.reportable`
  drops refund legs. Shares join both: `from` adds share links to the per-expense total, `reportable`
  drops a credit that has any share link (it leaves income in full; a remainder that fits no bill is
  answered with "they owed you", §6 D). The callers today: `DashboardService`, `ReportService`, `AnalyticsService`, `InsightsService`,
  `BudgetService`, `CategorizationService`, `StatementImportService` (verify each; a caller that builds
  `RefundNetting` from a month window must load share links across all time, as refunds already are).
- **Lent and repays-a-borrowing debits** are not spending, like a transfer; they are not income either.
  New `FlowReason`s for them; `FlowClassifier.VERSION` bumps.
- **Unconfirmed credits read as today.** A Friend Repayment credit nobody links stays
  `ADJUSTMENT / PAID_BACK` (out of income, not lowering spend); a person credit in a spending category
  stays `UNRESOLVED` until linked or answered.
- **Precedence.** A reconciliation pairing (own-account transfer, refund, reversal, card payment) still
  outranks everything. A confirmed share or loan entry outranks the inflow kind and category rules.

## 6. Suggestions: when the app asks

Every suggestion is a row in Insights "To look at" (§7). None changes a figure until confirmed. Each
uses only deterministic signals: the person, the user's category, dates, amounts, and words in the UPI
note (`rent`, `share`, `trip`, `dinner`, …). No AI, and names and narrations never leave the server.

**A. "Is this a share of a bill?"** for a person credit, when any of:
- the user filed it under a spending category (Rent, Food, Travel, …) — the strongest signal measured;
- the user filed it under Friend Repayment and there is no earlier payment to the same person;
- its UPI note has a share word.

The bill picker lists expenses in the 14 days before the credit, then up to 14 days after, same category
first, nearest first; "Something else" opens the full list (situation 5).

**B. "Lent, or paid for something?"** for a payment to a person, when it is not a repeating payment,
not already a share or a bill with shares, not to a person the user marked family, and nothing has come
back from that person within 30 days. Asked once per payment.

**C. "Paying back what you lent?"** for a credit from a person whose balance says they owe the user,
or who received a payment from the user in the 180 days before it that was never answered. Confirming
the second form records that earlier payment as Lent and this credit as paid back, in one step.

**D. "They owed you" / "Borrowed?"** for a person credit with no bill and no balance, when the user
answers A with "No, it's not a share".

A's three answers read "Yes, link it to a bill", "No, it's not a share" and "Leave as it is"
(reworded 2026-10-23 from "Yes, pick the bill", "Not a share" and "Got it", which did not say what
each one does; "Leave as it is" is A's dismissal, the "Got it" below).

**E. "Settle it"** for a balance owed to the user that has been open for 60 days with nothing back:
*Ask them* (a UPI request link built from the user's own UPI id, which Fynora does not store today:
asked once the first time and saved to the profile; the user shares the link themselves), *Paid in cash*,
*Let it go*, *Keep waiting*. Once per person per open balance; *Keep waiting* silences it until the
balance changes.

**F. "Every month?"** after a share is confirmed, when the same sender sent a credit near the same bill
identity in at least one other month: *Every month* creates a split rule and links the past months it
matches; *Just this one*.

**G. "Always do this for <person>?"** after a paid-back is confirmed: creates the repayment rule.

**Quiet by default.** A user with no person rows sees none of this. A dismissed suggestion ("Got it")
is stored with the existing insight acknowledgements (V277) and never asked again for that row.

## 7. Screens (mobile)

Approved as mockups on 2026-10-18 (share flow and loan flow).

**Insights**
- *To look at* gains the rows of §6 A–G.
- *Coming up* gains money expected in: for each split rule, the sender's usual share around its usual
  day, marked late once past.
- A new section, **With people**: owed to you, you owe, one line per person; tap for the person page.
  It is reached by a fifth question, "Who owes me?".
- The month line counts the user's share (it reads Reports' figure, so it follows automatically).
- *Paid regularly*: a bill with a split rule shows the user's share and "split with N".
- **Running high:** the prior-months average for a category starts at the first month that category has
  spend in the user's history. Today a month before a payment first appears counts as zero
  (`InsightsService`, `PRIOR_MONTHS_WINDOW = 4`, divided by the number of prior months).

**Bill (transaction details sheet)**
- A bill with shares shows paid, the user's share, and each share (person, date, amount), with Undo.
- Any expense offers "Split this" (pick the credits from the person list) and, for a payment to a
  person, "Lent".
- Any person credit offers "Share of a bill", "Paid back", "Borrowed".

**Person page** (from With people or a transaction's person)
- Balance, timeline of lent / back / shares, the rules for this person (remove a rule here), "Family"
  switch (a family person never gets B or E).

## 8. Data

New tables (names are the builder's; the next free migration is V279 at the time of writing — check
`origin/main` before adding):

- `share_links`: user, credit transaction, bill transaction, amount, source (`USER` | `SPLIT_RULE`), rule
  id, created at. Unique on (credit, bill).
- `split_rules`: user, sender key, bill identity (category + payee key, or recurring item), created at,
  removed at.
- `loan_entries`: user, person key, transaction (null for cash and let-go entries), direction (`LENT`,
  `PAID_BACK`, `BORROWED`, `REPAID`, `CASH_SETTLED`, `LET_GO`), amount, created at.
- `person_settings`: user, person key, family flag, last "settle it" prompt.

Constraints every builder must keep:
- A transaction deleted, superseded or marked duplicate drops out of every link and entry (as refund
  links do); a re-import that re-creates the row does not resurrect a link without a rule.
- Every per-person rule and balance requires `identifiesOnePayee`.
- Account deletion purges, and data export includes, all four tables (as V277 did).
- The mobile change-sync stamp: a new section or the transactions section moves when links change, and
  every new query key is classified in `invalidateFinancialData.test`.
- Bulk updates to `transactions` bump `version` (`ChangeStampBulkWriteGuardTest`).

## 9. Delivery

- **PR A (server):** tables, classification and netting changes, suggestions, rules, endpoints, the
  running-high average fix, backfill of suggestions over history (no figures change until confirmed).
- **PR B (mobile):** Insights rows and sections, details sheet actions, person page. Ships with the
  next binary; never published as an OTA to 1.0.0.
- **PR C (website):** later.

## 10. Verification

- Unit tests for every row of §5 and every trigger in §6, including the boundaries: a share equal to
  the bill, one paise over, a credit linked across two bills, a bill shared across two months, a share
  before its bill, a balance going to exactly zero and past it.
- One integration test per figure-reading service proving it reads the shares (a test that fails when a
  caller still builds netting from refunds alone).
- Full backend `verify`, mobile suite, lint and type-check on Node 22.
- After deploy, a read-only production measurement: for each user who confirms links, Reports' month
  spend equals the sum of their categories and the dashboard agrees, by value, not by count.

## 11. Calls made in this design (not discussed; check while reading)

- Suggestion window: 14 days before a credit, then 14 after; "settle it" after 60 days; "lent?" after
  30 days with nothing back; loan look-back 180 days.
- A share is capped at what is left of the bill; the rest of the credit stays unlinked.
- Let go counts under Gifts & Donations, in the month lent.
- Weak-identity senders get one-off links only, never rules or balances.
- The user's UPI id for "Ask them" is asked once and stored on the profile.

## 12. Decided by Sid (2026-10-18)

- Free for now: no plan gating.
- Situations 15, 16 and 17 (§3) stay out of this build.
