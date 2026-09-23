# Shared Navigation Taxonomy — Design Spec

Date: 2026-09-23
Status: Approved for planning
Evidence base: source read against `origin/main` @ `b7f2523f`

## Purpose

Web and mobile present the same destinations in two unrelated arrangements. A user who learns one
client gains nothing on the other. This spec defines a single shared taxonomy — one set of groups,
one membership list — that both clients organise their navigation around, while each continues to
render those groups in its own platform-native way.

The underlying principle is Jacob's Law applied internally: a user expects the second Fynora client
to work like the first. Today it does not.

The spec covers two layers, shipping as one track:

1. **The taxonomy** — one shared set of groups, rendered natively on each client.
2. **Convergence work** — the four divergences grouping alone cannot fix: web has no Review
   Categories screen, Ask Fyn is ambient on one client and a destination on the other, notifications
   use two unrelated surfaces, and `/app/wrapped` has no inbound link on web.

No backend change is required by any of it.

## The problem, measured

Nine destinations appear in both the web sidebar (`frontend/src/components/Sidebar.tsx:20-43`, 12
flat items) and the mobile More menu (`mobile/src/screens/MoreScreen.tsx:34-47`, 13 flat items). The
two sequences agree at no position:

| # | Web sidebar | Mobile More |
|---|---|---|
| 1 | Dashboard | Accounts |
| 2 | Import Statement | Investments |
| 3 | Statement History | Budgets |
| 4 | Financial Memory | Goals |
| 5 | Accounts | Reports |
| 6 | Transactions | Advanced Reports |
| 7 | Budgets | Ask Fyn |
| 8 | Goals | Review Categories |
| 9 | Investments | Statement History |
| 10 | Reports | Financial Memory |
| 11 | Advanced Reports | Subscription |
| 12 | Insights | Refer & Earn |
| 13 | — | Settings |

Neither list is grouped. Web renders a single `<nav>` with `space-y-1` and no section headers
(`Sidebar.tsx:123-144`); mobile maps `MENU_ITEMS` straight into one flat list
(`MoreScreen.tsx:113-124`). Twelve and thirteen ungrouped items are both past the point where a
person scans rather than reads.

Four further divergences follow from the same root cause and are addressed here: account-scope items
sit in different containers, two destinations carry different names on each client, Support sits
three levels deep on mobile against one click on web, and Ask Fyn is modelled as ambient chrome on
one client and a destination on the other.

## The governing rule

**Groups and group membership are shared. Rendering is platform-native.**

This is not a new pattern for this codebase. Settings already works exactly this way:
`frontend/src/pages/settings/SettingsNav.tsx:5-12` and `mobile/src/screens/SettingsScreen.tsx:12-19`
declare the same seven categories, with the same labels, in the same order — then web renders them
as a `?tab=`-driven master-detail pane (`frontend/src/pages/Settings.tsx:17-43`) and mobile pushes a
native stack screen. Structure shared, presentation native, and no one has ever had to reconcile the
two.

This spec extends that same rule from the Settings level up to the top level. It does not change
Settings itself.

## The taxonomy

Five groups plus an ungrouped root.

**Home** *(root, ungrouped)*

**Money**
- Accounts
- Transactions

**Statements**
- Import Statement
- Statement History
- Review Categories
- Financial Memory

**Planning**
- Budgets
- Goals
- Investments

**Analysis**
- Insights
- Reports
- Advanced Reports
- Ask Fyn

**Your Account**
- Profile
- Subscription
- Refer & Earn
- Settings *(opens the existing seven-category Settings taxonomy, unchanged)*
- Support

Group sizes are two to five. No group name collides with an item inside it — that is why the group
containing Accounts is named "Money" and the group containing Insights is named "Analysis". Each
group name is a word consumer finance products already use; borrowing existing vocabulary rather than
inventing new terms is the point.

### Why Financial Memory sits in Statements

This is the least obvious placement in the taxonomy, and a reader could reasonably expect it under
Analysis, so the reasoning is recorded here rather than left implicit.

Financial Memory is grouped with Statements because everything it reports is import-and-categorisation
provenance, not financial analysis. The web screen (`frontend/src/pages/FinancialMemory.tsx:52-125`)
is titled "What Fynora remembers" and surfaces exactly these metrics: History, Completeness, Accounts
connected, Transactions processed, Merchants identified, Rules learned, Manual corrections, and
recognised recurring payments.

Not one of them is about spending, income, or a financial outcome. "Rules learned" and "Manual
corrections" are literally the output of the Review Categories loop, which sits in the same group.
Financial Memory answers *how well has Fynora understood my statements*, which is a Statements
question; Analysis answers *what is happening to my money*.

Its Identity Engine siblings, Journey and Wrapped, are retrospectives about the user's money and are
deliberately outside the taxonomy entirely — so shared provenance in that plan is not an argument for
grouping Financial Memory with them.

## Destination mapping

| Group | Item | Web today | Mobile today | Change |
|---|---|---|---|---|
| *(root)* | Home | Sidebar #1, labelled "Dashboard" | Tab "Home" | Rename web |
| Money | Accounts | Sidebar #5 | More #1 | Regroup both |
| Money | Transactions | Sidebar #6 | Tab | Regroup web; mobile keeps tab and gains group entry |
| Statements | Import Statement | Sidebar #2 | FAB → QuickActionSheet | Regroup web; mobile keeps FAB and gains group entry |
| Statements | Statement History | Sidebar #3 | More #9 | Regroup both |
| Statements | Review Categories | absent | More #8 | **Built on web** — see Convergence work |
| Statements | Financial Memory | Sidebar #4 | More #10 | Regroup both |
| Planning | Budgets | Sidebar #7 | More #3 | Regroup both |
| Planning | Goals | Sidebar #8 | More #4 | Regroup both |
| Planning | Investments | Sidebar #9 | More #2 | Regroup both |
| Analysis | Insights | Sidebar #12 | Tab | Regroup web; mobile keeps tab and gains group entry |
| Analysis | Reports | Sidebar #10 | More #5 | Regroup both |
| Analysis | Advanced Reports | Sidebar #11 | More #6 | Regroup both |
| Analysis | Ask Fyn | TopBar widget (`TopBar.tsx:141`) | More #7 | Membership shared; each keeps its current affordance |
| Your Account | Profile | Avatar popup | More header row | Membership shared |
| Your Account | Subscription | Avatar popup, labelled "Billing" | More #11 | Rename web |
| Your Account | Refer & Earn | Avatar popup | More #12 | Already consistent |
| Your Account | Settings | Avatar popup | More #13 | Membership shared |
| Your Account | Support | TopBar help menu (`TopBar.tsx:235`) | Under Settings → Help & Support | Promote on mobile |

### Deliberately outside the taxonomy

- **Journey** and **Wrapped** — contextual destinations. Journey is reached from a dashboard widget
  on web (`JourneyWidget.tsx:56`) and from `DashboardScreen.tsx` on mobile; Wrapped is reached from
  Journey on mobile. Neither is a nav item on either client today, and neither becomes one.
- **Search, Notifications, Theme** — chrome, not destinations.

## Taxonomy rules

The taxonomy is only worth defining if it survives the next twenty destinations. These four rules
exist so that future contributors do not have to re-derive the reasoning, and so that a well-meaning
cleanup cannot quietly undo it.

### Classification rule

A new top-level destination is grouped by the user's primary intent, not by which subsystem built it:

| User intent | Group |
|---|---|
| Move, inspect, or manage money | Money |
| Import, clean, categorise, or reconcile data | Statements |
| Plan future financial outcomes | Planning |
| Understand, analyse, or explain finances | Analysis |
| Manage account, subscription, support, or profile | Your Account |

Where a destination fits more than one group, choose the one representing its primary workflow, and
record the reasoning in this spec — as was done above for Financial Memory. Two plausible near-term
examples that the rule does not resolve cleanly on its own are Net Worth (a computed view, so
Analysis, though "inspect money" also reads as Money) and Insurance (protection of future outcomes,
so Planning, though it is also an account one manages). Both should be argued and recorded when they
arrive rather than assigned silently.

This rule governs **top-level** destinations only. Settings already has its own seven-category
taxonomy, and a destination that belongs inside it — Bank Sync and Connected Apps, for instance —
stays there and does not become a top-level entry.

### Shortcut rule

**Promotion does not change taxonomy membership.**

A destination may appear simultaneously in its taxonomy group, in the tab bar, in the header, behind
a FAB, and in contextual surfaces. Today that means Transactions and Insights are both tabs and group
items, and Import Statement is both the centre FAB and a group item.

Removing a destination from its taxonomy group because it has a shortcut elsewhere is a taxonomy
violation, not a cleanup. That removal is exactly what would put the two clients' group contents back
out of sync.

### Missing destinations

**Taxonomy membership and implementation availability are independent concerns.**

A taxonomy entry may exist with no implementation on one platform. In that case the destination stays
in the shared definition and is simply absent from that platform's rendered navigation.

A platform must not remove a destination from the shared taxonomy definition because its
implementation is pending on that platform. The asymmetry is the point — it records the gap rather
than hiding it.

Review Categories was the case that motivated this rule: a member of Statements with no web screen.
This spec now closes that gap rather than tolerating it (see Convergence work), so the rule currently
has no live instance — which is the intended steady state. It exists for the next one, and for the
window between a destination being agreed and its second-platform implementation landing.

### Governance

Any new top-level destination must:

1. Belong to exactly one taxonomy group.
2. Be added to the shared taxonomy definition — both clients' copies — in the same PR.
3. Carry an explicit rationale if it is also promoted to a tab, FAB, header action, or other shortcut.
4. Be reviewed against this taxonomy before release.

A destination may have many entry points but exactly one taxonomy home.

Requirement 2 is about the *definition*, not the implementation: adding a destination to both clients'
taxonomy definitions in one PR is compatible with shipping its screen on one platform first, per the
Missing destinations rule above.

**Who owns this is not decided here.** Naming a human or a role as the taxonomy owner is the
repository owner's call, and this spec deliberately does not invent one. What it can do is make the
rule machine-checkable instead of depending on an owner noticing — see Enforcement below.

## Rendering

### Web

The sidebar gains group headers. Home is pinned at the top, ungrouped, followed by four labelled
sections: Money, Statements, Planning, Analysis.

"Your Account" stays in the avatar popup at the bottom of the sidebar. The container is the
established web idiom and moving it would be change for its own sake; what changes is its
*contents*, which become exactly the five items listed above. Today that popup holds Profile,
Billing, Settings and Refer & Earn — Support is absent from it, living only in the TopBar help menu.

The TopBar is otherwise untouched. Search, Ask Fyn, theme, notifications and the help menu all stay
where they are.

**Group headers render as small uppercase labels when expanded, and as separator rules when
collapsed.** The collapsed sidebar state (`Sidebar.tsx:79`, `w-20`) must keep working, and header
text has no sensible rendering at 80px, so the rule carries the grouping instead. No new design
tokens are introduced — this is the lowest-risk treatment that keeps both states legible.

#### Active state

**Groups are static sections, not accordions.** Every group is always fully visible; nothing expands
or collapses per-group, and there is no per-group disclosure state to persist. The only collapse in
the sidebar is the existing whole-sidebar `w-64`/`w-20` toggle. This is worth stating plainly because
"grouped sidebar" invites the accordion reading, and that is not what is specified.

Given that:

- The active destination row is highlighted, exactly as today via `NavLink`'s `isActive`
  (`Sidebar.tsx:131-138`). Grouping does not change the mechanism.
- No group-level active or expanded state exists, because no group can be closed.
- **Group headers are organisational labels and are never selectable.** They carry no route, no
  focus, and no active state.
- Collapsed, only the destination icon is highlighted; the separator rules that stand in for headers
  never take an active state.

The existing `end: true` handling must be preserved. `Sidebar.tsx:33-41` records a real bug that was
found live: `/app/reports/advanced` is a nested child route, and `NavLink`'s default prefix matching
highlighted both Reports and Advanced Reports at once. Both items land in the Analysis group, so this
stays exactly as relevant after regrouping — and a test should cover it, since grouping is the kind of
refactor that quietly drops a prop.

### Mobile

The More menu gains the same five group headers, in the same order, with the same membership. The
Profile row moves out of its current special header position (`MoreScreen.tsx:85`) and into "Your
Account" alongside its peers.

Support is promoted out of Settings into "Your Account", reachable in two navigations instead of
four. The `SupportTickets` route already exists in `MoreStack` (`AppTabs.tsx`), so this is a menu
entry, not a new screen. The existing Help & Support section inside Settings may remain as a
secondary entry point.

### The tab bar is a shortcut layer, not a second taxonomy

Home, Transactions, Import and Insights stay exactly where they are
(`AppTabs.tsx:195-206`). Under this taxonomy they are understood as *promotions* of four items out
of their groups, chosen by frequency.

Promoted items **remain listed in their groups as well**. Duplicating a shortcut is standard practice
— iOS Settings and Gmail both do it — and removing a promoted item from its group is precisely what
would put the two clients' group contents back out of sync, which is the failure this spec exists to
prevent.

This means the mobile More menu will list Transactions and Insights even though both are also tabs,
and will list Import Statement even though it is also the centre FAB.

## Naming decisions

Two labels differ between clients and must resolve to one word each.

**"Dashboard" (web) versus "Home" (mobile) → Home.** More conventional for consumer finance, already
correct on mobile, and consistent with the owner decision recorded in
`docs/project-management/plans/mobile-web-parity-matrix.md` that mobile is intended to become a full
replacement for the web application rather than a companion — which makes mobile's vocabulary the
better anchor. Cost: renaming the web sidebar's most-used item.

**"Billing" (web) versus "Subscription" (mobile) → Subscription.** "Billing" describes the web screen
more accurately, since it shows invoices and payments. But mobile has no in-app cancel or
change-plan by design — the V4 billing spec cites App Store and Play Store policy — so "Billing"
would promise something one client can never deliver. "Subscription" is true on both. The web route
`/app/billing` is not renamed; only the label changes.

## Convergence work

The four divergences that the taxonomy alone does not resolve are in scope for this spec and ship
with it as one track.

### Mobile header actions — the shared container

Two of the four items (Ask Fyn, notifications) need a persistent, cross-screen affordance on mobile,
and **mobile has no header component today**: the tab navigator sets `headerShown: false`
(`AppTabs.tsx`), and each screen renders its own title.

It is not being invented from nothing, though. `DashboardScreen.tsx:392-415` already renders a
greeting row with an action on its right — a search button whose own comment describes it as "a
shorter path" to the Ledger's search, which is precisely what web's TopBar search is. That row is
mobile's de facto TopBar, limited to one screen and one action.

This spec generalises it into a shared component carrying three actions: **search, Ask Fyn,
notifications** — rendered on the Home, Transactions and Insights tabs.

**The hosting criterion:** the shared header appears only on primary tab destinations, because those
are the application's persistent work surfaces. Secondary and pushed screens keep their existing
navigation patterns, where a native back affordance is the priority and a row of global actions would
compete with it. That rule is what makes the set derivable rather than arbitrary — More is a menu
rather than a work surface and is excluded by the same criterion, and Import is a FAB-triggered flow
rather than a tab destination.

All three ship together, not Home first. Ask Fyn and notifications are both meant to become globally
reachable, and a Home-only rollout would leave one screen on the new model and two on the old — a
fresh inconsistency introduced by the change meant to remove one.

Deliberately *not* carried over from web's TopBar:

- **Theme** — a set-once preference, already in Settings → General on both clients. Web's one-click
  toggle is a convenience, not a structural affordance.
- **Help** — the taxonomy is already promoting Support into "Your Account" on mobile, which is a
  shorter path than a header menu.

### Ask Fyn on mobile

Ask Fyn gains an action in the header row, making it reachable from any primary tab rather than only
from the More menu. This follows the promotion web made deliberately, recorded at `TopBar.tsx:141`.

`FynScreen` stays as it is and the header action navigates to it. Web opens Fyn inline as a widget
(`FynWidget.tsx`) rather than navigating; that difference is acceptable and needs no reconciliation —
it is rendering, and rendering is platform-native under the governing rule.

Ask Fyn remains a member of the Analysis group on both clients. Per the Shortcut rule it is now both
a promoted shortcut and a group member, exactly as Transactions and Insights already are on mobile.

### Notifications on mobile

The header row gains a bell, matching web.

There is no new data and no backend work. Web's bell already reads `summary?.notifications`
(`TopBar.tsx:75`) — the identical payload mobile already renders as its "Next Actions" dashboard card
(`DashboardScreen.tsx:762-776`). The difference has only ever been the surface.

Read-state comes with it. Web tracks read notifications client-side under
`finora_read_notifications_<email>` (`TopBar.tsx:25-28`) and derives an unread count at `:76`. Mobile
has no read-state today; `mobile/src/lib/safeStorage.ts` is the equivalent helper and the same key
shape applies.

**The two helpers are not interchangeable, and the difference is load-bearing.** Web's
`safeStorage` wraps `localStorage` and is synchronous, so web reads the stored set during state
initialisation. Mobile's wraps `expo-secure-store` and is Promise-based (`getItemAsync` /
`setItemAsync`). Mobile therefore cannot derive the unread count synchronously on first render: it
needs an async load, and the initial render must not flash every notification as unread while that
load is in flight. Whether a lighter backing store than SecureStore is warranted for a
read-notification list is a planning question; the async constraint holds either way.

**Read-state is device-local and does not synchronise.** This is already true of web today and is
not a regression — but it should be stated so it is not later filed as a bug. Web's is scoped to one
browser profile on one machine, so the same user in a second browser sees everything unread again;
mobile's is scoped to one app install. Reading a notification on one device never marks it read on
another. Making read-state server-side would be a backend change and is out of scope.

**The "Next Actions" card stays — decided, not open.** The bell and the card are different surfaces
rather than duplicates: the bell carries status, awareness and global reachability; the card carries
prioritisation and action as dashboard content. Removing the card would make Home less informative,
put actionable items behind a tap, and require users to discover the bell first. That trades a real
usability loss for a mild consistency gain. Web already carries overlapping dashboard widgets and
navigation shortcuts, so more than one surface for the same underlying information is not anomalous
here. Revisit only if user research later says otherwise.

### Review Categories on web

Web gains the review queue at `/app/review-categories`, labelled "Review Categories", in the
Statements group — filling the slot the taxonomy currently leaves empty.

**Backend work: none.** All three endpoints already exist —
`TransactionController.java:64` (`/needs-review`), `:70` (`/groups/needs-review`) and `:77`
(`/groups/needs-review/by-counterparty`).

**Web client work is two API methods plus the screen.** `frontend/src/api/endpoints.ts` already
declares `needsReview` (`:267`), `updateCategory` (`:277`) and `bulkRecategorize` (`:283`). Only
`needsReviewGroups` and `needsReviewByCounterparty` are missing, and mobile's
(`mobile/src/api/endpoints.ts:233-239`) are the reference.

**The correctness constraint that must not be missed:** the three result sets are *disjoint by server
design*. The server removes anything returned by the group queries from the singles list, and a
merchant-matched row never reaches the counterparty grouping — `TransactionService.java:882-885` and
`TransactionGroupingService.java:21-23` both document this. They therefore **partition** the review
backlog and must be rendered together on one screen, not as alternative tabs or filters. Treating
them as alternatives would silently hide part of the backlog. Mobile's `CategoryReviewScreen.tsx`
renders all three together and is the reference implementation.

### Wrapped entry point on web

Web's Journey page gains a link to Wrapped, mirroring mobile, where `JourneyScreen.tsx` navigates to
it. The `/app/wrapped` route already exists (`App.tsx:169`); it simply has no inbound link, so it is
reachable today only by typing the URL.

This is a single link. Neither Journey nor Wrapped enters the taxonomy — both stay contextual.

## Files affected

Two layers: the taxonomy itself (navigation definition only) and the convergence work above (one new
web screen, two web API methods, one new mobile component, one web link). No backend changes anywhere.

### Taxonomy

**Web**
- `frontend/src/components/Sidebar.tsx` — introduce a grouped structure in place of the flat `links`
  array; render group headers; handle the collapsed state; rename Dashboard to Home; align the
  avatar popup's contents with the "Your Account" group, adding Support.
- `frontend/src/components/Sidebar.test.tsx` — three existing tests break by design and must be
  updated deliberately, not silently: `:124` asserts the account menu lists "Profile, Billing,
  Settings, Refer & Earn, and Log out in that order" (Support is added, Billing is relabelled), and
  `:135` / `:143` both refer to Billing by label. The collapsed-state tests at `:50`–`:80` must keep
  passing as-is.

**Mobile**
- `mobile/src/screens/MoreScreen.tsx` — introduce groups in place of the flat `MENU_ITEMS` array;
  render section headers; move Profile into "Your Account"; add Support, Transactions, Insights and
  Import Statement entries.
- `mobile/src/navigation/AppTabs.tsx` — no structural change expected; verify the `MoreStack` routes
  referenced by the new entries all exist.

**Shared**
- A single source of truth for group names and membership, plus the drift test that enforces it. See
  Enforcement below — this is a requirement, not an option.

### Convergence

**Web**
- `frontend/src/pages/ReviewCategories.tsx` — new. Mirrors mobile's `CategoryReviewScreen.tsx`,
  rendering all three disjoint result sets together.
- `frontend/src/App.tsx` — register `/app/review-categories`.
- `frontend/src/api/endpoints.ts` — add `needsReviewGroups` and `needsReviewByCounterparty`; the
  other three review methods already exist.
- `frontend/src/types/index.ts` — `MerchantGroup` and `CounterpartyGroup` types, which mobile already
  has and web does not. Derive these from the real response shape rather than copying mobile's on
  trust: the clients each maintain a hand-written `src/types/index.ts` with no shared package and no
  codegen, and `docs/project-management/plans/mobile-web-parity-matrix.md` records a case where one
  client's mirror had silently drifted from the backend enum.
- The Journey page — add the Wrapped link.

**Mobile**
- `mobile/src/components/ScreenHeaderActions.tsx` — new. Search, Ask Fyn, notifications.
- `mobile/src/screens/DashboardScreen.tsx` — replace the inline search button at `:405-415` with the
  shared component; keep the Next Actions card at `:762-776` unchanged.
- `mobile/src/screens/LedgerScreen.tsx`, `mobile/src/screens/InsightsScreen.tsx` — adopt the shared
  component.
- Notification read-state helper built on `mobile/src/lib/safeStorage.ts`, mirroring web's key shape.

## Testing

- Web: `Sidebar.test.tsx` must assert group membership and order, not just presence of links.
  Collapsed-state rendering needs its own case.
- Mobile: a `MoreScreen` test asserting the same group membership and order.
- The drift check asserting the two clients' group definitions match — required, see Enforcement.
- Web: the `end: true` behaviour is **already covered** and those tests must keep passing unchanged.
  `Sidebar.tsx:33-41` records a double-highlight bug found live when `/app/reports/advanced`
  prefix-matched Reports, and `Sidebar.test.tsx:102` and `:112` guard both directions of it. Both
  items land in the Analysis group, so regrouping must preserve the prop — these two tests are the
  safety net for that.
- Web: group headers must not be focusable or routable — assert they carry no link role.
- Accessibility: group headers must be exposed as headings, not as unlabelled text, on both clients.
  Mobile's existing screen-reader considerations around back affordances (`AppTabs.tsx:66-70`) are
  unaffected.
- **`mobile/src/lib/invalidateFinancialData.test.ts` will now trip.** It fails on any new `queryKey`
  not classified as refreshed or deliberately excluded. The taxonomy alone introduces no queries, but
  the convergence work does — the mobile notification read-state and any query the shared header adds
  must be classified there. This was a non-issue in the taxonomy-only draft and is a live requirement
  now.

**Convergence**

- Web `ReviewCategories`: the decisive test is that all three result sets render together. Assert
  that singles, merchant groups and counterparty groups are each present simultaneously — a test that
  only checks one of the three would pass against the exact bug this spec warns about.
- Web `ReviewCategories`: bulk recategorize applies to every transaction id in a group, and the list
  refetches after.
- Mobile `ScreenHeaderActions`: renders on Home, Transactions and Insights; each action is labelled
  for screen readers, matching the existing `accessibilityLabel` treatment on the search button it
  replaces.
- Mobile notifications: unread count derives from the same payload the Next Actions card uses, and
  read-state survives a remount — the mobile analogue of web's existing persistence test.
- Mobile notifications: **no unread flash on first render.** Because mobile's `safeStorage` is
  Promise-based where web's is synchronous, the naive port renders every notification as unread until
  the stored set resolves. Assert the pre-resolution state explicitly; this is the one bug the
  synchronous web implementation cannot have, so a straight port will not surface it in review.
- Mobile notifications: the Next Actions card still renders unchanged. It is easy to delete by
  accident while adding the bell.
- Web: the Journey page links to `/app/wrapped`. One assertion, but it is the whole fix for a route
  that is currently unreachable.

## Enforcement

**The shared taxonomy is enforced by an automated drift check, not by convention.**

Navigation consistency is the entire goal of this change. Relying on review discipline alone
reintroduces the exact failure mode being fixed — nobody chose today's divergence either; it
accumulated one reasonable PR at a time, and a search of `docs`, `frontend/src` and `mobile/src`
finds no record of any of it being decided.

**The mechanism is a mirrored constant on each client plus a drift test**, not a generated file or a
new shared package. The repo has no shared package between `frontend` and `mobile` today — each
maintains its own hand-written `src/types/index.ts` — and introducing build tooling for a single
navigation list is heavier than the problem it solves. Two constants and a test that fails when they
disagree gets the same guarantee with no new infrastructure.

The drift check must fail on: a group present on one client only, a membership difference, and a
group-order difference. It must *not* fail on a destination whose implementation exists on one
platform only, which is legitimate per the Missing destinations rule.

## Migration

**Labels change; routes do not.**

No redirect, route change, or deep-link change is required. `/app/billing` keeps its path and only
its label becomes "Subscription"; the web root keeps its path and only its label becomes "Home".
Route identifiers, deep links, and the `associatedDomains` / `intentFilters` already verified for
mobile are all untouched.

Two user-facing labels do change for existing users. **They ship silently — no in-app announcement.**
Both renames are self-evident in place: the item keeps its position, its icon and its destination,
and only the word changes. An announcement would cost more attention than the change itself does.

## Success criteria

**There is no product analytics in either client.** Verified: no PostHog, Mixpanel, Amplitude,
Segment, or Firebase Analytics dependency appears in `frontend/package.json` or `mobile/package.json`,
and no instrumentation calls exist in either `src` tree. So "monitor destination usage after launch"
is not currently an available option, and this spec will not pretend otherwise.

**Decision: instrument first.** Navigation usage counters are specified in
[`2026-09-23-navigation-analytics-design.md`](./2026-09-23-navigation-analytics-design.md) and land
before or alongside this change, so there is a pre-change baseline to compare against. Shipping a
navigation redesign with no way to observe its effect was the alternative, and it is not one worth
taking when the instrumentation turns out to be four counters on infrastructure that already exists.

What that spec can and cannot deliver bears directly on this one. It produces **aggregate** counts —
destination usage, the web-versus-mobile split, and the promoted-shortcut-versus-grouped-entry
split — which is enough to answer whether the usage distribution moved after grouping. It cannot
produce per-user funnels or discovery rates for an individual, because the published privacy policy
commits to non-identifying analytics. So "did a struggling user find Budgets faster" stays
unanswerable; "did Budgets' share of navigation change" does not.

What this change must not be evaluated on is implementation completion. "The sidebar now has headers"
is not evidence that anyone finds anything faster.

## Out of scope

The four convergence items that were previously listed here are now in scope — see Convergence work.
What remains out:

- **A "Next Actions" card on web.** Mobile keeps its card and gains the bell; web keeps the bell
  only. Adding the card to web was considered and not chosen.
- **Theme and Help in mobile's header.** Both stay where they are — theme in Settings → General on
  both clients, Support promoted into "Your Account" by the taxonomy.
- **Fyn's inline-versus-navigate difference.** Web opens Fyn as an inline widget, mobile navigates to
  a screen. Both are reachable from anywhere once this ships, which was the point; how each renders
  is platform-native under the governing rule.
- **The Identity Engine's own scope.** Journey and Wrapped stay contextual destinations on both
  clients. Only the missing web link is fixed.
- **Backend changes of any kind.** None are required by anything in this spec.

## Open questions

None remain open. All six raised during design and review are decided, and each decision is recorded
where it applies rather than listed here:

| Question | Decision | Recorded in |
|---|---|---|
| Drift-check mechanism | Mirrored constants plus a drift test | Enforcement |
| Web group-header treatment | Uppercase labels expanded, separator rules collapsed | Rendering → Web |
| Announce the two label changes? | Ship silently | Migration |
| Instrument before shipping? | Yes — see the analytics spec | Success criteria |
| Mobile: keep the Next Actions card? | Keep it alongside the bell | Notifications on mobile |
| Shared header: Home first? | All three primary tabs at once | Mobile header actions |

## What is not established

This is a structural proposal derived from reading source. No user testing supports it, and no
measurement in this repository shows what the current flat lists cost anyone. The group names are
conventional choices, not validated ones. If any grouping should be settled by evidence rather than
by convention, that evidence does not exist yet and would need to be gathered first.

The convergence work rests on the same footing, and in two places the reasoning is weaker than the
structural argument:

- **That a bell adds enough on mobile to be worth the surface.** The bell is justified by
  cross-client consistency and global reachability, not by measured usability. Note this is no longer
  a bell-versus-card question — the card stays, so the risk is narrowed to whether the bell earns its
  place beside it, which is a smaller and more reversible bet than replacing the card would have been.
- **That a persistent header action is the right home for Ask Fyn on mobile.** It follows web's own
  deliberate promotion, which is good evidence about intent but is not evidence about outcome — web's
  promotion was itself never measured.

What *is* established, by reading source rather than inference: the API and endpoint inventory, the
disjointness of the three review result sets, the absence of any inbound link to `/app/wrapped`, the
absence of a mobile header component, and the absence of product analytics in either client.
