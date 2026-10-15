# Mobile floating tab pill: design

**Status:** design approved by the owner on 2026-10-15, section by section in conversation, and
built the same day. The build plan is `docs/superpowers/plans/2026-10-15-mobile-floating-tab-pill.md`;
what the build changed against this design is under "Build record" at the end.

## Why

The mobile tab bar is already a glass surface (`GlassSurface` in `mobile/src/navigation/AppTabs.tsx`),
but it sits in normal layout flow below the screens. Nothing ever passes behind it, so the glass
only ever shows the backdrop and reads as a flat strip. The glass plan
(`docs/superpowers/plans/2026-10-09-mobile-glass-ui.md`, open decision D1) deliberately left the
floating version out until the owner decided. The owner has now decided.

Three options were drawn with the app's own palette, fonts and backdrop: a floating pill, the
current full-width bar with content under it, and Apple's own iOS 26 tab bar. The owner chose the
floating pill.

## What a person sees

1. The tab bar becomes a rounded glass pill floating above the bottom of the screen. Lists scroll
   under it and show through it.
2. The pill holds the same five items in the same order: Home, Transactions, "+", Insights, More,
   with today's icons and labels. The selected tab is in the primary colour, the others grey.
3. "+" becomes a 46 pt filled circle inside the pill instead of a raised button above the bar. It
   opens the same quick-action menu as today.
4. While the keyboard is open the pill slides away; it comes back when the keyboard closes.
5. Every screen reachable from the tabs can scroll its last row fully above the pill.

Nothing the tabs lead to changes.

## The pill

- **Size and place.** 16 pt from each side of the screen, 64 pt tall, fully rounded ends. Its
  bottom edge sits `max(insets.bottom - 6, 12)` pt above the bottom of the screen: just above the
  home indicator on phones that have one (28 pt on a 34 pt inset), 12 pt on phones that do not
  (iPhones with a Home button, Androids with on-screen buttons).
- **Wide screens.** The app supports iPad in portrait. The pill is at most 480 pt wide, centred.
- **Material.** `GlassSurface`, the app's one glass material: Liquid Glass on iOS 26, a blur on
  older iOS, a see-through tinted fill on Android (no blur), and the solid card colour when Reduce
  Transparency is on or not yet known. A soft shadow lifts it off the list.
- **Items.** Equal widths. Icon 24 pt; label 10 pt, one line, growing with the system text size up
  to 1.3 times and no further, and shrinking to fit its slot rather than being cut off. The pill
  keeps its height at every text size.
- **Built as our own component**, passed to the navigator as `tabBar`. Restyling the library's own
  bar was considered and rejected: it pads itself for the home indicator, is laid out for full
  width, and the "+" and the rounded shape would fight it on every detail.

## Behaviour

- **Tab presses** send the same `tabPress` event the library's own bar sends, and navigate the same
  way when it is not prevented. So the existing tap tracking (`trackNavigation` listeners on Home,
  Transactions and Insights), the library's handling of a press on the tab already open, and
  Android's back button all behave as today.
- **"+"** opens `QuickActionSheet`, as today, and keeps the screen-reader name "Quick actions". It
  sends no navigation event and no tracking, as today (the sheet's own callbacks record the
  destination).
- **Keyboard.** On iOS the pill starts sliding out when the keyboard starts to open
  (`keyboardWillShow`/`keyboardWillHide`), over about 200 ms. Android does not announce the
  keyboard in advance, so there it uses `keyboardDidShow`/`keyboardDidHide`. With Reduce Motion on
  it hides and shows at once. While hidden it cannot be pressed and is hidden from screen readers.
- **Sheets and pop-ups** open above everything, the pill included, as today.
- **Screen readers.** Each tab has the tab role and its label; the selected one is marked selected.
  Every press area is at least 44 pt.
- **Tour.** The tour today shows only its card over a dimmed screen: `TourOverlay.tsx` has no
  spotlight yet (its own comment calls the spotlight "a deliberate follow-up"). Nothing about the
  tour changes. The pill's Home, Transactions, Insights and "+" register themselves as tour targets
  (`useRegisterTourTarget`) exactly as the current bar's do, so a later spotlight can find them.
- **No first-frame jump.** The pill's height comes from fixed sizes and the safe-area inset, so it
  is known before the first frame. The pill reports it through the library's
  `BottomTabBarHeightCallbackContext` (read in `@react-navigation/bottom-tabs` 7.20.0, the version
  `mobile/package-lock.json` pins), so `useBottomTabBarHeight()` and `BottomTabBarHeightContext`
  carry the pill's real height.

## Space at the bottom of every screen

Today no screen leaves room at the bottom, because the bar sits below the screens. With the pill
floating over them, every one has to.

- **`useTabBarSpace()`**, one shared helper. It returns the height the pill covers (pill height plus
  its bottom gap) plus 12 pt so the last row does not touch the pill. It reads our own
  `TabBarSpaceContext`, which `AppTabs` provides around its navigator, and is 0 outside it. (As
  first written it read the library's `BottomTabBarHeightContext`; every screen test runs under a
  stand-in for `@react-navigation/native` that the library cannot load under, so the build uses our
  own context with the same number. The pill still reports its height to the library.) Layout
  pieces used both inside and outside the tabs (the sign-in card layout `AuthScreenLayout`)
  therefore get space only inside the tabs.
- **The space does not change while the pill hides for the keyboard**, so lists do not jump when
  typing starts.
- **Scrolling screens.** The bottom padding of each screen's main vertical scrolling list grows by
  that space, and on iOS its scroll indicator stops above the pill. Sideways strips (the goals and
  health rows on Home) are left alone. The screens, 35 in all:
  - Tabs: Dashboard, Ledger (Transactions), Import, Insights.
  - More stack: More, Accounts, Category Review, Money Review, Gmail Review, Statement History,
    Budgets, Paywall and My Subscription (both shown by Subscription), Reports, Advanced Reports,
    Fyn, Goals, Investments, Financial Memory, Journey, Wrapped, Profile, Settings, Settings
    General, Security, Categorization, Data, Connected Apps, Bank Sync, Bank Sync Confirm,
    Account, Referrals, Verify Email Change (through `AuthScreenLayout`), Support Tickets, Support
    Ticket Detail.
- **Fyn's message box** is pinned to the bottom. With the keyboard closed it sits above the pill;
  with the keyboard open the pill is hidden and the box sits right above the keyboard, as today.
- **Money Review** adds `insets.bottom` to its own bottom padding today. That is replaced by the
  helper, so it does not get both.
- **Toast** (`components/Toast.tsx`) is placed today with a guessed bar height
  (`APPROX_TAB_BAR_HEIGHT = 56`). It mounts outside the tab navigator, so it cannot read the
  library's context; it moves to sit above the pill using the same fixed sizes the pill uses.

### Guard

A source-scanning test in the mobile suite (`mobile/src/navigation/tabBarSpace.guard.test.ts`, the
same pattern as `glassMigration.test.ts`; first written here as a script in `scripts/`) reads the navigation files to
find every screen reachable from the tabs and fails if a screen file (or the shared layout it
renders through) holds a vertical scrolling list but does not call `useTabBarSpace()`. It is a text
check: it proves each screen asks for the space, not that the space works. That is what the
simulator pass below is for. The guard is shown to fail by removing the helper from one screen.

## Testing and checks

Tests are written before the code they cover.

- **Pill:** five items in order; the selected tab marked selected; a press sends `tabPress` and
  navigates; "+" opens the quick-action menu; labels one line and capped at 1.3; hides on keyboard
  show and returns on hide, at once under Reduce Motion; solid fill under Reduce Transparency;
  480 pt cap on a wide screen; the height it reports.
- **`useTabBarSpace()`:** with and without a home indicator, and 0 outside a tab navigator.
- **Fyn and Toast:** the message box above the pill with the keyboard closed; the Toast above the
  pill.
- **Guard:** passes on the finished tree; fails with the helper removed from one screen.
- **Whole mobile check under Node 22:** jest, `tsc --noEmit`, `expo lint --max-warnings 0`, the
  Android bundle export and the repository guards.

**On the iPhone simulator**, signed in to the owner's account against production, read only:

- Each of the 35 screens scrolled to the very bottom: the last row clears the pill. The result for
  each is recorded in the build record.
- Light and dark; the largest ordinary accessibility text size; the keyboard on Transactions search
  and on Fyn; each tab and "+"; the tour run once from the start; an iPad simulator for the 480 pt
  cap.

**Speed.** Glass over a moving list asks more of the GPU than glass over a still backdrop. Scrolling
on Transactions is profiled before and after with the method used for the Transactions redesign
(Time Profiler, sample counts on the app's own threads, three runs each). If scrolling is worse,
work stops and the numbers go to the owner before anything else.

## Not covered

- **Android** is not checked unless the owner signs in on a test build, or agrees to uninstalling
  the signed build on the emulator.
- **iOS below 26:** no simulator below iOS 26 on this machine.
- **A physical phone**, and **Reduce Transparency on a device** (unit tests only).

## Out of scope

- A tour spotlight (it does not exist yet).
- Apple's own native tab bar (`@react-navigation/bottom-tabs/unstable`).
- Any change to what the tabs contain or where they lead.

## Shipping

One pull request. It ships in the next store build (1.1.0) and must not be sent over the air to the
1.0.0 runtime.

## Build record (2026-10-15)

Built on `feature/mobile-floating-tab-pill` from main at `2f25e0248`. Checked on an iPhone 17 Pro
simulator (iOS 26.5) signed in to the owner's account against production, read only. Nothing was
deleted, edited, added, sent or answered there.

### What the build changed against this design, and why

- **The bottom-space helper reads our own context.** See "Space at the bottom of every screen".
  Found by a probe before the plan was written: importing `@react-navigation/bottom-tabs` under the
  test setup's stand-in for `@react-navigation/native` fails (`createScreenFactory is not a
  function`), which would have meant a new mock in 35 screen test files.
- **The guard is a test in the mobile suite**, not a script in `scripts/`.
- **Labels shrink to fit their slot.** Seen on the simulator at `accessibility-large`: the
  "Transactions" label at 1.3 times showed as "Transacti…". A 320 pt wide phone gives each slot
  about 56 pt even at the default size. Fixed test-first (`adjustsFontSizeToFit`,
  `minimumFontScale` 0.7) and seen fixed on the simulator.
- **The pill's row is a tab list for screen readers** (`accessibilityRole="tablist"`), as the
  library's own bar is (`BottomTabBar.tsx`: `role="tablist"`). Found in the bug review after the
  first push: without it VoiceOver and TalkBack read the items as unrelated buttons. React Native
  maps the role to the iOS tab-bar trait (`RCTConversions.h`) and handles it on Android
  (`ReactAccessibilityDelegate.kt`). On the simulator each tab reads "button" and the open one
  "selected"; the row's own trait was not read back on the device.
- **Import's "Import complete" summary scrolls.** Found in the same review: it was a plain view with
  a warnings list of any length and three buttons below it, and the screen now runs under the pill,
  whose top edge sits about 9 pt higher than the old bar's. A long warnings list or large text would
  have left "View in Ledger" under the pill with no way to reach it. The guard did not catch it
  because the same file scrolls in its other states; a scan of every non-scrolling state in the 35
  screens found no other such case (the rest are centred loading and error messages).
- **A second tap on a tab scrolls its screen back to the top.** Added at the owner's request on
  2026-10-15, for all four tabs. Home, Transactions, Insights and More hand their list to React
  Navigation's `useScrollToTop`, which acts on the pill's `tabPress` event only when the tab is
  already open. On More, a second tap first returns to the More menu (the stack pops), then scrolls
  it. Seen working on the simulator for all four.
- **Two Settings screens had no theme hook** to place the new hook after; it went after their first
  hook (`SettingsScreen`) or first in the component (`SettingsConnectedAppsScreen`, which has none).

### Speed

Time Profiler, sample counts on the app's own threads in the busiest 11 seconds of a 26 second
recording, ten swipes down the Transactions list, a fresh app load before each run. Before is the
base commit served from a separate checkout, after is this branch, same session.

| | JavaScript thread | Main thread |
|---|---|---|
| Before (three runs) | 1259 / 1294 / 988 | 985 / 1009 / 666 |
| After (three runs) | 1143 / 1214 / 1193 | 789 / 916 / 868 |

The after runs sit inside the before range on both threads. Not measured: the system's render
process, where the glass is composited.

### Bottom of every screen

Each screen was scrolled to its end and its last row checked against the pill: 29 of the 35 screens
were checked and every one cleared the pill. Six could not be opened on this account:

| Screen | Why not checked |
|---|---|
| My Subscription | shown only to a paying account (Subscription showed Paywall, which passed) |
| Bank Sync Confirm | opens only from a bank link |
| Connected Apps | not listed in Settings on this account |
| Gmail Review | opens only from Connected Apps |
| Support Ticket Detail | no tickets on this account |
| Verify Email Change | opens only from an email-change link; its layout is covered by `AuthScreenLayout.tabBar.test.tsx` |

Also seen: the keyboard on Transactions search and on Fyn hides the pill and brings it back when it
closes; Fyn's message box sits above the pill with the keyboard down; "+" opens its menu above the
pill; a second press on More after opening a screen under it returns to the More menu; the iOS
scroll indicator stops above the pill; the dark theme; the largest ordinary text size (after the
label fix above).

### Not verified

- **Android.** Not looked at on a device or emulator. Open: Android reports the keyboard only once it is up, so the pill may ride up with the keyboard for a moment before sliding away; and the shadow (elevation) under the see-through pill.
- **iOS below 26**, a **physical phone**, and **Reduce Transparency** on a device (unit tests only).
- **iPad.** An iPad simulator exists, but the app there needs its own sign-in; the 480 pt cap is
  covered by unit tests only.
- **The tour**, which would mean resetting onboarding on the owner's production account. Its target
  registration is covered by `AppTabs.test.tsx`.

### Found in passing, not changed

- `AppTabs.test.tsx` prints "You are trying to `require` a file after the Jest environment has been
  torn down": its existing "+" menu test fires a real tracking call. Same on the base commit.
- With the keyboard up, Fyn's message box keeps its own bottom padding (safe-area inset plus 8 pt),
  about 42 pt above the keyboard, as before this change.
- `npm ci` warns that the `expo-screen-capture` patch was made for 57.0.2 and applied to 57.0.4. The
  patch applied.
