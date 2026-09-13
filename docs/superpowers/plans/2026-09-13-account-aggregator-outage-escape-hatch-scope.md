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

## Out of scope (explicitly deferred, not forgotten)

- **Cost controls** (Plan 5): link caps, relink throttling, rate limiting. Unrelated to staleness.
- **Consent-management UX** (Plan 5): linked-accounts list, per-account last-synced display,
  revoke/relink controls. This plan's staleness detection is backend-only; there is no new
  user-facing screen. Whether the *existing* import flow should show any UI signal when the hatch is
  active (e.g. "your bank sync looks delayed — manual import is available") is a real, undecided
  product question — see Open items, not assumed either way.
- **Bank-side mutation handling** (Plan 6): the three-way `{new, changed, missing}` diff, sliding-
  window re-fetch. Unrelated — this plan does not change what a sync *fetches*, only what happens
  when syncs stop arriving.
- **Per-link cadence** (if Setu's real API turns out to expose one): this plan ships a single global
  config constant; making cadence per-link (per FI type, or per-link if Setu returns it) is a
  follow-up once sandbox access confirms what's actually available.
- **A retroactive fix for Plan 2's missing instrumentation claim beyond what this plan needs**: this
  plan builds only the minimum metric surface its own staleness gauge requires. A full sync-lifecycle
  metrics catalog (matching everything Plan 2's scope doc originally claimed — fetch attempted/
  succeeded/failed counters, ingested-per-tick) is a reasonable follow-up but not re-litigated here
  in full; noted as a known, real gap either way (see Open items).

## Open items (need a decision before an implementation plan is written)

1. **Confirm against Setu's real API/sandbox whether a per-link cadence is actually available**
   before locking in the single-global-constant approach above. If Setu does expose it, the
   threshold computation changes from a global constant to a per-link field on
   `AccountAggregatorLink`, which is a schema change (new migration) this scope doc does not
   currently plan for.
2. **Should the outage-hatch firing surface anything in the frontend**, beyond "manual upload simply
   isn't blocked anymore"? The design doc doesn't specify a UI treatment for this case (only for the
   Gmail confirm-time warning, which Plan 3 already implemented and then dropped per your own review
   — see Plan 3's scope doc). Leaning toward "no new UI in this plan" since Plan 5 owns the
   consent-management surface where staleness would naturally live, but flagging rather than
   deciding unilaterally.
3. **Scope of the "missing instrumentation" backfill**: build only the one staleness gauge this plan
   needs, or take this plan as the opportunity to also build the fetch-attempted/succeeded/failed
   counters Plan 2's scope doc already promised? Leaning toward the narrower option (staleness gauge
   only) to keep this plan's diff reviewable, with the fuller counter set flagged as a separate
   follow-up task — but this is your call, not a default I should make silently.
4. **Sweep interval and default cadence/threshold values** — proposing sweep every 1h (matches
   `SubscriptionReconciliationSweepService`'s own default) and a 24h expected cadence (3x = 72h
   staleness threshold), both as named config keys, not hardcoded. Open to your input; not tuned
   against any real data since none exists yet (no live Setu traffic).
