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

## Resolved decisions (previously open questions)

**1. Placement of the sections not named in the original "Placement" list — RESOLVED: yes to the
recommendation.** Final order:

Header → Financial Health Seal → Health Factors → Monthly Snapshot (`LedgerSnapshotCard`) → Cash
Flow Mini + Accounts → Spending by Category → Goals → Financial Note → Recent Transactions →
Quick Actions → Upcoming/Recurring → Categorization Confidence → Next Actions → Detected Issues →
full Cash Flow Chart (3M/6M/12M) → Budget Progress → Insights → Getting Started
(`ChecklistWidget`).

The coverage-gap banner, review-queue nudge, and limited-history banner stay where they are today
(above the Health Seal) — they're conditional data-integrity warnings about the numbers below them,
not part of either the narrative or operational layer, and weren't proposed to move.

Rationale: the first part of the screen becomes the curated "financial story," the rest becomes the
operational layer. None of those 8 sections get redesigned — this is a pure JSX reorder.

**2. The Health Seal's progress arc color — RESOLVED: no, keep it semantic.** Red/amber/green stays
on the progress arc exactly as `healthBarColor` computes it today — a 34/Needs-Attention score must
never look like a 91/Excellent one. Brass is applied instead to: the seal's outer frame/decorative
ring, the score plate (background behind the score number), the month-over-month delta badge, and
`HealthFactorsRow`'s top-opportunity highlight text.

Note on the delta badge specifically: it currently colors by direction (green up / red down via
`successBg`/`dangerBg`) — the same shape of semantic signal as the arc. Sid explicitly chose brass
for it anyway, distinct from the arc's answer. Kept as instructed since the badge's `+`/`-` sign and
number already carry the direction as text even without color, unlike the arc where color is the
only signal that isn't the number itself.

**3. Bank logo — RESOLVED: in scope, but as a separate initiative, not part of this plan.** Sid
wants a shared `BankLogo` component (Logo.dev → local SVG → initials fallback, same hierarchy as
web's `frontend/src/components/BankLogo.tsx`) used everywhere the app shows bank/account identity —
Accounts, Transactions, Import, Statement History, Budgets, Goals, Investments, Transfers, Review
flows, and every account-picker modal/sheet, not just `AccountsCard`. That is a cross-app
consistency initiative spanning roughly 10 screens plus several modals, distinct in kind from a
Dashboard visual redesign. It gets its own audit, spec, plan, worktree, and branch — see the
companion doc `docs/superpowers/specs/2026-09-10-mobile-bank-logo-design.md` (audit findings) once
written. This plan's `AccountsCard` task keeps the current colored-initials avatars unchanged;
`BankLogo` adoption there happens as part of the separate initiative's own rollout, not this one.

## DashboardCard — corrected: subtle border, not zero border

Sid's follow-up correction: do not make `DashboardCard` fully borderless. The app has no
elevation/shadow system anywhere (confirmed during the earlier scope audit), so removing every
visual separator risks cards merging into each other. Instead: a hairline (not 1px) border in the
existing `c.border` token, plus more internal padding than `Card` uses, plus a soft shadow.
"Quiet, not flat" — softer borders and more breathing room, not zero borders and floating blocks.
See Task 2 in the implementation plan for the corrected styling.
