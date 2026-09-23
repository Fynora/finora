# Navigation Analytics — Design Spec

Date: 2026-09-23
Status: Approved for planning
Evidence base: source read against `origin/main` @ `b7f2523f`
Companion to: [`2026-09-23-shared-nav-taxonomy-design.md`](./2026-09-23-shared-nav-taxonomy-design.md)

## Purpose

The shared navigation taxonomy changes how every destination in the product is reached, and today
nothing in either client can observe the result. This spec adds the minimum measurement needed to
tell whether that change did anything — and no more.

The conclusion, stated up front because it is the whole design: **this is not "add an analytics
SDK."** It is three Micrometer counters on infrastructure the backend already runs, fed by one
first-party endpoint. No third-party vendor, no cookies, no user identity, no event storage.

That is not a minimalist preference. It is what the published privacy policy and the repository's own
observability contract permit, and everything below follows from those two constraints.

## The constraints, read before designing

### The privacy policy already covers this — and binds it

`frontend/src/pages/Privacy.tsx:134-140` commits, publicly, to:

> aggregated, non-identifying usage data (such as which features are used most) ... never combined
> with your individual financial data

Three things follow, none of them optional:

1. **Aggregated and non-identifying.** No per-user event stream, no `identify(userId)`, no session
   stitching. This rules out the default operating model of PostHog, Mixpanel, Amplitude and Segment,
   all of which are built around an identified user timeline.
2. **Feature usage is explicitly the sanctioned example.** "Which features are used most" is almost
   word-for-word what navigation counters produce, so this work sits inside the existing promise
   rather than requiring a policy change.
3. **Never combined with financial data.** Events carry no transaction, amount, merchant, balance or
   account information — see §3 of the observability contract below, which already forbids it.

Separately, `Privacy.tsx:125-131` states: *"We do not currently use third-party advertising or
tracking cookies."* A cookie-dropping third-party analytics SDK would contradict a published
statement. Changing the policy to accommodate a vendor is possible but is a much larger decision than
measuring a navigation change, and is not proposed here.

### The observability contract already exists

`docs/engineering/observability.md` is a mature, tested contract, and this spec inherits it rather
than inventing a parallel one:

- **§3 — what must never leave the platform.** Statement contents, account numbers, personal
  identifiers, credentials, request bodies and headers in any form, and free text the user typed —
  "most sharply the ledger search term."
- **§5 — tags are the one structured channel**, allowlisted rather than open, and *"every value under
  these keys must be an internal identifier or a bounded enum. Never customer data."*
- **§7** defines a worker instrumentation contract including required metrics, tags, correlation and
  tests — including `WorkerMetricsExportIT`, which exists because a meter in a registry nothing
  exports is not a measurement.

Two existing precedents matter directly:

- `backend/src/main/java/com/finora/observability/AuthMetrics.java` exists for exactly the reason this
  spec exists. Its own doc comment: without its counters, *"how many users are actually hitting the
  new limits" was a guess, not a measurement.* Same argument, applied to navigation.
- `AuthMetrics` also **refuses a client-asserted tag**. It declines to tag by client platform because
  `X-Client-Platform` is client-asserted and it will not carry that on a security-relevant counter.
  This spec must answer that precedent rather than ignore it — see Tags below.
- `SentryScrubber.java` establishes **allowlist, never denylist**: *"Structures are rebuilt from
  scratch rather than having fields deleted from them."* The ingest endpoint follows the same rule.

## What gets measured

Three counters. Each corresponds to a discrete user action, and each answers a question the taxonomy
spec actually asks.

| Counter | Question it answers |
|---|---|
| `finora.nav.destination_opened` | Which destinations are used, and how does the distribution shift after grouping? |
| `finora.nav.entry_point_used` | Do people reach a destination via its group entry, a promoted shortcut, or a contextual link? |
| `finora.nav.search_used` | Does navigation search substitute for browsing when the list is long? |

`entry_point_used` is the one that justifies the Shortcut rule empirically. The taxonomy spec asserts
that promoted items must stay listed in their groups; this counter is what would eventually show
whether the group entry is used at all, or whether the shortcut carries everything.

### The counter that was cut

An earlier draft had a fourth, `group_expanded_view`, to ask whether grouped sections were being
scanned or scrolled past. It is deliberately removed rather than redefined.

The name was a leftover from an accordion model the taxonomy spec explicitly rejected — groups are
static sections, so nothing ever "expands" and there is no user action to count. What remains is a
viewport question, and a viewport question would be counted differently by each client almost
immediately: scrolled into view, rendered at all, or the More menu merely opened. Two clients
disagreeing about what a number means is worse than not having the number.

The three above are all unambiguous taps or clicks. If "are groups being scanned" becomes a real
question later, it needs eye-level research, not a counter.

### Tags

Following §5, tags are allowlisted and every value is a bounded enum:

- `destination` — a taxonomy destination id. **The bounded enum is the taxonomy itself**, which is
  what makes this safe: the set is small, fixed, and already defined in one shared place by the
  companion spec. A value not in the taxonomy is rejected, not recorded. See Derivation below — this
  is a derivation, not a second copy of the list.
- `group` — one of the five group names, or `root`.
- `entry` — one of `group`, `tab`, `fab`, `header`, `contextual`, `search`.
- `platform` — `web` or `mobile`.

**On `platform`, and the `AuthMetrics` precedent.** `AuthMetrics` refuses this tag because it is
client-asserted and its counters are security-relevant — a falsified platform value there would
corrupt a security signal. Neither condition holds here: these counters are product telemetry, not a
security control, and the whole question being asked is whether the two clients behave differently,
which is unanswerable without the tag. A client that lies about its platform skews a usage chart and
nothing else. The tag is accepted for that reason, and the reasoning is recorded here so the
divergence from `AuthMetrics` is deliberate rather than accidental.

### What is deliberately not measured

- **No user id, account id, session id, or device id.** Counters increment; nothing is attributable.
- **No timing or dwell.** "Time to find X" would need a per-user timeline, which the policy forecloses.
- **No search terms.** §3 names the ledger search term as the sharpest case of free text that must
  never leave. `search_used` counts that a search happened, never what was typed.
- **No funnels, cohorts, or retention.** All require identity.
- **No raw event storage.** The endpoint increments a counter and returns. There is no events table,
  so there is no dataset to later re-identify, leak, or be asked to hand over.

## Derivation — analytics has no destination list of its own

Reusing the taxonomy as the bounded enum would create a new coupling if it were done by copying:
taxonomy drift would become analytics drift, and analytics drift is silent — a destination missing
from the allowlist simply stops being measured, with no error anywhere.

So the allowlist is **derived, never mirrored.** There is no separate analytics destination list to
keep in step.

The chain, using only tooling that already exists:

1. **The backend enum is the wire contract.** It is the validation boundary for the ingest endpoint,
   so it has to exist in Java regardless.
2. **Both clients already receive it.** `frontend/package.json:14` and `mobile/package.json:84` each
   run the same `generate:types` script — `openapi-typescript` against
   `backend/openapi/openapi.json`, emitting `src/api/generated-types.ts`. The backend's enum reaches
   both clients through infrastructure that is already in place; nothing new is built.
3. **Each client's analytics allowlist is computed from its taxonomy constant**, not declared
   alongside it. Adding a destination to the taxonomy makes it trackable in the same edit; there is
   no second place to forget.
4. **A test asserts the taxonomy constant and the generated enum agree.** This is the seam that
   catches a destination added to the clients but not to the backend enum, which would otherwise be
   rejected at ingest and silently unmeasured.

Step 4 belongs in the companion spec's existing drift test rather than a new one — that test already
compares the two clients' taxonomy definitions, and this adds the backend as a third participant
without adding a second test to maintain.

Note this makes the earlier "no codegen in this repo" framing precise: there is no codegen for the
hand-written domain types in `src/types/index.ts`, but there *is* established codegen for the API
surface, and it is exactly the right vehicle here.

## How client events reach the counters

Micrometer runs server-side; navigation happens client-side. One thin first-party endpoint bridges
them.

`POST /api/v1/nav-events` — authenticated, accepting a small batch of `{destination, group, entry}`
objects. `platform` is taken from existing client identity; the body carries no other fields.

**The endpoint is an allowlist, not a parser.** Per the `SentryScrubber` rule, it does not sanitise
what it receives — it validates each field against the bounded enums and drops anything that does not
match. An unrecognised destination is rejected rather than recorded under an "other" bucket, because
an "other" bucket is precisely where an unexpected free-text value would land.

**Nothing is persisted.** The handler increments counters and returns `204`. No row is written.

**Rate limiting is required**, as it is an authenticated write endpoint that a client drives at UI
speed. Batching keeps normal volume low; the limit exists for the abnormal case.

**Failure is silent and non-blocking on the client.** A failed nav-event post never surfaces an
error, never retries aggressively, and never delays navigation. Measurement must not be able to
degrade the thing it measures.

## Consent

No consent prompt is added, and the reasoning should be explicit rather than assumed.

What is collected is aggregate and non-identifying by construction — no identifier is transmitted, so
there is no personal data to consent to processing. That is the same basis on which the existing
privacy policy already describes this collection, and it is why no cookie is involved and no banner
is needed.

This is a design constraint to preserve, not merely a present fact: **if a future change adds any
identifier to these events, the consent question reopens and so does the privacy policy wording.**
That is the line to watch.

## Files affected

**Backend**
- `backend/src/main/java/com/finora/observability/NavigationMetrics.java` — new. Three counters,
  modelled on `AuthMetrics`.
- A controller for `POST /api/v1/nav-events` — validation, increment, `204`. No service layer and no
  repository, because nothing is stored.
- Bounded-enum definitions for destination, group and entry, kept in step with the taxonomy.

**Web**
- A small `trackNavigation` helper, called from the sidebar, the header actions and contextual links.

**Mobile**
- The same helper, called from the tab bar, the shared header actions and the More menu.

**Shared**
- No new artifact. The allowlist is derived per Derivation above, and the companion spec's existing
  drift test gains the backend enum as a third participant.

**No third-party dependency is added to any client.**

## Release dependencies

These are gates, not recommendations. Each one exists because skipping it destroys the value of the
work rather than merely delaying it.

**1. Counters ship before the taxonomy change, and collect for a full observation window first.**
There is no backfill. The moment the taxonomy ships, the old distribution is gone permanently, and a
before/after comparison becomes impossible rather than difficult.

The window should span **at least four weeks, including one complete month-end.** That is not an
arbitrary round number: this product's dominant usage cycle is statement import, which clusters at
month end, so a baseline that misses a month-end has not observed the product's busiest navigation
period and would be compared against an after-period that does include one.

**2. A dashboard exists before implementation begins.** Not after.

**3. A named reviewer and a review cadence exist before implementation begins.** Naming them is the
repository owner's call, but the work should not start without them.

Gates 2 and 3 address the actual failure mode here, which is not drift. It is: metrics ship,
dashboards never appear, nobody looks, and the counters become dead code carrying a permanent
maintenance and privacy-surface cost for no return. Unreviewed telemetry is worse than no telemetry,
because it looks like diligence.

## Testing

- **Meters reach the scrape.** `docs/engineering/observability.md` §7 already requires this and has a
  precedent in `WorkerMetricsExportIT` — a meter in a registry nothing exports is not a measurement.
  The same assertion is required here.
- **Rejection, not coercion.** An unrecognised `destination` is dropped and no counter moves. This is
  the test that keeps the enum bounded, and it is the one most likely to be written as "falls back to
  other," which would defeat the design.
- **Derivation holds.** The taxonomy constant, each client's allowlist and the backend enum all agree.
  Folded into the companion spec's existing drift test rather than duplicated here. The case that
  matters is a destination added to the taxonomy but missing from the backend enum: it would be
  rejected at ingest and silently unmeasured, which is the one failure this whole design is arranged
  to prevent.
- **No persistence.** Assert no row is written by a nav-event post — a guard against someone later
  adding an events table for convenience.
- **No identifier in the payload.** Assert the request body's accepted shape carries no user, session
  or device field. This is the test that fails loudly if the consent basis is ever quietly eroded.
- **Client failure is silent.** A rejected or failed post does not block navigation or surface an
  error.
- **Rate limiting applies.**

## Out of scope

- **Any identified analytics** — funnels, cohorts, retention, session replay. All require a policy
  change and a consent mechanism, and none is needed to evaluate a navigation change.
- **A third-party analytics vendor.** Would contradict the published cookie statement and add a data
  processor to a product whose privacy posture is a selling point.
- **Dashboards and alerting.** The counters are scraped by the existing Prometheus setup; what to
  chart is an operational task, not a design one.
- **Instrumenting anything beyond navigation.** The three counters answer the taxonomy spec's
  questions. Broader product analytics is a separate decision with a separate privacy analysis.
- **Backfilling a pre-change baseline.** Not possible — nothing was measured before. This is why
  baseline collection is a release gate rather than a preference; see Release dependencies.

## What is not established

- **That aggregate counters will settle the question.** They show whether the usage distribution
  moved. They cannot show *why*, and they cannot distinguish "grouping helped people find Budgets"
  from "Budgets got more useful that month." Attributing a distribution shift to the navigation
  change specifically is an inference this data supports weakly, and a seasonal or feature-driven
  confound is entirely plausible.
- **That anyone will look.** This is now a release gate rather than a hope — a dashboard and a named
  reviewer must exist before implementation begins (see Release dependencies). What is still not
  established is *who*: naming them is the repository owner's call and this spec does not invent one.
- **That the four-week window is the right length.** It is reasoned from the product's month-end
  import cycle rather than measured, because nothing has been measured yet. If the first month-end
  shows the cycle is sharper or flatter than assumed, the window is the thing to revisit.
- **The exact rate limit, batch size and flush cadence.** Planning-level detail, not design.
- Verified rather than assumed: the privacy and cookie policy wording, the absence of any analytics
  SDK in either client, the presence of Micrometer with a Prometheus registry, the existing tag
  allowlist discipline, and the `AuthMetrics` precedent on client-asserted tags.
