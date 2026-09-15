# Shared Merchant Category Corpus + AI Fallback — Design

Status: proposed, pending spec review. Restructured around a two-stage observation/promotion
model after a real-corpus audit (§12) found the original single-table design let unpromoted,
possibly-personal data sit as if it were reusable merchant knowledge.
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

- A private log of per-user corrections, and a separate, durable corpus of only the mappings
  that cross-user evidence has actually corroborated — membership in the corpus is earned, not
  immediate (§4).
- A trust model that lets the corpus be consulted automatically, without treating a single
  correction (or a single account) as ground truth — including detecting when a *previously*
  trusted mapping is being contradicted, not just building trust upward (§7).
- An LLM fallback for keys the corpus and every deterministic layer have nothing for, using
  Fyn's existing call/governance/audit infrastructure (§8).
- Where both plug into the existing `CategorizationService.suggest()` waterfall (§9).
- What to measure once this is live, so "the corpus exists" and "the corpus is helping" stay
  distinguishable (§10).

Out of scope, and why, is its own section (§11) — several plausible-sounding pieces of this
(reputation scoring, correlated-voter detection, a new identity resolver, a continuous confidence
score) were considered and deliberately deferred rather than silently dropped.

## 3. Identity & eligibility — the write-time invariant

**An observation (§4) is eligible to be logged at all only if both hold:**

1. The transaction's `counterpartyKey` is `vpa:`-strength (never `name:` — `CounterpartyIdentity`
   already documents `name:` keys as too weak to present as a resolved identity anywhere; the
   same reasoning applies at global scale, doubly, since a weak key now aggregates across
   strangers instead of one user's own history).
2. The transaction's `counterpartyType` (from `CounterpartyClassifier`) is `BUSINESS` or
   `FINANCIAL_INSTITUTION` — never `PERSON`, `GOVERNMENT`, or `UNKNOWN`.

This is enforced **at write time**, not filtered at read time: the code path that records an
observation returns immediately if either check fails. A `PERSON`-typed row is never persisted at
all, so a future read-path bug cannot leak it — there is nothing there to leak.

**Why `BUSINESS` and `FINANCIAL_INSTITUTION` both, not `BUSINESS` alone:** `CounterpartyType`'s
own doc comment defines `FINANCIAL_INSTITUTION` as "a bank, broker, AMC, NBFC or insurer" —
brokers and AMCs (Zerodha, ICICI Prudential MF) were always meant to live in this type, and
excluding it would cut off exactly the Investments/Insurance signal this corpus is most useful
for. The type does also catch pure bank-mechanism rows (`SB INT CREDIT`, `ATM WDL`, `NACH
MANDATE` — matched by `CounterpartyClassifier`'s `FINANCIAL_MECHANISM` pattern, checked first).
Those aren't a privacy concern — there's no third party to leak, it's the user's own bank — and
in practice they're not a corpus concern either: `FINANCIAL_MECHANISM` rows are bank-ledger
entries, not UPI transactions, so they essentially never carry a `vpa:` handle.

**Why `GOVERNMENT` stays excluded:** a judgment call, not a measured one. It's thinly evidenced
(6 of 1,869 corpus rows per `CounterpartyType`'s own doc comment) and a tax/GST payment's category
is close to fixed regardless of which government body collected it — low expected value from
crowdsourcing it. Revisit if real data says otherwise.

**This gate alone is not a sufficient privacy boundary — measured, not assumed.** §12 records a
real-corpus audit: 31.1% of distinct `BUSINESS`/`FINANCIAL_INSTITUTION`-typed, `vpa:`-keyed
identities in a real 29-statement corpus are, on inspection, a named individual with no business
signal in the narration at all. The cause is structural, not a classifier bug — India's UPI
ecosystem has both real small merchants and private individuals collecting payment over the exact
same QR/acquirer rails (Paytm, BharatPe, generic UPI collect requests), and narration text alone
cannot reliably tell them apart. `PersonToPersonTransferDetector`'s own doc comment independently
confirms this same limitation from the other direction. **This is why §3's gate is not, by
itself, treated as the privacy boundary any more — it is the entry condition for a *private,
temporary* observation (§4), and a second, independent mechanism (cross-user corroboration, §4/§5)
is what decides whether that observation ever becomes reusable corpus knowledge.**

## 4. Data model — observations vs. the corpus

**The central structural change from the first draft of this design:** a correction does not
write into a shared, queryable corpus. It writes into a private observation log. Only once
independent cross-user evidence corroborates an observation does anything become part of the
corpus — the thing the waterfall (§9) can actually see. This directly addresses §12's finding: a
`BUSINESS`-typed row for a real individual (e.g., a one-off personal UPI payment) is now, by
construction, never merchant knowledge — it is at most a private, temporarily-retained
observation that never earns promotion, because a real individual's personal payments essentially
never draw votes from 3+ independent Finora users. Three tables:

**`counterparty_category_observation`** — an append-only, private log of human corrections. Never
read by the categorization waterfall, never exposed through any suggestion surface — only the
promotion process (below) reads it.

| column | type | notes |
|---|---|---|
| `id` | UUID | |
| `counterparty_key` | text | always `vpa:...` per §3 |
| `direction` | enum (`DEBIT`, `CREDIT`) | see below |
| `category` | text | same category vocabulary as the rest of the app |
| `user_id` | UUID | used solely to count *distinct* humans, never exposed to other users |
| `counterparty_type_at_vote` | enum | snapshot for audit/debugging; not re-checked after write |
| `created_at` | timestamp | drives decay (§6), retention (below), and contradiction detection (§7) |

**Why direction is part of the key.** `Transaction` already carries debit/credit as `txn_type`.
Splitting on it is free and resolves the Kronos case (a credit from Kronos is payroll; a debit to
Kronos is a business expense) without needing a distribution or a model — they're simply
different rows.

**`shared_merchant_category`** — the actual corpus: one row per `(counterparty_key, direction)`,
created *only* by promotion, never written to directly.

| column | type | notes |
|---|---|---|
| `counterparty_key` | text | |
| `direction` | enum | |
| `status` | enum (`TRUSTED`, `DISPUTED`, `REVALIDATING`) | never `EMPTY`/`PROVISIONAL` — those describe a key with no corpus row at all (§5) |
| `category` | text | the current winning category |
| `category_distribution` | jsonb | decay-weighted shares, for the Disputed/Revalidating case and for observability (§10) |
| `distinct_user_count` | int | raw, undecayed (§5) |
| `promoted_at` | timestamp | when this row was first created |
| `last_recomputed_at` | timestamp | when `status`/`category` last changed |

**Promotion** runs synchronously whenever a new observation is recorded for a key — corrections
are rare events (a human categorizing a transaction), so there is no need for a periodic sweep in
v1. The check: does this key's full observation history now clear the `Trusted`/`Disputed`
thresholds (§5) for the first time, or does an existing `shared_merchant_category` row's status
need to change given the new observation (including a §7 contradiction)? If nothing crosses a
threshold, the observation is recorded and nothing else happens. If it later grows large enough
that per-write recomputation is measurably slow, that's a read-path optimization — sorry, a
*write*-path one — to make *then*, against real numbers, not a speculative one to build now.

**Retention** applies only to observations that have never contributed to a promoted row —
exactly the population §12's audit shows is disproportionately personal:

| Observation's key has... | Retention |
|---|---|
| 1 distinct human voter, never promoted | 6 months from the observation's `created_at` |
| 2 distinct human voters, never promoted | 12 months from the most recent observation on that key |
| Contributed to a promoted (`TRUSTED`/`DISPUTED`/`REVALIDATING`) row | Indefinite |

Once a key is promoted, its backing observations are cross-user-corroborated evidence — the same
standing §3's eligibility gate already grants a merchant, just demonstrated rather than assumed —
so they're kept indefinitely, the same way the shared corpus retains any other confirmed mapping.
An observation that *never* gets a second independent voter is, definitionally, the case §12
found risky, and ages out on a clock instead of sitting in a table forever. `Trusted` corpus rows
themselves are never subject to retention — only decay (§6) and revalidation (§7) change them.

**`shared_merchant_category_ai_suggestion`** — one row per `(counterparty_key, direction)`,
holding only the *latest* AI answer, entirely separate from both tables above:

| column | type | notes |
|---|---|---|
| `counterparty_key` | text | |
| `direction` | enum | |
| `category` | text | |
| `model` | text | which Fyn model produced this, for audit |
| `generated_at` | timestamp | |

Upserted, not appended — an AI suggestion is a cache entry ("a model guessed; don't re-ask until
this is stale"), not evidence, so there's no history to keep. §8 covers why this is a third,
fully separate table rather than a `source` flag anywhere.

## 5. Trust tiers

Computed from the observation log alone — an AI suggestion lives in a fully separate table (§4)
and is structurally incapable of being counted here.

- **Empty** — 0 human observations for the key. No row in `counterparty_category_observation` or
  `shared_merchant_category`. May still have a cached `AI` suggestion; that's a hypothesis, not
  consensus (§8).
- **Provisional** — 1–2 distinct human voters. Exists only as observation-log rows; **no
  `shared_merchant_category` row exists yet.** This is the population §12's audit found is
  disproportionately personal, which is exactly why it isn't part of the corpus at all — see §4's
  retention table.
- **Trusted** — ≥3 distinct human voters, AND the winning category holds ≥70% of decay-weighted
  observations (§6), AND the winning category has ≥3 observations in its own right (guards the
  low-N edge case where 3 total voters split 2/1 and 2-of-3 clears 70% on essentially no
  evidence). A `shared_merchant_category` row exists, `status = TRUSTED`.
- **Disputed** — ≥3 distinct human voters, but no category clears 70%. This is where genuine
  ambiguity lives (the Amazon/Myntra case): shown as unresolved, never silently picked for the
  user. A `shared_merchant_category` row exists, `status = DISPUTED`.
- **Revalidating** — a key that *was* `Trusted` and just received an observation disagreeing with
  its winning category. Behaves exactly like `Disputed` for auto-apply purposes (§9: not
  surfaced, not applied) while in this state. The row is not removed — it was already promoted,
  already cross-user-corroborated — only its `status` changes. §7 covers why this exists as its
  own state instead of letting the tier formula above re-run immediately.

The two count-based safeguards (≥3 distinct voters, ≥3 winning votes) are evidence-*volume*
checks and use raw, undecayed counts — the question they answer is "has enough independent
evidence accumulated at all," not "how fresh is it." Only the 70% share is decay-weighted.

Only **Trusted** entries are ever surfaced as a suggestion (§9). `Disputed` and `Revalidating`
rows exist in the corpus but are never shown to a user in v1 — they're a signal for the next
vocabulary-mining pass, not a weak recommendation. `Empty`/`Provisional` keys aren't corpus rows
at all, so there's nothing to show regardless.

Numeric thresholds (3 voters, 70%, 3 minimum winning votes) are starting defaults, not measured —
there is no real multi-user corpus yet to tune them against. They deliberately produce odd
outcomes at low volume today (3-of-3 votes clears Trusted at 100%; 69-of-100 votes does not,
despite being far more evidence) — acceptable for a v1 with no real vote volume to be wrong about
yet, and cheap to fix later because promotion is a pure function over the raw observation log,
with nothing else precomputed. Replacing the bucket rule with, say, a sample-size-aware confidence
interval is a formula change re-run by the promotion process, not a schema migration or a
backfill — see §11 for why that replacement isn't being built now.

**The 3-distinct-voter threshold is now doing two jobs, not one.** Before §12's audit it was a
quality mechanism (don't trust one person's habit as universal truth). After it, it's also the
mechanism that separates a personal counterparty from population-level merchant knowledge without
needing a perfect identity resolver: a real individual's personal payments essentially never draw
independent votes from 3+ unrelated Finora users, while a real merchant accumulates them
naturally over time. This is a stronger justification for keeping the threshold conservative than
"more evidence is better" alone.

## 6. Decay

Applies only within an already-promoted `shared_merchant_category` row — an unpromoted key has no
distribution to decay, only a retention clock (§4). Each observation's weight decays with its age:
`weight = base_weight * decay(now - created_at)`, half-life 12 months as a starting default. The
decay-weighted distribution, recomputed whenever a new observation triggers the promotion process
(§4) — not on a timer — is what §5's 70% test and the suggested category use. An old majority can
lose to a newer one as a merchant's real-world categorization shifts (rebranding, business-model
change); a key with no new observations simply keeps its last-computed answer, which is correct —
there's no new evidence to justify changing it.

Decay and the contradiction handling in §7 are complementary, not overlapping: decay acts
gradually, over months, and only when a new observation triggers recomputation; revalidation
triggers instantly on a *single* disagreeing observation. Decay alone, at a 12-month half-life,
would let one new contradicting vote against an established 8-1 `Trusted` mapping get
statistically swamped immediately — far too slow to catch a sudden identity change (a VPA
changing hands, a business rebrand) on its own.

## 7. Contradiction handling (merchant drift)

**The risk this addresses:** a `BUSINESS`/`FINANCIAL_INSTITUTION` `vpa:` key is not guaranteed to
refer to the same merchant forever — a handle can be reassigned, or a business can rebrand under
the same UPI ID. Decay (§6) alone handles this passively and slowly: a single new observation
against a well-established `Trusted` row barely moves an 8-1 distribution, so the corpus would
keep confidently serving the old answer for months after the underlying merchant actually changed.

**The rule:** any observation recorded against a currently-`Trusted` `shared_merchant_category`
row, whose category disagrees with that row's current winning category, immediately flips
`status` to `REVALIDATING` — regardless of how lopsided the historical evidence is. While
`Revalidating`, the row is not auto-applied (identical blast radius to `Disputed`). It becomes
eligible to re-run the promotion computation — and land back on `TRUSTED` or `DISPUTED` depending
on what the full observation history now says — only after either:

- 3 more observations are recorded against the key following the contradicting one, or
- 90 days have passed since the contradicting observation,

whichever comes first. A lone contradiction that really was a fluke re-promotes to `Trusted`
quickly once a little confirming evidence (or just time, with nothing further disagreeing) has
passed. This is a mandatory cooldown, not a new scoring model — one additional state transition,
not a parallel confidence system.

This only applies to demoting an already-`Trusted` row — a contradicting observation against a
`Provisional` (unpromoted) or `Disputed` key is just another observation, handled by the normal
promotion computation, since neither of those was ever auto-applied and so neither has anything
to protect against a premature return to trust.

**Observability tie-in:** every transition into `Revalidating` is exactly the "human override of
a Trusted mapping" event §10 tracks as a metric — a high rate of these for one merchant is itself
evidence of a badly-drifting or genuinely polymorphic key, worth a human look during the next
vocabulary-mining pass. The revalidation window's numbers (3 observations / 90 days) are starting
defaults alongside the tier thresholds in §5 — see §12.

## 8. AI integration

The AI fallback only runs when the waterfall (§9) reaches it with **no Trusted corpus entry and
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

**Why AI answers live in their own table (§4), never touching the observation log or the
promotion process.** A human observation and an AI answer are different *kinds* of evidence, not
the same kind at different confidence levels: an observation means "a person observed this
transaction and corrected it"; an AI answer means "a model produced a guess — cache it so we
don't re-ask." Mixing them relies on every future reader remembering to filter correctly before
treating a row as evidence — one missed filter anywhere (a report, a debugging query, a future
feature) quietly starts treating model guesses as corroborating evidence, or worse, as a second
"voter" that helps a key toward promotion. A fully separate table makes both mistakes structurally
impossible instead of a discipline to maintain: neither the promotion process nor §5's tier
computation has an AI row anywhere in its input, full stop.

A user's own correction always wins for that user immediately, via the existing per-user learned
distribution (`MerchantCategoryLearning`) — this was true before this design and is unchanged by
it; nothing here overrides an individual's own view of their own transactions.

## 9. Waterfall placement

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

"Shared corpus (Trusted only)" means exactly that: `shared_merchant_category` also holds
`Disputed` and `Revalidating` rows, but the waterfall only ever reads `status = TRUSTED` rows.

## 10. Observability

Metrics to define before rollout, not after — otherwise the corpus's existence is known but
whether it's actually helping isn't:

- Row counts per state: `Empty`/`Provisional` observation counts (not corpus rows), and
  `Trusted`/`Disputed`/`Revalidating` row counts in `shared_merchant_category`.
- **Promotion rate** — of all keys that ever get a first observation, what share ever get
  promoted vs. age out under retention (§4) unpromoted. Directly answers whether the corpus
  "contains only things that demonstrated cross-user relevance," the goal §4's restructuring was
  built around, rather than assuming it.
- % of transactions a `Trusted` corpus entry actually resolves — the number this whole feature
  exists to move.
- Top `Disputed` merchants by transaction count or value — direct input to the next
  vocabulary-mining pass, the same way this session's own mining passes have worked from real
  corpus reads.
- Top merchants entering `Revalidating`, and how often (§7) — the leading indicator of a
  badly-tuned threshold, a wrong decay half-life, a broken identity assumption, or a genuinely
  polymorphic merchant. Without this metric a real drift problem looks identical to normal noise.
- Rate of human overrides of a `Trusted` suggestion generally (a user manually recategorizing a
  transaction the corpus had already suggested) — the general form of the `Revalidating`-entry
  metric above, covering disagreement that doesn't happen to flip the majority outright.

## 11. Explicitly not built for v1

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
  `FINANCIAL_INSTITUTION` gating, backstopped by the promotion model in §4, removes most of the
  exposure a generic resolver would have existed to reduce.
- **A continuous confidence score in place of the tier buckets.** §5 already notes the formula is
  swappable later with no data migration; not built now because there is no real vote volume yet
  to demonstrate the bucket rule actually produces bad outcomes in practice, versus merely looking
  imprecise on a hypothetical example.
- **A periodic promotion sweep.** §4's promotion check runs synchronously on write; a background
  job is only worth building if write-time recomputation is later measured to be too slow, which
  requires real volume this design doesn't have yet.

## 12. Real-corpus audit: `BUSINESS`/`FINANCIAL_INSTITUTION` false-positive rate

**This section changed from an open question to a finding, and the finding is what §3/§4/§5
restructure around.**

Method: every `BUSINESS`/`FINANCIAL_INSTITUTION`-typed, `vpa:`-keyed transaction in the real
29-statement corpus (1,869 rows total) was extracted via the real classification pipeline
(`CounterpartyTyping.of`, no mocking), collapsed to distinct `(type, key)` pairs, and hand-reviewed
for whether the narration actually names a business or a private individual. Narration text was
viewed only transiently during the review; nothing was written to a file or committed.

**Result:**

- 710 of 1,869 rows (38.0%) were `BUSINESS`/`FINANCIAL_INSTITUTION`-typed and `vpa:`-keyed,
  collapsing to 305 distinct identities.
- **95 of 305 distinct identities (31.1%)** are, on inspection, clearly a named individual with no
  business signal in the narration — full personal names receiving payment over the same UPI
  QR/acquirer rails a real shop uses.
- **146 of 710 occurrences (20.6%), volume-weighted** — lower than the distinct-identity rate
  because high-frequency keys are dominated by real major brands, but still one in five.

**Why this isn't fixable by another narration-side heuristic:** `PersonToPersonTransferDetector`
was considered as a second veto and rejected — it shares the exact acquirer-rail-marker signal
that caused the original misclassification, so it would catch nothing new. Both classes'
own doc comments independently describe this as a structural limit of narration-only
classification in the Indian UPI ecosystem, not a gap a vocabulary addition closes.

**One separately-fixable bug found in passing, filed independently, not part of this design's
scope:** at least one statement format prefixes every narration with the card issuer's own name
("HDFC BANK LIMITED"), spuriously matching `FINANCIAL_ENTITY`'s `\bbank\b` token regardless of the
actual counterparty — confirmed flipping at least one real individual's row from `BUSINESS` to
`FINANCIAL_INSTITUTION` for that reason alone. This affects `CounterpartyClassifier` app-wide
(analytics, merchant grouping, any future identity feature), so it's tracked and fixed on its own,
not gated on this design.

**Methodology limits, stated plainly:** one reviewer, one 29-statement corpus, manual judgment
with no second rater and no formal inter-rater check. The 31.1%/20.6% figures are strong direct
evidence that the risk is real and non-trivial — not a large-scale statistical validation. Re-run
this same measurement against a larger, more diverse corpus before general availability, ideally
with a second reviewer on a sample.

**Remaining open items, not yet measured:**

- Whether the 3-voter / 70% / 3-minimum-winning-votes thresholds (§5), the 12-month decay
  half-life (§6), the 3-observation/90-day revalidation window (§7), and the 6-/12-month retention
  windows (§4) are reasonable once real multi-user vote volume exists.
- The promotion rate (§10) once live — the real-world confirmation that unpromoted, personal-
  shaped observations actually age out rather than accumulate.

## 13. Relationship to the existing categorization waterfall

No existing waterfall step changes behavior. `CategorizationService.suggest()` gains two new steps
(shared corpus, AI fallback) inserted at the position in §9; every step before them is untouched.
