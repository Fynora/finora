# Dashboard: say which dates, and show money not counted as income

Date: 2026-11-10. Status: draft for Sid's approval. Options A and C chosen by Sid on 2026-11-10
(option B, a "Money in" card, was rejected).

## Why

A customer asked why the dashboard showed income in the tens of rupees when his bank statement had
UPI credits in the thousands. Traced on his rows (prod, read-only):

- His statement ran to the 8th of the current month. The dashboard opens on "This Month", so every
  card covered the 1st to the 8th only. The cards said "This Month" and nothing said which days.
- In those days the only income was a quarterly interest credit. Two UPI credits from people, together
  in the five figures, were classed `PERSON_INFLOW` (`FlowClassifier`, counterparty `PERSON`), so they
  were left out of Income. The only sign of them was the grey "N transactions need classification"
  line under the cards.
- The rest of his large amounts were in earlier months, which "This Month" does not show.

Nothing was wrong with the figures. The screen did not explain itself, and support needed three rules
(date range, person credits, rounding) to explain one number. The goal is that a user never needs that
explanation.

## Goals

1. Every Income, Expenses and Net savings card names the real dates it covers.
2. Money received that is not counted as income is on the Income card itself, with its amount, and
   one tap leads to sorting it.
3. Right after an import, the user is asked what the money from people was, while they remember.

## Non-goals

- No change to what counts as income or expense. `FlowClassifier`, `FlowTotals` and the totals stay as
  they are.
- No "Money in" card (option B).
- No change to rounding of displayed amounts.
- No redesign of Quick sort beyond the overlap rule in C3.

## Anti-goals (failure even if it works)

- Counting money from a person as income without the user saying so. Sid's earlier decisions stand:
  a person's credit stays unresolved until the user names it.
- A prompt that comes back every visit. The Done-step card (C) shows once per import; the Income
  card line (A2) is quiet text, not a pop-up.
- A date label that claims more than the data covers (for example "1–30 Nov" when statements stop on
  the 8th).

## Part A: dashboard cards (web and mobile)

### A1. Real dates on the cards

- "This Month" view: the card label names the days covered, for example "Income · 1–8 Nov". The end
  day is the newest transaction date in the reporting month across the user's live accounts. When the
  data reaches the month's last day, the label names the month ("Income · November").
- Range view: preset ranges also show their dates ("Income · Jun–Nov 2026") instead of only
  "Last 6 Months". Custom ranges already show dates (`rangeCardSuffix`).
- Server: `DashboardSummaryDto` gets one new field with the newest transaction date in the reporting
  month (name is the builder's call, for example `figuresThrough`). Preset range labels are
  month-level, built from `DashboardRangeSummaryDto.startDate` and `endDate`, which already exist.
- Several accounts covering different days: the end day is the newest across them. The existing
  "so far" note (`statementBehindNote`) keeps saying which account stops earlier.
- The picker button can keep saying "This Month". Only the card labels change.

### A2. Money not counted, on the Income card

- When `unresolvedInflowCount > 0`, the Income card gets a second line under its amount:
  - "+ ₹15,000 not counted yet" (amount from `unresolvedInflow`, same rounding as the card)
  - the existing reason line from `UNRESOLVED_REASON_LINE` ("Mostly money received from people")
  - a link, "Tell us what it is", to Money Review with the same bounds the banner uses today.
- The separate grey banner under the cards (`unresolved-inflow-banner`, web `Dashboard.tsx`, mobile
  `DashboardScreen.tsx`) is removed: the Income card replaces it. Existing tests that look for the
  banner move to the card.
- Colour: warning ink for the amount line, as in the mockup. Income stays success ink, expenses stay
  danger ink (Sid's earlier decision, see memory "Expense amounts stay red").
- When everything is sorted, the line disappears.

## Part C: ask at the end of an import (web and mobile)

### C1. Where

On the import Done step: web `Import.tsx` summary (where `QuickSortCta` renders) and mobile
`ImportScreen.tsx` Done step (`import-summary-actions`). The card sits above the Quick sort button,
because it changes the headline numbers.

### C2. What it shows

- Heading: "N payments you received aren't counted as income". Body: "What were they? This decides if
  they count as income." The heading does not say "from people": the endpoint returns every
  unresolved credit (people, card credits, cash deposits, unnamed senders) and `UnresolvedSender`
  carries no reason, so "from people" would be wrong for some rows.
- Source: `GET /transactions/unresolved-inflows`, which already groups by sender, given a new
  `statementImportId` parameter so it lists only the rows this import added. (Planning change: the
  statement period was the first idea, but CSV imports carry no printed period and a period would
  also pick up rows from other statements.)
- Up to 3 senders inline, biggest total first. Each uses the existing `InflowKindPicker` and the
  existing "Every payment from X" / "Just this one" choice, saved with
  `PUT /transactions/{id}/inflow-kind`. If there are more than 3, "See all" opens Money Review for the
  same period.
- "Skip for now" hides the card for this import. Nothing is recorded as an answer.
- After a choice: refresh money figures (`useInvalidateMoneyFigures` on web, the mobile equivalent),
  show the same "Saved. Undo" as Money Review.
- Shared code: the sender row and its picker are lifted out of Money Review into one component per
  app, used by both screens, so the two never drift.
- When the period has no unresolved credits, or the request fails, the card does not render. The
  import itself succeeded either way.

### C3. Overlap with Quick sort (my call, check it)

Quick sort already asks about money received (`QuickSortDto.Kind.MONEY_IN`), but it files a
category ("Friend Repayment", "Salary", "Gifts & Donations", "Transfer"). Only "Salary" and
"Friend Repayment" change whether a credit counts as income (`FlowTotals` category roles), so a
user can answer Quick sort and still see the credit as "not counted". Rule: a row that is an
unresolved inflow is asked about once, by the C card (and Money Review), and Quick sort leaves it out
of its `MONEY_IN` questions. Not established yet: how many rows sit in both queues today. The builder
measures that before changing Quick sort and reports the numbers.

## Copy

| Place | Text |
|---|---|
| Card label, partial month | Income · 1–8 Nov |
| Card label, full month | Income · November |
| Card label, preset range | Income · Jun–Nov 2026 |
| Income card, line 1 | + ₹15,000 not counted yet |
| Income card, line 2 | the existing reason line |
| Income card, link | Tell us what it is |
| Done card heading | 2 payments you received aren't counted as income |
| Done card body | What were they? This decides if they count as income. |
| Done card links | See all · Skip for now |

All amounts in this document are illustrative, not from any customer.

## Constraints

- Mobile changes ship in the next store build, never as an OTA update to a live binary.
- The web and mobile cards must show the same dates and amounts for the same account.
- Repository is public: tests and fixtures use synthetic names and amounts only.

## How we know it works

Value-level checks, not only green tests:

1. A seeded account shaped like the case (an interest credit, two UPI credits from people, a small bank
   charge, data ending mid-month): the dashboard shows "1–<day> <Mon>", the Income card shows the
   interest and "+ ₹<sum> not counted yet"; web and mobile agree.
2. On that account, marking one person credit as Income on the Done card raises the Income card by
   exactly that amount and lowers the "not counted" line by the same amount. Undo puts both back.
3. A full month and a preset range show the label rules in A1.
4. An import whose period has no person credits shows no C card. A failed unresolved request shows no
   C card and no error.
5. The C3 overlap: counts of rows in both queues before and after, on the seeded data and on a prod
   read Sid runs.

## Left to the builder

Component structure, the new field's name, exact spacing, and how the label shortens at small widths
and the largest text size on mobile (it must stay readable and not truncate the dates).
