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
- All 13 whole-screen spinner screens already show a failure message. Seven of them give no way to
  try again: no button and no pull to refresh (Accounts, Year in Review, Ticket Detail, Settings ›
  General, Security, Categorization, Account). Each of those gets a "Try again" button that refetches
  the same query. The wording of the existing messages stays.

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

**No artificial delay.** The skeleton shows exactly as long as the first load takes. No minimum time
is added; if a very fast load is seen to flash on the device, that is raised with the owner then.

### Section spinners become section skeletons (16)

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
| Choosing a segment (All / Income / Expense, the Insights tabs, the Theme setting, the Spending Tracking question), a filter chip or a picker option | selection | the selected value changes; tapping the one already selected gives nothing |
| Turning a switch on or off | selection | the value changes |
| Pull to refresh | impact | once, when the refresh is committed (React Native's `onRefresh` call; there is no earlier "threshold reached" event to hook) |
| A long press that opens a menu or a delete confirmation | impact | the person pressed (Transactions already does) |
| A save, add, import or delete | success | once, after the server confirmed it; never on the tap or when the request starts |
| The same, refused by the server or failed on the network | error | once, for an action the person started; not for a failed background refresh or for browsing offline |
| A form that cannot be sent yet (a missing amount, a required field) | warning | the person tried to send it |
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
- Pull to refresh: one impact from the `onRefresh` handlers of the 13 screens that have it.

Then the save, delete and failure outcomes in each sheet and screen that does not already fire one:
Add Transaction, Edit Transaction, Account form, Category edit and delete, Change Email, Change
Password, Feedback, New Ticket, Amount prompt, Referral code prompt, account delete, goal create,
referral code copy. The nine places that already fire keep what they do.

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

**On real phones (owner):** haptics on and off, a real save and a failed one, the iPhone System
Haptics setting, and on Android the touch-feedback setting. These are the owner's to do; this work
cannot claim them.

## Not covered by me

- Android, unless the owner signs in on a test build on the emulator.
- How the haptics feel on a physical phone, and whether the phones' own settings silence them.

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
- **Not adopted:** a skeleton design-token system of its own (the existing tokens already cover it);
  extra performance work on the shared pulse without a measured problem; physical-device testing as
  something this work can claim (it is listed as the owner's step).
