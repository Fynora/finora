# Mobile Insights redesign: what's worth knowing

Status: approved by Sid in chat, 2026-10-16 ("go with your pick, 1 2 5 and 4", then "looks right, go ahead").
Scope: the mobile app's Insights tab (`mobile/src/screens/InsightsScreen.tsx`), plus one small server
addition for "Got it". The website's Insights page follows in its own PR, not this one.

## Why

Measured on the Android emulator with a real account on 2026-10-16:

- Two of the five tabs are placeholders. Recurring says "A dedicated Recurring tab is coming soon";
  Trends says "Spending trends over time are coming soon."
- Overview, Spending and Income repeat Reports, which since #2217 is three tabs with the same names
  (`ReportsScreen.tsx`: "Overview (how the month went), Spending (where the money went) and Income
  (what came in)").
- The Overview tab shows two different spending totals for the same month: "Expenses" (from the
  insights pipeline, which leaves out investment transfers) and the category donut's total (from the
  dashboard summary, which keeps them). The gap equals the Investments slice.
- "Categories" reads 12 on Overview and the Spending tab says 11.
- The Spending tab prints the month as "2026-09" and amounts with Western grouping ("₹117,162")
  inside server-built sentences; its heading runs into the month picker.
- The same "spending is N% higher" message appears at the top and the bottom of Overview.
- A card bill shows as "Credit Card bill spend was 5265% more than your recent average": the server
  only leaves a card payment out when reconciliation has linked it to a card account
  (`InsightsService.pipeline`, `ccPaymentFromTransactionIds`).

Insights should answer "what should I notice?" and leave "what happened?" (totals, charts) to Reports.

## Goals

One scrolling screen, no tabs, about the month of the user's latest data. Top to bottom:

1. **Month line.** The month the figures describe, with a month picker (today's picker, reused), and
   one plain sentence: total spent and the change against the month before, in Indian grouping. No
   chart.
2. **Questions (idea 4).** Three plain questions that jump (scroll) to their section on this screen:
   "What needs a look?", "What's coming up?", "What do I pay regularly?". A fourth, "Where did my money
   go?", opens Reports for the same month. Questions whose section is empty are not shown.
3. **To look at (idea 1).** A short list of things that changed, each one actionable:
   - **A new repeating payment nobody has answered** (`RecurringItem.state === 'NEEDS_ANSWER'`): shows
     the existing `RecurringQuestion` inline so it can be answered right there. Answering clears it.
   - **A repeating payment whose amount moved out of its saved range** (`state === 'AMOUNT_CHANGED'`,
     and `recurringApi.changedAmounts()`): shows the existing `RecurringQuestion` in its
     amount-changed form, plus "Got it".
   - **A category running high**: from `InsightsData.movers`, a category whose `pctChange` is at least
     the server's own 15% significance threshold and whose `current` is at least ₹500 above
     `priorAverage`; at most three, largest rupee difference first. Wording: "Food is ₹3,100 above
     your usual month (₹9,400 vs ₹6,300)". No percentages over 100% in the wording; the rupee gap
     carries the meaning. Card bill payments never appear here (see Constraints).
   - Every row opens the Ledger filtered to those transactions (the existing drill-through), except the
     inline question, which is answered in place.
   - "Got it" hides a row for good, on every device (server-stored, below).
   - When the list is empty: one quiet line, "Nothing new to look at for September 2026."
4. **Coming up (idea 2).** Repeating payments from `recurringApi.list()` with their `nextEstimate`
   and `averageAmount`, soonest first, plus "Still to go out this month: ₹X" summing the ones dated in
   the current calendar month.
   - A `nextEstimate` already in the past (data stops at the last statement) reads "expected around
     1 Oct" with a muted note under the section: "Your latest statement ends 26 Sep. Upload October's
     to see if these were paid." It is never shown as if it were still ahead, and never counted in
     "still to go out".
5. **Paid regularly (idea 5).** Every repeating payment with its cadence label ("Monthly",
   "Weekly", ...), amount, and a marker on any whose latest amount is above its average. Header: the
   monthly total and the yearly total. Monthly equivalents: Weekly × 52 / 12, Biweekly × 26 / 12,
   Monthly × 1, Quarterly / 3. Called "Paid regularly", not "Subscriptions", because detection finds
   rent, EMIs and SIPs as well as subscriptions.

A footer link "See the full month in Reports" opens Reports for the same month.

### The tab dot

The Insights tab shows a dot while "To look at" has any row. It counts the same rows the section
shows, from the same queries (shared React Query keys, so no extra requests when the screen is open).

### "Got it" (server)

A new table and two endpoints:

- `insight_acknowledgements (id, user_id, kind, item_key, created_at)`, unique on
  `(user_id, kind, item_key)`. `kind` is `CATEGORY_HIGH`, `AMOUNT_CHANGED` or `CARD_STATEMENT_MISSING`.
  - `CATEGORY_HIGH` key: `<category name>|<yyyy-MM>`. A new month that runs high again shows again.
  - `AMOUNT_CHANGED` key: `<merchant, trimmed and lower-cased>|<latestDate>`. A later payment that
    moves again shows again.
- `GET /insights/acknowledgements` returns the user's keys; `POST /insights/acknowledgements`
  `{kind, itemKey}` adds one (idempotent). Both scoped to the signed-in user.
- The client hides acknowledged rows; the server does not filter `/insights` or `/recurring`, so the
  Dashboard and web are unaffected.
- The table joins the account-purge and data-export paths the way `recurring_dismissals` does
  (`AccountPurgeSweepService`, `DataExportService`), and the mobile change-sync stamp if that is what
  keeps other screens fresh for user-owned tables (memory: "new tables join the stamp").
- Migration number is taken at build time from `origin/main` (latest today is V275), never guessed.

## Non-goals

- No change to Reports, the Dashboard, or the website screens in this work, apart from the server
  no longer calling a card bill a mover (their insight sentences come from the same server). The
  Dashboard keeps its own upcoming-payments section.
- No AI wording. The existing note "rule-based statistical observations ... not an AI-generated
  assistant" stays, once, small, at the bottom.
- No "usual vs now" range chart (idea 3) and no "money with people" (idea 6): both need new server
  calculations. Later.
- No weekly recap: data arrives per statement, so "this week" would usually be empty.

## Anti-goals (failure even if it works)

- A row that states a number the user can't reach by tapping it, or that disagrees with the Ledger
  or Reports for the same month.
- A past date presented as upcoming.
- A card bill presented as a spending spike.
- Any count, total or month on this screen that differs from another place on the same screen.

## Constraints

- **Card bills.** A card bill payment is moving money to the card, and its size is a whole month of
  card purchases paid at once, so it must not appear as "running high". A payment is a card bill
  when any one of these holds (Sid, 2026-10-16: "either one"):
  - it is linked to a card statement (`ReconciliationService`'s `CC_PAYMENT` link; already left out
    of the insights pipeline, so it can never be a mover);
  - its category is named "Credit Card bill" (case-insensitive; a user-made category, not seeded);
  - its description matches the server's existing card-bill patterns in `BankActivityCategory`
    (`CARD_BILL_PAID`: "cc billpay", "credclub", "cred club"; or net banking's "ib billpay" with a
    masked card number). Reuse those patterns through one shared predicate; never copy them.

  Card bills stay in spending totals (Sid chose to keep them as spending), but are never a mover:
  the server leaves them out of `movers` and the mover sentences, which also corrects the
  Dashboard's preview of those sentences and the web page. This is a deliberate server change.
- **Card statement missing.** Why the observed account's September card bills never linked was
  established in #2256's session: the Axis statements for those months were never uploaded (only
  Feb–Mar and Mar–Apr 2026 exist). #2256 widens the match window to start at the statement date. For
  card bills that still have no linked statement, `InsightsData` gains one field for the reporting
  month: `unlinkedCardBills: { month, total, count } | null` (card bills by the rule above that have
  no `CC_PAYMENT` link). "To look at" shows it as one row: "You paid ₹X in card bills in September
  with no card statement uploaded for it. Upload it to see what you bought." Tapping opens Import.
  Sid, 2026-10-16 (decided in the card-bill session, confirmed here): name the card when it is
  known, else say "a credit card". Known means every card bill that month points to the same card
  of the user's: its narration carries exactly that card's last 4 digits (read as reconciliation
  reads them), or it carries no card digits and the user has exactly one card. Digits naming no
  card of theirs leave it unknown. `unlinkedCardBills` therefore also carries
  `cardName: string | null` and `cardLast4: string | null`.
  "Got it" kind `CARD_STATEMENT_MISSING`, key `<yyyy-MM>`.
- Amounts in Indian grouping via the app's own formatter; months as "September 2026" / "Sep 2026",
  through `withSep` (#2251) wherever a short month is written.
- Every figure on screen comes from one source per concept: spending totals from `InsightsData`
  (the pipeline that leaves out investment transfers), never mixed with the dashboard summary.
- Mobile ships with the next binary (1.1.0). Never published as an OTA to 1.0.0.
- Accessibility at fontScale 2 (the jest default), screen-reader labels on every row, as on the
  Ledger; glass surfaces per `glassMigration.test.ts`; skeletons on first load and haptics on actions
  as elsewhere (#2208, #2213).
- `trackNavigation` on every navigation the screen starts (hygiene check
  `check-nav-tracking-coverage.py`).

## Decided by Sid

- Direction B: one list of what's worth knowing, built from ideas 1, 2, 5 and 4.
- Mobile first; web later.
- "Got it" stored on the server so it follows the user across devices.
- Card bills do not count as "running high".

## Builder's calls (marked so Sid can check them)

- The ₹500 floor and the cap of three on "running high" rows (cheap to tune).
- "Paid regularly" as the section name instead of "Subscriptions".
- Questions that jump within the screen rather than opening new screens.
- The empty-state and past-date wording above.
- Drop the Recurring and Trends placeholder tabs entirely; drop the Income tab (Reports has income).
- Remove the category donut from Insights (Reports has it), which also removes the two-totals bug
  from this screen. Whether Reports shows the same two-totals gap is not checked here and is
  reported separately, not fixed in this work.

- The month picker changes the month line and "running high" only. "Coming up" and "Paid regularly"
  describe the repeating payments detected now, whatever month is picked, and say so in their
  section header when an older month is picked ("Based on all your statements").
- An amount change can arrive both as a `RecurringItem` with `state === 'AMOUNT_CHANGED'` and as a
  `changedAmounts()` entry for the same payee. Show it once per payee (trimmed, lower-cased merchant).

## Kept from today's screen

- The coverage-gap warning when the month overlaps a missing statement (`coverageCaveat`).
- A second tap on the Insights tab scrolls back to the top.
- The onboarding-checklist credit for opening the tab, and the "Smart Insights" usage count that
  `MySubscriptionScreen` reads; entitlements and gating unchanged.
- Dismissing a repeating payment as "not recurring" (`recurringApi.dismiss`), on "Paid regularly" rows.

## Left to the builder

Component split, query wiring, test layout, exact spacing within the existing glass and type tokens.
