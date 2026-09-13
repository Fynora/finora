# Account Aggregator sync — Plan 6 scope (bank-side mutation handling)

Status: scoping, not yet an implementation plan — and unlike Plans 1–5, **not architecturally
settled yet**, per review. Two things are still unknown that aren't implementation details, they
determine whether the whole mutation-handling strategy is even viable:

1. Whether Setu/FIPs provide a transaction identity signal (`externalTxnId`) stable enough to
   detect a correction at all — see "The central mechanical problem" below. If not, for the FIPs
   where it fails, "changed" cannot be distinguished from "old row untouched, new row inserted" by
   any logic this plan could write.
2. What "a correction happened" should actually mean to a user who already categorized,
   reconciled, or budgeted around the old value — see "The real blocking question" below. This is a
   product decision, not an engineering one, and this plan cannot move to an implementation plan
   without an answer.

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

## Recommendation: land the force-fetch gap first, as its own small change, ahead of anything else

This is probably the single biggest finding in this scope doc, bigger than mutation-handling
itself, and review agreed it should be elevated above the rest of Plan 6 rather than bundled in.

The design spec's own "Architecture" section describes an `AccountAggregatorReconciliationSweepService`
— "finds links with no sync past their expected cadence + grace window, force-fetches directly
rather than waiting on a webhook that may never arrive." That was speced back in Plan 2. Grepped for
`@Scheduled` across the whole `integrations/setu/` package: three sweep services exist
(`AccountAggregatorLinkSweepService` — reaps stale `CONSENT_PENDING` rows, not a data fetch;
`AccountAggregatorOutageSweepService` — Plan 4, read-only staleness *observability*, mutates
nothing; `AccountAggregatorLinkLifecycleSweepService` — Plan 5, PAUSED/ACTIVE/EXPIRED status only).
**None of them force-fetch data.** Today, the `data.ready` webhook is the *only* thing that ever
triggers an ongoing sync — confirmed via `grep -rn "fetchService.sync("`, two call sites total: the
one-time 3-month backfill on first `ACTIVE`, and the webhook case.

Concretely:

```text
missed webhook → no future sync → forever
```

That's a materially worse failure mode than "mutations aren't detected" — an entire link can go
silently, permanently stale with nothing anywhere noticing beyond `AccountAggregatorOutageSweepService`'s
gauge, which only observes, never acts. It also means Plan 6's own diff logic is structurally
unable to run for an affected link at all: sliding-window re-fetch only detects a correction or a
missing row *if a fetch actually happens*.

**Land `AccountAggregatorReconciliationSweepService` as its own small PR first, independent of the
diff logic below.** It's pure infrastructure Plan 6 depends on but didn't create the need for, it's
a genuine production risk on its own regardless of whether mutation-handling ever ships, and
bundling it into one large Plan 6 change would obscure a fix that's valuable by itself.

## The central mechanical problem: "changed" can't be detected the way it sounds

`transactionFingerprint = hash(accountId, amount, direction, valueDate, normalize(narration))` —
the fingerprint **includes amount and narration**, the exact fields a correction changes. A
genuinely corrected transaction (same real-world event, different amount on posting) produces a
**different fingerprint**, indistinguishable from a brand-new transaction by fingerprint alone.

That leaves `externalTxnId` as the *only* possible signal for "this is the same transaction, but
its values changed" — and the design spec's own "Transaction identity and idempotency" section says
plainly: "Not established: whether every FIP populates it consistently... or whether it's stable
across a pending→posted transition for the same transaction." Plan 6's entire "changed" detection
capability rests on an assumption the spec itself flags as unverified.

This is not a bug in the proposed logic — it's an information-theory ceiling. No amount of clever
code can infer identity if the upstream system stops providing it. For any transaction where
`externalTxnId` is absent, null, or changes across a pending→posted transition, a real correction is
structurally indistinguishable from `{old row untouched, new row inserted}`. Plan 6 needs either a
verified-reliable `txnId` from real sandbox testing, or an explicit decision to only support
correction detection for FIPs/transactions where `txnId` proves stable, degrading to "look like two
separate transactions" everywhere else (which is exactly what happens today, so not a regression,
just a known ceiling).

## The real blocking question: what does a correction mean to the user, not how is it detected

Even perfect `externalTxnId`-based detection still leaves this unanswered:

```text
user categorizes/reconciles/budgets a transaction at ₹500
   ↓
bank corrects the same transaction to ₹700
   ↓
now what?
```

The design spec's own reasoning for why silent overwrite is wrong ("if the user already categorized
or reconciled that row against its old values, a silent overwrite would invalidate a decision they
made without telling them") stops short of saying what to do instead. This is a product decision
Plan 6 cannot be finalized without — not an implementation detail deferred to task-writing time.

## In scope (revised per review — narrower than the first draft)

- Sliding-window re-fetch on every `data.ready` tick and every backfill-style fetch: re-request a
  trailing window instead of "since last sync," so a correction or a disappearing pre-auth inside
  that window is still visible. Window size is **not** a config value to pick now — see "Still
  open."
- `AccountAggregatorReconciliationSweepService` — the force-fetch safety net (see "Recommendation"
  above), landed first, independent of the diff logic.
- Three-way diff per fetch, replacing `mapNew`'s current skip-if-seen logic — **narrowed from the
  first draft, per review**:
  - *New* — unchanged from today, insert.
  - *Changed* (same `externalTxnId`, different amount/narration) — **detected, but not silently
    applied as an ordinary update.** The corrected value is a business event, not a database write:
    preserve the old value's history and generate a review task rather than overwriting in place
    and hoping an audit trail is enough. Exact mechanics (what the user sees, whether the old value
    stays visible until reviewed) depend on "The real blocking question" above, which is not yet
    answered.
  - *Missing* (a row a prior fetch of this window returned, absent from this one) — **v1 scope is
    flag-only.** Per review: missing is the harder problem of the two (a vanished pre-auth, a
    provider bug, a window inconsistency, and a genuine correction all look identical at this
    layer), and this codebase has no existing precedent for the grace-period/second-confirming-fetch
    resolution logic the design spec originally described. Building automatic resolution now, before
    real sandbox data exists on how often and why rows actually go missing, risks a false positive
    doing something destructive. v1 flags a missing row for review and stops there; automatic
    resolution is explicitly deferred to a later iteration once real data justifies a specific grace
    period, not designed speculatively here.
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
- **Automatic resolution of a missing row** — narrowed to v1 flag-only per review; see "In scope."
- **Whether Setu's fetch API actually supports re-requesting an arbitrary overlapping past range at
  all** — the design spec flags this as needing sandbox verification "before this is
  implementation-final." Not assumed true here either; if it turns out Setu only supports
  since-a-cursor fetches, the sliding-window approach doesn't work as speced and this plan's
  approach needs to change before implementation, not after.

## Decisions made (settled per review)

- **Correction audit trail: reuse `AuditService`/`AuditLog`, not new columns on `Transaction` and
  not a dedicated history table.** Ranked and reasoned in review: `AuditLog` already exists to
  answer exactly "what changed, when" for other sensitive flows, and it's the only option of the
  three that doesn't break down under repeated corrections — a `previousAmount`/`previousNarration`-
  style column pair only ever holds the immediately-prior value, but a transaction can legitimately
  be corrected more than once (pending → adjusted → posted), and columns can't hold an unbounded
  history the way an append-only log can without the entity's own shape growing indefinitely.

## Decisions still needed (genuinely open, not mine to settle unilaterally)

1. **What does a correction mean to the user** — see "The real blocking question" above. This is
   the decision the rest of Plan 6 is downstream of; nothing else here can be finalized ahead of it.
2. **Review surface choice** for changed/missing rows — `needsCategoryReview`-style boolean+queue,
   or the FUZZY-graph-edge mechanism reconciliation already has. These aren't equivalent: the
   boolean pattern is simpler and already has UI (Ask Once); the graph-edge pattern is what an
   *ambiguous match* already looks like to a user today, which "did this transaction actually
   change or disappear" is a different shape of question than "which of these two rows is the
   duplicate." Depends partly on the answer to (1).
3. **Sliding-window size's cost interaction with `ReconciliationService`.** `SetuDataFetchService.sync`
   already re-runs `reconciliationService.reconcileForImport(userId, from, to)` over every fetched
   range on every successful sync. Whatever the window ends up being once evidence sets it (see
   "Still open"), fetching it on *every* `data.ready` tick means reconciliation re-evaluates the
   same days repeatedly, not just the new sliver — its own doc comment says these passes are
   "idempotent full re-evaluations," so correctness isn't at risk, but the repeated work has a real,
   uncosted daily overhead this plan should measure once a window size exists, not just note.

## Still open (external unknowns — evidence to gather, not decisions to make)

- **Sliding-window size.** Explicitly *not* a configuration value to pick now, per review: "before
  sandbox testing shows how long after first appearance corrections actually occur, any number
  (7 days, 14 days) is a guess dressed as a config default." If corrections all land within 48h, a
  14-day window is wasted fetch/reconciliation cost; if they routinely land after 10 days, a 7-day
  window misses them. This needs real sandbox measurement before implementation, not a default
  shipped now and tuned later.
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
  including a corrected row's `AuditLog` trail and a missing row's flagged state, proven against real
  Postgres (same discipline this session's post-implementation review passes have required for every
  AA plan so far — mutating transaction rows on a schedule is exactly the shape of bug class that
  needs a real-transaction proof, not just a mocked unit test).
- No real-bank testing possible before real sandbox access exists — same ceiling every prior AA plan
  has had.
