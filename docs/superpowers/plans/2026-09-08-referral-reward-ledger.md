# Referral Reward Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore reward crediting for the Refer & Earn program (removed by the 2026-09-05 MVP
descope, commit `326add55`) with one correction: fire the automatic REGISTERED→SUBSCRIBED
transition off `subscription.charged` (a real, confirmed charge), not `subscription.activated`
(which can fire with zero funds movement) — a bug the Subscription Billing V2 plan already found
and documented before this rebuild.

**Architecture:** `referrals.status`/`referrals.reward` and `wallet_ledger` (V101) already exist in
the schema, unused since the descope — no new migration. A referral moves
`REGISTERED → SUBSCRIBED` automatically (webhook-triggered, idempotent) and
`SUBSCRIBED → REWARDED` only via an admin-manual credit action (amount decided per-referral, not a
fixed policy) that writes one `WalletLedgerEntry` row. Balance is always a computed `SUM` over that
table, never a stored counter.

**Tech Stack:** Spring Boot 3 (Java, Jakarta), Spring Data JPA, PostgreSQL/Flyway, React +
TanStack Query (web/admin-portal), React Native + TanStack Query (mobile), JUnit 5 + Mockito
(unit), Testcontainers `*IT` (integration), Vitest (web/admin-portal), Jest (mobile).

**Spec:** [`docs/superpowers/specs/2026-09-08-referral-reward-ledger-design.md`](../specs/2026-09-08-referral-reward-ledger-design.md) — read alongside this plan.

## Global Constraints

- No new Flyway migration — `referrals.status`/`referrals.reward` and `wallet_ledger` already exist
  (V101). Before touching migrations for any *other* reason, run `git fetch origin` and check
  `backend/src/main/resources/db/migration` for the current highest version first (repo-wide rule,
  not specific to this feature).
- Trigger the automatic REGISTERED→SUBSCRIBED transition from `subscription.charged`
  (`RazorpayWebhookDispatcher`) and `INITIAL_PURCHASE` (`RevenueCatWebhookDispatcher`) —
  **never** from `subscription.activated` alone (spec §5, confirmed bug in the original design).
- Reward amount is admin-set per referral at credit time — do not add any fixed/flat/tiered/
  percentage amount constant anywhere in this plan.
- `frontend/src/pages/Billing.tsx` is explicitly **out of scope** — do not touch it. It lives on a
  separate open branch (PR #1223) this work does not depend on or modify.
- Commit messages carry no AI-attribution trailer (repo-wide `CLAUDE.md` rule) — plain commit
  messages only, no `Co-Authored-By` line of any kind.
- Work happens in this session's existing worktree (`Finora/vigilant-kalam-7bdd90`, already
  branched from `origin/main`) — every command below assumes that working directory.

---

## Task 1: Restore `Referral` entity, repository, and DTOs

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/Referral.java`
- Modify: `backend/src/main/java/com/finora/repository/ReferralRepository.java`
- Modify: `backend/src/main/java/com/finora/dto/ReferralDtos.java`
- Modify: `backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java:296-306` (2 lines)

**Interfaces:**
- Produces: `Referral.STATUS_REGISTERED/STATUS_SUBSCRIBED/STATUS_REWARDED/STATUS_INVITED`,
  `Referral.getStatus()/setStatus(String)/getReward()/setReward(BigDecimal)/getUpdatedAt()/setUpdatedAt(Instant)`;
  `ReferralRepository.findByReferrerUserIdOrderByCreatedAtDesc(UUID)`,
  `.findByReferredUserId(UUID)`, `.findAllByOrderByCreatedAtDesc(Pageable)`; DTOs
  `ReferralDtos.MyReferralDto`, `MyReferralsDto`, `AdminReferralSummaryDto`,
  `CreditReferralRewardRequest` — all consumed by Task 2/4.

This task has no failing-test step of its own (it's pure data-shape restoration with no behavior
yet) — Task 2's tests are what exercise it. Compile success is the pass condition here.

- [ ] **Step 1: Restore the `Referral` entity**

Replace the full contents of `backend/src/main/java/com/finora/entity/Referral.java`:

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One referral (proposal §4) -- {@code referred_user_id} is unique, so a user is referred at most
 * once, ever. No {@link #STATUS_INVITED} row is ever created by this codebase (Finora has no
 * invite-by-email mechanism); every row starts at {@link #STATUS_REGISTERED}, the moment someone
 * signs up with a valid code (see {@code ReferralService.redeemCode}). Not extending
 * {@link BaseEntity}: this is a status-tracking row updated in place by well-defined,
 * one-directional transitions (REGISTERED -> SUBSCRIBED -> REWARDED, see
 * {@code ReferralService.onPlanChanged}/{@code creditReward}), not a soft-deletable user-owned
 * resource.
 */
@Entity
@Table(name = "referrals")
public class Referral {

    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_SUBSCRIBED = "SUBSCRIBED";
    public static final String STATUS_REWARDED = "REWARDED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;

    @Column(name = "referred_user_id", nullable = false, unique = true)
    private UUID referredUserId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column
    private BigDecimal reward;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getReferrerUserId() { return referrerUserId; }
    public void setReferrerUserId(UUID referrerUserId) { this.referrerUserId = referrerUserId; }
    public UUID getReferredUserId() { return referredUserId; }
    public void setReferredUserId(UUID referredUserId) { this.referredUserId = referredUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getReward() { return reward; }
    public void setReward(BigDecimal reward) { this.reward = reward; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

Note `status` no longer has a field-initializer default — every creation site must set it
explicitly. That's Step 2 below plus Task 2's `redeemCode`.

- [ ] **Step 2: Fix the two `Referral` fixtures in `AccountPurgeSweepServiceIT`**

In `backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java`, find:

```java
        Referral referredByOther = new Referral();
        referredByOther.setReferrerUserId(otherUserId);
        referredByOther.setReferredUserId(userId);
        referralRepository.save(referredByOther);

        Referral referredOther = new Referral();
        referredOther.setReferrerUserId(userId);
        referredOther.setReferredUserId(otherUserId);
        referralRepository.save(referredOther);
```

Replace with:

```java
        Referral referredByOther = new Referral();
        referredByOther.setReferrerUserId(otherUserId);
        referredByOther.setReferredUserId(userId);
        referredByOther.setStatus(Referral.STATUS_REGISTERED);
        referralRepository.save(referredByOther);

        Referral referredOther = new Referral();
        referredOther.setReferrerUserId(userId);
        referredOther.setReferredUserId(otherUserId);
        referredOther.setStatus(Referral.STATUS_REGISTERED);
        referralRepository.save(referredOther);
```

- [ ] **Step 3: Restore `ReferralRepository`**

Replace the full contents of `backend/src/main/java/com/finora/repository/ReferralRepository.java`:

```java
package com.finora.repository;

import com.finora.entity.Referral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    List<Referral> findByReferrerUserIdOrderByCreatedAtDesc(UUID referrerUserId);

    /** {@code referred_user_id} is unique (V101) -- at most one row can ever match. Used by
     *  {@code ReferralService.onPlanChanged} to find the referral (if any) a newly-paying user
     *  arrived through. */
    Optional<Referral> findByReferredUserId(UUID referredUserId);

    /** Admin Portal, Referral dashboard list -- grows with referral volume (roughly bounded by
     *  user count, same shape of risk as SubscriptionRepository.findAllByOrderByCreatedAtDesc's
     *  own doc comment), so this replaces an unconditional {@code findAll} the same way. */
    Page<Referral> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** AccountPurgeSweepService -- referrals the purged user made as a referrer. Does not touch
     *  the OTHER user's own row-half; see {@link #deleteByReferredUserId} for that. */
    void deleteByReferrerUserId(UUID referrerUserId);

    /** AccountPurgeSweepService -- the (at most one) referral row where the purged user is the one
     *  who was referred. Deleting this loses the record of who referred them, an accepted
     *  consequence of a full account purge, same as any other joint record this sweep removes
     *  without preserving the other party's half. */
    void deleteByReferredUserId(UUID referredUserId);
}
```

- [ ] **Step 4: Restore `ReferralDtos`**

Replace the full contents of `backend/src/main/java/com/finora/dto/ReferralDtos.java`:

```java
package com.finora.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Referral program DTOs (proposal §4) -- the user-facing "my code / my referrals / my wallet"
 *  surface and the admin-facing referral dashboard, kept together the same way BillingDtos keeps
 *  its user- and admin-facing records together. */
public class ReferralDtos {

    /** GET /api/v1/referrals/my-code -- lazily generated on first request, see
     *  {@code ReferralService.myCode}. */
    public record MyReferralCodeDto(String code) {}

    /** One row in a user's own "who I referred" list. */
    public record MyReferralDto(
            UUID referralId, String referredUserFullName, String status, BigDecimal reward, Instant createdAt
    ) {}

    /** GET /api/v1/referrals/mine. {@code code} is the user's own shareable code (kept at the top
     *  level, not a separate round trip -- both web and mobile read it directly off this
     *  response). {@code walletBalance} is the same computed SUM
     *  {@code WalletLedgerRepository.sumAmountByUserId} returns -- never a stored field. */
    public record MyReferralsDto(String code, List<MyReferralDto> referrals, BigDecimal walletBalance) {}

    /** Admin Portal, Referral dashboard -- one row per referral, both parties identified (an admin
     *  reviewing for abuse needs to see who's on each side, unlike the user-facing view above). */
    public record AdminReferralSummaryDto(
            UUID referralId,
            UUID referrerUserId, String referrerEmail, String referrerFullName,
            UUID referredUserId, String referredEmail, String referredFullName,
            String status, BigDecimal reward, Instant createdAt
    ) {}

    /** Admin-only, manual (the actual reward amount is a per-referral product decision -- see
     *  {@code ReferralService.creditReward}'s own doc comment for why crediting is an admin
     *  action rather than an automatic one). */
    public record CreditReferralRewardRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Reward amount must be greater than zero") BigDecimal amount,
            @NotBlank(message = "A reason is required") String reason
    ) {}
}
```

- [ ] **Step 5: Compile check**

Run: `cd backend && ./mvnw -q -DskipTests compile`
Expected: FAILS — `ReferralService`/`ReferralController` (Task 2/4) still reference the old
2-argument constructor and old `MyReferralsDto(code, referralCount)` shape. This is expected;
Task 2 fixes it. Do not attempt to make this task compile in isolation.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/entity/Referral.java \
        backend/src/main/java/com/finora/repository/ReferralRepository.java \
        backend/src/main/java/com/finora/dto/ReferralDtos.java \
        backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java
git commit -m "refactor(referrals): restore status/reward lifecycle to entity, repository, DTOs"
```

---

## Task 2: Restore `ReferralService` (trigger + admin crediting)

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReferralService.java`
- Modify: `backend/src/test/java/com/finora/service/ReferralServiceTest.java`

**Interfaces:**
- Consumes: `Referral`/`ReferralRepository`/`ReferralDtos` from Task 1;
  `WalletLedgerEntry`/`WalletLedgerRepository` (unchanged, already exist);
  `RefreshTokenRepository.findDistinctLastSeenIpsByUserId(UUID)` (unchanged, already exists);
  `UserRepository`, `AuditService.record(UUID, String, String, UUID, Map)` (unchanged).
- Produces: `ReferralService.onPlanChanged(UUID userId, String newPlanCode)` (note: **no**
  `actingAdminId` parameter — the old pre-descope signature took one because it was only ever
  called from the admin-manual `changePlan`; this version is called from webhook handlers with no
  admin in scope, so the audit write below drops the `actorId` field it used to carry),
  `ReferralService.creditReward(UUID referralId, BigDecimal amount, String reason, UUID actingAdminId)`,
  `ReferralService.myReferrals(UUID userId)` returning `MyReferralsDto`, `ReferralService.listAll(int page, int size)`
  returning `PagedResponse<AdminReferralSummaryDto>` — all consumed by Task 3/4.

- [ ] **Step 1: Write the failing tests**

Replace the full contents of `backend/src/test/java/com/finora/service/ReferralServiceTest.java`:

```java
package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.entity.User;
import com.finora.entity.WalletLedgerEntry;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReferralServiceTest {

    private ReferralCodeRepository referralCodeRepository;
    private ReferralRepository referralRepository;
    private WalletLedgerRepository walletLedgerRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private ReferralService service;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID referredId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralCodeRepository = mock(ReferralCodeRepository.class);
        referralRepository = mock(ReferralRepository.class);
        walletLedgerRepository = mock(WalletLedgerRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        service = new ReferralService(referralCodeRepository, referralRepository, walletLedgerRepository,
                refreshTokenRepository, userRepository, auditService);
        when(referralRepository.save(any(Referral.class))).thenAnswer(inv -> {
            Referral r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        when(referralCodeRepository.save(any(ReferralCode.class))).thenAnswer(inv -> {
            ReferralCode c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(any())).thenReturn(List.of());
    }

    @Test
    void myCode_generatesAndPersistsOne_whenTheUserHasNoneYet() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.empty());
        when(referralCodeRepository.existsByCode(any())).thenReturn(false);

        String code = service.myCode(referrerId);

        assertThat(code).isNotBlank();
        verify(referralCodeRepository).save(any(ReferralCode.class));
    }

    @Test
    void redeemCode_isANoOp_whenTheCodeIsBlank() {
        service.redeemCode(referredId, "  ");

        verifyNoInteractions(referralCodeRepository);
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeemCode_isASilentNoOp_whenTheCodeIsNotRecognized_neverBlockingSignup() {
        when(referralCodeRepository.findByCode("BADCODE1")).thenReturn(Optional.empty());

        service.redeemCode(referredId, "badcode1");

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void redeemCode_isASilentNoOp_whenTheCodeBelongsToTheAccountBeingCreated() {
        ReferralCode ownCode = new ReferralCode();
        ownCode.setUserId(referredId);
        ownCode.setCode("SELFCODE");
        when(referralCodeRepository.findByCode("SELFCODE")).thenReturn(Optional.of(ownCode));

        service.redeemCode(referredId, "selfcode");

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void redeemCode_createsARegisteredReferral_forAValidCode() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("VALIDCOD");
        when(referralCodeRepository.findByCode("VALIDCOD")).thenReturn(Optional.of(code));

        service.redeemCode(referredId, "  validcod  ");

        var captor = org.mockito.ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(captor.capture());
        assertThat(captor.getValue().getReferrerUserId()).isEqualTo(referrerId);
        assertThat(captor.getValue().getReferredUserId()).isEqualTo(referredId);
        assertThat(captor.getValue().getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
        verify(auditService).record(eq(referredId), eq("REFERRAL_REGISTERED"), eq("Referral"), any(),
                eq(java.util.Map.of("referrerUserId", referrerId.toString())));
    }

    @Test
    void onPlanChanged_movesRegisteredToSubscribed() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onPlanChanged(referredId, "PLUS");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
        verify(referralRepository).save(referral);
        verify(auditService).record(eq(referredId), eq("REFERRAL_SUBSCRIBED"), eq("Referral"), any(), any());
    }

    @Test
    void onPlanChanged_isANoOp_whenTheUserWasNeverReferred() {
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.empty());

        service.onPlanChanged(referredId, "PLUS");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onPlanChanged_isANoOp_whenTheReferralIsNotCurrentlyRegistered() {
        Referral referral = new Referral();
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onPlanChanged(referredId, "PREMIUM");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onPlanChanged_isANoOp_forADowngradeToFree() {
        service.onPlanChanged(referredId, "FREE");

        verifyNoInteractions(referralRepository);
    }

    @Test
    void myReferrals_includesTheCodeListAndWalletBalance() {
        ReferralCode existing = new ReferralCode();
        existing.setUserId(referrerId);
        existing.setCode("ABCD1234");
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(existing));

        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REWARDED);
        referral.setReward(new BigDecimal("250.00"));
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of(referral));

        User referred = new User();
        ReflectionTestUtils.setField(referred, "id", referredId);
        referred.setFullName("Jane Doe");
        when(userRepository.findAllById(any())).thenReturn(List.of(referred));
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(new BigDecimal("250.00"));

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.code()).isEqualTo("ABCD1234");
        assertThat(dto.referrals()).hasSize(1);
        assertThat(dto.referrals().get(0).referredUserFullName()).isEqualTo("Jane Doe");
        assertThat(dto.referrals().get(0).status()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(dto.walletBalance()).isEqualByComparingTo("250.00");
    }

    @Test
    void listAll_mapsReferrerAndReferredIdentity() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        Page<Referral> page = new PageImpl<>(List.of(referral), PageRequest.of(0, 20), 1);
        when(referralRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);

        User referrer = new User();
        ReflectionTestUtils.setField(referrer, "id", referrerId);
        referrer.setEmail("referrer@example.com");
        User referred = new User();
        ReflectionTestUtils.setField(referred, "id", referredId);
        referred.setEmail("referred@example.com");
        when(userRepository.findAllById(any())).thenReturn(List.of(referrer, referred));

        PagedResponse<AdminReferralSummaryDto> result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).referrerEmail()).isEqualTo("referrer@example.com");
        assertThat(result.content().get(0).referredEmail()).isEqualTo("referred@example.com");
    }

    @Test
    void creditReward_rejectsAReferralThatIsNotYetSubscribed() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findById(any())).thenReturn(Optional.of(referral));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), new BigDecimal("100"), "test", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("current status: REGISTERED");
        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    void creditReward_rejectsWhenReferrerAndReferredShareADeviceOrIp() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("1.2.3.4"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("1.2.3.4"));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), new BigDecimal("100"), "test", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("self-referral");
        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    void creditReward_writesAWalletEntryAndMarksTheReferralRewarded() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));

        service.creditReward(referral.getId(), new BigDecimal("250.00"), "successful referral", adminId);

        var captor = org.mockito.ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(referrerId);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("250.00");
        assertThat(captor.getValue().getReason()).isEqualTo(WalletLedgerEntry.REASON_REFERRAL_REWARD);
        assertThat(captor.getValue().getReferenceId()).isEqualTo(referral.getId());

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(referral.getReward()).isEqualByComparingTo("250.00");
        verify(auditService).record(eq(referrerId), eq("REFERRAL_REWARD_CREDITED"), eq("Referral"), any(), any());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q -Dtest=ReferralServiceTest test`
Expected: FAIL to compile — `ReferralService`'s constructor and methods don't match yet.

- [ ] **Step 3: Restore `ReferralService`**

Replace the full contents of `backend/src/main/java/com/finora/service/ReferralService.java`:

```java
package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.entity.User;
import com.finora.entity.WalletLedgerEntry;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The referral program (proposal §4) -- codes, invite tracking, and reward crediting. Reward
 * CREDITING is deliberately admin-manual (see {@link #creditReward}), not automatic: the actual
 * reward amount is a per-referral product decision, same reasoning {@code SubscriptionService}
 * gives for why admin-manual plan grants exist. Everything ELSE in the lifecycle -- code
 * issuance, redemption at registration, and the REGISTERED -> SUBSCRIBED transition -- is
 * automatic, because none of those steps require inventing a business term.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            WalletLedgerRepository walletLedgerRepository, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuditService auditService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** Lazily creates the user's own shareable code on first request -- there is no natural
     *  earlier moment (registration itself is the one time we can't hand out "your own" code, since
     *  the account doesn't exist yet). */
    @Transactional
    public String myCode(UUID userId) {
        return referralCodeRepository.findByUserId(userId)
                .map(ReferralCode::getCode)
                .orElseGet(() -> {
                    ReferralCode created = new ReferralCode();
                    created.setUserId(userId);
                    created.setCode(generateUniqueCode());
                    return referralCodeRepository.save(created).getCode();
                });
    }

    /** 8 uppercase hex characters -- same SecureRandom + hex convention as
     *  {@code AdminMfaService.generateRecoveryCodes}, short enough to type or paste into a
     *  registration field. Retried on the (astronomically unlikely) collision against the UNIQUE
     *  column rather than trusting one draw. */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] raw = new byte[4];
            secureRandom.nextBytes(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02X", b));
            String code = hex.toString();
            if (!referralCodeRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique referral code after 5 attempts.");
    }

    /**
     * Called from {@code AuthService.register()} only -- not the shared {@code createUserRecord}
     * helper, so admin-assisted signup ({@code adminCreateUser}) never creates a referral (there is
     * no organic acquisition to track there). Fails silently (logs, does not throw) on a
     * missing/invalid code: a mistyped or stale referral code must never block someone from
     * completing registration -- the one thing this method is not allowed to do is turn a cosmetic
     * referral link into a hard signup failure. Self-referral is rejected the same way: since
     * {@code referredUserId} is a brand-new account, it can never already own the code being
     * redeemed today, but the check is kept explicit rather than relying on that being true forever.
     */
    @Transactional
    public void redeemCode(UUID referredUserId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return;

        Optional<ReferralCode> code = referralCodeRepository.findByCode(rawCode.trim().toUpperCase());
        if (code.isEmpty()) {
            log.info("Referral code {} not recognized at registration for user {} -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }
        if (code.get().getUserId().equals(referredUserId)) {
            log.info("Referral code {} belongs to the account being created ({}) -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }

        Referral referral = new Referral();
        referral.setReferrerUserId(code.get().getUserId());
        referral.setReferredUserId(referredUserId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        referral = referralRepository.save(referral);

        auditService.record(referredUserId, "REFERRAL_REGISTERED", "Referral", referral.getId(),
                Map.of("referrerUserId", code.get().getUserId().toString()));
    }

    /**
     * Called from {@code RazorpayWebhookDispatcher.handleCharged} and
     * {@code RevenueCatWebhookDispatcher.handleInitialPurchase} -- both represent a real, confirmed
     * charge, not merely a mandate authorization (design spec §5: {@code subscription.activated}
     * can fire with zero funds movement, so it is deliberately NOT a call site for this). A purely
     * factual transition (this user is now on a paying plan) -- no business term is being invented
     * by observing it, so it happens automatically, unlike reward crediting. Silently a no-op if
     * the user was never referred, was already past REGISTERED, or {@code newPlanCode} is FREE (a
     * downgrade/reconciliation must never re-trigger or reverse this). Takes no admin id -- unlike
     * the reward-crediting audit trail below, there is no admin in scope at a webhook call site.
     */
    @Transactional
    public void onPlanChanged(UUID userId, String newPlanCode) {
        if ("FREE".equals(newPlanCode)) return;
        referralRepository.findByReferredUserId(userId)
                .filter(r -> Referral.STATUS_REGISTERED.equals(r.getStatus()))
                .ifPresent(r -> {
                    r.setStatus(Referral.STATUS_SUBSCRIBED);
                    referralRepository.save(r);
                    auditService.record(userId, "REFERRAL_SUBSCRIBED", "Referral", r.getId(),
                            Map.of("referrerUserId", r.getReferrerUserId().toString(), "planCode", newPlanCode));
                });
    }

    @Transactional(readOnly = true)
    public MyReferralsDto myReferrals(UUID userId) {
        String code = referralCodeRepository.findByUserId(userId).map(ReferralCode::getCode).orElse(null);
        var referrals = referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, User> usersById = userRepository.findAllById(
                referrals.stream().map(Referral::getReferredUserId).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));

        var dtos = referrals.stream().map(r -> {
            User referred = usersById.get(r.getReferredUserId());
            return new MyReferralDto(r.getId(), referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }).toList();

        BigDecimal balance = walletLedgerRepository.sumAmountByUserId(userId);
        return new MyReferralsDto(code, dtos, balance);
    }

    /** Admin Portal, Referral dashboard. An unconditional {@code findAll()} across the whole table
     *  would grow with referral volume -- same reasoning {@code SubscriptionService.listAll}'s own
     *  doc comment gives for the identical fix there. The user batch-fetch below is already scoped
     *  to just this page's referrer/referred ids, not the whole table. */
    @Transactional(readOnly = true)
    public PagedResponse<AdminReferralSummaryDto> listAll(int page, int size) {
        Page<Referral> referrals = referralRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size)));
        Set<UUID> userIds = new HashSet<>();
        referrals.forEach(r -> { userIds.add(r.getReferrerUserId()); userIds.add(r.getReferredUserId()); });
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return PagedResponse.of(referrals.map(r -> {
            User referrer = usersById.get(r.getReferrerUserId());
            User referred = usersById.get(r.getReferredUserId());
            return new AdminReferralSummaryDto(
                    r.getId(),
                    r.getReferrerUserId(), referrer != null ? referrer.getEmail() : null, referrer != null ? referrer.getFullName() : null,
                    r.getReferredUserId(), referred != null ? referred.getEmail() : null, referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }));
    }

    /**
     * Admin-only, manual (see this class's own doc comment for why). Fails closed on suspected
     * self-referral -- reusing {@code RefreshToken}'s own device/IP capture, not a new
     * fingerprinting mechanism. Idempotent by construction: only a referral currently at SUBSCRIBED
     * can be credited, so retrying against an already-REWARDED referral is rejected rather than
     * double-crediting the wallet.
     */
    @Transactional
    public void creditReward(UUID referralId, BigDecimal amount, String reason, UUID actingAdminId) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Referral not found."));
        if (!Referral.STATUS_SUBSCRIBED.equals(referral.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a referral whose referred user has subscribed can be credited (current status: "
                            + referral.getStatus() + ").");
        }
        if (sharesADeviceOrIp(referral.getReferrerUserId(), referral.getReferredUserId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Possible self-referral detected -- these two accounts share a device/IP. Reward not credited.");
        }

        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setUserId(referral.getReferrerUserId());
        entry.setAmount(amount);
        entry.setReason(WalletLedgerEntry.REASON_REFERRAL_REWARD);
        entry.setReferenceId(referral.getId());
        walletLedgerRepository.save(entry);

        referral.setStatus(Referral.STATUS_REWARDED);
        referral.setReward(amount);
        referralRepository.save(referral);

        auditService.record(referral.getReferrerUserId(), "REFERRAL_REWARD_CREDITED", "Referral", referral.getId(),
                Map.of("amount", amount.toString(), "reason", reason, "actorId", actingAdminId.toString()));
    }

    private boolean sharesADeviceOrIp(UUID referrerUserId, UUID referredUserId) {
        Set<String> referrerIps = new HashSet<>(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerUserId));
        if (referrerIps.isEmpty()) return false;
        return refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredUserId).stream()
                .anyMatch(referrerIps::contains);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q -Dtest=ReferralServiceTest test`
Expected: PASS, all 14 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/ReferralService.java \
        backend/src/test/java/com/finora/service/ReferralServiceTest.java
git commit -m "feat(referrals): restore reward crediting and the SUBSCRIBED/REWARDED lifecycle"
```

---

## Task 3: Wire the trigger into the payment webhooks

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`
- Modify: `backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`

**Interfaces:**
- Consumes: `ReferralService.onPlanChanged(UUID, String)` from Task 2.
- Produces: nothing new consumed elsewhere — this task only wires an existing method into two
  existing dispatchers.

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`:

`ReferralRepository` and `ReferralCodeRepository` are already covered by the existing
`import com.finora.repository.*;` wildcard, and `ReferralService` needs no import at all — this
test class is itself in package `com.finora.service`, same as `ReferralService`. No new imports
are needed.

Add this autowired field alongside the existing ones (after `@Autowired private PaymentRepository paymentRepository;`):

```java
    @Autowired private ReferralService referralService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReferralCodeRepository referralCodeRepository;
```

Add these two tests at the end of the class, just before the final closing `}`:

```java
    @Test
    void chargedAdvancesAReferredUsersReferralToSubscribed() {
        User referrer = createUser();
        String code = referralService.myCode(referrer.getId());
        User referred = createUser();
        referralService.redeemCode(referred.getId(), code);

        subscriptionService.provisionFreeSubscription(referred.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String razorpayPlanId = "plan_test_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(referred.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_referral_test", "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.charged", payload);

        Referral referral = referralRepository.findByReferredUserId(referred.getId()).orElseThrow();
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
    }

    @Test
    void activatedAloneDoesNotAdvanceTheReferral_onlyChargedDoes() {
        // The exact bug the Subscription Billing V2 plan flagged: subscription.activated can fire
        // with no funds movement, so it must never be the referral trigger on its own.
        User referrer = createUser();
        String code = referralService.myCode(referrer.getId());
        User referred = createUser();
        referralService.redeemCode(referred.getId(), code);
        subscriptionService.provisionFreeSubscription(referred.getId());

        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(referred.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("1299.00"));
        subscriptionOrderRepository.save(order);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.activated", payload);

        Referral referral = referralRepository.findByReferredUserId(referred.getId()).orElseThrow();
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q -Dtest=RazorpayWebhookDispatcherIT test`
Expected: FAIL — `chargedAdvancesAReferredUsersReferralToSubscribed` fails because
`RazorpayWebhookDispatcher` doesn't call `onPlanChanged` yet
(`activatedAloneDoesNotAdvanceTheReferral_onlyChargedDoes` passes trivially since nothing calls it
from `handleActivated` either — that's fine, it becomes a real regression guard once Step 3 lands).

- [ ] **Step 3: Wire `RazorpayWebhookDispatcher.handleCharged`**

In `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`:

Add `import com.finora.service.ReferralService;` is unnecessary (same package) — no new import
needed. Add a field and constructor parameter. Find:

```java
    private final UserRepository userRepository;
    private final EmailProvider emailProvider;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository,
                                      RazorpaySubscriptionGateway gateway,
                                      UserRepository userRepository,
                                      EmailProvider emailProvider) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
    }
```

Replace with:

```java
    private final UserRepository userRepository;
    private final EmailProvider emailProvider;
    private final ReferralService referralService;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository,
                                      RazorpaySubscriptionGateway gateway,
                                      UserRepository userRepository,
                                      EmailProvider emailProvider,
                                      ReferralService referralService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
        this.referralService = referralService;
    }
```

Then, in `handleCharged`, find the end of the method:

```java
        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_RENEWED);
        event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);
    }
```

Replace with:

```java
        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_RENEWED);
        event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);

        // design spec §5 (referral reward ledger): subscription.charged is the real-charge signal
        // this is keyed off (not subscription.activated, which can fire with zero funds movement).
        // Fires on every charge including renewals -- onPlanChanged's own REGISTERED-only guard
        // makes repeat calls a no-op, so this needs no idempotency handling of its own.
        Plan chargedPlan = planRepository.findById(subscription.getPlanId()).orElse(null);
        if (chargedPlan != null) {
            referralService.onPlanChanged(subscription.getUserId(), chargedPlan.getCode());
        }
    }
```

- [ ] **Step 4: Wire `RevenueCatWebhookDispatcher.handleInitialPurchase`**

In `backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java`, find:

```java
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;

    public RevenueCatWebhookDispatcher(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                        IapProductRepository iapProductRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
    }
```

Replace with:

```java
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;
    private final ReferralService referralService;

    public RevenueCatWebhookDispatcher(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                        IapProductRepository iapProductRepository, ReferralService referralService) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
        this.referralService = referralService;
    }
```

Then, in `handleInitialPurchase`, find:

```java
        subscription.setRevenuecatOriginalTransactionId((String) eventPayload.get("original_transaction_id"));
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);
    }
```

Replace with:

```java
        subscription.setRevenuecatOriginalTransactionId((String) eventPayload.get("original_transaction_id"));
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);

        // design spec §5 (referral reward ledger): the store has already charged the user by the
        // time this webhook arrives, so INITIAL_PURCHASE is a real-charge signal, same as
        // Razorpay's subscription.charged. onPlanChanged's own REGISTERED-only guard keeps this
        // safe even though this handler doesn't run on renewals (see handleRenewal above).
        referralService.onPlanChanged(subscription.getUserId(), plan.getCode());
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q -Dtest=RazorpayWebhookDispatcherIT test`
Expected: PASS, all tests in the class green (existing ones plus the two new ones).

Run: `cd backend && ./mvnw -q -Dtest=RevenueCatWebhookControllerIT test`
Expected: PASS — confirms the new constructor parameter didn't break existing RevenueCat wiring.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
        backend/src/main/java/com/finora/service/RevenueCatWebhookDispatcher.java \
        backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java
git commit -m "feat(referrals): trigger the reward-eligible transition off real charges only"
```

---

## Task 4: Restore the referral API surface (user-facing + admin)

**Files:**
- Modify: `backend/src/main/java/com/finora/controller/ReferralController.java`
- Create: `backend/src/main/java/com/finora/controller/AdminReferralController.java`
- Modify: `backend/src/test/java/com/finora/controller/ReferralControllerIT.java`
- Create: `backend/src/test/java/com/finora/controller/AdminReferralControllerIT.java`

**Interfaces:**
- Consumes: `ReferralService.myReferrals`, `.listAll`, `.creditReward` from Task 2.
- Produces: `GET /api/v1/referrals/mine` returning the richer `MyReferralsDto`;
  `GET /api/v1/admin/referrals`, `POST /api/v1/admin/referrals/{id}/credit` — consumed by Task 6/7/8's
  frontend clients.

- [ ] **Step 1: Write the failing test — user-facing controller**

In `backend/src/test/java/com/finora/controller/ReferralControllerIT.java`, find:

```java
    @Test
    void mine_returnsTheCodeAndZeroCount_forAUserWhoHasReferredNoOne() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("code").asText()).isNotBlank();
        assertThat(data.get("referralCount").asInt()).isZero();
    }
```

Replace with:

```java
    @Test
    void mine_returnsTheCodeAnEmptyListAndZeroBalance_forAUserWhoHasReferredNoOne() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("code").asText()).isNotBlank();
        assertThat(data.get("referrals").size()).isZero();
        assertThat(data.get("walletBalance").asDouble()).isEqualTo(0.0);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=ReferralControllerIT test`
Expected: FAIL — current response still has `referralCount`, not `referrals`/`walletBalance`.

- [ ] **Step 3: Update `ReferralController`**

Replace the full contents of `backend/src/main/java/com/finora/controller/ReferralController.java`:

```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReferralDtos.MyReferralCodeDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.security.CurrentUser;
import com.finora.service.ReferralService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current user's own referral code, their referrals, and their wallet balance. */
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService referralService;
    private final CurrentUser currentUser;

    public ReferralController(ReferralService referralService, CurrentUser currentUser) {
        this.referralService = referralService;
        this.currentUser = currentUser;
    }

    @GetMapping("/my-code")
    public ApiResponse<MyReferralCodeDto> myCode() {
        return ApiResponse.ok(new MyReferralCodeDto(referralService.myCode(currentUser.id())));
    }

    @GetMapping("/mine")
    public ApiResponse<MyReferralsDto> mine() {
        return ApiResponse.ok(referralService.myReferrals(currentUser.id()));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest=ReferralControllerIT test`
Expected: PASS.

- [ ] **Step 5: Write the failing test — admin controller**

Create `backend/src/test/java/com/finora/controller/AdminReferralControllerIT.java`:

```java
package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AuthService;
import com.finora.service.ReferralService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end -- proves REFERRAL_MANAGEMENT_VIEW/_MANAGE gate separately (V101), and the full
 *  lifecycle against a real database: a referral code redeemed at registration (REGISTERED), a
 *  real webhook-driven charge advancing it (SUBSCRIBED), and an admin credit finishing it
 *  (REWARDED, with the wallet balance actually moving). */
class AdminReferralControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ReferralService referralService;
    @Autowired private AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-referral-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Referral IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromListingReferrals() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromCreditingAReward() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals/" + UUID.randomUUID() + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "reason", "test"), bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creditingABareRegisteredReferral_isRejectedWithConflict() throws Exception {
        User referrer = createUser("USER");
        String code = referralService.myCode(referrer.getId());
        String referredEmail = "referred-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(referredEmail, "Password123", "Referred Person",
                "+919876500778" /* synthetic-ok */, code));
        User referred = userRepository.findByEmailIgnoreCaseAndAccountScope(referredEmail, User.SCOPE_USER)
                .orElseThrow();
        User admin = createUser("ADMIN");

        ResponseEntity<String> list = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        UUID referralId = UUID.fromString(findRow(list, referred.getId()).get("referralId").asText());

        ResponseEntity<String> creditResponse = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "too early"), bearerFor(admin)), String.class);

        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fullLifecycle_registrationToSubscriptionToCreditedReward_movesTheWalletBalance() throws Exception {
        User referrer = createUser("USER");
        String code = referralService.myCode(referrer.getId());

        String referredEmail = "referred-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(referredEmail, "Password123", "Referred Person",
                "+919876500777" /* synthetic-ok */, code));
        User referred = userRepository.findByEmailIgnoreCaseAndAccountScope(referredEmail, User.SCOPE_USER)
                .orElseThrow();

        User admin = createUser("ADMIN");

        ResponseEntity<String> afterRegister = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        JsonNode row = findRow(afterRegister, referred.getId());
        assertThat(row.get("status").asText()).isEqualTo("REGISTERED");
        UUID referralId = UUID.fromString(row.get("referralId").asText());

        // The real automatic trigger (Task 3), not a direct service call -- proves the actual
        // webhook wiring, not just ReferralService in isolation.
        referralService.onPlanChanged(referred.getId(), "PLUS");

        ResponseEntity<String> afterSubscribe = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(findRow(afterSubscribe, referred.getId()).get("status").asText()).isEqualTo("SUBSCRIBED");

        ResponseEntity<String> creditResponse = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "successful referral"), bearerFor(admin)), String.class);
        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> mineResponse = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(referrer)), String.class);
        JsonNode mine = mapper.readTree(mineResponse.getBody()).get("data");
        assertThat(mine.get("walletBalance").asDouble()).isEqualTo(250.0);
        assertThat(mine.get("referrals").get(0).get("status").asText()).isEqualTo("REWARDED");

        ResponseEntity<String> secondCredit = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "duplicate attempt"), bearerFor(admin)), String.class);
        assertThat(secondCredit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private JsonNode findRow(ResponseEntity<String> response, UUID referredUserId) throws Exception {
        JsonNode data = mapper.readTree(response.getBody()).get("data").get("content");
        for (JsonNode row : data) {
            if (row.get("referredUserId").asText().equals(referredUserId.toString())) return row;
        }
        throw new AssertionError("No referral row found for referredUserId=" + referredUserId);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=AdminReferralControllerIT test`
Expected: FAIL to compile — `AdminReferralController` doesn't exist yet.

- [ ] **Step 7: Create `AdminReferralController`**

Create `backend/src/main/java/com/finora/controller/AdminReferralController.java`:

```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.CreditReferralRewardRequest;
import com.finora.security.CurrentUser;
import com.finora.service.ReferralService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Admin Portal, Referral dashboard -- read access and reward crediting gated separately
 *  (REFERRAL_MANAGEMENT_VIEW vs. _MANAGE, V101), matching AdminSubscriptionController's own
 *  split. */
@RestController
@RequestMapping("/api/v1/admin/referrals")
public class AdminReferralController {

    private final ReferralService referralService;
    private final CurrentUser currentUser;

    public AdminReferralController(ReferralService referralService, CurrentUser currentUser) {
        this.referralService = referralService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('REFERRAL_MANAGEMENT_VIEW')")
    public ApiResponse<PagedResponse<AdminReferralSummaryDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(referralService.listAll(page, size));
    }

    @PostMapping("/{referralId}/credit")
    @PreAuthorize("hasAuthority('REFERRAL_MANAGEMENT_MANAGE')")
    public ApiResponse<Void> creditReward(@PathVariable UUID referralId, @Valid @RequestBody CreditReferralRewardRequest request) {
        referralService.creditReward(referralId, request.amount(), request.reason(), currentUser.id());
        return ApiResponse.ok(null, "Reward credited");
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest=AdminReferralControllerIT test`
Expected: PASS, all 4 tests green.

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: PASS — confirms nothing else (e.g. `SubscriptionServiceTest`,
`RevenueCatWebhookControllerIT`, `AccountPurgeSweepServiceIT`) broke from Tasks 1–4.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/finora/controller/ReferralController.java \
        backend/src/main/java/com/finora/controller/AdminReferralController.java \
        backend/src/test/java/com/finora/controller/ReferralControllerIT.java \
        backend/src/test/java/com/finora/controller/AdminReferralControllerIT.java
git commit -m "feat(referrals): restore the admin referral dashboard API"
```

---

## Task 5: Restore the admin-portal Referrals page

**Files:**
- Modify: `admin-portal/src/App.tsx`
- Modify: `admin-portal/src/components/Sidebar.tsx`
- Modify: `admin-portal/src/context/AdminAuthContext.tsx`
- Modify: `admin-portal/src/api/endpoints.ts`
- Modify: `admin-portal/src/types/index.ts`
- Create: `admin-portal/src/pages/Referrals.tsx`
- Create: `admin-portal/src/pages/Referrals.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/admin/referrals`, `POST /api/v1/admin/referrals/{id}/credit` from Task 4.
- Produces: nothing consumed by later tasks — this is a leaf UI page.

- [ ] **Step 1: Add the `AdminReferralSummaryDto` type**

In `admin-portal/src/types/index.ts`, find:

```typescript
/** D-28 PR4-A. Mirrors backend BillingDtos.SubscriptionSummaryDto exactly -- one row per user's
 *  current subscription, joined with their plan and account details for the admin list. */
export interface SubscriptionSummaryDto {
```

Replace with:

```typescript
/** Mirrors backend ReferralDtos.AdminReferralSummaryDto exactly -- one row per referral, both
 *  parties identified for abuse review. */
export interface AdminReferralSummaryDto {
  referralId: string;
  referrerUserId: string;
  referrerEmail: string | null;
  referrerFullName: string | null;
  referredUserId: string;
  referredEmail: string | null;
  referredFullName: string | null;
  status: string;
  reward: number | null;
  createdAt: string;
}

/** D-28 PR4-A. Mirrors backend BillingDtos.SubscriptionSummaryDto exactly -- one row per user's
 *  current subscription, joined with their plan and account details for the admin list. */
export interface SubscriptionSummaryDto {
```

- [ ] **Step 2: Add the `adminReferralsApi` client**

In `admin-portal/src/api/endpoints.ts`, find the import block's type list and add
`AdminReferralSummaryDto` back in (alphabetically, next to `AdminUpdateUserRequest`):

```typescript
  AccountDto, ActivationFunnelDto, ActivityTrendPointDto, AdminUpdateUserRequest, AuditLogDto, BankDto, CategoryConfidencePoint,
```

Replace with:

```typescript
  AccountDto, ActivationFunnelDto, ActivityTrendPointDto, AdminReferralSummaryDto, AdminUpdateUserRequest, AuditLogDto, BankDto, CategoryConfidencePoint,
```

Then find:

```typescript
export const adminSystemApi = {
```

Insert immediately before it:

```typescript
// REFERRAL_MANAGEMENT_VIEW/_MANAGE-gated (V101), same split as adminSubscriptionsApi.
export const adminReferralsApi = {
  list: (page: number, size: number) =>
    api.get<PagedResponse<AdminReferralSummaryDto>>('/admin/referrals', { params: { page, size } }).then((r) => r.data),
  creditReward: (referralId: string, amount: number, reason: string) =>
    api.post(`/admin/referrals/${referralId}/credit`, { amount, reason }),
};

export const adminSystemApi = {
```

- [ ] **Step 3: Restore permission gate**

In `admin-portal/src/context/AdminAuthContext.tsx`, find:

```typescript
  'SUBSCRIPTION_MANAGEMENT_VIEW',
```

Replace with:

```typescript
  'SUBSCRIPTION_MANAGEMENT_VIEW',
  // Referrals page -- REFERRAL_MANAGEMENT_MANAGE (crediting a reward) is deliberately NOT listed,
  // since the frontend always renders the "Credit Reward" button and only the backend rejects an
  // unauthorized attempt, same reasoning as SUBSCRIPTION_MANAGEMENT_VIEW above.
  'REFERRAL_MANAGEMENT_VIEW',
```

- [ ] **Step 4: Restore the sidebar entry**

In `admin-portal/src/components/Sidebar.tsx`, find:

```typescript
  CreditCard, Plug, Waypoints, Lightbulb, ListOrdered, ChevronDown, ChevronRight, Bell, Clock, ShieldAlert,
```

Replace with:

```typescript
  CreditCard, Gift, Plug, Waypoints, Lightbulb, ListOrdered, ChevronDown, ChevronRight, Bell, Clock, ShieldAlert,
```

Then find:

```typescript
      { to: '/subscriptions', label: 'Subscriptions', icon: CreditCard, end: false, permission: 'SUBSCRIPTION_MANAGEMENT_VIEW' },
```

Replace with:

```typescript
      { to: '/subscriptions', label: 'Subscriptions', icon: CreditCard, end: false, permission: 'SUBSCRIPTION_MANAGEMENT_VIEW' },
      { to: '/referrals', label: 'Referrals', icon: Gift, end: false, permission: 'REFERRAL_MANAGEMENT_VIEW' },
```

- [ ] **Step 5: Restore the route**

In `admin-portal/src/App.tsx`, find:

```typescript
const Subscriptions = lazy(() => import('./pages/Subscriptions'));
```

Replace with:

```typescript
const Subscriptions = lazy(() => import('./pages/Subscriptions'));
const Referrals = lazy(() => import('./pages/Referrals'));
```

Then find:

```typescript
              <Route path="/subscriptions" element={<ProtectedRoute><Subscriptions /></ProtectedRoute>} />
```

Replace with:

```typescript
              <Route path="/subscriptions" element={<ProtectedRoute><Subscriptions /></ProtectedRoute>} />
              <Route path="/referrals" element={<ProtectedRoute><Referrals /></ProtectedRoute>} />
```

- [ ] **Step 6: Create `admin-portal/src/pages/Referrals.tsx`**

```tsx
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Users as UsersIcon } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { useNotify } from '../context/NotificationContext';
import { adminReferralsApi } from '../api/endpoints';
import type { AdminReferralSummaryDto } from '../types';

const PAGE_SIZE = 20;

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

function statusBadge(status: string) {
  switch (status) {
    case 'REWARDED': return 'text-success';
    case 'SUBSCRIBED': return 'text-primary';
    default: return 'text-muted';
  }
}

function userCell(email: string | null, fullName: string | null) {
  return (
    <div className="flex items-center gap-2.5">
      <span className="w-7 h-7 rounded-lg bg-bg border border-border flex items-center justify-center flex-shrink-0">
        <UsersIcon size={13} className="text-muted" />
      </span>
      <div className="min-w-0">
        <p className="font-medium text-ink truncate">{fullName ?? '(no name)'}</p>
        <p className="text-xs text-muted truncate">{email}</p>
      </div>
    </div>
  );
}

/**
 * Reward crediting is admin-manual (see backend ReferralService.creditReward's own doc comment
 * for why the actual amount is never invented automatically) -- this page is the only place it
 * happens, same "admin types the number, backend enforces the rules" pattern Subscriptions.tsx's
 * plan-change dropdown already established. "Admin credited referral reward" is a fixed reason,
 * same simplification Subscriptions.tsx's "Admin manual override" makes: refining reason-capture
 * UX is a later decision, not a blocker for this first cut.
 */
function ReferralsContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [page, setPage] = useState(0);
  const { data, isLoading } = useQuery({
    queryKey: ['admin-referrals', page],
    queryFn: () => adminReferralsApi.list(page, PAGE_SIZE),
  });

  const creditMutation = useMutation({
    mutationFn: ({ referralId, amount }: { referralId: string; amount: number }) =>
      adminReferralsApi.creditReward(referralId, amount, 'Admin credited referral reward'),
    onSuccess: (_data, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['admin-referrals'] });
      setAmounts((prev) => {
        const next = { ...prev };
        delete next[variables.referralId];
        return next;
      });
      notify.success('Reward credited.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to credit this reward.')),
  });

  const columns: DataTableColumn<AdminReferralSummaryDto>[] = [
    { header: 'Referrer', render: (r) => userCell(r.referrerEmail, r.referrerFullName) },
    { header: 'Referred', render: (r) => userCell(r.referredEmail, r.referredFullName) },
    {
      header: 'Status',
      render: (r) => <span className={`font-medium ${statusBadge(r.status)}`}>{r.status}</span>,
    },
    {
      header: 'Reward',
      render: (r) => {
        if (r.status === 'REWARDED') return `₹${r.reward}`;
        if (r.status !== 'SUBSCRIBED') return <span className="text-muted">—</span>;
        return (
          <div className="flex items-center gap-1.5">
            <input
              type="number"
              min="0.01"
              step="0.01"
              placeholder="Amount"
              value={amounts[r.referralId] ?? ''}
              onChange={(e) => setAmounts((prev) => ({ ...prev, [r.referralId]: e.target.value }))}
              className="w-24 text-xs border border-border rounded-lg px-2 py-1.5 bg-card text-ink"
            />
            <button
              type="button"
              disabled={creditMutation.isPending || !amounts[r.referralId]}
              onClick={() => creditMutation.mutate({ referralId: r.referralId, amount: Number(amounts[r.referralId]) })}
              className="text-xs font-semibold bg-primary text-white rounded-lg px-2.5 py-1.5 disabled:opacity-40"
            >
              Credit
            </button>
          </div>
        );
      },
    },
    { header: 'Since', render: (r) => r.createdAt.slice(0, 10), cellClassName: 'text-muted' },
  ];

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted max-w-xl">
        Every referral, both parties shown for abuse review. A reward can only be credited once a
        referral reaches Subscribed, and only once -- crediting is rejected if the two accounts
        share a device or IP.
      </p>
      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        keyFor={(r) => r.referralId}
        loading={isLoading}
        emptyMessage="No referrals yet."
      />
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function Referrals() {
  return (
    <AdminLayout title="Referrals" subtitle="Review referrals and credit rewards">
      <RequirePermission permission="REFERRAL_MANAGEMENT_VIEW">
        <ReferralsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
```

- [ ] **Step 7: Create `admin-portal/src/pages/Referrals.test.tsx`**

This follows the exact conventions already established in
`admin-portal/src/pages/Subscriptions.test.tsx` (`mockAdminAuthState` test helper, `ThemeContext`
mock required by `AdminLayout`'s `ThemeToggle`, `useNotify` mocked as a plain object since no
`NotificationProvider` is mounted in tests):

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { adminReferralsApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import type { AdminReferralSummaryDto } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminReferralsApi: { list: vi.fn(), creditReward: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

function row(overrides: Partial<AdminReferralSummaryDto> = {}): AdminReferralSummaryDto {
  return {
    referralId: 'referral-1',
    referrerUserId: 'referrer-1', referrerEmail: 'referrer@example.com', referrerFullName: 'Alice',
    referredUserId: 'referred-1', referredEmail: 'referred@example.com', referredFullName: 'Bob',
    status: 'SUBSCRIBED', reward: null, createdAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function pageOf(...rows: AdminReferralSummaryDto[]) {
  return { content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 };
}

describe('Referrals (admin portal)', () => {
  beforeEach(() => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW', 'REFERRAL_MANAGEMENT_MANAGE']);
    vi.mocked(adminReferralsApi.list).mockReset().mockResolvedValue(pageOf());
    vi.mocked(adminReferralsApi.creditReward).mockReset();
    notifySuccess.mockClear();
    notifyError.mockClear();
  });

  it('shows the empty state with no referrals', async () => {
    renderPage();
    expect(await screen.findByText(/no referrals yet/i)).toBeInTheDocument();
  });

  it('lets an admin credit a SUBSCRIBED referral', async () => {
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(row()));
    vi.mocked(adminReferralsApi.creditReward).mockResolvedValue(undefined as any);
    renderPage();

    await screen.findByText('Alice');
    fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '250' } });
    fireEvent.click(screen.getByText('Credit'));

    await waitFor(() =>
      expect(adminReferralsApi.creditReward).toHaveBeenCalledWith('referral-1', 250, 'Admin credited referral reward'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled());
  });

  it('shows a rupee amount for an already-REWARDED referral instead of the credit form', async () => {
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(row({ status: 'REWARDED', reward: 250 })));
    renderPage();

    await screen.findByText('Alice');
    expect(screen.getByText('₹250')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Amount')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 8: Run the tests**

Run: `cd admin-portal && npx vitest run src/pages/Referrals.test.tsx`
Expected: PASS, all 3 tests green.

- [ ] **Step 9: Run the admin-portal build and full suite**

Run: `cd admin-portal && npm run build`
Expected: PASS, no TypeScript errors.

Run: `cd admin-portal && npx vitest run`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add admin-portal/src/App.tsx admin-portal/src/components/Sidebar.tsx \
        admin-portal/src/context/AdminAuthContext.tsx admin-portal/src/api/endpoints.ts \
        admin-portal/src/types/index.ts admin-portal/src/pages/Referrals.tsx \
        admin-portal/src/pages/Referrals.test.tsx
git commit -m "feat(admin-portal): restore the Referrals dashboard"
```

---

## Task 6: Update the web Referrals page

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `frontend/src/pages/Referrals.tsx`
- Modify: `frontend/src/pages/Referrals.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/referrals/mine` (Task 4) returning `{code, referrals, walletBalance}`.
- Produces: nothing consumed by later tasks — leaf UI page. `Billing.tsx` is explicitly NOT
  touched here (Global Constraints).

- [ ] **Step 1: Write the failing tests**

Replace the full contents of `frontend/src/pages/Referrals.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { referralsApi } from '../api/endpoints';
import type { MyReferralEntry } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  referralsApi: { myCode: vi.fn(), mine: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function entry(overrides: Partial<MyReferralEntry> = {}): MyReferralEntry {
  return {
    referralId: 'referral-1',
    referredUserFullName: 'Jane Doe',
    status: 'REGISTERED',
    reward: null,
    createdAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('Referrals', () => {
  beforeEach(() => {
    vi.mocked(referralsApi.mine).mockReset();
  });

  it('shows the empty state and a zero balance when nothing has happened yet', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ code: 'ABCD1234', referrals: [], walletBalance: 0 });
    renderPage();

    expect(await screen.findByText(/no referrals yet/i)).toBeInTheDocument();
    expect(screen.getByText('₹0')).toBeInTheDocument();
  });

  it("renders the user's own referral link once the code loads", async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ code: 'ABCD1234', referrals: [], walletBalance: 0 });
    renderPage();

    const input = await screen.findByDisplayValue(/\/register\?ref=ABCD1234$/);
    expect(input).toBeInTheDocument();
  });

  it('renders a referral row with its status and reward', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234',
      referrals: [entry({ status: 'REWARDED', reward: 250 })],
      walletBalance: 250,
    });
    renderPage();

    expect(await screen.findByText('Jane Doe')).toBeInTheDocument();
    expect(screen.getByText('Rewarded')).toBeInTheDocument();
    expect(screen.getByText(/Earned ₹250/)).toBeInTheDocument();
    expect(screen.getByText('₹250')).toBeInTheDocument();
    expect(screen.queryByText(/no referrals yet/i)).not.toBeInTheDocument();
  });

  it('labels a merely-registered referral distinctly from a subscribed one', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234',
      referrals: [entry({ status: 'SUBSCRIBED' })],
      walletBalance: 0,
    });
    renderPage();

    expect(await screen.findByText('Subscribed')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Referrals.test.tsx`
Expected: FAIL — `MyReferralEntry` doesn't exist in `api/endpoints.ts` yet, and `Referrals.tsx`
doesn't render a per-referral list yet.

- [ ] **Step 3: Update the `referralsApi` type in `frontend/src/api/endpoints.ts`**

Find:

```typescript
// Refer & Earn MVP -- mirrors backend ReferralDtos exactly. Just a code and a count.
export interface MyReferralsDto {
  code: string;
  referralCount: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};
```

Replace with:

```typescript
// Referral program -- mirrors backend ReferralDtos exactly.
export interface MyReferralEntry {
  referralId: string;
  referredUserFullName: string | null;
  status: string;
  reward: number | null;
  createdAt: string;
}

export interface MyReferralsDto {
  code: string;
  referrals: MyReferralEntry[];
  walletBalance: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};
```

- [ ] **Step 4: Restore `frontend/src/pages/Referrals.tsx`**

Replace the full contents:

```tsx
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Gift, Copy, Check, Users } from 'lucide-react';
import { referralsApi } from '../api/endpoints';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState } from '../design-system';

function fmt(amount: number) {
  return '₹' + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'REWARDED': return { text: 'Rewarded', className: 'text-success bg-success-bg' };
    case 'SUBSCRIBED': return { text: 'Subscribed', className: 'text-primary bg-primary-light' };
    default: return { text: 'Registered', className: 'text-muted bg-bg' };
  }
}

/**
 * Refer & Earn -- a user's own shareable code, their referrals, and their wallet balance. The
 * reward AMOUNT a referral eventually earns is set by an admin (ReferralService.creditReward's
 * own doc comment explains why), so this page shows whatever REWARDED referrals actually earned
 * -- it never predicts or advertises a number up front.
 */
export default function Referrals() {
  const [copied, setCopied] = useState(false);
  const { data: mine, isLoading } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  const shareLink = mine ? `${window.location.origin}/register?ref=${mine.code}` : '';

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(shareLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API unavailable (older browser, insecure context) -- the link is already
      // visible in the field below for a manual select-and-copy, so there's nothing more useful
      // to do than leave it there.
    }
  }

  const referrals = mine?.referrals ?? [];

  return (
    <div className="space-y-4">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Refer & Earn</h1>
        <p className="text-sm text-muted">Share Fynora with friends and earn rewards when they join.</p>
      </div>

      <FinoraCard padding="lg">
        <div className="flex items-center gap-2.5 mb-3">
          <div className="w-9 h-9 rounded-full bg-primary-light flex items-center justify-center">
            <Gift size={16} className="text-primary" />
          </div>
          <div>
            <p className="text-sm font-semibold text-ink">Your referral link</p>
            <p className="text-xs text-muted">Anyone who signs up with this link is credited to you.</p>
          </div>
        </div>
        {isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={shareLink}
              className="flex-1 min-w-0 border border-border rounded-lg px-3 py-2 text-sm bg-bg text-ink"
              onFocus={(e) => e.target.select()}
            />
            <button
              type="button"
              onClick={handleCopy}
              className="flex items-center gap-1.5 bg-primary text-white text-xs font-semibold rounded-lg px-3 py-2 flex-shrink-0"
            >
              {copied ? <Check size={14} /> : <Copy size={14} />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
        )}
      </FinoraCard>

      <FinoraCard padding="lg">
        <p className="text-xs uppercase text-muted mb-1">Wallet balance</p>
        <p className="text-2xl font-bold text-ink">{isLoading ? '—' : fmt(mine?.walletBalance ?? 0)}</p>
      </FinoraCard>

      {!isLoading && referrals.length === 0 ? (
        <FinoraCard padding="lg">
          <EmptyState
            icon={Users}
            iconBg="bg-blue-100"
            iconColor="text-blue-600"
            title="No referrals yet"
            desc="Share your link above — when a friend signs up with it, they'll show up here."
          />
        </FinoraCard>
      ) : (
        <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
          <div className="divide-y divide-border">
            {referrals.map((r) => {
              const status = statusLabel(r.status);
              return (
                <div key={r.referralId} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-ink truncate">{r.referredUserFullName ?? 'A new user'}</p>
                    <p className="text-xs text-muted">
                      Joined {formatDate(r.createdAt)}{r.reward != null ? ` · Earned ${fmt(r.reward)}` : ''}
                    </p>
                  </div>
                  <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 ${status.className}`}>
                    {status.text}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Referrals.test.tsx`
Expected: PASS, all 4 tests green.

- [ ] **Step 6: Run the frontend build and full test suite**

Run: `cd frontend && npm run build`
Expected: PASS, no TypeScript errors.

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/pages/Referrals.tsx frontend/src/pages/Referrals.test.tsx
git commit -m "feat(web): show wallet balance and per-referral status on Refer & Earn"
```

---

## Task 7: Update the mobile Refer & Earn screen

**Files:**
- Modify: `mobile/src/api/endpoints.ts`
- Modify: `mobile/src/screens/ReferralsScreen.tsx`
- Modify: `mobile/src/screens/ReferralsScreen.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/referrals/mine` (Task 4).
- Produces: nothing consumed by later tasks — leaf UI screen.

- [ ] **Step 1: Write the failing test**

In `mobile/src/screens/ReferralsScreen.test.tsx`, find:

```typescript
  it('shows the code and a zero count for a user with no referrals yet', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderScreen();

    expect(await screen.findByText('ABCD1234')).toBeTruthy();
    expect(screen.getByText('0')).toBeTruthy();
  });

  it('shows the real referral count once it loads', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 7 });
    renderScreen();

    expect(await screen.findByText('7')).toBeTruthy();
  });
```

Replace with:

```typescript
  it('shows the code, a zero count, and a zero earned amount for a user with no referrals yet', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referrals: [], walletBalance: 0 });
    renderScreen();

    expect(await screen.findByText('ABCD1234')).toBeTruthy();
    expect(screen.getByText('0')).toBeTruthy();
    expect(screen.getByText('₹0')).toBeTruthy();
  });

  it('shows the real referral count, pending count, and earned amount once they load', async () => {
    api.mine.mockResolvedValue({
      code: 'ABCD1234',
      referrals: [
        { referralId: 'r1', referredUserFullName: 'Jane', status: 'SUBSCRIBED', reward: null, createdAt: '2026-09-01T00:00:00Z' },
        { referralId: 'r2', referredUserFullName: 'Jo', status: 'REWARDED', reward: 250, createdAt: '2026-08-20T00:00:00Z' },
      ],
      walletBalance: 250,
    });
    renderScreen();

    expect(await screen.findByText('2')).toBeTruthy();
    expect(screen.getByText('₹250')).toBeTruthy();
    expect(screen.getByText('1')).toBeTruthy(); // pending count: the one SUBSCRIBED-not-yet-REWARDED row
  });
```

Every other `api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 })` call in this file
(the copy/share/channel tests) needs the same shape update — find all remaining occurrences and
replace `referralCount: 0` with `referrals: [], walletBalance: 0`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd mobile && npx jest src/screens/ReferralsScreen.test.tsx`
Expected: FAIL — `ReferralsScreen` doesn't render an earned amount or pending count yet.

- [ ] **Step 3: Update the `referralsApi` type in `mobile/src/api/endpoints.ts`**

Find (lines 998-1007):

```typescript
// Refer & Earn MVP -- mirrors backend ReferralDtos exactly. Just a code and a count, ported from
// frontend/src/api/endpoints.ts's own copy.
export interface MyReferralsDto {
  code: string;
  referralCount: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};
```

Replace with:

```typescript
// Referral program -- mirrors backend ReferralDtos exactly, ported from
// frontend/src/api/endpoints.ts's own copy.
export interface MyReferralEntry {
  referralId: string;
  referredUserFullName: string | null;
  status: string;
  reward: number | null;
  createdAt: string;
}

export interface MyReferralsDto {
  code: string;
  referrals: MyReferralEntry[];
  walletBalance: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};
```

- [ ] **Step 4: Add earned/pending stats to `ReferralsScreen.tsx`**

In `mobile/src/screens/ReferralsScreen.tsx`, find the final line of the component body:

```tsx
      <MetricTile label="Friends Referred" value={String(data.referralCount)} />
    </ScrollView>
  );
}
```

Replace with:

```tsx
      <View style={styles.statsRow}>
        <MetricTile label="Friends Referred" value={String(data.referrals.length)} />
        <MetricTile label="Pending" value={String(data.referrals.filter((r) => r.status === 'SUBSCRIBED').length)} />
        <MetricTile label="Earned" value={`₹${Math.round(data.walletBalance)}`} />
      </View>
    </ScrollView>
  );
}
```

Then add a `statsRow` style to the `StyleSheet.create` block at the bottom of the file — find:

```typescript
  channelRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs },
```

Insert immediately before it:

```typescript
  statsRow: { flexDirection: 'row', gap: spacing.sm },
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd mobile && npx jest src/screens/ReferralsScreen.test.tsx`
Expected: PASS, all tests green.

- [ ] **Step 6: Run the mobile lint/typecheck and full test suite**

Run: `cd mobile && npx tsc --noEmit`
Expected: PASS, no TypeScript errors.

Run: `cd mobile && npx jest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add mobile/src/api/endpoints.ts mobile/src/screens/ReferralsScreen.tsx mobile/src/screens/ReferralsScreen.test.tsx
git commit -m "feat(mobile): show pending/earned referral stats on Refer & Earn"
```

---

## Task 8: Regenerate the OpenAPI spec and generated client types

**Files:**
- Modify: `backend/openapi/openapi.json`
- Modify: `frontend/src/api/generated-types.ts`
- Modify: `mobile/src/api/generated-types.ts`

**Interfaces:**
- Consumes: every backend DTO/endpoint shape changed in Tasks 1–4.
- Produces: nothing — terminal task, output is committed generated artifacts only.

This task has no test steps of its own (the generated files are committed data, not behavior) —
the "test" is the generation script's own emptiness guard plus a diff review.

- [ ] **Step 1: Build the backend jar**

Run: `cd backend && ./mvnw -q -DskipTests package`
Expected: succeeds, produces a jar under `backend/target/`.

- [ ] **Step 2: Ensure Postgres is reachable**

`docker-compose.yml`'s `postgres` service publishes `finora/finora/finora` credentials on
`127.0.0.1:5432`. If nothing is listening there:

Run: `docker compose up -d postgres`

- [ ] **Step 3: Regenerate `openapi.json`**

Run: `cd backend && ./scripts/generate-openapi-spec.sh`
Expected: prints `Wrote openapi/openapi.json: N paths, M schemas.` with N/M larger than before
(new `/api/v1/admin/referrals` paths, new `AdminReferralSummaryDto`/`CreditReferralRewardRequest`/
`MyReferralsDto`/`MyReferralDto` schemas). Refuses (`sys.exit(1)`) if it looks empty — treat that
as a real failure, not something to retry blindly; check the backend actually booted (its own log
output) if it happens.

- [ ] **Step 4: Regenerate the frontend and mobile client types**

Run: `cd frontend && npm run generate:types`
Run: `cd mobile && npm run generate:types`
Expected: both succeed, `generated-types.ts` diffs show new referral-related types/paths.

- [ ] **Step 5: Confirm the frontend and mobile builds still pass with the regenerated types**

Run: `cd frontend && npm run build`
Run: `cd mobile && npx tsc --noEmit`
Expected: both PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/openapi/openapi.json frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts
git commit -m "chore: regenerate OpenAPI spec and client types for the referral reward ledger"
```

---

## Final check

- [ ] Run `cd backend && ./mvnw -q test` — full backend suite green.
- [ ] Run `cd frontend && npx vitest run && npm run build` — full frontend suite + build green.
- [ ] Run `cd admin-portal && npx vitest run && npm run build` — full admin-portal suite + build green.
- [ ] Run `cd mobile && npx jest && npx tsc --noEmit` — full mobile suite + typecheck green.
- [ ] Confirm `frontend/src/pages/Billing.tsx` has zero diff (`git diff --stat origin/main -- frontend/src/pages/Billing.tsx` is empty) — this work must not have touched it.
- [ ] `git fetch origin && git log --oneline origin/main..HEAD` — review the full commit list before opening a PR.
