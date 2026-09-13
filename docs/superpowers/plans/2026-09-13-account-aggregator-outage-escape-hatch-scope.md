# Account Aggregator sync — Plan 4 scope (outage escape hatch)

Status: scoped, not yet an implementation plan. Boundary decisions below are settled; a task-by-task
TDD plan is a separate future step, same as Plans 1-3's own process.

Builds on the merged Plan 1 (link lifecycle, #1396/#1400), Plan 2 (transaction sync, #1406), and
Plan 3 (AA-vs-Gmail canonicalization, #1415). This is the design spec's own "Outage escape hatch"
section (`docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md`), named explicitly as
its own numbered plan by both the design doc and Plan 2's scope doc ("staleness is Plan 4's
outage-hatch threshold, not built here").

## Problem

`ImportService.AccountAggregatorGuard` (added in Plan 1) refuses any manual import into an account
with an `ACTIVE` `AccountAggregatorLink`, unconditionally — see
`backend/src/main/java/com/finora/imports/ImportService.java:104-124`. It has no concept of *how
stale* that link's data is. Today, sync is entirely webhook-driven (`data.ready` →
`AccountAggregatorWebhookDispatcher.dispatch` → `SetuDataFetchService.sync`); there is no scheduled
poll and no reconciliation sweep for a link that stops receiving webhooks. Confirmed by reading the
actual dispatcher and the only two scheduled services that exist in `integrations/setu/` today:

- `AccountAggregatorLinkSweepService` — reaps abandoned `CONSENT_PENDING`/
  `PENDING_ACCOUNT_CONFIRMATION` rows past a TTL. Does not touch `ACTIVE` links at all.
- Nothing else scheduled exists in this package. `git grep -n "@Scheduled"` under
  `integrations/setu/` returns exactly the one method above.

So if Setu, a specific FIP, or the user's AA app has an outage — a real, documented failure mode for
AA ecosystem participants, not hypothetical — an `ACTIVE` link's `lastSyncedAt` simply stops
advancing, with no automatic detection and no fallback. The user is locked out of both the automatic
feed (down) and manual upload (blocked by the guard), for however long the outage lasts. This plan
closes that gap.

## In scope

### 1. Staleness detection

- A new scheduled service, `AccountAggregatorOutageSweepService` (mirrors
  `SubscriptionReconciliationSweepService`'s shape exactly: `@Scheduled(fixedDelayString =
  "${...sweep.interval-ms:...}")`, a `sweepEnabled` flag `application-test.yml` turns off, tests call
  `sweep()` directly rather than waiting on the schedule).
- New repository query on `AccountAggregatorLinkRepository`: `ACTIVE` links whose `lastSyncedAt` is
  either `null` (never synced even once — e.g. the initial backfill silently never landed) or older
  than a cutoff. Needed because no query today distinguishes "healthy `ACTIVE`" from "stale
  `ACTIVE`" — `findByAccountIdAndStatus` only looks up by account, not by staleness.
- **The "3x expected cadence" threshold names something this codebase does not currently track as a
  number.** The design's own cadence discussion says cadence is "fixed at consent-grant time" inside
  the AA consent artifact itself, not a Fynora-side config value — and nothing in
  `AccountAggregatorLink` persists a per-link cadence today. Setu's consent-creation API is not
  confirmed (not guessed) to echo the granted cadence back in a way this codebase currently reads.
  Given that, this plan treats "expected cadence" as a single global config constant (default: 24h,
  the design's own stated cadence — "recurring (daily)"), not a per-link value, until real Setu
  responses are checked for whether a per-link cadence is actually available. **This needs
  confirming against Setu's real API docs/sandbox before implementation locks in the constant** —
  flagged the same way Plan 2 flagged unresolved sandbox questions, not assumed resolved here.
  Threshold: `3 * app.integrations.setu.expected-cadence-hours` (one new config key), reusing the
  "one number, not three independently invented ones" principle the design doc itself states for
  this exact threshold (shared by the escape hatch and the alerting requirement below).
- **Staleness definition, documented per review feedback but deliberately NOT changing the v1
  design.** `lastSyncedAt` records that `SetuDataFetchService.sync` completed without throwing — it
  advances identically whether that call fetched five new transactions or zero. Raised in review as
  "sync success ≠ new transactions arrived," which is true, but is not the question this plan
  answers: Plan 4 detects whether Fynora can still reach Setu and the AA network, not whether the
  user transacted recently. A sync that succeeds and finds zero new transactions is healthy — the
  user simply had no activity, and the hatch correctly stays closed. The one thing this can't
  distinguish is Setu returning empty-but-200 responses due to its *own* upstream issue (indistinguishable
  from genuine inactivity without a materially different signal — e.g. anomaly detection against the
  account's own historical transaction volume, which doesn't exist in this codebase today). Accepted
  as a known v1 gap, stated explicitly so a future reader doesn't assume this hatch guarantees fresh
  data — only that it guarantees sync attempts kept succeeding.

### 2. The escape hatch itself

- `AccountAggregatorGuard.checkNotActivelySynced` gets one additional condition: an `ACTIVE` link
  whose `lastSyncedAt` is null or past the staleness cutoff no longer trips the guard. Manual import
  proceeds as if the account were on `Account.PrimarySource.MANUAL` — **`primarySource` itself is
  NOT changed**. This is a deliberate difference from the `REVOKED`/`EXPIRED`/`PAUSED` paths (which
  do flip `primarySource` back to `MANUAL` in `AccountAggregatorWebhookDispatcher`): those are
  durable state changes Fynora was told about; an outage is transient and self-correcting the moment
  Setu recovers, so the account should silently return to AA-only the moment a fresh sync succeeds,
  with no separate "re-attach" step required. Concretely: the staleness check is evaluated live in
  the guard (same request-time computation the guard already does), not cached as a status flag on
  the link — the instant `lastSyncedAt` advances again, the hatch closes on its own.

- **Revised per review feedback: `lastSyncedAt == null` does NOT open the hatch immediately.**
  Traced the actual activation path to check how real this risk is, rather than assuming either
  way. Every path to `ACTIVE` status goes through
  `AccountAggregatorIdentityResolutionService.attach()`, which sets `status = ACTIVE` and, in the
  same method, immediately calls `fetchService.sync(link, today.minusMonths(3), today)` — the
  initial 3-month backfill — *synchronously*, before `attach()` returns. `SetuDataFetchService.sync`
  sets `lastSyncedAt` on both its success path and its caught-`RuntimeException` failure path,
  meaning a backfill that genuinely fails (the reviewer's "Case A") still leaves `lastSyncedAt`
  **non-null** with `lastSyncStatus = FAILED` — it does not stay null. `lastSyncedAt` only stays
  null past `attach()` returning in two narrow, already-degenerate cases: the entitlement check or
  `gateway.isConfigured()` check inside `sync()` short-circuits before either save (a race with a
  downgrade in the exact window between consent approval and backfill, or Setu credentials genuinely
  absent) — and in both, AA is not going to produce data soon regardless, so opening the hatch is
  arguably correct there, not a bypass.

  What *is* real (the reviewer's "Case B" narrowed to its actual shape): `attach()` saves
  `status = ACTIVE` and *then* runs the backfill — these are two separate saves, not one atomic
  transaction, so there is a genuine window, lasting as long as the backfill's own Setu HTTP call
  (observed nowhere in this codebase yet, but presumably seconds to low minutes for a 3-month pull),
  during which the DB already shows `ACTIVE` + `lastSyncedAt == null` simultaneously. A request
  racing exactly inside that window would see the hatch open before the backfill had a chance to
  finish.

  **Simplified per second round of review feedback: reuse the SAME 72h staleness threshold for this
  case too, rather than a separate 30-minute constant** — "same principle as the normal stale check,"
  one number doing both jobs rather than two independently chosen ones (consistent with the design
  doc's own reasoning for sharing one cadence constant across the hatch and the alerting threshold).
  Still no new persisted column: the reference point for "how long has this link been waiting to
  sync" reuses `AccountAggregatorLink.updatedAt`, which `setStatus` already touches at the exact
  moment `ACTIVE` is set (before the backfill call begins). Final rule, symmetric across both
  branches:
  - `lastSyncedAt != null` → stale when `now - lastSyncedAt > 72h`.
  - `lastSyncedAt == null` → stale when `now - updatedAt > 72h`.

  A newly-linked account (the in-progress-backfill race above) is nowhere near 72h old and correctly
  stays non-stale; a link that genuinely never got a working sync (entitlement lapsed right at
  activation, Setu credentials missing) correctly opens the hatch once it's been sitting that way for
  3 days, exactly like any other stale link. Flagged for the same reason as before: if a future plan
  makes the initial backfill asynchronous, `updatedAt`'s meaning here should be re-examined.
- **Single source of truth for the staleness predicate, per review feedback's finding #2.** The
  backend guard and the frontend picker (Task 5 below) cannot each carry their own copy of "is this
  link stale" — they would drift, and "backend allows it, frontend still refuses it" becomes
  inevitable exactly as flagged. New injectable bean, `AccountAggregatorLinkStalenessService`
  (alongside the other AA services in `integrations/setu/`, constructed from the same
  `expected-cadence-hours` config value as the sweep), exposing one method:
  `boolean isStale(AccountAggregatorLink link)` — pure, no repository access of its own, just the
  symmetric `lastSyncedAt`/`updatedAt` rule above evaluated against `Instant.now()`. Both consumers
  do their own lookup (`AccountAggregatorLinkRepository.findByAccountIdAndStatus`, already the
  existing call in both places) and then ask this one bean whether the result is stale:
  - `AccountAggregatorGuard.checkNotActivelySynced`: only throws the 409 when an `ACTIVE` link
    exists **and** `!isStale(link)`.
  - `AccountService`'s `AccountDto` assembly (`AccountService.java:83`, `:193`, `:231` — every call
    site that builds a `AccountDto` from an `Account`): looks up the same `ACTIVE` link and sets the
    new `aaSyncStale` field (Task 5) from the same `isStale(link)` call. `AccountDto.from(...)` stays
    a plain static factory (as Plan 3 left it) — `AccountService` computes the boolean and threads it
    through, the same pattern Plan 3's Task 1 already established for `primarySource`.
  One predicate, two callers, no second copy of the threshold math to drift.
- No new `Transaction`-level "outage import" tag. The design doc's phrase "rows tagged as a
  temporary-outage import" reads as explanatory framing, not a literal new persisted field —
  confirmed by checking `Transaction.Source` (still just `MANUAL`/`CSV_IMPORT`/`GMAIL_IMPORT`/
  `ACCOUNT_AGGREGATOR`, no fifth value, and no other column recording "why manual was allowed"). The
  overlap-reconciliation requirement below doesn't need a tag to work: `ReconciliationService`'s
  passes already re-evaluate the *entire* current transaction set on every run (not an incremental
  diff against "since last run"), so a manually-imported row from an outage window is automatically
  caught by the existing AA-vs-manual exact-match and fuzzy passes (Plan 2) the next time
  reconciliation runs for that account — which already happens on every transaction create/import
  confirm, and again explicitly inside `SetuDataFetchService.sync` after AA data eventually lands.
  Nothing new to build here beyond what Plans 1-3 already ship; stated explicitly so it isn't
  mistaken for an open gap.
- Audit logging: record `ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED` via the existing
  `AuditService` when the guard's staleness bypass actually fires (not merely when a link goes
  stale) — mirrors the design's own "audit logging... not optional for a regulated data-sharing
  feature" requirement, and gives product/support a queryable trail of when the hatch was actually
  exercised, not just when it was theoretically available.

### 3. Monitoring and alerting

- **A real, checked gap found while scoping this plan, not previously flagged in Plans 1-3's own
  addenda: Plan 2's scope doc claimed "minimal sync instrumentation: fetch attempted/succeeded/
  failed and transactions-ingested-per-tick counters" as in scope, but no such instrumentation was
  actually built.** Confirmed by grep: zero references to `WorkerObservability`, `MeterRegistry`, or
  any `Counter`/`Gauge` call anywhere under `backend/src/main/java/com/finora/integrations/setu/`.
  `SetuDataFetchService.sync` only logs and updates `lastSyncStatus`; there is no metric surface at
  all for AA sync today, unlike `ReconciliationMetrics`'s dedicated catalog for the reconciliation
  engine. This plan needs to build that missing baseline before it can add a staleness *metric* on
  top of it — see Open items for the scope decision this forces.
- A gauge (or equivalent periodic measurement) for "`ACTIVE` links currently past the staleness
  threshold," registered the same way `ReconciliationMetrics` registers its counters, so a future
  dashboard/alert can query it under one name.
- **"Incident alerting" is aspirational language this codebase cannot fully back today.** Per this
  session's own memory (`pre-launch-safety-check-findings`): there is no production alerting
  pipeline in Finora at all yet — no PagerDuty/Opsgenie/Slack-webhook integration, nothing that
  turns a metric crossing a threshold into a page. What exists is Sentry (`MonitoringConfig`, error
  capture only) and structured logs. This plan's realistic scope is: a `WARN`-level structured log
  line (`AA link {} stale: last synced {} ago, threshold {}h`) and the metric above, both of which a
  *future* alerting system can consume — not a new alerting channel invented here. Flagged
  explicitly rather than silently narrowed, since the design doc's wording ("alert when...") could
  otherwise be read as promising more than this plan can deliver against current infra.

### 4. Repository/query additions

- `AccountAggregatorLinkRepository.findByStatusAndLastSyncedAtBeforeOrLastSyncedAtIsNull(status,
  cutoff)` (or equivalent) — the sweep's read path.
- Reuses `AccountAggregatorLinkRepository.findByAccountIdAndStatus` (already exists) for the guard's
  live per-account check; the guard adds an in-line staleness comparison on the result, not a new
  query.

### 5. Frontend interaction with Plan 3's account picker (moved in scope per review feedback)

**Real bug this plan would otherwise ship with, not just a UX nicety: without this, the hatch is
unreachable.** Plan 3's `Import.tsx` disables the `<option>` for any account with
`primarySource === 'ACCOUNT_AGGREGATOR'` unconditionally (line ~1964), and
`accountMatch.ts`'s `matchExistingAccount` filters such accounts out of auto-preselection entirely
(`frontend/src/lib/accountMatch.ts:48`). Neither knows anything about staleness. If this plan only
changes backend behavior, a stale-linked account becomes importable via a direct API call but stays
permanently disabled in the one UI surface that offers it — the backend allows the import, the
frontend never lets a user reach it.

- New computed field on `AccountDto`/`Account` (mirrors Plan 3 Task 1's own `primarySource`
  exposure): `aaSyncStale: boolean`, sourced from the single `AccountAggregatorLinkStalenessService`
  bean defined in Task 2 above — not a second copy of the threshold math. Meaningless/`false` for a
  `MANUAL` account.
- `Import.tsx`'s picker: `disabled={a.primarySource === 'ACCOUNT_AGGREGATOR' && !a.aaSyncStale}` is
  the one required change — an AA-linked-and-stale account must become selectable, full stop. The
  exact label text is not architecturally significant and is left to product/copy review at
  implementation time; a reasonable placeholder (`— Bank Sync delayed (manual import available)`
  when stale, `— Bank Sync active` when healthy) is enough to unblock building it.
- `accountMatch.ts`'s `matchExistingAccount`: the `eligibleAccounts` filter changes from unconditionally
  excluding every `ACCOUNT_AGGREGATOR` account to `a.primarySource !== 'ACCOUNT_AGGREGATOR' ||
  a.aaSyncStale` — so auto-preselection becomes consistent with what the backend will actually allow,
  not stricter than it.
- Explicitly **not** a new UI surface or screen — no banner, no dedicated staleness indicator
  elsewhere in the app. Scoped to making the *existing* Plan 3 picker's disabled state match backend
  reality, nothing broader. The consent-management screen (linked-accounts list, last-synced
  timestamp) stays Plan 5's, per the design spec.

## Out of scope (explicitly deferred, not forgotten)

- **Cost controls** (Plan 5): link caps, relink throttling, rate limiting. Unrelated to staleness.
- **Consent-management UX** (Plan 5): a dedicated linked-accounts list, per-account last-synced
  display, revoke/relink controls. Task 5 above only touches the existing import account picker
  (Plan 3's surface) — it does not add a new screen.
- **Bank-side mutation handling** (Plan 6): the three-way `{new, changed, missing}` diff, sliding-
  window re-fetch. Unrelated — this plan does not change what a sync *fetches*, only what happens
  when syncs stop arriving.
- **Per-link cadence** (if Setu's real API turns out to expose one): this plan ships a single global
  config constant; making cadence per-link (per FI type, or per-link if Setu returns it) is a
  follow-up once sandbox access confirms what's actually available. **Confirmed as the right v1
  approach per review** — no schema change until Setu proves cadence exists.
- **A retroactive fix for Plan 2's missing instrumentation claim beyond what this plan needs**: this
  plan builds only the minimum metric surface its own staleness gauge requires — **confirmed per
  review**: keep this plan narrow, one gauge, not the full sync-lifecycle metrics catalog Plan 2's
  scope doc originally (and, per this plan's own audit, never actually) built. That fuller catalog is
  a separate follow-up task, not part of Plan 4.
- **Detecting "sync keeps succeeding but the underlying data looks wrong"** (Setu returning
  empty-but-200 responses due to its own upstream issue) — see the staleness-definition note above.
  Accepted gap for v1; would need a materially different signal (e.g. anomaly detection against an
  account's own historical transaction volume) that does not exist in this codebase today.

## Decisions confirmed per review (no longer open)

- **Per-link cadence**: single global config constant (24h expected cadence, 72h staleness
  threshold) until Setu sandbox evidence justifies a per-link value. Do not build the schema change
  speculatively.
- **`lastSyncedAt == null` grace period**: reuses the same 72h staleness threshold as the populated
  case (measured against `updatedAt` instead of `lastSyncedAt`), not a separately invented shorter
  constant. A newly-linked account is nowhere near 72h old and stays non-stale; a link that never got
  a working sync opens the hatch after the same 3 days any other stale link would.
- **Staleness detects connectivity to Setu/AA, not user transaction activity, by design.** A sync
  that succeeds and returns zero new transactions is healthy. This does not change based on the
  "sync success ≠ fresh data" observation — documented as a known distinction, not a redesign
  trigger.
- **Single source of truth for import eligibility**: the new `AccountAggregatorLinkStalenessService`
  bean (Task 2) is the only place the staleness predicate is evaluated; both
  `AccountAggregatorGuard` (backend enforcement) and `AccountService`'s `AccountDto` assembly
  (frontend-facing `aaSyncStale`) call it. The backend-allows/frontend-still-disabled mismatch this
  review flagged is closed by construction, not by keeping two copies in sync by convention.
- **Frontend signal**: no dedicated new UI surface — update the existing Plan 3 picker's disabled
  state (a required change) and label (a placeholder, not architecturally significant — final copy
  is a product-review detail) only. No banner, no separate staleness screen.
- **Instrumentation scope**: build only the one staleness gauge this plan needs. The fact that Plan
  2's broader metrics claim was never actually built is flagged as a real, known gap — but fixing it
  in full is an explicit non-goal of this plan, not something to opportunistically absorb.
- **Sweep interval and thresholds**: sweep every 1h (matches
  `SubscriptionReconciliationSweepService`'s own default), 24h expected cadence, 72h staleness
  threshold (3x cadence) — one threshold value, reused everywhere a threshold is needed. Not tuned
  against real data — none exists yet — revisit once live Setu traffic exists.

## Still open (needs resolving before an implementation plan is written)

1. **Confirm against Setu's real API/sandbox whether a per-link cadence is actually available**
   before implementation locks in the single-global-constant approach. If Setu does expose it, the
   threshold computation changes from a global constant to a per-link field on
   `AccountAggregatorLink` — a schema change this scope doc does not currently plan for.
