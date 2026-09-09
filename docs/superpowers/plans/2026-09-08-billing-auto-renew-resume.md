# Billing Auto-Renew Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user turn Auto Renewal back on after cancelling, without a fresh checkout, by deferring the real Razorpay cancellation call until close to the renewal date instead of sending it immediately.

**Architecture:** `BillingCheckoutService.cancel()` stops calling Razorpay synchronously — it only flips `autoRenew=false` locally. A new hourly sweep (`SubscriptionCancellationDispatchSweepService`, same shape as the existing `SubscriptionReconciliationSweepService`) sends the real `cancel_at_cycle_end=true` call once a subscription is within 3 days of its `renewalDate`, stamping a new `cancellationDispatchedAt` timestamp. A new `resume()` service method flips `autoRenew=true` back — for free, with no Razorpay call — as long as that timestamp is still null; once it's set, Razorpay itself gives no way back (verified against Razorpay's docs), so `resume()` returns 409. The web Billing page gets a three-state Auto Renewal toggle reading a new backend-computed `autoRenewResumable` flag.

**Tech Stack:** Spring Boot / JPA / Flyway (backend), React / TanStack Query / Tailwind (frontend), JUnit 5 + Mockito (unit tests), Spring Boot `@SpringBootTest` IT tests against a real Postgres (integration tests), Vitest + Testing Library (frontend tests).

**Spec:** [docs/superpowers/specs/2026-09-08-billing-auto-renew-resume-design.md](../specs/2026-09-08-billing-auto-renew-resume-design.md)

## Global Constraints

- Dispatch buffer is exactly 3 days (`renewalDate <= today + 3`) — Sid picked this over 1-day for outage safety margin, at the cost of a narrower resume window.
- Post-dispatch UX is "hide/disable the toggle, show why" — never let the user click resume and get a 409 as their first signal.
- Scope is web (`frontend/src/pages/Billing.tsx`) and its backend only. Mobile's `MySubscriptionScreen.tsx` and admin's immediate-stop cancel path are explicitly untouched.
- No `Co-Authored-By: Claude` trailer in any commit message (repo-wide rule, `CLAUDE.md`).
- Before creating the Flyway migration file, re-confirm `V167` is still free on `origin/main` (`git fetch origin && git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | grep -oE '/V[0-9]+' | sort -n | tail -3`) — other sessions may have taken it since this plan was written.

---

### Task 1: Data model — `cancellation_dispatched_at` column, entity field, sweep query, and the two existing-code reset points

**Files:**
- Create: `backend/src/main/resources/db/migration/V167__subscription_cancellation_dispatch.sql`
- Modify: `backend/src/main/java/com/finora/entity/Subscription.java`
- Modify: `backend/src/main/java/com/finora/repository/SubscriptionRepository.java`
- Modify: `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java` (`handleActivated`)
- Modify: `backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java` (`sweep`)
- Test: `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`
- Test: `backend/src/test/java/com/finora/service/SubscriptionReconciliationSweepServiceIT.java`

**Interfaces:**
- Produces: `Subscription.getCancellationDispatchedAt(): Instant`, `Subscription.setCancellationDispatchedAt(Instant)`; `SubscriptionRepository.findSubscriptionsAwaitingCancellationDispatch(LocalDate cutoff): List<Subscription>`.

Why the two resets matter: `subscriptions` is a single reused row per user (confirmed by reading `handleActivated` and the reconciliation sweep — both overwrite the same row rather than creating a new one). Without resetting `cancellationDispatchedAt` to `null` on a fresh activation and on downgrade-to-Free, a stale timestamp from a *previous* subscription lifecycle would permanently block `resume()` on a brand-new subscription that never had anything dispatched.

- [ ] **Step 1: Confirm V167 is still free, then create the migration**

```bash
git fetch origin
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | grep -oE '/V[0-9]+' | sed 's#/V##' | sort -n | tail -3
```
Expected: highest number is `165` on `origin/main` (166 is already claimed by other in-flight worktrees as of 2026-09-08 — confirmed by checking `.claude/worktrees/*/backend/src/main/resources/db/migration`, not just `origin/main`). If a higher number than what's used here is now taken anywhere, pick the next free one and use it consistently for the rest of this task instead of 167.

`backend/src/main/resources/db/migration/V167__subscription_cancellation_dispatch.sql`:
```sql
-- Subscription billing: auto-renew resume (deferred-dispatch cancellation). Design spec at
-- docs/superpowers/specs/2026-09-08-billing-auto-renew-resume-design.md. Razorpay's Subscriptions
-- API has no way to undo a dispatched cancel_at_cycle_end=true cancellation (verified directly
-- against Razorpay's own API docs -- see the design doc's "Why this can't be a thin wrapper on
-- Razorpay" section), so a real resume needs BillingCheckoutService.cancel() to stop calling
-- Razorpay immediately and defer that call to a background sweep instead.
--
-- NULL: either no cancellation is scheduled, or one is scheduled locally (auto_renew=false) but
-- not yet sent to Razorpay -- resume is a free, always-succeeding local flip in both cases.
-- Non-NULL: the real cancel_at_cycle_end=true call already reached Razorpay -- irreversible from
-- here, per Razorpay's own documented limitation.
ALTER TABLE subscriptions ADD COLUMN cancellation_dispatched_at TIMESTAMPTZ;
```

- [ ] **Step 2: Add the entity field**

In `backend/src/main/java/com/finora/entity/Subscription.java`, add the import (alongside the existing `java.time.LocalDate` import at the top):

```java
import java.time.Instant;
```

Add the field, immediately after the existing `autoRenew` field (currently the last field in the class):

```java
    @Column(name = "cancellation_dispatched_at")
    private Instant cancellationDispatchedAt;
```

Add the getter/setter, immediately after `setAutoRenew` (currently the last method in the class, just before the closing `}`):

```java
    public Instant getCancellationDispatchedAt() { return cancellationDispatchedAt; }
    public void setCancellationDispatchedAt(Instant cancellationDispatchedAt) { this.cancellationDispatchedAt = cancellationDispatchedAt; }
```

- [ ] **Step 3: Add the sweep-candidates repository query**

In `backend/src/main/java/com/finora/repository/SubscriptionRepository.java`, add at the end of the interface (before the closing `}`), right after `findCancelledSubscriptionsPastPeriodEnd`:

```java

    /** SubscriptionCancellationDispatchSweepService -- candidates for the real, deferred Razorpay
     *  cancel_at_cycle_end=true call (design spec at docs/superpowers/specs/
     *  2026-09-08-billing-auto-renew-resume-design.md). {@code cancellationDispatchedAt IS NULL}
     *  is the guard against re-dispatching a row the sweep already handled. */
    @Query("SELECT s FROM Subscription s WHERE s.autoRenew = false AND s.status = 'ACTIVE' " +
           "AND s.cancellationDispatchedAt IS NULL AND s.razorpaySubscriptionId IS NOT NULL " +
           "AND s.renewalDate <= :cutoff")
    List<Subscription> findSubscriptionsAwaitingCancellationDispatch(@Param("cutoff") LocalDate cutoff);
```

- [ ] **Step 4: Write the failing test for the `handleActivated` reset**

In `backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java`, add a new test near `aBrandNewSignupActivationNeverCallsCancel` (read that test first for the exact fixture-building pattern it uses — `createUser`, `subscriptionService.provisionFreeSubscription`, building the webhook payload, `dispatcher.dispatch(...)` or the direct `handleActivated` call, whichever that file's existing tests use):

```java
    @Test
    void activationClearsAStaleCancellationDispatchedAtFromAPriorSubscriptionLifecycle() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        // Simulates a subscription that was previously cancelled (dispatched to Razorpay) and later
        // downgraded to Free, then the same row is reused for a brand-new checkout.
        subscription.setCancellationDispatchedAt(Instant.now().minusSeconds(3600));
        subscriptionRepository.save(subscription);

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(planRepository.findByCode("PREMIUM").orElseThrow().getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId("sub_reactivation_test");
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(java.math.BigDecimal.valueOf(799));
        subscriptionOrderRepository.save(order);

        dispatcher.handleActivated(activatedPayload("sub_reactivation_test"));

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId("sub_reactivation_test").orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }
```

Read the top of `RazorpayWebhookDispatcherIT.java` first to match the exact helper method name for building an `activated` webhook payload (used by the existing `activationCompletesTheMatchingPendingOrderAndActivatesTheUsersSubscription` test) — reuse that helper rather than inventing a new payload shape. Adjust the test above's payload-building call to match whatever that helper is actually named.

- [ ] **Step 5: Run it to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT#activationClearsAStaleCancellationDispatchedAtFromAPriorSubscriptionLifecycle
```
Expected: FAIL — `reloaded.getCancellationDispatchedAt()` is not null (nothing clears it yet).

- [ ] **Step 6: Add the reset in `handleActivated`**

In `backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java`, inside `handleActivated`, add one line alongside the other field resets (right after `subscription.setAutoRenew(true);`):

```java
        subscription.setAutoRenew(true);
        subscription.setCancellationDispatchedAt(null);
```

- [ ] **Step 7: Run it to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT#activationClearsAStaleCancellationDispatchedAtFromAPriorSubscriptionLifecycle
```
Expected: PASS

- [ ] **Step 8: Write the failing test for the reconciliation sweep's reset**

In `backend/src/test/java/com/finora/service/SubscriptionReconciliationSweepServiceIT.java`, add:

```java
    @Test
    void downgradingToFreeClearsAnyStaleCancellationDispatchedAt() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(LocalDate.now().minusDays(1));
        subscription.setCancellationDispatchedAt(java.time.Instant.now().minusSeconds(3600));
        subscriptionRepository.save(subscription);

        sweepService.sweep();

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }
```

- [ ] **Step 9: Run it to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionReconciliationSweepServiceIT#downgradingToFreeClearsAnyStaleCancellationDispatchedAt
```
Expected: FAIL

- [ ] **Step 10: Add the reset in the sweep**

In `backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java`, inside the `sweep()` method's loop, add one line alongside the other field resets (right after `subscription.setAutoRenew(true);`):

```java
            subscription.setAutoRenew(true);
            subscription.setCancellationDispatchedAt(null);
```

- [ ] **Step 11: Run it to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionReconciliationSweepServiceIT#downgradingToFreeClearsAnyStaleCancellationDispatchedAt
```
Expected: PASS

- [ ] **Step 12: Run the full affected test classes to check for regressions**

```bash
cd backend && ./mvnw test -Dtest=RazorpayWebhookDispatcherIT,SubscriptionReconciliationSweepServiceIT
```
Expected: all PASS.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/resources/db/migration/V167__subscription_cancellation_dispatch.sql \
  backend/src/main/java/com/finora/entity/Subscription.java \
  backend/src/main/java/com/finora/repository/SubscriptionRepository.java \
  backend/src/main/java/com/finora/service/RazorpayWebhookDispatcher.java \
  backend/src/main/java/com/finora/service/SubscriptionReconciliationSweepService.java \
  backend/src/test/java/com/finora/service/RazorpayWebhookDispatcherIT.java \
  backend/src/test/java/com/finora/service/SubscriptionReconciliationSweepServiceIT.java
git commit -m "feat(billing): add cancellation_dispatched_at column for deferred-dispatch cancel resume"
```

---

### Task 2: `cancel()` stops calling Razorpay immediately

**Files:**
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java` (`cancel`)
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java` (update, not add)

**Interfaces:**
- Consumes: nothing new from Task 1 directly (the column just needs to exist).
- Produces: `cancel()`'s new behavior is what Task 4's sweep and Task 3's `resume()` both depend on.

- [ ] **Step 1: Update the existing IT test's expectation (write it failing first)**

In `backend/src/test/java/com/finora/controller/BillingControllerIT.java`, the existing `cancelCallsRazorpayAndSetsAutoRenewFalse` test currently asserts `verify(gateway).cancelSubscription(...)`. Rename and flip that assertion — this test now documents the *new* contract:

```java
    @Test
    void cancelSetsAutoRenewFalseWithoutCallingRazorpayYet() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancel", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }
```
Add `import static org.mockito.Mockito.never;` to that file's static imports if not already present (check the existing `import static org.mockito.Mockito.times;` / `.when;` lines and add alongside them).

- [ ] **Step 2: Run it to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT#cancelSetsAutoRenewFalseWithoutCallingRazorpayYet
```
Expected: FAIL — `Wanted but not invoked: gateway.cancelSubscription(...)` was in fact invoked.

- [ ] **Step 3: Also write the unit-level failing test**

In `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`, there's currently no dedicated `cancel()` unit test at all (the coverage was only at the IT level). Add one:

```java
    @Test
    void cancelSetsAutoRenewFalseWithoutCallingTheGateway() {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        service.cancel(userId);

        assertThat(subscription.isAutoRenew()).isFalse();
        verify(subscriptionRepository).save(subscription);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }

    @Test
    void cancelThrowsWhenNoActiveSubscriptionExists() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(userId)).isInstanceOf(ApiException.class);
    }

    @Test
    void cancelThrowsWhenTheSubscriptionHasNoBillingToCancel() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));

        assertThatThrownBy(() -> service.cancel(userId)).isInstanceOf(ApiException.class);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }
```

- [ ] **Step 4: Run it to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest#cancelSetsAutoRenewFalseWithoutCallingTheGateway
```
Expected: FAIL — `gateway.cancelSubscription` was called.

- [ ] **Step 5: Change `cancel()`**

In `backend/src/main/java/com/finora/service/BillingCheckoutService.java`, replace the current `cancel()` body:

```java
    @Transactional
    public void cancel(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to cancel.");
        }
        gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
    }
```

with:

```java
    /** Deliberately does NOT call the Razorpay gateway -- design spec at docs/superpowers/specs/
     *  2026-09-08-billing-auto-renew-resume-design.md. Razorpay has no API to undo a dispatched
     *  cancel_at_cycle_end=true call, so calling it here would make resume() impossible. The real
     *  Razorpay call is deferred to {@link SubscriptionCancellationDispatchSweepService}, which
     *  fires it once the subscription is close enough to its renewal date that resume no longer
     *  needs to remain possible. */
    @Transactional
    public void cancel(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to cancel.");
        }
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
    }
```

- [ ] **Step 6: Run both tests to verify they pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,BillingControllerIT
```
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
  backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
  backend/src/test/java/com/finora/controller/BillingControllerIT.java
git commit -m "feat(billing): defer the real Razorpay cancel call out of cancel()"
```

---

### Task 3: `resume()` service method, `autoRenewResumable` DTO field, and the `/resume` endpoint

**Files:**
- Modify: `backend/src/main/java/com/finora/service/BillingCheckoutService.java` (add `resume`, update `mySubscription`)
- Modify: `backend/src/main/java/com/finora/dto/BillingDtos.java` (`MySubscriptionDto`)
- Modify: `backend/src/main/java/com/finora/controller/BillingController.java`
- Test: `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`
- Test: `backend/src/test/java/com/finora/controller/BillingControllerIT.java`

**Interfaces:**
- Consumes: `Subscription.getCancellationDispatchedAt()` (Task 1).
- Produces: `BillingCheckoutService.resume(UUID userId): void`; `MySubscriptionDto.autoRenewResumable(): boolean`; `POST /api/v1/billing/resume`.

- [ ] **Step 1: Write the failing unit tests for `resume()`**

In `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`, add:

```java
    @Test
    void resumeFlipsAutoRenewBackOnWhenNothingHasBeenDispatchedYet() {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(false);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        service.resume(userId);

        assertThat(subscription.isAutoRenew()).isTrue();
        verify(subscriptionRepository).save(subscription);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }

    @Test
    void resumeIsANoOpWhenAutoRenewIsAlreadyOn() {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        service.resume(userId);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void resumeThrowsConflictOnceTheRealCancellationHasBeenDispatched() {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setAutoRenew(false);
        subscription.setCancellationDispatchedAt(java.time.Instant.now());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.resume(userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already scheduled to cancel");
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void resumeThrowsWhenThereIsNoBillingSubscriptionToResume() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));

        assertThatThrownBy(() -> service.resume(userId)).isInstanceOf(ApiException.class);
    }

    @Test
    void resumeThrowsWhenNoActiveSubscriptionExists() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume(userId)).isInstanceOf(ApiException.class);
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest#resumeFlipsAutoRenewBackOnWhenNothingHasBeenDispatchedYet
```
Expected: FAIL — `resume` does not exist yet (compile error is an acceptable form of "fails" here; the whole test file won't compile until Step 3 adds the method).

- [ ] **Step 3: Add `resume()` to `BillingCheckoutService`**

In `backend/src/main/java/com/finora/service/BillingCheckoutService.java`, add this method right after `cancel()`:

```java
    /** design spec at docs/superpowers/specs/2026-09-08-billing-auto-renew-resume-design.md. A
     *  free, always-succeeding local flip as long as nothing has been sent to Razorpay yet --
     *  see {@link #cancel} for why cancel() no longer calls the gateway synchronously. */
    @Transactional
    public void resume(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to resume.");
        }
        if (subscription.isAutoRenew()) {
            return;
        }
        if (subscription.getCancellationDispatchedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This subscription is already scheduled to cancel with Razorpay and can no longer be resumed.");
        }
        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);
    }
```

- [ ] **Step 4: Run to verify the unit tests pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: all PASS (including the pre-existing tests — check nothing else regressed).

- [ ] **Step 5: Write the failing test for `autoRenewResumable` on `mySubscription()`**

In `backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java`, extend the existing `mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber` test (don't duplicate its whole setup — add the assertion to that existing test) with:

```java
        assertThat(dto.autoRenewResumable()).isFalse(); // autoRenew is already true in this fixture
```

Add two new focused tests right after it:

```java
    @Test
    void mySubscriptionMarksAutoRenewResumableWhenCancelledButNotYetDispatched() {
        UUID plusPlanId = planId;
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.autoRenewResumable()).isTrue();
    }

    @Test
    void mySubscriptionMarksAutoRenewNotResumableOnceDispatched() {
        UUID plusPlanId = planId;
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscription.setCancellationDispatchedAt(java.time.Instant.now());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.autoRenewResumable()).isFalse();
    }
```

- [ ] **Step 6: Run to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest#mySubscriptionMarksAutoRenewResumableWhenCancelledButNotYetDispatched
```
Expected: FAIL — compile error, `MySubscriptionDto` has no `autoRenewResumable` component yet.

- [ ] **Step 7: Add the field to `MySubscriptionDto` and compute it**

In `backend/src/main/java/com/finora/dto/BillingDtos.java`, change:

```java
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange, PendingOrderDto pendingOrder, String paymentProvider
```

to (adding the new component at the end, right before the closing `)`):

```java
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange, PendingOrderDto pendingOrder, String paymentProvider,
            boolean autoRenewResumable
```

(Read the full record declaration first — it may span onto the next line with the closing `) {}`; keep that closing exactly as it is, only add the new component before it.)

In `backend/src/main/java/com/finora/service/BillingCheckoutService.java`, in `mySubscription()`, compute the new value right before the final `return new MySubscriptionDto(...)` and add it as the last constructor argument:

```java
        boolean autoRenewResumable = hasBillingSubscription && !subscription.isAutoRenew()
                && subscription.getCancellationDispatchedAt() == null;
        return new MySubscriptionDto(
                plan.getCode(), plan.getName(), subscription.getBillingCycle(), subscription.getStatus(),
                subscription.getRenewalDate(), subscription.isAutoRenew(),
                hasBillingSubscription, pendingChange, pendingOrder,
                subscription.getPaymentProvider(), autoRenewResumable);
```

- [ ] **Step 8: Run to verify the tests pass**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest
```
Expected: all PASS.

- [ ] **Step 9: Write the failing IT tests for the endpoint**

In `backend/src/test/java/com/finora/controller/BillingControllerIT.java`, add:

```java
    @Test
    void resumeFlipsAutoRenewBackOnBeforeAnythingIsDispatched() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/resume", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isTrue();
    }

    @Test
    void resumeReturnsConflictOnceDispatched() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscription.setCancellationDispatchedAt(java.time.Instant.now());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/resume", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resumeReturnsBadRequestWhenThereIsNoBillingSubscription() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/resume", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
```

- [ ] **Step 10: Run to verify they fail**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT#resumeFlipsAutoRenewBackOnBeforeAnythingIsDispatched
```
Expected: FAIL — 404, no such endpoint yet.

- [ ] **Step 11: Add the controller endpoint**

In `backend/src/main/java/com/finora/controller/BillingController.java`, add right after the `cancel` method:

```java
    @PostMapping("/resume")
    public ApiResponse<Void> resume() {
        billingCheckoutService.resume(currentUser.id());
        return ApiResponse.ok(null, "Auto-renewal resumed");
    }
```

- [ ] **Step 12: Run to verify all three IT tests pass**

```bash
cd backend && ./mvnw test -Dtest=BillingControllerIT
```
Expected: all PASS.

- [ ] **Step 13: Run the full backend billing test surface for regressions**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,BillingControllerIT,RazorpayWebhookDispatcherIT,SubscriptionReconciliationSweepServiceIT,SubscriptionBillingEndToEndIT,SubscriptionUpgradeDowngradeEndToEndIT
```
Expected: all PASS. If `SubscriptionBillingEndToEndIT` or `SubscriptionUpgradeDowngradeEndToEndIT` assert on `cancel()` calling the gateway immediately anywhere, fix those assertions the same way Task 2 fixed `BillingControllerIT`'s — read the failure output before changing anything (no guessing).

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/java/com/finora/service/BillingCheckoutService.java \
  backend/src/main/java/com/finora/dto/BillingDtos.java \
  backend/src/main/java/com/finora/controller/BillingController.java \
  backend/src/test/java/com/finora/service/BillingCheckoutServiceTest.java \
  backend/src/test/java/com/finora/controller/BillingControllerIT.java
git commit -m "feat(billing): add POST /api/v1/billing/resume and autoRenewResumable"
```

---

### Task 4: The dispatch sweep

**Files:**
- Create: `backend/src/main/java/com/finora/service/SubscriptionCancellationDispatchSweepService.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/finora/service/SubscriptionCancellationDispatchSweepServiceIT.java`
- Modify: `backend/src/main/java/com/finora/entity/SubscriptionEvent.java` (new event-type constant)

**Interfaces:**
- Consumes: `SubscriptionRepository.findSubscriptionsAwaitingCancellationDispatch(LocalDate)` (Task 1), `RazorpaySubscriptionGateway.cancelSubscription(String, boolean)` (existing).
- Produces: `SubscriptionCancellationDispatchSweepService.sweep(): int` (dispatched count), matching `SubscriptionReconciliationSweepService.sweep()`'s own return-count convention.

- [ ] **Step 1: Add the new event-type constant**

In `backend/src/main/java/com/finora/entity/SubscriptionEvent.java`, add alongside the existing constants:

```java
    public static final String CANCELLATION_DISPATCHED = "CANCELLATION_DISPATCHED";
```

- [ ] **Step 2: Add the two config entries**

In `backend/src/main/resources/application.yml`, add right after the existing `subscription-reconciliation.sweep` block (same indentation level, sibling key under `app:`):

```yaml
  # Subscription billing -- auto-renew resume (design spec at docs/superpowers/specs/
  # 2026-09-08-billing-auto-renew-resume-design.md). Dispatches the real, deferred Razorpay
  # cancel_at_cycle_end=true call once a locally-cancelled subscription is within 3 days of its
  # renewal date -- the point past which resume() can no longer undo it. The 3-day buffer (not
  # 1-day) is a deliberate outage-safety tradeoff: an hourly sweep down for less than 3 days still
  # won't miss dispatching before the actual renewal charge.
  subscription-cancellation-dispatch:
    sweep:
      enabled: ${SUBSCRIPTION_CANCELLATION_DISPATCH_SWEEP_ENABLED:true}
      interval-ms: ${SUBSCRIPTION_CANCELLATION_DISPATCH_SWEEP_INTERVAL_MS:3600000}
      initial-delay-ms: ${SUBSCRIPTION_CANCELLATION_DISPATCH_SWEEP_INITIAL_DELAY_MS:300000}
      buffer-days: ${SUBSCRIPTION_CANCELLATION_DISPATCH_SWEEP_BUFFER_DAYS:3}
```

In `backend/src/main/resources/application-test.yml`, add right after the existing `subscription-reconciliation.sweep.enabled: false` block:

```yaml
  # Same reasoning as subscription-reconciliation.sweep above -- tests call
  # SubscriptionCancellationDispatchSweepService.sweep() directly.
  subscription-cancellation-dispatch:
    sweep:
      enabled: false
```

- [ ] **Step 3: Write the failing IT tests first**

`backend/src/test/java/com/finora/service/SubscriptionCancellationDispatchSweepServiceIT.java`:

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubscriptionCancellationDispatchSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionCancellationDispatchSweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("cancel-dispatch-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Cancellation Dispatch Sweep IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Subscription cancelledLocallyNotYetDispatched(User user, LocalDate renewalDate) {
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_dispatch_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(renewalDate);
        return subscriptionRepository.save(subscription);
    }

    @Test
    void dispatchesTheRealCancellationWhenWithinTheBufferWindow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(2));

        int dispatched = sweepService.sweep();

        assertThat(dispatched).isGreaterThanOrEqualTo(1);
        verify(gateway).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), eq(true));
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNotNull();
    }

    @Test
    void leavesASubscriptionAloneWhenOutsideTheBufferWindow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(10));

        sweepService.sweep();

        verify(gateway, never()).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), org.mockito.ArgumentMatchers.anyBoolean());
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getCancellationDispatchedAt()).isNull();
    }

    @Test
    void isIdempotentOnASecondRunOverTheSameRow() {
        User user = createUser();
        Subscription subscription = cancelledLocallyNotYetDispatched(user, LocalDate.now().plusDays(1));

        sweepService.sweep();
        int secondRunDispatched = sweepService.sweep();

        assertThat(secondRunDispatched).isZero();
        verify(gateway).cancelSubscription(eq(subscription.getRazorpaySubscriptionId()), eq(true)); // exactly once total
    }
}
```

- [ ] **Step 4: Run to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionCancellationDispatchSweepServiceIT
```
Expected: FAIL to compile — `SubscriptionCancellationDispatchSweepService` doesn't exist yet. A compile failure is the expected "fail" here, same as any other brand-new-class test-first step.

- [ ] **Step 5: Write the service**

`backend/src/main/java/com/finora/service/SubscriptionCancellationDispatchSweepService.java`:

```java
package com.finora.service;

import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Subscription billing -- auto-renew resume (design spec at docs/superpowers/specs/
 * 2026-09-08-billing-auto-renew-resume-design.md). {@code BillingCheckoutService.cancel()} no
 * longer calls Razorpay synchronously -- this sweep is what actually sends the real
 * {@code cancel_at_cycle_end=true} call, once a locally-cancelled subscription is within
 * {@code bufferDays} of its {@code renewalDate}. Same shape as
 * {@code SubscriptionReconciliationSweepService}: {@code fixedDelay}, gated by a flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 *
 * <p>Residual risk, accepted explicitly in the design: if this sweep is down for an outage
 * spanning the entire buffer window, a subscription whose user wants it cancelled could still
 * auto-renew and charge on Razorpay's side, since nothing was ever sent to stop it.
 */
@Service
public class SubscriptionCancellationDispatchSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCancellationDispatchSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final RazorpaySubscriptionGateway gateway;

    @Value("${app.subscription-cancellation-dispatch.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.subscription-cancellation-dispatch.sweep.buffer-days:3}")
    private int bufferDays;

    public SubscriptionCancellationDispatchSweepService(SubscriptionRepository subscriptionRepository,
                                                          SubscriptionEventRepository subscriptionEventRepository,
                                                          RazorpaySubscriptionGateway gateway) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${app.subscription-cancellation-dispatch.sweep.interval-ms:3600000}",
            initialDelayString = "${app.subscription-cancellation-dispatch.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int dispatched = sweep();
        if (dispatched > 0) {
            log.info("Cancellation dispatch sweep: {} subscription(s) sent to Razorpay for cycle-end cancellation.",
                    dispatched);
        }
    }

    @Transactional
    public int sweep() {
        LocalDate cutoff = LocalDate.now().plusDays(bufferDays);
        List<Subscription> candidates = subscriptionRepository.findSubscriptionsAwaitingCancellationDispatch(cutoff);

        for (Subscription subscription : candidates) {
            gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
            subscription.setCancellationDispatchedAt(Instant.now());
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.CANCELLATION_DISPATCHED);
            event.setMetadata(Map.of("razorpaySubscriptionId", subscription.getRazorpaySubscriptionId()));
            subscriptionEventRepository.save(event);
        }
        return candidates.size();
    }
}
```

- [ ] **Step 6: Run to verify they now pass**

```bash
cd backend && ./mvnw test -Dtest=SubscriptionCancellationDispatchSweepServiceIT
```
Expected: all PASS — the failing-to-compile tests from Step 4 and the implementation from Step 5 now resolve together.

- [ ] **Step 7: Run the broader billing suite for regressions**

```bash
cd backend && ./mvnw test -Dtest=BillingCheckoutServiceTest,BillingControllerIT,RazorpayWebhookDispatcherIT,SubscriptionReconciliationSweepServiceIT,SubscriptionCancellationDispatchSweepServiceIT
```
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/service/SubscriptionCancellationDispatchSweepService.java \
  backend/src/main/java/com/finora/entity/SubscriptionEvent.java \
  backend/src/main/resources/application.yml \
  backend/src/main/resources/application-test.yml \
  backend/src/test/java/com/finora/service/SubscriptionCancellationDispatchSweepServiceIT.java
git commit -m "feat(billing): dispatch the deferred Razorpay cancellation 3 days before renewal"
```

---

### Task 5: Regenerate the OpenAPI spec and frontend generated types

**Files:**
- Modify (generated): `backend/openapi/openapi.json`
- Modify (generated): `frontend/src/api/generated-types.ts`

**Interfaces:**
- Consumes: every backend DTO/endpoint change from Tasks 1-4.
- Produces: nothing new consumed by later tasks — this is drift hygiene per `docs/engineering/openapi-contracts.md`, and CI's `openapi-contract-check` is advisory, not blocking, so this task can be done last without blocking Task 6/7's frontend work if a local Postgres isn't available in this environment.

- [ ] **Step 1: Regenerate the backend spec**

Needs a reachable Postgres matching `docker-compose.yml`'s credentials (`finora`/`finora`/`finora` on `5432`). Start it however this environment normally runs the backend locally, then:

```bash
cd backend
./mvnw -DskipTests package
DB_HOST=localhost DB_PORT=5432 DB_NAME=finora DB_USER=finora DB_PASSWORD=finora \
  ./scripts/generate-openapi-spec.sh
```

Expected: exits 0, `backend/openapi/openapi.json` is rewritten with `MySubscriptionDto` now including `autoRenewResumable`.

If no Postgres is reachable in this environment, skip this step and say so explicitly rather than guessing at the output — CI's advisory check will flag the drift on the PR instead, and it can be regenerated then.

- [ ] **Step 2: Regenerate the frontend types**

```bash
cd frontend && npm run generate:types
```

Expected: `frontend/src/api/generated-types.ts` changes to include `autoRenewResumable` on `MySubscriptionDto`.

- [ ] **Step 3: Review the diff**

```bash
git diff --stat backend/openapi/openapi.json frontend/src/api/generated-types.ts
```

Expected: only the fields touched by Tasks 1-4 changed (the `/resume` path, `MySubscriptionDto.autoRenewResumable`) — not a wholesale rewrite. If the diff looks unrelated to this work (e.g. `servers` URL churn), re-read `docs/engineering/openapi-contracts.md`'s "Determinism was also verified" section before investigating further — that's a known, already-solved class of noise.

- [ ] **Step 4: Commit**

```bash
git add backend/openapi/openapi.json frontend/src/api/generated-types.ts
git commit -m "chore: regenerate OpenAPI spec and frontend types for billing resume"
```

(If Step 1 was skipped, skip this commit too — nothing changed to commit.)

---

### Task 6: Frontend — Auto Renewal toggle

**Files:**
- Modify: `frontend/src/api/endpoints.ts` (`billingApi.resume`, `MySubscription.autoRenewResumable`)
- Modify: `frontend/src/pages/Billing.tsx`
- Test: `frontend/src/pages/Billing.test.tsx`

**Interfaces:**
- Consumes: `POST /api/v1/billing/resume` (Task 3), `MySubscription.autoRenewResumable: boolean` (Task 3).
- Produces: nothing consumed elsewhere — this is the leaf of the plan.

- [ ] **Step 1: Add the API surface**

In `frontend/src/api/endpoints.ts`, add `autoRenewResumable: boolean;` to the `MySubscription` interface, right after `paymentProvider: string | null;`:

```ts
export interface MySubscription {
  planCode: string;
  planName: string;
  billingCycle: string | null;
  status: string;
  renewalDate: string | null;
  autoRenew: boolean;
  hasBillingSubscription: boolean;
  pendingChange: PendingPlanChange | null;
  pendingOrder: PendingOrder | null;
  paymentProvider: string | null;
  autoRenewResumable: boolean;
}
```

Add `resume` to `billingApi`, right after `cancel`:

```ts
  cancel: () => api.post<{ message: string }>('/billing/cancel').then((r) => r.data),
  resume: () => api.post<{ message: string }>('/billing/resume').then((r) => r.data),
```

- [ ] **Step 2: Write the failing frontend tests**

In `frontend/src/pages/Billing.test.tsx`:

Add `resume: vi.fn()` to the `vi.mock('../api/endpoints', ...)` factory's `billingApi` object (alongside `cancel: vi.fn()`), and add `vi.mocked(billingApi.resume).mockReset();` to the `beforeEach` block, alongside the existing `.mockReset()` calls.

Add `autoRenewResumable: false` to the `subscription()` test helper's default return object (alongside the existing `autoRenew: true` default).

Add these three tests, in the `describe('Billing', ...)` block, following the file's existing style (read a couple of the existing tests around the current "Cancel subscription" button coverage first, to match exactly how they query/mock/assert):

```ts
  it('shows an enabled auto-renewal toggle that is on for an active paid subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(
      subscription({
        planCode: 'PLUS', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
        autoRenew: true, autoRenewResumable: false,
      })
    );
    renderPage();

    const toggle = await screen.findByRole('switch', { name: /auto renewal/i });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(toggle).not.toBeDisabled();
  });

  it('resumes auto-renewal directly, with no confirm dialog, when resumable', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(
      subscription({
        planCode: 'PLUS', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
        autoRenew: false, autoRenewResumable: true,
      })
    );
    vi.mocked(billingApi.resume).mockResolvedValue({ message: 'Auto-renewal resumed' });
    const user = userEvent.setup();
    renderPage();

    const toggle = await screen.findByRole('switch', { name: /auto renewal/i });
    expect(toggle).not.toBeDisabled();
    await user.click(toggle);

    await waitFor(() => expect(billingApi.resume).toHaveBeenCalled());
    expect(screen.queryByText(/cancel subscription\?/i)).not.toBeInTheDocument();
  });

  it('disables the toggle with an explanation once the cancellation has been dispatched to Razorpay', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(
      subscription({
        planCode: 'PLUS', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
        autoRenew: false, autoRenewResumable: false,
      })
    );
    renderPage();

    const toggle = await screen.findByRole('switch', { name: /auto renewal/i });
    expect(toggle).toBeDisabled();
    expect(screen.getByText(/too close to your renewal date to resume/i)).toBeInTheDocument();
  });
```

- [ ] **Step 3: Run to verify they fail**

```bash
cd frontend && npm test -- Billing.test.tsx
```
Expected: FAIL — no element with `role="switch"` exists yet.

- [ ] **Step 4: Replace the Cancel button with the three-state toggle**

In `frontend/src/pages/Billing.tsx`, add a `resumeMutation` right after the existing `cancelMutation` (name it `resumeMutation`/`resumeAutoRenew` — deliberately distinct from the existing `resumePendingOrder` function further down, which resumes an abandoned *checkout*, a different concept entirely):

```ts
  const resumeMutation = useMutation({
    mutationFn: () => billingApi.resume(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setError(e.response?.data?.message ?? 'Could not resume auto-renewal. Try again.');
    },
  });
```

Replace this block (the "Cancel subscription" button, currently the only control in this area):

```tsx
            {subscription.hasBillingSubscription && subscription.autoRenew && subscription.paymentProvider !== 'REVENUECAT' && (
              <Button variant="danger" size="sm" onClick={() => setConfirmingCancel(true)}>
                Cancel subscription
              </Button>
            )}
```

with:

```tsx
            {subscription.hasBillingSubscription && subscription.paymentProvider !== 'REVENUECAT' && (
              <div className="flex flex-col items-end gap-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted">Auto Renewal</span>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={subscription.autoRenew}
                    aria-label="Auto Renewal"
                    disabled={!subscription.autoRenew && !subscription.autoRenewResumable}
                    onClick={() => {
                      if (subscription.autoRenew) {
                        setConfirmingCancel(true);
                      } else {
                        resumeMutation.mutate();
                      }
                    }}
                    className={
                      'relative w-9 h-5 rounded-full transition-colors disabled:opacity-40 disabled:cursor-not-allowed ' +
                      (subscription.autoRenew ? 'bg-primary' : 'bg-border')
                    }
                  >
                    <span
                      className={
                        'absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-card transition-transform ' +
                        (subscription.autoRenew ? 'translate-x-4' : '')
                      }
                    />
                  </button>
                </div>
                {!subscription.autoRenew && !subscription.autoRenewResumable && (
                  <p className="text-xs text-muted max-w-[220px] text-right">
                    Too close to your renewal date to resume — you can subscribe again once this period ends.
                  </p>
                )}
              </div>
            )}
```

- [ ] **Step 5: Run to verify the new tests pass**

```bash
cd frontend && npm test -- Billing.test.tsx
```
Expected: all PASS.

- [ ] **Step 6: Run the full frontend test suite for regressions**

```bash
cd frontend && npm test
```
Expected: all PASS — check specifically for anything else asserting on the old "Cancel subscription" button text, since it no longer renders.

- [ ] **Step 7: Type-check and lint**

```bash
cd frontend && npx tsc --noEmit && npm run lint
```
Expected: clean.

- [ ] **Step 8: Manually verify in the browser**

Start the frontend dev server and Billing page, log in as a test user with a paid Razorpay subscription (or seed one), and check all three toggle states render correctly and the confirm dialog / direct resume both fire the right network call — this is a UI change, so an automated pass alone isn't sufficient per this repo's verification standard.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/pages/Billing.tsx frontend/src/pages/Billing.test.tsx
git commit -m "feat(billing): add a two-way Auto Renewal toggle to the Billing page"
```

---

## Post-plan checklist (do not skip)

- [ ] Re-run the full backend suite once, not just the touched classes, to catch any test elsewhere that hard-codes the old immediate-cancel behavior: `cd backend && ./mvnw test`.
- [ ] Re-run the full frontend suite once: `cd frontend && npm test`.
- [ ] Confirm `git log --oneline origin/main..HEAD` only contains the commits from this plan before opening a PR.
- [ ] Open the PR against `origin/main` from this worktree's branch — do not touch the primary checkout.
