# Account Aggregator Outage Escape Hatch Implementation Plan (Plan 4 of 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An `ACTIVE` `AccountAggregatorLink` that has stopped syncing (Setu/AA/FIP outage, or a
link that never completed its first sync) must not permanently lock a user out of both the
automatic feed (down) and manual upload (blocked by `AccountAggregatorGuard`). Manual import
unblocks itself, live, once a link has gone unsynced past 3x its expected cadence, and re-blocks
itself the instant a fresh sync succeeds -- no separate "re-attach" step, no persisted `STALE`
status to fall out of sync with reality.

**Architecture:** One new shared predicate bean (`AccountAggregatorLinkStalenessService`) is the
single source of truth for "is this link stale," consumed by three call sites that previously had
no way to agree with each other: the import guard (backend enforcement), the account list DTO
(frontend-facing signal), and a new read-only monitoring sweep. `AccountAggregatorGuard` is
promoted from a static nested class inside `ImportService` to a real Spring bean, since it now
needs two more dependencies than a hand-constructed nested class could reasonably take.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ (backend); React/TypeScript, Vitest +
Testing Library (frontend).

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)'s
"Outage escape hatch" and "Missing requirements" (sync monitoring, incident alerting) sections.
**Scope doc:** [2026-09-13-account-aggregator-outage-escape-hatch-scope.md](2026-09-13-account-aggregator-outage-escape-hatch-scope.md)
-- every design decision below (threshold math, single-source-of-truth, sweep being read-only,
what's deliberately out of scope) was settled there across three review rounds; this plan does not
re-litigate any of it, only turns it into tasks.

## Global Constraints

- **One staleness predicate, three consumers, zero duplicated threshold math.**
  `AccountAggregatorLinkStalenessService.isStale(link)` is the only place `now - reference > 72h`
  is ever computed. The guard, `AccountService`'s DTO assembly, and the new sweep all call it --
  none of them re-derive it. (The scope doc's own review history is explicit about why: a second,
  independently-drifting copy of this exact threshold is the class of bug that already had to be
  fixed once, between the frontend picker and the backend guard, before this plan even started.)
- **The sweep is observability only.** It mutates no `AccountAggregatorLink` and no `Account`. The
  hatch itself is evaluated live, per request, by the guard. Disabled under the `test` profile
  (`application-test.yml`), same convention as every other scheduled sweep in this codebase --
  tests call `sweep()` directly.
- **`primarySource` is never touched by this plan.** The hatch bypasses the guard; it does not
  revert `Account.PrimarySource` to `MANUAL` the way `AccountAggregatorWebhookDispatcher` does for
  `REVOKED`/`EXPIRED`/`PAUSED`. That distinction is load-bearing -- see the scope doc's Task 2.
- Threshold and cadence are single global config constants (`expected-cadence-hours`, default 24;
  threshold = 3x that, computed in code, not a second config key) -- no per-link value, no schema
  change, until real Setu sandbox access confirms one is available (open item, not resolved by
  this plan).
- No AI-attribution trailer in any commit message (repository rule, `CLAUDE.md`).

---

## File Structure

```
backend/src/main/java/com/finora/integrations/setu/
  AccountAggregatorLinkStalenessService.java   (new)
  AccountAggregatorLinkRepository.java          (modify -- 2 new query methods)
  AccountAggregatorOutageSweepService.java      (new)

backend/src/main/java/com/finora/imports/
  AccountAggregatorGuard.java                   (new -- promoted out of ImportService)
  ImportService.java                            (modify -- constructor param swap, remove nested class)

backend/src/main/java/com/finora/accounts/
  AccountDto.java                                (modify -- add aaSyncStale field)
  AccountService.java                            (modify -- compute aaSyncStale in listForUser)

backend/src/main/resources/application-test.yml  (modify -- disable the new sweep under test)

backend/openapi/openapi.json                     (regenerate)
frontend/src/api/generated-types.ts              (regenerate)
mobile/src/api/generated-types.ts                (regenerate)
admin-portal/src/api/generated-types.ts          (regenerate)

frontend/src/types/index.ts                      (modify -- add aaSyncStale to Account)
frontend/src/pages/Import.tsx                    (modify -- picker disabled/label logic)
frontend/src/lib/accountMatch.ts                 (modify -- eligibleAccounts filter)

backend/src/test/java/com/finora/integrations/setu/
  AccountAggregatorLinkStalenessServiceTest.java (new)
  AccountAggregatorOutageSweepServiceTest.java   (new)

backend/src/test/java/com/finora/imports/
  AccountAggregatorGuardTest.java                 (new -- replaces ImportServiceAccountAggregatorBlockTest.java)
  ImportServiceStorageDualWriteTest.java          (modify -- constructor call site)
  ImportServiceShadowEvidenceIsolationTest.java   (modify -- constructor call site)
  MultiSectionZeroExtractionTest.java             (modify -- constructor call site)
  VerificationSurvivesStagingConversionTest.java  (modify -- constructor call site)
  ImportServiceSessionTest.java                   (modify -- constructor call site)
  ImportServiceOpeningBalanceCarryForwardTest.java (modify -- constructor call site)
  ImportServiceAskOnceTest.java                   (modify -- constructor call site)
  ImportServiceCoverageWarningsTest.java          (modify -- constructor call site)

backend/src/test/java/com/finora/accounts/
  AccountServiceTest.java                         (modify -- constructor call site + new assertions)

frontend/src/pages/
  Import.test.tsx                                 (modify -- new test case)
frontend/src/lib/
  accountMatch.test.ts                             (modify -- new test case)
```

---

### Task 1: `AccountAggregatorLinkStalenessService` -- the shared predicate

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkStalenessService.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkStalenessServiceTest.java`

**Interfaces:**
- Produces: `boolean isStale(AccountAggregatorLink link)`. Pure -- no repository access, no
  side effects, evaluated against `Instant.now()`.

Rule (settled across the scope doc's review rounds, not re-derived here):

```
lastSyncedAt != null  ->  stale when  now - lastSyncedAt > threshold
lastSyncedAt == null  ->  stale when  now - updatedAt    > threshold

threshold = 3 * expected-cadence-hours (default 24h -> 72h)
```

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkStalenessServiceTest {

    // 24h cadence -> 72h threshold, same default the scope doc settled on.
    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24);

    private AccountAggregatorLink linkSyncedAgo(java.time.Duration ago) {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setLastSyncedAt(Instant.now().minus(ago));
        return link;
    }

    private AccountAggregatorLink linkNeverSyncedUpdatedAgo(java.time.Duration ago) {
        AccountAggregatorLink link = new AccountAggregatorLink();
        // lastSyncedAt stays null (default). updatedAt is set on construction by the entity's own
        // field initializer -- overridden here via reflection to simulate a link that has been
        // sitting untouched since that duration ago.
        ReflectionTestUtils.setField(link, "updatedAt", Instant.now().minus(ago));
        return link;
    }

    @Test
    void notStaleWhenSyncedWellWithinTheThreshold() {
        assertThat(staleness.isStale(linkSyncedAgo(java.time.Duration.ofHours(1)))).isFalse();
    }

    @Test
    void staleWhenLastSyncIsWellPastTheThreshold() {
        assertThat(staleness.isStale(linkSyncedAgo(java.time.Duration.ofHours(96)))).isTrue();
    }

    @Test
    void boundaryIsExclusive_exactlyAtSeventyTwoHoursIsNotYetStale() {
        // "exceeds 3x the expected cadence" (design spec) -- exactly at the threshold has not yet
        // exceeded it. One second past does.
        assertThat(staleness.isStale(linkSyncedAgo(java.time.Duration.ofHours(72)))).isFalse();
        assertThat(staleness.isStale(linkSyncedAgo(java.time.Duration.ofHours(72).plusSeconds(1)))).isTrue();
    }

    @Test
    void aFreshlyActivatedNeverSyncedLinkIsNotStale() {
        // The narrow real race the scope doc traced: attach() saves ACTIVE, then the backfill
        // sync() call sets lastSyncedAt a moment later. A link only seconds old with lastSyncedAt
        // still null must not immediately open the hatch.
        assertThat(staleness.isStale(linkNeverSyncedUpdatedAgo(java.time.Duration.ofSeconds(5)))).isFalse();
    }

    @Test
    void aNeverSyncedLinkStillSittingThereAfterSeventyTwoHoursIsStale() {
        // Entitlement lapsed right at activation, or Setu credentials missing -- both leave
        // lastSyncedAt permanently null. Same 72h threshold as any other stale link, not a
        // separate shorter constant.
        assertThat(staleness.isStale(linkNeverSyncedUpdatedAgo(java.time.Duration.ofHours(96)))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkStalenessServiceTest`
Expected: FAIL to compile -- the class doesn't exist yet.

- [ ] **Step 3: Implement the service**

```java
package com.finora.integrations.setu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The single place "is this Account Aggregator link stale" is computed -- see this plan's own
 * scope doc for why a second, independently-drifting copy of this threshold is exactly the bug
 * class this plan exists to avoid repeating. Three callers ask this bean the same question:
 * {@link com.finora.imports.AccountAggregatorGuard} (backend enforcement, live per request),
 * {@code AccountService}'s {@code AccountDto} assembly (the frontend-facing {@code aaSyncStale}
 * signal), and {@link AccountAggregatorOutageSweepService} (observability only).
 *
 * <p>Staleness measures absence of successful sync EVENTS, not absence of fresh transaction DATA
 * -- a sync that succeeds and finds zero new transactions is healthy. See the design spec's
 * "Outage escape hatch" section and this plan's scope doc for the full reasoning; not re-derived
 * here.
 */
@Component
public class AccountAggregatorLinkStalenessService {

    private final Duration threshold;

    public AccountAggregatorLinkStalenessService(
            @Value("${app.integrations.setu.expected-cadence-hours:24}") long expectedCadenceHours) {
        // 3x expected cadence -- reuses the design spec's own stated multiplier and its "one
        // number, not three independently invented ones" reasoning (shared by the hatch and the
        // alerting threshold below).
        this.threshold = Duration.ofHours(expectedCadenceHours * 3);
    }

    /**
     * @param link an ACTIVE link -- callers are responsible for the status check; this method only
     *             answers the staleness question, not "should the hatch apply at all."
     */
    public boolean isStale(AccountAggregatorLink link) {
        Instant reference = link.getLastSyncedAt() != null ? link.getLastSyncedAt() : link.getUpdatedAt();
        return Duration.between(reference, Instant.now()).compareTo(threshold) > 0;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorLinkStalenessServiceTest`
Expected: PASS (all 5 cases, including both boundary assertions).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkStalenessService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkStalenessServiceTest.java
git commit -m "feat(backend): add AccountAggregatorLinkStalenessService"
```

---

### Task 2: Repository queries the guard, sweep, and DTO assembly all need

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java`

**Interfaces:**
- Produces: `List<AccountAggregatorLink> findByAccountIdInAndStatus(Collection<UUID> accountIds, AccountAggregatorLinkStatus status)`
  (batch lookup -- `AccountService.listForUser` needs every `ACTIVE` link across a whole account
  list in one query, the same N+1-avoidance discipline that method already applies to statement
  metadata and transaction counts, not a per-account query in a loop).
- Produces: `List<AccountAggregatorLink> findByStatus(AccountAggregatorLinkStatus status)` (the
  sweep's read path -- deliberately NOT a `COALESCE(lastSyncedAt, updatedAt) < cutoff` SQL filter,
  which would be a THIRD independent copy of the threshold math Task 1 exists to prevent; the sweep
  fetches every `ACTIVE` link and filters with `AccountAggregatorLinkStalenessService.isStale`
  in Java, same as the guard and the DTO assembly do).

This task is additive-only (two new derived-query methods on an existing repository interface) --
no TDD loop of its own; Spring Data derived queries have no logic to unit-test in isolation, and
both methods are exercised by Task 4's and Task 5's own tests.

- [ ] **Step 1: Add the two methods**

```java
    /** AccountService.listForUser's batch resolution of Account.aaSyncStale (Plan 4) -- one query
     *  for every account on the page, not one per account. Staleness itself is computed by
     *  AccountAggregatorLinkStalenessService against each returned link, not by this query. */
    List<AccountAggregatorLink> findByAccountIdInAndStatus(
            java.util.Collection<UUID> accountIds, AccountAggregatorLinkStatus status);

    /** AccountAggregatorOutageSweepService's read path (Plan 4) -- every currently-ACTIVE link,
     *  filtered for staleness in Java via AccountAggregatorLinkStalenessService.isStale, not a
     *  second copy of the threshold math in SQL. */
    List<AccountAggregatorLink> findByStatus(AccountAggregatorLinkStatus status);
```

- [ ] **Step 2: Compile**

Run: `cd backend && ./mvnw compile`
Expected: PASS (Spring Data derived-query methods compile without a corresponding test; correctness
is verified through the callers in Tasks 4 and 5).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLinkRepository.java
git commit -m "feat(backend): add batch and status-only AA link queries"
```

---

### Task 3: Promote `AccountAggregatorGuard` to a real bean, wire the escape hatch

**Files:**
- Create: `backend/src/main/java/com/finora/imports/AccountAggregatorGuard.java`
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` (remove the static nested
  class at lines 99-125; swap the `accountAggregatorLinkRepository` constructor parameter for an
  injected `AccountAggregatorGuard`)
- Create: `backend/src/test/java/com/finora/imports/AccountAggregatorGuardTest.java` (replaces
  `ImportServiceAccountAggregatorBlockTest.java`, same two existing cases plus the new ones below)
- Modify (constructor call site only -- delete the trailing
  `mock(com.finora.integrations.setu.AccountAggregatorLinkRepository.class)` argument, replace with
  `mock(AccountAggregatorGuard.class)`): `ImportServiceStorageDualWriteTest.java`,
  `ImportServiceShadowEvidenceIsolationTest.java`, `MultiSectionZeroExtractionTest.java`,
  `VerificationSurvivesStagingConversionTest.java`, `ImportServiceSessionTest.java`,
  `ImportServiceOpeningBalanceCarryForwardTest.java`, `ImportServiceAskOnceTest.java`,
  `ImportServiceCoverageWarningsTest.java` -- verified via
  `grep -rn "new ImportService(" backend/src/test/java`, all 8 pass the AA link repository mock as
  the last positional constructor argument, at that exact position, and nowhere else in the file --
  a mechanical one-line swap in each, not a semantic change (none of these 8 tests exercise AA
  behavior; the guard mock defaults to a no-op `checkNotActivelySynced`, identical in effect to the
  no-op-returning repository mock it replaces).

**Why the promotion, not a bigger constructor:** `AccountAggregatorGuard` was a static nested class,
hand-constructed inside `ImportService`'s constructor from 2 raw dependencies
(`accountRepository`, `accountAggregatorLinkRepository`). Task 1's staleness service and an
`AuditService` (for the hatch-used audit log) are two more dependencies the guard now needs.
Threading both through `ImportService`'s constructor -- already 27 parameters -- just to reach a
class it doesn't otherwise use directly would make an already-large constructor larger for no
benefit. Making the guard a real `@Component` instead removes a parameter from `ImportService`'s
constructor (`accountAggregatorLinkRepository`, previously passed through only to build the nested
class -- confirmed via `grep -n "accountAggregatorLinkRepository" ImportService.java`, it has no
other use in the file) and replaces it with one injected `AccountAggregatorGuard` field. Net: one
fewer constructor parameter on `ImportService`, not more.

**Interfaces:**
- `AccountAggregatorGuard.checkNotActivelySynced(UUID userId, UUID accountId)` -- unchanged
  signature and unchanged behavior for every case except the new staleness bypass.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountAggregatorGuardTest {

    // Real instance, not a mock -- AccountAggregatorLinkStalenessService is a pure computation
    // over the link's own fields (Task 1), nothing to stub. 24h cadence -> 72h threshold.
    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24);

    private AccountRepository accountRepositoryReturning(UUID userId, UUID accountId) {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        return accountRepository;
    }

    @Test
    void refusesAnExistingAccountWhoseAaLinkIsActiveAndNotStale() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        // Freshly constructed -- lastSyncedAt null, updatedAt defaults to Instant.now() via the
        // entity's own field initializer, so this link is not stale.
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(new AccountAggregatorLink()));
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void allowsAnAccountWhoseAaLinkIsNotActive() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        account.setUserId(userId);
        account.setPrimarySource(Account.PrimarySource.MANUAL);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // does not throw
    }

    @Test
    void allowsManualImportWhenTheActiveLinkIsStaleAndAuditsIt() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(96)));
        UUID linkId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(stale, "id", linkId);
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(stale));
        AuditService auditService = mock(AuditService.class);

        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // does not throw -- the hatch is open

        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED"),
                eq("AccountAggregatorLink"), eq(linkId));
    }

    @Test
    void theHatchClosesAgainOnceTheLinkResyncs() {
        // The auto-reclose guarantee this plan is built around: "computed, not persisted" only
        // means something if two calls against the same (mutated) link disagree. Same guard
        // instance, same repository mock, the underlying link object is mutated between calls the
        // way a real sync would mutate the row this guard re-reads on the next request.
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        AccountRepository accountRepository = accountRepositoryReturning(userId, accountId);

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(96)));
        AccountAggregatorLinkRepository aaLinks = mock(AccountAggregatorLinkRepository.class);
        when(aaLinks.findByAccountIdAndStatus(accountId, AccountAggregatorLinkStatus.ACTIVE))
                .thenReturn(Optional.of(link));
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorGuard guard = new AccountAggregatorGuard(accountRepository, aaLinks, staleness, auditService);

        guard.checkNotActivelySynced(userId, accountId); // stale -- hatch open, does not throw

        // A sync succeeds -- SetuDataFetchService.sync's own effect on the row this guard reads.
        link.setLastSyncedAt(Instant.now());

        assertThatThrownBy(() -> guard.checkNotActivelySynced(userId, accountId))
                .isInstanceOf(ApiException.class); // hatch closed again, no code path re-opens it
    }
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorGuardTest`
Expected: FAIL to compile -- `AccountAggregatorGuard` (top-level), `AccountAggregatorLinkStalenessService`
constructor param, and the 4-arg `AccountAggregatorGuard` constructor don't exist yet at this path.

- [ ] **Step 3: Create the top-level guard**

```java
package com.finora.imports;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Refuses a manual import into an account an ACTIVE AccountAggregatorLink already owns -- see
 *  the design spec's "Account identity resolution" section: hiding the upload control in the UI
 *  is not enforcement, this is. A PAUSED/REVOKED/EXPIRED link's account is unaffected -- see
 *  AccountAggregatorWebhookDispatcher, which reverts primarySource to MANUAL the moment a link
 *  stops being ACTIVE, so this check only ever fires while sync is genuinely live.
 *
 *  <p>Promoted from a static nested class inside ImportService (Plan 1) to a top-level Spring bean
 *  here (Plan 4, the outage escape hatch) -- see this plan's own doc for why: the two new
 *  dependencies below made a hand-constructed nested class the wrong shape, and this removes a
 *  parameter from ImportService's constructor rather than adding more to it. */
@Component
public class AccountAggregatorGuard {
    private final AccountRepository accountRepository;
    private final AccountAggregatorLinkRepository aaLinks;
    private final AccountAggregatorLinkStalenessService staleness;
    private final AuditService auditService;

    public AccountAggregatorGuard(AccountRepository accountRepository, AccountAggregatorLinkRepository aaLinks,
                                   AccountAggregatorLinkStalenessService staleness, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.aaLinks = aaLinks;
        this.staleness = staleness;
        this.auditService = auditService;
    }

    public void checkNotActivelySynced(UUID userId, UUID accountId) {
        Account account = OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        if (account.getPrimarySource() != Account.PrimarySource.ACCOUNT_AGGREGATOR) return;
        Optional<AccountAggregatorLink> activeLink = aaLinks.findByAccountIdAndStatus(accountId,
                AccountAggregatorLinkStatus.ACTIVE);
        if (activeLink.isEmpty()) return;
        AccountAggregatorLink link = activeLink.get();

        if (staleness.isStale(link)) {
            // Outage escape hatch (Plan 4). primarySource stays ACCOUNT_AGGREGATOR -- this is a
            // transient, self-correcting bypass (the hatch closes on its own the instant a fresh
            // sync succeeds), not the durable MANUAL reversion AccountAggregatorWebhookDispatcher
            // performs for REVOKED/EXPIRED/PAUSED. Audited so product/support has a queryable
            // trail of when the hatch was actually exercised, not just when it was available.
            auditService.record(userId, "ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED",
                    "AccountAggregatorLink", link.getId());
            return;
        }

        throw new ApiException(HttpStatus.CONFLICT,
                "This account syncs automatically and can't be manually imported into "
                + "while that sync is active.");
    }
}
```

- [ ] **Step 4: Remove the nested class from ImportService, wire the injected bean**

Delete the `static class AccountAggregatorGuard { ... }` block and its leading doc comment
(`ImportService.java` lines ~99-125). Replace the constructor's
`com.finora.integrations.setu.AccountAggregatorLinkRepository accountAggregatorLinkRepository`
parameter with `AccountAggregatorGuard accountAggregatorGuard` (same package, no import needed),
and change the body from
`this.accountAggregatorGuard = new AccountAggregatorGuard(accountRepository, accountAggregatorLinkRepository);`
to `this.accountAggregatorGuard = accountAggregatorGuard;`. The field declaration
(`private final AccountAggregatorGuard accountAggregatorGuard;`) and the one call site
(`accountAggregatorGuard.checkNotActivelySynced(userId, request.existingAccountId());`, line 1770)
need no changes -- both already refer to the unqualified type name.

- [ ] **Step 5: Delete the old test, update the 8 constructor call sites**

Delete `ImportServiceAccountAggregatorBlockTest.java` (fully replaced by
`AccountAggregatorGuardTest.java` above). In each of the 8 files listed under Files above, change
the trailing `mock(com.finora.integrations.setu.AccountAggregatorLinkRepository.class)` argument to
`mock(AccountAggregatorGuard.class)` (add the `com.finora.imports.AccountAggregatorGuard` import
where the test isn't already in that package).

- [ ] **Step 6: Run tests to verify everything passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorGuardTest,ImportServiceStorageDualWriteTest,ImportServiceShadowEvidenceIsolationTest,MultiSectionZeroExtractionTest,VerificationSurvivesStagingConversionTest,ImportServiceSessionTest,ImportServiceOpeningBalanceCarryForwardTest,ImportServiceAskOnceTest,ImportServiceCoverageWarningsTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/AccountAggregatorGuard.java \
        backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/AccountAggregatorGuardTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceAccountAggregatorBlockTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceStorageDualWriteTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceShadowEvidenceIsolationTest.java \
        backend/src/test/java/com/finora/imports/MultiSectionZeroExtractionTest.java \
        backend/src/test/java/com/finora/imports/VerificationSurvivesStagingConversionTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceSessionTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceOpeningBalanceCarryForwardTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceCoverageWarningsTest.java
git commit -m "feat(backend): wire the outage escape hatch into AccountAggregatorGuard"
```

---

### Task 4: `AccountAggregatorOutageSweepService` -- observability only

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorOutageSweepService.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorOutageSweepServiceTest.java`
- Modify: `backend/src/main/resources/application-test.yml`

**Interfaces:**
- Produces: gauge `finora.account_aggregator.stale_links` (current count of `ACTIVE` links past
  the staleness threshold), and one `WARN` log line per stale link found, on every tick.
- Public method `int sweep()` -- returns the count found, mirrors every other sweep's own
  test-facing shape (`AccountAggregatorLinkSweepService.sweepStaleLinks()`,
  `SubscriptionReconciliationSweepService.sweep()`).

**A gauge is registered once, not re-registered per tick.** Micrometer's `Gauge.builder(...)` binds
to a live reference (here, an `AtomicInteger`) at construction time; the sweep updates that
reference's value on every tick rather than calling `.register(...)` again, which would either
throw or silently duplicate the meter. See `WorkerObservability.publishQueueDepth`'s own use of
this exact pattern (`Gauge.builder(name, supplier).register(registry)`, called once, from a
constructor).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAggregatorOutageSweepServiceTest {

    private final AccountAggregatorLinkStalenessService staleness =
            new AccountAggregatorLinkStalenessService(24); // 72h threshold

    @Test
    void countsOnlyTheStaleActiveLinksAndPublishesTheGauge() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(96)));
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale, healthy));
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);

        assertThat(sweep.sweep()).isEqualTo(1);
        assertThat(registry.get("finora.account_aggregator.stale_links").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void mutatesNoLinkAndNoAccount() {
        // Read-only, per this plan's Global Constraints -- the hatch is evaluated live by the
        // guard, never by this sweep. No save() call of any kind should ever happen here.
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(96)));
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale));
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);
        sweep.sweep();

        org.mockito.Mockito.verify(links, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void gaugeReadsZeroWhenNothingIsStale() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of());
        MeterRegistry registry = new SimpleMeterRegistry();

        AccountAggregatorOutageSweepService sweep =
                new AccountAggregatorOutageSweepService(links, staleness, registry);

        assertThat(sweep.sweep()).isZero();
        assertThat(registry.get("finora.account_aggregator.stale_links").gauge().value()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorOutageSweepServiceTest`
Expected: FAIL to compile -- the class doesn't exist yet.

- [ ] **Step 3: Implement the sweep**

```java
package com.finora.integrations.setu;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only. Detects and reports Account Aggregator outages -- it does not create the escape
 * hatch, which {@link com.finora.imports.AccountAggregatorGuard} evaluates live on every request
 * regardless of whether this scheduler is even running. This sweep exists so an outage is
 * observable even if no user happens to attempt a manual import during it: on every tick, it
 * counts ACTIVE links currently past the staleness threshold (published as a gauge) and logs one
 * WARN per stale link found.
 *
 * <p>"Incident alerting" (design spec) is realistically scoped to this gauge plus the WARN log
 * line, both of which a future alerting system can consume -- this codebase has no paging/alerting
 * pipeline yet (see the scope doc's own note on this). Mirrors
 * {@code SubscriptionReconciliationSweepService}'s scheduling shape ({@code fixedDelay}, gated by a
 * flag {@code application-test.yml} turns off, tests call {@link #sweep()} directly) -- but unlike
 * that service (and unlike {@link AccountAggregatorLinkSweepService}), mutates nothing.
 */
@Service
public class AccountAggregatorOutageSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorOutageSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountAggregatorLinkStalenessService staleness;
    private final AtomicInteger staleLinkCount = new AtomicInteger(0);

    @Value("${app.integrations.setu.outage-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorOutageSweepService(AccountAggregatorLinkRepository links,
                                                AccountAggregatorLinkStalenessService staleness,
                                                MeterRegistry registry) {
        this.links = links;
        this.staleness = staleness;
        Gauge.builder("finora.account_aggregator.stale_links", staleLinkCount, AtomicInteger::get)
                .description("ACTIVE Account Aggregator links currently past the staleness threshold")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.outage-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int found = sweep();
        if (found > 0) {
            log.info("Account Aggregator outage sweep: {} ACTIVE link(s) currently stale.", found);
        }
    }

    public int sweep() {
        List<AccountAggregatorLink> staleLinks = links.findByStatus(AccountAggregatorLinkStatus.ACTIVE).stream()
                .filter(staleness::isStale)
                .toList();
        staleLinkCount.set(staleLinks.size());
        for (AccountAggregatorLink link : staleLinks) {
            Instant reference = link.getLastSyncedAt() != null ? link.getLastSyncedAt() : link.getUpdatedAt();
            log.warn("AA link {} stale: last synced {} ago.", link.getId(),
                    Duration.between(reference, Instant.now()));
        }
        return staleLinks.size();
    }
}
```

- [ ] **Step 4: Disable the sweep under the test profile**

In `application-test.yml`, add a new block under `app.integrations:` (which does not yet have a
`setu:` sub-block -- confirmed via `grep -n "setu:" application-test.yml`), matching the exact
comment convention every other sweep entry in that file already uses:

```yaml
  integrations:
    setu:
      # Same reasoning as subscription-reconciliation.sweep above (BH-058): a background thread
      # publishing gauge/log state mid-test is exactly that cross-test pollution risk, even though
      # this sweep itself is read-only. Tests that exercise it call
      # AccountAggregatorOutageSweepService.sweep() directly, which does not consult this flag.
      outage-sweep:
        enabled: false
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorOutageSweepServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorOutageSweepService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorOutageSweepServiceTest.java \
        backend/src/main/resources/application-test.yml
git commit -m "feat(backend): add read-only AA outage sweep (gauge + log, no state mutation)"
```

---

### Task 5: Expose `AccountDto.aaSyncStale`

**Files:**
- Modify: `backend/src/main/java/com/finora/accounts/AccountDto.java`
- Modify: `backend/src/main/java/com/finora/accounts/AccountService.java`
- Modify: `backend/src/test/java/com/finora/accounts/AccountServiceTest.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`,
  `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Produces: `AccountDto.aaSyncStale` -- `boolean`. `true` only when the account is
  `ACCOUNT_AGGREGATOR`-sourced AND its `ACTIVE` link is currently stale; `false` for a `MANUAL`
  account, an AA-linked-but-healthy account, or an AA-linked account with no `ACTIVE` link at all
  (e.g. `REVOKED`).

**Accuracy is scoped to `AccountService.listForUser` only -- deliberate, not an oversight.**
`AccountDto.from(...)` is a plain static factory (no repository access, same as `primarySource`'s
own Plan 3 precedent), so the caller must resolve the AA link and pass the boolean in. Four
call sites build an `AccountDto` today (`grep -rn "AccountDto\.from(" backend/src/main/java`):
`AccountService.listForUser` (the one that feeds `GET /accounts`, which Task 6's picker actually
reads from), `AccountService.create`/`update` (an account is always `MANUAL` the instant `create`
runs -- `AccountAggregatorIdentityResolutionService.createAccount` calls this same `create` method
*before* flipping `primarySource` to `ACCOUNT_AGGREGATOR` in its own separate `attach()` step, so
`create`'s `false` is not a simplification there, it's simply correct; `update` COULD touch an
already-AA-linked account, e.g. a rename), and `ImportService.confirmSection`'s response snapshot
(`accountRepository.findById(accountId).map(AccountDto::from)`, via the 1-arg overload). Making all
three of the non-`listForUser` sites resolve the real value would mean giving `ImportService` and
every 1-arg/2-arg `AccountDto.from` caller a new repository + staleness-service dependency for a
field nothing there currently reads for a staleness-sensitive purpose -- real scope creep for this
plan's actual requirement (Task 6's picker). `false` at those three sites, documented on the field
itself so it reads as a decision, not a bug.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void listForUserComputesAaSyncStaleFromTheActiveLink() {
        Account healthy = new Account();
        ReflectionTestUtils.setField(healthy, "id", UUID.randomUUID());
        healthy.setUserId(userId);
        healthy.setAccountType(Account.Type.SAVINGS);
        healthy.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);

        Account stale = new Account();
        ReflectionTestUtils.setField(stale, "id", UUID.randomUUID());
        stale.setUserId(userId);
        stale.setAccountType(Account.Type.SAVINGS);
        stale.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);

        Account manual = new Account();
        ReflectionTestUtils.setField(manual, "id", UUID.randomUUID());
        manual.setUserId(userId);
        manual.setAccountType(Account.Type.SAVINGS);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(healthy, stale, manual));

        com.finora.integrations.setu.AccountAggregatorLink healthyLink =
                new com.finora.integrations.setu.AccountAggregatorLink();
        healthyLink.setAccountId(healthy.getId());
        healthyLink.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(1)));

        com.finora.integrations.setu.AccountAggregatorLink staleLink =
                new com.finora.integrations.setu.AccountAggregatorLink();
        staleLink.setAccountId(stale.getId());
        staleLink.setLastSyncedAt(Instant.now().minus(java.time.Duration.ofHours(96)));

        when(aaLinks.findByAccountIdInAndStatus(any(), eq(com.finora.integrations.setu.AccountAggregatorLinkStatus.ACTIVE)))
                .thenReturn(List.of(healthyLink, staleLink));

        List<AccountDto> dtos = accountService.listForUser(userId);

        assertThat(dtos).extracting(AccountDto::id, AccountDto::aaSyncStale)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(healthy.getId(), false),
                        org.assertj.core.groups.Tuple.tuple(stale.getId(), true),
                        org.assertj.core.groups.Tuple.tuple(manual.getId(), false));
    }
```

Add `com.finora.integrations.setu.AccountAggregatorLinkRepository aaLinks = mock(...)` to the
file's `@BeforeEach setUp()`, and add it (plus the real
`com.finora.integrations.setu.AccountAggregatorLinkStalenessService` -- 24h default, no mocking
needed, same reasoning as Task 3) as new trailing constructor arguments to
`AccountServiceTest`'s single `new AccountService(...)` call site.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountServiceTest`
Expected: FAIL to compile -- `AccountDto.aaSyncStale()` and the new `AccountService` constructor
parameters don't exist yet.

- [ ] **Step 3: Add the field to AccountDto**

Add the new record component after `primarySource`, and thread it through the one real
constructor:

```java
        String primarySource,

        // true only when primarySource is ACCOUNT_AGGREGATOR AND the account's ACTIVE link is
        // currently past the staleness threshold (AccountAggregatorLinkStalenessService, Plan 4)
        // -- the signal Import.tsx's account picker uses to re-enable a stale-linked account
        // instead of leaving it permanently disabled. Meaningless (always false) for a MANUAL
        // account. Computed accurately only by AccountService.listForUser -- see that method's own
        // comment for why the other three AccountDto.from call sites (create/update/
        // ImportService's confirm-response snapshot) pass false rather than resolving it.
        boolean aaSyncStale,
```

```java
    public static AccountDto from(Account a, BankDto bank, Instant lastImportedAt,
                                   LocalDate lastStatementPeriodStart, LocalDate lastStatementPeriodEnd,
                                   int statementsCount, long transactionsCount, boolean aaSyncStale) {
        return new AccountDto(a.getId(), a.getName(), a.getAccountType().name(),
                a.getBalance(), a.getCreditLimit(), a.getDueDate(), a.getInvestmentKind(),
                a.getAccountHolderName(), a.getAccountNumberMasked(),
                a.getBranchName(), a.getIfscCode(),
                bank,
                lastImportedAt, lastStatementPeriodStart, lastStatementPeriodEnd,
                statementsCount, transactionsCount,
                "ACTIVE",
                a.getPrimarySource().name(),
                aaSyncStale,
                a.getPrincipalAmount(), a.getInterestRate(), a.getMaturityDate(), a.getMaturityAmount(),
                a.getInstallmentAmount(), a.getInstallmentsPaid(), a.getInstallmentsTotal());
    }
```

Update the 1-arg and 2-arg overloads to pass `false` at the new trailing position:

```java
    public static AccountDto from(Account a) {
        return from(a, BankDto.from(BankRegistry.get(a.getBankId())), null, null, null, 0, 0L, false);
    }

    public static AccountDto from(Account a, BankDto bank) {
        return from(a, bank, null, null, null, 0, 0L, false);
    }
```

`AccountService.create`/`update` and `ImportService.confirmSection`'s `AccountDto::from` call all
keep using these overloads unchanged -- no other change needed at those three sites.

- [ ] **Step 4: Compute the real value in `listForUser`**

```java
    public AccountService(AccountRepository accountRepository, StatementImportRepository statementImportRepository,
                           TransactionRepository transactionRepository, AuditService auditService,
                           BankManagementService bankManagementService, TransactionGraphService transactionGraphService,
                           EntitlementService entitlementService,
                           com.finora.integrations.setu.AccountAggregatorLinkRepository aaLinks,
                           com.finora.integrations.setu.AccountAggregatorLinkStalenessService aaStaleness) {
        // ...existing assignments...
        this.aaLinks = aaLinks;
        this.aaStaleness = aaStaleness;
    }
```

In `listForUser`, alongside the existing `latestImportByAccount`/`statementsCountByAccount` maps
(same N+1-avoidance discipline that method's own comments already establish):

```java
        Map<UUID, com.finora.integrations.setu.AccountAggregatorLink> activeAaLinkByAccount =
                aaLinks.findByAccountIdInAndStatus(
                                accounts.stream().map(Account::getId).toList(),
                                com.finora.integrations.setu.AccountAggregatorLinkStatus.ACTIVE)
                        .stream()
                        .collect(Collectors.toMap(
                                com.finora.integrations.setu.AccountAggregatorLink::getAccountId,
                                java.util.function.Function.identity()));
```

and in the `.map(a -> ...)` lambda, replace the `AccountDto.from(...)` call's argument list with
one that ends in:

```java
                            java.util.Optional.ofNullable(activeAaLinkByAccount.get(a.getId()))
                                    .map(aaStaleness::isStale)
                                    .orElse(false));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountServiceTest`
Expected: PASS

- [ ] **Step 6: Regenerate the OpenAPI spec and client types**

Same sequence Plan 3's Task 1 used:

```bash
cd backend && ./mvnw -DskipTests package && bash scripts/generate-openapi-spec.sh
cd ../frontend && npm run generate:types
cd ../mobile && npm run generate:types
cd ../admin-portal && npm run generate:types
```

Diff each regenerated file afterward and confirm the change is exactly the new `aaSyncStale`
field -- nothing else. (Needs a reachable Postgres on 5432 with `finora/finora/finora`
credentials; reuse whatever's already running rather than starting a second one.)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/accounts/AccountDto.java \
        backend/src/main/java/com/finora/accounts/AccountService.java \
        backend/src/test/java/com/finora/accounts/AccountServiceTest.java \
        backend/openapi/openapi.json \
        frontend/src/api/generated-types.ts \
        mobile/src/api/generated-types.ts \
        admin-portal/src/api/generated-types.ts
git commit -m "feat(backend): expose AccountDto.aaSyncStale, computed in listForUser"
```

---

### Task 6: Frontend -- re-enable the picker when stale

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/pages/Import.tsx`
- Modify: `frontend/src/pages/Import.test.tsx`
- Modify: `frontend/src/lib/accountMatch.ts`
- Modify: `frontend/src/lib/accountMatch.test.ts`

**Without this task the hatch is unreachable from the UI** -- Plan 3's picker disables any
`ACCOUNT_AGGREGATOR` account's `<option>` unconditionally; without reading `aaSyncStale`, a
backend-allowed stale-linked account stays permanently unselectable in the one surface that offers
it.

- [ ] **Step 1: Add the type**

`frontend/src/types/index.ts`, in the `Account` interface, next to `primarySource`:

```typescript
  primarySource: 'MANUAL' | 'ACCOUNT_AGGREGATOR';
  aaSyncStale: boolean;
```

- [ ] **Step 2: Write the failing frontend tests**

In `frontend/src/pages/Import.test.tsx`, extend the existing `existingAccount()` test factory
(from Plan 3's own picker test) with `aaSyncStale: false` as its default, and add a new test in the
same "Import — resuming via navigation state" describe block Plan 3's own picker test lives in:

```typescript
  it('re-enables a stale AA-linked account in the existing-account dropdown, with a different label', async () => {
    const accounts = [
      existingAccount({ id: 'acc-1', name: 'Axis Bank', primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: false }),
      existingAccount({ id: 'acc-2', name: 'HDFC Bank', primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: true }),
    ];
    renderImportWithResumeState({ existingAccounts: accounts });
    const user = userEvent.setup();
    await user.click(screen.getByRole('radio', { name: /use an existing account/i }));

    const healthyOption = screen.getByRole('option', { name: /Axis Bank.*Bank Sync active/i });
    expect(healthyOption).toBeDisabled();

    const staleOption = screen.getByRole('option', { name: /HDFC Bank.*Bank Sync delayed/i });
    expect(staleOption).toBeEnabled();
  });
```

In `frontend/src/lib/accountMatch.test.ts`, add:

```typescript
  it('matches an AA-linked account when its sync is currently stale', () => {
    const staleAaLinked = account({
      id: 'acc-aa', accountNumberMasked: 'XXXXXX4587',
      primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: true,
    });
    const statement = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(statement, [staleAaLinked])?.id).toBe('acc-aa');
  });
```

and update the `account()` factory's default to include `aaSyncStale: false`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx src/lib/accountMatch.test.ts`
Expected: FAIL -- `aaSyncStale` doesn't exist on the test fixtures/component logic yet.

- [ ] **Step 4: Update the picker**

`Import.tsx`'s `AccountChoiceFields` `<option>` rendering:

```tsx
            {existingAccounts.map((a) => (
              <option
                key={a.id}
                value={a.id}
                disabled={a.primarySource === 'ACCOUNT_AGGREGATOR' && !a.aaSyncStale}
              >
                {a.name} ({a.accountType.replace('_', ' ')})
                {a.primarySource === 'ACCOUNT_AGGREGATOR'
                  ? a.aaSyncStale
                    ? ' — Bank Sync delayed (manual import available)'
                    : ' — Bank Sync active'
                  : ''}
              </option>
            ))}
```

- [ ] **Step 5: Update `matchExistingAccount`'s eligibility filter**

`accountMatch.ts`:

```typescript
  const eligibleAccounts = accounts.filter(
    (a) => a.primarySource !== 'ACCOUNT_AGGREGATOR' || a.aaSyncStale,
  );
```

(update the comment above it to note the staleness exception, mirroring the existing comment's own
style)

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/pages/Import.test.tsx src/lib/accountMatch.test.ts`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/index.ts \
        frontend/src/pages/Import.tsx \
        frontend/src/pages/Import.test.tsx \
        frontend/src/lib/accountMatch.ts \
        frontend/src/lib/accountMatch.test.ts
git commit -m "feat(frontend): re-enable the account picker for a stale AA-linked account"
```

---

## After Task 6: run the full suite

```bash
cd backend && ./mvnw test
cd ../frontend && npm run build && npx vitest run
```

Both must be green, no regressions, before this plan is considered done. Per this repo's own
mandatory post-implementation verification (`CLAUDE.md`), do not stop at "the tests I wrote pass" --
reread the scope doc's settled decisions and confirm each one actually has real, verified behavior
behind it, not just the parts that were easiest to build.
