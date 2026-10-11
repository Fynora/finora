# Mobile Transactions screen redesign

**Date:** 2026-10-11
**Status:** design, waiting for the owner's approval. No code is written.
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
2. Scrolled content never touches the title's text: it ends under a solid bar.
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
- The list is cut off exactly at the bottom of the line under the title, with no gap and no
  edge, so a card sliding up meets that line's letters. (An earlier reading, that content showed
  through under the title, was wrong; see "Follow-up measurements".)
- In the first 200 rows of the list:
  - 196 titles are the bank's own narration (a payment rail, a reference number, a cut-off name).
    Median length 50 characters, longest 154; the row shows about 23.
  - All 200 rows also have a `merchant` value, and none of those is a narration. Median 8
    characters. 90 of the 200 are exactly eight characters taken from the narration, because the
    bank itself cuts a payee's name to eight characters there, so some of these names end mid-word.
    The stored value is always lower case and at most four words (see "Review of this design").
  - Every row shows a review tag. On 123 of 200 it is "Reviewed" or "Categorized", which are
    the normal state; on 74 it is "Needs Review"; 3 are also "Recurring". 44 rows carry a match
    tag as well (transfer, investment, refund).
  - Days hold a median of 2 rows, 7 at the 90th percentile, 12 at most.
- The 200 loaded rows held 6 repeats (see the paging defect under "Follow-up measurements"), so
  they are 194 different transactions. The counts above include the repeats.
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
- **Pinned bar.** A glass surface runs from the very top of the screen, behind the status bar,
  down to 54 points below it. It is clear while the list is at rest and becomes solid as soon as
  the list has moved 24 points, so nothing slides behind the clock or is cut by a hard edge. Its
  contents, "Transactions", a search button and the add button, appear once the large title has
  gone under it. The search button scrolls the list to the top and puts the cursor in the search
  box. Both changes take 150 ms; with Reduce Motion on they happen at once.

### Month card

- One glass card: the period on the left (`periodTitle` from `useDashboardKpis`, written as that
  hook gives it), the comparison on the right (`deltaLabel`, shown only when a change figure
  exists).
- Two columns, Income and Expenses, each a label, a change chip and the figure. A column without a
  change figure shows no chip.
- Same data as today (the dashboard's figures, shared cache key), same rule for hiding the card
  when there is no summary. It does not follow the filters, as today.
- The figures keep their count-up animation and their test identifiers (`kpi-Income`,
  `kpi-Expenses`). A change chip is spoken with the comparison in words (`deltaSpokenLabel`), not
  as a bare percentage.
- The new component file is added to `scripts/check-reporting-period-labels.py` as a companion of
  `LedgerScreen.tsx`, so a hard-coded "This Month" or "vs last month" cannot be written into it.

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
- The heading is written from the day's date by a new function; `groupTransactionsByDay` and its
  existing labels are left as they are for their tests.
- A transaction is shown once. Pages are merged by transaction id, so a row the server returns on
  two pages (the paging defect below) is not listed twice and is not counted twice in the day's
  total. A transaction the server leaves out of every page cannot be recovered on the phone; only
  the server fix restores it.
- Inside the card, one row per transaction, hairline dividers between rows.
- **Row title:** who the transaction was with, written the way the statement prints it. The
  server's `merchant` value cannot be shown as it is: it is lower case, stripped of punctuation
  and cut to four words, and for a hand-entered transaction it is a mangled copy of the person's
  own words. So the title is worked out in this order (`rowTitle`, a pure function):
  1. No merchant: the description; "Transaction" when there is none.
  2. The description has no slash in it and, lower-cased and stripped the same way, begins with
     the merchant: the description is plain words already (a hand-entered transaction, an
     unstructured bank line), so the description itself is the title, in full. The slash test
     keeps out the layouts that print the payee ahead of a structured narration.
  3. Otherwise the merchant's words are looked for inside the description, ignoring case and
     whatever punctuation sits between them, and that stretch of the description is the title,
     exactly as printed.
  4. Not found (the server's "interest" label, a merchant the person typed in the edit sheet): the
     merchant as stored, with each word's first letter capitalised when it is all lower case.

  Measured on the 386 different transactions of the test account: rule 2 gives the title for 8,
  rule 3 for 373, rule 4 for 5 (three "interest" labels and two names whose words are not next to
  each other in the narration). Titles have a median of 8 characters; 6 are longer than 24 and
  end in an ellipsis on one line. Where the name is found in the description it is printed in all
  capitals on 274 rows, mixed case on 100 and lower case on 7, so most titles are capitals, as on
  the statement. Whether to soften capitals into ordinary capitalisation is the owner's choice:
  it reads more easily but turns abbreviations into words ("IRCTC" would become "Irctc"), so this
  design keeps what is printed unless told otherwise. The narration stays in the details sheet, in
  the delete confirmation and in search.
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
- The sheet scrolls when its content is taller than the screen (the date picker opens in place on
  iOS). Android's back button and a tap outside close it. A From date after the To date is not
  blocked, as today, and simply matches nothing.

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

These are content changes, not only looks. Items 2 to 4 and 6 were listed in words when the owner
chose option A. Items 5 and 7 were visible in that picture but not listed. Item 1 was shown wrongly
in that picture (tidy names the app cannot produce) and is shown correctly in the corrected one.
All seven are called out here for the review of this document:

1. The row title is the payee's name as the statement prints it (mostly capitals), not the whole
   bank narration.
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
shadow and clean corners, instead of rows pretending to be a card. Measured (see "Follow-up
measurements"): scrolling day cards costs no more than scrolling today's rows, and scrolling
inside one very long card is cheap too. What is expensive is drawing a very long card in one go.
So a day is cut into pieces of at most 25 rows. The first piece carries the heading and the
rounded top, the last the rounded bottom, and pieces in between are square, so a long day still
reads as one card while the list only ever draws 25 rows at a time. A row is a memoised component,
so a new page arriving does not redraw the rows already on screen. The day's total is worked out
over the whole day, not per piece.

The list's tuning changes with the item size. Today it draws 12 items first, which is 12 rows; with
day pieces 12 items could be 300 rows. The first draw is set to 4 pieces, and the plan measures the
time to the first drawn row before and after.

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

- The scroll measurement counted the app's own threads only. Shadow drawing done by the system's
  render process was not counted this time, and hangs were not recorded. The dashboard's shadows
  measured at no cost there on this simulator; this screen's were not re-measured.
- The 25-row piece size comes from the page size (20), not from a measurement of its own. The plan
  measures a 100-row day built from pieces before the work is called done.
- Merchant names cut to eight characters by the bank will read as clipped titles. What can and
  cannot be recovered is under "Follow-up measurements"; it is server work with its own review.
- The paging defect below repeats rows in today's list and will do the same in the new one until
  the server is fixed. The new list must not crash or mis-key on a repeated row meanwhile.
- Android draws the card shadow as an elevation under a see-through fill. Elevation under a
  see-through view is known to show as a grey patch inside the view on Android. The dashboard's
  cards have the same pairing and were not looked at on Android either. The plan includes a look at
  both on an Android emulator before this is merged; if that cannot be done it is said so.
- The details sheet still titles a transaction by its narration while the row now shows the
  payee's name. Bringing the sheet in line is not in this round.
- Found in passing and not touched: the web Ledger shows the lower-case `merchant` as its row
  title (`frontend/src/pages/Ledger.tsx`).
- Not verifiable here: iOS below 26, a physical phone, Android on a device.
- This ships in the next store build with the rest of the redesign, never over the air to 1.0.0.

## Follow-up measurements (2026-10-11)

The owner asked for the open points to be settled rather than carried. Same simulator and account.

### The title and the list

- The list's scroll view starts at 114 points from the top of the screen and clips to its bounds
  (read from the native view tree). The line under the title ends at 114.5 points. A full-size
  screenshot taken mid-scroll shows nothing drawn above 114 points.
- So nothing shows through. The fault is the join: no gap, no edge, and a card's border meeting the
  letters of that line. The earlier reading came from the accessibility tree, which also lists
  elements that are scrolled out of view.
- The design already removes that line and puts a solid bar over scrolled content, which settles
  it. Nothing else needs fixing.

### Scroll cost

Twelve identical swipes down from the top of the list, recorded with the Time Profiler attached to
the app only. The figures are counts of profiler samples on that thread in a 22 second recording (the sampling
interval was the tool's default and was not read back).

| Version | JavaScript thread | Main thread |
|---|---|---|
| Today's list, three runs | 2,632 / 2,454 / 2,539 | 1,861 / 1,367 / 1,631 |
| One card per day, with the card shadow, two runs | 1,649 / 1,676 | 1,275 / 1,393 |
| Every loaded row (200) forced into one card, scrolling | 1,464 | 1,241 |
| The same, at the moment that one card is first drawn | 3,610 | 792 |

- Day cards with shadows are not slower to scroll than today's list on either thread; the
  JavaScript thread does about a third less work. One difference between the runs is not design:
  today's list was showing a development-only warning (the repeated row below) that the prototype
  did not, which may account for part of the gap.
- A 200-row card scrolls cheaply once drawn. Drawing it cost 3,610 samples on the JavaScript
  thread, more than a whole scroll. That figure includes a development reload and is an upper
  bound, not a clean cost; it is still the reason for the 25-row pieces.
- The prototype was throwaway code and is not in the repository.

### Names cut to eight characters

Among the 194 different transactions, 88 have a name of exactly eight characters taken from the
narration (51 different names). For those 88:

| What the narration offers | Rows |
|---|---|
| The payment handle continues the same letters (a median of 6 more) | 22 |
| The handle is a phone number | 34 |
| The handle has letters, but not this name's | 30 |
| No handle field | 2 |

Separately, for 9 of the 88 a longer form of the same name appears on another row of the account.

- For at least 34 rows the statement simply does not contain the rest of the name. No change to
  the server can produce it.
- For 22 rows the handle could complete the name, but a handle is itself cut and often runs words
  together, so the result would need checking row by row.
- The name is produced by `CategoryRules.extractMerchantLabel` on the server, and the same value
  is what merchants are grouped by, what learned categories attach to and what recurring payments
  are identified by. Changing it is a parser change with consequences for all three, and by this
  repository's rules needs a before-and-after measurement on the real statement corpus.
- That makes it its own piece of server work, not a step of this screen. This screen shows whatever
  name the server gives.

The owner asked for it to be done, so the candidate rule was measured on the real statement
collection before any code was written (a throwaway probe, counts and masked shapes only). The
rule: a name is completed from a later field of its narration when that field continues the
name's letters, is not a number, and its own separators give the word breaks.

- 34 statements, 1,965 rows. A structured payee name was found on 456 rows. On 322 of those the
  field after the name is mostly digits.
- The rule fired on 31 rows in 6 statements. Most of those are not cut names at all: a short name
  followed by a longer code that happens to start with the same letters. Applied as written it
  would replace correct names with codes, so it must be limited to names sitting on a statement's
  cut length.
- Only one statement in the collection cuts names to eight characters (33 of its 46 names). There
  the rule gives a properly spaced, longer name on 4 rows and a run-together one on 3.
- So across the collection the honest gain is 4 rows of 1,965. Other banks cut at 10, 15 or 30
  characters and print a phone number after the name, where nothing can be completed.
- Not measured: whether the merchant a row is linked to (the server groups rows under known
  brands) carries a better name than the cut one. That needs the production data and was not read.
- Nothing was built. Whether a rule with this little reach is worth a parser change is the owner's
  decision.

### A paging defect found on the way

- 6 of the 200 loaded rows are a transaction that is already in the list. Each repeat sits exactly
  where one page of 20 ends and the next begins, inside a day that has several rows.
- Cause, read in the code: `TransactionService.search` sorts by one field only (the date, or the
  amount), with nothing to order rows that share it. The database may then return such rows in a
  different order for each page request, so a row can appear on two pages while another appears
  on none.
- Effect for a person: a transaction listed twice, its amount counted twice in that day's total,
  and another transaction missing from the list. Measured after scrolling to the very end: the
  server reports 392 transactions, the phone loaded 392 rows, and they are 386 different
  transactions. Six are shown twice and six are never shown.
  The web Ledger pages through the same endpoint, so the same can happen there; it was not
  observed there.
- It is a server fix (a second sort key) and is tracked separately from this redesign.

## Review of this design (2026-10-11)

The owner asked for a check for bugs and gaps. Done by the author against the code and the running
app, with no second reviewer. What it found is corrected above; the list is kept here so the
changes can be traced.

- **The row title was wrong as first written.** It said "the merchant name". The server derives
  that value from the description (`CategoryRules.extractMerchantLabel`): lower case, punctuation
  removed, four words at most, for hand-entered transactions too. On the test account all 386
  values are lower case. The first options picture showed tidy names that the app could not have
  produced. The title rule above replaces it, and a corrected picture was sent to the owner.
- **Transactions are missing, not only repeated.** See the paging defect: six of 392 are never
  shown. The server fix is separate work already started; this design adds the merge by id.
- **The first draw would have been up to 25 times heavier** with day pieces and the list's current
  setting. Corrected under "Code shape".
- **A hard edge under the clock.** With the title scrolling, the list would have been cut at the
  status bar until the pinned bar appeared. Corrected under "Pinned bar".
- **The month card would have dropped out of the period-label guard, and lost its test identifiers
  and its spoken comparison.** Corrected under "Month card".
- **Android's shadow under a see-through card was never looked at**, here or on the dashboard.
  Added to the risks with a check in the plan.
- **A claim was too strong.** The scroll figures were called milliseconds; the sampling interval
  was not read back. They are sample counts.
- Checked and found sound: the eight status choices match the code; the short forms for who was
  on the other side match `counterpartyLabel`; the date picker works inside a sheet (it opens in
  place on iOS and as a system dialog on Android); the search box keeps its focus while results
  reload, because it is part of the list's header element as today.
