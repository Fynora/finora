# Account Aggregator Cost Controls + Consent-Management UX Implementation Plan (Plan 5 of 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Account Aggregator currently has no user-facing entry point at all — no connect button, no
consent flow, no linked-accounts screen. This plan builds the first one (connect → PROBABLE-match
confirmation → manage/disconnect), closes two real state-machine gaps found while scoping it
(`PAUSED` links never resume; `EXPIRED` is never actually reached), and adds the cost controls the
design spec calls for (link cap, relink throttling, rate limiting).

**Architecture:** A new `AccountAggregatorLinkManagementService` becomes the one place the
`consent.revoked`/user-disconnect transition and the link-listing query live, called by both the
existing webhook dispatcher (refactored to delegate, not duplicate) and two new endpoints. A single
consolidated `AccountAggregatorLinkLifecycleSweepService` handles both directions of the
entitlement/expiry gaps in one scheduled pass, rather than three near-identical sweep services. The
frontend adds a "Bank Sync" section to `Settings.tsx`, structurally mirroring its existing Gmail
section, plus one new route for the PROBABLE-match confirmation step Plan 1 built the backend for
but never wired a screen to.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ (backend, plus a real-Postgres IT for the
sweep and the shared revoke transition); React/TypeScript, Vitest + Testing Library (frontend).

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)'s
"Cost control" and "Missing requirements" (consent-management UX) sections.
**Scope doc:** [2026-09-13-account-aggregator-cost-controls-consent-ux-scope.md](2026-09-13-account-aggregator-cost-controls-consent-ux-scope.md)
— every design decision below (③ `REVOKED` not a new status, `PAUSED` resume being automatic, the
`statusChangedAt` field, the disconnect endpoint's semantics) was settled there across three review
rounds; this plan does not re-litigate any of it, only turns it into tasks.

## Global Constraints

- **Plan 4 dependency (PR #1426, not yet merged as of this writing).** This plan's staleness display
  wants `AccountAggregatorLinkStalenessService` and the `findByStatus(ACTIVE)` query Plan 4 adds.
  Task 10 (the linked-accounts list) explicitly does NOT block on this — it ships with
  status/`lastSyncedAt`/`statusChangedAt` only, and gets a staleness column as a small follow-up once
  Plan 4 merges, rather than duplicating that service speculatively.
- **One state machine, not two.** The disconnect endpoint (Task 6) sets `REVOKED` — the exact status
  value `AccountAggregatorWebhookDispatcher`'s `consent.revoked` case already sets — never a new
  status. Per-actor distinction (did the user disconnect it, or did their bank revoke it) lives in
  the audit action name, not in `AccountAggregatorLinkStatus`.
- **Bootstrap values only** for the link cap and the `PAUSED`-counts-toward-cap question — both
  genuinely open product decisions per the scope doc, not resolved by this plan. Shipped as named,
  clearly-commented config/constants an implementer can change without touching logic, same
  discipline every prior AA plan used for its own unvalidated thresholds.
- **The entitlement/expiry sweep mutates only `AccountAggregatorLink` and `Account.primarySource`** —
  same category of change the existing webhook-driven transitions already make, nothing new.
- No AI-attribution trailer in any commit message (repository rule, `CLAUDE.md`).

---

## File Structure

```
backend/src/main/resources/db/migration/
  V200__account_aggregator_link_status_changed_at.sql   (new)

backend/src/main/java/com/finora/integrations/setu/
  AccountAggregatorLink.java                       (modify -- statusChangedAt field)
  AccountAggregatorLinkRepository.java              (modify -- cap-count, listForUser, sweep queries)
  AccountAggregatorLinkManagementService.java       (new -- shared revoke transition, list, disconnect)
  AccountAggregatorWebhookDispatcher.java           (modify -- delegates consent.revoked)
  SetuConsentService.java                           (modify -- link cap + relink throttle)
  AccountAggregatorLinkLifecycleSweepService.java   (new -- downgrade/upgrade/expiry, one sweep)
  AccountAggregatorLinkController.java              (modify -- GET /links, POST /disconnect)
  AccountAggregatorLinkDto.java                     (new -- the list/detail response shape)

backend/src/main/java/com/finora/config/
  RateLimitFilter.java                              (modify -- new limiter for link initiation)

backend/src/main/resources/
  application-test.yml                              (modify -- disable the new sweep under test)

backend/openapi/openapi.json                        (regenerate)
frontend/src/api/generated-types.ts                 (regenerate)
mobile/src/api/generated-types.ts                   (regenerate)
admin-portal/src/api/generated-types.ts             (regenerate)

frontend/src/api/endpoints.ts                       (modify -- accountAggregatorApi)
frontend/src/pages/Settings.tsx                     (modify -- "Bank Sync" section)
frontend/src/pages/AccountAggregatorConfirm.tsx     (new -- PROBABLE-match confirmation screen)
frontend/src/App.tsx                                (modify -- route for the confirmation screen)

backend/src/test/java/com/finora/integrations/setu/
  AccountAggregatorLinkManagementServiceTest.java   (new)
  AccountAggregatorLinkManagementServiceIT.java     (new -- real-transaction revoke-path proof)
  AccountAggregatorLinkLifecycleSweepServiceTest.java (new)
  SetuConsentServiceTest.java                        (modify -- cap + throttle tests)
  AccountAggregatorLinkControllerTest.java           (new)
  AccountAggregatorWebhookDispatcherTest.java        (modify -- delegation, not duplication)
backend/src/test/java/com/finora/config/
  RateLimitFilterTest.java                           (modify -- new endpoint added to the exhaustive list)

frontend/src/pages/
  Settings.test.tsx                                  (modify -- Bank Sync section tests)
  AccountAggregatorConfirm.test.tsx                   (new)
```

---

### Task 1: `statusChangedAt` — the one added timestamp

**Files:**
- Create: `backend/src/main/resources/db/migration/V200__account_aggregator_link_status_changed_at.sql`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java`

**Interfaces:**
- Produces: `AccountAggregatorLink.getStatusChangedAt()` — set automatically by `setStatus(...)`
  itself, so no future call site can forget it (there are already ~6: `SetuConsentService`,
  `AccountAggregatorIdentityResolutionService` ×2, `AccountAggregatorWebhookDispatcher` ×2, and this
  plan's own new sweep and disconnect path).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkStatusChangedAtTest {

    @Test
    void statusChangedAtIsSetOnConstructionAndUpdatedOnEveryStatusChange() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        Instant initial = link.getStatusChangedAt();
        assertThat(initial).isNotNull();

        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        Instant afterFirstChange = link.getStatusChangedAt();
        assertThat(afterFirstChange).isAfterOrEqualTo(initial);

        // Setting other fields must NOT move statusChangedAt -- only a real status transition does,
        // which is the whole reason this field exists instead of reusing updatedAt (already touched
        // by every sync via setLastSyncedAt).
        link.setLastSyncedAt(Instant.now());
        assertThat(link.getStatusChangedAt()).isEqualTo(afterFirstChange);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkStatusChangedAtTest`
Expected: FAIL to compile — `getStatusChangedAt()` doesn't exist yet.

- [ ] **Step 3: Add the migration and the field**

```sql
-- One added timestamp (Plan 5 scope doc, "audit trail visibility"): when did this link last
-- change status, distinct from updated_at, which every sync touches too (setLastSyncedAt calls
-- the same touch() setStatus does) and is therefore useless for "when did this stop" or
-- "connected on". Backfilled from updated_at for existing rows -- an approximation for anything
-- that already changed status more than once, acceptable since no rows exist in production yet
-- (Plan 5 is what first makes this feature reachable from the app).
ALTER TABLE account_aggregator_links ADD COLUMN status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE account_aggregator_links SET status_changed_at = updated_at;
```

```java
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt = Instant.now();

    public Instant getStatusChangedAt() { return statusChangedAt; }
```

Change `setStatus` to set it:

```java
    public void setStatus(AccountAggregatorLinkStatus status) {
        this.status = status;
        this.statusChangedAt = Instant.now();
        touch();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkStatusChangedAtTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V200__account_aggregator_link_status_changed_at.sql \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkStatusChangedAtTest.java
git commit -m "feat(backend): add AccountAggregatorLink.statusChangedAt"
```

---

### Task 2: Rate limiting on link initiation

**Files:**
- Modify: `backend/src/main/java/com/finora/config/RateLimitFilter.java`
- Modify: `backend/src/test/java/com/finora/config/RateLimitFilterTest.java`

**Interfaces:**
- Produces: `POST /api/v1/integrations/setu/links` behind a new `linkInitiateLimiter`.

**A note on why this is its own task, first:** `RateLimitFilter`'s constructor already threads 16
limiter max/window pairs by position — adding a 17th is mechanical but touches every existing call
site of both constructors. Doing it before the cap/throttle logic (Task 3) keeps that mechanical
diff isolated and easy to review on its own, rather than buried inside a task with real new business
logic.

- [ ] **Step 1: Write the failing test**

Add to `RateLimitFilterTest`'s `mustBeLimited` array in `everyEndpointWithARealPerCallCostIsLimited`
(this test exists specifically to catch a newly-added costly endpoint that forgot a limiter — the
class's own comment: "a test that names the endpoints one at a time fails to catch the next one for
exactly the same reason the filter did"):

```java
                "/api/v1/integrations/setu/links",
```

Add a dedicated test too, mirroring `googleLimiter`'s own shape (a real per-call cost even on a
rejected/duplicate request — here, a live Setu consent-creation call):

```java
    @Test
    void tripsOnRepeatedAccountAggregatorLinkInitiation() throws Exception {
        RateLimitFilter filter = newFilter(false);
        assertThat(tripsRateLimitAfterManyRequests(filter,
                requestFor("/api/v1/integrations/setu/links", "10.0.3.1", null))).isTrue();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=RateLimitFilterTest`
Expected: FAIL — path not yet in `limitedEndpoints`.

- [ ] **Step 3: Add the limiter**

New default constant, alongside the others:

```java
    // Real per-call cost even on a duplicate/rejected attempt: a live Setu consent-creation call
    // (gateway.createConsent), the same class of cost googleLimiter/appleLimiter already protect
    // for their own external-provider calls. 10/10min -- generous for a legitimate user linking a
    // couple of accounts in one sitting, tight enough to bound a script hammering this endpoint
    // ahead of Task 3's own cap/throttle logic (defense in depth, not a substitute for it).
    static final int DEFAULT_AA_LINK_INITIATE_MAX = 10, DEFAULT_AA_LINK_INITIATE_WINDOW = 600;
```

Add the field, the `@Value` constructor parameter (both constructors), the `new RateLimiter(...)`
assignment, and the `LimitedEndpoint` entry:

```java
    new LimitedEndpoint(PARSER.parse("/api/v1/integrations/setu/links"), linkInitiateLimiter),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=RateLimitFilterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/config/RateLimitFilter.java \
        backend/src/test/java/com/finora/config/RateLimitFilterTest.java
git commit -m "feat(backend): rate-limit Account Aggregator link initiation"
```

---

### Task 3: Link cap + relink throttling

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/SetuConsentService.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/SetuConsentServiceTest.java`

**Interfaces:**
- Produces: `SetuConsentService.initiateLink` throws `ApiException(409)` over the cap, `429` under
  the relink-throttle window (distinct statuses — a cap is a durable "you're at your limit," a
  throttle is transient "try again later," and a caller/UI should tell them apart).

Both values are bootstrap defaults per the scope doc's own open items (link cap number, whether
`PAUSED` counts) — implemented as named, overridable config, not hardcoded logic a later product
decision would need to touch.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void refusesANewLinkOnceTheCapIsReached() {
        when(links.countByUserIdAndStatusIn(eq(userId), any())).thenReturn(5L); // at the bootstrap cap

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "key-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusesARepeatedInitiateForTheSameFiTypeWithinTheThrottleWindow() {
        when(links.countByUserIdAndStatusIn(eq(userId), any())).thenReturn(0L);
        when(links.existsByUserIdAndFiTypeAndCreatedAtAfter(eq(userId), eq(FiType.DEPOSIT), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.initiateLink(userId, FiType.DEPOSIT, "key-2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
```

(add `AccountAggregatorLinkRepository links = mock(...)` stubbed to return `0L`/`false` for these
two new methods in the existing test's `@BeforeEach`, matching that file's established
"safe default, override per-test" convention)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=SetuConsentServiceTest`
Expected: FAIL to compile — the two new repository methods don't exist yet.

- [ ] **Step 3: Add the repository queries**

```java
    /** SetuConsentService's link-cap check (Plan 5) -- counts only non-terminal statuses, so a
     *  REVOKED/EXPIRED/REJECTED/LINK_FAILED link never blocks a fresh one. Whether PAUSED should be
     *  included here is a still-open product decision (Plan 5 scope doc) -- the caller decides
     *  which statuses to pass, this query stays a generic count-by-status-set. */
    long countByUserIdAndStatusIn(UUID userId, List<AccountAggregatorLinkStatus> statuses);

    /** SetuConsentService's relink-throttle check (Plan 5) -- the design spec's own "at most one
     *  consent-creation attempt per specific account per rolling 24h window" is not implementable
     *  as literally stated (no account identity exists before consent completes -- see the scope
     *  doc's own reasoning), so this is scoped to fiType, the coarsest identity available at
     *  initiate time. */
    boolean existsByUserIdAndFiTypeAndCreatedAtAfter(UUID userId, FiType fiType, Instant cutoff);
```

- [ ] **Step 4: Add the checks in `initiateLink`**

Add imports `java.time.Duration`, `java.time.Instant`, `java.util.List` (none currently present in
this file). Insert after the existing entitlement check, before the idempotency-key lookup:

```java
        // Bootstrap values only -- both are open product decisions (Plan 5 scope doc: link cap
        // number, and whether PAUSED counts toward it). Named config, not hardcoded, so a later
        // decision changes a property, not this logic.
        List<AccountAggregatorLinkStatus> statusesCountedTowardCap =
                List.of(AccountAggregatorLinkStatus.CONSENT_PENDING,
                        AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION,
                        AccountAggregatorLinkStatus.ACTIVE);
        if (links.countByUserIdAndStatusIn(userId, statusesCountedTowardCap) >= linkCap) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You've reached the maximum number of linked bank accounts.");
        }
        if (links.existsByUserIdAndFiTypeAndCreatedAtAfter(
                userId, fiType, Instant.now().minus(Duration.ofHours(relinkThrottleHours)))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before starting another bank connection of this type.");
        }
```

New constructor `@Value`s: `@Value("${app.integrations.setu.link-cap:5}") int linkCap` and
`@Value("${app.integrations.setu.relink-throttle-hours:24}") long relinkThrottleHours` — bootstrap
defaults matching the scope doc's own "5" example and the design spec's stated "24h window."

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=SetuConsentServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java \
        backend/src/main/java/com/finora/integrations/setu/SetuConsentService.java \
        backend/src/test/java/com/finora/integrations/setu/SetuConsentServiceTest.java
git commit -m "feat(backend): add link cap and relink-throttle checks to initiateLink"
```

---

### Task 4: `AccountAggregatorLinkManagementService` — the shared revoke transition

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkManagementService.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkManagementServiceTest.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkManagementServiceIT.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`

**Interfaces:**
- Produces: `List<AccountAggregatorLink> listForUser(UUID userId)`,
  `void disconnect(UUID userId, UUID linkId)`.

**Why this is its own task, before the endpoints that use it:** the scope doc's own decision was
"one transition implementation, not two" for revoke. That means extracting the existing inline logic
out of `AccountAggregatorWebhookDispatcher`'s `consent.revoked` case, not just adding a copy of it
in a new place — this task does the extraction and proves (via a real-Postgres IT, not a mock) that
the webhook path still behaves identically afterward, before Task 6 adds the second caller.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkManagementServiceTest {

    @Test
    void listForUserReturnsOnlyThatUsersLinks() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        when(links.findByUserId(userId)).thenReturn(List.of(link));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);

        assertThat(service.listForUser(userId)).containsExactly(link);
    }

    @Test
    void disconnectRevokesTheLinkAndRevertsTheAccountAndAuditsWithADistinctAction() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findById(linkId)).thenReturn(Optional.of(link));
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);
        service.disconnect(userId, linkId);

        assertThat(link.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
        // Distinct action from the webhook path's ACCOUNT_AGGREGATOR_CONSENT_REVOKED -- see this
        // service's own doc comment for why the who/why distinction lives here, not in the status.
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_USER_DISCONNECTED"),
                eq("AccountAggregatorLink"), eq(linkId));
    }

    @Test
    void disconnectRefusesALinkBelongingToAnotherUser() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        AuditService auditService = mock(AuditService.class);
        UUID linkId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(UUID.randomUUID()); // a different user
        when(links.findById(linkId)).thenReturn(Optional.of(link));

        AccountAggregatorLinkManagementService service =
                new AccountAggregatorLinkManagementService(links, accountRepository, auditService);

        assertThatThrownBy(() -> service.disconnect(UUID.randomUUID(), linkId))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkManagementServiceTest`
Expected: FAIL to compile — the class, and `AccountAggregatorLinkRepository.findByUserId`, don't
exist yet.

- [ ] **Step 3: Add the repository method, then the service**

```java
    List<AccountAggregatorLink> findByUserId(UUID userId);
```

```java
package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The one place the "this link is done" transition lives -- both the inbound
 * {@code consent.revoked} webhook (see {@link AccountAggregatorWebhookDispatcher}) and the
 * user-triggered disconnect endpoint (Plan 5) call {@link #revoke} rather than each writing their
 * own copy. Same {@code REVOKED} status either way -- see the Plan 5 scope doc's own "different
 * behavior → different state, same behavior → same state" reasoning for why this is deliberately
 * NOT two statuses. The who/why distinction a support ticket actually needs lives in the audit
 * action name instead: {@code ACCOUNT_AGGREGATOR_CONSENT_REVOKED} (the AA app revoked it) vs.
 * {@code ACCOUNT_AGGREGATOR_USER_DISCONNECTED} (the user clicked disconnect in Fynora) -- the
 * mechanism this codebase already uses for provenance questions, not a second copy of it in the
 * state machine.
 */
@Service
public class AccountAggregatorLinkManagementService {

    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AccountAggregatorLinkManagementService(AccountAggregatorLinkRepository links,
                                                    AccountRepository accountRepository,
                                                    AuditService auditService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    public List<AccountAggregatorLink> listForUser(UUID userId) {
        return links.findByUserId(userId);
    }

    /** User-triggered. NOT a call to Setu -- see AccountAggregatorLinkStatus.REVOKED's own doc
     *  comment: Fynora cannot force a revoke. This stops Fynora calling Setu for this link; the
     *  user's actual consent grant at their AA app is untouched, which the caller (the disconnect
     *  endpoint / its frontend confirmation copy) must say plainly. */
    public void disconnect(UUID userId, UUID linkId) {
        AccountAggregatorLink link = OwnershipGuard.requireOwned(
                links.findById(linkId), AccountAggregatorLink::getUserId, userId, "AccountAggregatorLink");
        revoke(link, "ACCOUNT_AGGREGATOR_USER_DISCONNECTED");
    }

    /** Shared by {@link #disconnect} and {@link AccountAggregatorWebhookDispatcher}'s
     *  {@code consent.revoked} case -- see this class's own doc comment for why one transition,
     *  not two. */
    void revoke(AccountAggregatorLink link, String auditAction) {
        link.setStatus(AccountAggregatorLinkStatus.REVOKED);
        if (link.getAccountId() != null) {
            accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                account.setPrimarySource(Account.PrimarySource.MANUAL);
                accountRepository.save(account);
            });
        }
        links.save(link);
        auditService.record(link.getUserId(), auditAction, "AccountAggregatorLink", link.getId());
    }
}
```

- [ ] **Step 4: Refactor the webhook dispatcher to delegate**

```java
            case "consent.revoked" ->
                    linkManagementService.revoke(link, "ACCOUNT_AGGREGATOR_CONSENT_REVOKED");
```

removing the now-duplicated inline logic, and adding
`AccountAggregatorLinkManagementService linkManagementService` as a new constructor dependency.
Update `AccountAggregatorWebhookDispatcherTest`'s existing `consent.revoked` test(s) to mock
`AccountAggregatorLinkManagementService` instead of asserting on `accountRepository`/`auditService`
directly for that one case (every other case's tests are unaffected).

- [ ] **Step 5: Write the IT proving the refactor didn't change real behavior**

```java
package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountAggregatorLinkManagementServiceTest} proves the transition's decision logic
 * against mocks; this proves the same transition still fires correctly through the REAL webhook
 * path after Task 4's refactor moved its implementation out of
 * AccountAggregatorWebhookDispatcher -- a regression test for the refactor itself, not a new
 * requirement.
 */
class AccountAggregatorLinkManagementServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private AccountAggregatorWebhookDispatcher dispatcher;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-mgmt-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Management Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();
    }

    @Test
    void theWebhookPathStillRevokesAndRevertsPrimarySourceAfterTheRefactor() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("aa-mgmt-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        dispatcher.dispatch("consent.revoked", link.getConsentHandleId());

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountAggregatorLinkStatus.REVOKED);
        Account reloadedAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(reloadedAccount.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkManagementServiceTest,AccountAggregatorLinkManagementServiceIT,AccountAggregatorWebhookDispatcherTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkManagementService.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkManagementServiceTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkManagementServiceIT.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "feat(backend): extract the shared AA link revoke transition"
```

---

### Task 5: `AccountAggregatorLinkLifecycleSweepService` — downgrade, upgrade, expiry, one sweep

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkLifecycleSweepService.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkLifecycleSweepServiceTest.java`
- Modify: `backend/src/main/resources/application-test.yml`

**Interfaces:**
- Produces: `int sweep()` — returns the total number of links transitioned this tick (paused +
  resumed + expired), mirroring every other sweep's own test-facing shape.

**One sweep, not three**, because all three checks are the same shape ("re-validate an
ACTIVE/PAUSED link against something that can change independently of any webhook") and scheduling
three near-identical services would be pure duplication:
1. `ACTIVE` link, user lost `ACCOUNT_AGGREGATOR_SYNC` → `PAUSED` + `primarySource` → `MANUAL` (the
   confirmed gap: today only the consent-approval-time check catches this, `SetuDataFetchService.sync`
   only skips the fetch).
2. `PAUSED` link, user regained `ACCOUNT_AGGREGATOR_SYNC` → `ACTIVE` + `primarySource` →
   `ACCOUNT_AGGREGATOR` (the confirmed gap: `PAUSED` has zero resume path today, checked via
   `grep -rn "AccountAggregatorLinkStatus.PAUSED"` finding one write site and no reads at all).
   Automatic, not a manual button — see the scope doc's `GmailDiscoveryWorker` precedent for why.
3. `ACTIVE` link, `consentExpiresAt` has passed → `EXPIRED` (the confirmed gap: `EXPIRED` is never
   set anywhere in the codebase despite `consentExpiresAt` being stored on every link).

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.repository.AccountRepository;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorLinkLifecycleSweepServiceTest {

    @Test
    void pausesAnActiveLinkWhoseUserLostEntitlementAndRevertsPrimarySource() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink active = new AccountAggregatorLink();
        active.setUserId(userId);
        active.setAccountId(accountId);
        active.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(active));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(active.getStatus()).isEqualTo(AccountAggregatorLinkStatus.PAUSED);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.MANUAL);
    }

    @Test
    void resumesAPausedLinkWhoseUserRegainedEntitlement() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountAggregatorLink paused = new AccountAggregatorLink();
        paused.setUserId(userId);
        paused.setAccountId(accountId);
        paused.setStatus(AccountAggregatorLinkStatus.PAUSED);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of());
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of(paused));
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        Account account = new Account();
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(paused.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        assertThat(account.getPrimarySource()).isEqualTo(Account.PrimarySource.ACCOUNT_AGGREGATOR);
    }

    @Test
    void expiresAnActiveLinkPastItsConsentExpiry() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink expired = new AccountAggregatorLink();
        expired.setUserId(userId);
        expired.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        expired.setConsentExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(expired));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(any(), any())).thenReturn(true); // entitled, just expired

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(AccountAggregatorLinkStatus.EXPIRED);
    }

    @Test
    void leavesAHealthyActiveLinkAlone() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        EntitlementService entitlementService = mock(EntitlementService.class);
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setUserId(UUID.randomUUID());
        healthy.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        healthy.setConsentExpiresAt(Instant.now().plus(300, ChronoUnit.DAYS));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(healthy));
        when(links.findByStatus(AccountAggregatorLinkStatus.PAUSED)).thenReturn(List.of());
        when(entitlementService.hasEntitlement(any(), any())).thenReturn(true);

        AccountAggregatorLinkLifecycleSweepService sweep = new AccountAggregatorLinkLifecycleSweepService(
                links, accountRepository, entitlementService);

        assertThat(sweep.sweep()).isZero();
        assertThat(healthy.getStatus()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        verifyNoInteractions(accountRepository);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkLifecycleSweepServiceTest`
Expected: FAIL to compile — the class doesn't exist yet.

- [ ] **Step 3: Implement the sweep**

```java
package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.repository.AccountRepository;
import com.finora.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Closes two real gaps found while scoping Plan 5, neither previously flagged:
 * <ul>
 *   <li>An ACTIVE link whose user downgrades stays ACTIVE forever -- SetuDataFetchService.sync's
 *       own entitlement check only skips that one fetch, never touching status or
 *       Account.primarySource. Only the consent-approval-time check (resolveAndAttach) actually
 *       pauses a link, and only if the entitlement was already lost at that exact moment.</li>
 *   <li>PAUSED has zero resume path anywhere in this codebase (confirmed via
 *       {@code grep -rn "AccountAggregatorLinkStatus.PAUSED"}: one write site, no reads) -- a user
 *       who re-upgrades has no way back to a working link short of disconnecting and starting a
 *       brand-new consent flow.</li>
 *   <li>EXPIRED is never set anywhere despite consentExpiresAt being stored on every link.</li>
 * </ul>
 * One sweep for all three, not three sweeps, since each is the same shape: re-validate an
 * ACTIVE/PAUSED link against something that can change independently of any webhook. Mirrors
 * {@code SubscriptionReconciliationSweepService}'s scheduling shape (fixedDelay, gated by a flag
 * {@code application-test.yml} turns off, tests call {@link #sweep()} directly).
 */
@Service
public class AccountAggregatorLinkLifecycleSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorLinkLifecycleSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final EntitlementService entitlementService;

    @Value("${app.integrations.setu.lifecycle-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorLinkLifecycleSweepService(AccountAggregatorLinkRepository links,
                                                        AccountRepository accountRepository,
                                                        EntitlementService entitlementService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.entitlementService = entitlementService;
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.lifecycle-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int changed = sweep();
        if (changed > 0) {
            log.info("Account Aggregator lifecycle sweep: {} link(s) transitioned.", changed);
        }
    }

    public int sweep() {
        int changed = 0;
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)) {
            if (link.getConsentExpiresAt() != null && link.getConsentExpiresAt().isBefore(Instant.now())) {
                link.setStatus(AccountAggregatorLinkStatus.EXPIRED);
                links.save(link);
                changed++;
            } else if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
                setPrimarySourceAndSave(link, AccountAggregatorLinkStatus.PAUSED, Account.PrimarySource.MANUAL);
                changed++;
            }
        }
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.PAUSED)) {
            if (entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
                setPrimarySourceAndSave(link, AccountAggregatorLinkStatus.ACTIVE, Account.PrimarySource.ACCOUNT_AGGREGATOR);
                changed++;
            }
        }
        return changed;
    }

    private void setPrimarySourceAndSave(AccountAggregatorLink link, AccountAggregatorLinkStatus newStatus,
                                          Account.PrimarySource newPrimarySource) {
        link.setStatus(newStatus);
        links.save(link);
        if (link.getAccountId() != null) {
            accountRepository.findById(link.getAccountId()).ifPresent(account -> {
                account.setPrimarySource(newPrimarySource);
                accountRepository.save(account);
            });
        }
    }
}
```

- [ ] **Step 4: Disable under the test profile**

`application-test.yml`, alongside the `integrations.setu` block Plan 4 adds (or a new one if Plan 4
hasn't merged yet):

```yaml
    setu:
      lifecycle-sweep:
        enabled: false
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkLifecycleSweepServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkLifecycleSweepService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkLifecycleSweepServiceTest.java \
        backend/src/main/resources/application-test.yml
git commit -m "feat(backend): close the PAUSED-never-resumes and EXPIRED-never-reached gaps"
```

---

### Task 6: `GET /links` and `POST /links/{id}/disconnect`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkDto.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`,
  `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Produces: `AccountAggregatorLinkDto(id, fiType, status, consentExpiresAt, lastSyncedAt,
  lastSyncStatus, statusChangedAt)`. No staleness field yet — see Global Constraints on the Plan 4
  dependency; added as a small follow-up once Plan 4 merges.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAggregatorLinkControllerTest {

    @Test
    void listReturnsTheCallersOwnLinksAsDtos() {
        AccountAggregatorLinkManagementService managementService = mock(AccountAggregatorLinkManagementService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(managementService.listForUser(userId)).thenReturn(List.of(link));

        AccountAggregatorLinkController controller = new AccountAggregatorLinkController(
                mock(SetuConsentService.class), currentUser,
                mock(AccountAggregatorIdentityResolutionService.class), managementService);

        assertThat(controller.list().getBody()).hasSize(1);
        assertThat(controller.list().getBody().get(0).status()).isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
    }

    @Test
    void disconnectDelegatesToTheManagementService() {
        AccountAggregatorLinkManagementService managementService = mock(AccountAggregatorLinkManagementService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);

        AccountAggregatorLinkController controller = new AccountAggregatorLinkController(
                mock(SetuConsentService.class), currentUser,
                mock(AccountAggregatorIdentityResolutionService.class), managementService);

        controller.disconnect(linkId);

        verify(managementService).disconnect(userId, linkId);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: FAIL to compile — the new constructor dependency and methods don't exist yet.

- [ ] **Step 3: Add the DTO and wire the controller**

```java
package com.finora.integrations.setu;

import java.time.Instant;
import java.util.UUID;

public record AccountAggregatorLinkDto(UUID id, FiType fiType, AccountAggregatorLinkStatus status,
                                        Instant consentExpiresAt, Instant lastSyncedAt,
                                        AccountAggregatorLink.SyncStatus lastSyncStatus,
                                        Instant statusChangedAt) {
    public static AccountAggregatorLinkDto from(AccountAggregatorLink link) {
        return new AccountAggregatorLinkDto(link.getId(), link.getFiType(), link.getStatus(),
                link.getConsentExpiresAt(), link.getLastSyncedAt(), link.getLastSyncStatus(),
                link.getStatusChangedAt());
    }
}
```

Add `AccountAggregatorLinkManagementService linkManagementService` to
`AccountAggregatorLinkController`'s constructor, and:

```java
    @GetMapping
    public ResponseEntity<List<AccountAggregatorLinkDto>> list() {
        return ResponseEntity.ok(linkManagementService.listForUser(currentUser.id()).stream()
                .map(AccountAggregatorLinkDto::from).toList());
    }

    @PostMapping("/{linkId}/disconnect")
    public ResponseEntity<Void> disconnect(@PathVariable UUID linkId) {
        linkManagementService.disconnect(currentUser.id(), linkId);
        return ResponseEntity.ok().build();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkControllerTest`
Expected: PASS

- [ ] **Step 5: Regenerate the OpenAPI spec and client types**

```bash
cd backend && ./mvnw -DskipTests package && bash scripts/generate-openapi-spec.sh
cd ../frontend && npm run generate:types
cd ../mobile && npm run generate:types
cd ../admin-portal && npm run generate:types
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkDto.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkController.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkControllerTest.java \
        backend/openapi/openapi.json \
        frontend/src/api/generated-types.ts \
        mobile/src/api/generated-types.ts \
        admin-portal/src/api/generated-types.ts
git commit -m "feat(backend): add GET /links and POST /links/{id}/disconnect"
```

---

### Task 7: Frontend API client — `accountAggregatorApi`

**Files:**
- Modify: `frontend/src/api/endpoints.ts`

**Interfaces:**
- Produces: `accountAggregatorApi.{list, initiate, confirmExistingAccount, confirmNewAccount,
  disconnect}` — mirrors `gmailApi`'s own shape exactly, same file, same conventions.

- [ ] **Step 1: Add the client**

```typescript
export const accountAggregatorApi = {
  list: () => api.get<AccountAggregatorLinkDto[]>('/integrations/setu/links').then((r) => r.data),
  initiate: (fiType: 'DEPOSIT' | 'CREDIT_CARD', idempotencyKey: string) =>
    api.post<{ linkId: string; status: string; redirectUrl: string | null }>(
      '/integrations/setu/links', { fiType, idempotencyKey }).then((r) => r.data),
  confirmExistingAccount: (linkId: string, accountId: string) =>
    api.post(`/integrations/setu/links/${linkId}/confirm-existing-account`, { accountId }),
  confirmNewAccount: (linkId: string) =>
    api.post(`/integrations/setu/links/${linkId}/confirm-new-account`),
  disconnect: (linkId: string) => api.post(`/integrations/setu/links/${linkId}/disconnect`),
};
```

(`AccountAggregatorLinkDto` type comes from the regenerated `generated-types.ts`, Task 6 — import it
the same way `GmailConnectionStatus` is imported at the top of this file.)

This task has no dedicated tests of its own — it's a thin, directly-typed wrapper exercised by every
test in Tasks 8-10.

- [ ] **Step 2: Compile**

Run: `cd frontend && npx tsc -b`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/endpoints.ts
git commit -m "feat(frontend): add accountAggregatorApi client"
```

---

### Task 8: "Bank Sync" section in Settings — connect flow

**Files:**
- Modify: `frontend/src/pages/Settings.tsx`
- Modify: `frontend/src/pages/Settings.test.tsx`

**Interfaces:**
- New UI section, no new exported interfaces.

Mirrors the Gmail section's state shape exactly (`gmailStatus`/`gmailLoading`/`gmailError`/
`gmailConnecting`/`gmailActionError` → the same five states, `aaLinks`/`aaLoading`/`aaError`/
`aaConnecting`/`aaActionError`, plural since a user can have more than one link unlike Gmail's
single connection) — see the scope doc's own reasoning for why this is the right structural
precedent.

- [ ] **Step 1: Write the failing tests**

```typescript
  it('shows a Connect button when no accounts are linked, and starts the AA redirect on click', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([]);
    const user = userEvent.setup();
    renderSettings();

    await screen.findByText(/bank sync/i);
    expect(screen.getByText(/no bank accounts linked yet/i)).toBeInTheDocument();

    vi.mocked(accountAggregatorApi.initiate).mockResolvedValue({
      linkId: 'link-1', status: 'CONSENT_PENDING', redirectUrl: 'https://aa-app.example/consent/abc',
    });
    const originalLocation = window.location;
    // @ts-expect-error -- test-only reassignment, same pattern this file already uses for
    // window.location.href elsewhere (see the existing Gmail connect test).
    delete window.location;
    window.location = { ...originalLocation, href: '' };

    await user.click(screen.getByRole('button', { name: /connect a bank account/i }));

    expect(window.location.href).toBe('https://aa-app.example/consent/abc');
    window.location = originalLocation;
  });

  it('lists linked accounts with status and a disconnect control', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'link-1', fiType: 'DEPOSIT', status: 'ACTIVE', consentExpiresAt: null,
        lastSyncedAt: '2026-09-01T00:00:00Z', lastSyncStatus: 'SUCCESS',
        statusChangedAt: '2026-08-01T00:00:00Z' },
    ]);
    renderSettings();

    await screen.findByText(/bank sync/i);
    expect(screen.getByText(/active/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /disconnect/i })).toBeInTheDocument();
  });

  it('disconnect asks for confirmation before calling the API', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'link-1', fiType: 'DEPOSIT', status: 'ACTIVE', consentExpiresAt: null,
        lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
    ]);
    vi.mocked(accountAggregatorApi.disconnect).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderSettings();

    await screen.findByText(/bank sync/i);
    await user.click(screen.getByRole('button', { name: /disconnect/i }));
    // Required copy per the scope doc's own decision: disconnecting must not read as ending the
    // user's actual consent grant at their AA app.
    expect(screen.getByText(/does not cancel your consent/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /confirm disconnect/i }));

    expect(accountAggregatorApi.disconnect).toHaveBeenCalledWith('link-1');
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Settings.test.tsx`
Expected: FAIL — no "Bank Sync" section exists yet.

- [ ] **Step 3: Implement the section**

State (alongside the existing Gmail state block):

```tsx
  const [aaLinks, setAaLinks] = useState<AccountAggregatorLinkDto[]>([]);
  const [aaLoading, setAaLoading] = useState(true);
  const [aaError, setAaError] = useState(false);
  const [aaConnecting, setAaConnecting] = useState(false);
  const [aaActionError, setAaActionError] = useState<string | null>(null);
  const [aaDisconnectingId, setAaDisconnectingId] = useState<string | null>(null);
  const [aaConfirmingDisconnectId, setAaConfirmingDisconnectId] = useState<string | null>(null);
```

```tsx
  function loadAaLinks() {
    setAaLoading(true);
    setAaError(false);
    accountAggregatorApi.list()
      .then(setAaLinks)
      .catch(() => setAaError(true))
      .finally(() => setAaLoading(false));
  }

  async function handleAaConnect() {
    setAaConnecting(true);
    setAaActionError(null);
    try {
      const { redirectUrl } = await accountAggregatorApi.initiate('DEPOSIT', crypto.randomUUID());
      if (redirectUrl) {
        window.location.href = redirectUrl;
      } else {
        // No redirectUrl means this idempotency key was already used (a retried request) -- see
        // SetuConsentService.initiateLink's own comment. Nothing new to navigate to.
        setAaActionError('This connection attempt is already in progress.');
        setAaConnecting(false);
      }
    } catch (err) {
      setAaConnecting(false);
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setAaActionError(message || "Couldn't start connecting your bank -- please try again.");
    }
  }

  async function handleAaDisconnect(linkId: string) {
    setAaDisconnectingId(linkId);
    setAaActionError(null);
    try {
      await accountAggregatorApi.disconnect(linkId);
      setAaConfirmingDisconnectId(null);
      loadAaLinks();
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setAaActionError(message || "Couldn't disconnect -- please try again.");
    } finally {
      setAaDisconnectingId(null);
    }
  }
```

`useEffect(() => { loadAaLinks(); }, [])` alongside the existing `loadGmailStatus()` effect.

JSX: a new section titled "Bank Sync," structurally mirroring the Gmail section's card layout --
loading skeleton (`useDelayedLoading(aaLoading)`), an error state, an empty state with a "Connect a
bank account" button when `aaLinks.length === 0`, and a list of `aaLinks` otherwise, each row
showing fiType, status (per the scope doc's consent-lifecycle copy: `ACTIVE` "Connected",
`PAUSED` "Your plan no longer includes Bank Sync -- reconnects automatically once you upgrade"
[not a manual button, per Task 5], `REJECTED` "Declined -- try again", `LINK_FAILED` "Couldn't
connect -- try again", `EXPIRED` "Your bank connection has expired -- reconnect", `REVOKED`
"Disconnected"), and a "Disconnect" button (hidden for already-terminal statuses) that opens an
inline confirmation with the required "does not cancel your consent" copy before calling
`handleAaDisconnect`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Settings.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Settings.tsx frontend/src/pages/Settings.test.tsx
git commit -m "feat(frontend): add the Bank Sync connect/manage section to Settings"
```

---

### Task 9: PROBABLE-match confirmation screen

**Files:**
- Create: `frontend/src/pages/AccountAggregatorConfirm.tsx`
- Create: `frontend/src/pages/AccountAggregatorConfirm.test.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- New route `/app/settings/bank-sync/:linkId/confirm`.

`AccountAggregatorLinkController.confirmExistingAccount`/`confirmNewAccount` have existed since Plan
1 with zero frontend callers — this task is the first UI for existing, already-tested backend logic,
not new backend behavior.

- [ ] **Step 1: Write the failing tests**

```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import { accountAggregatorApi } from '../api/endpoints';
import AccountAggregatorConfirm from './AccountAggregatorConfirm';

vi.mock('../api/endpoints');

function renderConfirm(linkId = 'link-1') {
  return render(
    <MemoryRouter initialEntries={[`/app/settings/bank-sync/${linkId}/confirm`]}>
      <Routes>
        <Route path="/app/settings/bank-sync/:linkId/confirm" element={<AccountAggregatorConfirm />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('AccountAggregatorConfirm', () => {
  it('confirms the existing account when the user picks it', async () => {
    vi.mocked(accountAggregatorApi.confirmExistingAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await user.click(screen.getByRole('button', { name: /yes, this is my account/i }));

    expect(accountAggregatorApi.confirmExistingAccount).toHaveBeenCalledWith('link-1', expect.any(String));
  });

  it('confirms a new account when the user says this is different', async () => {
    vi.mocked(accountAggregatorApi.confirmNewAccount).mockResolvedValue({} as never);
    const user = userEvent.setup();
    renderConfirm();

    await user.click(screen.getByRole('button', { name: /this is a different\/new account/i }));

    expect(accountAggregatorApi.confirmNewAccount).toHaveBeenCalledWith('link-1');
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/AccountAggregatorConfirm.test.tsx`
Expected: FAIL — the component doesn't exist yet.

- [ ] **Step 3: Implement the screen**

A minimal, single-purpose screen: "Is this your [Bank] account ending ****1234?" (the design spec's
own suggested copy, "Account identity resolution" section) with two buttons, wired to
`confirmExistingAccount`/`confirmNewAccount`, redirecting to `/app/settings` on success. Reads
`linkId` from the route param (`useParams`); the candidate account's display details come from
wherever `PENDING_ACCOUNT_CONFIRMATION` surfaces them today — check
`AccountAggregatorIdentityResolutionService.resolveAndAttach`'s `PROBABLE` branch for what's
actually available to show (the match candidates), since this detail wasn't fully specified in the
scope doc and needs confirming against that method's real return shape before finalizing the props
this component reads.

- [ ] **Step 4: Add the route**

```tsx
const AccountAggregatorConfirm = lazy(() => import('./pages/AccountAggregatorConfirm'));
...
<Route path="/app/settings/bank-sync/:linkId/confirm" element={<Protected><AccountAggregatorConfirm /></Protected>} />
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/AccountAggregatorConfirm.test.tsx`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/AccountAggregatorConfirm.tsx frontend/src/pages/AccountAggregatorConfirm.test.tsx frontend/src/App.tsx
git commit -m "feat(frontend): add the PROBABLE-match confirmation screen"
```

---

## After Task 9: run the full suite

```bash
cd backend && ./mvnw test
cd ../frontend && npm run build && npx vitest run
```

Both must be green, no regressions, before this plan is considered done. Per this repo's own
mandatory post-implementation verification (`CLAUDE.md`), do not stop at "the tests I wrote pass" --
reread the scope doc's settled decisions and confirm each one actually has real, verified behavior
behind it. In particular: confirm the `PAUSED`/`EXPIRED` sweep transitions actually fire against a
real Postgres transaction (an IT, not just the mocked unit tests in Task 5), the same discipline
Plan 4's own review pass required for its audit-rollback finding — this plan's sweep mutates two
tables (`account_aggregator_links` and `accounts`) in one pass, exactly the shape that class of bug
lives in.

## Still open, not resolved by this implementation plan

The scope doc's own open items are implementation decisions this plan had to make a bootstrap choice
for (link cap = 5, relink throttle = 24h by `fiType`) or a design choice for
(`PAUSED` counts toward the cap — Task 3 implements it counting, since the repository query takes an
explicit status list and product can narrow it to exclude `PAUSED` by changing one call site, not
logic, whenever that policy question is actually answered) — none of this is presented as more
settled than the scope doc itself left it. Genuinely unresolved and unaffected by this plan:
Setu's real return-redirect support (Task 8's connect flow assumes none exists and polls/re-fetches
`accountAggregatorApi.list()` after the user returns to Settings by any means, rather than a
synchronous callback — revisit once Setu sandbox access confirms otherwise) and whether Setu sends
any consent-expiry webhook (Task 5's sweep is the safety net either way, per the scope doc's own
"webhook-plus-safety-net" reasoning).
