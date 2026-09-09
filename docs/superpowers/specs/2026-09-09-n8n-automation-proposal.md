# n8n Workflow Automation — Two Proposals

**Status:** Proposal, not approved. Both ideas below are evaluated for feasibility and
sequencing, not committed to build. Nothing described here has been implemented; no n8n
instance exists in this repository or in Finora's infrastructure today.

## 1. Objective

Sid asked, after being shown [n8n](https://github.com/n8n-io/n8n) (a node-based,
self-hostable workflow-automation tool), whether it could be used for two things:

1. Building the `FINO_AI` chatbot.
2. Automating the failed-import → admin-review → diagnosis flow that today runs through the
   Held Statement Review system.

This document verifies the current state of both target systems against the actual
codebase (not the verbal sketch that prompted it), states what backend surface each idea
would need, and gives a recommendation on scope and sequencing.

## 2. Current state, verified against this codebase

### 2.1 `FINO_AI` — zero code exists

```
grep -rli "fino_ai\|chatbot" backend/src/main frontend/src mobile/src admin-portal/src
```

returns only `FeatureEntitlement`, two Flyway migrations (`V99`, `V163`), and the
`PremiumFeatureGate` component/test files on web and mobile — all entitlement-gate
plumbing (`FeatureEntitlement.Feature.FINO_AI` exists as an enum value you can gate
behind), and nothing else. No chat endpoint, no conversation model, no LLM client, no
prompt. `investment-insights-premium-gate-shipped` (PR #1103) deliberately left `FINO_AI`
ungated pending Sid's explicit choice — it is parked, not partially built.

`grep -rli "anthropic\|claude" backend/src/main` returns nothing. There is no existing
Anthropic/Claude API client anywhere in the backend to build on or copy from.

### 2.2 Held Statement Review — real, working, more specific than the verbal sketch

Verified by reading `HeldStatement.java`, `HeldStatementService.java` (641 lines),
`AdminHeldStatementController.java`, and the admin-portal pages, not from memory summaries.

**State machine** (`HeldStatement.Status`, `backend/src/main/java/com/finora/entity/HeldStatement.java:42-49`):

```
HELD → ASSIGNED → INVESTIGATING → READY_FOR_IMPORT → { IMPORTED | REJECTED }
```

`IMPORTED` and `REJECTED` are `RESOLVED` and terminal — every mutating method
(`assign`, `startInvestigation`, `markReadyForImport`, `markImported`, `reject`) calls
`refuseIfResolved()` first and throws `IllegalStateException` on a second attempt.
`HELD → IMPORTED` directly is a legal transition ("not every hold needs an engineer" —
`HeldStatement.java:209-211`); an engineer is not mandatory in the flow.

**What triggers a hold**: `HeldStatementService.openHold` snapshots `parserVersion`,
`reliabilityStatus`, `textSource`, `headerReconstructionUncertain`, `bankName`, and
`holdReasonCategories` at hold time — a one-time snapshot, explicitly never re-derived
live (`HeldStatement.java:143-160`), because a later re-run under a different parser build
has to be comparable against what the original build actually saw.

**The engineer workflow's "root-cause" step is not automated diagnosis — it's a live
re-parse.** `rerunParser` (`HeldStatementService.java:404-457`) re-reads the statement
bytes, calls `importService.dryRunParse(...)` with the **current** parser build, and
re-evaluates `TrustPredicate.evaluate(...)` against the fresh result. If the current build
now clears the document, the hold transitions to `READY_FOR_IMPORT`; if not, it stays held
and records why. This writes an audit event and an `ApiResponse` diagnostic
(`HeldStatementRerunResultDto`: previous/current parser version, whether it changed,
whether the document still holds, and reasons) — but it never generates a root-cause
explanation or a code fix. Root cause is a free-text field an engineer fills in by hand
(`recordFindings(rootCause, fixReference)`, `HeldStatementService.java` — replaces
`rootCause`/`fixReference` wholesale, history lives in `held_statement_events`).

**Notification already exists and is intentionally minimal.**
`HeldItemAdminAlertService` (verified by reading the file) emails every admin holding
`TRUST_REVIEW_MANAGE` or `IMPORT_TRIAGE_MANAGE` the moment a hold opens — metadata and a
deep link only, **never statement content**, per
`docs/superpowers/specs/2026-09-05-held-item-admin-email-alerts-design.md` §3. This is the
precedent for how sensitive this system already treats even email as a channel.

**Admin surface**: `admin-portal/src/pages/HeldStatements.tsx` (list) and
`HeldStatementDetail.tsx` (detail — assign, investigate, notes, findings, rerun-parser,
approve, reject) are real, shipped pages, not a mockup.

**Access boundary**: `AdminHeldStatementController` is gated class-wide on
`TRUST_REVIEW_MANAGE`; the one endpoint that returns statement bytes
(`GET /{heldId}/document`) additionally requires `hasAnyRole('ADMIN', 'SUPER_ADMIN')`,
restated in one `@PreAuthorize` expression rather than layered — because a method-level
annotation *replaces* the class-level one in Spring Security rather than adding to it
(documented in the controller's own class doc, `AdminHeldStatementController.java:78-93`).
Browsing the list/detail is deliberately unaudited (no content); opening the document is
audited separately.

### 2.3 Backend API surface each idea would need

| Need | Exists today? | Evidence |
|---|---|---|
| Auth-scoped "get this user's balance / recent transactions" endpoint, callable by a *service* rather than the user's own session | **No.** `DashboardController`, `InsightsController`, `AnalyticsController` all resolve the caller from the request's own JWT (`CurrentUser`) — there is no service-account or scoped-token pattern to call them on a user's behalf from an external system. | grep across `backend/src/main/java/com/finora/controller` for user-facing balance/analytics endpoints |
| Any existing pattern for a service-to-service authenticated call (not a user JWT, not an inbound webhook signature) | **No, not for outbound.** The only comparable patterns are *inbound*, signature-verified webhooks (`RazorpayWebhookController` verifies `X-Razorpay-Signature` via `Utils.verifyWebhookSignature`; `RevenueCatSignatureVerifier` does the same shape). There is no outbound service-token issuance today. | `RazorpayWebhookController.java:1-40` |
| Programmatic read access to held-statement context (trigger reason, snapshot fields, notes) | **Yes**, already exposed to admins via `GET /api/v1/admin/held-statements/{heldId}` (`HeldStatementDetailDto`), gated on `TRUST_REVIEW_MANAGE`. | `AdminHeldStatementController.java:70-76` |
| A way to re-run diagnosis against the current parser build | **Yes** — `POST /{heldId}/rerun-parser`, described in §2.2 above. | `AdminHeldStatementController.java:145-151` |
| A field to persist an automated finding | **Yes, reused, not new** — `POST /{heldId}/notes` or `/{heldId}/findings` can carry whatever text a workflow produces; both already exist and already write to the audit trail via `held_statement_events`. | `AdminHeldStatementController.java:126-143` |
| Any existing outbound LLM API client (Anthropic or otherwise) | **No.** See §2.1. | grep, above |
| Secrets management pattern for a new external API key | **Yes, established** — `${RESEND_API_KEY:}` / `${TWO_FACTOR_API_KEY:}` pattern in `application.yml`, env-var-backed, blank-default (no-op in dev). A new `ANTHROPIC_API_KEY` would follow the same shape. | `backend/src/main/resources/application.yml:232-265` |

## 3. Idea 1 — `FINO_AI` chatbot on n8n

### 3.1 Can n8n build it?

n8n can be the **orchestrator**, not the brain — this matches the verbal sketch, and
nothing in the codebase contradicts it. A workable shape:

```
User message (mobile/web) → new backend endpoint → n8n webhook
    → n8n: Anthropic API node (Claude) with conversation state
    → optional tool-call back into Finora backend (balance lookup, transaction search)
      via NEW auth-scoped REST endpoints
    → n8n returns response → backend relays to client
```

This requires, all net-new:

- A conversation/session model (nothing like it exists — no chat entity, no message
  history table).
- New backend endpoints for whatever "tools" the assistant needs (balance, recent
  transactions, spend-by-category, at minimum) — and per §2.3, a way for n8n to call them
  that is **not** the user's own browser JWT, because n8n is a server-side intermediary,
  not the user's device. This is new authz surface, not a wrapper around what exists.
- An n8n instance: hosting, upgrade/patch ownership, secrets storage for both the
  Anthropic API key and whatever credential n8n uses to call Finora's backend.
- The mobile/web client change to add a chat surface at all — `FeatureEntitlement.FINO_AI`
  is gate plumbing only; no UI consumes it today.

### 3.2 What n8n does *not* solve

n8n does not remove the need to design the tool-calling contract (which backend
operations the assistant can invoke, with what scoping, and what it's blocked from), or
the entitlement/cost question (Claude API calls cost money per message; `FINO_AI` is
currently an unpriced, unspecified premium tier per
`fynora-packaging-audit-2026-09-06` — pricing this needs a product decision before any
usage-metered feature ships). It is a reasonable orchestration layer *if and when* the
product decision to build `FINO_AI` is made — it does not make that decision, or its
scoping work, go away.

## 4. Idea 2 — automate held-statement diagnosis with n8n

### 4.1 What's actually being proposed

```
n8n trigger on HeldStatement entering HELD (poll or a new webhook-on-create hook)
    → pull failure context via GET /api/v1/admin/held-statements/{heldId}
      (trigger_summary, hold_reason_categories, parser_version, reliability_status, etc.)
    → call Claude API with that context + the relevant parser source snippet
    → post the diagnosis to POST /{heldId}/notes (or a new "suggested fix" field)
      and/or to Slack/email
```

### 4.2 What this would actually add on top of what exists

Per §2.2 and §2.3, the held-statement system already has: the state machine, the
notification-on-hold email, the context-retrieval endpoint, the live re-parse
(`rerun-parser`), and a place to persist a written finding (`notes`/`findings`). What n8n
+ Claude would add is **narrowing the search space for an engineer** — a plausible-looking
first read of "here's probably why this parse failed and here's the likely code path" —
written into the existing `notes` field or a new column, surfaced in
`HeldStatementDetail.tsx` before the engineer opens the code themselves.

### 4.3 The load-bearing caveat: this is diagnosis, not a fix

A plain Claude API completion node in n8n can read context and *describe* a probable root
cause. It cannot edit the parser code, run the test suite against the real corpus, or open
a PR — those require an agentic coding tool with actual tool access (Claude Code invoked
headless, e.g. `claude -p`, or the Agent SDK), which can read the repository, make edits,
run `mvn test`, and push a branch. n8n's Anthropic node is a stateless text-completion
call; conflating "n8n calls an LLM" with "n8n fixes the bug" would overstate what gets
built. If auto-fix is ever wanted, it is a **different, larger** piece of infrastructure —
headless Claude Code with repo write access, itself needing its own trust boundary
(what it's allowed to touch, whether it can push directly or only opens a PR for human
review, and how that interacts with this repo's existing squash-merge/worktree discipline)
— not a natural extension of an n8n-Claude-API node.

This document does not propose building that. If it's wanted later, it needs its own
design doc, not a footnote here.

## 5. The blocking question for both ideas: PII leaving the trust boundary

This is not a footnote — it blocks both ideas as designed above, not just idea 2.

Bank statement content (idea 2's parser context: account numbers, transaction narrations,
balances, sender identity) and financial account data (idea 1's tool-call responses:
balances, transaction history) are the most sensitive data class this product holds.
`fynora-packaging-audit-2026-09-06` and the encryption/PII-leak history in project memory
(`ocr-production-deployment` — a PII leak was found and fixed in production logs;
`deleted-account-dashboard-leak-fix` — a data leak found and repeated across 6 more
services) establish that this codebase has had real, shipped PII-boundary bugs, not
hypothetical risk.

Sending statement text or transaction data to Claude's API — whether directly from the
backend (idea 1) or via an n8n node (idea 2) — means that data leaves Finora's own
infrastructure and reaches Anthropic's API. The Gmail Sync design (approved, not yet built — per project memory
`gmail-sync-design-approved`) already establishes a comparable trust rule: trust requires a
known sender **and** a specific parser **and** explicit user confirmation, never
LLM-inferred trust. That precedent establishes that this
codebase already treats "send financial content to an inference step and trust the
result" as something requiring deliberate, narrow design — not something to wire up as a
side effect of adding an orchestration tool.

Before either idea proceeds past this document, one of the following has to be
decided, explicitly:

1. **Redact before send.** Idea 2's parser-context payload strips account numbers and full
   narrations, sending only what's needed to diagnose a parsing failure (a structural
   description of what the parser expected vs. what it found — not the underlying
   financial values). Idea 1's tool-call responses would need the same treatment, which is
   harder: a "what's my balance" chatbot's entire value is telling the user their real
   balance, so redaction has to happen at the point where the answer is *composed*
   (backend fills in real numbers after getting Claude's response shape), not on the way
   to Claude — meaning Claude never sees real financial values, only intent/routing.
2. **An enterprise data agreement with Anthropic** (or whichever provider) covering data
   retention, training-data exclusion, and regulatory posture appropriate for financial
   PII, before any real customer data is sent as-is.
3. **Neither is in place yet — don't send real data.** Any prototype work uses synthetic
   or heavily redacted fixtures only, until (1) or (2) is decided.

Nothing in this document assumes an answer. This is the actual blocking design question,
and it needs Sid's decision, not an implementation default.

## 6. Recommendation on scope and sequencing

**Do not start engineering on either idea now.** Reasons, each independently sufficient:

- **Sequencing conflict with an existing decision.** Per project memory
  (`personal-cfo-premium-layer-sequencing`, `premium-import-reliability-v1`): premium and
  Gmail-sync-adjacent features are sequenced **post-launch**, audit-before-vision. Both
  ideas here are premium-adjacent (`FINO_AI` is an unpriced premium feature;
  automated-diagnosis tooling is pure internal ops investment, not user-facing, and
  doesn't move the launch gate). Neither is on the v1 GA critical path
  (`v1-scope-decision-vision-vs-gate`).
- **New infrastructure with no owner decision yet.** An n8n instance is new hosting,
  patching, secrets management, and an additional system in the security review surface —
  this itself needs to be weighed against `native-build-ci-audit-2026-09-09`'s still-open
  "go private" question and the pre-launch safety-check gaps already tracked
  (`pre-launch-safety-check-findings`: no DB backups, no prod alerting confirmed). Adding
  an unmanaged automation platform before those close increases operational surface at the
  wrong time.
- **The blocking PII question in §5 is unresolved.** Building either integration before
  that's decided risks shipping the exact kind of PII-boundary bug this codebase has
  already had to fix twice in production (§5).

**If and when engineering time is allocated** (post-launch, after the sequencing items
above clear):

- **Idea 2 (held-statement diagnosis) is the smaller, lower-risk one to build first**, if
  either is built at all: it's an internal ops tool, not user-facing; it reuses existing
  endpoints (§2.3) almost entirely; and its blast radius from a bad diagnosis is "engineer
  reads a wrong suggestion and dismisses it," not "user gets told a wrong balance." It
  should still resolve §5 before any real statement content leaves the backend.
- **Idea 1 (`FINO_AI`) is materially larger** — it needs a product-scoping decision (what
  can the assistant actually do, how is it priced, what's the entitlement model) before
  any architecture question, n8n included, is worth answering. That scoping decision
  belongs to Sid, not to this document.

## 7. Explicitly out of scope (this document)

- No n8n instance was installed or configured.
- No integration code, backend endpoint, or admin-portal change was written.
- No Anthropic API client was added to the backend.
- Pricing/entitlement design for `FINO_AI` is not addressed — that's a product decision,
  not an engineering one.
- The "auto-fix via headless Claude Code" extension mentioned in §4.3 is named, not
  designed.

## 8. Open questions

1. Does Sid want either idea prioritized at all, given the sequencing conflict in §6 — or
   should this stay parked alongside `FINO_AI`'s existing parked status until post-launch?
2. For idea 1: what's the actual product scope of `FINO_AI` — free-text Q&A over the
   user's own data, a fixed set of guided actions, or something narrower? This changes the
   tool-calling surface entirely and should be answered before any orchestration-layer
   choice (n8n or otherwise).
3. For idea 2: is "narrows the search space for an engineer" (§4.2) valuable enough on its
   own to justify new infrastructure, given the trust-review predicate already fires on
   0/27 of the real corpus per `held-statement-review-track` — i.e., how often would this
   tool even run in practice?
4. Which of §5's three options (redact, enterprise agreement, synthetic-only) does Sid
   want, and does Anthropic (or another provider) actually offer terms that satisfy option
   2 at whatever usage volume Finora would have?
5. If n8n is adopted for either idea, is it hosted alongside the existing Railway
   infrastructure (extending the operational surface already tracked in
   `hikaricp-bottleneck-and-railway-ceiling`) or separately — and who owns patching it?
