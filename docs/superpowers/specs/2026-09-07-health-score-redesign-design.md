# Financial Health Score redesign — design spec

Date: 2026-09-07
Status: approved by Sid, pending implementation plan

## Context

The Dashboard's "Financial Health Score" card ([Dashboard.tsx:435](../../../frontend/src/pages/Dashboard.tsx)) currently
shows a single number (0-100), a label (Excellent/Good/Fair/Needs Attention), and five horizontal
progress bars for the underlying factors (Savings Rate, Debt Score, Emergency Fund, Spend
Consistency, Cash Flow Stability). Sid asked for a premium redesign closer to the credit-score
dashboards common in consumer fintech apps (a semi-circular gauge, individual factor cards, an
AI-style insight card, and a trend sparkline).

The score itself is computed by `DashboardService.computeHealthScore()`
([DashboardService.java:485](../../../backend/src/main/java/com/finora/service/DashboardService.java))
and is **entirely stateless today** — every dashboard load recomputes it fresh from the trailing 6
months of transactions. There is no stored history, no month-over-month delta, and no "opportunity"
/ insight logic anywhere in the codebase. Building the trend sparkline and the monthly change
indicator therefore requires new backend persistence, not just a frontend visual pass.

A `Goals` feature already exists (`/app/goals`, `GoalController`/`GoalService`, generic
`name/targetAmount/currentAmount/targetDate` model, no query-param prefill support) — the AI
Insight card's "Create Goal" CTA links there directly, matching how the rest of the Dashboard
already links to Goals (no new prefill plumbing).

## Goals

1. Replace the progress-bar layout with a semi-circular gauge (0-100), monthly change indicator,
   and short explanation.
2. Replace the five bars with individual factor cards: score, status badge, explanation (reusing
   the existing `healthBreakdownDetail` text), and a short deterministic improvement suggestion.
3. Add an AI Insight card identifying the single factor with the largest realistic point-gain
   opportunity, with a "Create Goal" CTA.
4. Add a 6-month score trend sparkline.
5. Preserve everything the current card already does correctly: the `healthScoreAvailable` gate
   (fewer than `MIN_TRANSACTIONS_FOR_HEALTH_SCORE` transactions ⇒ onboarding-progress state, not a
   number) and the `isEmpty` gate (card hidden entirely for a zero-transaction account) stay
   exactly as they are today. Only the *available* rendering path changes.

## Non-goals

- No change to the underlying scoring formula (weights, sub-score calculations) — this redesign
  only adds new *derived* fields (delta, sparkline, insight) computed from the existing formula.
  It never recomputes what the formula already produces correctly.
- No AI/ML-generated copy anywhere. "Improvement suggestion" text and the Insight card's framing
  are deterministic templates keyed by factor name + score tier, not generated prose — consistent
  with how every other explanatory string on this Dashboard (`breakdownDetail`, notification text)
  is already server-computed, not invented per-request.
- No prefill plumbing for "Create Goal" — links to `/app/goals` like every existing Dashboard CTA.

## Backend changes

### New table: `health_score_snapshot`

```sql
CREATE TABLE health_score_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    year_month CHAR(7) NOT NULL,              -- e.g. '2026-09'
    overall_score INT NOT NULL,
    label VARCHAR(32) NOT NULL,
    savings_rate_score DOUBLE PRECISION NOT NULL,
    debt_score DOUBLE PRECISION NOT NULL,
    emergency_fund_score DOUBLE PRECISION NOT NULL,
    spend_consistency_score DOUBLE PRECISION NOT NULL,
    cash_flow_stability_score DOUBLE PRECISION NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, year_month)
);
CREATE INDEX idx_health_score_snapshot_user_month ON health_score_snapshot(user_id, year_month);
```

Explicit typed columns for the 5 factors (not JSONB) — the factor set is small, fixed, and already
named as Java `Map` keys in `HealthResult`; explicit columns keep them queryable without a JSON
path expression, and this schema already mixes both styles elsewhere so there's no established
single convention to defer to.

Exact next Flyway version number to be confirmed against `origin/main` at implementation time
(CLAUDE.md's migration-collision rule) — `V163` is the latest on `main` as of this spec.

### Persistence: on-demand upsert + nightly sweep (mirrors `NetWorthSnapshot`)

This codebase already solves an almost identical problem — tracking a computed financial metric
per user over time — for net worth: `NetWorthSnapshot` / `NetWorthSnapshotRepository` /
`NetWorthSnapshotSweepService`. The Health Score snapshot follows the exact same shape rather than
inventing a new one:

- **Idempotent upsert at the repository layer.** `HealthScoreSnapshotRepository` gets an
  `upsertForMonth(...)` method: a native `INSERT ... ON CONFLICT (user_id, year_month) DO UPDATE`
  query, annotated `@Transactional(propagation = REQUIRES_NEW)` **on the repository method itself**
  — matching `NetWorthSnapshotRepository.upsertForToday()` exactly. `REQUIRES_NEW` here serves two
  purposes at once: it gives the `@Modifying` query a transaction to run in regardless of the
  caller's own transactional state, and — critically — it keeps this write from ever landing inside
  `summarize()`'s transaction. `summarize()` is
  `@Transactional(readOnly = true)` ([DashboardService.java:56](../../../backend/src/main/java/com/finora/service/DashboardService.java)):
  a write nested inside a read-only transaction silently no-ops under Hibernate (read-only sets
  `FlushMode.MANUAL`; nothing ever flushes, no exception is thrown). This exact class of bug has hit
  this codebase before, which is exactly why `NetWorthSnapshotRepository`'s own doc comment calls
  out `REQUIRES_NEW` for this same reason.
- **On-demand**: `computeHealthScore()` calls the upsert whenever it returns an *available* result
  (`healthScoreAvailable = true`), keeping the current month's snapshot fresh the moment a user
  opens their dashboard.
- **Nightly sweep**: a new `HealthScoreSnapshotSweepService`, structured like
  `NetWorthSnapshotSweepService` — `@Scheduled(fixedDelay = ...)`, iterates users, resolves each
  user's "current month" against their own `User.timezone` (not a single global cutover), and calls
  the same upsert. This closes the gap for a user who has enough transaction history to score but
  doesn't open the dashboard that month.
- **Flag-gated**, matching `NetWorthSnapshotSweepService`'s own `app.net-worth-snapshot.sweep.enabled`
  convention exactly (`app.health-score-snapshot.sweep.enabled`, defaulting `true`, `false` in
  `application-test.yml`) — a background sweep writing rows mid-test is the same cross-test
  pollution class that convention already exists to prevent (BH-058).
- The upsert only ever touches **the current calendar month's** row, from either path. Once the
  month rolls over, prior months' rows are never rewritten — history stays genuinely frozen rather
  than silently reshaped if the formula or a user's data changes later.
- `year_month` is `period.calendarMonth()` (the user-timezone-aware actual current calendar month
  `DashboardService` already computes for budget evaluation, [DashboardService.java:262](../../../backend/src/main/java/com/finora/service/DashboardService.java)),
  not `period.month()` (which can lag behind if the user hasn't imported this month's statement
  yet) — the snapshot should reflect "what would the score be if computed today," matching what the
  live dashboard already shows.

### New `DashboardSummaryDto` fields

All four are `null`/empty exactly when `healthScoreAvailable` is `false`, mirroring the existing
gating convention for `healthBreakdown`/`healthBreakdownDetail`:

```java
Integer healthScoreDeltaVsLastMonth,   // this month's score - most recent prior snapshot's score;
                                        // null if no prior snapshot exists (not "+0" — a real absence)
List<HealthScorePoint> healthSparkline, // up to 6 trailing (yearMonth, score) points; gaps are
                                         // just missing months, never interpolated
String healthTopOpportunityFactor,      // e.g. "Emergency Fund"; null if every factor >= 80
Integer healthTopOpportunityPotentialGain // e.g. 18; null alongside the factor field
```

`HealthScorePoint` is a small new record: `record HealthScorePoint(String yearMonth, int score)`.
`healthSparkline` is ordered oldest-to-newest (matching `DashboardService`'s existing chronologically-ascending
`months` list), so the frontend can render it left-to-right without re-sorting.

### Insight formula (confirmed with Sid)

Reuses the exact weights `computeHealthScore()` already applies — no new constants, so this can
never drift out of sync with the overall score:

```
weights = { Savings Rate: 0.25, Debt Score: 0.20, Emergency Fund: 0.25,
            Spend Consistency: 0.15, Cash Flow Stability: 0.15 }

for each factor with breakdown[factor] < 80:
    gain(factor) = weight[factor] * (80 - breakdown[factor])

topOpportunity = factor with the largest gain(factor), if any exist
potentialGain = round(gain(topOpportunity))
```

A factor already at or above 80 (the existing "Good" threshold) is never a candidate — it isn't a
realistic opportunity. The result is also discarded if `potentialGain < 3`: a "+1 point" or "+2
point" opportunity reads as noise, not insight, and undermines the credibility of the ones that
are real. In either case (no candidate, or the best candidate rounds under 3) both new fields are
`null` and the frontend hides the Insight card entirely (see below) rather than showing a weak one.
This threshold is applied server-side, in the same place the candidate is selected — one source of
truth for "is this a real opportunity," not a frontend-side second check on backend data.

## Frontend changes

All in `Dashboard.tsx` and new files under `frontend/src/design-system/` (or a new
`frontend/src/pages/dashboard/` subfolder if the file grows unwieldy — a call for the
implementation plan, not this spec).

### Gauge

New component, e.g. `HealthScoreGauge`: SVG semi-circle (180°), `stroke-dasharray` fill technique
matching the existing landing-page `HealthScoreRing` ([HealthScoreRing.tsx](../../../frontend/src/pages/landing/hero/HealthScoreRing.tsx))
for the animation approach, but half-circle instead of full, and three colored zones per Sid's
spec: red (0-30), amber (31-60), green (61-100). This is a **coarser** 3-zone read than the
existing 4-tier `scoreLabel`/`healthColor` cutoffs (80/60/40) used for the text label beneath the
gauge — both stay, deliberately: real credit-score dashboards commonly pair a coarse gauge-color
with a finer text label, and unifying them would require either changing the gauge to 4 zones
(contradicts Sid's explicit red/amber/green spec) or changing the existing label thresholds
app-wide (out of scope here).

Below the gauge: score number (large), label text (reusing `scoreLabel`/`healthColor`, unchanged),
monthly change indicator (`healthScoreDeltaVsLastMonth`, arrow + color; hidden entirely when
`null`), and the existing short explanatory line. Beneath that, a compact 4-row range legend
(`0-40 Needs Attention`, `41-60 Fair`, `61-80 Good`, `81-100 Excellent`) with the row matching the
user's current score visually highlighted — an unfamiliar "51/100" means nothing on its own, and
this answers "what does 51 mean, how far to the next tier" without making the user hunt for it.

**Layout weight**: an unexplained proprietary score (unlike a credit score, this one has no
external, universally-understood meaning) shouldn't dominate the card. The gauge + range legend
should read as roughly 40% of the card's visual weight, with the factor cards below taking the
remaining ~60% — the explanation is what actually builds trust here, not the gauge.

### Factor cards

Five cards (one per breakdown entry), each showing:
- Score, shown as `NN / 100` (not a bare number) — reinforces the scale on every card, not just
  the gauge
- Status badge — reuses `scoreLabel(score)` + `healthColor(score)`-equivalent badge styling, no
  new vocabulary
- Explanation — the existing `healthBreakdownDetail[name]` text, unchanged
- Improvement suggestion — new deterministic template, one per factor name, parameterized by
  whether the factor is already ≥ 80 (a "keep it up" variant) or below (an actionable tip). Exact
  copy is an implementation-time detail, not a spec-level decision — the plan should draft it
  against the existing tone of `breakdownDetail` strings (plain, specific, no hype).
- **Only** on the card matching `healthTopOpportunityFactor` (when non-null): a small
  `↑ +N point opportunity` badge, reusing the same `healthTopOpportunityPotentialGain` value the
  Insight card shows. This cross-references the two rather than making the Insight card the only
  place the opportunity is visible — the factor card itself makes it obvious without adding a
  second large panel competing for attention.

### AI Insight card

Rendered only when `healthTopOpportunityFactor` is non-null (which, per the backend threshold
above, also means the gain is real — never a "+1 point" card). Shows the factor name, the
`+N points` potential gain, and a "Create Goal" link to `/app/goals`. Framing text follows the
reference Sid provided ("Your X is the biggest opportunity to improve your score") — templated
from the factor name, not free text.

### Sparkline

Reuses the same inline `<svg>` polyline technique already used for the hero's decorative
illustration and `CashFlowChart`'s crosshair plugin (small, dependency-free, consistent with this
file's existing patterns) rather than pulling in a new chart type for a 6-point line. Renders
`healthSparkline` points; a missing month is a gap in the line, not an interpolated value.

## Testing

- Backend: unit test for the insight-formula helper (weights, 80-threshold, "no candidate" case);
  integration test for the snapshot upsert (current-month row updates in place, prior-month rows
  untouched, unique constraint respected); DTO gating test (`healthScoreAvailable = false` ⇒ all
  four new fields null/empty).
- Frontend: `Dashboard.test.tsx` gets cases for the gauge rendering the right zone color at
  boundary scores (30/31, 60/61), the delta indicator hidden when `null`, the Insight card hidden
  when no opportunity exists, and the sparkline handling a gap month. The gauge/sparkline SVGs
  themselves are plain inline SVG (not Chart.js), so none of the existing `react-chartjs-2` mocking
  boundary applies here — they're fully testable via RTL like the rest of this file's SVG-based
  visuals (e.g. `dashboard-hero-illustration`).
