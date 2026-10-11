# Mobile Transactions screen redesign

**Date:** 2026-10-11
**Status:** design, waiting for the owner's review. No code is written.
**Screen:** `mobile/src/screens/LedgerScreen.tsx` (the Transactions tab).

## Goal

The Transactions tab should look like it belongs to the redesigned dashboard (#2106, #2114) and
should give the list more of the screen. A person opens this tab to find or scan transactions;
today the first transaction starts more than halfway down, its title is raw bank text, and every
row carries tags that say nothing.

Success, in things that can be checked:

1. The first transaction row starts in the top half of the screen on an iPhone 17 Pro at the
   default text size. Today it starts at 58% of the screen height; the approved drawing measures
   46%.
2. Nothing scrolls visibly underneath the title.
3. Every behaviour listed under "What does not change" still works, proven by the existing tests
   (changed only where a control moved) and by a walk on the simulator.
4. The screen holds up at the `accessibility-large` text size, in the dark theme, and with Reduce
   Transparency on.

## What the owner decided (2026-10-11)

- **Layout: option A, "Balanced"**, chosen from a picture of three options: a slim month card, the
  search box, an All / Income / Expense switch, and one Filters button that holds the rarely used
  filters.
- **Picture now, Figma later.** The Figma connector is locked by the Starter plan's call limit
  (tested 2026-10-11; the reset date is not established). The design was drawn as a picture with
  the app's own colours, type sizes, card shadow, fonts, icons and backdrop image, and approved
  from that. It is copied into the Figma file (`t3Bapfm0kCVpfbSIfMr5U1`) when the connector works
  again. Until then the "Design reference" table below is the binding record.
- Standing decisions carried over from the dashboard: expenses red and income green; everything
  glass; Android keeps elevation where iOS draws a shadow.

## What was measured before designing

On an iPhone 17 Pro simulator (iOS 26.5), default text size, the owner's own account:

- The first transaction row starts at 58% of the screen height. Above it: the title, the month
  card, the search box, one row of filter chips and two date boxes.
- After one scroll, the month card's text is visible under the title. **Cause not established.**
  The title is laid out above the list, not over it, so the list drawing above its own top edge is
  unexpected. The plan's first task traces it; nothing is "fixed" before that.
- In the first 200 rows of the list:
  - 196 titles are the bank's own narration (a payment rail, a reference number, a cut-off name).
    Median length 50 characters, longest 154; the row shows about 23.
  - All 200 rows also have a `merchant` value, and none of those is a narration. Median 8
    characters. 90 of the 200 are exactly eight characters taken from the narration, because the
    bank itself cuts a payee's name to eight characters there, so some of these names end mid-word.
  - Every row shows a review tag. On 123 of 200 it is "Reviewed" or "Categorized", which are
    the normal state; on 74 it is "Needs Review"; 3 are also "Recurring". 44 rows carry a match
    tag as well (transfer, investment, refund).
  - Days hold a median of 2 rows, 7 at the 90th percentile, 12 at most.
- This is one account. How other accounts are distributed is not known.

## Scope

In: the Transactions tab's main screen: title area, month card, search, filters, the list, its
loading, empty and error states, at ordinary and large text sizes, light and dark.

Out, stated so it is not mistaken for done:

- The details sheet, the add and edit sheets, and the category, transfer, source, explanation and
  bank-correction pop-ups keep their current look.
- No change to what is fetched, to filtering or sorting on the server, or to the web Ledger.
- No new filter and no removed filter.
- The page backdrop and the tab bar are shared by every screen and stay as they are.

## Design reference (binding)

All values are existing tokens in `mobile/src/theme` unless marked new. Sizes are points.

| Element | Value |
|---|---|
| Side padding | `spacing.ml` (20) |
| Gap between blocks in the header area | `spacing.ms` (12) |
| Screen title | `typography.screenTitle`, `ink` |
| Count under the title | `typography.bodyS`, `mutedInk` |
| Round button (add; search in the pinned bar) | 44 by 44 touch target, glass, icon 22 |
| Pinned bar | height 54 under the status bar, glass, title `typography.cardTitle` |
| Month card | glass card, radius `radius.xxl` (20), padding 14 by 16, card shadow |
| Month card heading | `typography.eyebrow` left, `typography.caption` right, both `mutedInk` |
| Month card figure | `typography.numberM`, `ink`; label `typography.labelS`, `mutedInk`; `DeltaChip` |
| Search field | height 48, fully rounded, glass, icon 18, text `typography.bodyM` |
| Type switch | height 44, fully rounded glass track, padding 4; selected segment `primary` fill with `onPrimary` text; others `mutedInk`; `typography.labelS` |
| Filters button | height 44, fully rounded, glass, icon 16, `typography.labelS`, count badge 18 high in `primary` / `onPrimary` |
| Active filter chip | height 32, fully rounded, `primaryLight` fill, `primary` edge and text, `typography.labelS` |
| Day heading | `typography.eyebrow`, `mutedInk`; day total `typography.labelS` in `successInk` or `dangerInk` |
| Day card | glass (the list-row variant), radius `radius.xxl` (20), padding 2 by 16, card shadow on iOS, elevation 2 on Android |
| Row | padding 12 top and bottom, gap 12, hairline `border` divider between rows of one day |
| Row avatar | `MerchantLogo`, 40 |
| Row title | `typography.labelM`, `ink`, one line |
| Row second line | `typography.bodyS`, `mutedInk`, one line |
| Row tag | `typography.caption`, fully rounded, padding 2 by 8, 5 above |
| Row amount | `typography.numberS`, `successInk` for income, `dangerInk` for expense |
| Filters sheet | slides up, glass, top corners `radius.hero` (28), padding 20 |
| Sheet status chip | height 36, fully rounded; selected `primary` fill with `onPrimary` text |
| Sheet primary button | height 50, fully rounded, `primary` fill, `typography.labelM` in `onPrimary` |

## The screen, top to bottom

### Title area

- "Transactions" as the screen title, with the count under it: "392 transactions", "1
  transaction". The count follows today's rule exactly: blank while the first load is running and
  blank when the first load failed with nothing on screen.
- One round add button on the right (today's "Add transaction").
- The line "All your financial activity in one place." is removed.
- The title, the count and everything down to the list scroll away with the list.
- **Pinned bar.** Once the title has scrolled out of view, a 54 point glass bar appears under the
  status bar with "Transactions", a search button and the add button. It covers what scrolls
  beneath it. The search button scrolls the list to the top and puts the cursor in the search box.
  The bar fades in and out over 150 ms; with Reduce Motion on it appears and disappears at once.

### Month card

- One glass card: the period on the left (`periodTitle` from `useDashboardKpis`, written as that
  hook gives it), the comparison on the right (`deltaLabel`, shown only when a change figure
  exists).
- Two columns, Income and Expenses, each a label, a change chip and the figure. A column without a
  change figure shows no chip.
- Same data as today (the dashboard's figures, shared cache key), same rule for hiding the card
  when there is no summary. It does not follow the filters, as today.

### Search

- The same field with the same placeholder and the same spoken name, restyled as a glass pill.
- Typing, the 300 ms delay, and a search arriving from another screen behave as today.

### Type switch and Filters button

- All / Income / Expense becomes a three-part switch. Each part keeps its spoken name ("Filter:
  all", "Filter: income", "Filter: expense") and its selected state.
- **Filters** opens the sheet below. When any of the sheet's filters is on, the button shows how
  many (a date range counts as one) and its spoken name says so: "Filters, 2 on".

### Active filters

- One removable chip per filter that is on in the sheet: the status by its label, the date range
  ("1 Sep 2026 to 30 Sep 2026", "From 1 Sep 2026", "Until 30 Sep 2026"), and "International".
  Removing a chip turns that filter off.
- The chip for a filter that came from another screen (a chart, a budget, an insight) is this same
  row, with today's label and today's clearing behaviour, including clearing a search it brought.
- No chip, no row.

### The list

- Each day is a heading and one card. The heading is the day ("Today", "Yesterday", or
  "Wed, 30 Sep", with the year added when it is not the current year) and the day's total on the
  right, coloured as today. Which rows count toward the total does not change.
- Inside the card, one row per transaction, hairline dividers between rows.
- **Row title:** the merchant name; the bank narration when there is no merchant; "Transaction"
  when there is neither. The narration stays in the details sheet, in the delete confirmation and
  in search.
- **Second line:** the category, then who was on the other side in its short form ("Person",
  "Business", "Bank", "Government") when known. No date: the heading says it.
- **Tags**, on a third line that exists only when there is a tag, in this order:
  1. the match tag, when there is one (Duplicate, Transfer, Refund, Reversed, Investment,
     Superseded);
  2. Bank Correction, Needs Review, Recurring, each when true;
  3. International, when the statement marked the row so.
  "Reviewed" and "Categorized" are not shown on rows. They stay in the details sheet.
  Labels are written as they are today, without capital-letter styling.
  Colours: Needs Review and Reversed in the warning pair, Bank Correction and Duplicate in the
  danger pair, Refund in the success pair, the rest in the neutral pair (`border` fill,
  `mutedInk` text).
- **Amount** on the right, signed and coloured as today. While a row is being deleted the amount
  is replaced by the spinner, as today.
- No arrow at the end of the row. The whole row opens the details sheet; long-press asks to
  delete; both as today.

### Filters sheet

- A sheet in the pattern of the details sheet (`AppModal`, glass surface, safe-area padding).
- **Status:** the same eight choices as today's chips ("Any status", "OK", then the six match
  labels), one selected at a time, wrapped over as many lines as needed. Spoken names unchanged
  ("Filter by status: Transfer").
- **Date range:** the existing `DateField` twice, From and To, with their existing Clear links.
- **International only:** a switch, spoken as today ("Show only international transactions").
- **Clear all** turns off status, date range and international. It does not touch the search, the
  type switch, or a filter that came from another screen.
- Filters apply as they are changed, as today. The button at the bottom closes the sheet and reads
  "Show 21 transactions" with the live count, or "Done" while the count is loading or failed.

## States

| State | What shows |
|---|---|
| First load | Title with a blank count, the header blocks, then one card holding eight skeleton rows |
| First load failed | "Couldn't load your transactions." and "Try again", as today, in a card |
| Loading more | The spinner under the last card, as today |
| Loading more failed | "Couldn't load more transactions." and "Try again", as today |
| No transactions at all | Today's sentence, in a card |
| Filters match nothing | "No transactions match these filters.", as today, in a card |
| Pull to refresh | As today |
| An action failed (category, delete, unmark) | Today's red message, above the list |

## Large text

At a text size of 1.3 times and above (`useLargeFontScale`), following the dashboard:

- A row stops being a single line: the avatar is hidden, the title and the second line wrap
  without a limit, tags wrap, and the amount moves to its own line under them.
- The month card's two columns stack.
- The type switch and the Filters button stack, each full width.
- The pinned bar's title is one line and stops growing at 1.3.
- The sheet scrolls.

Checked by walking the whole screen at `accessibility-large` on the simulator, not at one step
above default.

## Dark theme, Reduce Transparency, Android

- Every colour is a theme token, so the dark theme follows. Each text and fill pairing that is new
  on this screen (tags on the day card, the count badge, the selected segment, the active chip) is
  added to the existing contrast test (`glassContrast.test.ts`) in both themes.
- Reduce Transparency: the glass surfaces fall back to their solid form through `GlassSurface` and
  `useGlassSurfaceStyle`, as elsewhere.
- Android: no `boxShadow`; day cards and the month card keep an elevation, as on the dashboard.

## Screen reader

- A row is still one button. Its spoken name starts with the same title a sighted user sees, then
  income or expense and the amount, the category, the date (kept here, since the heading is a
  separate element), who was on the other side in its full form, the match tag's explanation, and
  the tags shown on the row. "Reviewed" and "Categorized" are no longer spoken, matching the row.
- Day headings are headings.
- The pinned bar's buttons are named "Search transactions" and "Add transaction".
- Every control keeps a 44 point touch target.

## What does not change

Search and its delay. The three filters and their server parameters. The date range and its
priority over a range that came from another screen. Arriving from a chart, a budget, an insight
or an import, and the chip that shows and clears it. Paging, pull to refresh, the cache key and
the dashboard's prefetch. Day grouping and which rows count in a day's total. Tap for details,
long-press to delete, and every action in the details sheet. The getting-started checklist credit
after 1.5 seconds. Screen-capture protection. Navigation tracking.

## Changes to what a person reads, called out

These are content changes, not only looks. All seven are visible in the options picture. Items 1
to 4 and 6 were also listed in words when the owner chose option A; items 5 and 7 were not, so
they are called out here for the review of this document:

1. The row title is the merchant name, not the bank narration.
2. Rows no longer show the date.
3. Rows no longer show "Reviewed" or "Categorized".
4. Status, date range and International move into the Filters sheet.
5. The line under the title is removed and "392 total" becomes "392 transactions".
6. The arrow at the end of each row is removed.
7. A day heading reads "Wed, 30 Sep" instead of "Wednesday, 30 Sep 2026"; the year appears only
   when it is not the current year. "Today" and "Yesterday" stay.

## Code shape

`LedgerScreen.tsx` is 1,189 lines. The redesign moves its presentation into small files and leaves
state, queries and handlers where they are:

| File | Purpose |
|---|---|
| `mobile/src/lib/ledgerRows.ts` | Pure functions: `rowTitle(t)`, `rowTags(t)`, and grouping into one list item per day. `groupTransactionsByDay` and `statusBadges` keep their current exports and behaviour for their other callers. |
| `mobile/src/components/ledger/LedgerTitleBar.tsx` | The scrolling title block and the pinned bar |
| `mobile/src/components/ledger/LedgerMonthCard.tsx` | The month card (replaces the inline `LedgerMonthSummary`) |
| `mobile/src/components/ledger/LedgerControls.tsx` | Search, type switch, Filters button, active filter chips |
| `mobile/src/components/ledger/LedgerFiltersSheet.tsx` | The sheet |
| `mobile/src/components/ledger/LedgerDayCard.tsx` | A day's heading, card and rows |

**The list.** One list item per day: its heading and its card. A card is then one view with one
shadow and clean corners, instead of rows pretending to be a card. The cost is coarser
virtualisation: a day with very many rows is one tall item. Measured days hold at most 12 rows; an
account with far busier days was not measured. The plan includes a scroll measurement with a
synthetic day of 100 rows, and a card is split into continuation cards only if that measurement
shows a problem.

**Glass in the list.** Day cards use the list-row glass style the rows use today (tinted fill and
edge, no native blur per card), because a native glass view per card in a long list is the
expensive kind. The month card, the pinned bar and the sheet are single surfaces and use
`GlassSurface`.

## Testing

- Unit tests first for the pure functions: title choice, tag order and omission, day items.
- Component tests for each new file: what it shows, spoken names, large-text layout, both
  platforms where a style differs.
- `LedgerScreen.test.tsx` keeps passing. Tests that reach status, date range or International
  open the sheet first; nothing else in them changes. A test that
  asserted a title by narration asserts the merchant, with a separate test that the narration is
  used when there is no merchant.
- Contrast tests for the new pairings, both themes.
- Full mobile suite, typecheck, lint, the Android bundle and the three repository guards, on
  Node 22.
- Simulator: default and `accessibility-large`, light and dark, the first-row position measured
  against success criterion 1, and the scroll cost measured before and after for the app's own
  threads.

## Risks and things not yet known

- Why the list draws above its top edge today (to be traced first).
- Scroll cost of a shadow per day card in a long list (to be measured; the dashboard's shadows
  measured at no cost on this simulator).
- A day with very many rows as one list item (to be measured).
- Merchant names cut to eight characters by the bank will read as clipped titles. Making those
  names whole is a server question and is not part of this work.
- Not verifiable here: iOS below 26, a physical phone, Android on a device.
- This ships in the next store build with the rest of the redesign, never over the air to 1.0.0.
