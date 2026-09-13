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
  request, counting `AccountAggregatorLinkRepository` rows in every non-terminal-*by-construction*
  status — `CONSENT_PENDING`/`PENDING_ACCOUNT_CONFIRMATION`/`ACTIVE` — for that user. Every fully
  terminal status (`REVOKED`/`EXPIRED`/`REJECTED`/`LINK_FAILED`) is excluded for the uncontroversial
  reason a user must always be able to start a fresh link after a fully-dead one.

  **Whether `PAUSED` also counts is product policy, not settled here — walked back per review
  feedback.** An earlier revision of this doc decided to exclude it (reasoning: the cap exists for
  cost control, and a `PAUSED` link costs nothing since `SetuDataFetchService.sync` already skips
  it). That's a real argument, but not the only reasonable one — a case for counting it exists too
  (e.g. product may want "how many banks has this user ever connected" bounded, not just "how many
  are currently costing something," to keep the linked-accounts screen itself from growing
  unboundedly across repeated downgrade/upgrade cycles). Both are legitimate product calls this
  plan shouldn't make unilaterally — see Open items. What *is* settled regardless of which way this
  goes: `PAUSED` needs an actual resume path (section 3), which today doesn't exist at all — that
  finding stands independent of the cap question.
- **Relink throttling**: "at most one consent-creation attempt per specific account per rolling 24h
  window" (design spec) — distinct from Plan 1's `linkIdempotencyKey` unique-index guard, which only
  prevents *concurrent* duplicate submissions of the *same* attempt, not a user re-triggering fresh
  link attempts in a loop over time. **Elevated per review feedback — this is a real design question,
  not just an open detail to fill in later.** The design spec's own wording ("per specific account")
  describes a granularity this system cannot implement as stated: there is no account identity at
  all before consent completes (that's the whole reason `PENDING_ACCOUNT_CONFIRMATION` /
  `ProductIdentityResolver`'s `PROBABLE` tier exist — even *after* consent, identity is sometimes
  ambiguous). A per-account throttle is therefore not implementable at the point where throttling
  has to happen (before `initiateLink` creates a new consent request), regardless of what Setu's API
  turns out to expose. The real open question (Open item 3) is only how much finer than "per
  `fiType`" Setu's *initiate* response lets this get — not whether per-account throttling as
  literally specified is achievable, because it isn't.

  **Concrete fallback if Setu research comes back with nothing finer**: throttle by
  `(userId, fiType)` — at most one non-terminal consent-creation attempt per `fiType` per rolling
  24h window. Coarser than the design spec's own framing (blocks a user from starting a second
  *different* `DEPOSIT` account link within the window, not just a retry of the same one), but a
  real, shippable control against the actual abuse case (a user or script hammering
  `initiateLink`) rather than a blocked plan waiting indefinitely on Setu API research. This plan
  should ship this fallback if item 3 isn't resolved by implementation time, not treat the whole
  relink-throttling requirement as blocked on it.

### 2. New backend endpoints (needed before any frontend screen can exist)

- `GET /api/v1/integrations/setu/links` — list the caller's own links (id, fiType, status,
  `consentExpiresAt`, `lastSyncedAt`, `lastSyncStatus`, `statusChangedAt` (section 7), and — once
  Plan 4 merges — a per-link staleness signal reusing
  `AccountAggregatorLinkStalenessService.isStale`, the same single-source-of-truth principle Plan 4
  established between its guard and its DTO).
- `POST /api/v1/integrations/setu/links/{id}/disconnect` — see "AA app vs. Fynora as source of
  truth" below for the full reasoning. Not a call to Setu that revokes consent at the AA layer
  (`AccountAggregatorLinkStatus.REVOKED`'s own doc comment: Fynora "cannot force a revoke") — sets
  the same `REVOKED` status the `consent.revoked` webhook case already sets (section 6), recording a
  distinct audit action so the *who/why* difference is still traceable without a second status.

### 3. Entitlement sync — downgrade AND upgrade, a real, confirmed gap on both sides

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
workaround for an architectural gap, not a real fix.

**A second, equally real gap on the other side of the same transition, found while responding to
review feedback on the link-cap question above: `PAUSED` is a dead end.** `grep -rn
"AccountAggregatorLinkStatus.PAUSED"` across the whole backend finds exactly one write site
(`resolveAndAttach`, setting it) and *zero* reads anywhere — no sweep, no webhook case, nothing
ever transitions a `PAUSED` link back to `ACTIVE`. A user who downgrades and later re-upgrades has
no path back to a working link short of disconnecting and starting an entirely new consent flow
from scratch, even though `PAUSED` is deliberately not documented as terminal in the enum's own doc
comments (unlike `REVOKED`/`EXPIRED`/`REJECTED`/`LINK_FAILED`, which are) — its own design implies
resumability that nothing delivers.

**This plan closes both gaps with one symmetric mechanism**, not two: an entitlement-sync sweep (or
an extension of Plan 4's read-only outage sweep, once merged — see Open items) that, on every tick,
finds `ACTIVE` links whose user has lost `ACCOUNT_AGGREGATOR_SYNC` and pauses them (status →
`PAUSED`, `primarySource` → `MANUAL` — the same transition `resolveAndAttach`'s own entitlement
check already performs at consent-approval time), **and** finds `PAUSED` links whose user has
regained the entitlement and resumes them (status → `ACTIVE`, `primarySource` → `ACCOUNT_AGGREGATOR`).
Automatic, not a manual "Resume" button — deliberately the opposite choice from
`BillingCheckoutService`'s subscription-pause/resume UX (which does use a manual button), because
pausing a subscription is itself a deliberate user choice needing a deliberate resume, whereas an AA
link going `PAUSED` is an involuntary side effect of a billing event elsewhere; the closer precedent
is `GmailDiscoveryWorker`'s own entitlement gate, which is symmetric and automatic by construction
(a live check, not a persisted flag needing a toggle) — nothing in Gmail Sync needs a manual
re-enable either.

### 4. Consent expiry — `EXPIRED` is never actually reached, found while investigating review feedback

Raised in review as "consent-expiry renewal UX" being underdeveloped in this doc. Checked deeper
than the UX layer: it's not underdeveloped, it's **entirely unbuilt on the backend**.
`consentExpiresAt` is stored on every link (`AccountAggregatorLink.getConsentExpiresAt()`) but
`grep -rn "AccountAggregatorLinkStatus.EXPIRED"` across the whole backend returns nothing — no
webhook case, no scheduled check comparing it against `now()`. A consent that expires today simply
leaves its link `ACTIVE` forever, indistinguishable from a healthy one until Plan 4's outage hatch
eventually notices the dead sync 72h later — the identical accidental-workaround shape as the
downgrade gap above.

This plan adds the missing transition, and it's a genuine open question (not resolved here) whether
that's a new webhook case, a proactive sweep comparing `consentExpiresAt < now()`, or both —
whether Setu sends any expiry-related webhook at all is unverified against real API docs/sandbox
(same category as Open items 1 and 3). This codebase's own established architecture principle
(design spec: "Webhook-driven + reconciliation sweep, not poll-only") argues for building the sweep
regardless of what the webhook research finds, the same "external push, verified by a periodic
sweep" shape every other AA lifecycle transition already uses — a sweep is not wasted effort even
if a webhook also exists, since it's the safety net for a missed or nonexistent one. Once `EXPIRED`
is actually reachable, the frontend needs it as a first-class case in the "Consent lifecycle UX"
list below: "Your bank connection has expired — reconnect" with a direct path back into the connect
flow, not lumped in with `REJECTED`'s "try again" copy (a user didn't decline this one, time simply
ran out — worth saying differently).

### 5. Frontend — the connect flow, the confirmation screen, and the management screen

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

**Consent lifecycle UX**: `REJECTED` ("declined — try again"), `LINK_FAILED` ("couldn't create the
request — try again"), and `EXPIRED` ("your bank connection has expired — reconnect," worded
distinctly from `REJECTED` per section 4 above — see that section for why the transition itself
doesn't exist yet either) all need a defined UI treatment — the design spec calls the first two out
explicitly as "not a silently-stuck row," and the same principle extends to the third once it's
reachable. `PAUSED` (downgrade) needs a "your plan no longer includes Bank Sync" message distinct
from all three, and — now that section 3 makes `PAUSED`→`ACTIVE` automatic on re-upgrade — should
say so ("reconnects automatically once you're back on a plan that includes this"), not imply the
user needs to do anything once they upgrade.

### 6. AA app vs. Fynora as source of truth — resolved by the code's own documentation, not guessed

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

**Decided: the second option, for the action.** A `POST /links/{id}/disconnect` endpoint (name
deliberately not "revoke," to avoid implying the capability the REVOKED status's own doc comment
says doesn't exist) that stops Fynora calling Setu for that link, triggered by the user's own
request instead of an inbound webhook. The rationale for picking this over the copy-only "relink"
option: a user who downgrades or simply wants their bank data out of Fynora's live sync needs
*something* they can click inside Fynora itself, not an instruction to go do it somewhere else —
and this control is honest about what it actually does (stops Fynora from calling Setu for this
link) without claiming to touch Setu's own consent record. Required copy change, not optional: the
disconnect confirmation must say plainly that this doesn't cancel the user's consent grant at their
AA app or at Setu — full revocation still has to happen there — otherwise this reads as a real
revoke to a user who has no reason to know the distinction.

**Status value: back to a single `REVOKED`, not a second `DISCONNECTED` — reversed after review
pushback, and the reversal is correct.** A prior revision of this doc introduced `DISCONNECTED` as
a distinct status, reasoning that collapsing it into `REVOKED` throws away real information (Setu-
side consent gone vs. Fynora unilaterally stopped calling it). Review feedback applied the sharper
test directly: *different behavior → different state, same behavior → same state* — and by that
test, `DISCONNECTED` fails, because nothing downstream currently treats the two cases differently
(re-confirmed as still true, not just previously true: `AccountAggregatorGuard`, the outage sweep,
and the picker only ever ask "is this link ACTIVE," never anything finer). A second enum value with
no behavioral difference is exactly the kind of complexity-before-there's-a-requirement the test
exists to catch. The `/disconnect` endpoint sets `REVOKED`, same as the `consent.revoked` webhook
case — one status, one meaning: this link is done, regardless of which side ended it.

**The original concern this was solving for is real, though, and doesn't have to be solved at the
status-enum level.** "Did the user disconnect this, or did their bank kick them out" is a *who/why*
question, and this codebase already has a mechanism built specifically for who/why: the audit log,
not the state machine. `AccountAggregatorWebhookDispatcher`'s `consent.revoked` case already
records `ACCOUNT_AGGREGATOR_CONSENT_REVOKED`; the `/disconnect` endpoint should record a distinct
action (e.g. `ACCOUNT_AGGREGATOR_USER_DISCONNECTED`) via the same `AuditService` call, both writing
`link.status = REVOKED`. This keeps the state machine honest to the review's own rule (one
behavioral state) while keeping the provenance a support ticket actually needs — in the layer this
codebase already uses for exactly that purpose, not a second copy of it bolted onto the status enum.

### 7. One added timestamp: `statusChangedAt` — added per review feedback

Not a full audit-log UI (explicitly not asked for) — one new column, because the two obvious
support questions ("when did this stop syncing," "when did they connect this") turn out to have no
honest answer with the fields that exist today. `createdAt` is set once, at `CONSENT_PENDING`
creation, which can be days before the link ever reaches `ACTIVE` if the user takes their time
approving consent — not "connected on." `updatedAt` looked like the obvious candidate (Plan 4 already
leans on it for a different purpose), but it's touched by `setLastSyncedAt` on every single sync —
for a healthy `ACTIVE` link mid-daily-sync, `updatedAt` is always "a few hours ago" regardless of
when the link actually reached its current *status*, making it useless for either question.

New field, set inside `AccountAggregatorLink.setStatus()` itself (not repeated at each of the
~6 call sites that set status, so it can't be forgotten at a future one): `statusChangedAt`. For a
currently-`ACTIVE` link that hasn't yet had its first status-preserving mutation after activation,
it doubles as "connected on"; for a `PAUSED`/`REVOKED`/`EXPIRED`/etc. link, it directly answers
"when did this stop." Surfaced on the new `GET /links` endpoint (section 2) and the management
screen (section 5).

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

## Decisions made across this doc's revisions (no longer open)

- **Disconnect control**: a `POST /links/{id}/disconnect` endpoint, with copy explicit about not
  touching the user's consent grant at Setu/their AA app. Sets the same `REVOKED` status the
  `consent.revoked` webhook already sets (one behavioral state, per review's own "different
  behavior → different state" test), recording a distinct audit action for who/why traceability
  instead of a second status value. Section 6.
- **`PAUSED` gets an automatic (not manual-button) resume path back to `ACTIVE` on re-upgrade** —
  a real, confirmed gap (zero resume path exists today, for either the consent-time or the
  already-active pause case) independent of the still-open cap-counting question below. Section 3.
- **`statusChangedAt`**: one new field, set inside `setStatus()` so no call site can forget it,
  added per review feedback on audit-trail visibility. Section 7.
- **Where the connect flow lives**: `Settings.tsx`, in a new "Bank Sync" section alongside the
  existing Gmail section. Section 5.

## Still open (need a decision before an implementation plan is written)

1. **Does Setu's consent-request API accept a return-redirect URL**, the way Google's OAuth flow
   does? This determines whether the connect flow can show an immediate result or must poll after
   redirecting the user away. Needs real Setu API docs/sandbox access — the same category of gap
   already flagged unresolved in Plans 2 and 4. Genuinely external: no amount of further reading
   this codebase resolves it, since the integration is unbuilt (`SetuConsentGatewayImpl` is a
   placeholder).
2. **Link cap value, and whether `PAUSED` counts toward it** — both product policy, not settled
   here. The design spec names the cap's *mechanism* but not a number; whether a dormant `PAUSED`
   link should consume a slot has reasonable arguments on both sides (section 1) and isn't an
   engineering call this doc should make unilaterally.
3. **Relink-throttling matching key** — the granularity finer than `fiType` (section 1's fallback)
   depends on what Setu's initiate response actually returns before consent completes. Needs the
   same Setu API research as item 1.
4. **Does Setu send any consent-expiry webhook?** (section 4, new in this revision) — determines
   whether `EXPIRED` needs a webhook case, a sweep, or both; this plan builds the sweep regardless
   (matching this codebase's established webhook-plus-safety-net architecture), but whether a
   webhook case is also worth adding depends on this. Same research category as items 1 and 3 — all
   three could plausibly be answered by the same round of Setu API/sandbox investigation.
5. **Sequencing against Plan 4** (PR #1426, not yet merged): this plan's staleness display and its
   entitlement-sync sweep both want to reuse `AccountAggregatorLinkStalenessService` and the
   `findByStatus(ACTIVE)` query Plan 4 adds. Implementation should wait for Plan 4 to merge rather
   than duplicate that infrastructure speculatively — flagged so it isn't lost, not because this
   plan can't be *scoped* without it (it can, and has been, above).
