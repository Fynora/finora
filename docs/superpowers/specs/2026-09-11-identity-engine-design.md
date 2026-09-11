# Fynora Identity Engine — Design Spec

**Status:** Draft, for review
**Date:** 2026-09-11
**Origin:** brainstorming session, not yet implementation-planned

## 1. Problem

India's exclusivity/status psychology is real and well-documented (CRED's credit-score-gated
membership, luxury-market identity signaling) and is a genuine growth lever Fynora isn't using.
But the naive version of this — a paid "Premium" tier, a purchasable "Black Circle," a
percentile leaderboard — fails for reasons specific to where Fynora is today:

- **Bought status is weak status.** CRED's actual insight is that its 750+ credit-score gate
  creates status *because it can't be bought* — Premium-by-payment-alone doesn't do that.
- **Wealth-based ranking is exclusionary in a way users notice.** A score or leaderboard based on
  net worth or absolute savings rewards inherited money and high salaries, not financial
  behavior. A 24-year-old on ₹40k/month can't compete, and that reads as unfair rather than
  aspirational.
- **Fynora doesn't yet have the trust or scale two of these ideas need.** Percentile claims
  ("top 5%," "top 3%") require a large-enough active user base to be credible, and a Financial
  Health Score requires transaction data accurate enough to publish as a number back to the
  user. Neither precondition holds yet — the import pipeline has an active, ongoing data-quality
  workstream (see `corpus-quality-track`, `held-statement-review-track` project history), and
  Fynora is not at CRED- or Spotify-scale active users.
- **Shame-based framing backfires on money specifically.** A "Financial Age: 61" for a
  28-year-old reads as an insult, not a nudge — unlike a Spotify Wrapped or a gym streak, a
  wrong or harsh number about someone's finances lands on their actual anxiety, not their taste.

## 2. Core principle

**Sell identity, not features.** The question every layer below has to answer is "what does
using this signal about the person," not "what report does this unlock."

Two re-framings drive the whole design:

1. **Discipline over wealth.** Status should be earned through financial *behavior*
   (consistency, goal completion, resilience) — never through absolute net worth or income.
   This is also the larger addressable market: aspirational middle-class users vastly outnumber
   the already-wealthy in India.
2. **Ownership of history over artificial exclusivity.** The most durable moat available to
   Fynora isn't a gate that keeps people out — it's a growing personal record that a competitor
   cannot hand a user on day one, the same mechanic that makes GitHub's contribution graph,
   Strava's activity history, and Spotify Wrapped sticky. The precise asset that's non-portable
   is **the actions a user took inside Fynora** — the goals they named, the budgets they set,
   the milestones they hit — not the underlying transaction ledger, which originates from the
   user's bank and is exactly as reconstructable by any competitor who imports the same
   statements. The pitch is "years of decisions you made here," not "years of transactions."

## 3. What Fynora already has (evidence, not assumption)

Verified directly against the current codebase before designing on top of it:

| Asset | Where | What it gives us |
|---|---|---|
| `Goal` + `GoalContribution` | `backend/src/main/java/com/finora/goals/` | Per-goal target/current amount, `completedAt` (Instant, set once, never overwritten — `V94__goal_completed_at.sql`), and a full contribution history with day-precision, per-user-timezone-correct `contributedAt` dates. Real streak/milestone material. |
| `Budget` | `backend/src/main/java/com/finora/entity/Budget.java`, `budgets/BudgetService.java` | Current month's limit vs. live-computed spend only. **No history** — no snapshot of past months' pass/fail, no versioning of past limits. Cannot support an adherence streak today without new infrastructure. |
| `AuditLog` (via `AuditService`) | `backend/src/main/java/com/finora/service/AuditService.java` | Per-user, chronologically ordered event stream (`findByUserIdOrderByCreatedAtDesc`) already covering `GOAL_CREATED`, `GOAL_CONTRIBUTION_ADDED`, `ACCOUNT_CREATED`, `BUDGET_UPSERTED`, `TRANSACTION_CREATED`, and ~20 other action types. This is most of the raw substrate a timeline needs — but see the redaction caveat below. |
| `NetWorthSnapshot` | `backend/src/main/java/com/finora/entity/NetWorthSnapshot.java`, `NetWorthSnapshotSweepService` | Daily, per-user net worth history, already a proven "snapshot for status/story" pattern in this codebase. **Important limitation:** the sweep only ever writes *today's* snapshot — there is no backfill, so a user who imports a year of old statements tomorrow still only has net-worth history starting from whenever the sweep first ran for their account. Year-over-year net-worth comparisons are gated by tenure on Fynora, not by imported data depth. |
| `EXTENDED_HISTORY` entitlement | `backend/src/main/java/com/finora/imports/ImportService.java` (`FREE_STATEMENT_PERIOD_MAX_DAYS = 31`) | Caps a *single statement's* covered date span on Free tier, not cumulative import depth. A Free user importing 12 separate monthly statements can still build a full year of transaction history — this entitlement does not block timeline/streak features built on transaction data. |

**Load-bearing constraint — audit log redaction (`V89__audit_log_redaction.sql`):** by explicit
decision (Sid, 2026-08-15, BH-044), the audit *event* (actor, action, entity, timestamp) is kept
forever, but the metadata JSONB payload — the actual amounts, descriptions, budget limits — is
redacted to a marker once a row passes the configured retention window. **`AuditLog` cannot be
the timeline's source of real figures past that window.** A milestone that needs its number
("Saved first ₹10,000," "Reduced dining spend 18%") must read that number from the owning
entity (`GoalContribution.amount`, `NetWorthSnapshot`, `Transaction`) at the time it's computed,
or persist it into a dedicated timeline record at event time — never reconstruct it later from
audit metadata.

**Also confirmed:** `AccountPurgeSweepService` hard-deletes `Goal`, `Budget`, and
`NetWorthSnapshot` on account deletion; `AuditLog` event skeletons survive deletion (redacted)
but nothing else does. The entire identity/timeline layer lives only in an active account — this
is expected and requires no special handling, just stating it so it isn't rediscovered later as
a surprise.

## 4. Architecture: five layers, strict build order

Each layer is gated on the layer before it, not on a calendar date — the gate is "does the
precondition hold," not "how much time has passed."

### Layer 1 — Financial Journey Timeline (the moat, build first)

A per-user, chronological feed of **behavioral milestones**, not raw audit noise and not a
literal replay of `AuditLog`. Examples: "Started first goal," "Saved first ₹10,000," "Emergency
fund 25%," "Completed first goal," "Reduced dining spend 18% this month."

Design requirements:
- **New persisted record per milestone, written at the moment it fires** — not derived on read
  from `AuditLog` (per the redaction constraint above) and not derived on read from raw
  transaction scans every time the timeline is viewed (too expensive, and re-deriving old
  months' numbers from current-day category/refund logic risks the numbers silently drifting
  from what actually happened).
- **A curation layer, not a raw feed.** `AuditLog` already has ~20 action types firing on nearly
  every mutation (including one row per `TRANSACTION_CREATED` — thousands of rows during a bulk
  import). The timeline needs an explicit allowlist of milestone-worthy triggers, organized by a
  taxonomy (below) — never "log everything and filter at render time."
- **Timeline is the source of truth; badges are derived, not separately tracked.** A badge like
  "Goal Achiever" is a display label attached to a timeline event, not an independent state
  machine that can drift out of sync with what the timeline says happened.

#### 4.1 Milestone taxonomy

Every milestone belongs to exactly one of four buckets. This is what keeps the allowlist from
becoming an arbitrary, ever-growing list of "things someone thought were interesting":

| Type | Definition | Examples |
|---|---|---|
| Starting | First instance of an action type | First goal created, first budget created, first import |
| Consistency | Sustained behavior over a rolling window | "5 of last 6 months" contribution momentum (Layer 4) |
| Progress | Crossing a threshold on the way to a target | Goal 25% / 50% / 100%, emergency fund 25%/50%/100% |
| Transformation | A durable state change, not a point-in-time reading | Emergency fund complete, debt-free, first ₹1L cumulative saved |

#### 4.2 Permanent vs. dynamic milestones

Not every milestone ages the same way, and the timeline entity must record which kind each one
is so rendering and Wrapped/share-card selection can treat them differently:

- **Permanent** — a durable fact about the user's financial life that stays true and stays
  meaningful indefinitely ("Completed Emergency Fund," "First goal completed," anything in the
  Transformation bucket above). These anchor the timeline long-term.
- **Dynamic** — a point-in-time observation that was true when it fired but has no lasting
  significance ("Dining spend fell 18% this month"). Useful for Wrapped/monthly color, but the
  timeline should not let these accumulate forever as equally-weighted permanent entries — a
  timeline that's 90% stale monthly spend deltas from three years ago is noise, not a story.
  Dynamic entries should be eligible for lower-priority display (e.g., collapsed by default,
  surfaced mainly in the year they happened) rather than sitting at the same visual weight as
  permanent ones indefinitely.

#### 4.3 Importance

Each milestone record carries an importance level, not left implicit:

- **Minor** — timeline-only, everyday texture (a routine goal contribution).
- **Major** — timeline + eligible for in-app celebration (e.g., a completion toast).
- **Landmark** — the only tier eligible for profile highlights, Wealth Wrapped inclusion, and
  shareable cards.

Restricting Wrapped/share-card content to Landmark-only is what keeps the annual story concise —
without this filter, Wrapped risks becoming a dump of every Minor event from the year.

#### 4.4 Surfacing — "My Journey" cannot be a hidden page

If the timeline is the moat, it has to be encountered routinely, not filed away behind a menu
item nobody taps. From the first release of Layer 1, the Dashboard must surface at least the
most recent Landmark (or, absent one, Major) timeline entry — a strong architecture that goes
unseen produces no identity effect and no retention benefit. This is a Layer 1 launch
requirement, not a later polish pass.

Sourcing per milestone type:
  - Goal-related: `Goal.completedAt`, `GoalContribution` rows (existing, no new tables needed
    for the underlying facts — only the curated timeline-event table is new).
  - Net-worth-threshold milestones ("crossed ₹1L saved"): `NetWorthSnapshot` history, subject to
    the no-backfill caveat above — only trustworthy from whenever the user's snapshot history
    actually starts.
  - Spend-change milestones ("dining down 18%"): computed from `Transaction` data at
    month-close, written once, not recomputed later against categorization rules that may have
    since changed.

### Layer 2 — Wealth Wrapped (the marketing vehicle)

An annual (or on-demand) shareable card built **from Layer 1's data**, not a separate
computation: goals completed, contribution count, largest category improvement, best month,
consistency description. Explicitly the acquisition/virality mechanic (Spotify Wrapped
precedent) — cheap to build once Layer 1 exists, because it's a rendering/aggregation pass over
already-curated milestone data, not new data collection.

Any composite "score" shown here (e.g., a 0–100 "consistency" figure) needs its formula defined
and reviewed before shipping — it is new derived content, not a raw count, and carries the same
"don't publish a number you can't defend" risk as Layer 5's score, just at lower stakes since
it's descriptive flavor rather than a gate.

### Layer 3 — Milestones & badges derived from the timeline

Cosmetic labels attached to specific Layer 1 events. Founder/join-date badges live here too, but
demoted to purely cosmetic — tenure ("when you joined") doesn't fit the "reward behavior, not
attribute" principle the rest of this design follows, so it should not anchor product strategy.

### Layer 4 — Momentum systems

Resilient, not punitive: "contributed in 5 of the last 6 months," not "6-month streak, reset to
zero on one miss." A hard consecutive-streak model quietly re-introduces the same fairness
problem this whole design is trying to escape — freelancers, commission earners, and seasonal
earners in India don't have monthly-regular cash flow, and a rigid streak breaks for reasons
that have nothing to do with financial discipline. Implement as a rolling-window count, not a
counter that resets on a single miss.

**Precondition to unlock budget-based momentum specifically:** none of this layer's
budget-adherence variants ("stayed within budget 12 months") are buildable today — see Section 3
above. They require new monthly budget-snapshot infrastructure (limit + actual spend + pass/fail,
captured at each month's close) before they can start counting, following the same
snapshot-table pattern `NetWorthSnapshot` already established. Past months before that
infrastructure ships are not recoverable, because past budget limits aren't versioned.

### Layer 5 — Status & scoring (deferred, explicit preconditions)

Financial Health Score, "Black Circle" earned-membership tier, percentile rankings, cross-user
comparison. Deliberately last. Two preconditions must both hold before this layer is designed in
detail, not just before it ships:

1. **Data trust** — the transaction/categorization pipeline's accuracy work needs to have
   reached a bar where a number shown back to a user as *their* score is defensible, not a
   plausible-looking guess.
2. **Scale** — percentile and exclusivity claims ("top 3%," "5% acceptance") require a large
   enough active-user denominator to be credible; before that, they read as manufactured rather
   than aspirational.

Additionally, any feature that computes or surfaces **cross-user** comparisons (percentile
rank, "better than 87% of users") needs a compliance/consent-design check against India's DPDP
Act 2023 before implementation — this is new ground for Fynora (nothing today aggregates
behavior across users for a user-facing comparison) and should be a real check at design time,
not an afterthought.

## 5. Explicit anti-goals

- **No "Financial Age."** Evaluates the person, not the behavior — "Financial Age: 61" for a
  28-year-old reads as an insult regardless of intent. Reframe any age-like comparison as
  behavior-forward instead ("improved savings rate 4 months running").
- **No net-worth- or income-based ranking, ever**, including inside Layer 5. Every scoring
  mechanic in this design is behavior-based (consistency, completion, resilience), never
  wealth-based — this is the one rule that must survive all the way to Layer 5's eventual design,
  not just Layers 1–4.
- **No hard consecutive streaks that reset to zero on one miss** — see Layer 4.
- **No percentile/leaderboard features before Layer 5's two preconditions both hold.**

## 6. Build sequencing

1. Layer 1 (Timeline, **including the Dashboard "My Journey" surface from launch, per §4.4 —
   not a follow-on**) + Layer 2 (Wrapped) + Layer 3 (derived badges) + Layer 4's goal-based
   momentum — all buildable now, on data and infrastructure that already exists (`Goal`,
   `GoalContribution`, `NetWorthSnapshot`, plus new curated timeline-event storage).
2. Layer 4's budget-based momentum — after budget-snapshot infrastructure ships (new, modeled on
   `NetWorthSnapshot`'s existing pattern).
3. Layer 5 — after data-trust and scale preconditions are independently confirmed, with its own
   design pass at that time (including the DPDP check above).

## 7. Open questions for Sid

- Confirm the milestone allowlist for Layer 1 — §4.1 gives the taxonomy (Starting / Consistency
  / Progress / Transformation) each trigger must fall under, but the concrete per-bucket list is
  still examples, not final.
- Confirm the Minor/Major/Landmark importance assignment per milestone type (§4.3) — the spec
  fixes the rule (only Landmark reaches Wrapped/share cards/profile highlights) but not yet which
  specific milestones get which tier.
- Decide the tone/voice for milestone copy (uplifting vs. neutral) before any copy ships,
  especially for anything touching spend-reduction framing, which is the closest remaining thing
  to the "Financial Age" shame risk this design otherwise avoids.
- Decide whether Layer 1 data (timeline events) should be included in the existing account-data
  export flow (`DataExportService`) the same way `Goal`/`GoalContribution` already are.
- No engineering estimate is attempted in this document — that's the next step, via an
  implementation plan, once this spec is reviewed.

## 8. Non-goals of this document

This spec does not include UI mockups, copy drafts, an entitlement/pricing mapping (which tier
gets which layer), or an implementation plan. Those are follow-on work once this design is
approved.
