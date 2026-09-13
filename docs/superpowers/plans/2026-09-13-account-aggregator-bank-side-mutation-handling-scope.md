# Account Aggregator sync — Plan 6 scope (bank-side mutation handling)

Status: scoping, not yet an implementation plan.

Builds on Plan 2 (transaction sync, merged), which is explicitly insert-only: a transaction whose
upstream value changes, or that disappears from a later fetch, is not detected by anything shipped
so far. This is Plan 6, per Plan 2's own "Out of scope" line and the design spec's "Data
corrections and mutations" section — the last of the six plans this codebase's own AA roadmap
named.

## Goal

AA has no native amend/delete event for a previously-fetched transaction (a declined pre-auth that
should vanish, a pending amount that changes on posting). Close that gap with the design spec's own
strategy: **sliding-window re-fetch, not point-fetch**, plus a **three-way diff** — `{new, changed,
missing}` — over every re-fetched window, instead of the current pure upsert-by-insert.

## What the current code actually does (read directly, not assumed)

- `AccountAggregatorTransactionMapper.mapNew` is exactly what its name says: look up by
  `externalTxnId`, then by `transactionFingerprint`; if either matches, **silently skip**; otherwise
  insert. No update path exists at all today.
- `AccountAggregatorWebhookDispatcher`'s `data.ready` case computes `from = lastSyncedAt + 1 day`,
  `to = today` — a **point fetch since last sync**, not a sliding window. A correction or a vanished
  pre-auth that lands inside a window already fetched and moved past is never seen again.
- `SetuDataFetchGateway.fetchTransactions(consentHandleId, from, to)` already accepts an arbitrary
  `from`/`to` — the gateway interface needs no change for a sliding window; only the *caller's*
  range computation does.
- **Confirmed gap, not previously flagged anywhere:** the design spec's own "Architecture" section
  describes an `AccountAggregatorReconciliationSweepService` — "finds links with no sync past their
  expected cadence + grace window, force-fetches directly rather than waiting on a webhook that may
  never arrive." Grepped for `@Scheduled` across the whole `integrations/setu/` package: three sweep
  services exist (`AccountAggregatorLinkSweepService` — reaps stale `CONSENT_PENDING` rows, not a
  data fetch; `AccountAggregatorOutageSweepService` — Plan 4, read-only staleness *observability*,
  mutates nothing; `AccountAggregatorLinkLifecycleSweepService` — Plan 5, PAUSED/ACTIVE/EXPIRED
  status only). **None of them force-fetch data.** Today, the `data.ready` webhook is the *only*
  thing that ever triggers an ongoing sync — confirmed via `grep -rn "fetchService.sync("`, two call
  sites total: the one-time 3-month backfill on first `ACTIVE`, and the webhook case. If Setu's
  webhook for a given link is ever lost or never arrives, that link simply never syncs again, with
  nothing else pulling data, ever. This bears directly on Plan 6: sliding-window re-fetch only
  detects a correction or a missing row *if a fetch actually happens*, and right now there's no
  fallback trigger at all.

## The central mechanical problem: "changed" can't be detected the way it sounds

`transactionFingerprint = hash(accountId, amount, direction, valueDate, normalize(narration))` —
the fingerprint **includes amount and narration**, the exact fields a correction changes. A
genuinely corrected transaction (same real-world event, different amount on posting) produces a
**different fingerprint**, indistinguishable from a brand-new transaction by fingerprint alone.

That leaves `externalTxnId` as the *only* possible signal for "this is the same transaction, but
its values changed" — and the design spec's own "Transaction identity and idempotency" section says
plainly: "Not established: whether every FIP populates it consistently... or whether it's stable
across a pending→posted transition for the same transaction." Plan 6's entire "changed" detection
capability rests on an assumption the spec itself flags as unverified. For any transaction where
`externalTxnId` is absent, null, or changes across a pending→posted transition, a real correction is
structurally indistinguishable from `{old row untouched, new row inserted}` — Plan 6 cannot fix this
with better logic; it needs either a verified-reliable `txnId` from real sandbox testing, or an
explicit decision to only support correction detection for FIPs/transactions where `txnId` proves
stable, degrading to "look like two separate transactions" everywhere else (which is exactly what
happens today, so not a regression, just a known ceiling).

## In scope (candidate — not yet decided, see "Still open")

- Sliding-window re-fetch on every `data.ready` tick and every backfill-style fetch: re-request a
  trailing window (design spec suggests 7–14 days) instead of "since last sync," so a correction or
  a disappearing pre-auth inside that window is still visible.
- Three-way diff per fetch, replacing `mapNew`'s current skip-if-seen logic:
  - *New* — unchanged from today, insert.
  - *Changed* (same `externalTxnId`, different amount/narration) — update the existing row in
    place, and record that a correction occurred (see "Decisions needed" — what "recorded" means).
  - *Missing* (a row a prior fetch of this window returned, absent from this one) — never hard-delete
    on a single absence; some kind of flagged/pending state that resolves after a grace period or a
    second confirming fetch.
- The force-fetch safety-net sweep the design spec already speced for Plan 2 but was never built —
  needed for Plan 6's own diff logic to run at all when a webhook is missed, not solely a
  reliability nice-to-have.
- Some review surface for changed/missing rows — this codebase already has two structurally
  different precedents to choose between (see "Decisions needed"): `needsCategoryReview` (a boolean
  flag on `Transaction`, surfaced in Ledger/Ask Once) and the FUZZY-confidence graph-edge mechanism
  reconciliation already uses for ambiguous ties.

## Out of scope

- **`CREDIT_CARD`** — still gated behind the same unresolved real-sandbox validation task every
  prior AA plan has deferred (issuer coverage, `txnId` reliability, posted-vs-transaction-date drift
  — the design spec's own explicit "named pre-implementation task, not a footnote"). Plan 6 builds
  for `DEPOSIT` only, same as every plan before it.
- **AA-vs-Gmail canonicalization** — Plan 3's territory, already shipped, unrelated to bank-side
  mutations.
- **Cost controls / consent-management UX changes** — Plan 5's territory, already shipped. Plan 6
  does not touch link caps, rate limiting, or the Bank Sync settings section, though the sliding
  window's own Setu-call cost is a real consideration (see "Still open").
- **Whether Setu's fetch API actually supports re-requesting an arbitrary overlapping past range at
  all** — the design spec flags this as needing sandbox verification "before this is
  implementation-final." Not assumed true here either; if it turns out Setu only supports
  since-a-cursor fetches, the sliding-window approach doesn't work as speced and this plan's
  approach needs to change before implementation, not after.

## Decisions needed (genuinely open, not mine to settle unilaterally)

1. **What does "record that a correction occurred" mean, concretely?** Options, not yet chosen:
   - Keep only the corrected value (overwrite), with a `correctedAt`/`previousAmount`-style audit
     trail field(s) on `Transaction` itself — cheap, but grows the entity's own shape.
   - A separate small history/audit table, keyed to the transaction, append-only — more normalized,
     more moving parts, mirrors how `AuditLog` already works for other sensitive changes.
   - Reuse `AuditService`/`AuditLog` directly (already the established mechanism for "something
     changed, who/when needs to be queryable") rather than inventing new columns or a new table.
2. **What happens to a corrected row the user already interacted with** (categorized it, marked it
   not-a-duplicate, reconciled it, included it in a budget)? The design spec's own reasoning for why
   silent overwrite is wrong ("if the user already categorized or reconciled that row against its
   old values, a silent overwrite would invalidate a decision they made without telling them") stops
   short of saying what to do instead — surface a re-review prompt? Keep the user's category but flag
   the amount? This is a product decision with real UX shape, not an implementation detail.
3. **Exact grace period / confirming-fetch count for "missing" before it resolves.** No existing
   precedent in this codebase to copy (checked: `StatementImportService`'s "supersession" grace
   period is a different mechanism — a whole re-uploaded statement replacing an old one, not a
   single transaction's disappearance from a live feed). A number has to be picked and justified,
   not borrowed.
4. **What "resolves" even means for a missing row.** Soft-delete (a new `ReconciliationStatus` or a
   boolean, excluded from totals but kept for audit — same shape `SUPERSEDED` already uses for a
   different case)? Hard-delete after the grace period, finally? The design spec explicitly rejects
   hard-delete on a *single* absence but doesn't say what happens after the grace period elapses.
5. **Review surface choice** (see "In scope" above) — `needsCategoryReview`-style boolean+queue, or
   the FUZZY-graph-edge mechanism reconciliation already has. These aren't equivalent: the boolean
   pattern is simpler and already has UI (Ask Once); the graph-edge pattern is what an *ambiguous
   match* already looks like to a user today, which a "did this transaction actually change or
   disappear" question is a different shape of than "which of these two rows is the duplicate."
6. **Sliding-window size and its cost interaction with `ReconciliationService`.** `SetuDataFetchService.sync`
   already re-runs `reconciliationService.reconcileForImport(userId, from, to)` over every fetched
   range on every successful sync. A 7–14 day sliding window fetched on *every* `data.ready` tick
   means reconciliation re-evaluates the same days repeatedly, not just the new sliver — its own doc
   comment says these passes are "idempotent full re-evaluations," so correctness isn't at risk, but
   the repeated work has a real, uncosted daily overhead this plan should measure, not just note.
7. **Whether the force-fetch safety net (missing from Plan 2, needed by Plan 6) is properly this
   plan's job or a small Plan 2 follow-up landed first.** It's pure infrastructure Plan 6 depends on
   but didn't create the need for — worth deciding whether to land it as its own small PR before the
   diff logic, rather than bundled into one large Plan 6 change.

## Still open (external unknowns, not blocking scoping, blocking implementation)

- Whether Setu's fetch API supports an arbitrary overlapping re-fetch range (see "Out of scope").
- Whether `externalTxnId` is actually populated consistently and stable across pending→posted, per
  FIP — this plan's entire "changed" detection ceiling depends on the answer (see "central
  mechanical problem" above).
- Exact field-level definition of "changed" per FI type (design spec's own words: "final field-level
  behavior... still needs validation against real Setu sandbox responses").

## Testing approach (once this scope is settled)

- Unit: the three-way diff logic against hand-written fixture JSON (new/changed/missing cases,
  mirroring Plan 2's own fixture-based mapper tests — no real sandbox access here either).
- Integration: a full sliding-window re-fetch → diff → persist round trip against a stubbed gateway,
  including a corrected row surviving with its audit trail and a missing row's grace-period
  resolution, proven against real Postgres (same discipline this session's post-implementation
  review passes have required for every AA plan so far — mutating transaction rows on a schedule is
  exactly the shape of bug class that needs a real-transaction proof, not just a mocked unit test).
- No real-bank testing possible before real sandbox access exists — same ceiling every prior AA plan
  has had.
