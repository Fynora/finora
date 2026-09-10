# Dashboard "Passbook" Redesign — Design Spec

**Status:** Approved by Sid, decided live across chat (2026-09-10). This doc consolidates those
decisions into one record; it does not re-litigate them. Two open questions surfaced only at
implementation-planning depth are called out at the end and need an answer before their tasks start.

## Direction

"A beautifully maintained digital passbook, not a fintech analytics app." Calm, premium, timeless,
trustworthy. Not CRED, not crypto dashboards, not generic fintech-purple.

## Token system

**Color** — existing graphite/cream palette (`mobile/src/theme/palette.ts`) unchanged. One new
accent, additive only:

- `brass` `#B8862E` — used only for the Financial Health Seal, goal progress rings, and the
  Financial Note (formerly AI Insight) accent. Never for buttons, navigation, general cards,
  charts, or backgrounds.

**Type** — finish the existing Manrope/Inter rollout (`mobile/src/theme/fonts.ts`, already loaded,
used in exactly one file today — `AuthScreenLayout.tsx`). Manrope ExtraBold for major money
figures, Manrope Bold for section headers, Inter for everything else. No new typefaces.

**Layout** — reduce visible borders, rely on whitespace and typography for hierarchy — **scoped to
Dashboard only**, via one new component (`DashboardCard`), not a change to the shared `Card.tsx`.

**Signature** — the Financial Health Seal: brass arc draws in 0 → score once per Dashboard mount,
score counts up in sync, then settles. No loop, no re-trigger on refresh, no persistence
(`AsyncStorage` or otherwise) — "once per mount" means once per navigation to the tab, not
once-ever.

## Scope boundary (confirmed via codebase audit, not assumed)

Dashboard-only. Confirmed 29 screens total in the app; 68 files reference the `c.border` theme
token app-wide; only ~8 files are Dashboard-scoped. A whole-app version of this would need a
shadow/elevation token that doesn't exist anywhere in the app today, and would touch `TextField`,
whose border doubles as the only validation-error signal (`borderColor: error ? c.danger :
c.border`) — real prerequisites, explicitly deferred, not built now.

**Left untouched:** `Card.tsx`, `TextField`, `DateField`, every other shared design-system
primitive, every non-Dashboard screen.

## Component classification (audited against actual files, `mobile/src/components/dashboard/`)

| Component | Today | Decision |
|---|---|---|
| `HealthHero.tsx` | Bespoke, no `Card` dependency, dark `primaryDark` surface, SVG semi-circle gauge | Redesign in place |
| `HealthFactorsRow.tsx` | Local card style (not `Card`), already has name/score/status-pill/suggestion/detail-toggle/opportunity-highlight — no progress bars | Redesign in place (style pass only — see "HealthFactorsRow" below) |
| `AccountsCard.tsx` | Uses shared `Card` | Redesign in place |
| `CashFlowMiniCard.tsx` | Uses shared `Card` | Redesign in place |
| `AIInsightCard.tsx` | Local card style (not `Card`) | Redesign in place (rename to Financial Note, retone copy) |
| `MonthlySnapshotGrid.tsx` | Uses shared `Card`, 4 separate KPI cards in a grid | **Replace** with a new single-list component |
| `GoalsRow.tsx` | Local card style (not `Card`), text-percentage progress bar | **Replace** with brass-progress-ring cards |

**New components:** `DashboardCard` (Dashboard-only borderless/whitespace surface wrapper),
`LedgerSnapshotCard` (replaces `MonthlySnapshotGrid`'s 4-card grid with one "This Month" list),
`FinancialHealthFactorCard` (the per-factor card `HealthFactorsRow` maps over, extracted so its
existing logic — `healthImprovementSuggestion`, `scoreLabel`, the `Why?`/`Hide` toggle — moves
verbatim into a restyled shell).

## HealthFactorsRow — correction made during planning

Sid's original ask assumed the current implementation used progress bars and needed to be built
from scratch. Verified against `HealthFactorsRow.tsx` and `lib/health.ts`: no progress bars exist.
The component already renders factor name, rounded score, a status pill (`scoreLabel`:
Excellent/Good/Fair/Needs Attention), an always-visible deterministic suggestion
(`healthImprovementSuggestion`, ported verbatim from web), a top-opportunity highlight, and a
`Why?`/`Hide` toggle that reveals `breakdownDetail` (the backend's own explanation text).

**Decision:** keep all of that logic and interaction exactly as-is. This task is a chrome pass only
— move `HealthFactorsRow` and its per-card unit onto the new `DashboardCard`/whitespace treatment,
improve typography, do not touch `healthImprovementSuggestion`, `scoreLabel`, the toggle behavior,
or the factor/backend data contract. `DashboardScreen.test.tsx`'s `'shows the score and breakdown
once available, with each row's detail hidden until asked for'` test (asserts `Debt Score`, `100%`,
`Hide`, etc.) must still pass unmodified.

## Placement

Core narrative order, top to bottom: Header → Health Hero → Health Factors → Monthly Snapshot
(→ `LedgerSnapshotCard`) → Accounts (paired with Cash Flow mini, as today) → Spending by Category
→ Goals → Financial Note → Recent Transactions → Getting Started (`ChecklistWidget`).

This list names the *core* sections. `DashboardScreen.tsx` currently has 17 sections, not 11 — see
"Open question 1" below for the ones this list doesn't mention.

## Empty states

No generic "No data available." Every empty state names the next action:
- No Health Score: "Import a statement to generate your first Financial Health Score." (`HealthHero`'s
  existing `available: false` branch already does this in substance — "Getting Started" +
  "Continue Setup" CTA — copy tweak only, not new UI.)
- No Goals: "Start with an Emergency Fund goal."
- No Spending History: "Import your first statement to unlock spending insights." (`EmptyState`
  message on the Spending by Category card, copy-only change.)

## Motion

Allowed: Health Seal draw-in + count-up (first mount per screen visit only), number count-ups on
`LedgerSnapshotCard`'s figures (reuse `AnimatedNumber`, same component `MonthlySnapshotGrid`
already uses), a subtle stagger fade-in on first load. Avoid: loops, pulsing, decorative-only
motion.

## Open questions — need an answer before their specific task starts

**1. Placement of the 8 sections not named in "Placement" above.**
`DashboardScreen.tsx` currently renders, in order: coverage-gap banner, review-queue nudge,
limited-history banner, Health Hero, Health Factors, Monthly Snapshot, **Quick Actions**, Cash Flow
mini + Accounts, Spending by Category, **Upcoming/Recurring**, Goals, Financial Note,
**Categorization Confidence**, **Next Actions**, **Detected Issues**, the full **Cash Flow chart**
card (3M/6M/12M toggle), Recent Transactions, **Budget Progress**, **Insights** (sentences),
Getting Started. The bolded six + the full Cash Flow chart aren't mentioned in the requested order.
Recommendation: leave them in their current relative order, inserted after Recent Transactions and
before Getting Started (lowest-risk option — doesn't require a decision about any individual
card's importance). Needs a yes/no, not a guess.

**2. The Health Seal's progress arc color.**
Today `HealthHero`'s progress arc uses `healthBarColor` (red/amber/green by score band) — the same
semantic color every other health display on the screen uses (breakdown pills, Categorization
Confidence). The spec says "Brass progress arc." Replacing the arc's color with a fixed brass tone
would break that semantic consistency (a 34/Needs-Attention score would draw the same brass arc as
a 91/Excellent one). Recommendation: keep the arc's health-semantic color as-is, apply brass to the
seal's *frame* instead — e.g. the outer ring/plate behind the gauge, or the delta pill — so
"brass = this is the signature element" survives without erasing the red/amber/green signal.
Needs a yes/no.

**3. Bank logo port (`AccountsCard`), asked earlier this thread, not yet answered.**
Web already has a 3-stage `BankLogo` resolver (Logo.dev CDN → locally bundled SVG → colored
initials) at `frontend/src/components/BankLogo.tsx`; mobile has none of it and
`EXPO_PUBLIC_LOGODEV_TOKEN` doesn't exist in `mobile/.env.example` yet. Porting it is real,
bounded work (new RN component using `Image`+`onError` chaining instead of Vite's
`import.meta.glob`, a new env var, the same attribution caveat web's own file already flags as
unresolved) but it's a second, independent piece of work from the visual redesign — it changes
what data source feeds the avatar, not how the avatar looks. Treated as **out of scope for this
plan** unless Sid says otherwise; `AccountsCard`'s redesign task below keeps the existing
colored-initials avatars exactly as they render today.
