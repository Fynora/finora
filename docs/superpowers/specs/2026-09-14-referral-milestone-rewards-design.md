# Referral milestone rewards — design

Status: approved by Sid, pending write-up as an implementation plan.
Supersedes: nothing existing (additive to the live referral program — see
`backend/src/main/java/com/finora/service/ReferralService.java` and PR #1239).

## 1. Problem and constraint

The referral program is live (code issuance, redemption at signup, SUBSCRIBED tracking, admin-manual
cash reward via `creditReward`/`wallet_ledger`). Sid does not want to fund cash rewards at scale.
Goal: replace cash as the *default* reward with free subscription time, funded from margin instead of
cash, while keeping the existing cash path available for a manual one-off case.

## 2. Milestones

**Revised 2026-09-14, after product review of the first draft of this spec** — the original design
had Plus and Premium share one counter that reset on any redemption, forcing an either/or choice at 3
(redeem Plus now, forfeiting a shot at Premium, or hold out and lose Plus entirely). Product review
called this out as adding friction and discarding progress users had already earned. Revised model:

- **Two independent counters**, both incremented together by the same event (a referral reaching
  `Referral.STATUS_SUBSCRIBED` — registered-only referrals do not count, matching the existing
  SUBSCRIBED gate `creditReward` already uses, and avoiding a reward for a throwaway signup):
  `plusMilestoneCounter` and `premiumMilestoneCounter`.
- At `plusMilestoneCounter` ≥ 3: user may redeem **1 month of Plus**. Redeeming resets *only*
  `plusMilestoneCounter` to 0 — `premiumMilestoneCounter` is untouched and keeps climbing toward its
  own threshold independently.
- At `premiumMilestoneCounter` ≥ 7: user may redeem **1 month of Premium**, independently of whether
  Plus has ever been redeemed. Redeeming resets *only* `premiumMilestoneCounter` to 0.
- Nothing is ever forfeited: a user who never redeems Plus and keeps referring past 7 still has both
  redeem options available (Plus at 3, Premium at 7) simultaneously and can act on either or both.
- Both counters repeat indefinitely — Plus becomes redeemable again every 3 more referrals since its
  own last redemption (3, 6, 9, ...), Premium every 7 more since its own last redemption (7, 14, 21,
  ...). There is no cap on how many times a user can earn either reward.
- Self-referral guard: `ReferralService`'s existing `sharesADeviceOrIp` check (currently only run
  inside admin-manual `creditReward`) moves to the point a referral would count toward either
  counter. A referral flagged as a likely self-referral increments neither counter, full stop —
  redemption is now self-service with no admin in the loop, so the fraud check can no longer happen
  only at the old manual-approval step.
- The redeem action itself stays manual (a button click) — product review considered removing it
  entirely (auto-granting the instant a threshold is crossed) but chose to keep the click, just
  without the either/or forfeiture that made it feel punitive.

## 3. Grant model — overlay, not billing mutation

**Constraint that shapes this section:** Fynora can push back a Razorpay-billed user's renewal date
server-side, but has no API to touch a RevenueCat (Apple/Google IAP)-billed user's billing cycle at
all (`SubscriptionService.cancelPaidSubscription`'s own doc comment already establishes this for the
unrelated case of releasing a mandate). So "grant a free month" cannot be implemented as literally
skipping a real charge for every user — it has to work identically regardless of payment provider.

**Design: a new `ReferralGrant` entity, checked by `EntitlementService` alongside the real
subscription, never touching billing.**

```
ReferralGrant
  id
  userId
  tier            PLUS | PREMIUM
  status          PENDING | ACTIVE | EXPIRED
  earnedFromReferralId   (the referral whose SUBSCRIBED transition completed this milestone)
  activatedAt
  expiresAt
```

Created as `PENDING` the instant a user redeems a milestone. A nightly sweep (same shape as the
existing `SubscriptionCancellationDispatchSweepService` / `SubscriptionReconciliationSweepService`)
is the only place state changes:

- For each user with a `PENDING` grant, compute their current effective tier = `max(real subscription
  tier, any currently-ACTIVE grant's tier)`. If the `PENDING` grant's tier is *higher* than that, it
  activates: `status = ACTIVE`, `activatedAt = now`, `expiresAt = now + 1 month`.
- If the `PENDING` grant's tier is *not* higher (the user already has that tier or better, whether by
  paying for it or via another still-active grant), it stays queued. This is the **stacking** rule —
  earned rewards are never wasted and never cashed out, they simply wait their turn. FIFO: oldest
  `PENDING` grant activates first once there's room.
- When an `ACTIVE` grant's `expiresAt` passes, it flips to `EXPIRED`, and the sweep immediately checks
  whether the next `PENDING` grant (if any) can now activate.

This uniformly covers every case Sid specified during design:
- FREE user redeems Plus → activates immediately (0 < Plus), reverts to FREE when it expires (or the
  next queued grant activates in its place).
- FREE user redeems Premium (reached 7) → activates immediately, same as above at the higher tier.
- Plus-paying user redeems the 3-referral Plus reward → tier is a no-op against their own paid Plus →
  queued, sits there until they ever downgrade/cancel below Plus, at which point it activates and
  gives them a bonus month before finally dropping to FREE.
- Plus-paying user redeems the 7-referral Premium reward → real upgrade (Plus < Premium) → activates
  immediately, giving them a free month of Premium, then reverts to their real paid Plus tier when it
  expires (not FREE — their real subscription is untouched underneath).
- Premium-paying user redeems either reward → no tier is higher than what they already have → queued,
  same as the Plus-payer case above.

No cash conversion at any point — this was explicitly decided against (Sid: "stack them, don't cash
out at premium"). The existing `creditReward` cash/wallet path is untouched and stays available as a
manual, admin-only, dormant action — not wired into this flow at all.

## 4. Entitlement integration

`EntitlementService.hasEntitlement` and `planCodeFor` are the single choke point every paywall gate in
the codebase already reads through (FINO_AI, Gmail Sync, Investment Insights, multi-month import,
etc. — confirmed by reading the service, not assumed). Both change from reading
`subscriptionRepository.findActiveOrTrial(userId).planId` alone to reading
`max(that plan's tier, any ACTIVE ReferralGrant's tier for this user)`. This is the *only* change
needed for referral-earned Plus/Premium to unlock real features — no other service changes, because
they all already go through this one.

## 5. Notifications (email + in-app, `NotificationCategory.FINANCIAL`)

Three new `NotificationType` values (each needs its `notification_templates` row per the existing
convention — a type with no template row dead-letters):

- `REFERRAL_FRIEND_SUBSCRIBED` — fired from `ReferralService.onPlanChanged`'s existing SUBSCRIBED
  transition, for every referral, not just milestone ones. Body includes both counters' progress
  (e.g. "2/3 toward Plus, 5/7 toward Premium").
- `REFERRAL_MILESTONE_REACHED` — fired independently whenever either counter crosses its own
  threshold (3 for Plus, 7 for Premium) — up to two of these can fire off the same referral event if
  both happen to cross at once. Tells the user that tier is now redeemable.
- `REFERRAL_GRANT_ACTIVATED` — fired by the nightly sweep the moment a grant flips `PENDING` →
  `ACTIVE`. This is deliberately *not* fired at the moment of redemption — for a queued grant that
  could be sent long before the reward is actually usable, which would be misleading ("free month
  active" when it isn't yet). Body includes the tier and the `expiresAt` date.

## 6. UI

### 6.1 Redeem flow and progress visibility (web `Referrals.tsx`, mobile `ReferralsScreen.tsx`)

**Revised 2026-09-14** — product review flagged the first draft as having no emotional momentum
(a bare 0/1/2/3 count with nothing shown between milestones) and asked for persistent progress
visualization rather than a static counter that only surfaces at the threshold.

- Always show both progress indicators, not just once a threshold is hit: "X/3 toward Plus" and
  "Y/7 toward Premium," e.g. as two small progress bars. These never disappear or reset to a blank
  state — they're the running, permanent view onto `plusMilestoneCounter`/`premiumMilestoneCounter`.
- Once a counter reaches its threshold, its progress bar is replaced by a redeem card for that tier
  only ("Redeem 1 month of Plus") — the *other* tier's progress bar keeps showing independently right
  alongside it. Both can be visible and actionable at the same time (e.g. "Redeem 1 month of Plus"
  card next to a "5/7 toward Premium" bar).
- No "keep referring" dismiss affordance is needed — since redeeming Plus no longer costs any
  progress toward Premium, there's no decision being asked of the user beyond "redeem when you want
  the reward," which the redeem button itself already is.

### 6.2 Plan badge, next to the FYNORA brand mark

- **Web**: `Sidebar.tsx` renders `BrandMark` at the top; add a small pill badge next to it, sourced
  from `entitlementsFor`'s existing `planCode` (which will already reflect an ACTIVE referral grant
  once §4 ships — no separate query needed). FREE shows no badge, Plus/Premium show their pill.
- **Mobile**: `BrandMark` currently only renders on `AuthScreenLayout` (login/register) — there is no
  persistent in-app header today. Per Sid's direction, add a small header bar to `DashboardScreen`
  carrying the brand mark + badge, mirroring the web placement rather than putting it on `MoreScreen`.

### 6.3 Badge visual spec

No dedicated "Plus" color token exists in `frontend/src/index.css` — only `--color-premium`
(`#0F4C3F`) / `--color-premium-fixed` (`#34A788`) for Premium. Decision: Plus reuses the base
graphite/cream pair (no green), so Premium stays visually the "special" one and Plus reads as clean
and neutral. Concretely: Plus badge = `#2E2D2A` background, `#F4F1EC` text, `#D9D5CB` border; Premium
badge = `--color-premium-bg`/`--color-premium` (existing tokens).

### 6.4 Upgrade animation (fires once, at the moment a grant activates — i.e. alongside the
`REFERRAL_GRANT_ACTIVATED` notification, not at redemption)

Validated live in the browser during design (mocked against the real sidebar dark background
`#1B1A18` and the real token palette — not just described):

- **Premium**: full combined sequence — (1) a teal (`--color-premium-fixed`) glow sweeps once down
  the sidebar's edge, (2) ~0.35s later the badge pops in (spring-bounce scale) as the glow reaches it,
  with a white shine sweeping across it twice and a thin ring pulsing outward twice, (3) simultaneous
  with the pop, a 28-piece confetti burst in cream/teal/graphite fires from the badge and falls/fades
  over ~1.4s. Total sequence ≈ 1.5s.
- **Plus**: pop + one shine sweep only, recolored to the graphite/cream badge palette — no sidebar
  glow wave, no confetti. Deliberately smaller than Premium's, so reaching Premium later still feels
  like the bigger moment (Sid: "toned down").
- Implementation should follow the codebase's existing motion conventions in
  `frontend/src/index.css` (`floatSlow`/`gradientShift`/`statement-scan` etc.), including the existing
  `prefers-reduced-motion` guard pattern used for other one-off animations, rather than introducing a
  new animation library.
- Mobile equivalent: same beats (pop/shine for both tiers, glow+confetti added for Premium only),
  implemented with React Native's `Animated`/Reanimated equivalent of the same timings — exact
  mobile implementation is a task for the implementation plan, not fully specified here.

## 7. Out of scope for this change

- Any change to the existing admin-manual cash reward (`creditReward`) — stays exactly as-is, unused
  by this flow.
- Retroactively reconsidering a referral that already counted toward a milestone if the referred
  friend later cancels/downgrades — not addressed; counted-is-counted, matching how `REWARDED` already
  behaves today.
- A cap on how many times a user can cycle through 3/7 — none. Product review (2026-09-14) flagged
  that unlimited cycling deserves a unit-economics pass before launch (expected referral conversion
  rate, cost per Plus/Premium month granted, subscription cannibalization risk) — noted here as a
  pre-launch action item, not addressed by this spec or its implementation plan, since it needs real
  conversion-rate assumptions Sid supplies, not a code change.
- A dedicated "you unlocked X" full-screen congratulations moment beyond the existing
  `REFERRAL_GRANT_ACTIVATED` notification and the always-visible progress bars in §6.1 — product
  review suggested one; deferred as optional future polish rather than blocking this launch.
- A prestige/status layer (the badge signals the user's own plan to themselves, not to others) —
  explicitly a different product from this one; see the parked Identity Engine proposal for that
  discussion.
