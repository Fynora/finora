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

## Destination mapping

| Group | Item | Web today | Mobile today | Change |
|---|---|---|---|---|
| *(root)* | Home | Sidebar #1, labelled "Dashboard" | Tab "Home" | Rename web |
| Money | Accounts | Sidebar #5 | More #1 | Regroup both |
| Money | Transactions | Sidebar #6 | Tab | Regroup web; mobile keeps tab and gains group entry |
| Statements | Import Statement | Sidebar #2 | FAB → QuickActionSheet | Regroup web; mobile keeps FAB and gains group entry |
| Statements | Statement History | Sidebar #3 | More #9 | Regroup both |
| Statements | Review Categories | absent | More #8 | Slot ships empty on web — see Out of scope |
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

The collapsed sidebar state (`Sidebar.tsx:79`, `w-20`) must keep working. Group headers have no
sensible collapsed rendering as text, so in the collapsed state they become separator rules between
icon runs rather than labels.

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

## Files affected

Navigation definition only. No screen, route, or API changes.

**Web**
- `frontend/src/components/Sidebar.tsx` — introduce a grouped structure in place of the flat `links`
  array; render group headers; handle the collapsed state; rename Dashboard to Home; align the
  avatar popup's contents with the "Your Account" group, adding Support.
- `frontend/src/components/Sidebar.test.tsx` — existing assertions against the flat list will need
  updating.

**Mobile**
- `mobile/src/screens/MoreScreen.tsx` — introduce groups in place of the flat `MENU_ITEMS` array;
  render section headers; move Profile into "Your Account"; add Support, Transactions, Insights and
  Import Statement entries.
- `mobile/src/navigation/AppTabs.tsx` — no structural change expected; verify the `MoreStack` routes
  referenced by the new entries all exist.

**Shared**
- Consider a single source of truth for group names and membership, checked by a test on both sides,
  so the two lists cannot drift again. The repo has no `frontend`/`mobile` shared package today —
  each maintains its own hand-written `src/types/index.ts` — so this is likely a mirrored constant
  plus a drift test rather than a real shared module. Decide during planning.

## Testing

- Web: `Sidebar.test.tsx` must assert group membership and order, not just presence of links.
  Collapsed-state rendering needs its own case.
- Mobile: a `MoreScreen` test asserting the same group membership and order.
- A drift check asserting the two clients' group definitions match, if the mirrored-constant approach
  is taken.
- Accessibility: group headers must be exposed as headings, not as unlabelled text, on both clients.
  Mobile's existing screen-reader considerations around back affordances (`AppTabs.tsx:66-70`) are
  unaffected.
- Note: `mobile/src/lib/invalidateFinancialData.test.ts` fails on any new `queryKey` not classified
  as refreshed or excluded. No new queries are introduced here, so it should not trip, but it is a
  known trap in this area.

## Out of scope

Each of these is a real finding from the same investigation, deliberately not bundled into this
change:

- **Review Categories does not exist on web.** `mobile/src/screens/CategoryReviewScreen.tsx:21-25`
  describes itself as the mobile half of "Ask Once, Learn Forever" and notes that Settings promised a
  review queue that "had nowhere to lead." Web has no such screen and no `/app/category-review`
  route — it exposes only a "Needs Review" badge on Ledger rows (`Ledger.tsx:199`). The taxonomy
  gives the destination a slot; the slot simply stays empty on web. Building the screen is a product
  decision.
- **Ask Fyn's two interaction models.** Web promoted Fyn from a sidebar page to a persistent header
  icon, as its own comment at `TopBar.tsx:141` records; mobile still treats it as a destination.
  Membership is unified here, but bringing mobile to the ambient model is separate work.
- **`/app/wrapped` is orphaned on web.** The route is registered at `frontend/src/App.tsx:169` and a
  search across `frontend/src` finds no inbound link — it is reachable only by typing the URL. Mobile
  reaches Wrapped from the Journey screen. The fix is a Journey → Wrapped link on web, unrelated to
  grouping.
- **Notifications have no shared model.** Web renders a TopBar bell (`TopBar.tsx:187`); mobile renders
  the same `summary.notifications` payload as a "Next Actions" card inside the dashboard body
  (`DashboardScreen.tsx:762-776`). Chrome rather than a destination, so outside this taxonomy, but it
  deserves its own decision.

## Open questions

- Should the shared group definition be enforced by a drift test, or left as convention? The answer
  determines whether this ships as two independent edits or as a small piece of shared scaffolding.
- Does the web sidebar need a visual treatment for group headers at all, or would separators alone
  suffice? This affects the collapsed state directly.

## What is not established

This is a structural proposal derived from reading source. No user testing supports it, and no
measurement in this repository shows what the current flat lists cost anyone. The group names are
conventional choices, not validated ones. If any grouping should be settled by evidence rather than
by convention, that evidence does not exist yet and would need to be gathered first.
