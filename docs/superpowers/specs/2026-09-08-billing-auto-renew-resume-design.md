# Auto-renew resume (deferred-dispatch cancellation) — design

Date: 2026-09-08
Status: approved, ready for implementation plan

## Problem

[Billing.tsx](../../../frontend/src/pages/Billing.tsx)'s "Cancel subscription" is one-way. Sid
wants a two-way Auto Renewal control: toggle off to cancel, toggle back on to undo — without a
fresh checkout.

## Why this can't be a thin wrapper on Razorpay

Investigated Razorpay's Subscriptions API directly (5 official doc pages fetched and quoted):

- `cancel_at_cycle_end=true` schedules cancellation; status flips to `cancelled` only at the
  actual cycle end. No parameter to undo it.
- `PATCH /v1/subscriptions/:id` (update) only accepts `plan_id`, `quantity`, `remaining_count`,
  `schedule_change_at`, `offer_id`, `customer_notify` — nothing cancellation-related.
- `POST /v1/subscriptions/:id/resume` is explicitly for **paused** subscriptions only: "Resume is
  only allowed for subscriptions in the paused state."
- `cancel_scheduled_changes` unschedules a pending **plan-change** update, not a cancellation —
  different feature.
- FAQ, verbatim: "there is no direct mechanism to reactivate a cancelled subscription" —
  recommends a new subscription instead.

Razorpay gives no way to reverse a dispatched `cancel_at_cycle_end` call. The only way to offer a
real resume is to not send that call to Razorpay until we're forced to — i.e. defer dispatch, and
let "resume" be a pure local flip for as long as nothing has actually been sent.

## Design

### Data model

New nullable column `cancellation_dispatched_at` (timestamp) on `subscriptions`
(migration `V166__subscription_cancellation_dispatch.sql` — re-confirm V166 is still free on
`origin/main` immediately before creating the file, per repo convention).

- `NULL` — either no cancellation is scheduled, or one is scheduled locally
  (`auto_renew=false`) but not yet sent to Razorpay. Resume is a free, always-succeeds local flip.
- non-`NULL` — the real `cancel_at_cycle_end=true` call already reached Razorpay. Point of no
  return; Razorpay itself provides no way back from here.

### Backend

**`BillingCheckoutService.cancel()`** (existing endpoint, behavior change): stop calling
`gateway.cancelSubscription()` synchronously. Only sets `autoRenew=false` and saves.
`cancellationDispatchedAt` stays null. Everything else about this method (guard clauses, 404/400
cases) is unchanged.

**New `POST /api/v1/billing/resume`** → `BillingCheckoutService.resume(userId)`:

1. `subscriptionRepository.findActiveOrTrial(userId)` → 404 if none (matches `cancel()`).
2. `razorpaySubscriptionId == null` → 400 "This subscription has no billing to resume." (matches
   `cancel()`'s equivalent guard).
3. `autoRenew` already true → no-op success (idempotent double-click/double-tap).
4. `cancellationDispatchedAt != null` → 409 Conflict, "This subscription is already scheduled to
   cancel with Razorpay and can no longer be resumed."
5. Else: `autoRenew=true`, save. No Razorpay call — nothing was ever sent, nothing to undo.

**New `SubscriptionCancellationDispatchSweepService`** — same shape as the existing
[SubscriptionReconciliationSweepService](../../../backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java):
`@Scheduled(fixedDelayString/initialDelayString)`, own
`app.subscription-cancellation-dispatch.sweep.enabled` flag (test profile turns it off, tests call
`sweep()` directly), same `fixedDelay` pattern as the existing sweep (hourly is fine — the 3-day
buffer below is what actually protects against a missed run, not sweep frequency).

Each run, a new repository query finds subscriptions where: `status=ACTIVE`, `autoRenew=false`,
`cancellationDispatchedAt IS NULL`, `razorpaySubscriptionId IS NOT NULL`,
`renewalDate <= CURRENT_DATE + 3`. For each: `gateway.cancelSubscription(id, true)` (same
`cancel_at_cycle_end=true` call `cancel()` used to make immediately — Razorpay still waits for its
own exact cycle boundary regardless of how early the call lands, so there is no early-cutoff risk
from dispatching up to 3 days ahead), stamp `cancellationDispatchedAt=now()`, save, log a
`SubscriptionEvent` (mirrors the audit-trail pattern the reconciliation sweep and
`handleActivated`/`handleCancelled` already follow).

**Residual risk, stated explicitly (Sid approved this trade-off):** today's code dispatches to
Razorpay the instant the user clicks cancel — it can never miss. This design defers dispatch by up
to `renewalDate - 3 days`. If the sweep is down for an extended outage spanning that entire
window, a subscription whose user wants it cancelled could still auto-renew and charge. The 3-day
buffer (chosen over 1-day) is the mitigation Sid picked for this.

**`MySubscriptionDto`** gains `autoRenewResumable: boolean` — computed server-side with the exact
same rule `resume()` enforces (`hasBillingSubscription && !autoRenew && cancellationDispatchedAt
== null`), so the frontend never re-implements or drifts from the business rule.

### Frontend ([Billing.tsx](../../../frontend/src/pages/Billing.tsx))

New "Account Controls" panel, Razorpay-provider rows only (REVENUECAT rows keep today's unchanged
"managed through the App Store/Play Store" message, no toggle). Replaces today's bare "Cancel
subscription" button with a three-state Auto Renewal toggle:

| State | UI | Action |
|---|---|---|
| `autoRenew=true` | Toggle ON | Click off → today's confirm dialog (same copy) → `POST /cancel` |
| `autoRenew=false`, `autoRenewResumable=true` | Toggle OFF, enabled | Click on → `POST /resume` directly, no confirm dialog (non-destructive) → invalidate `my-subscription` query |
| `autoRenew=false`, `autoRenewResumable=false` | Toggle OFF, disabled | Helper text: "Too close to your renewal date to resume — you can subscribe again once this period ends." |

New `billingApi.resume()` in [endpoints.ts](../../../frontend/src/api/endpoints.ts), same style as
the existing `cancel`/`cancelPendingOrder` entries.

Backend DTO/endpoint changes require regenerating the OpenAPI spec and
`generated-types.ts` (the process PR #1156/#1221 already established) before the frontend change
compiles against real generated types.

### Testing

- Backend unit: `cancel()` — assert `gateway.cancelSubscription` is **not** called, only the
  local flip happens. `resume()` — four branches: success, already-on no-op, 409 dispatched, 400
  no-subscription.
- New sweep unit test, mirroring `SubscriptionReconciliationSweepServiceTest`'s pattern if one
  exists: dispatches only qualifying rows, stamps `cancellationDispatchedAt`, calls the gateway
  exactly once per row, is a no-op on a second run over the same rows (idempotent).
- `BillingControllerIT`: extend for `/resume` — success, 409, 400, matching existing IT style in
  that file.
- `Billing.test.tsx`: render + interaction coverage for all three toggle states.

### Explicitly out of scope

- [MySubscriptionScreen.tsx](../../../mobile/src/screens/MySubscriptionScreen.tsx) (mobile) — Sid
  named web `Billing.tsx` only; mobile has no cancel-flow change requested here.
- Admin's immediate-stop cancel path (`cancelAtCycleEnd=false`, spec §6.6) — different endpoint,
  different actor, untouched by this design.
