# Layout-review alerts: severity tiers and a daily digest

Status: proposed, not implemented. Author: Claude, for Sid. Date: 2026-11-06.

## Why

Every newly flagged statement layout sends the same email, to every admin holding
`LAYOUT_REGISTRY_MANAGE`, with the same subject ("Statement layout needs review — FP-…").
A layout that is merely new, or a three-row warning whose gaps cancel and is explained by
re-ordering, reads exactly like a failed balance check on a real customer's statement. On a
phone, the one that matters is not distinguishable from the ones that do not.

PR #2481 put the verdict in the email (WARNING/FAILED, bad rows out of rows checked, whether the
gaps cancel) and PR #2483 added whether re-ordering explains it. Those facts are enough to sort
alerts without any customer data.

## What was checked in the code (read, not run)

- `LayoutReviewService.recordInTransaction` returns `Flagged.newlyRaised = !wasFlagged`. The alert
  is sent only when that is true, so **one alert per layout per flag**, not per upload.
- The reason string is `VERIFICATION_NOT_PASSED:BALANCE_CHAIN` for both WARNING and FAILED. The
  stored reasons therefore cannot tell the two apart.
- **A gap in today's behaviour:** if a layout is already flagged (for example `NEW_LAYOUT`, or a
  benign warning) and a later upload of it fails the balance check badly, the reasons merge, the
  flag stays raised, `newlyRaised` is false, and **no email is sent**. A worse outcome on an
  already-flagged layout is silent. This spec closes that.
- `LayoutReviewAlertService.alertLayoutNeedsReview` already takes the finding lines and runs on
  `layoutReviewAlertExecutor`. `HoldOverdueEscalationService` is an existing pattern for a scheduled,
  executor-offloaded, marker-before-send job with an `app.….enabled` switch.
- `SmsProvider.send(SmsRequest)` exists and `User` has `phoneNumber`/`phoneVerified`. The 2Factor
  provider's own documentation scopes it to real-time transaction alerts; using it for admin alerts
  is a scope extension (open question 1).

## Not established

- **How many alerts are noise.** There is one alert in this session's evidence. The volume of
  flagged layouts per week was not measured. Phase 1 below changes nothing about delivery so that
  the tiers can be observed before anything is held back.
- Whether the real customer's case (FP-1-095C8AA0) is explained by re-ordering. It will be known the
  next time the check runs on it.

## Goals

1. An alert's subject says how much to care: `[URGENT]` or no prefix, and `[explained]` in a digest.
2. Anything not positively identified as benign is urgent. Unknown reason codes, missing data and
   any error in the tier logic resolve to urgent, never to the digest.
3. Benign alerts wait in one daily email instead of arriving one by one.
4. A worse outcome on an already-flagged layout produces an alert.
5. No customer data in any email: the same counts, positions and yes/no facts as today.

## Non-goals

- No change to verdicts, to what is held, to what the customer sees, or to any row.
- No SMS in phases 1–2 (open question 1).
- No auto-clearing or acknowledging of a layout. A person still decides.
- No per-admin preferences. Recipients stay "everyone holding `LAYOUT_REGISTRY_MANAGE`".

## Design

### Tier function

A pure function `LayoutAlertTier.of(reasons, findings)` in `com.finora.imports`, returning
`IMMEDIATE` or `DIGEST`, computed **from the staging run that is being recorded**, not from stored
reasons.

`DIGEST` only when every one of these holds, otherwise `IMMEDIATE`:

- every reason is one of: `NEW_LAYOUT`, `HOLDER_NAME_UNREADABLE`, or
  `VERIFICATION_NOT_PASSED:BALANCE_CHAIN`;
- for `VERIFICATION_NOT_PASSED:BALANCE_CHAIN`: the finding is `WARNING` (never `FAILED`), the gaps
  cancel, and the reorder test found exactly one reconciling order for every tested window
  (`reconciles: true`) with no window left untested;
- there is no other not-passed finding in any section.

`IMMEDIATE` therefore covers: `FAILED` anywhere; a warning whose gaps do not cancel; a warning that
re-ordering does not explain; `STAGING_FAILED`; `IDENTITY_CONFLICT`; `BLANK_DESCRIPTIONS`; any
reason code this function does not list; any missing or malformed detail.

A layout that is only new (`NEW_LAYOUT` alone) is `DIGEST`.

### Delivery

- `IMMEDIATE`: sent as today, with `[URGENT] ` prepended to the subject.
- `DIGEST`: not sent now. The layout is marked pending (below) and included in the next digest.
- Digest: one email per run to the same recipients, subject `Layout review digest — N layouts`.
  Per layout: fingerprint, profile, reasons, the same finding lines, the analysis reference and a
  link to the queue. Nothing else. No email when nothing is pending.

### Closing the silent-escalation gap

An alert is also sent when this run's tier is `IMMEDIATE` and the layout has had no immediate alert
since it was last flagged or acknowledged, even if the flag was already raised. It is sent once:
`last_immediate_alert_at` is set in the same locked transaction that decides it, so two uploads
finishing together cannot both send. A layout already pending in the digest that becomes urgent is
sent immediately and removed from the digest.

### Data

One Flyway migration adding to `layout_registry`: `digest_pending_since TIMESTAMPTZ NULL`,
`digest_sent_at TIMESTAMPTZ NULL`, `last_immediate_alert_at TIMESTAMPTZ NULL`. The version number is
chosen at implementation time after fetching `origin/main` and listing the migration directory.
Nothing existing is modified.

### Digest job

Scheduled daily at a configured local time (`app.layout-review.digest.cron`, zone
`Asia/Kolkata` by default, `app.layout-review.digest.enabled` default true), offloaded to its own
executor that drops an overlapping tick, like `HoldOverdueEscalationService`.

Claim before send, so two instances cannot both send: one
`UPDATE … SET digest_sent_at = now(), digest_pending_since = NULL WHERE digest_pending_since IS NOT NULL
RETURNING …`. The marker is written whether or not the email succeeds, as the existing escalation
does. A failed digest is logged, not retried, because a retry could repeat it to admins it reached.
That means a failed digest loses the day's benign items from the digest, but never an urgent one.
They remain visible in the review queue, which is unchanged.

### Privacy

The digest and the urgent email carry only what `findingSummaries` already emits: rule name,
outcome, counts, and one-based row positions. The tier function reads the same structured details
the recorder already allowlists. No amounts, dates, narration, file names or user identity.

## Phases

1. **Label only.** Compute the tier and add it to the subject and body of every alert. Delivery is
   unchanged. This shows, from real alerts, how many would have waited.
2. **Digest and escalation.** The migration, the digest job, and the silent-escalation fix.
3. **Optional SMS** for `IMMEDIATE`, if open question 1 is answered yes.

Phases 1 and 2 are separate PRs. Phase 2 does not start until Sid has seen phase 1's labels on
real alerts.

## Test plan

Tier function (unit, table-driven):
1. `NEW_LAYOUT` alone → DIGEST.
2. WARNING, gaps cancel, every window reconciles → DIGEST.
3. FAILED → IMMEDIATE.
4. WARNING, gaps do not cancel → IMMEDIATE.
5. WARNING, gaps cancel, a window does not reconcile → IMMEDIATE.
6. WARNING, gaps cancel, a window untested (any `NOT_TESTED_*`) → IMMEDIATE.
7. Reorder details absent or malformed → IMMEDIATE.
8. Unknown reason code, or `STAGING_FAILED`, `IDENTITY_CONFLICT`, `BLANK_DESCRIPTIONS` → IMMEDIATE.
9. Two sections, one benign and one failing → IMMEDIATE.
10. Null or empty inputs → IMMEDIATE.

Delivery (integration):
11. Urgent alert subject starts `[URGENT]`; digest-tier layout sends nothing now and sets
    `digest_pending_since`.
12. Already-flagged layout, later urgent upload → one immediate email; a second urgent upload →
    none (no repeat).
13. Two concurrent urgent uploads → exactly one email.
14. Pending layout that becomes urgent → sent now and no longer pending.
15. Digest run: one email listing all pending layouts, markers cleared; second run sends nothing;
    two concurrent runs send one.
16. Digest provider failure → logged, marker still cleared, no exception, next run unaffected.
17. No recipients → nothing sent, nothing thrown.
18. Emails contain no amount, date, narration or file name (assert on a fixture that has them).
19. Existing alert tests still pass; `enabled=false` leaves behaviour as in phase 1.

## Risks

- **A benign thing misjudged as urgent** costs one extra email, which is today's behaviour.
- **An urgent thing misjudged as benign** is the real risk. The function is default-urgent and every
  digest condition is a positive finding; tests 3–10 pin it.
- **The digest's single send** is a delay of up to a day for benign items. Anything uncertain is not
  in it.
- **Phase 2 changes who hears about what, when.** Phase 1 first, so Sid can check the labels against
  alerts he has seen.

## Implementation notes

- **Phase 1 shipped** (#2485): the tier function and the labels.
- **Shipped ahead of the digest:** the silent-escalation fix and a counter. An urgent alert is now
  also sent when a layout that was already flagged has had no urgent alert since it was flagged
  (`layout_registry.last_immediate_alert_at < review_flagged_at`, claimed under the row lock). A
  migration backfills currently flagged layouts as already alerted. `finora.layout_review.alert`
  counts alerts by tier and by whether the layout was already flagged.
- **Not done:** the digest itself (`digest_pending_since`, `digest_sent_at`, the scheduled job).
- **Left as it was, on purpose:** a reason an admin acknowledged stays silent. The acknowledgement
  is of the reason's name, so a failed check after an acknowledged warning still sends nothing. That
  is a decision about what acknowledging means, not a bug in the tiers.

## Open questions for Sid

1. **SMS for urgent alerts?** Suggested: yes, later (phase 3), via the existing 2Factor provider to
   admins with a verified phone number. It extends a provider documented for transaction alerts, so
   it needs your call. Default if unanswered: email only.
2. **A warning whose gaps cancel but re-ordering does not explain it** (possibly this customer's
   case). This spec treats it as urgent. The alternative is the digest. Default: urgent, until real
   alerts show how often it is harmless.
3. **Digest time.** Default 09:00 IST.
4. **Recipients.** Same set for both. Default: yes.
