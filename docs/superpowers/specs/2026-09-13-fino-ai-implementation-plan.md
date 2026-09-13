# Fyn — Implementation Plan

**Naming note (2026-09-13):** the user/product-facing assistant is named **Fyn**, not "Fino" —
Sid's call, made after this plan was first drafted. The existing `FeatureEntitlement.FINO_AI` enum
value and its Flyway migrations (`V99`, `V163`) are pre-existing code and are **not** renamed by
this plan (renaming a shipped enum/migration is its own decision — see §4.4). Everywhere below,
"Fyn" is the name shown to users; `FINO_AI` remains the internal entitlement key it's gated behind
unless/until a separate rename is decided.

**Status:** Plan only. No code in this doc has been written. Scope and sequencing below reflect
Sid's 2026-09-13 answers: build **chat Q&A + insights narration + import/parsing assist**, starting
**now, in parallel** with launch work.

**Supersedes nothing, builds on:** `docs/superpowers/specs/2026-09-09-n8n-automation-proposal.md`
(PR #1270). That doc evaluated n8n as an orchestrator and left five open questions (§8). This plan
answers the ones Sid has now decided (scope, timing) and makes an explicit architecture call on the
one it didn't decide (orchestration layer) — see §1.

## 0. Verified starting state (2026-09-13, this session)

- `FINO_AI` = `FeatureEntitlement.FINO_AI` enum + `PremiumFeatureGate` component (web, mobile).
  Zero backing capability. No LLM client, no chat entity, no conversation table anywhere in the repo
  (`backend/src/main/java/com/finora`, `frontend/src`, `mobile/src`, `admin-portal/src`).
- No `ANTHROPIC_API_KEY` or any LLM provider key in `application.yml`.
- `AnalyticsService` (top merchants, category/spend trends) is real, live, but wired only to
  `AdminUserAnalyticsController` — no customer-facing controller exists yet.
- Held Statement Review (`HeldStatementService`, state machine `HELD → ASSIGNED → INVESTIGATING →
  READY_FOR_IMPORT → {IMPORTED|REJECTED}`) is real; `rerunParser` is a live re-parse, not automated
  diagnosis; root cause is a free-text field an engineer fills by hand.
- No Fyn pricing/entitlement scope exists — what a Premium user actually gets is undefined.

## 1. Architecture decision: skip n8n, build native in the Spring Boot backend

The n8n proposal's biggest blocker (§2.3 of that doc) was **no service-to-service auth pattern** —
n8n as an external orchestrator would need a new scoped-token mechanism to call Finora's backend on
a user's behalf. That whole problem disappears if the chat/insight/assist logic lives **inside** the
existing backend, in the same request as the user's own JWT:

```
Client → ChatController (existing @CurrentUser JWT auth, no new authz surface)
  → ChatOrchestrationService
      → AnthropicClient (new: wraps Claude Messages API + tool-use)
      → existing services in-process (BudgetService, AnalyticsService, AccountService, ...)
  → response persisted to ChatMessage, returned to client
```

Trade-off, stated plainly: this couples AI logic into the monolith rather than isolating it in a
separate orchestrator. Given this codebase's own operational-surface concerns (no n8n owner, no
extra hosting/patching burden, matches `hikaricp-bottleneck-and-railway-ceiling`'s existing Railway
constraints), that trade-off is worth it — one fewer system to run, patch, and secure, and it
removes the service-auth problem entirely rather than solving it. **This is the one open
architecture question from the n8n doc's §8.5 that this plan resolves; flag if you disagree before
Phase 1 starts.**

## 2. The PII trust-boundary decision (n8n doc §5) — default taken, needs confirmation

Not re-litigating §5's three options — restating the choice this plan defaults to, since it drives
every prompt design below:

**Default: aggregate-and-compose, never raw.** Claude is sent computed aggregates (a balance
figure, a category total, a budget-vs-actual delta, a parser's expected-vs-found field shape) —
**never** raw transaction narrations, counterparty names, UPI IDs, or full account numbers. For
chat Q&A this means: the backend resolves the tool call to real numbers first (e.g.
`getSpendByCategory(userId, "dining", "2026-08")` → `₹4,200`), and only that resolved aggregate goes
into the prompt Claude composes a sentence around. Claude never sees the transaction list.

This is stricter than strictly necessary for insights narration (aggregates only, low risk) but
exactly as strict as idea 1 needed in the original doc (`§5` option 1's "hardest case").

**This is a default, not a decision made on Sid's behalf** — it ships behind `ANTHROPIC_API_KEY`
being unset (no-op, per this repo's existing blank-default secrets pattern) until explicitly
confirmed, and Phase 1–2 work (foundation, import assist) can proceed without sending any real
customer data at all, since dev/prod today has exactly one real user (Sid, per the project plan's
own "unpopulated deployment" framing). Confirm before Phase 4 (chat) goes live with real accounts.

## 3. Phasing (lowest-risk first, even though timing is "now, in parallel")

Risk-ordered, not date-ordered — each phase is independently shippable and gated by its own
entitlement check, so nothing here blocks launch-critical work, and if only some ship, the lowest-
risk ones ship first.

### Phase 1 — Foundation (prerequisite for all three features)

- `AnthropicClient` service: wraps Claude Messages API (model, retries, timeout, token/cost
  logging). New config key `ANTHROPIC_API_KEY` (blank-default, same pattern as
  `RESEND_API_KEY`/`TWO_FACTOR_API_KEY` in `application.yml`) — no-op until Sid provisions the key.
- Flyway migration: `chat_conversations`, `chat_messages` tables (id, user_id, role, content,
  tool_calls_json, created_at) — needed for Phase 4 but scaffolded here.
- Per-user usage cap: a simple daily message/token ceiling (config-driven, conservative default),
  independent of pricing — this exists purely to bound API cost before any pricing model is decided,
  not as a product feature.
- No customer-facing surface yet.

### Phase 2 — Import/parsing assist (lowest risk: internal, admin-gated, no new PII exposure)

Reuses Held Statement Review almost entirely, per the n8n doc's §4.2 finding that idea 2 needs
little new surface:

- New endpoint `POST /api/v1/admin/held-statements/{heldId}/suggest-diagnosis` (gated
  `TRUST_REVIEW_MANAGE`, same class as existing controller).
- Context sent to Claude: `parserVersion`, `holdReasonCategories`, `reliabilityStatus`, and a
  **structural** diff (what the parser's field extractors expected vs. what they found — field
  names and shapes, not values) — never raw statement bytes or narrations, consistent with §2's
  default.
- Output written to the existing `notes`/`findings` field via the existing
  `recordFindings`/notes endpoints — no new persistence needed.
- Surfaced as a "Suggest diagnosis" button in `HeldStatementDetail.tsx`, admin-only.
- No entitlement gate needed (internal ops tool, not user-facing).

### Phase 3 — Insights narration (customer-facing, aggregate-only data)

- New customer-facing controller wrapping `AnalyticsService` read paths (the service already
  computes top merchants, category/spend trends — currently admin-only per the packaging audit).
  This itself is useful gated on `ADVANCED_REPORTS`/Plus tier independent of AI; the AI layer adds a
  narrated summary on top.
- `InsightsNarrationService`: takes the already-computed aggregate DTOs (no raw transactions) and
  asks Claude to produce a short natural-language summary ("You spent 18% more on dining this month,
  mostly concentrated in the first two weeks").
- Gated on `FINO_AI` entitlement (Fyn's underlying key — see naming note at top).
- Failure mode: if Claude call fails/times out, fall back to showing the raw numeric summary with no
  narration — never block the existing numeric insights page on the AI call.

### Phase 4 — Chat Q&A (highest risk: conversational, real-time, biggest surface)

- `ChatController` (`POST /api/v1/fyn/chat`, JWT-scoped like every other user endpoint).
- Tool-calling contract, minimum viable set: `getBalance`, `getRecentTransactionsSummary`
  (aggregated — count + total per category, not line items), `getSpendByCategory`,
  `getBudgetStatus`. Each tool wraps an **existing** service call with the request's own
  `@CurrentUser` — no new authz surface, per §1.
- Conversation persisted to `chat_conversations`/`chat_messages` (Phase 1 schema).
- Gated on `FINO_AI` entitlement (Fyn's underlying key); requires the pricing/scope decision below
  before general availability.
- Chat UI surface: new panel/screen on web + mobile, branded as **Fyn** (none exists today —
  `PremiumFeatureGate` is gate plumbing only, no chat UI consumes it).

## 4. Open decisions that block specific phases (not all of them)

None of these block Phase 1 or Phase 2 starting today.

1. **Fyn pricing/scope** (blocks Phase 4 GA, not Phase 4 dev): what exactly is included —
   unlimited messages, a monthly cap, which tools. Product decision, not engineering's to make.
2. **PII default in §2** (blocks Phase 4 going live with real data): confirm aggregate-and-compose,
   or pick one of the n8n doc's other two options (enterprise agreement, synthetic-only).
3. **Anthropic API key provisioning**: Sid needs to create the key and set `ANTHROPIC_API_KEY` in
   Railway env — engineering can build and test Phase 1–3 against it once available; until then,
   development proceeds with the client wired but calls mocked/stubbed in tests.
4. **Whether to rename the `FINO_AI` enum/migration itself to match Fyn**: not done by this plan.
   The key is already shipped (`V99`, `V163`) and live in `PremiumFeatureGate` on web + mobile;
   renaming it is a pure internal-naming cleanup with no user-visible effect either way (users only
   ever see whatever string the UI renders, which this plan already has as "Fyn" in Phase 3/4's
   UI surfaces regardless of the enum's name). Low priority, does not block any phase — flag if you
   want it done for code-hygiene reasons.

## 5. Explicitly out of scope (this plan)

- No n8n or any external workflow-automation tool (see §1).
- No "auto-fix" via headless Claude Code editing the parser (named, not designed, in the n8n doc's
  §4.3 — a materially different, larger piece of infrastructure than anything here).
- No mobile/web billing changes beyond the existing `FINO_AI` entitlement key.
- No rename of the `FINO_AI` enum/migration to match the "Fyn" product name (see §4.4).
- No change to the v1.0 launch-gate scope or the existing post-launch premium sequencing decision
  ([[personal-cfo-premium-layer-sequencing]]) — this plan runs in parallel per Sid's explicit choice,
  it does not reprioritize launch-blocking work.

## 6. Suggested build order within "now, in parallel"

Phase 1 → Phase 2 → Phase 3 → Phase 4, each as its own worktree/PR, each independently mergeable
and entitlement-gated so a partial landing is safe. Phase 2 first because it has the smallest surface
and zero new customer-facing risk; Phase 4 last because it's the largest and most PII-sensitive.
