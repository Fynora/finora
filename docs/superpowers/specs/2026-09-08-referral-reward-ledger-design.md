# Referral Reward Ledger — Design

**Status:** Approved by Sid in chat 2026-09-08. Restoring a feature that existed, was deliberately
cut, and is now being reinstated with one correction — not a from-scratch design.

## 1. Background

`326add55` (2026-09-05, "descope Refer & Earn to an MVP relationship tracker") removed reward
crediting, the SUBSCRIBED/REWARDED status lifecycle, wallet crediting, and the admin referral
dashboard, "per product direction." The `referrals.status`/`referrals.reward` columns and the
`wallet_ledger` table were deliberately left in the schema, unused, for exactly this situation —
see that commit's own message.

Separately, the Subscription Billing V1 design spec
(`docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md` §6.7) had already planned
moving the referral trigger onto the Razorpay webhook. Its own §10 flagged a risk to verify before
relying on it: whether `subscription.activated` proves a real charge happened. The V2 plan
(`docs/superpowers/plans/2026-09-05-subscription-billing-v2-upgrade-downgrade-admin.md`, "Spec
deviations") checked this against Razorpay's docs and found `subscription.activated` **can** fire
with zero funds movement (mandate-only) — the correct signal is `subscription.charged`. §6.7 was
explicitly dropped from V2's scope pending a future referral-reward rebuild. This is that rebuild.

Trigger: PR #1223 (open, unmerged, on a branch this session does not own) redesigned `Billing.tsx`
and added two hardcoded reward figures (₹1,250 earned / ₹250 pending) as placeholders, flagged in
its own code comments as fabricated pending a real backend. Reversing the 2026-09-05 descope to
build that backend was confirmed explicitly with Sid before any design work started.

## 2. Goals

- A referral earns its referrer a real, admin-set rupee reward once (and only once) the referred
  user becomes a paying customer.
- The reward lives in the existing `wallet_ledger` table (append-only, already used by nothing
  else) as the balance of record — never a mutable counter.
- Correctness over the previous implementation: trigger off `subscription.charged`
  (real charge confirmed), not `subscription.activated` (can be a zero-charge mandate event).

## 3. Non-goals (explicit, from Sid's answers)

- No fixed/flat/tiered/percentage reward amount is being decided here — amount stays admin-set,
  per referral, at credit time. A future policy change (e.g. moving to an automatic flat amount)
  is separate work and does not change this design's shape.
- No automatic reward-amount computation, no wallet redemption/payout flow (money leaving the
  ledger) — out of scope, same as it was pre-descope.
- No self-service reward payout UI for the referrer — crediting is admin-only, same as before.
- `Billing.tsx`'s placeholder swap is explicitly **not** part of this work — PR #1223 is a separate
  open branch this session does not own. Follow-up after it merges.

## 4. Data model

No new migration. `referrals.status` and `referrals.reward` (V101, nullable/defaulted) and
`wallet_ledger` (V101, already has `WalletLedgerEntry`/`WalletLedgerRepository`, unused since the
descope) are reused as-is.

`Referral` entity gains back:
```java
public static final String STATUS_INVITED = "INVITED";     // never written, kept for schema completeness
public static final String STATUS_REGISTERED = "REGISTERED";
public static final String STATUS_SUBSCRIBED = "SUBSCRIBED";
public static final String STATUS_REWARDED = "REWARDED";
// status: no longer defaulted in the field initializer — redeemCode sets it explicitly
// reward: BigDecimal, mapped (was left unmapped since the descope)
```
`updatedAt` also gets a setter — needed by both new transitions.

`WalletLedgerEntry.REASON_REFERRAL_REWARD` already exists and is unused; this is its first writer.

## 5. Lifecycle and triggers

```
REGISTERED --(referred user's first real charge)--> SUBSCRIBED --(admin credits)--> REWARDED
```

**REGISTERED → SUBSCRIBED, automatic, idempotent:** `ReferralService.onPlanChanged(userId,
newPlanCode)`. No-ops if the user was never referred, the referral isn't currently REGISTERED, or
`newPlanCode` is FREE. Wired from two call sites, both representing a confirmed real charge:

- `RazorpayWebhookDispatcher.handleCharged` — **not** `handleActivated`, per the V2 plan's finding.
  `handleCharged` already fires on every charge including the first, and the no-op guard above
  makes it safe to call unconditionally on every renewal too.
- `RevenueCatWebhookDispatcher.handleInitialPurchase` — the mobile IAP equivalent; the store has
  already charged the user by the time this webhook arrives, so it's a real-charge signal same as
  `subscription.charged`. Renewals go through `handleRenewal`, which this does **not** hook (same
  "once" guard would make it a no-op anyway, but the intent is clearer calling it only from the
  purchase path).

`SubscriptionService.changePlan` (admin-granted complimentary plan) does **not** call this —
matches "a referral should count only when money is received."

**SUBSCRIBED → REWARDED, admin-manual:** `ReferralService.creditReward(referralId, amount, reason,
actingAdminId)`, restored essentially as it existed pre-descope:
- Rejects (409) unless the referral is currently SUBSCRIBED (idempotent — can't double-credit).
- Rejects (409) if `referrerUserId`/`referredUserId` share a device/IP
  (`RefreshTokenRepository.findDistinctLastSeenIpsByUserId`, unchanged method, already exists).
- Writes one `WalletLedgerEntry` (amount, `REASON_REFERRAL_REWARD`, `referenceId = referral.id`).
- Sets `referral.status = REWARDED`, `referral.reward = amount`.
- Audit log `REFERRAL_REWARD_CREDITED` with amount/reason/actorId.

This is a straight restore of the pre-descope `creditReward` — see commit `326add55^`'s
`ReferralService.java` for the exact prior implementation being reinstated.

## 6. API surface

**User-facing** (`/api/v1/referrals`, unchanged base path):
- `GET /my-code` — unchanged.
- `GET /mine` — response shape changes from `{code, referralCount}` to:
  ```java
  record MyReferralDto(UUID referralId, String referredUserFullName, String status,
                        BigDecimal reward, Instant createdAt) {}
  record MyReferralsDto(String code, List<MyReferralDto> referrals, BigDecimal walletBalance) {}
  ```
  (`code` kept at the top level — both web and mobile currently read `data.code` directly for the
  share link; dropping it would be a breaking change for no reason.) `walletBalance` is
  `WalletLedgerRepository.sumAmountByUserId` — a real, computed sum, never stored.

**Admin-facing** (`/api/v1/admin/referrals`, restored, gated by the still-present
`REFERRAL_MANAGEMENT_VIEW`/`_MANAGE` permissions — never dropped from `role_permissions`):
- `GET ?page&size` → `PagedResponse<AdminReferralSummaryDto>` (referrer + referred identity, status,
  reward, createdAt) — same shape as pre-descope.
- `POST /{referralId}/credit` body `{amount, reason}` → credits via `creditReward`.

## 7. Frontend

- **Web `Referrals.tsx`**: adds an "Earned" stat (₹ `walletBalance`) and a "Pending" stat (**count**
  of `SUBSCRIBED` referrals, not a rupee figure — there's no amount until an admin sets one, per
  Sid's answer). Per-referral list becomes viewable (name, status, reward if any, date).
- **Mobile `ReferralsScreen.tsx`**: same data, native layout — mirrors whatever the web page ends
  up doing, consistent with how this codebase has kept the two in parity elsewhere (Refer & Earn,
  Advanced Reports).
- **Admin portal**: restore `pages/Referrals.tsx` (list + credit-amount action), its route, and its
  `Sidebar.tsx` entry — same shape as what `326add55` removed.
- **`Billing.tsx`**: untouched. Explicitly deferred to a follow-up PR after #1223 merges.

## 8. Testing

- Restore and adapt `ReferralServiceTest` (had full coverage pre-descope: `onPlanChanged`
  transitions and no-ops, `creditReward` success/409s, self-referral device/IP rejection).
- **New test, not in the pre-descope version:** `onPlanChanged` fires from `handleCharged` and does
  **not** fire from `handleActivated` alone — this is the exact correction this design makes over
  the original spec, so it needs its own explicit coverage, not just a restore.
- Restore `AdminReferralControllerIT` (permission gating, credit flow, 409s).
- Web/mobile: extend `Referrals.test.tsx` / `ReferralsScreen.test.tsx` for the new stats; restore
  admin-portal `Referrals.test.tsx`.

## 9. Out of scope for this PR (explicit, not silent)

- `Billing.tsx` placeholder swap (blocked on PR #1223 merging first).
- Reward amount policy (flat/tiered/percentage) — stays a future product decision.
- Any change to `AccountPurgeSweepService` — it already deletes `wallet_ledger`/`referrals` rows by
  user id; nothing about this design changes that.
