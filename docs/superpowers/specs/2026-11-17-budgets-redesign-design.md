# Budgets redesign (web + mobile) — design

Date: 2026-11-17
Status: draft, waiting for Sid's approval
Figma: https://www.figma.com/design/NdqLc0fe006GT3maC7qJUo
(frames: "Web · Budgets · Option A", "Web · states", "Mobile · Budgets · Option A", "Mobile · states")

## Why

The Budgets screen today shows totals and bars, but it doesn't answer the question people open it
with: *am I going to overspend this month, and what can I still spend?* Web and mobile also differ:
web has four stat cards and a doughnut but takes the category as free text; mobile has a proper
category picker but no totals, no days left and a red Remove button on every card.

Success: one look at the top of the screen tells someone whether the month is on course, how much
they can spend per day, and which budget is the problem. Both apps show the same thing.

## Goals

- Pace marker on every bar: where spending should be today if the budget were spread evenly.
- Safe to spend per day, per budget and across all budgets.
- Month-end forecast at the current rate, overall and per budget.
- Budgets grouped by risk, problems first.
- One ring at the top replaces the four web stat cards.
- Change a limit in place without retyping the category.
- Web and mobile show the same numbers, groups and wording.
- Web category input becomes a picker, so a typo can no longer create a budget that never fills.

## Non-goals

- No backend or API change. Everything is computed from the five fields `GET /api/v1/budgets`
  already returns (`id`, `categoryId`, `categoryName`, `monthlyLimit`, `spentThisMonth`).
- Not in this work (considered and set aside 2026-11-17):
  - spending in categories with no budget, and suggested limits from past months — both need
    per-category spend, which today comes only from `/analytics/top-categories`, gated by
    `requireAdvancedReports()` (Plus only);
  - past-months strip per budget — past limits are not stored;
  - budget alerts at 80% / 100% — no budget notification exists yet;
  - an overall monthly cap — needs a new stored field.
- Dashboard budget widgets stay as they are. Both (`frontend/src/pages/Dashboard.tsx`,
  `mobile/src/screens/DashboardScreen.tsx`) only turn red when over and never used the 90% rule, so
  they don't conflict with the new groups.

## Anti-goals

- A forecast or "safe per day" that reads as a promise. It is arithmetic on spend so far; the
  wording says "at this pace", never "you will".
- Hiding a budget that is over. Over budgets always sort first.
- New native code on mobile. The redesign must ship over the air.

## Approaches considered

Layout (Sid picked A on 2026-11-17, after seeing both drawn):

- **A — one-ring hero with a dense list (picked).** The ring answers "am I OK this month" before
  any row is read; the list stays readable with 15+ budgets.
- B — slim header with a grid of budget cards. Lighter, but two-column phone cards cut long
  category names and need a one-column fallback at large text sizes.

Phone row actions (Sid picked the three-dot button on 2026-11-17):

- **Three-dot button on each card opening a menu (picked).** No new library, ships over the air,
  works with screen readers.
- Swipe left to reveal Edit / Remove. Needs `react-native-gesture-handler`, which `mobile/` does not
  have; native code would hold the phone redesign until a store build.

## The numbers

All amounts in rupees; `today` is the device's local date.

```
daysInMonth = last day of the current month
dayOfMonth  = today's day number (1..daysInMonth)
daysLeft    = daysInMonth - dayOfMonth          // today counts as spent time, like today's page
monthFrac   = dayOfMonth / daysInMonth          // position of the pace marker

per budget b:
  spentFrac   = spent / limit                   // bar fill, capped at 100% for drawing
  remaining   = limit - spent
  safePerDay  = remaining > 0 && daysLeft > 0 ? floor(remaining / daysLeft) : 0
  forecast    = round(spent / dayOfMonth * daysInMonth)
  group       = spent >= limit                    -> "Over"
                forecast > limit                  -> "Heading over at this pace"
                otherwise                         -> "On track"

overall: same formulas over the sums of spent and limit.
```

Rules and edge cases:

- `safePerDay` rounds down so following it never tips a budget over.
- Last day of the month (`daysLeft = 0`): show "Last day · ₹X left" instead of a per-day figure.
- Spent exactly the limit (to the rupee shown): the budget is in Over and reads "Limit reached", not
  "₹0 over".
- `limit` is always > 0 (the API rejects 0), so no division by zero there.
- `spent = 0`: forecast is ₹0, budget is On track, bar is empty with the marker visible.
- Sort inside each group: Over by amount over (largest first); Heading over by forecast overshoot;
  On track by `spentFrac` (highest first).
- Rounding for display: whole rupees, `en-IN` grouping, as today's `fmt` / `fmtCurrency`.
- The forecast is a straight line. It overstates months with one big early payment (rent, yearly
  fees). Accepted for now; wording "at this pace" carries that.
- Month boundary: the server works out "this month" in the user's own timezone
  (`BudgetService`: `YearMonth.now(safeZoneId(userId))`); the screen uses the device date. They
  agree for anyone whose device is in their own timezone. For a few hours at a month boundary they
  can disagree; the screen then shows the server's spend against the device's day count. Accepted;
  not worth an API change.

## Web — `frontend/src/pages/Budgets.tsx`

Order, top to bottom (see "Web · Budgets · Option A"):

1. **Header.** "Budgets" + "November 2026 · day 17 of 30"; primary "New budget" button on the right.
2. **Hero card.** Ring: outer arc = overall `spentFrac` (colour by overall group), thin inner arc =
   `monthFrac`, centre "78% spent". Three figures: "Spent of ₹29,000" (amount + "57% of the month
   gone, 78% of budget used"), "Safe to spend" (₹/day + "13 days left · across all budgets"), "At
   this pace by 30 Nov" (forecast; danger ink + "₹X over your budgets" when forecast > total limit,
   otherwise muted "₹X under").
3. **Budget list card**, grouped. Group heading = coloured dot + "Over · 1" etc.; empty groups are
   not shown. Each row: category icon (existing `ICON_COMPONENTS` / `COLOR_HEX`), name, bar with pace
   marker, "₹spent / ₹limit" (limit underlined dotted = editable), status (main line: "₹X over" or
   "₹X / day safe"; sub line: "₹0 left to spend" / "Heading ₹X over by 30 Nov" / "About ₹X by 30
   Nov"), three-dot button.
4. **Legend** under the list explaining the marker and the dotted limit.

Behaviour:

- **Edit in place.** Clicking the dotted limit (or "Edit limit" in the menu) turns it into a number
  input with +500 / +1,000 chips, Cancel and Save. Enter saves, Esc cancels. Save calls the existing
  `budgetsApi.upsert(categoryName, limit)`. Same validation as today (> 0).
- **Row menu**: Edit limit, See transactions, Remove budget (existing `ConfirmDialog` text
  unchanged). See transactions links to `/app/transactions?category=<name>&from=<YYYY-MM-01>&to=<last
  day>` — the params `parseLedgerUrlFilters` (`frontend/src/lib/ledgerUrlFilters.ts`) already reads;
  `category` is a name, resolved to an id by the Ledger.
- **New budget**: dialog with `CategoryCombobox` (already in `frontend/src/components/`) and limit
  field. Replaces the free-text input.
- Keep today's cache invalidations after save/remove (`budgets`, `dashboard-summary`, `onboarding`)
  and the `allSettled` split so a categories failure only drops icons.
- Remove the doughnut and the four `MetricCard`s.
- Loading, empty and error states keep today's behaviour and wording; the skeleton follows the new
  shape (ring + rows).

## Mobile — `mobile/src/screens/BudgetsScreen.tsx`

Order (see "Mobile · Budgets · Option A"):

1. Stack header as today ("Budgets", back to More) with a "+" button on the right.
2. **Hero glass card.** Ring (spent / total in the centre: "₹22,700 of ₹29,000"), inner month ring,
   two tiles ("₹484 / day · Safe to spend", "13 days · Left · day 17 of 30"), forecast line in a
   danger tint when over ("At this pace you reach ₹X by 30 Nov, ₹Y over your budgets").
3. Grouped cards, same groups and sort as web. Card: icon + name, status amount + three-dot button;
   bar with pace marker; foot "₹spent of ₹limit" and the sub line.
4. Hint: "Tap a card to see its transactions. The three-dot button edits or removes."

Behaviour:

- Tapping a card keeps today's drill-through to Transactions (category + this month filter).
- Three-dot button (44pt hit area, its own accessibility label "More actions for Shopping") opens a
  menu: Edit limit, See transactions, Remove budget. Remove keeps today's `AppAlert` confirm.
- Edit limit opens a bottom sheet: limit field prefilled, +500 / +1,000 / +2,000 chips, a hint line
  ("₹7,200 spent so far. At ₹9,000 you can spend ₹138 a day for the next 13 days."), Save limit.
  The menu must be fully closed before the sheet opens — stacked native modals have frozen touches
  on iOS before.
- "+" opens a New budget sheet with the existing `CategoryPickerModal` flow and limit field; this
  replaces the always-visible form card.
- Category icons: `mobile/src/lib/categoryIcons.ts` (`iconNameFor`, `colorHexFor`) with the
  categories list; a failed categories fetch falls back to a neutral icon, never blocks budgets.
- Keep: screen-capture prevention, haptics (success on save/remove, warning on invalid input, error
  on server failure), `useSingleFlight`, pull to refresh, `AnimatedNumber` for amounts, glass
  surfaces with the Reduce Transparency fallback, large-text wrapping.
- Accessibility: each card stays one node, its label read as "Shopping: ₹8,400 spent of ₹6,000.
  ₹2,400 over budget." and, when on track, "… ₹269 a day safe for 13 days." The ring gets a single
  label with the three hero figures.

## Shared logic

The formulas above live in one small pure module per app (web `frontend/src/lib/budgetPace.ts`,
mobile `mobile/src/lib/budgetPace.ts`) with identical tests, so both apps can't drift. Names are the
builder's call.

## Visual tokens

Use existing tokens only. Status colours: bars use the fill colours (success / warning / danger),
text uses the ink variants. Fonts: Manrope for figures and headings, Inter for everything else, as
both apps already load. Dark mode uses each app's existing dark tokens; Figma shows light only (the
Starter plan allows one variable mode).

## Left to the builder

- Component split inside each page.
- Exact ring drawing (inline SVG on web; `react-native-svg` on mobile, already a dependency at
  15.16.1).
- Skeleton shapes for the new layout.
- Test layout, beyond: the pace module is unit-tested at the boundaries (day 1, last day, spent 0,
  spent exactly = limit, forecast exactly = limit), and each screen has render tests for the three
  groups, the menu actions and edit-in-place save / cancel.

## Rollout

Web ships on merge. Mobile is JavaScript only, so it can ship over the air; no store build needed.
