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

Each placeholder group is wrapped once with `accessibilityLabel="Loading"`, with its pieces hidden
from screen readers, so a screen reader says "Loading" once instead of reading out a pile of boxes.

### Whole-screen spinners become screen skeletons (13)

Each composes the building blocks into a rough copy of the real screen's layout, shown only while
its main query has no data (`isLoading`):

| Screen | Skeleton |
|---|---|
| Accounts | title, then three account cards (`SkeletonCard`) |
| Financial Memory | title, a two-column grid of stat cards, a list card |
| Goals | title, three goal cards |
| Investments | title, a summary card, list rows |
| Journey | title, a card of lines |
| Year in Review | one tall card |
| Profile | profile header, form fields |
| Refer & Earn | a tall picture-sized card, a code card, three stat tiles |
| Ticket Detail | title lines, a message card |
| Settings › General | form fields, a segment-shaped bar, settings rows |
| Settings › Security | settings rows, list rows |
| Settings › Categorization | settings rows, a list card |
| Settings › Account | two text sections |

A screen whose real layout is centred today on a spinner moves to its normal top-aligned layout for
the skeleton, so the content does not jump from the middle to the top when it lands.

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

A source scan in the mobile suite (same pattern as `theme/glassMigration.test.ts`) fails if an
`ActivityIndicator` appears under an `isLoading`-style first-load condition in `screens/` or
`components/`, except in the files and lines listed by name as action spinners. Shown to fail by
putting one spinner back.

## Part 2: Haptics

### The switch

- **Settings › General › Haptics**, a switch, on by default.
- Saved on the phone with `safeStorage` under its own key, like the theme. Not sent to the server.
- Read once at start into a small store (the same pattern as `useReduceTransparency`), so firing a
  haptic never waits on storage. Until the stored value is read, haptics are on.
- `lib/haptics.ts` checks it in one place: when off, every haptic function does nothing.

### Where haptics fire

| When | Kind |
|---|---|
| Tapping a tab in the floating pill, or "+" | selection |
| A second tap on the open tab (scrolls to the top) | selection (the same tap) |
| Choosing a segment: All / Income / Expense, the Insights tabs, the Theme setting, the Spending Tracking question | selection |
| Turning a switch on or off | selection |
| Choosing a filter chip or a picker option | selection (pickers already do) |
| Pull to refresh reaching the point where it refreshes | impact |
| A long press that opens a menu or a delete confirmation | impact (Transactions already does) |
| A save, add, import or delete that worked; copying the referral code | success |
| The server refused, or the network failed | error |
| A form that cannot be sent yet (a missing amount, a required field) | warning |

**Never:** while scrolling; on taps that only open another screen; for something the person did not
do (a background refresh finishing, a banner appearing); twice for one action.

### Where the calls go

Into shared pieces first, so new screens inherit them:

- `FloatingTabBar`: selection on every tab press and on "+".
- `LedgerControls` (`TypeFilter`), the Insights tab row, the Theme segment, the filter chips in
  `LedgerFiltersSheet`, `InflowKindPicker`: selection on change.
- Every `Switch` in the app (Ledger filters, app lock, notification preferences, inflow kinds,
  statement refresh, Import, the new Haptics switch): selection on change. The switch for haptics
  itself fires after it is turned on, not after it is turned off.
- Pull to refresh: one impact when the refresh starts, from the `onRefresh` handlers of the 13
  screens that have it.

Then the save, delete and failure outcomes in each sheet and screen that does not already fire one:
Add Transaction, Edit Transaction, Account form, Category edit and delete, Change Email, Change
Password, Feedback, New Ticket, Amount prompt, Referral code prompt, account delete, goal create,
referral code copy. The nine places that already fire keep what they do.

### Guard

- A source scan fails if any file other than `lib/haptics.ts` imports `expo-haptics`.
- Tests on the shared controls assert the kind of haptic each fires.
- `haptics.test.ts` covers the switch: off means no call reaches `expo-haptics`; on, and the not-yet-
  read state, behave as today.

## Testing and checks

- Tests are written before the code they cover: each converted screen shows its skeleton while
  loading and its content after, never a spinner; each haptic touchpoint fires the right kind; the
  switch silences all of them.
- The whole mobile check under Node 22: jest, `tsc --noEmit`, `expo lint --max-warnings 0`, the
  Android bundle export and the repository guards.
- On the iPhone simulator, signed in to the owner's account against production, read only: each
  converted screen's skeleton seen on a slowed first load, and Reduce Motion holding it still.
  Haptics cannot be felt on a simulator; they are checked through the tests and a debug log of
  calls, and the owner feels them on a real phone.
- **Android:** whether `expo-haptics` follows Android's own touch-feedback setting is not
  established. It is checked on the emulator if the owner signs in there; otherwise the in-app switch
  covers it.

## Not covered

- Android unless the owner signs in on a test build.
- How the haptics feel on a physical phone, which only the owner can judge.

## Out of scope

- New haptic kinds, custom vibration patterns, or sound.
- Skeletons for content that is not loading (empty states stay as they are).
- Changing which data a screen loads or when.

## Shipping

Two pull requests: skeletons first, then haptics. Both ship in the next store build (1.1.0) and must
not be sent over the air to the 1.0.0 runtime.
