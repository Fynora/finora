# Fyn — Implementation Plan

**Naming note (2026-09-13):** the user/product-facing assistant is named **Fyn**, not "Fino" —
Sid's call, made after this plan was first drafted. The existing `FeatureEntitlement.FINO_AI` enum
value and its Flyway migrations (`V99`, `V163`) are pre-existing code and are **not** renamed by
this plan (renaming a shipped enum/migration is its own decision — see §7.4). Everywhere below,
"Fyn" is the name shown to users; `FINO_AI` remains the internal entitlement key it's gated behind
unless/until a separate rename is decided.

**Status:** Plan only. No code in this doc has been written. Scope and sequencing below reflect
Sid's 2026-09-13 answers: build **chat Q&A + insights narration + import/parsing assist**, starting
**now, in parallel** with launch work. Revised 2026-09-13 to incorporate an external architecture
review — see §10 for what was accepted, merged, deferred, or rejected and why.

**Supersedes nothing, builds on:** `docs/superpowers/specs/2026-09-09-n8n-automation-proposal.md`
(PR #1270). That doc evaluated n8n as an orchestrator and left five open questions (§8). This plan
answers the ones Sid has now decided (scope, timing) and makes an explicit architecture call on the
one it didn't decide (orchestration layer) — see §2.

## 0. Verified starting state (2026-09-13, this session)

- `FINO_AI` = `FeatureEntitlement.FINO_AI` enum + `PremiumFeatureGate` component (web, mobile).
  Zero backing capability. No LLM client, no chat entity, no conversation table anywhere in the repo
  (`backend/src/main/java/com/finora`, `frontend/src`, `mobile/src`, `admin-portal/src`).
- No `ANTHROPIC_API_KEY` or any LLM provider key in `application.yml`. **Note:** a Claude Max
  *subscription* (claude.ai/Claude Code) does not provide this — API billing is a wholly separate
  system (console.anthropic.com, prepaid credits/pay-as-you-go). Sid confirmed this needs its own
  setup.
- `AnalyticsService` (top merchants, category/spend trends) is real, live, but wired only to
  `AdminUserAnalyticsController` — no customer-facing controller exists yet.
- Held Statement Review (`HeldStatementService`, state machine `HELD → ASSIGNED → INVESTIGATING →
  READY_FOR_IMPORT → {IMPORTED|REJECTED}`) is real; `rerunParser` is a live re-parse, not automated
  diagnosis; root cause is a free-text field an engineer fills by hand.
- No Fyn pricing/entitlement scope exists — what a Premium user actually gets is undefined.

## 1. Product contract for Fyn

Added per the external review (§10, item 1) — the original draft described capabilities without
saying what Fyn *is*. This drives system prompts, tool permissions, and compliance review below.

**Fyn is:**
- A personal-finance copilot over the user's own Finora data.
- A spending-analysis assistant (Phase 4 chat).
- A financial-insights narrator (Phase 3).
- An import-troubleshooting assistant (Phase 2, admin-only).

**Fyn is not, and must never present itself as:**
- A financial advisor, investment advisor, tax advisor, or insurance/loan recommendation engine.
- A source of advice to buy, sell, or reallocate anything — it narrates and explains the user's own
  historical data, it does not recommend future financial action.

**How this is enforced, not just stated:** the system prompt for every Fyn surface (Phase 3
narration, Phase 4 chat) carries an explicit instruction to decline advice requests and redirect to
"talk to a licensed advisor," and the tool registry (§4.1) never exposes a tool that could be used
to construct advice (no "should I invest in X" tool exists or is planned). This boundary is a
prerequisite for Phase 3/4 shipping, not a nice-to-have — Finora already avoids "personalized
investment advice" as a stated non-goal elsewhere in this product (see the assistant's own
standing action-category rules), and Fyn must not become the backdoor around that.

## 2. Architecture decision: skip n8n, build native in the Spring Boot backend

The n8n proposal's biggest blocker (§2.3 of that doc) was **no service-to-service auth pattern** —
n8n as an external orchestrator would need a new scoped-token mechanism to call Finora's backend on
a user's behalf. That whole problem disappears if the chat/insight/assist logic lives **inside** the
existing backend, in the same request as the user's own JWT:

```
Client → ChatController (existing @CurrentUser JWT auth, no new authz surface)
  → ChatOrchestrationService
      → ToolRegistry → Tool implementations (wrap existing services, §4.1)
      → Financial Facts Layer (resolves tool calls to real numbers, §4.2)
      → AnthropicClient (wraps Claude Messages API + tool-use)
  → response persisted to ChatMessage + AiAuditLog (§4.3), returned to client
```

Trade-off, stated plainly: this couples AI logic into the monolith rather than isolating it in a
separate orchestrator. Given this codebase's own operational-surface concerns (no n8n owner, no
extra hosting/patching burden, matches `hikaricp-bottleneck-and-railway-ceiling`'s existing Railway
constraints), that trade-off is worth it — one fewer system to run, patch, and secure, and it
removes the service-auth problem entirely rather than solving it.

**Provider abstraction — explicitly not building one.** The external review (§10, item 11)
suggested an `AIProvider` interface with `AnthropicProvider`/`OpenAIProvider`/`GeminiProvider`
implementations, only one active. Rejected: there is no pending decision to use a second provider —
Sid confirmed staying on Anthropic (cost comparison showed provider price differences are
cents-per-user-per-month, not worth the switch). Building unused provider implementations is the
premature abstraction this repo's own engineering rules warn against. `AnthropicClient` stays a
concrete class; if a real second-provider need appears later, extract the interface then, against an
actual second caller, not speculatively now.

## 3. The PII trust-boundary decision (n8n doc §5) — default taken, needs confirmation

Not re-litigating §5's three options — restating the choice this plan defaults to, since it drives
every prompt design below:

**Default: aggregate-and-compose, never raw.** Claude is sent computed aggregates (a balance
figure, a category total, a budget-vs-actual delta, a parser's expected-vs-found field shape) —
**never** raw transaction narrations, counterparty names, UPI IDs, or full account numbers. For
chat Q&A this means: the backend resolves the tool call to real numbers first (e.g.
`getSpendByCategory(userId, "dining", "2026-08")` → `₹4,200`), and only that resolved aggregate goes
into the prompt Claude composes a sentence around. Claude never sees the transaction list. This is
now a named architectural component, not just a design rule — see §4.2, Financial Facts Layer.

This is stricter than strictly necessary for insights narration (aggregates only, low risk) but
exactly as strict as idea 1 needed in the original doc (`§5` option 1's "hardest case").

**This is a default, not a decision made on Sid's behalf** — it ships behind `ANTHROPIC_API_KEY`
being unset (no-op, per this repo's existing blank-default secrets pattern) until explicitly
confirmed, and Phase 1–2 work (foundation, import assist) can proceed without sending any real
customer data at all, since dev/prod today has exactly one real user (Sid, per the project plan's
own "unpopulated deployment" framing). Confirm before Phase 4 (chat) goes live with real accounts.

**Provider retention terms, for context, not as the primary control** (this plan's primary control
is redaction via the Financial Facts Layer, not vendor promises): Anthropic and OpenAI both exclude
commercial/API data from training by default; OpenAI additionally offers a named Zero Data Retention
enterprise arrangement; Google's Gemini **free tier trains on your data — never route anything
through it**, its paid tier does not. None of this changes §4.2's design — Fyn should not depend on
a vendor's retention promise to keep raw financial data safe, it should simply never send it.

## 4. Supporting architecture components

Consolidates the external review's items 2, 5, 6, 9 into three concrete pieces (not five separate
systems — data classification folds into the tool registry, and version tracking folds into the
audit log, per §10).

### 4.1 Tool Registry

Every tool `ChatOrchestrationService` can call declares, in one place:

```
name                 e.g. GET_BALANCE
description
requiredEntitlement  e.g. FYN_CHAT (§5)
maxDataTier          0-4, see below — the ceiling on what this tool is allowed to return
auditEnabled         boolean, true for all financial tools
```

**Data tiers** (replaces the review's separate classification system — same idea, one field):

| Tier | Contents | Fyn tools allowed here |
|---|---|---|
| 0 | Public metadata (categories list, currency) | Any |
| 1 | Aggregates — category totals, budget status, balance summaries | `getBalance`, `getSpendByCategory`, `getBudgetStatus`, `getRecentTransactionsSummary` — **the large majority of Fyn's tools live here** |
| 2 | Merchant names | None planned in Phase 1-4 |
| 3 | Transaction descriptions/narrations | None — never exposed to Claude per §3 |
| 4 | Account numbers, UPI IDs, reference numbers | None — never exposed to Claude per §3 |

A tool's `maxDataTier` is enforced in code (the tool's return type literally cannot carry Tier 2+
fields), not just documented — this is what makes §3's redaction rule structural rather than a
convention someone can forget.

### 4.2 Financial Facts Layer

Names what §3 already required: a layer between existing services and Claude that resolves tool
calls to concrete, already-computed values before anything reaches the model.

```
Financial Services (BudgetService, AnalyticsService, AccountService, ...)
    ↓
Financial Facts Layer  — resolves a tool call to a real number/fact
    ↓
Claude  — converts a verified fact into natural language, never derives or calculates one
    ↓
Fyn response
```

Claude's job is phrasing, never arithmetic. `{"monthly_spend": 52340, "monthly_change_percent":
12.4, "top_category": "Dining", "budget_status": "Exceeded"}` goes in; "You spent 12% more this
month, mostly on dining, and you're over budget" comes out. This is what makes Fyn's numeric
answers deterministic and testable independent of the LLM — the number was never something the LLM
computed, only phrased.

### 4.3 AI audit log

One table, covering both the review's audit-log ask (item 3) and its version-tracking ask (item 9)
— no separate systems:

```
ai_audit_log
  id, user_id, conversation_id
  model, prompt_version, temperature
  tool_name, tool_inputs, tool_outputs
  tokens_in, tokens_out, cost, latency_ms
  error
  created_at
```

Written on every Fyn call (Phase 2's suggest-diagnosis included), independent of whether the
conversation itself is persisted (Phase 1's `chat_conversations`/`chat_messages` cover Phase 4's
turn history; this table covers cost/debugging/reproducibility for every phase). Populated from
day one of Phase 1, not bolted on later — this is the only way §7's evaluation framework has real
data to compare against.

### 4.4 Cost governance

Phase 1 already specified a per-user daily message/token ceiling. Adding, per the review's item 8:
a **monthly AI budget** with alert thresholds (70% warning, 90% warning, 100% stop-and-alert),
computed from `ai_audit_log`'s `cost` column. Cheap to add on top of the same table, protects
against prompt loops, abuse, or a misconfigured deployment burning spend unnoticed between manual
checks.

### 4.5 Deferred, not blocking: response caching

The review's item 7 (cache repeated questions like "how much did I spend this month," ~15 min TTL)
is a real cost/latency optimization but not a design requirement — nothing in Phases 1-4 depends on
it. Deferred to a post-launch tuning pass once real usage data (from §4.3's audit log) shows which
questions actually repeat enough to be worth caching.

## 5. Entitlements: split, not one key

Per the review's item 4. The existing shipped `FINO_AI` migration (`V99`) is **not modified** —
Flyway migrations aren't edited after shipping, per this repo's own rule. Instead, a new migration
adds three feature-specific keys used by Phases 2-4 going forward:

```
FYN_CHAT           — Phase 4, chat Q&A
FYN_INSIGHTS       — Phase 3, insights narration
FYN_IMPORT_ASSIST  — Phase 2 — actually ungated (internal ops tool, see Phase 2 below);
                     key exists for future use if this ever becomes user-facing
```

This enables independent rollout, beta access, and separate pricing per feature instead of one
all-or-nothing Premium flag — directly useful given Phase 2-4 already ship independently per §6.
The pricing/scope question (which of these a given tier actually includes) is still §7's open
decision 1, unchanged by the split — the split just makes that decision implementable per-feature
instead of forcing one answer for all three.

## 6. Phasing (lowest-risk first, even though timing is "now, in parallel")

Risk-ordered, not date-ordered — each phase is independently shippable and gated by its own
entitlement check, so nothing here blocks launch-critical work, and if only some ship, the lowest-
risk ones ship first.

### Phase 1 — Foundation (prerequisite for all three features)

- `AnthropicClient` service: wraps Claude Messages API (model, retries, timeout). Model: **Haiku
  4.5** ($1/$5 per MTok input/output) — cheapest Claude tier, still tool-use capable; no reason for
  Fyn to run on Sonnet/Opus given the aggregate-only, short-prompt design. New config key
  `ANTHROPIC_API_KEY` (blank-default, same pattern as `RESEND_API_KEY`/`TWO_FACTOR_API_KEY` in
  `application.yml`) — no-op until Sid provisions a console.anthropic.com API key (separate from any
  Max subscription).
- `ai_audit_log` table (§4.3) and `chat_conversations`/`chat_messages` tables (Flyway migration).
- Tool Registry scaffolding (§4.1) — empty at first, populated as each phase adds tools.
- Per-user daily usage cap + monthly AI budget with alert thresholds (§4.4).
- New entitlement keys (§5) via their own migration.
- No customer-facing surface yet.

### Phase 2 — Import/parsing assist (lowest risk: internal, admin-gated, no new PII exposure)

Reuses Held Statement Review almost entirely, per the n8n doc's §4.2 finding that idea 2 needs
little new surface:

- New endpoint `POST /api/v1/admin/held-statements/{heldId}/suggest-diagnosis` (gated
  `TRUST_REVIEW_MANAGE`, same class as existing controller).
- Context sent to Claude: `parserVersion`, `holdReasonCategories`, `reliabilityStatus`, and a
  **structural** diff (what the parser's field extractors expected vs. what they found — field
  names and shapes, not values) — Tier 0/1 only per §4.1, never raw statement bytes or narrations.
- Registered in the Tool Registry as a Tier-1, audited tool even though it's admin-only — same
  governance path as customer-facing tools, no special-casing.
- Output written to the existing `notes`/`findings` field via the existing
  `recordFindings`/notes endpoints — no new persistence needed.
- Surfaced as a "Suggest diagnosis" button in `HeldStatementDetail.tsx`, admin-only.
- Not gated on `FYN_IMPORT_ASSIST` for now (internal ops tool, not user-facing) — the key exists for
  if this ever becomes a customer-facing feature.

### Phase 3 — Insights narration (customer-facing, aggregate-only data)

- New customer-facing controller wrapping `AnalyticsService` read paths (the service already
  computes top merchants, category/spend trends — currently admin-only per the packaging audit).
  This itself is useful gated on `ADVANCED_REPORTS`/Plus tier independent of AI; the AI layer adds a
  narrated summary on top.
- `InsightsNarrationService`: implements the Financial Facts Layer pattern (§4.2) — passes only the
  already-computed aggregate DTOs (Tier 1) to Claude, which composes a short natural-language
  summary ("You spent 18% more on dining this month, mostly concentrated in the first two weeks").
- Gated on `FYN_INSIGHTS`.
- Failure mode: if Claude call fails/times out, fall back to showing the raw numeric summary with no
  narration — never block the existing numeric insights page on the AI call.

### Phase 4 — Chat Q&A (highest risk: conversational, real-time, biggest surface)

- `ChatController` (`POST /api/v1/fyn/chat`, JWT-scoped like every other user endpoint).
- Tool-calling contract, minimum viable set, all Tier 1 per §4.1: `getBalance`,
  `getRecentTransactionsSummary` (aggregated — count + total per category, not line items),
  `getSpendByCategory`, `getBudgetStatus`. Each tool wraps an **existing** service call with the
  request's own `@CurrentUser` — no new authz surface, per §2.
- Conversation persisted to `chat_conversations`/`chat_messages` (Phase 1 schema); every call also
  writes to `ai_audit_log` (§4.3).
- Gated on `FYN_CHAT`; requires the pricing/scope decision below before general availability.
- Chat UI surface: new panel/screen on web + mobile, branded as **Fyn** (none exists today —
  `PremiumFeatureGate` is gate plumbing only, no chat UI consumes it).
- System prompt enforces the product contract (§1): declines advice requests, only narrates the
  user's own historical data.
- **Hard gate before real users reach this phase: the evaluation framework, §7's item 5 below.**
  Not optional, not a nice-to-have — this repo's own standing rule is that a green test suite is
  never proof of correctness for financial output, and an LLM composing sentences about a user's
  real balance is exactly the kind of output that needs value-level verification, not "looks
  plausible."

## 7. Open decisions that block specific phases (not all of them)

None of these block Phase 1 or Phase 2 starting today.

1. **Fyn pricing/scope, per feature** (blocks Phase 3/4 GA, not dev): what exactly each of
   `FYN_CHAT`/`FYN_INSIGHTS` includes — unlimited messages, a monthly cap, which tools. Product
   decision, not engineering's to make.
2. **PII default in §3** (blocks Phase 4 going live with real data): confirm aggregate-and-compose,
   or pick one of the n8n doc's other two options (enterprise agreement, synthetic-only).
3. **Anthropic API key provisioning**: Sid needs to create a console.anthropic.com account (separate
   from the Max subscription) and set `ANTHROPIC_API_KEY` in Railway env — engineering can build and
   test Phase 1–3 against it once available; until then, development proceeds with the client wired
   but calls mocked/stubbed in tests.
4. **Whether to rename the `FINO_AI` enum/migration itself to match Fyn**: not done by this plan.
   The key is already shipped (`V99`, `V163`) and live in `PremiumFeatureGate` on web + mobile;
   renaming it is a pure internal-naming cleanup with no user-visible effect. Low priority, does not
   block any phase.
5. **Evaluation benchmark suite must exist and pass before Phase 4 reaches real users** (§6, Phase
   4's hard gate; review item 10): a maintained set of 100+ representative prompts ("How much did I
   spend on dining?", "What is my largest account balance?", "Did I exceed my grocery budget?",
   "How has spending changed month-over-month?") that every prompt or model change is evaluated
   against before deployment. This is engineering's to build, not a product decision, but it's
   listed here because it is a genuine go/no-go gate, not a suggestion.

## 8. Explicitly out of scope (this plan)

- No n8n or any external workflow-automation tool (see §2).
- No multi-provider `AIProvider` abstraction — rejected, see §2. `AnthropicClient` is concrete.
- No response caching layer — deferred, see §4.5.
- No "auto-fix" via headless Claude Code editing the parser (named, not designed, in the n8n doc's
  §4.3 — a materially different, larger piece of infrastructure than anything here).
- No mobile/web billing changes beyond the entitlement keys in §5.
- No rename of the `FINO_AI` enum/migration to match the "Fyn" product name (see §7.4).
- No change to the v1.0 launch-gate scope or the existing post-launch premium sequencing decision
  ([[personal-cfo-premium-layer-sequencing]]) — this plan runs in parallel per Sid's explicit choice,
  it does not reprioritize launch-blocking work.

## 9. Suggested build order within "now, in parallel"

Phase 1 → Phase 2 → Phase 3 → Phase 4, each as its own worktree/PR, each independently mergeable
and entitlement-gated so a partial landing is safe. Phase 2 first because it has the smallest
surface and zero new customer-facing risk; Phase 4 last because it's the largest, most
PII-sensitive, and the only one gated on the evaluation framework (§7.5).

## 10. External architecture review — disposition (2026-09-13)

Sid forwarded an 11-point external review of this plan. Recorded here for traceability rather than
silently folded in, since a few points were pushed back on rather than accepted as written.

| # | Review item | Disposition |
|---|---|---|
| 1 | Product contract | **Accepted** — §1 |
| 2 | Tool Registry | **Accepted, merged with #6** — §4.1 (one registry, data tier is a field on it, not a second system) |
| 3 | AI audit log | **Accepted, merged with #9** — §4.3 (one table, prompt/model version are columns on it, not a second system) |
| 4 | Split `FINO_AI` into per-feature keys | **Accepted** — §5 (new migration, existing key untouched) |
| 5 | Financial Facts Layer | **Accepted** — §4.2 (formalizes what §3 already required, not new scope) |
| 6 | Data classification tiers | **Accepted, merged into #2** — §4.1's tier table, enforced via `maxDataTier` |
| 7 | Response caching | **Accepted in principle, deferred** — §4.5, post-launch tuning, not a Phase 1-4 requirement |
| 8 | Monthly AI budget governance | **Accepted** — §4.4, added to Phase 1's existing daily cap |
| 9 | Prompt/model version tracking | **Accepted, merged into #3** — §4.3 |
| 10 | Evaluation framework before Phase 4 | **Accepted, elevated to a hard gate** — §6 Phase 4, §7 item 5. Strongest item in the review; matches this repo's own standing rule that green tests aren't proof of correctness for financial output. |
| 11 | Multi-provider `AIProvider` abstraction | **Rejected** — §2. No pending second-provider decision exists; this is the premature abstraction this repo's own engineering rules explicitly warn against. Revisit only against a real second caller. |
