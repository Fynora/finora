# Account Aggregator sync — Plan 5 scope (cost controls + consent-management UX)

Status: scoped, not yet an implementation plan. Boundary decisions below are settled; a task-by-task
TDD plan is a separate future step, same as Plans 1-4's own process.

Builds on the merged Plan 1 (link lifecycle, #1396/#1400), Plan 2 (transaction sync, #1406), Plan 3
(AA-vs-Gmail canonicalization, #1415), and Plan 4 (outage escape hatch, PR #1426, **not yet
merged** — this scope doc was written against `origin/main` without Plan 4, and cites Plan 4's code
as "per PR #1426" rather than as confirmed-merged fact; see the dependency note under Open items).

## The scope is bigger than "cost controls + a management screen"

The design spec's own "Missing requirements" section frames this plan as adding "consent-management
UX: linked accounts list, per-account status, last-synced timestamp, revoke/relink controls" to an
otherwise-working feature. **That undersells what's actually missing, checked directly against the
code rather than assumed:**

- Exhaustive grep across `frontend/src` for `Bank Sync`, `Account Aggregator`, `integrations/setu`,
  `initiateLink`, `linkId`, `consentHandleId`, `FiType`, `redirectUrl` finds **zero** references
  outside auto-generated API types and the two reactive picker fixes (Plans 3/4 disabling/labeling
  an *already-linked* account's `<option>`). There is no "Connect your bank" button, no consent
  flow, no PROBABLE-match confirmation screen anywhere in the app today.
- `AccountAggregatorLinkController` (`backend/src/main/java/com/finora/integrations/setu/`) exposes
  exactly three endpoints: `POST /links` (initiate), `POST /links/{id}/confirm-existing-account`,
  `POST /links/{id}/confirm-new-account`. **No list endpoint, no revoke endpoint, no per-link status
  endpoint exists.** A "linked accounts list" needs a new read endpoint before it needs a screen.

So Plan 5 is not "add management UX to a working self-service flow" — it's the **first plan that
builds any user-facing entry point into Account Aggregator at all**. Every AA link created so far
must have gone through direct API calls, not the app. This is worth stating plainly before scoping
further, since it changes the shape of "how big is Plan 5" considerably from what the roadmap's own
one-line description implies.

**The good news:** Gmail Sync already has a fully-built, working analog of exactly this shape --
`Settings.tsx`'s Gmail section (`handleGmailConnect`/`loadGmailStatus`/`handleGmailSyncNow`/
`handleGmailDisconnect`, `frontend/src/pages/Settings.tsx`) does connect-via-redirect, status
polling, error surfacing, and disconnect for an external-data-source OAuth flow. This plan should
mirror that structure, not invent a new one -- see "Frontend architecture" below for the one
structural difference that matters (redirect-*return* handling).

## In scope

### 1. Cost controls (design spec: link caps, relink throttling, rate limiting)

- **Rate limiting**: reuse the existing `RateLimitFilter`/`RateLimiter` pattern
  (`backend/src/main/java/com/finora/config/`) — a single filter matching path patterns to
  per-endpoint `RateLimiter` instances, already used for login/register/forgot-password/CSV-import-
  staging/change-password. Add `POST /api/v1/integrations/setu/links` (link initiation) as a new
  matched path with its own limiter. Not a new mechanism — one more entry in an existing,
  battle-tested filter.
- **Link cap per user**: mirrors `AccountService`'s own `FREE_ACCOUNT_LIMIT`/`countByUserId` pattern
  (checked live on every call, not a stale pre-count, per that code's own documented reasoning for
  a multi-account-creation race). A new config value (not necessarily tier-differentiated — the
  design spec doesn't specify a number, and this plan shouldn't invent one without product input;
  see Open items), checked in `SetuConsentService.initiateLink` before creating a new consent
  request, counting `AccountAggregatorLinkRepository` rows in non-terminal statuses
  (`CONSENT_PENDING`/`PENDING_ACCOUNT_CONFIRMATION`/`ACTIVE`/`PAUSED`) for that user.
- **Relink throttling**: "at most one consent-creation attempt per specific account per rolling 24h
  window" (design spec) — distinct from Plan 1's `linkIdempotencyKey` unique-index guard, which only
  prevents *concurrent* duplicate submissions of the *same* attempt, not a user re-triggering fresh
  link attempts in a loop over time. Needs a query for "does this user already have a
  non-terminal-or-recently-terminal link for this same FI type/account within the last 24h" — the
  exact matching key (by `fiType` alone, since there's no FI/account identity to key on before
  consent even completes) needs care; see Open items.

### 2. New backend endpoints (needed before any frontend screen can exist)

- `GET /api/v1/integrations/setu/links` — list the caller's own links (id, fiType, status,
  `consentExpiresAt`, `lastSyncedAt`, `lastSyncStatus`, and — once Plan 4 merges — a per-link
  staleness signal reusing `AccountAggregatorLinkStalenessService.isStale`, the same single-source-
  of-truth principle Plan 4 established between its guard and its DTO).
- A revoke-adjacent endpoint — **name and exact semantics are an open item, not settled here**, see
  "AA app vs. Fynora as source of truth" below. Whatever it does, it is NOT a call to Setu that
  revokes consent at the AA layer — `AccountAggregatorLinkStatus.REVOKED`'s own doc comment states
  this explicitly: *"the user revoked consent in their own AA app (out-of-band — Fynora only
  observes this via webhook, it cannot force a revoke)."* Confirmed by reading the enum itself, not
  assumed.

### 3. Downgrade handling — a real, confirmed gap, not a Plan 5 invention

**Checked directly, not assumed:** the design spec states "Downgrade to Free/Plus → link `PAUSED`...
linked `Account.primarySource` reverts to `MANUAL`, manual upload unblocks," and separately, "Both
the webhook handler and the sweep re-check entitlement before processing a tick, mirroring
`GmailDiscoveryWorker`'s existing downgrade check." Grepping for `hasEntitlement`/`PAUSED` across
`integrations/setu/` shows the entitlement re-check exists in exactly two places:
`SetuConsentService.initiateLink` and `AccountAggregatorIdentityResolutionService.resolveAndAttach`
(both check *at the moment consent is being approved*, and correctly set `PAUSED` if the check
fails there) — and `SetuDataFetchService.sync`, which checks entitlement but **only to skip that
one fetch call**, logging and returning; it does not touch `link.status` or `Account.primarySource`
at all. `GmailDiscoveryWorker`'s own "downgrade check" (the pattern the design spec says this
mirrors) does the identical bare skip — correct for Gmail, since a Gmail connection carries no
`primarySource`-style manual-import lockout consequence, but insufficient for AA, which does.

**Net effect today: a user who downgrades while a link is already `ACTIVE` keeps that link `ACTIVE`
forever** (sync silently stops, but status/primarySource never revert) — locked out of manual
import for that account indefinitely, with no path back to `MANUAL` other than Plan 4's outage
hatch eventually opening after 72h of dead sync, which is an accidental, indirect, non-obvious
workaround for an architectural gap, not a real fix. This plan closes it properly: a downgrade-aware
sweep (or an extension of Plan 4's read-only sweep, once merged — see Open items) that finds `ACTIVE`
links whose user has lost `ACCOUNT_AGGREGATOR_SYNC` and transitions them to `PAUSED` + reverts
`primarySource`, the same transition `resolveAndAttach`'s own entitlement check already performs at
consent-approval time.

### 4. Frontend — the connect flow, the confirmation screen, and the management screen

**Connect flow** (mirrors `Settings.tsx`'s Gmail section structure):
- A "Bank Sync" section, likely in `Settings.tsx` alongside Gmail (same page, same mental model —
  "external data sources you've connected") unless product direction says otherwise (see Open
  items).
- `handleConnect`-equivalent: `POST /links`, then `window.location.href = redirectUrl` — same real-
  browser-navigation reasoning Gmail's own comment gives (the AA app is a different origin
  entirely).
- **Structural difference from Gmail that needs its own design, not a copy-paste**: Gmail's OAuth
  flow is a synchronous redirect-back-with-a-code; AA's consent approval is asynchronous and
  webhook-driven (`consent.approved` arrives on Fynora's backend independently of when/whether the
  user's browser ever returns to Fynora at all). There is currently no configured "return to Fynora"
  redirect target for the AA flow (unlike Google's `post-connect-redirect`) — whether Setu's
  consent-request API even accepts a return-URL parameter is unverified against real Setu docs/
  sandbox (see Open items). The realistic UX, pending that answer, is a polling "waiting for your
  bank's confirmation" state after initiation, not an immediate synchronous result.

**PROBABLE-match confirmation screen**: `AccountAggregatorLinkController.confirmExistingAccount`/
`confirmNewAccount` already exist server-side (built in Plan 1, per that endpoint's own bug-fix
comment: "the original plan built the service method for this but never wired an endpoint to it")
but have zero frontend callers today. This is existing, tested backend logic waiting for its first
UI.

**Linked-accounts management screen**: list (via the new `GET /links` endpoint), per-account status,
last-synced timestamp, a staleness indicator once Plan 4 merges, and whatever the revoke/relink
control turns out to mean (see below).

**Consent lifecycle UX**: `REJECTED` ("declined — try again") and `LINK_FAILED` ("couldn't create
the request — try again") both need a defined UI treatment — the design spec calls both out
explicitly as "not a silently-stuck row." `PAUSED` (downgrade) needs a "your plan no longer includes
Bank Sync" message distinct from both.

### 5. AA app vs. Fynora as source of truth — resolved by the code's own documentation, not guessed

`AccountAggregatorLinkStatus.REVOKED`'s own doc comment is unambiguous: Fynora "cannot force a
revoke." Whatever this plan's "revoke" control does, it is not a Setu API call that revokes consent.
The two honest options:
- A "relink" control only (re-initiate a fresh consent flow for the same account), with explicit
  copy pointing the user to their AA app for actual revocation — matching the design spec's own
  "an explicit note that the user's AA app — not Fynora — is the system of record for revoking
  consent."
- A Fynora-side-only "disconnect" that stops Fynora from calling Setu's fetch endpoint for that link
  (effectively a local pause, cost-wise equivalent to a revoke from Fynora's own billing exposure,
  even though Setu's own consent record stays technically valid until the user separately revokes it
  in their AA app or it expires). This needs explicit copy too, so a user doesn't believe clicking
  it ends Setu's own retention of their consent.

This plan should pick one (leaning toward the second — a real, useful control Fynora can actually
offer, with honest copy about what it does and doesn't do) rather than ship a "Revoke" button that
implies a capability that doesn't exist. Flagged as a decision this scope doc surfaces, not one it
makes unilaterally — see Open items.

## Out of scope (explicitly deferred, not forgotten)

- **Bank-side mutation handling** (Plan 6): the three-way `{new, changed, missing}` diff, sliding-
  window re-fetch. Unrelated to consent management.
- **The confirm-time Gmail warning dialog**: already decided against in Plan 3 (Plan 1's guard makes
  it structurally unreachable) — not reopened here.
- **`CREDIT_CARD` FiType validation against real Setu sandbox data**: still gated behind its own
  named pre-implementation task per the design spec's "Explicitly out of scope" section, unchanged
  by this plan. The connect flow this plan builds should work for `DEPOSIT` first; whether
  `CREDIT_CARD` is offered in the initial FI-type picker depends on that unresolved validation.
- **Tier-differentiated link caps** (e.g. a higher cap for a hypothetical higher tier): the design
  spec doesn't specify numbers or tiers; this plan ships one global cap, not a pricing decision.
- **A Redis-backed rate limiter**: `RateLimiter`'s own doc comment already states the single-
  instance, in-memory design is deliberate until there's a second instance to synchronize across —
  not revisited here.

## Open items (need a decision before an implementation plan is written)

1. **Does Setu's consent-request API accept a return-redirect URL**, the way Google's OAuth flow
   does? This determines whether the connect flow can show an immediate result or must poll after
   redirecting the user away. Needs real Setu API docs/sandbox access — the same category of gap
   already flagged unresolved in Plans 2 and 4.
2. **Revoke/relink semantics** (see section 5 above) — a product decision on what the control
   actually does and how its copy should honestly describe it, not an engineering one.
3. **Link cap value** — the design spec names the *mechanism* ("a hard cap on linked accounts per
   user, config value") but not a number. Needs product input, not an invented default.
4. **Relink-throttling matching key** — before a link resolves to a real account, what makes two
   consent attempts "the same account" for the 24h-throttle's purposes? By `fiType` alone (crude —
   throttles a user from linking *any* second deposit account for 24h after linking their first) or
   something finer once more is known about what Setu's initiate response actually returns before
   consent completes. Needs the same Setu API research as item 1.
5. **Where does the connect flow live** — folded into `Settings.tsx` next to Gmail (this doc's
   working assumption, since it's the closest existing analog), or its own page/route? A UI/product
   call, not resolved here.
6. **Sequencing against Plan 4** (PR #1426, not yet merged): this plan's staleness display and its
   downgrade-handling sweep both want to reuse `AccountAggregatorLinkStalenessService` and the
   `findByStatus(ACTIVE)` query Plan 4 adds. Implementation should wait for Plan 4 to merge rather
   than duplicate that infrastructure speculatively — flagged so it isn't lost, not because this
   plan can't be *scoped* without it (it can, and has been, above).
