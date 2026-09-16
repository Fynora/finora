# AI-Created Categories Design

## 1. Motivation

The Fyn categorization fallback (spec `2026-09-15-shared-merchant-corpus-design.md` §8) asks
an AI model for a category name only when the rule engine, the user's own learned history,
keyword matching, and the shared merchant corpus have all missed. Today that name is handed
straight to `CategorizationService.resolveOrCreateCategory`, the same generic method every
other suggestion source uses — which means the AI can *already* create a brand-new category
for a user, as an unintended side effect: `resolveOrCreateCategory` doesn't care where a name
came from, so if the AI's free-text guess doesn't match an existing category (case-insensitive
exact match), it's silently created, indistinguishable in the database from one the user typed
themselves.

This design makes that deliberate instead of accidental, and fixes the real problem the
current shape has: **the AI's answer is cached per merchant, shared across every user, but a
category name is inherently a per-user fact** (two different users can reasonably want the
same pet-store transaction filed under two differently-named categories they each already
have). Reusing one cached category name across users is wrong regardless of the cache
technology.

## 2. Scope

- Make AI-created categories visible: a new nullable field on `Category` records *why* it was
  created, shown in web, mobile, and the admin portal.
- Split the AI's job into two independently-cacheable steps: understanding what a merchant is
  (global, cacheable across users) and choosing which of *this* user's categories it maps to,
  or what a new one should be called (per-user, cacheable per user).
- Category deletion, concurrent resolution requests, and manual correction all need to interact
  correctly with the new per-user resolution cache — covered below, reusing existing patterns
  rather than inventing new ones.

Out of scope: any change to the shared merchant corpus's own trust-tier logic (§5-§7 of the
corpus spec) — that system's suggestions are still a single shared answer once Trusted, by
design; personalization is specific to the AI-fallback tier, which only ever fires when the
corpus has nothing.

## 3. Data model

### `categories` (existing table — one new column)

| column | type | notes |
|---|---|---|
| `ai_creation_reason` | text, nullable | Set only on the branch of `resolveOrCreateCategory` that actually inserts a new row. Never set when the name matched an existing category — matching isn't creating, so nothing gets tagged. Its presence *is* the "created by Fynora" tag; no separate boolean. |

### `merchant_understanding` (new — Tier 1, global)

| column | type | notes |
|---|---|---|
| `id` | UUID | |
| `counterparty_key` | text | Same key shape as the shared corpus (`CounterpartyTyping`). |
| `direction` | enum | `Transaction.Type` (INCOME/EXPENSE), same reuse as the corpus tables. |
| `understanding` | text | Free-text description from Claude, e.g. "Pet supplies retailer selling food, toys, and grooming products." Not a structured/enum type — see §4's "why free text" for why a closed taxonomy was rejected here as it was for the corpus's own eligibility gate. |
| `model` | text | Which Fyn model produced it, for audit — same field shape as `shared_merchant_category_ai_suggestion`. |
| `generated_at` | timestamp | |

`UNIQUE(counterparty_key, direction)`. Upserted, not versioned — see §9's deferred-items list
for why.

### `user_merchant_category_resolution` (new — Tier 2, per-user)

| column | type | notes |
|---|---|---|
| `id` | UUID | |
| `user_id` | UUID | |
| `counterparty_key` | text | |
| `direction` | enum | |
| `category_id` | UUID, FK → `categories.id` | Never the category *name* — see §6. |
| `resolved_at` | timestamp | |

`UNIQUE(user_id, counterparty_key, direction)` — this is what makes the concurrency story in
§7 work, and what `categories`' deletion-dependency hook (§6) looks up by.

## 4. AI integration: two-step resolution

Both steps replace the current single-shot `FynCategorizationFallbackService.suggest()` call
(spec `.../shared-merchant-corpus-design.md` §8), which asked for a bare category name via a
plain-text completion. Both steps use Anthropic tool-use (`LlmClient.LlmRequest.withTools`),
already built for Fyn's chat features — a schema-enforced `{category, reason}` /
`{understanding}` response instead of parsing free text out of a completion string.

**Step 1 — merchant understanding (Tier 1 cache miss only).** Same privacy shape as today: only
the raw transaction description is sent, never amount/account/user-identifying data. Runs once
per merchant, ever (barring a manual cache-busting op, not built for v1).

**Step 2 — per-user resolution (Tier 2 cache miss only).** Sent: the merchant's Tier-1
understanding, plus this user's own category names (capped at 100, ordered by name, to bound
prompt size — not expected to matter for any real user's category count). The model is
instructed to reply with the exact name of an existing category if one fits, or a new name plus
a short reason if none do. `resolveOrCreateCategory`'s existing exact-match-first lookup is what
actually decides "reuse vs. create" — if the model's reply matches an existing category exactly,
nothing new is created and no reason is attached, regardless of what the model said in its
`reason` field.

**Why free text for Tier 1, not a structured `merchantType` field.** A structured type only
gives deterministic, comparable values if it's backed by a real closed taxonomy — otherwise the
model will write different strings for similar merchants across different calls ("PET_SUPPLIES"
one day, "Pet Store" another), which is no more structured than free text, just wrapped in a
key. A real taxonomy was already rejected for the category-naming step itself (open-ended
naming was the explicit point of this feature); a `merchantType` enum for Tier 1 would
reintroduce that same commitment one layer down, for an analytics benefit ("easier to compare
later") that isn't a concrete need yet. Tier 1's only consumer is Tier 2's own LLM call, which
reads prose as prompt context — no separate parsing step needed either way, so free text costs
nothing extra here.

**Cost.** Worst case (brand-new merchant, brand-new user): 2 short LLM calls. Every other user's
first transaction from an already-understood merchant: 1 call (Tier 2 only). Any user's second+
transaction from a merchant they've already resolved: 0 calls. This is a real, deliberate
increase from a purely-global cache (which would be 1 call ever, shared by everyone) — accepted
because a purely-global cache is the exact bug this design fixes: it silently gave every user
after the first whatever category name the first user's context happened to produce.

**Failure handling (both steps): non-fatal, no placeholder rows.** Tier 1 and Tier 2 calls will
occasionally fail — timeout, rate limit, malformed tool response, model unavailable — same as
any `LlmClient.complete()` call today. `FynCategorizationFallbackService`'s existing behavior on
a thrown exception already matches what this needs: catch it, write the failure to
`ai_audit_log`, return no answer. Neither step writes a row on failure — no placeholder
`merchant_understanding` or `user_merchant_category_resolution` row is ever created for "we
tried and got nothing," specifically so a later transaction can retry cleanly rather than being
permanently short-circuited by a cached failure. On any failure, categorization falls through to
the rest of the existing waterfall exactly as if the shared corpus and AI fallback had never
existed: user rules → global rules → user learning → shared corpus → keyword rules → structural
P2P → Other. This keeps import behavior deterministic during an AI outage. Retries for the same
merchant/user+merchant are subject to the same `FynCostGovernanceService`/`FynAvailabilityGuard`
controls as any other Fyn call — no special-cased retry logic.

## 5. Waterfall placement

Unchanged from the corpus spec: AI fallback still only runs after the shared corpus has no
Trusted answer. Within the AI-fallback step itself, `CategorizationService` now calls a
resolution service that checks Tier 2 first (per-user, zero-cost on a hit), falls to Tier 1 +
a fresh Tier 2 call on a Tier-2 miss, and falls further to a fresh Tier 1 call only on a Tier-1
miss too.

## 6. Category deletion: participates in the existing dependency-reassignment lifecycle

`CategoryService.delete()` already requires a `reassignTo` target whenever a category has
dependents — transactions, a budget, rules, or merchant-learning rows — and repoints each
dependent type via its own handler (e.g. `merchantLearningService.onCategoryDeleted`, called
unconditionally, independent of the `hasDependents` transaction/budget/rule check). This is a
proven pattern for exactly this problem, already covering four dependent kinds; a runtime
"does this category still exist" check at resolution-lookup time (the originally-proposed fix)
would have been new, reactive logic solving a problem this codebase already solves proactively
elsewhere.

`user_merchant_category_resolution` becomes a fifth dependent:

- Its row count folds into `hasDependents` alongside `learningRowCount`, so deleting a category
  a resolution points at still requires picking a `reassignTo`.
- A new handler, mirroring `onCategoryDeleted`'s shape, repoints (`UPDATE ... SET category_id =
  :reassignTo`) every resolution row pointing at the deleted category — called unconditionally,
  same as the learning-row handler, since a resolution can outlive the category's own
  transactions the same way learning rows can.

Storing `category_id` rather than a category *name* (per §3) is what makes this repointing
possible without the resolution ever going stale on a rename.

**Category rename requires no special handling.** `user_merchant_category_resolution` stores
`category_id`, not a category name. Renaming a category updates the category record itself;
every existing resolution row continues to point at the same category automatically. No Tier 2
recomputation occurs on rename.

## 7. Concurrency

The import-confirm loop (`ImportService`) processes rows with a plain sequential `for` loop —
no parallelism within a single import, so the real race is only across genuinely separate
concurrent requests (two API calls landing at the same instant for the same user+merchant),
which is rare.

`UNIQUE(user_id, counterparty_key, direction)` on `user_merchant_category_resolution` plus
`INSERT ... ON CONFLICT DO NOTHING` is the guard, reusing the exact pattern already shipped for
`shared_merchant_category_ai_suggestion`'s upsert (`RegisteredLayoutRepository`-style
`REQUIRES_NEW` transaction) rather than adding Redis-based distributed locking. The dangerous
outcome — two *resolution rows* disagreeing about a user's category for the same merchant — is
what the unique constraint eliminates outright. The remaining possible outcome of a true
simultaneous race is a rare cost leak (two Claude calls, one resolution row wins) and, in the
narrower sub-case where both requests independently create different new categories before
either resolution row is written, an occasionally unused category record — not orphaned in the
referential-integrity sense (it's still a valid category the user could use), just one that
never ends up pointed at by a resolution. Accepted as a v1 tradeoff given how narrow the window
is, not solved with new locking infrastructure for it.

## 8. Human override always wins

Every existing manual-correction call site (`TransactionService.updateCategory` and its
siblings) already calls `sharedCorpusService.recordObservation(...)` unconditionally on a
manual category change (see the corpus spec). The same call sites now also upsert
`user_merchant_category_resolution` for that user+counterparty+direction to point at the
corrected category — pinning it immediately, so no further AI call happens for that
user+merchant until something changes the pin again.

**Manual correction is always treated as the latest truth, not a one-time pin.** Every manual
category change for a transaction whose counterparty_key and direction are known upserts the
resolution row again, unconditionally — there's no "first correction wins forever" behavior.
If a user resolves a merchant to Pet Care, later changes their mind to Pets, and later still to
Shopping, each change simply overwrites the resolution row; the most recent manual correction
always wins. The row can also still be repointed indirectly by deleting the category it points
at (§6).

## 9. Explicitly deferred

- **`categorySetVersion` invalidation** (re-running Tier 2 when the user's category set changes
  after a resolution was cached). Not built: AI-fallback suggestions are already low-confidence
  by design and typically flagged for review; a manual correction (§8) permanently overrides a
  stale resolution the first time it actually matters; the existing AI-suggestion cache
  (`shared_merchant_category_ai_suggestion`) doesn't version its output either. The failure mode
  is categorization *quality* — an occasionally suboptimal match — not correctness, security, or
  data integrity.
- **Merchant-understanding versioning/propagation** (invalidating Tier 2 resolutions when Tier
  1's understanding of a merchant changes). Not built: merchant-type changes are expected to be
  rare, human correction self-heals the specific user+merchant pairs that are actually affected,
  and — again — the existing shared AI-suggestion cache already uses plain upsert semantics
  rather than historical version tracking for the same class of data.
- **Fixed merchant-type taxonomy** (§4) and **embedding-based similarity matching** — both
  rejected in favor of free-text Tier 1 + LLM-driven Tier 2 matching; see §4.
- **Redis for resolution storage** — everything in this design lives in Postgres, consistent
  with the corpus tables and the existing AI-suggestion cache; Redis wasn't needed as storage,
  only briefly considered (and rejected) as a distributed lock in §7.
- **A "remove this mapping" UI** for human-overridden resolutions — the only way to clear one in
  v1 is deleting the category it points at (§6), which already repoints it.

## 10. Observability / UI

- **Web** (`CategoryCombobox.tsx`, `CategoryCreateEditPanel.tsx`) and **Mobile**
  (`CategoryPickerModal.tsx`, `CategoryEditSheet.tsx`, `SettingsCategorizationScreen.tsx`): a
  small badge on any category with a non-null `aiCreationReason`, reason shown on tap/hover.
  Category names elsewhere in the app (transaction rows, budgets, insights) are untouched — this
  is a category-*management*-surface concern only.
- **Admin portal**: new `CategoriesSection.tsx` alongside `LearningSection.tsx`/`RulesSection.tsx`
  in `UserDetail`'s `user-detail/` folder (no category view exists there today) — lists a user's
  Fynora-created categories with their reasons.
- **API contract**: category endpoints gain one new optional field, `aiCreationReason: string |
  null`. Regenerated through the existing OpenAPI pipeline into web/mobile/admin generated
  types — the same mechanical step this repo already takes for any API shape change.
