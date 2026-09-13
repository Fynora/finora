# Account Aggregator sync — Plan 2 scope (transaction sync)

Status: scoped, not yet an implementation plan. Boundary decisions below are settled; this is not
a task-by-task TDD plan (that's a separate future step, same as Plan 1's).

Builds on the merged Plan 1 (link lifecycle, PR #1396/#1400): a link can already reach `ACTIVE`
with an `Account` attached, but zero transactions flow in today. Plan 2 closes that gap.

## Goal

For an `ACTIVE` `AccountAggregatorLink`, actually pull bank transaction data from Setu and land it
in `Transaction` rows, on the recurring/webhook-driven cadence the design already specifies.

## In scope

- `SetuDataFetchGateway` — new interface seam (mirrors `SetuConsentGateway` from Plan 1): the real
  Setu fetch + ECDH decrypt call, behind an interface so the rest of the pipeline can be built and
  tested without real Setu sandbox access.
- `SetuDataFetchGatewayImpl` — placeholder bean, same shape as Plan 1's `SetuConsentGatewayImpl`:
  `isConfigured()` delegates to `SetuProperties`, real fetch method throws
  `UnsupportedOperationException` until real Setu credentials exist. Keeps the Spring context
  bootable without a real integration.
- `AccountAggregatorTransactionMapper` — maps decrypted `DEPOSIT` FI-data JSON into `Transaction`
  rows tagged `Source.ACCOUNT_AGGREGATOR`, `SourceTrust` 70. Built and unit-tested against
  hand-written fixture JSON shaped per the public AA FI-data schema, not real sandbox responses.
- Transaction identity (insert path only): `externalTxnId` when present, `transactionFingerprint`
  fallback, exactly as speced. On ingest: look up by `externalTxnId`, then by fingerprint, else
  insert new. **No update-in-place, no missing-row handling** — see Out of scope.
- `data.ready` webhook handling in the existing `AccountAggregatorWebhookDispatcher` (adds the case
  currently unhandled by Plan 1) → triggers the fetch for that link.
- Historical backfill on first reaching `ACTIVE`: last 3 months, per the design's stated scope.
- AA-vs-manually-imported-history reconciliation, both passes the design specs for this pair:
  - Reuse of the existing exact-match duplicate pass (composite key already exists).
  - New fuzzy near-duplicate pass (same shape as the existing Gmail cross-source pass, simpler —
    no merchant-token reduction). Threshold tuning is explicitly deferred (see Open items) but the
    pass itself, wired in with a starting threshold, is in scope.
- `AccountAggregatorLink.lastSyncedAt` / `lastSyncStatus` updated on every fetch attempt
  (success and failure) — the fields already exist from Plan 1, unused until now.
- Minimal sync instrumentation: fetch attempted/succeeded/failed and transactions-ingested-per-tick
  counters, reusing the existing `WorkerObservability`/metrics shape. Full incident *alerting* on
  staleness is Plan 4's outage-hatch threshold, not built here — this is just enough visibility to
  see Plan 2 working in production.
- Entitlement re-check before a scheduled fetch actually runs (mirrors Plan 1's webhook/sweep
  re-check) — a downgrade between `ACTIVE` and the next tick must not pull data for a lapsed user.

## Out of scope (explicitly deferred, not forgotten)

- **Bank-side mutation handling** (Plan 6): the three-way `{new, changed, missing}` diff, sliding
  window re-fetch, and correction/removal semantics. Plan 2 is upsert-by-insert-only — a changed or
  vanished upstream row is simply not detected. This is a known, accepted gap for this plan, not a
  bug to fix here.
- **AA-vs-Gmail canonicalization rule** (Plan 3): the dedicated `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)`
  auto-exclude rule. Until Plan 3 ships, an AA-linked account with active Gmail Sync will see the
  existing conservative Gmail behavior (FUZZY edge only, never auto-excluded) — meaning inflated
  totals are possible in that combination until Plan 3 lands. Flagging this explicitly so it isn't
  mistaken for a Plan 2 regression when it's noticed.
- **Confirm-time Gmail warning UX** (also Plan 3) — same reason.
- **Outage escape hatch** (Plan 4): the 3×-cadence manual-upload-unblock fallback. If Setu/AA has an
  outage during Plan 2's rollout, `ACTIVE` links just go stale with no automatic fallback yet;
  `lastSyncStatus`/`lastSyncedAt` will show it, but nothing acts on it.
- **Cost controls** (Plan 5): per-user link caps, relink throttling, rate limiting. Plan 2 does not
  add any new cost bound beyond what Plan 1 already has (entitlement gate + link idempotency).
- **Consent-management UX** (Plan 5): linked-accounts list, revoke/relink controls. Plan 2 is
  backend-only; there is no user-facing surface for what it does beyond what Plan 1 already built.
- **`CREDIT_CARD` FI type** — deferred until Setu's card-issuer coverage, `txnId` reliability, and
  posted-vs-transaction-date behavior are verified against a real sandbox. Plan 2 builds `DEPOSIT`
  only; the mapper/dispatcher should not need a structural rewrite to add `CREDIT_CARD` later, but
  no card-specific logic is written now.
- **Webhook secret storage/rotation** — unchanged from Plan 1 (plain `SetuProperties` config value,
  not yet routed through `EncryptionService`). Still an open item from the spec's "Missing
  requirements," not something Plan 2 resolves; noted so it isn't assumed fixed.

## Testing

- Unit: transaction mapper + fingerprint computation against fixture JSON (both the insert-new and
  already-seen-by-externalTxnId/fingerprint paths); the new AA-vs-manual fuzzy pass; webhook
  `data.ready` dispatch; entitlement re-check before fetch.
- Integration: full `ACTIVE` link → `data.ready` webhook → fetch (stubbed gateway) → `Transaction`
  round trip, including the 3-month backfill and its overlap-reconciliation pass against
  pre-existing manually-imported rows for the same account.
- No real Setu sandbox testing is possible in this environment — same constraint as Plan 1. Real
  fixture accuracy is unverified until real sandbox access exists; flagged, not assumed.

## Open items still unresolved (carried from the design spec, not blocking Plan 2 start)

- Exact similarity/confidence threshold for the new AA-vs-manual fuzzy pass — a starting value ships
  with Plan 2, tuning against real data happens later, same as the Gmail matcher's own history.
- Whether Setu's fetch API actually supports re-requesting an arbitrary overlapping past range —
  matters for Plan 6, not Plan 2 (Plan 2's backfill is a single initial fetch, not a sliding window).
- Webhook secret rotation (noted above).
