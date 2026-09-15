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
  correction (or a single account) as ground truth — including detecting when a *previously*
  trusted mapping is being contradicted, not just building trust upward.
- An LLM fallback for keys the corpus and every deterministic layer have nothing for, using
  Fyn's existing call/governance/audit infrastructure.
- Where both plug into the existing `CategorizationService.suggest()` waterfall.
- What to measure once this is live, so "the corpus exists" and "the corpus is helping" stay
  distinguishable.

Out of scope, and why, is its own section (§11) — several plausible-sounding pieces of this
(reputation scoring, correlated-voter detection, a new identity resolver, a continuous confidence
score) were considered and deliberately deferred rather than silently dropped.

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

**How reliable is `BUSINESS`/`FINANCIAL_INSTITUTION` itself, as a privacy boundary?** Checked
directly against `CounterpartyClassifier.classify()`, not assumed: the type is assigned
deterministically from ordered regex checks over the narration text — not by AI, not by a learned
model, and with no attached confidence score. `PERSON` is deliberately the *last* positive check
run (`CounterpartyClassifier.java:134`), because "anything with a business or institutional
signal must be taken off the table before it is consulted" (the classifier's own doc comment,
line 34). That ordering is correct for the classifier's original purpose — don't credit an
ambiguous row to a person when a business signal is present — but it means the bias runs the
direction that matters here: an ambiguous row leans `BUSINESS`, not `PERSON`. A real individual
whose narration happens to contain a trade word or corporate-suffix-shaped token could be typed
`BUSINESS` and become eligible for the corpus. This is a real, not-yet-measured risk to the
eligibility gate in this section — §12 makes measuring it a pre-launch gate, not an optional
follow-up.

## 4. Data model

Two tables — human corrections and AI answers are structurally separate, not merely distinguished
by a field, for the reasons in §8.

**`shared_merchant_category_vote`** — an append-only log of human corrections only:

| column | type | notes |
|---|---|---|
| `id` | UUID | |
| `counterparty_key` | text | always `vpa:...` per §3 |
| `direction` | enum (`DEBIT`, `CREDIT`) | see below |
| `category` | text | same category vocabulary as the rest of the app |
| `user_id` | UUID | used solely to count *distinct* humans, never exposed to other users |
| `counterparty_type_at_vote` | enum | snapshot for audit/debugging; not re-checked after write |
| `created_at` | timestamp | drives decay (§6) and contradiction detection (§7) |

**Why direction is part of the key.** `Transaction` already carries debit/credit as `txn_type`.
Splitting the corpus key on it is free and resolves the Kronos case (a credit from Kronos is
payroll; a debit to Kronos is a business expense) without needing a distribution or a model —
they're simply different rows.

**Why an event log, not an aggregate row with a running confidence.** Decay has to reweight the
*distribution*, not a single scalar (§6) — "5 old Dining votes vs. 3 new Shopping votes" can flip
the winner once old votes are discounted, which an aggregate `row.confidence *= decayFactor` can't
express. Storing individual votes and recomputing the distribution from decay-weighted votes is
both more correct and, here, simpler than maintaining an aggregate incrementally. It also keeps
the tier rule (§5) replaceable later without a data migration — see the note at the end of §5.

**Read path.** `category_distribution`, `distinctUserCount`, and tier (§5) are computed from the
vote log on read, `@Cacheable` with a short TTL — the same precedent
`FynCostGovernanceService.monthlyBudget()` already established for an org-wide aggregate that's
consulted on every request: no bespoke invalidation, a cache miss falls through to the real query,
and the only thing that ever changes the value is more votes accruing. Appropriate here because
the corpus starts from zero real volume; if it later grows large enough that on-read aggregation
is measurably slow, that's a read-path optimization to make *then*, against real numbers, not a
speculative one to build now.

**`shared_merchant_category_ai_suggestion`** — one row per `(counterparty_key, direction)`,
holding only the *latest* AI answer:

| column | type | notes |
|---|---|---|
| `counterparty_key` | text | |
| `direction` | enum | |
| `category` | text | |
| `model` | text | which Fyn model produced this, for audit |
| `generated_at` | timestamp | |

Upserted, not appended — an AI suggestion is a cache entry ("a model guessed; don't re-ask until
this is stale"), not permanent evidence, so there is no reason to keep a history of superseded
guesses the way there is for human votes. §8 covers why this lives in its own table instead of a
`source` flag on the vote log.

## 5. Trust tiers

Computed per `(counterparty_key, direction)` from the human vote log alone — an AI suggestion
lives in a separate table (§4) and is structurally incapable of being counted here, not merely
filtered out.

- **Empty** — 0 human votes. May still have a cached `AI` suggestion; that's a hypothesis, not
  consensus (§8).
- **Provisional** — 1–2 distinct human voters.
- **Trusted** — ≥3 distinct human voters, AND the winning category holds ≥70% of decay-weighted
  votes (§6), AND the winning category has ≥3 votes in its own right (guards the low-N edge case
  where 3 total voters split 2/1 and 2-of-3 clears 70% on essentially no evidence).
- **Disputed** — ≥3 distinct human voters, but no category clears 70%. This is where genuine
  ambiguity lives (the Amazon/Myntra case): shown as unresolved, never silently picked for the
  user.
- **Revalidating** — a key that *was* `Trusted` and just received a human vote disagreeing with
  its winning category. Behaves exactly like `Disputed` for auto-apply purposes (§9: not
  surfaced, not applied) while in this state. §7 covers why this exists as its own state instead
  of letting the tier formula above re-run immediately on the new vote.

The two count-based safeguards (≥3 distinct voters, ≥3 winning votes) are evidence-*volume*
checks and use raw, undecayed counts — the question they answer is "has enough independent
evidence accumulated at all," not "how fresh is it." Only the 70% share is decay-weighted.

Only **Trusted** entries are ever surfaced as a suggestion (§9). Provisional, Disputed, and
Revalidating entries are not shown to users at all in v1 — they're a signal for the next
vocabulary-mining pass (a human reviewing "what's accumulating votes but never reaching Trusted,
or just fell out of Trusted" is the same kind of review this project already does manually), not
a weak recommendation.

Numeric thresholds (3 voters, 70%, 3 minimum winning votes) are starting defaults, not measured —
there is no real multi-user corpus yet to tune them against. They deliberately produce odd
outcomes at low volume today (3-of-3 votes clears Trusted at 100%; 69-of-100 votes does not,
despite being far more evidence) — acceptable for a v1 with no real vote volume to be wrong
about yet, and cheap to fix later precisely because tier computation is a pure function over the
raw vote log, with nothing else precomputed or stored per key. Replacing the bucket rule with,
say, a sample-size-aware confidence interval is a formula change, not a schema migration or a
backfill — see §11 for why that replacement isn't being built now.

## 6. Decay

Each vote's weight decays with its age at read time: `weight = base_weight * decay(now -
created_at)`, half-life 12 months as a starting default. The distribution used for tier
computation and for the suggested category is the decay-weighted sum over the vote log, not the
raw count — an old majority can lose to a newer one as a merchant's real-world categorization
shifts (rebranding, business-model change), which a per-row confidence scalar can't express (§4).

Decay and the contradiction handling in §7 are complementary, not overlapping: decay acts
gradually, over months, on the *whole* distribution; revalidation triggers instantly on a
*single* disagreeing vote. Decay alone, at a 12-month half-life, would let one new contradicting
vote against an established 8-1 `Trusted` mapping get statistically swamped immediately — far too
slow to catch a sudden identity change (a VPA changing hands, a business rebrand) on its own.

## 7. Contradiction handling (merchant drift)

**The risk this addresses:** a `BUSINESS`/`FINANCIAL_INSTITUTION` `vpa:` key is not guaranteed to
refer to the same merchant forever — a handle can be reassigned, or a business can rebrand under
the same UPI ID. Decay (§6) alone handles this passively and slowly: a single new vote against a
well-established `Trusted` mapping barely moves an 8-1 distribution, so the corpus would keep
confidently serving the old answer for months after the underlying merchant actually changed.

**The rule:** any human vote recorded against a currently-`Trusted` key, whose category disagrees
with that key's current winning category, immediately moves the key to `Revalidating` (§5) —
regardless of how lopsided the historical vote count is. While `Revalidating`, the key is not
auto-applied (identical blast radius to `Disputed`). It becomes eligible to re-run the normal
tier computation — and land back on `Trusted`, `Disputed`, or `Provisional` depending on what the
*full* vote log, old votes included, now says — only after either:

- 3 more human votes are recorded against the key following the contradicting one, or
- 90 days have passed since the contradicting vote,

whichever comes first. A lone contradiction that really was a fluke re-promotes to `Trusted`
quickly once a little confirming evidence (or just time, with nothing further disagreeing) has
passed. This is a mandatory cooldown, not a new scoring model — one additional state transition,
not a parallel confidence system.

This state only applies to demoting an already-`Trusted` key — a contradicting vote against a
`Provisional` or `Disputed` key is just another vote, handled by the normal tier formula, since
neither of those tiers was ever auto-applied and so neither has anything to protect against a
premature return to trust.

**Observability tie-in:** every transition into `Revalidating` is exactly the "human override of
a Trusted mapping" event §10 tracks as a metric — a high rate of these for one merchant is itself
evidence of a badly-drifting or genuinely polymorphic key, worth a human look during the next
vocabulary-mining pass. The revalidation window's numbers (3 votes / 90 days) are starting
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

**Why AI answers live in their own table (§4), not as a `source = AI` row mixed into the human
vote log.** A human vote and an AI answer are different *kinds* of evidence, not the same kind at
different confidence levels: a human vote means "a person observed this transaction and corrected
it"; an AI answer means "a model produced a guess — cache it so we don't re-ask." Keeping both in
one table relies on every future reader remembering to filter `source = HUMAN` before treating a
row as evidence — one missed filter anywhere (a report, a debugging query, a future feature)
quietly starts treating model guesses as corpus consensus. Separate tables make that mistake
structurally impossible instead of a discipline to maintain: §5's tier computation reads only the
vote table, which has no AI rows in it to accidentally include, full stop.

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

## 10. Observability

Metrics to define before rollout, not after — otherwise the corpus's existence is known but
whether it's actually helping isn't:

- Row counts per tier (`Empty`/`Provisional`/`Trusted`/`Disputed`/`Revalidating`).
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
  `FINANCIAL_INSTITUTION` gating removes most of the collision risk a generic resolver would have
  existed to solve.
- **A continuous confidence score in place of the tier buckets.** §5 already notes the formula is
  swappable later with no data migration; not built now because there is no real vote volume yet
  to demonstrate the bucket rule actually produces bad outcomes in practice, versus merely looking
  imprecise on a hypothetical example.

## 12. Open validation items (not yet measured)

- **`BUSINESS` classification's false-positive rate on `PERSON` rows — required before the corpus
  accepts writes from real users, not merely a future tuning item.** §3 traces
  `CounterpartyClassifier`'s own check ordering: business and institutional signals are checked,
  and suppress a person classification, before the person check ever runs, so an ambiguous row
  leans `BUSINESS`. Since `BUSINESS`/`FINANCIAL_INSTITUTION` is this design's entire privacy
  boundary (§3), this needs a real audit — sample real `BUSINESS`-typed, `vpa:`-keyed rows and
  check how many are actually a named individual — before any write path to the shared corpus
  goes live. This is the one item in this section that gates launch rather than only informing
  future tuning.
- Whether the 3-voter / 70% / 3-minimum-winning-votes thresholds (§5) and the 12-month decay
  half-life (§6) and 3-vote/90-day revalidation window (§7) are reasonable once real multi-user
  vote volume exists — no corpus data exists yet to check any of them against.
- Whether any `FINANCIAL_MECHANISM`-shaped rows (§3) do carry a `vpa:` handle in practice; expected
  to be rare-to-none but not directly measured.

## 13. Relationship to the existing categorization waterfall

No existing waterfall step changes behavior. `CategorizationService.suggest()` gains two new steps
(shared corpus, AI fallback) inserted at the position in §9; every step before them is untouched.
