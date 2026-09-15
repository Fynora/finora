# Shared Merchant Category Corpus + AI Fallback — Design

Status: proposed, pending spec review
Supersedes: §11 ("Shared Corpus Governance") of
`docs/superpowers/specs/2026-09-01-transaction-categorization-design.md`, which sketched the idea
but explicitly left it unbuilt. This doc is that sketch, fully worked out.

## 1. Motivation

The categorization roadmap's own re-baselines (2026-09-01 spec, "Re-baseline" sections) show the
keyword layer (`CategoryRules`) topping out: each vocabulary-mining pass finds real but
shrinking gains, and each pass is bounded by what shows up in one operator's manual reading of the
corpus. Two things changed since that roadmap was written:

- Fyn AI (the in-house assistant) shipped — `LlmClient`, cost governance, a kill switch, and
  audit logging all exist and are load-bearing in production (chat, insights, import diagnosis).
  An LLM-based categorization fallback no longer needs new infrastructure, only a new caller.
- The gap the original roadmap always flagged as the *harder* prerequisite — a shared place to
  cache answers across users, so one person's correction benefits everyone who sees the same
  merchant — still doesn't exist. `Merchant` is a per-user entity (confirmed: non-nullable
  `user_id` column), so there is no cross-user corpus today.

This design builds both together, not sequentially: an AI fallback with nowhere to cache its
answers would re-ask the same question for every user who ever sees "KRONOS PAYMENT SVCS", and a
shared corpus with nothing to consult when it comes up empty leaves the exact same long tail of
one-off merchants unresolved that a keyword pass can't reach either.

## 2. What's explicitly in scope

- A cross-user table recording, per merchant-shaped counterparty, what category real users have
  assigned it.
- A trust model that lets that table be consulted automatically, without treating a single
  correction (or a single account) as ground truth.
- An LLM fallback for keys the corpus and every deterministic layer have nothing for, using
  Fyn's existing call/governance/audit infrastructure.
- Where both plug into the existing `CategorizationService.suggest()` waterfall.

Out of scope, and why, is its own section (§9) — several plausible-sounding pieces of this
(reputation scoring, correlated-voter detection, a new identity resolver) were considered and
deliberately deferred rather than silently dropped.

## 3. Identity & eligibility — the write-time invariant

**A vote is eligible for the shared corpus only if both hold:**

1. The transaction's `counterpartyKey` is `vpa:`-strength (never `name:` — `CounterpartyIdentity`
   already documents `name:` keys as too weak to present as a resolved identity anywhere; the
   same reasoning applies at global scale, doubly, since a weak key now aggregates across
   strangers instead of one user's own history).
2. The transaction's `counterpartyType` (from `CounterpartyClassifier`) is `BUSINESS` or
   `FINANCIAL_INSTITUTION` — never `PERSON`, `GOVERNMENT`, or `UNKNOWN`.

This is enforced **at write time**, not filtered at read time: the code path that records a vote
returns immediately if either check fails. A `PERSON`-typed row is never persisted to the shared
table at all, so a future read-path bug cannot leak it — there is nothing there to leak.

**Why `BUSINESS` and `FINANCIAL_INSTITUTION` both, not `BUSINESS` alone:** `CounterpartyType`'s
own doc comment defines `FINANCIAL_INSTITUTION` as "a bank, broker, AMC, NBFC or insurer" —
brokers and AMCs (Zerodha, ICICI Prudential MF) were always meant to live in this type, and
excluding it would cut off exactly the Investments/Insurance signal this corpus is most useful
for. The type does also catch pure bank-mechanism rows (`SB INT CREDIT`, `ATM WDL`, `NACH
MANDATE` — matched by `CounterpartyClassifier`'s `FINANCIAL_MECHANISM` pattern, checked first).
Those aren't a privacy concern — there's no third party to leak, it's the user's own bank — and
in practice they're not a corpus concern either: `FINANCIAL_MECHANISM` rows are bank-ledger
entries, not UPI transactions, so they essentially never carry a `vpa:` handle. The `vpa:`
requirement above is doing the real filtering work here; the type check is a second, independent
reason a `PERSON` row can't get in, not the only one.

**Why `GOVERNMENT` stays excluded:** a judgment call, not a measured one. It's thinly evidenced
(6 of 1,869 corpus rows per `CounterpartyType`'s own doc comment) and a tax/GST payment's category
is close to fixed regardless of which government body collected it — low expected value from
crowdsourcing it. Revisit if real data says otherwise.

## 4. Data model

**`shared_merchant_category_vote`** — an append-only event log, one row per correction:

| column | type | notes |
|---|---|---|
| `id` | UUID | |
| `counterparty_key` | text | always `vpa:...` per §3 |
| `direction` | enum (`DEBIT`, `CREDIT`) | see below |
| `category` | text | same category vocabulary as the rest of the app |
| `source` | enum (`HUMAN`, `AI`) | |
| `user_id` | UUID, nullable | null only for `AI`-sourced rows; used solely to count *distinct* humans, never exposed to other users |
| `counterparty_type_at_vote` | enum | snapshot for audit/debugging; not re-checked after write |
| `created_at` | timestamp | drives decay |

**Why direction is part of the key.** `Transaction` already carries debit/credit as `txn_type`.
Splitting the corpus key on it is free and resolves the Kronos case (a credit from Kronos is
payroll; a debit to Kronos is a business expense) without needing a distribution or a model —
they're simply different rows.

**Why an event log, not an aggregate row with a running confidence.** Decay has to reweight the
*distribution*, not a single scalar (§6) — "5 old Dining votes vs. 3 new Shopping votes" can flip
the winner once old votes are discounted, which an aggregate `row.confidence *= decayFactor` can't
express. Storing individual votes and recomputing the distribution from decay-weighted votes is
both more correct and, here, simpler than maintaining an aggregate incrementally.

**Read path.** `category_distribution`, `distinctUserCount`, and tier (§5) are computed from the
vote log on read, `@Cacheable` with a short TTL — the same precedent
`FynCostGovernanceService.monthlyBudget()` already established for an org-wide aggregate that's
consulted on every request: no bespoke invalidation, a cache miss falls through to the real query,
and the only thing that ever changes the value is more votes accruing. Appropriate here because
the corpus starts from zero real volume; if it later grows large enough that on-read aggregation
is measurably slow, that's a read-path optimization to make *then*, against real numbers, not a
speculative one to build now.

## 5. Trust tiers

Computed per `(counterparty_key, direction)` from **`HUMAN`-sourced votes only** — `AI` votes
never count toward any tier:

- **Empty** — 0 human votes. May still have `AI` votes; that's a hypothesis, not consensus (see
  §7 for why this is its own state rather than folded into Provisional).
- **Provisional** — 1–2 distinct human voters.
- **Trusted** — ≥3 distinct human voters, AND the winning category holds ≥70% of decay-weighted
  votes (§6), AND the winning category has ≥3 votes in its own right (guards the low-N edge case
  where 3 total voters split 2/1 and 2-of-3 clears 70% on essentially no evidence).

The two count-based safeguards (≥3 distinct voters, ≥3 winning votes) are evidence-*volume*
checks and use raw, undecayed counts — the question they answer is "has enough independent
evidence accumulated at all," not "how fresh is it." Only the 70% share is decay-weighted.
- **Disputed** — ≥3 distinct human voters, but no category clears 70%. This is where genuine
  ambiguity lives (the Amazon/Myntra case): shown as unresolved, never silently picked for the
  user.

Only **Trusted** entries are ever surfaced as a suggestion (§8). Provisional and Disputed entries
are not shown to users at all in v1 — they're a signal for the next vocabulary-mining pass
(a human reviewing "what's accumulating votes but never reaching Trusted" is the same kind of
review this project already does manually), not a weak recommendation. Numeric thresholds (3
voters, 70%, 3 minimum winning votes) are starting defaults, not measured — there is no real
multi-user corpus yet to tune them against.

## 6. Decay

Each vote's weight decays with its age at read time: `weight = base_weight * decay(now -
created_at)`, half-life 12 months as a starting default. The distribution used for tier
computation and for the suggested category is the decay-weighted sum over the vote log, not the
raw count — an old majority can lose to a newer one as a merchant's real-world categorization
shifts (rebranding, business-model change), which a per-row confidence scalar can't express (§4).

## 7. AI integration

The AI fallback only runs when the waterfall (§8) reaches it with **no Trusted corpus entry and
no keyword/rule match** for the key. It reuses Fyn's existing infrastructure as-is, with no new
governance built:

- `LlmClient.complete()` for the call itself.
- `FynAvailabilityGuard` / `FynCostGovernanceService` for the same kill switch and per-user daily
  / org monthly caps chat and insights already respect — a categorization call is just another
  caller against the same budget.
- `FynImportDiagnosisService`'s shape as the template: a narrow, PII-scrubbed prompt (the
  narration text and nothing else user-identifying) in, a short answer out, an `AiAuditLog` row
  written on both success and failure (per `LlmClient`'s own doc comment, skipping this silently
  defeats the cost caps for this call path).

**An AI answer is written to `shared_merchant_category_vote` with `source = AI` and is visible as
an `Empty`-state entry, but it can never by itself promote a key to Provisional or Trusted** — the
tier computation in §5 only counts `HUMAN` rows. This is deliberate: an AI guess is a prior worth
showing once, not a vote that should ever look like consensus. If a human later confirms or
corrects it, that's a normal `HUMAN` vote like any other, counted the same as if the AI had never
been consulted.

A user's own correction always wins for that user immediately, via the existing per-user learned
distribution (`MerchantCategoryLearning`) — this was true before this design and is unchanged by
it; nothing here overrides an individual's own view of their own transactions.

## 8. Waterfall placement

```
User rules
→ Global rules
→ Per-user learned distribution   (unchanged — a user's own correction always outranks the below, for that user)
→ CategoryRules                   (unchanged position)
→ Shared corpus (Trusted only)    (new)
→ AI fallback                     (new)
→ Structural P2P
→ Other
```

**Shared corpus is placed after `CategoryRules`, not before.** `CategoryRules` is deterministic
and explainable — a word-boundary match is effectively 100% confidence by construction; the
corpus is statistical, even at Trusted. Where a key exists in both, the keyword rule should win.
The corpus is **not** given authority to override a stale keyword mapping in v1 — if a Trusted
corpus entry and a keyword rule persistently disagree for the same key over time, that's a signal
worth surfacing to the next vocabulary-mining pass (the keyword is probably wrong and should be
fixed there), not something the waterfall resolves automatically. This is a real product decision,
not an oversight, and is recorded here so it isn't quietly relitigated later.

## 9. Explicitly not built for v1

- **Voter-reputation / sockpuppet scoring.** Reddit/Wikipedia-style reputation systems exist
  because signing up is free and anonymous. Casting a vote here requires having imported a real
  bank statement and corrected a real transaction on it — a real cost an open web platform doesn't
  have. Treated as a real but currently unmeasured risk; revisit if it shows up in practice, not
  before.
- **Correlated-voter (household) detection.** No existing infrastructure to build on (the
  household/relationship-graph work in this codebase was scoped once and parked). The generous
  distinct-voter threshold plus the Disputed fallback already absorbs an honest household
  disagreeing with the wider population; a real correlation-detection system is only worth
  building if real corpus data later shows this actually distorts outcomes.
- **A new `merchant_identity_id` resolver.** Not needed once §3's `vpa:` + `BUSINESS`/
  `FINANCIAL_INSTITUTION` gating removes most of the collision risk a generic resolver would have
  existed to solve.

## 10. Open validation items (not yet measured)

- Whether the 3-voter / 70% / 3-minimum-winning-votes thresholds (§5) and the 12-month decay
  half-life (§6) are reasonable once real multi-user vote volume exists — no corpus data exists
  yet to check this against.
- Whether any `FINANCIAL_MECHANISM`-shaped rows (§3) do carry a `vpa:` handle in practice; expected
  to be rare-to-none but not directly measured.

## 11. Relationship to the existing categorization waterfall

No existing waterfall step changes behavior. `CategorizationService.suggest()` gains two new steps
(shared corpus, AI fallback) inserted at the position in §8; every step before them is untouched.
