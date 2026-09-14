# Referral Milestone Rewards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace cash as the default referral reward with milestone-based free subscription time (3 SUBSCRIBED referrals → 1 month Plus, 7 → 1 month Premium), implemented as a non-billing "overlay grant" `EntitlementService` already checks, plus the UI (redeem flow, plan badge, upgrade animation) to go with it.

**Architecture:** A new `ReferralGrant` entity (PENDING/ACTIVE/EXPIRED) is created when a user redeems a milestone, and a nightly sweep activates the oldest queued grant once the user's real subscription no longer already covers that tier — never touching Razorpay/RevenueCat billing. `EntitlementService` becomes the single place that resolves `max(real plan tier, active grant tier)`, so every existing paywall gate picks up referral-earned access automatically. The existing admin-manual cash reward path (`creditReward`) is untouched and stays dormant.

**Tech Stack:** Spring Boot / JPA / Flyway (backend), React + Tailwind (web), React Native + Expo (mobile).

**Spec:** [docs/superpowers/specs/2026-09-14-referral-milestone-rewards-design.md](../specs/2026-09-14-referral-milestone-rewards-design.md)

## Global Constraints

- Milestone counting only fires on `Referral.STATUS_SUBSCRIBED` (a friend must pay, not just register).
- The self-referral device/IP fraud check moves from `creditReward` (admin-manual) into the counter-increment path (self-service, no admin in the loop).
- Grants are never cash-converted; a reward that would be a no-op against what the user already has is queued (`PENDING`), never wasted, activated FIFO once there's room.
- `ReferralGrant` never mutates `Subscription`'s own billing fields — this is a strict requirement, not a style preference: Fynora has no API to touch a RevenueCat-billed user's renewal date at all.
- Redeeming a milestone (either tier) resets the counter to 0. Reaching 7 without redeeming at 3 forfeits the Plus tier — only Premium is redeemable at that point.
- Next available Flyway version is **V206** (confirmed via `ls backend/src/main/resources/db/migration`, numerically sorted, against a worktree branched fresh from `origin/main` — re-check this against `origin/main` again immediately before Task 1 if any time has passed, per this repo's own migration-collision rule in CLAUDE.md).
- No AI attribution in any commit message (repo-wide rule).
- Existing `creditReward`/`wallet_ledger` cash path: zero changes, not wired into this flow.

---

## Task 1: Database migration — `referral_grants`, two independent milestone counters, notification copy

**Files:**
- Create: `backend/src/main/resources/db/migration/V206__referral_milestone_rewards.sql`

**Interfaces:**
- Produces: table `referral_grants` (columns: `id`, `user_id`, `tier`, `status`, `earned_from_referral_id`, `activated_at`, `expires_at`, `created_at`, `updated_at`); columns `referral_codes.plus_milestone_counter` and `referral_codes.premium_milestone_counter` (two independent counters — see the spec's revised section 2 on why this is not one shared counter); three new `notification_templates` rows per channel for `REFERRAL_FRIEND_SUBSCRIBED` / `REFERRAL_MILESTONE_REACHED` / `REFERRAL_GRANT_ACTIVATED`.

This is a schema-only task (no Java yet) — verified by booting the app against it in Task 2's test run, not by a dedicated migration test (this repo has none; Flyway itself fails the boot if the SQL is invalid, which is the check).

- [ ] **Step 1: Fetch origin and re-confirm V206 is free**

```bash
git fetch origin
ls backend/src/main/resources/db/migration | sed -E 's/^V([0-9]+)__.*/\1 &/' | sort -n | tail -3
git diff --stat HEAD origin/main -- backend/src/main/resources/db/migration
```

Expected: highest existing version is V205, and the diff against `origin/main` is empty (no one else has added a migration on `origin/main` since this worktree branched). If it is NOT empty, pick the next free number instead of V206 and update every reference to "V206" in this plan.

- [ ] **Step 2: Write the migration**

```sql
-- Referral milestone rewards (design spec at docs/superpowers/specs/
-- 2026-09-14-referral-milestone-rewards-design.md). Replaces cash as the default referral reward
-- with free subscription time -- ReferralService.creditReward/wallet_ledger (V168) are untouched
-- and stay available as a dormant, admin-only fallback.

-- Two INDEPENDENT counters, not one shared counter (design spec section 2, revised after product
-- review: the original single-counter design forced an either/or choice at 3 that discarded
-- progress toward 7 if the user redeemed early). Both increment together on the same event (a
-- referral reaching SUBSCRIBED, see ReferralService.incrementMilestoneCounterIfEligible); each
-- resets to 0 ONLY when its own tier is redeemed, so redeeming Plus never costs any progress
-- toward Premium and vice versa. Both live on referral_codes (one row per referrer already)
-- rather than being computed by scanning referrals on every read.
ALTER TABLE referral_codes ADD COLUMN plus_milestone_counter INT NOT NULL DEFAULT 0;
ALTER TABLE referral_codes ADD COLUMN premium_milestone_counter INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN referral_codes.plus_milestone_counter IS
    'Referrals reaching SUBSCRIBED since Plus was last redeemed (or ever, if never redeemed). Resets to 0 only when Plus is redeemed -- never affected by redeeming Premium.';
COMMENT ON COLUMN referral_codes.premium_milestone_counter IS
    'Referrals reaching SUBSCRIBED since Premium was last redeemed (or ever, if never redeemed). Resets to 0 only when Premium is redeemed -- never affected by redeeming Plus.';

-- A referral-earned free month of Plus or Premium -- an overlay EntitlementService checks
-- alongside the user's real subscription, never a mutation of subscriptions' own billing fields
-- (spec section 3: Fynora has no API to touch a RevenueCat-billed user's renewal date, so the
-- grant has to work identically regardless of payment provider). Not soft-deletable: a
-- state-tracking row updated in place by ReferralGrantSweepService's one-directional
-- PENDING -> ACTIVE -> EXPIRED transitions, same reasoning as referrals' own lack of a
-- deleted_at/version pair.
CREATE TABLE referral_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    tier VARCHAR(10) NOT NULL,
    status VARCHAR(10) NOT NULL,
    -- ON DELETE CASCADE: AccountPurgeSweepService hard-deletes a purged user's own referrals
    -- (ReferralRepository.deleteByReferrerUserId) -- this row always points to one of THAT same
    -- user's referrals (see ReferralService.redeemMilestone), so it must not survive as a dangling
    -- FK once the referral it points to is gone. The purge sweep also calls
    -- ReferralGrantRepository.deleteByUserId directly (Task 2) as the primary cleanup path, same
    -- as every other user-owned table in this codebase -- this CASCADE is a backstop, not the
    -- intended path.
    earned_from_referral_id UUID NOT NULL REFERENCES referrals(id) ON DELETE CASCADE,
    activated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_referral_grants_user_id ON referral_grants(user_id);
-- ReferralGrantSweepService's own candidate queries: all PENDING grants (to find who has one to
-- activate) and all ACTIVE grants past expiry (to expire them).
CREATE INDEX idx_referral_grants_status ON referral_grants(status);
-- At most one ACTIVE grant per user at a time -- enforced by the database, same reasoning as
-- subscriptions' own idx_subscriptions_one_active_per_user (V99): the sweep's own re-check logic
-- assumes this invariant, not just trusts it.
CREATE UNIQUE INDEX idx_referral_grants_one_active_per_user ON referral_grants(user_id)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN referral_grants.tier IS 'PLUS or PREMIUM -- see ReferralGrant.TIER_PLUS/TIER_PREMIUM.';
COMMENT ON COLUMN referral_grants.status IS 'PENDING, ACTIVE, or EXPIRED -- see ReferralGrant.STATUS_*.';
COMMENT ON COLUMN referral_grants.earned_from_referral_id IS
    'Informational only -- which referral pushed the counter to this threshold. Never read by ReferralGrantSweepService or EntitlementService.';

-- Notification copy for the three new types (see NotificationType's own doc comment: a type with
-- no active template row here cannot be rendered). {{plusCount}}/{{premiumCount}}/{{tier}}/
-- {{expiresAt}} are plain {{placeholder}} substitution, same as every other row in this table.
-- plusCount/premiumCount are the two INDEPENDENT counters (design spec section 2) -- both always
-- present together, since the two tiers no longer share one progress track.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'EMAIL',
     'A friend just subscribed!',
     'Great news — a friend you referred just subscribed to Fynora. You''re now {{plusCount}}/3 '
     'toward your next Plus reward and {{premiumCount}}/7 toward your next Premium reward.'),
    (gen_random_uuid(), 'REFERRAL_FRIEND_SUBSCRIBED', 'PUSH',
     'Referral progress',
     '{{plusCount}}/3 toward Plus, {{premiumCount}}/7 toward Premium.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'EMAIL',
     'You earned a reward!',
     'You''ve referred enough friends to redeem 1 month of Fynora {{tier}} for free. Open Fynora to '
     'redeem it now -- your progress toward any other reward is untouched.'),
    (gen_random_uuid(), 'REFERRAL_MILESTONE_REACHED', 'PUSH',
     'Reward unlocked',
     'Redeem 1 month of {{tier}} whenever you''re ready.'),
    (gen_random_uuid(), 'REFERRAL_GRANT_ACTIVATED', 'EMAIL',
     'Your free month of {{tier}} is active',
     'Your free month of Fynora {{tier}} is now active, until {{expiresAt}}. Enjoy the extra '
     'features!'),
    (gen_random_uuid(), 'REFERRAL_GRANT_ACTIVATED', 'PUSH',
     '{{tier}} is active',
     'Your free month of {{tier}} runs until {{expiresAt}}.');
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V206__referral_milestone_rewards.sql
git commit -m "feat(db): add referral_grants table and two independent milestone counters"
```

---

## Task 2: `ReferralGrant` entity, repository, `ReferralCode`'s two milestone counters, purge-sweep wiring

**Files:**
- Create: `backend/src/main/java/com/finora/entity/ReferralGrant.java`
- Create: `backend/src/main/java/com/finora/repository/ReferralGrantRepository.java`
- Modify: `backend/src/main/java/com/finora/entity/ReferralCode.java`
- Modify: `backend/src/main/java/com/finora/service/AccountPurgeSweepService.java`
- Test: `backend/src/test/java/com/finora/service/AccountPurgeSweepServiceTest.java`

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: `ReferralGrant` (constants `TIER_PLUS`, `TIER_PREMIUM`, `STATUS_PENDING`, `STATUS_ACTIVE`, `STATUS_EXPIRED`, static `int tierRank(String)`); `ReferralGrantRepository` with `findByUserIdAndStatus(UUID, String)`, `findFirstByUserIdAndStatusOrderByCreatedAtAsc(UUID, String)`, `findByStatusAndExpiresAtBefore(String, Instant)`, `findDistinctUserIdsByStatus(String)`, `findByUserIdOrderByCreatedAtDesc(UUID)`, `deleteByUserId(UUID)`; `ReferralCode.getPlusMilestoneCounter()`/`setPlusMilestoneCounter(int)`/`getPremiumMilestoneCounter()`/`setPremiumMilestoneCounter(int)`. Later tasks (3, 6, 7) depend on all of these exact names.

- [ ] **Step 1: Write the entity**

```java
// backend/src/main/java/com/finora/entity/ReferralGrant.java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A referral-earned free month of Plus or Premium (design spec at docs/superpowers/specs/
 * 2026-09-14-referral-milestone-rewards-design.md, section 3) -- an overlay EntitlementService
 * checks alongside the user's real subscription, never a mutation of Subscription's own billing
 * fields (Fynora has no API to touch a RevenueCat-billed user's renewal date, so the grant has to
 * work identically regardless of payment provider). Not extending BaseEntity: a state-tracking row
 * updated in place by ReferralGrantSweepService's one-directional PENDING -> ACTIVE -> EXPIRED
 * transitions, same reasoning as Referral's own class comment.
 */
@Entity
@Table(name = "referral_grants")
public class ReferralGrant {

    public static final String TIER_PLUS = "PLUS";
    public static final String TIER_PREMIUM = "PREMIUM";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * Shared tier comparison used by EntitlementService, ReferralService, and
     * ReferralGrantSweepService -- one place rather than three copies of the same ranking, so a
     * future tier (if one is ever added) only needs to change here. A plan code not in this list
     * (including null and "FREE") ranks lowest.
     */
    public static int tierRank(String tierOrPlanCode) {
        if (TIER_PREMIUM.equals(tierOrPlanCode)) return 2;
        if (TIER_PLUS.equals(tierOrPlanCode)) return 1;
        return 0;
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String tier;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "earned_from_referral_id", nullable = false)
    private UUID earnedFromReferralId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getEarnedFromReferralId() { return earnedFromReferralId; }
    public void setEarnedFromReferralId(UUID earnedFromReferralId) { this.earnedFromReferralId = earnedFromReferralId; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: Write the repository**

```java
// backend/src/main/java/com/finora/repository/ReferralGrantRepository.java
package com.finora.repository;

import com.finora.entity.ReferralGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralGrantRepository extends JpaRepository<ReferralGrant, UUID> {

    /** At most one row can ever match -- idx_referral_grants_one_active_per_user (V206). */
    Optional<ReferralGrant> findByUserIdAndStatus(UUID userId, String status);

    /** ReferralGrantSweepService's FIFO activation order. */
    Optional<ReferralGrant> findFirstByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, String status);

    /** ReferralGrantSweepService's expiry sweep. */
    List<ReferralGrant> findByStatusAndExpiresAtBefore(String status, Instant expiresAt);

    /** ReferralGrantSweepService's candidate scan -- every user with at least one queued grant. */
    @Query("select distinct g.userId from ReferralGrant g where g.status = :status")
    List<UUID> findDistinctUserIdsByStatus(@Param("status") String status);

    /** GET /api/v1/referrals/mine -- a user's own grant history, newest first. */
    List<ReferralGrant> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** AccountPurgeSweepService. */
    void deleteByUserId(UUID userId);
}
```

- [ ] **Step 3: Add the two counter fields to `ReferralCode`**

```java
// backend/src/main/java/com/finora/entity/ReferralCode.java -- add alongside the existing fields
    @Column(name = "plus_milestone_counter", nullable = false)
    private int plusMilestoneCounter = 0;

    @Column(name = "premium_milestone_counter", nullable = false)
    private int premiumMilestoneCounter = 0;
```

```java
// and alongside the existing getters/setters
    public int getPlusMilestoneCounter() { return plusMilestoneCounter; }
    public void setPlusMilestoneCounter(int plusMilestoneCounter) { this.plusMilestoneCounter = plusMilestoneCounter; }
    public int getPremiumMilestoneCounter() { return premiumMilestoneCounter; }
    public void setPremiumMilestoneCounter(int premiumMilestoneCounter) { this.premiumMilestoneCounter = premiumMilestoneCounter; }
```

- [ ] **Step 4: Write the failing purge-sweep test**

```java
// backend/src/test/java/com/finora/service/AccountPurgeSweepServiceTest.java -- add near the
// existing referralCodeRepository/referralRepository/walletLedgerRepository field block
    private ReferralGrantRepository referralGrantRepository;
```

```java
// in setUp(), alongside the existing `referralCodeRepository = mock(...)` lines
        referralGrantRepository = mock(ReferralGrantRepository.class);
```

```java
// in the AccountPurgeSweepService constructor call, add referralGrantRepository as a new
// argument immediately after referralCodeRepository (matches the order it will be added to the
// constructor in Step 5)
                referralCodeRepository, referralGrantRepository, referralRepository, walletLedgerRepository,
```

```java
// add this assertion in the same test method that already asserts the other referral-table
// deletions (the one containing "verify(referralCodeRepository).deleteByUserId(userId);")
        verify(referralGrantRepository).deleteByUserId(userId);
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd backend && mvn -Dtest=AccountPurgeSweepServiceTest -DfailIfNoTests=false test`
Expected: FAIL — compile error (`ReferralGrantRepository` unresolved) or a constructor-arity mismatch, since `AccountPurgeSweepService` doesn't accept the new parameter yet.

- [ ] **Step 6: Wire `ReferralGrantRepository` into `AccountPurgeSweepService`**

```java
// backend/src/main/java/com/finora/service/AccountPurgeSweepService.java -- add the import
import com.finora.repository.ReferralGrantRepository;
```

```java
// add the field, alongside the existing referralCodeRepository/referralRepository/walletLedgerRepository fields
    private final ReferralGrantRepository referralGrantRepository;
```

```java
// add the constructor parameter, immediately after referralCodeRepository, and assign it in the
// body immediately after `this.referralCodeRepository = referralCodeRepository;`
                                     ReferralCodeRepository referralCodeRepository,
                                     ReferralGrantRepository referralGrantRepository,
                                     ReferralRepository referralRepository,
```

```java
        this.referralCodeRepository = referralCodeRepository;
        this.referralGrantRepository = referralGrantRepository;
        this.referralRepository = referralRepository;
```

```java
// in the purge method, immediately before the existing
// `referralRepository.deleteByReferrerUserId(userId);` line -- must run BEFORE that line, since
// referral_grants.earned_from_referral_id points at one of this same user's own referrals
// (ReferralService.redeemMilestone), and the ON DELETE CASCADE added in V206 is a backstop, not
// the intended path
            referralGrantRepository.deleteByUserId(userId);
            referralCodeRepository.deleteByUserId(userId);
            referralRepository.deleteByReferrerUserId(userId);
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && mvn -Dtest=AccountPurgeSweepServiceTest -DfailIfNoTests=false test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/entity/ReferralGrant.java \
        backend/src/main/java/com/finora/repository/ReferralGrantRepository.java \
        backend/src/main/java/com/finora/entity/ReferralCode.java \
        backend/src/main/java/com/finora/service/AccountPurgeSweepService.java \
        backend/src/test/java/com/finora/service/AccountPurgeSweepServiceTest.java
git commit -m "feat(backend): add ReferralGrant entity/repository and wire it into account purge"
```

---

## Task 3: `NotificationType` additions

**Files:**
- Modify: `backend/src/main/java/com/finora/notification/domain/NotificationType.java`

**Interfaces:**
- Produces: `NotificationType.REFERRAL_FRIEND_SUBSCRIBED`, `REFERRAL_MILESTONE_REACHED`, `REFERRAL_GRANT_ACTIVATED`. Task 4 and Task 7 depend on these exact names (and on Task 1's migration having already seeded their template rows).

This is a one-line-per-value enum change with no unit of its own to test in isolation (the existing convention has no `NotificationTypeTest`) — its correctness is proven by Task 4's and Task 7's own tests successfully referencing these constants, and ultimately by `NotificationDispatcherIT`/`DatabaseTemplateRenderer` finding a template row for them at runtime (already seeded in Task 1). No separate test step here; fold it into the next task's compile.

- [ ] **Step 1: Add the three enum values**

```java
// backend/src/main/java/com/finora/notification/domain/NotificationType.java
public enum NotificationType {
    PASSWORD_CHANGED,
    IMPORT_STATEMENT_READY,
    IMPORT_STATEMENT_HELD,
    // Referral milestone rewards (design spec at docs/superpowers/specs/
    // 2026-09-14-referral-milestone-rewards-design.md). Template rows for all three, per channel,
    // were seeded in V206 alongside this addition -- see this enum's own class comment on why
    // that has to happen together.
    REFERRAL_FRIEND_SUBSCRIBED,
    REFERRAL_MILESTONE_REACHED,
    REFERRAL_GRANT_ACTIVATED
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/finora/notification/domain/NotificationType.java
git commit -m "feat(backend): add referral milestone notification types"
```

---

## Task 4: `ReferralService` — two independent counters, fraud-check move, redemption, expanded `myReferrals()`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReferralService.java`
- Modify: `backend/src/main/java/com/finora/repository/ReferralRepository.java`
- Modify: `backend/src/main/java/com/finora/dto/ReferralDtos.java`
- Test: `backend/src/test/java/com/finora/service/ReferralServiceTest.java`

**Interfaces:**
- Consumes: `ReferralGrant`/`ReferralGrantRepository` (Task 2), `NotificationType.REFERRAL_FRIEND_SUBSCRIBED`/`REFERRAL_MILESTONE_REACHED` (Task 3).
- Produces: `ReferralService.redeemMilestone(UUID userId, String tier)` (throws `ApiException` 400/409); `ReferralDtos.RedeemMilestoneRequest(String tier)`; `ReferralDtos.ReferralGrantDto(UUID id, String tier, String status, Instant activatedAt, Instant expiresAt)`; `MyReferralsDto` gains `int plusMilestoneCounter`, `int premiumMilestoneCounter`, and `List<ReferralGrantDto> grants` as its 5th/6th/7th components. Task 5 (controller) and Task 8 (OpenAPI/client types) depend on all of these exact names and the new `MyReferralsDto` shape. **Design note:** Plus and Premium are independently redeemable — redeeming one never resets or forfeits progress toward the other (design spec section 2, revised after product review).

- [ ] **Step 1: Add the repository method `redeemMilestone` needs**

```java
// backend/src/main/java/com/finora/repository/ReferralRepository.java -- add alongside the
// existing finder methods
    /** ReferralService.redeemMilestone -- any one of the referrer's currently-SUBSCRIBED
     *  referrals, recorded as ReferralGrant.earnedFromReferralId purely for an admin's later
     *  traceability. Not required to be the specific referral that pushed a counter over its
     *  threshold -- see that field's own doc comment on why it's informational only. */
    Optional<Referral> findFirstByReferrerUserIdAndStatus(UUID referrerUserId, String status);
```

- [ ] **Step 2: Add the new DTOs**

```java
// backend/src/main/java/com/finora/dto/ReferralDtos.java -- add these two records, and change
// MyReferralsDto's declaration as shown

    /** POST /api/v1/referrals/redeem. {@code tier}: ReferralGrant.TIER_PLUS or TIER_PREMIUM. */
    public record RedeemMilestoneRequest(@NotBlank String tier) {}

    /** One row in a user's own grant history (GET /api/v1/referrals/mine) -- mirrors
     *  ReferralGrant exactly. {@code activatedAt}/{@code expiresAt} are null while PENDING. */
    public record ReferralGrantDto(UUID id, String tier, String status, Instant activatedAt, Instant expiresAt) {}
```

```java
    // Replace the existing MyReferralsDto declaration with:
    /** GET /api/v1/referrals/mine. ... (existing doc comment unchanged) ...
     *  {@code plusMilestoneCounter}/{@code premiumMilestoneCounter} are TWO INDEPENDENT counters
     *  (design spec section 2) -- referrals reaching SUBSCRIBED since that specific tier was last
     *  redeemed (or ever, if never redeemed). Redeeming one never resets or affects the other; the
     *  UI shows both as persistent progress toward each reward, not a single count that vanishes
     *  once you redeem. {@code grants} is this user's own referral-grant history, newest first;
     *  the UI reads it to show an ACTIVE grant's expiry or a queued PENDING one. */
    public record MyReferralsDto(String code, List<MyReferralDto> referrals, BigDecimal walletBalance,
            int referralCount, int plusMilestoneCounter, int premiumMilestoneCounter, List<ReferralGrantDto> grants) {}
```

- [ ] **Step 3: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/ReferralServiceTest.java -- add these fields near the
// existing ones, update setUp()'s mock construction and ReferralService(...) call, and add these
// test methods

    private ReferralGrantRepository referralGrantRepository;
    private NotificationService notificationService;
```

```java
    // in setUp(), alongside the existing mock(...) lines
        referralGrantRepository = mock(ReferralGrantRepository.class);
        notificationService = mock(NotificationService.class);
        service = new ReferralService(referralCodeRepository, referralRepository, walletLedgerRepository,
                refreshTokenRepository, userRepository, auditService, referralGrantRepository, notificationService);
```

```java
    @Test
    void onPlanChanged_incrementsBothCountersTogetherAndNotifiesProgress() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPlusMilestoneCounter(1);
        code.setPremiumMilestoneCounter(1);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(any())).thenReturn(List.of());

        service.onPlanChanged(referredId, "PLUS");

        assertThat(code.getPlusMilestoneCounter()).isEqualTo(2);
        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(2);
        verify(notificationService).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_FRIEND_SUBSCRIBED && req.userId().equals(referrerId)));
        verify(notificationService, never()).request(argThat(req -> req.type() == NotificationType.REFERRAL_MILESTONE_REACHED));
    }

    @Test
    void onPlanChanged_plusCrossingThreeFiresMilestoneReachedForPlusOnly() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPlusMilestoneCounter(2);
        code.setPremiumMilestoneCounter(2);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(any())).thenReturn(List.of());

        service.onPlanChanged(referredId, "PLUS");

        assertThat(code.getPlusMilestoneCounter()).isEqualTo(3);
        verify(notificationService).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_MILESTONE_REACHED
                        && ReferralGrant.TIER_PLUS.equals(req.params().get("tier"))));
        verify(notificationService, never()).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_MILESTONE_REACHED
                        && ReferralGrant.TIER_PREMIUM.equals(req.params().get("tier"))));
    }

    @Test
    void redeemingPlusDoesNotResetOrAffectPremiumCounter() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPlusMilestoneCounter(3);
        code.setPremiumMilestoneCounter(5);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));

        Referral qualifying = new Referral();
        qualifying.setReferrerUserId(referrerId);
        qualifying.setReferredUserId(referredId);
        qualifying.setStatus(Referral.STATUS_SUBSCRIBED);
        ReflectionTestUtils.setField(qualifying, "id", UUID.randomUUID());
        when(referralRepository.findFirstByReferrerUserIdAndStatus(referrerId, Referral.STATUS_SUBSCRIBED))
                .thenReturn(Optional.of(qualifying));
        when(referralGrantRepository.save(any(ReferralGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS);

        assertThat(code.getPlusMilestoneCounter()).isZero();
        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(5);
        verify(referralGrantRepository).save(argThat(g ->
                g.getUserId().equals(referrerId) && ReferralGrant.TIER_PLUS.equals(g.getTier())
                        && ReferralGrant.STATUS_PENDING.equals(g.getStatus())));
    }

    @Test
    void redeemMilestone_belowThresholdThrows() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPlusMilestoneCounter(2);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS))
                .isInstanceOf(ApiException.class);
        verify(referralGrantRepository, never()).save(any());
    }

    @Test
    void redeemMilestone_premiumRedeemableIndependentlyOfWhetherPlusWasEverRedeemed() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPlusMilestoneCounter(0); // Plus already redeemed earlier, unrelated to Premium below
        code.setPremiumMilestoneCounter(7);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));

        Referral qualifying = new Referral();
        qualifying.setReferrerUserId(referrerId);
        qualifying.setReferredUserId(referredId);
        qualifying.setStatus(Referral.STATUS_SUBSCRIBED);
        ReflectionTestUtils.setField(qualifying, "id", UUID.randomUUID());
        when(referralRepository.findFirstByReferrerUserIdAndStatus(referrerId, Referral.STATUS_SUBSCRIBED))
                .thenReturn(Optional.of(qualifying));
        when(referralGrantRepository.save(any(ReferralGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.redeemMilestone(referrerId, ReferralGrant.TIER_PREMIUM);

        assertThat(code.getPremiumMilestoneCounter()).isZero();
        assertThat(code.getPlusMilestoneCounter()).isZero(); // untouched by this redemption, was already 0
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd backend && mvn -Dtest=ReferralServiceTest -DfailIfNoTests=false test`
Expected: FAIL — compile errors (`ReferralGrantRepository`/`NotificationService` not accepted by the constructor, `redeemMilestone`/`getPlusMilestoneCounter`/`getPremiumMilestoneCounter` undefined).

- [ ] **Step 5: Implement — new constructor params, two-counter logic, redemption, expanded `myReferrals()`**

```java
// backend/src/main/java/com/finora/service/ReferralService.java -- add these imports
import com.finora.dto.ReferralDtos.ReferralGrantDto;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.entity.ReferralGrant;
import com.finora.repository.ReferralGrantRepository;
import java.util.Set;
```

```java
    // add these two fields, alongside the existing ones
    private final ReferralGrantRepository referralGrantRepository;
    private final NotificationService notificationService;
```

```java
    // replace the constructor with this 8-arg version
    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            WalletLedgerRepository walletLedgerRepository, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuditService auditService,
                            ReferralGrantRepository referralGrantRepository, NotificationService notificationService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.referralGrantRepository = referralGrantRepository;
        this.notificationService = notificationService;
    }
```

```java
    // change onPlanChanged's .ifPresent(...) body to also call the new method, right after the
    // existing auditService.record(...) call:
                r.setStatus(Referral.STATUS_SUBSCRIBED);
                referralRepository.save(r);
                auditService.record(userId, "REFERRAL_SUBSCRIBED", "Referral", r.getId(),
                        Map.of("referrerUserId", r.getReferrerUserId().toString(), "planCode", newPlanCode));
                incrementMilestoneCountersIfEligible(r);
```

```java
    /**
     * Moves the self-referral fraud check that used to gate only admin-manual creditReward
     * earlier, to counter-increment time -- redemption is now self-service with no admin in the
     * loop, so the check can no longer happen only at the old manual-approval step (design spec
     * section 2). A flagged pair's referral increments neither counter and sends no notification;
     * onPlanChanged's own SUBSCRIBED transition above still happens either way, since that part is
     * a plain factual observation, not a reward.
     *
     * <p>Both counters increment together off the same event (design spec section 2, revised after
     * product review) -- they only diverge once one tier gets redeemed and its own counter resets
     * to 0 while the other keeps climbing. Each is checked against its own threshold independently,
     * so a single referral event can fire zero, one, or (rarely, if both happen to cross at once)
     * two REFERRAL_MILESTONE_REACHED notifications.
     */
    private void incrementMilestoneCountersIfEligible(Referral referral) {
        if (sharesADeviceOrIp(referral.getReferrerUserId(), referral.getReferredUserId())) {
            log.info("Referral {} not counted toward a milestone -- referrer/referred share a device/IP.",
                    referral.getId());
            return;
        }
        ReferralCode code = referralCodeRepository.findByUserId(referral.getReferrerUserId()).orElse(null);
        if (code == null) return;

        int updatedPlus = code.getPlusMilestoneCounter() + 1;
        int updatedPremium = code.getPremiumMilestoneCounter() + 1;
        code.setPlusMilestoneCounter(updatedPlus);
        code.setPremiumMilestoneCounter(updatedPremium);
        referralCodeRepository.save(code);

        notificationService.request(NotificationRequest.of(
                referral.getReferrerUserId(),
                NotificationType.REFERRAL_FRIEND_SUBSCRIBED,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "REFERRAL_FRIEND_SUBSCRIBED_" + referral.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of("plusCount", String.valueOf(updatedPlus), "premiumCount", String.valueOf(updatedPremium))));

        if (updatedPlus == 3) {
            notificationService.request(NotificationRequest.of(
                    referral.getReferrerUserId(),
                    NotificationType.REFERRAL_MILESTONE_REACHED,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "REFERRAL_MILESTONE_REACHED_PLUS_" + referral.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("tier", ReferralGrant.TIER_PLUS)));
        }
        if (updatedPremium == 7) {
            notificationService.request(NotificationRequest.of(
                    referral.getReferrerUserId(),
                    NotificationType.REFERRAL_MILESTONE_REACHED,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "REFERRAL_MILESTONE_REACHED_PREMIUM_" + referral.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("tier", ReferralGrant.TIER_PREMIUM)));
        }
    }
```

```java
    /**
     * Self-service redemption (design spec sections 2/3). Resets ONLY the redeemed tier's own
     * counter to 0 -- the other tier's counter is untouched, so redeeming Plus never costs any
     * progress toward Premium and vice versa (design spec section 2, revised after product review:
     * the original either/or design forfeited whichever tier wasn't redeemed). The self-referral
     * fraud check already ran at counter-increment time above; nothing further to check here.
     *
     * @param tier ReferralGrant.TIER_PLUS or ReferralGrant.TIER_PREMIUM
     */
    @Transactional
    public void redeemMilestone(UUID userId, String tier) {
        int required = ReferralGrant.TIER_PREMIUM.equals(tier) ? 7
                : ReferralGrant.TIER_PLUS.equals(tier) ? 3
                : -1;
        if (required < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown reward tier: " + tier);
        }
        ReferralCode code = referralCodeRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No referral progress to redeem."));
        int current = ReferralGrant.TIER_PREMIUM.equals(tier) ? code.getPremiumMilestoneCounter() : code.getPlusMilestoneCounter();
        if (current < required) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Not enough referrals yet for " + tier + " -- you have " + current + ", need " + required + ".");
        }

        Referral triggering = referralRepository.findFirstByReferrerUserIdAndStatus(userId, Referral.STATUS_SUBSCRIBED)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No qualifying referral found."));

        ReferralGrant grant = new ReferralGrant();
        grant.setUserId(userId);
        grant.setTier(tier);
        grant.setStatus(ReferralGrant.STATUS_PENDING);
        grant.setEarnedFromReferralId(triggering.getId());
        referralGrantRepository.save(grant);

        if (ReferralGrant.TIER_PREMIUM.equals(tier)) {
            code.setPremiumMilestoneCounter(0);
        } else {
            code.setPlusMilestoneCounter(0);
        }
        referralCodeRepository.save(code);

        auditService.record(userId, "REFERRAL_MILESTONE_REDEEMED", "ReferralGrant", grant.getId(), Map.of("tier", tier));
    }
```

```java
    // replace myReferrals()'s body from "BigDecimal balance = ..." onward:
        BigDecimal balance = walletLedgerRepository.sumAmountByUserId(userId);
        Optional<ReferralCode> referralCode = referralCodeRepository.findByUserId(userId);
        int plusMilestoneCounter = referralCode.map(ReferralCode::getPlusMilestoneCounter).orElse(0);
        int premiumMilestoneCounter = referralCode.map(ReferralCode::getPremiumMilestoneCounter).orElse(0);
        List<ReferralGrantDto> grants = referralGrantRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(g -> new ReferralGrantDto(g.getId(), g.getTier(), g.getStatus(), g.getActivatedAt(), g.getExpiresAt()))
                .toList();
        return new MyReferralsDto(code, dtos, balance, dtos.size(), plusMilestoneCounter, premiumMilestoneCounter, grants);
```

(`code` here is the local variable already holding the user's shareable code string from `myCode(userId)` earlier in the method — `referralCode` above is the new, differently-named `Optional<ReferralCode>` entity lookup; keep them distinct, don't collide the names.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && mvn -Dtest=ReferralServiceTest -DfailIfNoTests=false test`
Expected: PASS (all existing tests plus the 5 new ones)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/ReferralService.java \
        backend/src/main/java/com/finora/repository/ReferralRepository.java \
        backend/src/main/java/com/finora/dto/ReferralDtos.java \
        backend/src/test/java/com/finora/service/ReferralServiceTest.java
git commit -m "feat(backend): independent Plus/Premium milestone counters, self-service redemption, fraud-check move"
```

---

## Task 5: `ReferralController` — redeem endpoint

**Files:**
- Modify: `backend/src/main/java/com/finora/controller/ReferralController.java`
- Test: `backend/src/test/java/com/finora/controller/ReferralControllerIT.java`

**Interfaces:**
- Consumes: `ReferralService.redeemMilestone` and `ReferralDtos.RedeemMilestoneRequest` (Task 4).
- Produces: `POST /api/v1/referrals/redeem` — `ApiResponse<Void>`, 200 on success, propagates `ReferralService.redeemMilestone`'s `ApiException` (400/409) otherwise.

- [ ] **Step 1: Write the failing integration test**

```java
// backend/src/test/java/com/finora/controller/ReferralControllerIT.java -- add near the existing
// /mine-related tests
    @Test
    void redeem_belowThresholdReturnsConflict() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\":\"PLUS\"}"))
                .andExpect(status().isConflict());
    }
```

(Follow this test class's own existing convention for obtaining `token` — reuse whatever helper the file's other tests already call, e.g. `registerAndLogin()`, rather than introducing a new one.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && mvn -Dtest=ReferralControllerIT -DfailIfNoTests=false test`
Expected: FAIL — 404, since `/redeem` doesn't exist yet.

- [ ] **Step 3: Add the endpoint**

```java
// backend/src/main/java/com/finora/controller/ReferralController.java -- add these imports
import com.finora.dto.ReferralDtos.RedeemMilestoneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
```

```java
    // add alongside the existing /mine endpoint
    @PostMapping("/redeem")
    public ApiResponse<Void> redeem(@Valid @RequestBody RedeemMilestoneRequest request) {
        referralService.redeemMilestone(currentUser.id(), request.tier());
        return ApiResponse.ok(null, "Reward redeemed");
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && mvn -Dtest=ReferralControllerIT -DfailIfNoTests=false test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/controller/ReferralController.java \
        backend/src/test/java/com/finora/controller/ReferralControllerIT.java
git commit -m "feat(backend): add POST /api/v1/referrals/redeem"
```

---

## Task 6: `EntitlementService` — grant-aware tier resolution

**Files:**
- Modify: `backend/src/main/java/com/finora/service/EntitlementService.java`
- Test: create `backend/src/test/java/com/finora/service/EntitlementServiceTest.java` (none exists today — this service was previously only covered by controller-level ITs, per a repo grep; a real unit test is overdue given the new branching logic).

**Interfaces:**
- Consumes: `ReferralGrant`/`ReferralGrantRepository` (Task 2).
- Produces: `EntitlementService.hasEntitlement`/`planCodeFor`/`entitlementsFor` now reflect `max(real plan tier, active grant tier)`. No signature changes — every existing caller (FINO_AI, Gmail Sync, Investment Insights, multi-month import, `AnalyticsController`, etc.) needs zero changes.

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/EntitlementServiceTest.java
package com.finora.service;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.entity.Subscription;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private FeatureEntitlementRepository featureEntitlementRepository;
    private PlanRepository planRepository;
    private ReferralGrantRepository referralGrantRepository;
    private EntitlementService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID freePlanId = UUID.randomUUID();
    private final UUID plusPlanId = UUID.randomUUID();
    private final UUID premiumPlanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        featureEntitlementRepository = mock(FeatureEntitlementRepository.class);
        planRepository = mock(PlanRepository.class);
        referralGrantRepository = mock(ReferralGrantRepository.class);
        service = new EntitlementService(subscriptionRepository, featureEntitlementRepository, planRepository,
                referralGrantRepository);

        Plan free = plan(freePlanId, "FREE");
        Plan plus = plan(plusPlanId, "PLUS");
        Plan premium = plan(premiumPlanId, "PREMIUM");
        when(planRepository.findByCode("FREE")).thenReturn(Optional.of(free));
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));
        when(planRepository.findById(freePlanId)).thenReturn(Optional.of(free));
    }

    private Plan plan(UUID id, String code) {
        Plan p = new Plan();
        ReflectionTestUtils.setField(p, "id", id);
        p.setCode(code);
        return p;
    }

    @Test
    void planCodeFor_noActiveGrant_returnsRealPlanCode() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.empty());

        assertThat(service.planCodeFor(userId)).isEqualTo("FREE");
    }

    @Test
    void planCodeFor_activeGrantHigherThanRealPlan_returnsGrantTier() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PREMIUM);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        assertThat(service.planCodeFor(userId)).isEqualTo("PREMIUM");
    }

    @Test
    void hasEntitlement_activeGrantUnlocksFeatureRealPlanDoesNotHave() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PREMIUM);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        FeatureEntitlement fe = new FeatureEntitlement();
        fe.setEnabled(true);
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(premiumPlanId, "FINO_AI")).thenReturn(Optional.of(fe));

        assertThat(service.hasEntitlement(userId, "FINO_AI")).isTrue();
    }

    @Test
    void hasEntitlement_grantLowerThanRealPlan_usesRealPlan() {
        Subscription sub = new Subscription();
        sub.setPlanId(premiumPlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant grant = new ReferralGrant();
        grant.setTier(ReferralGrant.TIER_PLUS);
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.of(grant));

        FeatureEntitlement fe = new FeatureEntitlement();
        fe.setEnabled(true);
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(premiumPlanId, "FINO_AI")).thenReturn(Optional.of(fe));

        assertThat(service.hasEntitlement(userId, "FINO_AI")).isTrue();
        assertThat(service.planCodeFor(userId)).isEqualTo("PREMIUM");
    }
}
```

(Check `FeatureEntitlement`'s exact `setEnabled`/field-init API against `backend/src/main/java/com/finora/entity/FeatureEntitlement.java` before running — adjust the two `new FeatureEntitlement()` construction lines above if its real setter name differs.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -Dtest=EntitlementServiceTest -DfailIfNoTests=false test`
Expected: FAIL — compile error, `EntitlementService`'s constructor doesn't accept a 4th argument yet.

- [ ] **Step 3: Implement**

```java
// backend/src/main/java/com/finora/service/EntitlementService.java -- add these imports
import com.finora.entity.ReferralGrant;
import com.finora.repository.ReferralGrantRepository;
```

```java
    // add the field
    private final ReferralGrantRepository referralGrantRepository;
```

```java
    // replace the constructor with this 4-arg version
    public EntitlementService(SubscriptionRepository subscriptionRepository,
                               FeatureEntitlementRepository featureEntitlementRepository,
                               PlanRepository planRepository,
                               ReferralGrantRepository referralGrantRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.featureEntitlementRepository = featureEntitlementRepository;
        this.planRepository = planRepository;
        this.referralGrantRepository = referralGrantRepository;
    }
```

```java
    /**
     * max(real subscription tier, any ACTIVE referral grant's tier) -- design spec at
     * docs/superpowers/specs/2026-09-14-referral-milestone-rewards-design.md, section 4. This is
     * the single place a referral-earned Plus/Premium becomes real access: every other paywall
     * gate in the codebase reads through hasEntitlement/planCodeFor/entitlementsFor below, so none
     * of them need any change.
     */
    private String effectivePlanCodeFor(UUID userId) {
        String realPlanCode = subscriptionRepository.findActiveOrTrial(userId)
                .flatMap(sub -> planRepository.findById(sub.getPlanId()))
                .map(Plan::getCode)
                .orElse(null);
        String grantTier = referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)
                .map(ReferralGrant::getTier)
                .orElse(null);
        if (grantTier == null) return realPlanCode;
        if (realPlanCode == null) return grantTier;
        return ReferralGrant.tierRank(grantTier) > ReferralGrant.tierRank(realPlanCode) ? grantTier : realPlanCode;
    }
```

```java
    // replace hasEntitlement's body:
    @Transactional(readOnly = true)
    public boolean hasEntitlement(UUID userId, String featureKey) {
        String effectivePlanCode = effectivePlanCodeFor(userId);
        if (effectivePlanCode == null) return false;
        return planRepository.findByCode(effectivePlanCode)
                .flatMap(plan -> featureEntitlementRepository.findByPlanIdAndFeatureKey(plan.getId(), featureKey))
                .map(FeatureEntitlement::isEnabled)
                .orElse(false);
    }
```

```java
    // replace planCodeFor's body:
    @Transactional(readOnly = true)
    public String planCodeFor(UUID userId) {
        return effectivePlanCodeFor(userId);
    }
```

```java
    // replace entitlementsFor's body:
    @Transactional(readOnly = true)
    public EntitlementsDto entitlementsFor(UUID userId) {
        String effectivePlanCode = effectivePlanCodeFor(userId);
        if (effectivePlanCode == null) {
            return new EntitlementsDto(null, null, Map.of());
        }
        Plan plan = planRepository.findByCode(effectivePlanCode).orElse(null);
        if (plan == null) {
            return new EntitlementsDto(null, null, Map.of());
        }
        Map<String, Boolean> features = featureEntitlementRepository.findByPlanId(plan.getId()).stream()
                .collect(Collectors.toMap(FeatureEntitlement::getFeatureKey, FeatureEntitlement::isEnabled));
        return new EntitlementsDto(plan.getCode(), plan.getName(), features);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && mvn -Dtest=EntitlementServiceTest -DfailIfNoTests=false test`
Expected: PASS

- [ ] **Step 5: Run the full backend suite to check for regressions**

Run: `cd backend && mvn test`
Expected: PASS — `EntitlementService`'s constructor signature changed, so any other test constructing it directly (grep for `new EntitlementService(` under `backend/src/test`) needs the same 4th-argument mock added; fix any that show up as compile errors before considering this step done.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/EntitlementService.java \
        backend/src/test/java/com/finora/service/EntitlementServiceTest.java
git commit -m "feat(backend): EntitlementService resolves max(real plan tier, active referral grant tier)"
```

---

## Task 7: `ReferralGrantSweepService` — nightly activation/expiry sweep

**Files:**
- Create: `backend/src/main/java/com/finora/service/ReferralGrantSweepService.java`
- Test: `backend/src/test/java/com/finora/service/ReferralGrantSweepServiceTest.java`
- Modify: `backend/src/main/resources/application-test.yml` (if the sweep-disable flag pattern requires a new key — check first; other sweeps' flags default `true` and tests call `sweep()` directly without needing the flag disabled, per `SubscriptionCancellationDispatchSweepServiceTest`'s own pattern — only add a line here if that check shows otherwise).

**Interfaces:**
- Consumes: `ReferralGrant`/`ReferralGrantRepository` (Task 2), `NotificationType.REFERRAL_GRANT_ACTIVATED` (Task 3).
- Produces: `ReferralGrantSweepService.sweep()` returning `int` (count activated) — called directly by tests, and by `scheduledSweep()` on a timer in production.

- [ ] **Step 1: Check the sweep-disable convention**

Run: `grep -n "sweep.enabled" backend/src/main/resources/application-test.yml`

If `SubscriptionCancellationDispatchSweepService`'s own flag (`app.subscription-cancellation-dispatch.sweep.enabled`) already appears there set to `false`, add a corresponding `app.referral-grant.sweep.enabled: false` line in the same file, in the same section, before continuing. If that flag does NOT appear there (tests instead just call `sweep()` directly and never boot the `@Scheduled` timer during the test profile), skip adding anything — match whichever pattern the file actually shows.

- [ ] **Step 2: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/ReferralGrantSweepServiceTest.java
package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.entity.Subscription;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReferralGrantSweepServiceTest {

    private ReferralGrantRepository referralGrantRepository;
    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private NotificationService notificationService;
    private ReferralGrantSweepService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID freePlanId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralGrantRepository = mock(ReferralGrantRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        notificationService = mock(NotificationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        // TransactionTemplate.execute/executeWithoutResult just needs a real TransactionStatus to
        // hand the callback -- no actual DB transaction exists in this unit test, same technique
        // SubscriptionCancellationDispatchSweepServiceTest already uses for the identical shape.
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ReferralGrantSweepService(referralGrantRepository, subscriptionRepository, planRepository,
                notificationService, transactionManager);

        Plan free = new Plan();
        ReflectionTestUtils.setField(free, "id", freePlanId);
        free.setCode("FREE");
        when(planRepository.findById(freePlanId)).thenReturn(Optional.of(free));
    }

    private ReferralGrant grant(String tier, String status) {
        ReferralGrant g = new ReferralGrant();
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        g.setUserId(userId);
        g.setTier(tier);
        g.setStatus(status);
        return g;
    }

    @Test
    void sweep_activatesQueuedGrantWhenRealPlanDoesNotAlreadyCoverIt() {
        Subscription sub = new Subscription();
        sub.setPlanId(freePlanId);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(sub));

        ReferralGrant pending = grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_PENDING);
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of(userId));
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any())).thenReturn(List.of());
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE)).thenReturn(Optional.empty());
        when(referralGrantRepository.findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, ReferralGrant.STATUS_PENDING))
                .thenReturn(Optional.of(pending));

        int activated = service.sweep();

        assertThat(activated).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(ReferralGrant.STATUS_ACTIVE);
        assertThat(pending.getActivatedAt()).isNotNull();
        assertThat(pending.getExpiresAt()).isAfter(Instant.now());
        verify(referralGrantRepository).save(pending);
        verify(notificationService).request(argThat(req -> req.type() == NotificationType.REFERRAL_GRANT_ACTIVATED));
    }

    @Test
    void sweep_leavesGrantQueuedWhenUserAlreadyHasAnActiveGrant() {
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of(userId));
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any())).thenReturn(List.of());
        when(referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE))
                .thenReturn(Optional.of(grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_ACTIVE)));

        int activated = service.sweep();

        assertThat(activated).isZero();
        verify(referralGrantRepository, never()).save(any());
    }

    @Test
    void sweep_expiresGrantsPastExpiry() {
        ReferralGrant expiring = grant(ReferralGrant.TIER_PLUS, ReferralGrant.STATUS_ACTIVE);
        expiring.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING)).thenReturn(List.of());
        when(referralGrantRepository.findByStatusAndExpiresAtBefore(eq(ReferralGrant.STATUS_ACTIVE), any()))
                .thenReturn(List.of(expiring));
        when(referralGrantRepository.findById(expiring.getId())).thenReturn(Optional.of(expiring));

        service.sweep();

        assertThat(expiring.getStatus()).isEqualTo(ReferralGrant.STATUS_EXPIRED);
        verify(referralGrantRepository).save(expiring);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && mvn -Dtest=ReferralGrantSweepServiceTest -DfailIfNoTests=false test`
Expected: FAIL — `ReferralGrantSweepService` doesn't exist yet.

- [ ] **Step 4: Implement**

```java
// backend/src/main/java/com/finora/service/ReferralGrantSweepService.java
package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.ReferralGrant;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Activates and expires referral-earned free-month grants (design spec at docs/superpowers/specs/
 * 2026-09-14-referral-milestone-rewards-design.md, section 3). Same shape as
 * SubscriptionCancellationDispatchSweepService: fixedDelay on a flag application-test.yml can
 * disable, tests call sweep() directly, per-row TransactionTemplate rather than one transaction
 * around the whole loop -- see that class's own doc comment for the connection-pool-exhaustion
 * reasoning behind that choice, identical here.
 *
 * <p>"1 month" is implemented as a fixed 30 days, not a calendar month -- simpler and avoids
 * February/31-day edge cases the design spec never asked to be exact about.
 */
@Service
public class ReferralGrantSweepService {

    private static final Logger log = LoggerFactory.getLogger(ReferralGrantSweepService.class);

    private final ReferralGrantRepository referralGrantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.referral-grant.sweep.enabled:true}")
    private boolean sweepEnabled;

    public ReferralGrantSweepService(ReferralGrantRepository referralGrantRepository,
                                      SubscriptionRepository subscriptionRepository,
                                      PlanRepository planRepository,
                                      NotificationService notificationService,
                                      PlatformTransactionManager transactionManager) {
        this.referralGrantRepository = referralGrantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.referral-grant.sweep.interval-ms:3600000}",
            initialDelayString = "${app.referral-grant.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int activated = sweep();
        if (activated > 0) {
            log.info("Referral grant sweep: {} grant(s) activated.", activated);
        }
    }

    public int sweep() {
        expireDueGrants();

        List<UUID> userIds = referralGrantRepository.findDistinctUserIdsByStatus(ReferralGrant.STATUS_PENDING);
        int activated = 0;
        for (UUID userId : userIds) {
            try {
                if (activateNextIfEligible(userId)) {
                    activated++;
                }
            } catch (RuntimeException e) {
                log.error("Referral grant activation failed for user {}, will retry next sweep.", userId, e);
            }
        }
        return activated;
    }

    private void expireDueGrants() {
        Instant now = Instant.now();
        List<ReferralGrant> due = referralGrantRepository.findByStatusAndExpiresAtBefore(ReferralGrant.STATUS_ACTIVE, now);
        for (ReferralGrant grant : due) {
            transactionTemplate.executeWithoutResult(status -> {
                ReferralGrant fresh = referralGrantRepository.findById(grant.getId()).orElse(null);
                if (fresh == null || !ReferralGrant.STATUS_ACTIVE.equals(fresh.getStatus())
                        || fresh.getExpiresAt() == null || fresh.getExpiresAt().isAfter(Instant.now())) {
                    return;
                }
                fresh.setStatus(ReferralGrant.STATUS_EXPIRED);
                referralGrantRepository.save(fresh);
            });
        }
    }

    /** @return true if a grant was activated for this user in this call. */
    private boolean activateNextIfEligible(UUID userId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (referralGrantRepository.findByUserIdAndStatus(userId, ReferralGrant.STATUS_ACTIVE).isPresent()) {
                return false;
            }
            ReferralGrant next = referralGrantRepository
                    .findFirstByUserIdAndStatusOrderByCreatedAtAsc(userId, ReferralGrant.STATUS_PENDING)
                    .orElse(null);
            if (next == null) return false;

            String realPlanCode = subscriptionRepository.findActiveOrTrial(userId)
                    .flatMap(sub -> planRepository.findById(sub.getPlanId()))
                    .map(Plan::getCode)
                    .orElse(null);
            if (ReferralGrant.tierRank(next.getTier()) <= ReferralGrant.tierRank(realPlanCode)) {
                return false;
            }

            next.setStatus(ReferralGrant.STATUS_ACTIVE);
            next.setActivatedAt(Instant.now());
            next.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
            referralGrantRepository.save(next);

            notificationService.request(NotificationRequest.of(
                    userId,
                    NotificationType.REFERRAL_GRANT_ACTIVATED,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "REFERRAL_GRANT_ACTIVATED_" + next.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("tier", next.getTier(), "expiresAt", next.getExpiresAt().toString())));
            return true;
        }));
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && mvn -Dtest=ReferralGrantSweepServiceTest -DfailIfNoTests=false test`
Expected: PASS

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && mvn test`
Expected: PASS — this is the last backend task, a good point to catch any cross-file regression before moving to the frontend.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/ReferralGrantSweepService.java \
        backend/src/test/java/com/finora/service/ReferralGrantSweepServiceTest.java
git commit -m "feat(backend): add ReferralGrantSweepService to activate/expire referral grants"
```

(If Step 1 required an `application-test.yml` edit, `git add` that file too in this commit.)

---

## Task 8: OpenAPI regen + web/mobile client types

**Files:**
- Modify (generated, do not hand-edit beyond regen): `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `mobile/src/api/endpoints.ts`

**Interfaces:**
- Consumes: the new `/redeem` endpoint and expanded `MyReferralsDto` (Tasks 4-5).
- Produces: `referralsApi.redeem(tier: string)` on both platforms; `MyReferralsDto`/`MyReferralEntry` TypeScript interfaces gain `plusMilestoneCounter`/`premiumMilestoneCounter`/`grants` to match the backend (two independent counters, not one — design spec section 2). Tasks 9-12 depend on these names.

This task has no its own automated test — the check is the existing `npm run typecheck` (or equivalent) on both `frontend` and `mobile` passing against the regenerated types, run in Step 4.

- [ ] **Step 1: Build the backend jar and regenerate the OpenAPI spec**

Run:
```bash
cd backend && mvn -DskipTests package && ./scripts/generate-openapi-spec.sh
```

Expected: `Wrote openapi/openapi.json: N paths, M schemas.` with `N`/`M` both non-zero and `N` one more than before this task (the new `/redeem` path). Requires a reachable local Postgres (`docker-compose.yml`'s `finora/finora/finora` on 5432) per the script's own header comment — start it first if it isn't already running.

- [ ] **Step 2: Regenerate the web and mobile generated-types files**

Run:
```bash
cd frontend && npm run generate:types
cd ../mobile && npm run generate:types
```

(Check `mobile/package.json` for the exact script name if it differs from web's `generate:types`.)

- [ ] **Step 3: Add `referralsApi.redeem` and the new DTO fields on both platforms**

```typescript
// frontend/src/api/endpoints.ts -- extend the existing MyReferralsDto interface
export interface MyReferralsDto {
  code: string;
  referrals: MyReferralEntry[];
  walletBalance: number;
  referralCount: number;
  /** Two INDEPENDENT counters -- redeeming one never resets or affects the other. Referrals
   *  reaching SUBSCRIBED since that tier was last redeemed (or ever, if never redeemed). */
  plusMilestoneCounter: number;
  premiumMilestoneCounter: number;
  grants: ReferralGrantEntry[];
}

export interface ReferralGrantEntry {
  id: string;
  tier: 'PLUS' | 'PREMIUM';
  status: 'PENDING' | 'ACTIVE' | 'EXPIRED';
  activatedAt: string | null;
  expiresAt: string | null;
}
```

```typescript
// frontend/src/api/endpoints.ts -- extend referralsApi
export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
  redeem: (tier: 'PLUS' | 'PREMIUM') => api.post<void>('/referrals/redeem', { tier }).then(() => undefined),
};
```

```typescript
// mobile/src/api/endpoints.ts -- identical shape, mirroring the web additions above
export interface MyReferralsDto {
  code: string;
  referrals: MyReferralEntry[];
  walletBalance: number;
  referralCount: number;
  plusMilestoneCounter: number;
  premiumMilestoneCounter: number;
  grants: ReferralGrantEntry[];
}

export interface ReferralGrantEntry {
  id: string;
  tier: 'PLUS' | 'PREMIUM';
  status: 'PENDING' | 'ACTIVE' | 'EXPIRED';
  activatedAt: string | null;
  expiresAt: string | null;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
  redeem: (tier: 'PLUS' | 'PREMIUM') => api.post<void>('/referrals/redeem', { tier }).then(() => undefined),
};
```

- [ ] **Step 4: Typecheck both platforms**

Run:
```bash
cd frontend && npm run typecheck
cd ../mobile && npm run typecheck
```

Expected: PASS on both. (Check each `package.json` for the exact script name if `typecheck` doesn't exist — e.g. `tsc --noEmit` directly.)

- [ ] **Step 5: Commit**

```bash
git add backend/openapi/openapi.json frontend/src/api/generated-types.ts frontend/src/api/endpoints.ts \
        mobile/src/api/generated-types.ts mobile/src/api/endpoints.ts
git commit -m "chore: regenerate OpenAPI spec and client types for referral milestone rewards"
```

---

## Task 9: Web — plan badge next to the FYNORA brand mark

**Files:**
- Modify: `frontend/src/components/Sidebar.tsx`
- Test: `frontend/src/components/Sidebar.test.tsx` (create if it doesn't exist — check first with `ls frontend/src/components/Sidebar.test.tsx`)

**Interfaces:**
- Consumes: `entitlementsApi.mine()` (already existed before this plan) — `planCode` will already reflect an active referral grant once Task 6 ships, no new query needed.
- Produces: nothing new consumed by later tasks; visually independent of Task 10's animation (Task 10 renders inside `Referrals.tsx`/its own component, not inside `Sidebar.tsx`).

- [ ] **Step 1: Check for an existing Sidebar test file**

Run: `ls frontend/src/components/Sidebar.test.tsx 2>/dev/null || echo "none"`

If one exists, add the new test into it following its own existing setup/mocking conventions (e.g. how it already mocks `useAuth`/react-query) instead of the fresh scaffold in Step 2 below.

- [ ] **Step 2: Write the failing test**

```typescript
// frontend/src/components/Sidebar.test.tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { Sidebar } from './Sidebar';

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ fullName: 'Test User', logout: vi.fn() }),
}));

vi.mock('../api/endpoints', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/endpoints')>();
  return {
    ...actual,
    entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'PREMIUM', planName: 'Premium', features: {} }) },
  };
});

function renderSidebar() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Sidebar />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Sidebar plan badge', () => {
  it('shows the current plan badge next to the brand mark', async () => {
    renderSidebar();
    expect(await screen.findByText('PREMIUM')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/components/Sidebar.test.tsx`
Expected: FAIL — no "PREMIUM" text rendered yet.

- [ ] **Step 4: Implement the badge**

```typescript
// frontend/src/components/Sidebar.tsx -- add these imports
import { useQuery } from '@tanstack/react-query';
import { entitlementsApi } from '../api/endpoints';
```

```typescript
// inside export function Sidebar(), alongside the existing useState/useAuth/useNavigate calls
  const { data: entitlements } = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
    staleTime: 60_000,
  });
```

```tsx
{/* Replace the existing brand-mark block:
    <NavLink to="/app" end className="flex items-center gap-2.5 min-w-0">
      <div className="w-8 h-8 rounded-lg overflow-hidden flex-shrink-0">
        <BrandMark size={32} invert />
      </div>
      {!collapsed && <span className="text-white font-extrabold tracking-wide text-lg truncate">FYNORA</span>}
    </NavLink>
   with: */}
        <NavLink to="/app" end className="flex items-center gap-2.5 min-w-0">
          <div className="w-8 h-8 rounded-lg overflow-hidden flex-shrink-0">
            <BrandMark size={32} invert />
          </div>
          {!collapsed && (
            <span className="flex items-center gap-1.5 min-w-0">
              <span className="text-white font-extrabold tracking-wide text-lg truncate">FYNORA</span>
              {/* FREE shows no badge -- only a referral-earned or paid Plus/Premium tier does.
                  No dedicated "Plus" color token exists in index.css (only --color-premium/
                  --color-premium-fixed), so Plus reuses the base graphite/cream pair rather than
                  inventing a new token -- Premium stays the visually "special" one (design spec
                  section 6.3, validated live against the real sidebar background during design). */}
              {entitlements?.planCode === 'PLUS' && (
                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-[#2E2D2A] text-white border border-[#D9D5CB] flex-shrink-0">
                  PLUS
                </span>
              )}
              {entitlements?.planCode === 'PREMIUM' && (
                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-premium-bg text-premium flex-shrink-0">
                  PREMIUM
                </span>
              )}
            </span>
          )}
        </NavLink>
```

(Check that Tailwind classes `bg-premium-bg`/`text-premium` resolve to `--color-premium-bg`/`--color-premium` in this repo's `tailwind.config` — if the token names differ, use the actual configured class names instead.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/components/Sidebar.test.tsx`
Expected: PASS

- [ ] **Step 6: Manually verify in the browser**

Start the dev server (`preview_start` with the frontend's `.claude/launch.json` entry, or `npm run dev` if none exists), log in as a user with an active Plus or Premium plan, and confirm the badge renders next to "FYNORA" and disappears when collapsed (per the existing `!collapsed &&` guard already used for the "FYNORA" text itself).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/Sidebar.tsx frontend/src/components/Sidebar.test.tsx
git commit -m "feat(frontend): show the user's Plus/Premium plan badge next to the FYNORA brand mark"
```

---

## Task 10: Web — redeem UI and upgrade animation on `Referrals.tsx`

**Files:**
- Create: `frontend/src/components/UpgradeCelebration.tsx`
- Modify: `frontend/src/pages/Referrals.tsx`
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/pages/Referrals.test.tsx`

**Interfaces:**
- Consumes: `referralsApi.redeem` (Task 8), `MyReferralsDto.plusMilestoneCounter`/`premiumMilestoneCounter`/`grants` (Task 8).
- Produces: `UpgradeCelebration` component, `{ tier: 'PLUS' | 'PREMIUM' }` props — self-contained, no other file depends on its internals.

Animation timings/colors below were validated live in a browser mockup during design (glow sweep → badge pop/shine/ring → confetti for Premium; pop + one shine sweep only for Plus) — this task ports that exact CSS into the codebase's existing keyframe convention (`frontend/src/index.css`) rather than re-deriving it.

- [ ] **Step 1: Add the keyframes**

```css
/* frontend/src/index.css -- add alongside the existing keyframes (floatSlow, gradientShift, etc.) */

/* Referral milestone rewards -- upgrade celebration (design spec at docs/superpowers/specs/
   2026-09-14-referral-milestone-rewards-design.md, section 6.4). Colors are literal here, not
   Tailwind tokens: Premium's teal/green accent must render even for a Plus-tier badge recolored
   to graphite/cream, so both variants carry their own fixed palette rather than reading
   --color-premium, which only applies to the Premium case. */
@keyframes referralBadgePop {
  0% { opacity: 0; transform: scale(0.3); }
  60% { opacity: 1; transform: scale(1.15); }
  100% { opacity: 1; transform: scale(1); }
}
@keyframes referralShineSweep {
  0% { background-position: 150% 0; opacity: 1; }
  100% { background-position: -50% 0; opacity: 1; }
}
@keyframes referralRingPulse {
  0% { transform: scale(0.9); opacity: 0.9; }
  100% { transform: scale(1.7); opacity: 0; }
}
@keyframes referralGlowWave {
  0% { background-position: 0 -200%; opacity: 1; }
  90% { opacity: 1; }
  100% { background-position: 0 200%; opacity: 0; }
}

.referral-badge-pop { animation: referralBadgePop 0.5s cubic-bezier(.34,1.56,.64,1) forwards; }
.referral-shine-layer {
  position: absolute; inset: 0; border-radius: 9999px; pointer-events: none;
  background: linear-gradient(120deg, transparent 30%, rgba(255,255,255,0.55) 48%, transparent 66%);
  background-size: 250% 100%; background-position: 150% 0; opacity: 0;
}
.referral-shine-layer.playing { animation: referralShineSweep 1.1s ease-out 0.1s 2; }
.referral-glow-ring {
  position: absolute; inset: -6px; border-radius: 9999px; border: 1.5px solid #34A788; opacity: 0;
}
.referral-glow-ring.playing { animation: referralRingPulse 1s ease-out 0.05s 2; }
.referral-glow-border {
  position: absolute; inset: 0; border-radius: 10px; padding: 1.5px;
  background: linear-gradient(180deg, transparent, #34A788, transparent);
  background-size: 100% 300%; background-position: 0 -200%;
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor; mask-composite: exclude;
  opacity: 0; pointer-events: none;
}
.referral-glow-border.playing { animation: referralGlowWave 1.4s ease-out forwards; }

/* prefers-reduced-motion: disable every animation this component drives, matching the existing
   guard convention for other one-off animations in this file (e.g. .reveal). The badge and any
   confetti-spawned nodes still render, just without motion. */
@media (prefers-reduced-motion: reduce) {
  .referral-badge-pop, .referral-shine-layer.playing, .referral-glow-ring.playing, .referral-glow-border.playing {
    animation: none !important;
  }
}
```

- [ ] **Step 2: Write the component**

```tsx
// frontend/src/components/UpgradeCelebration.tsx
import { useEffect, useRef } from 'react';

/**
 * Fires once when a referral grant activates (design spec section 6.4) -- Premium gets the full
 * sequence (sidebar-edge glow wave, badge pop/shine/ring, confetti burst); Plus gets pop + one
 * shine sweep only, deliberately smaller so reaching Premium later still feels like the bigger
 * moment. Colors and timings were validated live in a browser mockup during design, not re-derived
 * here.
 */
export function UpgradeCelebration({ tier }: { tier: 'PLUS' | 'PREMIUM' }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const badgeRef = useRef<HTMLSpanElement>(null);
  const shineRef = useRef<HTMLSpanElement>(null);
  const ringRef = useRef<HTMLSpanElement>(null);
  const glowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function reset(el: HTMLElement | null) {
      if (!el) return;
      el.classList.remove('playing', 'referral-badge-pop');
      void el.offsetWidth;
    }

    function spawnConfetti(container: HTMLElement, colors: string[]) {
      const rect = container.getBoundingClientRect();
      for (let i = 0; i < 28; i++) {
        const p = document.createElement('div');
        p.style.position = 'absolute';
        p.style.width = '6px';
        p.style.height = '9px';
        p.style.left = `${rect.width / 2}px`;
        p.style.top = '38px';
        p.style.background = colors[i % colors.length];
        p.style.borderRadius = Math.random() > 0.5 ? '50%' : '2px';
        p.style.pointerEvents = 'none';
        container.appendChild(p);
        const angle = Math.random() * Math.PI - Math.PI - Math.PI / 2;
        const dist = 60 + Math.random() * 100;
        const dx = Math.cos(angle) * dist;
        const dy = Math.sin(angle) * dist - 20;
        const rot = Math.random() * 720 - 360;
        p.animate(
          [
            { transform: 'translate(0,0) rotate(0deg)', opacity: 1 },
            { transform: `translate(${dx}px, ${dy + 90}px) rotate(${rot}deg)`, opacity: 0 },
          ],
          { duration: 900 + Math.random() * 500, easing: 'cubic-bezier(.25,.8,.25,1)', fill: 'forwards' },
        );
        setTimeout(() => p.remove(), 1500);
      }
    }

    if (reduceMotion) return;

    if (tier === 'PREMIUM') {
      reset(glowRef.current);
      requestAnimationFrame(() => glowRef.current?.classList.add('playing'));
      const timer = setTimeout(() => {
        [badgeRef.current, shineRef.current, ringRef.current].forEach(reset);
        badgeRef.current?.classList.add('referral-badge-pop');
        shineRef.current?.classList.add('playing');
        ringRef.current?.classList.add('playing');
        if (stageRef.current) spawnConfetti(stageRef.current, ['#F4F1EC', '#34A788', '#98968F', '#0F4C3F']);
      }, 350);
      return () => clearTimeout(timer);
    }

    reset(badgeRef.current);
    reset(shineRef.current);
    badgeRef.current?.classList.add('referral-badge-pop');
    shineRef.current?.classList.add('playing');
  }, [tier]);

  const badgeColors =
    tier === 'PREMIUM'
      ? { background: 'var(--color-premium-bg)', color: 'var(--color-premium)', border: 'none' }
      : { background: '#2E2D2A', color: '#F4F1EC', border: '1px solid #D9D5CB' };

  return (
    <div ref={stageRef} className="relative inline-flex items-center" data-testid="upgrade-celebration">
      {tier === 'PREMIUM' && <div ref={glowRef} className="referral-glow-border" />}
      <span
        ref={badgeRef}
        className="relative inline-flex items-center gap-1 text-[11px] font-bold px-2.5 py-1 rounded-full"
        style={badgeColors}
      >
        ✦ {tier}
        <span ref={shineRef} className="referral-shine-layer" />
        {tier === 'PREMIUM' && <span ref={ringRef} className="referral-glow-ring" />}
      </span>
    </div>
  );
}
```

- [ ] **Step 3: Write the failing tests for the redeem UI**

```tsx
// frontend/src/pages/Referrals.test.tsx -- add near the existing tests, adjusting the file's own
// existing render/mock helpers (check what's already there before duplicating setup)
import { referralsApi } from '../api/endpoints';

describe('milestone redemption', () => {
  it('shows a persistent progress bar toward Plus below the threshold', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 2, premiumMilestoneCounter: 2, grants: [],
    });
    renderReferrals();
    expect(await screen.findByText(/2\s*\/\s*3/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /redeem.*plus/i })).not.toBeInTheDocument();
  });

  it('shows both a redeem-Plus button and a Premium progress bar once Plus reaches 3', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 3, premiumMilestoneCounter: 5, grants: [],
    });
    renderReferrals();
    expect(await screen.findByRole('button', { name: /redeem.*plus/i })).toBeInTheDocument();
    expect(screen.getByText(/5\s*\/\s*7/)).toBeInTheDocument();
  });

  it('shows both redeem buttons simultaneously once both thresholds are reached', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 4, premiumMilestoneCounter: 7, grants: [],
    });
    renderReferrals();
    expect(await screen.findByRole('button', { name: /redeem.*plus/i })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /redeem.*premium/i })).toBeInTheDocument();
  });

  it('calls referralsApi.redeem with the right tier on click', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 3, premiumMilestoneCounter: 3, grants: [],
    });
    vi.mocked(referralsApi.redeem).mockResolvedValue(undefined);
    renderReferrals();
    const button = await screen.findByRole('button', { name: /redeem.*plus/i });
    button.click();
    await waitFor(() => expect(referralsApi.redeem).toHaveBeenCalledWith('PLUS'));
  });
});
```

(Match this exactly to the file's existing `renderReferrals`/mock-setup helper names — read the top of `Referrals.test.tsx` first and adapt rather than assuming these names exist verbatim.)

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Referrals.test.tsx`
Expected: FAIL — no progress bars or redeem buttons exist yet.

- [ ] **Step 5: Implement the progress bars and redeem UI**

```tsx
// frontend/src/pages/Referrals.tsx -- add these imports
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { referralsApi } from '../api/endpoints';
```

```tsx
// inside export default function Referrals(), alongside the existing useQuery call
  const queryClient = useQueryClient();
  const redeemMutation = useMutation({
    mutationFn: (tier: 'PLUS' | 'PREMIUM') => referralsApi.redeem(tier),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals-mine'] }),
  });
```

```tsx
{/* Small reusable piece for one tier's row -- either a progress bar (below threshold) or a
    redeem card (at/above threshold). Both tiers render independently and simultaneously: reaching
    Premium's threshold never hides or replaces Plus's row, and vice versa (design spec section
    6.1, revised after product review -- progress is persistent, nothing is ever forfeited). */}
function MilestoneRow({
  label, counter, threshold, onRedeem, redeeming,
}: {
  label: string; counter: number; threshold: number;
  onRedeem: () => void; redeeming: boolean;
}) {
  if (counter >= threshold) {
    return (
      <FinoraCard padding="lg">
        <p className="text-sm font-semibold text-ink mb-2">You&apos;ve unlocked a reward!</p>
        <p className="text-xs text-muted mb-3">Redeem 1 month of {label}, free.</p>
        <button
          type="button"
          className="text-sm font-semibold px-4 py-2 rounded-lg bg-primary text-on-primary disabled:opacity-50"
          disabled={redeeming}
          onClick={onRedeem}
        >
          Redeem {label}
        </button>
      </FinoraCard>
    );
  }
  const pct = Math.min(100, Math.round((counter / threshold) * 100));
  return (
    <FinoraCard padding="lg">
      <p className="text-sm font-semibold text-ink mb-2">
        {counter} / {threshold} toward {label}
      </p>
      <div className="h-2 rounded-full bg-bg overflow-hidden">
        <div className="h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
      </div>
    </FinoraCard>
  );
}
```

```tsx
{/* Add this block into the page's JSX, after the existing "Your referral link" FinoraCard and
    before the referrals list. Both rows always render -- neither tier's progress or redeem card
    is ever hidden by the other reaching its own threshold. */}
      {mine && (
        <>
          <MilestoneRow
            label="Plus" counter={mine.plusMilestoneCounter} threshold={3}
            onRedeem={() => redeemMutation.mutate('PLUS')} redeeming={redeemMutation.isPending}
          />
          <MilestoneRow
            label="Premium" counter={mine.premiumMilestoneCounter} threshold={7}
            onRedeem={() => redeemMutation.mutate('PREMIUM')} redeeming={redeemMutation.isPending}
          />
        </>
      )}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Referrals.test.tsx`
Expected: PASS

- [ ] **Step 7: Manually verify in the browser**

Start the dev server, navigate to `/app/referrals` as a user whose `plusMilestoneCounter` is 3+ (seed this via the backend test data or a direct DB update in a local dev environment), confirm the redeem card and button render alongside the Premium progress bar, and the click succeeds. Then render `<UpgradeCelebration tier="PREMIUM" />` standalone (e.g. temporarily drop it into the page) to confirm the glow/pop/confetti sequence plays, and `tier="PLUS"` to confirm only the pop+shine plays with no glow/confetti.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/UpgradeCelebration.tsx frontend/src/pages/Referrals.tsx \
        frontend/src/index.css frontend/src/pages/Referrals.test.tsx
git commit -m "feat(frontend): add milestone redemption UI and upgrade celebration animation"
```

---

## Task 11: Mobile — plan badge on `DashboardScreen`

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx`
- Modify: `mobile/src/screens/DashboardScreen.test.tsx`

**Interfaces:**
- Consumes: `entitlementsApi.mine()` (already existed before this plan).
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Write the failing test**

```typescript
// mobile/src/screens/DashboardScreen.test.tsx -- add near the existing tests, reusing whatever
// query-mocking convention the file already uses for entitlementsApi/dashboardApi
it('shows the plan badge next to the brand mark when on Premium', async () => {
  jest.spyOn(entitlementsApi, 'mine').mockResolvedValue({ planCode: 'PREMIUM', planName: 'Premium', features: {} });
  const { findByText } = renderDashboard();
  expect(await findByText('PREMIUM')).toBeTruthy();
});
```

(Match this to the file's own existing render helper name, e.g. `renderDashboard()` or however it currently mounts the screen with its query client — read the top of the test file first.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd mobile && npx jest DashboardScreen.test.tsx`
Expected: FAIL — no "PREMIUM" text rendered.

- [ ] **Step 3: Implement the header bar**

```typescript
// mobile/src/screens/DashboardScreen.tsx -- add these imports
import { entitlementsApi } from '../api/endpoints';
import { BrandMark } from '../components/BrandMark';
```

```typescript
// inside export function DashboardScreen(), alongside the existing useQuery calls
  const entitlementsQ = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
    staleTime: 60_000,
  });
```

```tsx
{/* Add immediately inside the ScrollView's contentContainer, before the existing
    <View style={styles.greetingRow}> block. No dedicated in-app header exists on mobile today
    (BrandMark only renders on AuthScreenLayout) -- this mirrors the web sidebar's placement per
    design spec section 6.2, added to Dashboard specifically since that's where it was asked for. */}
      <View style={styles.brandRow}>
        <BrandMark size={22} />
        <Text style={[styles.brandWord, { color: c.ink }]}>FYNORA</Text>
        {entitlementsQ.data?.planCode === 'PLUS' && (
          <View style={[styles.planBadge, { backgroundColor: '#2E2D2A', borderColor: '#D9D5CB' }]}>
            <Text style={[styles.planBadgeText, { color: '#F4F1EC' }]}>PLUS</Text>
          </View>
        )}
        {entitlementsQ.data?.planCode === 'PREMIUM' && (
          <View style={[styles.planBadge, { backgroundColor: '#E3EEE9', borderColor: 'transparent' }]}>
            <Text style={[styles.planBadgeText, { color: '#0F4C3F' }]}>PREMIUM</Text>
          </View>
        )}
      </View>
```

```typescript
// add to the existing StyleSheet.create({...}) block
  brandRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: spacing.sm },
  brandWord: { fontSize: 15, fontWeight: '800', letterSpacing: 0.5 },
  planBadge: { borderRadius: 999, borderWidth: 1, paddingHorizontal: 10, paddingVertical: 3 },
  planBadgeText: { fontSize: 10.5, fontWeight: '700' },
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd mobile && npx jest DashboardScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx
git commit -m "feat(mobile): add a Dashboard header bar with the FYNORA brand mark and plan badge"
```

---

## Task 12: Mobile — redeem UI and upgrade animation on `ReferralsScreen`

**Files:**
- Modify: `mobile/src/screens/ReferralsScreen.tsx`
- Modify: `mobile/src/screens/ReferralsScreen.test.tsx`

**Interfaces:**
- Consumes: `referralsApi.redeem` (Task 8), `MyReferralsDto.plusMilestoneCounter`/`premiumMilestoneCounter`/`grants` (Task 8).
- Produces: nothing new consumed by later tasks — this is the last task in the plan.

Mobile's animation equivalent uses React Native's `Animated` API (no new dependency) rather than CSS/Web Animations, since RN has neither. Same three beats as web, scaled down: `Animated.spring` for the pop, an `Animated.loop`-free two-pass opacity/scale sequence for the shine-adjacent glow, and simple randomized `Animated.timing` translations for Premium's confetti dots (no external confetti library — consistent with this plan's web side also hand-rolling it rather than adding a dependency).

- [ ] **Step 1: Write the failing tests**

```typescript
// mobile/src/screens/ReferralsScreen.test.tsx -- add near the existing tests, reusing this file's
// own existing render/mock helper (read the top of the file first)
import { referralsApi } from '../api/endpoints';

describe('milestone redemption', () => {
  it('shows a progress readout toward Plus below the threshold', async () => {
    jest.spyOn(referralsApi, 'mine').mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 2, premiumMilestoneCounter: 2, grants: [],
    });
    const { findByText, queryByText } = renderReferralsScreen();
    expect(await findByText(/2\s*\/\s*3/)).toBeTruthy();
    expect(queryByText(/redeem plus/i)).toBeNull();
  });

  it('shows both redeem buttons simultaneously once both thresholds are reached', async () => {
    jest.spyOn(referralsApi, 'mine').mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 4, premiumMilestoneCounter: 7, grants: [],
    });
    const { findByText } = renderReferralsScreen();
    expect(await findByText(/redeem plus/i)).toBeTruthy();
    expect(await findByText(/redeem premium/i)).toBeTruthy();
  });

  it('calls referralsApi.redeem with the right tier on press', async () => {
    jest.spyOn(referralsApi, 'mine').mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 3, premiumMilestoneCounter: 7, grants: [],
    });
    const redeemSpy = jest.spyOn(referralsApi, 'redeem').mockResolvedValue(undefined);
    const { findByText } = renderReferralsScreen();
    const button = await findByText(/redeem premium/i);
    fireEvent.press(button);
    await waitFor(() => expect(redeemSpy).toHaveBeenCalledWith('PREMIUM'));
  });
});
```

(Match `renderReferralsScreen`/`fireEvent`/`waitFor` to whatever this file's existing tests already import and use.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd mobile && npx jest ReferralsScreen.test.tsx`
Expected: FAIL — no progress readout or redeem button exists yet.

- [ ] **Step 3: Implement the progress rows and redeem UI**

```typescript
// mobile/src/screens/ReferralsScreen.tsx -- add these imports
import { useEffect, useRef, useState } from 'react';
import { Animated } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
```

```typescript
// inside export function ReferralsScreen(), alongside the existing useState/useQuery calls
  const queryClient = useQueryClient();
  const redeemMutation = useMutation({
    mutationFn: (tier: 'PLUS' | 'PREMIUM') => referralsApi.redeem(tier),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals-mine'] }),
  });
```

```tsx
{/* Small reusable row -- either a progress readout (below threshold) or a redeem card (at/above
    it). Both tiers render independently and simultaneously, same as web's MilestoneRow: reaching
    one threshold never hides or replaces the other's row (design spec section 6.1, revised after
    product review -- progress is persistent, nothing is ever forfeited). Defined in this same
    file, above ReferralsScreen -- no other screen uses it. */}
function MilestoneRow({
  c, label, counter, threshold, onRedeem, redeeming,
}: {
  c: ReturnType<typeof useTheme>; label: string; counter: number; threshold: number;
  onRedeem: () => void; redeeming: boolean;
}) {
  if (counter >= threshold) {
    return (
      <Card style={styles.codeCard}>
        <Text style={[styles.cardLabel, { color: c.ink }]}>You&apos;ve unlocked a reward!</Text>
        <Text style={[styles.emptyDesc, { color: c.muted }]}>Redeem 1 month of {label}, free.</Text>
        <Pressable
          onPress={onRedeem}
          disabled={redeeming}
          style={[styles.shareButton, { backgroundColor: c.primary, opacity: redeeming ? 0.5 : 1 }]}
          accessibilityRole="button"
        >
          <Text style={[styles.shareButtonText, { color: c.onPrimary }]}>Redeem {label}</Text>
        </Pressable>
      </Card>
    );
  }
  return (
    <Card style={styles.codeCard}>
      <Text style={[styles.cardLabel, { color: c.ink }]}>{counter} / {threshold} toward {label}</Text>
    </Card>
  );
}
```

```tsx
{/* Add this block into the returned JSX, after the existing codeCard and before the statsRow.
    Both rows always render -- neither tier's progress or redeem card is hidden by the other. */}
      <MilestoneRow
        c={c} label="Plus" counter={data.plusMilestoneCounter} threshold={3}
        onRedeem={() => redeemMutation.mutate('PLUS')} redeeming={redeemMutation.isPending}
      />
      <MilestoneRow
        c={c} label="Premium" counter={data.premiumMilestoneCounter} threshold={7}
        onRedeem={() => redeemMutation.mutate('PREMIUM')} redeeming={redeemMutation.isPending}
      />
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd mobile && npx jest ReferralsScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Add the mobile upgrade-celebration animation**

```tsx
// mobile/src/screens/ReferralsScreen.tsx -- add this component in the same file, above
// export function ReferralsScreen (it has no other consumer, so it doesn't need its own file the
// way web's UpgradeCelebration does — that one is reused from Sidebar-adjacent code paths too)

/**
 * Mobile equivalent of web's UpgradeCelebration (design spec section 6.4) -- same three beats
 * (Premium: glow + pop/shine + confetti dots; Plus: pop + one shine pass only), built on RN's
 * Animated API since there is no CSS/Web Animations equivalent here. Rendered once, right after a
 * successful redeem, inline in this screen rather than as a shared component -- this is the only
 * mobile call site.
 */
function MobileUpgradeCelebration({ tier }: { tier: 'PLUS' | 'PREMIUM' }) {
  const scale = useRef(new Animated.Value(0.3)).current;
  const opacity = useRef(new Animated.Value(0)).current;
  const shineOpacity = useRef(new Animated.Value(0)).current;
  const [confettiDots] = useState(() =>
    tier === 'PREMIUM'
      ? Array.from({ length: 16 }, () => ({
          x: useRef(new Animated.Value(0)).current,
          y: useRef(new Animated.Value(0)).current,
          o: useRef(new Animated.Value(1)).current,
        }))
      : [],
  );

  useEffect(() => {
    Animated.sequence([
      Animated.timing(opacity, { toValue: 1, duration: 150, useNativeDriver: true }),
      Animated.spring(scale, { toValue: 1, friction: 4, tension: 120, useNativeDriver: true }),
    ]).start();

    Animated.sequence([
      Animated.timing(shineOpacity, { toValue: 1, duration: 200, useNativeDriver: true }),
      Animated.timing(shineOpacity, { toValue: 0, duration: 400, delay: 300, useNativeDriver: true }),
    ]).start();

    if (tier === 'PREMIUM') {
      confettiDots.forEach((dot) => {
        const angle = Math.random() * Math.PI * 2;
        const dist = 40 + Math.random() * 60;
        Animated.parallel([
          Animated.timing(dot.x, { toValue: Math.cos(angle) * dist, duration: 900, useNativeDriver: true }),
          Animated.timing(dot.y, { toValue: Math.sin(angle) * dist + 60, duration: 900, useNativeDriver: true }),
          Animated.timing(dot.o, { toValue: 0, duration: 900, useNativeDriver: true }),
        ]).start();
      });
    }
  }, [tier]);

  const badgeStyle =
    tier === 'PREMIUM'
      ? { backgroundColor: '#E3EEE9', borderColor: 'transparent' }
      : { backgroundColor: '#2E2D2A', borderColor: '#D9D5CB' };
  const textColor = tier === 'PREMIUM' ? '#0F4C3F' : '#F4F1EC';

  return (
    <View style={{ alignItems: 'center', justifyContent: 'center', paddingVertical: 12 }}>
      {confettiDots.map((dot, i) => (
        <Animated.View
          key={i}
          style={{
            position: 'absolute',
            width: 5,
            height: 5,
            borderRadius: 2.5,
            backgroundColor: i % 2 === 0 ? '#34A788' : '#98968F',
            opacity: dot.o,
            transform: [{ translateX: dot.x }, { translateY: dot.y }],
          }}
        />
      ))}
      <Animated.View
        style={[
          { borderRadius: 999, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 6, opacity, transform: [{ scale }] },
          badgeStyle,
        ]}
      >
        <Animated.View
          pointerEvents="none"
          style={{ position: 'absolute', inset: 0 as unknown as number, opacity: shineOpacity, backgroundColor: 'rgba(255,255,255,0.25)', borderRadius: 999 }}
        />
        <Text style={{ fontSize: 13, fontWeight: '700', color: textColor }}>✦ {tier}</Text>
      </Animated.View>
    </View>
  );
}
```

```tsx
{/* Wire it into the redeem success path -- track the just-redeemed tier locally and render the
    celebration once, then let it clear on next navigation (no persistence needed, this is a
    one-time in-session moment). Add this state near the existing useTransientFlag call: */}
  const [justRedeemed, setJustRedeemed] = useState<'PLUS' | 'PREMIUM' | null>(null);
```

```typescript
  // change the mutation's onSuccess to also set this:
  const redeemMutation = useMutation({
    mutationFn: (tier: 'PLUS' | 'PREMIUM') => referralsApi.redeem(tier),
    onSuccess: (_data, tier) => {
      queryClient.invalidateQueries({ queryKey: ['referrals-mine'] });
      setJustRedeemed(tier);
    },
  });
```

```tsx
{/* render right below the redeem button added in Step 3 */}
          {justRedeemed && <MobileUpgradeCelebration tier={justRedeemed} />}
```

- [ ] **Step 6: Run the full mobile test file once more**

Run: `cd mobile && npx jest ReferralsScreen.test.tsx`
Expected: PASS

- [ ] **Step 7: Manually verify on a simulator**

Launch the app on a simulator, sign in as a user whose `plusMilestoneCounter` is 3+ (seeded the same way as Task 10's web check), open Refer & Earn, tap Redeem, and confirm the badge pop/shine plays for Plus and the pop/shine/confetti-dots plays for Premium.

- [ ] **Step 8: Commit**

```bash
git add mobile/src/screens/ReferralsScreen.tsx mobile/src/screens/ReferralsScreen.test.tsx
git commit -m "feat(mobile): add milestone redemption UI and upgrade celebration animation"
```

---

## Self-Review

**Spec coverage:**
- Milestone counter (3/7, reset on redeem, SUBSCRIBED-gated) — Task 4.
- Fraud check moved from admin-manual to counter-increment — Task 4.
- Two independent, never-forfeited counters (revised 2026-09-14 after product review) — Tasks 1, 2, 4.
- `ReferralGrant` overlay + FIFO stacking, no cash conversion — Tasks 1, 2, 7.
- `EntitlementService` reads `max(real tier, active grant tier)` — Task 6.
- Three notifications (progress showing both counters, milestone per-tier, activation) — Tasks 1, 3, 4, 7.
- Persistent progress bars + redeem UI, web + mobile — Tasks 10, 12.
- Plan badge next to FYNORA, web + mobile — Tasks 9, 11.
- Upgrade animation, both tiers, both platforms — Tasks 10, 12.
- Cash path (`creditReward`) untouched — verified by construction: no task modifies it.
- Account purge cleanup for the new table — Task 2.

**Placeholder scan:** no TBD/TODO, no "add appropriate error handling," no unshown code steps — every step above carries the actual code or command.

**Type consistency:** `ReferralGrant.TIER_PLUS`/`TIER_PREMIUM`, `STATUS_PENDING`/`STATUS_ACTIVE`/`STATUS_EXPIRED`, and `tierRank(String)` are defined once in Task 2 and reused with identical names in Tasks 4, 6, 7, 9-12. `MyReferralsDto`'s new `plusMilestoneCounter`/`premiumMilestoneCounter`/`grants` fields (Task 4) match exactly across the Java DTO, `frontend/src/api/endpoints.ts`, and `mobile/src/api/endpoints.ts` (Task 8), and both UIs' `MilestoneRow` components (Tasks 10, 12) consume them identically, one row per tier, neither ever hidden by the other. `referralsApi.redeem(tier)` has the identical signature on both platforms (Task 8), consumed identically in Tasks 10 and 12.

**Known gaps deliberately left to task-time investigation** (per the No Placeholders rule these are named explicitly, not hidden):
- Task 5's exact login-helper name in `ReferralControllerIT` — read the file first.
- Task 6's `FeatureEntitlement` construction API — verify against the real entity before running.
- Task 7's `application-test.yml` sweep-flag check — the step itself is the investigation.
- Tasks 9-12's exact existing test-file helper names (`renderSidebar`-equivalent, `renderReferralsScreen`, etc.) and Tailwind class names for the premium color tokens — each step says to check the real file first rather than guessing.
