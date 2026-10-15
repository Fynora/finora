# Mobile skeletons and haptics: design

**Status:** design approved by the owner on 2026-10-15 in conversation. This document is the written
form, for review before the build plan.

## Why

The owner wants the mobile app to feel polished and finished before launch. Two things stand out on
the current app:

- **Loading.** Eight screens show grey placeholders shaped like their content while data first
  loads. Thirteen others replace the whole screen with a spinning wheel, and sixteen sections and
  pop-ups show a spinner in place of their content. The spinners read as "the app is stuck"; the
  placeholders read as "your content is on its way".
- **Touch.** The app makes the phone tap in nine places (pickers, Quick Sort, Budgets, Review
  Categories, Gmail Review, the Transactions long press, Import). Tapping a tab, the "+" button, a
  segment, a filter chip or a switch gives nothing, and most saves and failures outside those nine
  places give nothing either.

Owner's choices (2026-10-15): both, skeletons first; skeletons shaped per screen; haptics following
the map below; an in-app switch to turn haptics off.

## What exists today

- `mobile/src/components/skeletons/Skeletons.tsx`: `SkeletonCard`, `SkeletonTransactionRow`,
  `SkeletonKpiGrid`, `SkeletonBudgetCard`, `SkeletonChart`, all built from `Shimmer.tsx`, one shared
  pulse that starts when the first placeholder mounts, stops when the last unmounts, and holds still
  under Reduce Motion. Every call site gates on React Query's `isLoading` (no cached data yet), never
  `isFetching`, so a background refresh never swaps real data back to a placeholder.
- `mobile/src/lib/haptics.ts`: the one place the app calls `expo-haptics` (`~57.0.3`, already
  installed). Five kinds: `hapticSuccess`, `hapticWarning`, `hapticError`, `hapticSelection`,
  `hapticImpact`. Failures from the native call are swallowed on purpose.
- Settings › General has a Theme segment saved on the phone through `lib/safeStorage.ts`
  (SecureStore, never throws).

## Part 1: Skeletons

### Rule

A skeleton means "your content is coming"; a spinner means "your action is running". Content that
is loading for the first time gets a skeleton. An action the person started (a button press, a
delete, a purchase, a sync, the next page of a list, the app starting) keeps its spinner.

### New building blocks

Added to `Skeletons.tsx`, built from `Shimmer` like the existing ones:

- `SkeletonListRow`: a round or rounded-square icon, two lines of text, an optional amount on the
  right. For accounts, goals, tickets, statements, sessions, investments.
- `SkeletonFormField`: a label line over a field-shaped box. For the Settings forms.
- `SkeletonProfileHeader`: a circle and two lines. For Profile and the Settings account card.
- `SkeletonSettingsRow`: a label and a trailing control (switch or chevron). For Settings lists.
- `SkeletonLines`: a few text lines of varying width. For pop-ups and small sections.

**Look.** The new blocks use the existing look and nothing new: the shared `Shimmer` pulse (the
`border` colour at an opacity that moves between 0.35 and 1), the theme's radii and spacing, and the
`Card` / `DashboardCard` wrappers the real screens use. No new palette, animation or library.

**Skeleton tokens.** The values every skeleton uses are named once, in `theme/skeleton.ts`
(`skeletonTokens`), drawn from the existing theme rather than new numbers: the fill (`border`), the
pulse's low and high opacity and its duration, the Reduce Motion opacity, corner radii for text
lines, blocks and icons, standard heights for a text line, a list row, a settings row and a form
field, icon sizes, and chart proportions (from `lib/chartGeometry.ts`). `Shimmer` and every skeleton
block read from it; a source scan fails if a file in `components/skeletons/` writes its own colour,
radius or row height instead. In both themes the fill against the card and against the glass
backdrop is measured and recorded (it is decorative, so there is no contrast floor to meet, but it
must be visible).

**Screen readers.** Each placeholder group is one element with the static label "Loading" and its
pieces hidden from screen readers; there is no live-region announcement. So a screen reader meets
"Loading" once per group, nothing in it can be focused, and the real content is read normally once
it arrives.

**Reduce Motion.** The shared pulse already holds still at a fixed opacity under Reduce Motion; the
placeholders stay visible and the loading logic does not change.

### Data states

Every converted screen and section handles four states, and only the first changes:

| State | Shows |
|---|---|
| First load, no data yet (`isLoading`) | the skeleton (today: a spinner) |
| Loaded with data | the content, as today |
| Loaded, nothing to show | the existing empty state, as today |
| First load failed, or offline with nothing cached | an error with a way to try again |

Two facts from the code (checked 2026-10-15):

- A skeleton can never be left on screen by a failure. React Query's `isLoading` turns false when a
  first load fails, and stays false for a query paused offline with nothing cached; the screens
  already handle that case with `isPausedCold` (`lib/refreshingIndicator.ts`).
- All 13 whole-screen spinner screens already show a failure message. Eight of them give no way to
  try again: no button and no pull to refresh (Accounts, Year in Review, Ticket Detail, Profile,
  Settings › General, Security, Categorization, Account). Each of those gets a "Try again" button
  that refetches the same query. The wording of the existing messages stays.

Sections are not all as safe (checked 2026-10-15 while planning). Advanced Reports' six sections,
the statement-detail pop-up and Mark as Transfer show their "nothing here" message when the fetch
fails. Statement History's list asks for a pull to refresh that the screen does not have. Bank Sync
links, device sessions, Gmail connection, "counts as", notification preferences, and the source,
explanation and Bank Correction pop-ups show a failure with no way to retry. Each gets the fourth
state properly: its failure message and "Try again".

A background refresh that fails keeps the cached content on screen, as all of these screens already
do; nothing here changes that.

### Whole-screen spinners become screen skeletons (13)

Each skeleton is built from the screen's real layout, read from its code during the build plan, not
from the names below: the same wrappers, the same number of blocks per section where the count is
fixed, close heights and the same spacing, so that on a slow first load nothing shifts noticeably
when the content arrives. Exact text widths and dynamic counts (how many accounts) are not copied.

The 13 screens: Accounts, Financial Memory, Goals, Investments, Journey, Year in Review, Profile,
Refer & Earn, Ticket Detail, Settings › General, Settings › Security, Settings › Categorization,
Settings › Account. The plan lists, for each, the sections its real layout has and the blocks that
stand in for them.

A screen whose real layout is centred today on a spinner moves to its normal top-aligned layout for
the skeleton, so the content does not jump from the middle to the top when it lands.

**Speed.** Measured, then fixed only where a problem is found:

- The first load of the three heaviest skeleton screens (Home, Transactions, Advanced Reports) is
  recorded before and after with Time Profiler on the simulator, the method used for the tab pill
  (three runs each, sample counts on the app's JavaScript and main threads), and checked for dropped
  frames during the pulse.
- The shared pulse is confirmed to stop when the last skeleton unmounts, to stay single however many
  skeletons mount, and to pause while the app is in the background (React Native's `AppState`; if it
  keeps running there, it is stopped there, test-first).
- Skeletons are confirmed not to block scrolling or touches.
- Any slowdown or leak found is fixed test-first and re-measured; if none is found, the numbers go
  in the build record.

**No artificial delay.** The skeleton shows exactly as long as the first load takes. No minimum time
is added; if a very fast load is seen to flash on the device, that is raised with the owner then.

### Section spinners become section skeletons (16 sections and 5 pop-ups)

The same rule at section size, inside a screen that is otherwise drawn:

- Advanced Reports: top merchants, top categories, international spend, multi-year income and
  spend, multi-year categories, confidence (six sections).
- Statement History list, My Tickets list, Settings › Bank Sync links, Financial Memory recurring
  payments, the investment activity card, the "counts as" section.
- Settings sections: device sessions, Gmail connection, notification preferences, app lock.
- Pop-ups that load data: transaction source, transaction explanation, Bank Correction, Mark as
  Transfer, statement detail.

### Spinners that stay (13)

Button loading states (`Button`), a row being deleted (Accounts, Transactions), a plan being bought
(Paywall), import progress, the next page of transactions, Review Categories' refetch indicator,
Gmail Review's busy state, Bank Sync Confirm's link step, the transaction sheet's action rows, Import
resuming a session, a device session being signed out, Gmail syncing, and the app starting up
(`RootNavigator`).

### Guard

Two layers:

1. **Behaviour tests**, the main guarantee: for each converted screen and section, a test that it
   shows its skeleton (and no spinner) on first load, its content after a successful load, its
   empty state after an empty one, and its error with "Try again" after a failure.
2. **A source scan** in the mobile suite (same pattern as `theme/glassMigration.test.ts`) that fails
   if an `ActivityIndicator` appears in `screens/` or `components/` outside a named list of action
   spinners. Adding to the list is a visible change in review. Shown to fail by putting one spinner
   back.

## Part 2: Haptics

### The switch

- **Settings › General › Haptics**, a switch, on by default.
- Saved on the phone with `safeStorage` under its own key, like the theme. Not sent to the server.
- Read once at start into a small in-memory store (the same pattern as `useReduceTransparency`), so
  firing a haptic never waits on storage. The store has three states: **not yet read**, **on**,
  **off**. While not yet read, no haptic fires, so a person who turned haptics off never feels one
  during startup. A missing value means on. A failed read means on (the same never-throws contract
  as `safeStorage`; a person who turned them off and hits a storage fault gets them back, which is
  the lesser harm than an app that throws).
- `lib/haptics.ts` checks it in one place: unless the state is **on**, every haptic function does
  nothing.
- **Changing the switch:** the in-memory state changes first and takes effect at once; then, turning
  it on fires one selection haptic; then the value is saved. Turning it off fires nothing. A failed
  haptic or a failed save does not move the switch back. Fast repeated toggling leaves the last
  position in memory and in storage.
- **The phone's own settings still apply.** The app's switch is an extra way to turn haptics off,
  never a way to turn them on against the phone's setting. iPhone's System Haptics setting and
  Android's touch-feedback setting are to be checked on real devices; if either does not silence
  `expo-haptics`, that is reported, not worked around.

### Where haptics fire

This table is the one contract; every call in the code traces to a row.

| Interaction | Kind | Only when |
|---|---|---|
| Tapping a tab in the floating pill, or "+" | selection | always (the tab bar is the one navigation control that gets a haptic) |
| Tapping the tab that is already open | selection | it is the same tap as above, so one haptic, whether or not the screen was scrolled |
| Opening a screen from a row, card or link | none | |
| Choosing a segment (All / Income / Expense, the Insights tabs, the Theme setting, the Spending Tracking question), a filter chip or a picker option | selection | the selected value changes; tapping the one already selected gives nothing (the two picker pop-ups, which fire on every tap today, change to match) |
| Turning a switch on or off | selection | the value changes; a switch that saves at once gets this one haptic and no outcome haptic (one action, one haptic) |
| Pull to refresh | impact | once, when the refresh is committed (React Native's `onRefresh` call; there is no earlier "threshold reached" event to hook) |
| A long press that opens a menu or a delete confirmation | impact | the person pressed (Transactions already does) |
| A save, add, import or delete | success | once, after the server confirmed it; never on the tap or when the request starts |
| The same, refused by the server or failed on the network | error | once, for an action the person started; not for a failed background refresh or for browsing offline |
| A form that cannot be sent yet (a missing amount, a required field) | warning | the person tried to send it; only where the form checks on press -- a Save that is disabled until the form is valid cannot be pressed, so nothing fires |
| Copying the referral code | success | the copy worked |
| Cancelling a sheet or a confirmation | none | |

**One action, at most one haptic.** Each outcome haptic lives in exactly one place for that action
(the handler that learns the outcome), never also in the button, the sheet and a shared hook. An
automatic retry does not add haptics; the person feels the final outcome once.

**Optimistic changes.** Two places change the screen before the server answers: dismissing a
recurring merchant on Home and on Insights (`onMutate`, with a rollback on failure). Neither fires a
haptic when the screen changes; it fires success once the server confirms, or error if it rolls
back.

**Never:** while scrolling; for something the person did not do (a background refresh finishing, a
banner appearing).

### Where the calls go

Into shared pieces first, so new screens inherit them:

- `FloatingTabBar`: selection on every tab press and on "+".
- `LedgerControls` (`TypeFilter`), the Insights tab row, the Theme segment, the filter chips in
  `LedgerFiltersSheet`, `InflowKindPicker`: selection on change.
- Every `Switch` in the app (Ledger filters, app lock, notification preferences, inflow kinds,
  statement refresh, Import, the new Haptics switch): selection on change. The switch for haptics
  itself fires after it is turned on, not after it is turned off.
- Pull to refresh: one impact on every pull to refresh, through a shared `AppRefreshControl` (14
  screens have one today).

Then the save, delete and failure outcomes in each sheet and screen that does not already fire one:
Add Transaction, Edit Transaction, Account form, Category edit and delete, Change Email, Change
Password, Feedback, New Ticket, Amount prompt, Referral code prompt, account delete, goal create,
referral code copy. The nine places that already fire keep what they do.

The rule in the table applies to every action a person starts that saves, adds or deletes their data
or settings; the build plan lists which actions that covers and which are left out, with the reason
(sign-in and verification steps, chat, sharing, outside flows, actions that already fire).

### Guard

- A source scan fails if any file other than `lib/haptics.ts` imports `expo-haptics`.
- Tests assert the **kind and the count** of haptics, not just that one fired.
- `haptics.test.ts` covers the switch: on fires; off, and not yet read, never reach `expo-haptics`;
  a missing value and a failed read become on; a rejecting native call does not throw; turning it on
  fires once, turning it off fires nothing; the value survives a restart (a fresh read).

## Testing and checks

Tests are written before the code they cover.

**Loading states, per converted screen or section:** skeleton (no spinner) on first load; content
after success; the empty state after an empty answer; the error with "Try again" after a failure,
and "Try again" refetching; cached content kept when a background refresh fails; nothing left
running when the screen closes mid-load. Plus: action spinners (button, delete, next page) still
show; Reduce Motion holds the pulse still.

**Haptics, per touchpoint:** the right kind, exactly once; none when the switch is off or not yet
read; success only after the save resolves, error only when it rejects, warning only on a blocked
send; nothing on cancel, on a background refresh, or on tapping the selected segment; rapid repeated
taps on Save give one outcome haptic per save that actually ran.

**Whole mobile check** under Node 22: jest, `tsc --noEmit`, `expo lint --max-warnings 0`, the
Android bundle export and the repository guards. Run on each pull request, and again on main with
both merged before the store build.

**Sensitive flows unchanged:** adding and editing a transaction, importing a statement, Reports and
Advanced Reports, and pull to refresh on Home keep their existing tests passing unchanged apart from
the loading and haptic assertions this work adds. Nothing here changes query keys, cache behaviour,
refetch triggers, paging, saves, empty states, sign-in or navigation.

**On the iPhone simulator**, signed in to the owner's account against production, read only: each
converted screen's skeleton on a slowed first load (Network Link Conditioner or a throttled proxy),
checked for content jumping when it lands, in light and dark and at the largest text size; Reduce
Motion holding it still; the "Try again" states by turning the network off. Haptics cannot be felt on
a simulator; their calls are checked through the tests and a debug log.

**On real phones.** With an iPhone and an Android phone connected by USB and signed in by the owner:

- Done by me: install the build, walk every converted screen's first load, and drive each row of the
  haptics table with haptics on and off, confirming from a debug log on the phone that each call
  fires (or does not) with the right kind and count.
- Done by the owner, from a short checklist I prepare (about ten actions per phone): feel each kind,
  with the app's switch on and off, and with the phone's own setting (iPhone System Haptics, Android
  touch feedback) on and off. Only a person can tell whether the phone actually vibrated; the call is
  made either way.

**Platforms.** The code is shared, so both changes ship to iPhone and Android alike. How
`expo-haptics` 57 maps each of the five kinds onto Android vibration is read from its source during
the plan and recorded, not assumed. Android is tested by the owner on a build after both pull
requests merge (owner's decision, 2026-10-15), using an Android section of the same checklist.

Read from source (expo-haptics 57.0.3, 2026-10-16): in `HapticsModule.kt` the three functions the
app calls (`notificationAsync`, `selectionAsync`, `impactAsync`) drive the vibrator directly,
`Vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))` on API 26 and up and a
plain pattern below. Only the library's separate Android-only `performHapticsAsync` goes through
`View.performHapticFeedback`, the path Android's touch-feedback setting governs, and the app does not
call it. The library declares `android.permission.VIBRATE` in its own manifest. The waveforms the app
gets (milliseconds, amplitude 0–255; impact is the medium style `lib/haptics.ts` asks for):

| Kind | Timings | Amplitudes |
|---|---|---|
| selection | 0, 50 | 0, 30 |
| impact (medium) | 0, 43 | 0, 50 |
| success | 0, 40, 100, 40 | 0, 50, 0, 60 |
| warning | 0, 40, 120, 60 | 0, 40, 0, 60 |
| error | 0, 60, 100, 40, 80, 50 | 0, 50, 0, 40, 0, 50 |

So on Android every kind is a short buzz; success, warning and error differ by rhythm, not by
texture. Whether the phone's touch-feedback setting silences them is **not established**: the code
path suggests it does not, and the owner's Android checklist answers it on a real phone. If it does
not silence them, that is reported, not worked around.

## Not covered by me

- Whether a phone actually vibrated, and whether the phones' own settings silence the app's haptics:
  the owner's checklist covers it.
- Android: the owner tests it on a build after merge; I check it only through the tests and the
  Android bundle export.

## Completion criteria

- Every screen and section listed above has its skeleton, its content, empty and error states, and
  the tests for each.
- No first-load spinner remains outside the named action list.
- Every row of the haptics table has a test asserting its kind and count; the switch persists and
  silences everything; a failing native call breaks nothing.
- The whole mobile check passes on each pull request and on main with both merged.
- The simulator pass is recorded screen by screen; what could not be checked is listed, not assumed.

## Out of scope

- New haptic kinds, custom vibration patterns, or sound.
- Skeletons for content that is not loading (empty states stay as they are).
- Changing which data a screen loads or when.

## Shipping

Two pull requests: skeletons first, then haptics. Each can be fixed or reverted on its own, and the
haptics switch is an opt-out for people, not a fix for a defect. Both ship in the next store build
(1.1.0) and must not be sent over the air to the 1.0.0 runtime.

## Review (2026-10-15)

An external review of the first version of this document was taken into account:

- **Adopted:** one haptics contract with "only when" rules (the first version contradicted itself on
  tabs and on pull to refresh); one haptic per action; haptics silent until the switch is read; the
  switch's order of events; the four data states with "Try again"; skeletons built from each screen's
  real layout; existing tokens only; a static "Loading" label; behaviour tests ahead of the source
  scan; kind-and-count haptic tests; the regression and completion criteria.
- **Corrected against the code:** the review assumed a failed first load could leave a skeleton
  showing forever. It cannot: `isLoading` turns false on failure and for a query paused offline. The
  real gap it pointed at is that seven screens give no way to try again; that is now in scope.
- **Adopted after the owner asked (2026-10-15):** named skeleton tokens drawn from the existing
  theme; a measured speed pass with fixes only for what it finds; a real-phone pass run by me over
  USB, with the owner feeling the haptics from a checklist.

## Build record (skeletons, 2026-10-16)

Measured on the iPhone 17 Pro simulator (iOS 26, 402 x 874 pt) against production, signed in to the
owner's account and read only: nothing was saved, sent, deleted or switched. Slow and failed loads
were made with a local proxy that delays or fails chosen API paths; the app was pointed at it through
the worktree's own `.env.local` and pointed back afterwards.

### Speed

Busiest-window profiler samples (JS thread / main thread), three runs each, before this branch and
after it:

| Moment | Before | After |
|---|---|---|
| Home, cold start | JS 1390 / 3792 / 2862, main 776 / 1138 / 786 | JS 2479 / 2104 / 2674, main 825 / 773 / 965 |
| Transactions, first open | JS 716 / 240 / 217, main 680 / 508 / 174 | JS 229 / 232 / 259, main 184 / 395 / 210 |
| Advanced Reports, first open | JS 486 / 244 / 146, main 1117 / 842 / 798 | JS 191 / 206 / 146, main 646 / 923 / 616 |

After sits inside the before range on all three. Home and Transactions are served from the persisted
cache, so no skeleton was on screen in those runs. While a skeleton is on screen in the foreground the
pulse is most of the main thread's work: 1242 of 1623 main-thread samples in one 10-second recording.

### In the background

Before the fix, the pulse kept running while the app was in the background: 62 samples in the native
animation driver over the recording. The pulse now stops when the app leaves the foreground and starts
again when it returns with a skeleton still on screen; the same recording afterwards had 0 such
samples. Covered by three tests in `Shimmer.test.tsx`.

### Gestures

With a skeleton held on screen, the screen scrolls and the tab bar and back button respond.

### The fill against the card and the backdrop

Decorative, so there is no contrast floor, but it must be visible. Measured from screenshot pixels on
the Goals skeleton:

- Dark: the block at full pulse against the card 1.58:1, against the backdrop 1.90:1.
- Light: at the dim end of the pulse, 1.05:1 against the card; the fill colour at full opacity works
  out to about 1.17:1. Visible in every light capture; faint at the dim end.
- Reduce Motion: three captures over about 12 seconds measured the same colour, so the pulse holds
  still.

### Content jumping when it lands, screen by screen

Each screen was opened with its data delayed, captured with the skeleton showing, then captured again
when the content landed, and the two compared by position. Where they differed the skeleton was
rebuilt from the screen's real layout and measured again.

| Screen | Before the fix | After |
|---|---|---|
| Profile | about 60 pt | about 2 pt |
| Financial Memory | 43 pt | about 4 pt |
| Refer & Earn | about 16 pt | within about 7 pt |
| Settings: General | about 30 pt | about 2 pt |
| Settings: Categorization | about 23 pt | about 2 pt |
| Settings: Security | several rows off | about 2-3 pt |
| Settings: Account | a few points | about 2 pt |
| Statement History | about 19 pt per card | exact |
| Advanced Reports (lists, trend) | rows about 14 pt short | exact for the rows shown |
| Investments | trend card about 41 pt short, allocation about 113 pt tall with no holdings | about 1-2 pt |
| Goals | header and first card | exact |

What made most of the difference: a text placeholder now takes a whole line of room (a line of the
system font measured 1.19 times its size), rows keep the room the real screen keeps while empty (the
error slot under a text field, the save-status row above a Save button), and placeholders grow with
the phone's text size as real text does.

At the largest text size Statement History's jump roughly halved; what remains is long names wrapping
to extra lines, which depends on the data. List sections show a few placeholder rows against the real
number of rows, so anything below a list moves by the list's extra length.

### Try again

Checked by failing each load and then letting it through: Profile, Statement History, the Top Merchants
section of Advanced Reports (the other sections were unaffected), and the notification settings. Each
went from its message to the skeleton to the content.

Found on the way and fixed: Profile, Refer & Earn and the General, Security and Account settings
replaced content already on screen with their failure message when a later refresh failed. They now
show it only when there is nothing to show.

### Not checked, and why

- Journey and Year in Review: their entry is hidden while the timeline loads, so their skeletons are
  not reachable on the simulator.
- Ticket detail and My Tickets against real tickets, goal cards and investment holdings: the account
  has none, and creating one is a write. Their skeletons are built from the screens' code and seen
  rendering.
- Accounts: its list is kept between launches, so its skeleton shows only on a first install.
- Real phones: needs the owner to connect them.
- `StatementRefreshBanner.test.tsx` failed once in a full parallel run (a lookup timing out) and
  passed on its own six times out of six and in every later full run.

## Build record (haptics, 2026-10-16)

### What was built

- **The switch.** Settings › General › Haptics, the last row of the screen (it applies at once, like
  Retake Tour, so it sits outside the block "Save preferences" saves). Stored on the phone under
  `finora_haptics`; not yet read, on and off as specified. The General skeleton gained a matching
  row.
- **Shared pieces.** `AppSwitch` (every switch; a tick on change), `AppRefreshControl` (every pull to
  refresh, 14 screens; one impact on the committed pull), `hapticSelectionIfChanged` (segments, chips,
  pickers). A source scan keeps `expo-haptics`, `Switch` and `RefreshControl` behind those doors.
- **Outcomes.** Success after the server confirms and error on a refused or failed request, in the
  one handler that learns the outcome, for every action in the plan's list, plus two the plan had on
  neither list and that save the person's data: the Money Review choice (and its undo), and Bank Sync's
  confirm-existing / confirm-new account step.

Found and fixed on the way:

- Creating a category from inside the category picker gave two haptics (the sheet's success, then
  the picker's selection tick when it selected the new category). The picker now selects a saved
  category without a tick.
- A failed referral-code copy left a rejected promise unhandled. It is now caught: nothing is shown
  and nothing fires.

Found in the bug pass after the pull request opened:

- **A pick that is itself the save was felt twice** (the picker's tick, then the save's outcome):
  the ledger's category correction, Category Review, Quick Sort and the recurring question's
  "Something else". The category picker takes `silent` for these callers, so the save's success or
  error is the one haptic. Mark as Transfer ticked on the tap and gave nothing for the outcome; it
  now gives the outcome instead.
- **Saves and deletes that gave no outcome:** deleting a transaction and unmarking a transfer (the
  ledger gave an error but no success), discarding an unfinished import, removing a saved statement
  password (one or all), the recurring question's answer, and the statement-refresh banner's
  "Update" (only when something was updated) and its password update (a refused password is an
  error).

### The table, row by row, and the tests that pin it

| Row | Tests |
|---|---|
| Tab or "+" | `FloatingTabBar.test.tsx` (one per press, off = none, long press = none); `AppTabs.test.tsx` ("+" once, the quick-action row that opens a screen adds none) |
| The tab already open | `FloatingTabBar.test.tsx` (second press on the open tab = one more) |
| Opening a screen from a row, card or link | `AccountsScreen.test.tsx` (Edit opens the sheet: none); `AppTabs.test.tsx` (quick-action row: none) |
| Segments, chips, pickers | the "ticks only when … changes" tests in 16 files (Ledger type filter and status chips, Insights tabs, Cash Flow range, Multi-Year mode, investment period, account type, transaction type ×2, Import's three choices, other devices, feedback type, category icon and colour, money kind and its income question, Spending Tracking question, the category picker); `OptionPickerModal.test.tsx` (tapping the chosen option: none); `SettingsGeneralScreen.test.tsx` (Theme) |
| Switches | `AppSwitch.test.tsx`; `LedgerFiltersSheet.test.tsx` (International row); `SettingsGeneralScreen.test.tsx` (Haptics: on = one tick, off = none); `InflowKindsSettings.test.tsx` (a switch that saves at once: one tick, no outcome haptic even when the save fails) |
| Pull to refresh | `AppRefreshControl.test.tsx`; `DashboardScreen.test.tsx`; `LedgerScreen.test.tsx`; guard: no list uses its own `onRefresh` |
| Long press | `LedgerScreen.test.tsx` (impact 1, twice) |
| Save, add, import or delete: success | the success test beside each action's existing one, in 36 test files |
| Refused or failed: error | the failure test beside each, same files; `ChangeEmailSheet.test.tsx` (fails then succeeds: error, then success) |
| Blocked send: warning | Goals ×3, Investments ×2 (four bad values = four warnings), Profile, Settings › General |
| Referral code copy | `ReferralsScreen.test.tsx` (copied = success; copy fails = none) |
| Cancel | `AccountsScreen.test.tsx`, `AccountFormSheet.test.tsx`, `NewTicketSheet.test.tsx`, Bank Sync disconnect Cancel, subscription pause dismissed, Change Password's missing-session stop |
| Never while scrolling | `LedgerScreen.test.tsx` (scrolling, including past the pull point: none) |
| Never for what the person did not do | `DashboardScreen.test.tsx` (a background refresh finishing: none); Gmail sync (hands off to a job: none) |
| One action, one haptic | double press on Add Transaction and on Add Goal (one request, one success); optimistic dismiss on Home and Insights (none as the row leaves; one success, or one error with the row back); a pick that saves at once (ledger correction and Mark as Transfer: success only; Category Review, Quick Sort, recurring question: the picker is `silent`) |
| The switch itself | `haptics.test.ts` (not yet read, missing, failed read, stored off, on/off, fast toggling, a failed save, restart, a rejecting native call) |

### On the simulator

Pending: the simulator's dev client was signed out when this pass was due, and signing in is the
owner's. The read-only rows to drive once signed in, watching Metro for `[haptics]` lines: tabs and
"+"; the Transactions type filter; the Insights tabs; the Theme segment; pull to refresh on Home;
then the Haptics switch off and the same rows again, expecting no lines.

### Owner's checklist (real phones)

Feel only a person can judge; the calls themselves are pinned by the tests above. On each phone, with
the app's Haptics switch on unless a step says otherwise. Record what each step did.

**iPhone**

1. Tap a tab in the floating pill: a light tick.
2. Tap "+": a light tick. Close the sheet: nothing.
3. Transactions: change All / Income / Expense: a tick. Tap the one already chosen: nothing.
4. Home: pull to refresh: one firmer thud as it commits.
5. Transactions: long-press a transaction: a thud.
6. Settings › General › Theme: change it: a tick.
7. Settings › General: turn a notification switch on: a tick.
8. More › Goals › + New goal: press Add Goal with no name: a warning (a double buzz).
9. Settings › General › Haptics off, then repeat 1–3: nothing.
10. Haptics back on; the phone's Settings › Sounds & Haptics › System Haptics off; repeat 1–3. The
    spec expects nothing; record what is felt.

**Android** (the same ten; step 10 uses the phone's touch-feedback setting)

Read from the library's source (see Platforms): every kind is a short buzz, and success, warning and
error differ by rhythm, not texture. Whether touch feedback off silences them is not established; the
code path suggests it does not. If it does not, that is reported, not worked around.

### Not checked, and why

- Feel, on any phone, and whether the phones' own settings silence the app's haptics: the owner's
  checklist above, on a build after merge.
- The simulator pass: pending the owner's sign-in (above).
- Saves, deletes and failures are driven only in tests, never on the production account.
