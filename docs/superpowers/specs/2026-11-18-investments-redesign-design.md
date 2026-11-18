# Investments redesign (web + mobile) — design

Date: 2026-11-18
Status: approved by Sid on 2026-11-18
Figma: https://www.figma.com/design/NdqLc0fe006GT3maC7qJUo?node-id=12-27
(page "Investments", frames: "Web · Investments", "Web · states", "Mobile · Investments",
"Mobile · states")

## Why

The Investments screen today is a set of separate boxes: three stat cards (two of them about net worth
and liabilities), a doughnut with one slice per holding, a net worth chart, an always-open add form, a
flat list and a list of payments. It doesn't answer what people open it for:
*how much do I have invested, where is it, and what happens next?*

Values also go stale. The screen offers only Add and Delete, so changing a value means deleting the
holding and adding it again.

Success: one look at the top says the total and how it is spread; deposits show what matures next;
updating a value takes one action. Web and phone show the same numbers and wording.

## Goals

- Portfolio total with a mix bar by type, replacing the doughnut.
- Holdings grouped by type, each with its share of the portfolio.
- Update a holding's value in place.
- "Coming up": the next deposits to mature and recurring deposit progress.
- "Money going in": 12 months of SIP and broker payments by month.
- Net worth kept, but moved to one small card at the bottom.
- Web and phone show the same numbers, groups and wording.

## Non-goals

- No backend or API change.
- Not in this work, because the data does not exist today:
  - gain, loss or returns: no purchase cost or units are stored;
  - live prices or automatic valuation;
  - "last updated" on a holding: the API does not send a date for it;
  - matching SIP payments to the holding they bought.
- Dashboard investment and net worth widgets stay as they are.

## Anti-goals

- A profit, loss or return figure the app cannot back up.
- Advice. The screen states facts ("46% in mutual funds"), never "too concentrated" or "you should".
- A confident ₹0 when data failed to load.
- New native code on mobile. The phone part must ship over the air.

## Approaches considered

Sid approved the first on 2026-11-18, after seeing it drawn.

- **Portfolio first (picked).** Total and mix at the top, then what is coming up and money going in,
  then holdings. Works for everyone, including people with no deposits.
- Coming up first. Strong for deposit holders, but anyone without deposits opens on an empty section.
- Net worth first. Repeats the Dashboard and keeps the screen about something other than investments.

## The numbers

All amounts in rupees, shown in whole rupees with `en-IN` grouping. `today` is the device's local date.

**Type groups**, from `investmentKind`, in this display order:

| Group | `investmentKind` values |
| --- | --- |
| Mutual funds | `Mutual Fund` |
| Deposits | `FD`, `RD` |
| Stocks | `Stocks`, `Demat` |
| PPF, EPF and NPS | `PPF/NPS`, `PPF`, `EPF`, `NPS` |
| Other | `Other`, missing, or any other value |

Values are matched without regard to case or surrounding spaces. Empty groups are hidden. Inside a group,
holdings sort by value, largest first. (Figma's sample data shows the shorter label "PPF and NPS".)

```
total          = sum of every holding's balance
groupTotal     = sum of the group's balances
share          = value / total, for holdings and groups, when total > 0
assetShare     = total / netWorth.totalAssets, capped at 100%
```

- A share that rounds to 0 but is above zero reads "<1%".
- The mix bar draws only groups with a positive total. It is hidden when `total <= 0`.
- `assetShare` is hidden when net worth failed to load or `totalAssets <= 0`.
- The holding count in the header counts every holding.

**Coming up** (hidden when it has nothing to show), at most 3 rows:

1. Deposits with `maturityDate >= today`, soonest first: name, date, "today" / "tomorrow" / "in N days",
   and `maturityAmount` when known.
2. Then recurring deposits with both instalment counts, `installmentsTotal > 0` and
   `installmentsPaid < installmentsTotal`: "12 of 24 paid" with a progress bar, clamped to 0–100%.

A holding appears once; a recurring deposit that qualifies for both shows its maturity row with the paid
count beside it. `maturityDate` is parsed as a calendar date, never through UTC. A deposit past its
maturity date is not in Coming up; its holding row reads "Matured 3 Jan 2026".

**Money going in** uses the same rows as today's activity card: type `EXPENSE`, category "Investments",
excluding `DUPLICATE` and `SUPERSEDED`, 100 rows a page, at most 10 pages.

- Period: the current calendar month and the 11 before it, so every bar except the last is a whole month.
- Shows the total, "About ₹X a month", the payment count and 12 monthly bars. The tallest month fills the
  bar height; a month with no payments shows an empty slot.
- "About ₹X a month" is the total divided by the months from the first month with a payment through the
  current month, rounded. Earlier months may only lack an imported statement, so they are not counted.
  A month with no payments after the first one still counts. (Changed from total / 12 on Sid's decision,
  2026-11-18: two months of ₹10,000 SIPs read as about ₹1,667 a month.)
- If the page cap is hit, the total reads "At least ₹X".
- "See payments" opens the transactions list filtered to Investments for that period.
- The period picker and the list of the latest 8 rows are removed.

## Web — `frontend/src/pages/Investments.tsx`

Order, top to bottom (see "Web · Investments"):

1. **Header.** "Investments", "6 holdings · 76% of your assets", primary "Add holding" button.
2. **Portfolio card.** "Total value", the amount, mix bar, and a legend of group, amount and share.
3. **Two cards side by side.** Coming up and Money going in. With no Coming up, Money going in takes the
   full width. They stack below the width where they no longer fit.
4. **Holdings card.** Group headings with totals. Each row: name, terms line, share bar and percent, value,
   three-dot menu.
5. **Net worth card.** Net worth, assets, liabilities and the trend line.

Behaviour:

- **Row menu:** Update value, Delete holding. Reuse the Budgets `RowMenu` keyboard behaviour.
- **Update value** dialog: current value prefilled; a live line says "Up ₹15,000 from the last value you
  saved" (or "Down"; nothing when unchanged). Saves with
  `accountsApi.update(id, { name, accountType: 'INVESTMENT', balance })`. The server leaves the type and
  deposit terms untouched. Hidden for a holding with `primarySource === 'ACCOUNT_AGGREGATOR'`, whose value
  comes from its bank link.
- **Add holding** dialog replaces the always-open form: name, current value, type. The stored type values
  stay `Mutual Fund`, `Stocks`, `FD`, `PPF/NPS`, `Other`. The server's limit message (100 holdings) is shown.
- **Amount input:** same rules as Budgets limits: greater than zero, digit grouping accepted, at most 2
  decimals, no larger than the server allows, each with a plain message.
- **Delete** keeps a confirm: "Delete this holding?", "\"Name\" will be removed from your net worth. This
  can't be undone."
- After a save or delete, the change shows at once and the list reloads without blanking. An older response
  must not overwrite a newer one. Invalidate `dashboard-summary` as well.
- Accounts and net worth load independently, so one failing leaves the other usable.

## Mobile — `mobile/src/screens/InvestmentsScreen.tsx`

Same sections and order as web, one column (see "Mobile · Investments"). "+" in the header row opens Add
holding. The legend is a list under the mix bar. Holding rows show name, share or terms, value and a 44pt
three-dot button.

- Update value, Add holding and the three-dot menu are bottom sheets, built like the Budgets sheets:
  scrollable, clear of the status bar, and a drag closes the keyboard.
- Only one sheet is visible at a time.
- Keep: screen-capture prevention, haptics, single-flight saves, pull to refresh, skeletons, glass surfaces
  with the Reduce Transparency fallback, large-text wrapping, and "—" when a figure is unknown or offline.
- A failed refresh keeps the data already on screen.

## Net worth card (both)

Net worth (danger colour when negative), "₹X assets · ₹Y liabilities", the existing "cards left out" note
when it applies, and the trend when there are at least 2 points; otherwise "Building your net worth trend.
Check back in a day or two."

"Save today's snapshot" is removed: `NetWorthSnapshotSweepService` already saves one every day.

## States

- **Loading:** skeleton in the new shape.
- **No holdings:** "No investments yet", "Add a holding, or import a deposit statement.", Add holding.
  Money going in still shows if payments exist.
- **Holdings failed to load:** "Could not load your investments." No total, mix or empty state.
- **Net worth failed:** the net worth card says so; everything else stays.
- **Money going in failed:** that card says so; everything else stays.
- **No payments found:** "No SIPs or broker payments found in your statements yet."

## Accessibility

- Mix bar: one label listing each group and its share.
- Monthly bars: one label with the period, total and monthly average.
- Each holding row reads as one sentence, with its menu as a separate control.
- Progress and share bars are not the only way a figure is shown.

## Shared logic

Grouping, shares, Coming up, monthly buckets and amount parsing live in one pure module,
`investmentSummary.ts`, byte-identical in `frontend/src/lib` and `mobile/src/lib`, with the same tests in
both and a mobile test that fails if the copies differ (the `budgetPace.ts` pattern).

## Visual tokens

Existing tokens only. Fonts: Manrope for figures and headings, Inter for everything else. Group colours come
from the app's category palette: blue, teal, orange, violet and gray. Dark mode uses each app's dark tokens;
Figma shows light only.

## Left to the builder

- Component split, and whether the Budgets sheet and row menu move to a shared folder.
- Exact skeleton shapes and the width at which the two cards stack.

## Rollout

Web ships on merge. Mobile is JavaScript only, so it can ship over the air.
