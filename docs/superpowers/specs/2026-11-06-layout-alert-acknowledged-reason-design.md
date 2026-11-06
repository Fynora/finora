# Layout-review alerts: what an acknowledgement should and should not silence

Status: proposal, awaiting a decision from Sid. No code in this change.

## Why

An operator who resolves a layout's review flag acknowledges every reason on it
(`RegisteredLayout.resolveReview` copies `review_reasons` into `acknowledged_reasons`). From then on
`LayoutReviewService.recordInTransaction` drops any reason whose **name** is in that set, and if
nothing is left it returns without flagging or alerting.

The name is only the rule, for example `VERIFICATION_NOT_PASSED:BALANCE_CHAIN`. It says nothing
about how bad the result was. So a layout acknowledged for a harmless balance-chain warning stays
silent when a later run of the same layout fails the balance chain outright.

## What was checked in the code (read, not run)

- `recordInTransaction` reads `acknowledged_reasons` under the row lock, builds `fresh` from reasons
  not acknowledged, and returns `null` when `fresh` is empty. That return happens before the tier is
  computed and before `last_immediate_alert_at` is touched, so no alert path is reached.
- `alertTier(thisRunsReasons, merged, reports)` is only reached when `fresh` is non-empty. The
  union helper added in #2485 protects the case where one reason is fresh and another is
  acknowledged; it does not help when every reason is acknowledged.
- `LayoutAlertTier.of(reasons, reports)` can already say whether a run is `IMMEDIATE`.

## Not established

- How often an acknowledged layout recurs with an `IMMEDIATE`-tier result in real data. No query has
  been run for this.
- Whether operators acknowledge reasons they consider benign but which the tier function rates
  `IMMEDIATE` (an unexplained warning). If they do, option A below costs one extra email per
  acknowledgement for such layouts.

## Options

**A. One urgent alert per acknowledgement (recommended).** When every reason of a run is
acknowledged, still compute the tier over this run's reasons and reports. If it is `IMMEDIATE` and
no urgent alert has been sent since the acknowledgement, send one urgent alert and record it. Stay
silent afterwards until the next resolve. The flag (`needs_review`) is not raised, so the admin queue
does not change; the email says the reasons were previously acknowledged.

- Stores one timestamp. Resolve already marks the moment, so the check compares
  `last_immediate_alert_at` with a new `acknowledged_at`, or resets `last_immediate_alert_at` to
  NULL on resolve.
- Cost: a layout whose acknowledged warning is permanently rated `IMMEDIATE` produces one extra
  email, once per acknowledgement.
- Gain: a worse result after an acknowledgement is never fully silent.

**B. Severity snapshot at acknowledgement.** Store each reason's severity when it is acknowledged
and alert only when a later run is more severe (warning to failure). Most precise, but needs a new
column, a severity ordering for every reason, and a backfill for acknowledgements that already
exist, with no severity recorded.

**C. Leave as is.** Acknowledgement means "do not tell me about this reason again". Simplest, and it
keeps the silent-escalation hole described above.

## Recommendation

A. It closes the hole with one column or one reset, reuses the tier function and the escalation
claim already shipped in #2486, and fails towards telling a person.

## Design for A

1. In `recordInTransaction`, where `fresh.isEmpty()` returns `null`, first compute
   `tier = LayoutAlertTier.of(reasons, reports)`.
2. If the tier is `DIGEST`, return `null` as today.
3. If `IMMEDIATE`, claim the alert with a single conditional update on `last_immediate_alert_at`
   (NULL, or older than `acknowledged_at`), using the same lock the method already holds. Only the
   claimant sends.
4. Return a `Flagged` carrying `acknowledgedRecurrence = true`, `newlyRaised = false`; `record()`
   sends the urgent email and counts it with the existing `finora.layout_review.alert` counter
   under a new tag value.
5. The email lists the acknowledged reasons by name and says so. Only reason names, positions and
   booleans, as today.

## Privacy

No new data. The alert carries reason names and the same booleans already in the tier alert.

## Test plan

- Acknowledged reason recurs with an `IMMEDIATE` result: one urgent alert, then none.
- Acknowledged reason recurs with a `DIGEST`-tier result: nothing sent.
- Resolve again, recur again: a new single alert.
- Two concurrent runs: exactly one alert.
- Layout flagged for a different, unacknowledged reason: unchanged behaviour (existing tests).
- Migration backfills existing rows so no layout alerts on deploy without a new run.

## Risks

- Extra email for layouts whose acknowledged warning is rated `IMMEDIATE`. Bounded to one per
  acknowledgement.
- An acknowledged layout alerts while absent from the review queue. The email says why.

## Open questions for Sid

1. Option A, B or C?
2. Should the acknowledged-recurrence alert use the `[URGENT]` prefix, or a distinct one?
