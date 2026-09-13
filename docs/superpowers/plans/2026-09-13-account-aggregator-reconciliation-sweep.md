# Account Aggregator Reconciliation Sweep Implementation Plan (Plan 6, Track A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close a confirmed, code-verified production gap: today the `data.ready` webhook is the
*only* thing that ever triggers an ongoing Account Aggregator sync. If it's ever lost for a link,
that link never syncs again, with nothing anywhere force-fetching in its place — a materially worse
failure mode than the mutation-handling gap the rest of Plan 6 addresses, and independent of it.
This is Track A of Plan 6's own scope doc, approved for implementation planning on its own; Track B
(bank-side mutation handling — changed/missing detection) stays parked pending a product decision
and Setu sandbox validation, per that doc's own converged conclusion.

**Architecture:** One new scheduled service, `AccountAggregatorReconciliationSweepService` —
exactly the class the design spec's own "Architecture" section already named back in Plan 2 but was
never built. Reuses `AccountAggregatorLinkStalenessService` (Plan 4) for "is this link overdue" —
the same 3×-expected-cadence threshold the outage escape hatch and outage-sweep gauge already use,
not a new number. A small refactor extracts the `data.ready` webhook's own range-computation logic
(currently inline in `AccountAggregatorWebhookDispatcher`) into a shared, testable method on
`SetuDataFetchService`, so the webhook path and the new sweep can never compute "how much to
re-fetch" differently.

**Tech Stack:** Spring Boot, JPA/Hibernate, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ — same
as every prior AA plan.

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)
— this plan implements the `AccountAggregatorReconciliationSweepService` half of "Architecture"
(the stale-`CONSENT_PENDING`-reaping half is already built, `AccountAggregatorLinkSweepService`,
Plan 1). See also
[2026-09-13-account-aggregator-bank-side-mutation-handling-scope.md](2026-09-13-account-aggregator-bank-side-mutation-handling-scope.md)
for the full Track A / Track B split this plan was scoped against, and why Track A moves now while
Track B doesn't.

## Why a new service, not an extension of `AccountAggregatorOutageSweepService`

Both read the same set: `findByStatus(ACTIVE)` filtered by
`AccountAggregatorLinkStalenessService.isStale`. Merging looked tempting (Plan 5's own "one sweep,
not three, since each check is the same shape" precedent applies structurally here too) — but
`AccountAggregatorOutageSweepService`'s own doc comment states, deliberately, "unlike that service
[`SubscriptionReconciliationSweepService`]... mutates nothing." That was a real design choice in
Plan 4: a read-only observability sweep is safer to reason about, and can be toggled off
independently of whether force-fetching should also be paused (e.g. during an incident where more
Setu calls are the last thing wanted, observability should stay on regardless). Repurposing an
already-shipped, already-tested read-only class to gain a mutating responsibility is a bigger,
riskier change than adding a new one with its own `enabled` flag — the established pattern every
sweep in this package already follows. Kept separate.

## Global Constraints

- No changes to Track B's territory: no update-in-place logic, no "changed"/"missing" diff, no new
  `Transaction` fields or `ReconciliationStatus` values. This plan force-fetches using the exact
  same point-fetch-since-last-attempt semantics `data.ready` already uses today — a sliding window
  is Track B's concern, not this plan's.
- Entitlement re-check and gateway-configured check are already inside `SetuDataFetchService.sync`
  — this plan's own new code does not duplicate either check.
- `AccountAggregatorLinkStalenessService` (Plan 4) is reused as-is, no changes to its threshold or
  constructor.

- [ ] **Step 0: Confirm the migration/config landscape hasn't shifted underneath this plan**

Run: `git fetch origin && git log --oneline origin/main -5` and confirm no new AA-package changes
landed since this scope doc's own research pass. Re-read `AccountAggregatorLinkRepository.java`,
`AccountAggregatorLinkStalenessService.java`, and `AccountAggregatorWebhookDispatcher.java` fresh
before starting Task 1 — do not trust this plan's own quoted code snippets over the real files.

---

### Task 1: Extract the shared "since last attempt" sync range into `SetuDataFetchService`

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceSyncSinceLastAttemptTest.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`

**Interfaces:**
- Produces: `SetuDataFetchService.syncSinceLastAttempt(AccountAggregatorLink link): boolean` —
  computes the `[from, to]` range from `lastSyncedAt` (or the 3-month backfill range if never
  synced), calls `sync(link, from, to)` if the range is non-empty, returns whether a fetch was
  actually attempted. Named "last *attempt*," not "last sync," because `sync()` itself sets
  `lastSyncedAt` on both success and failure (see that method's own doc comment) — this range is
  genuinely "since the last time we tried," not "since the last time it worked."

**Why first, before the sweep exists:** the sweep needs this exact range logic (force-fetching with
whatever the webhook path would have used), and duplicating it inline a second time is exactly the
"written in two places, they drift" failure this codebase's own `RateLimiter` javadoc already warns
about for a structurally identical case.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SetuDataFetchService.sync(link, from, to) itself is covered by SetuDataFetchServiceTest --
 *  this covers only the range computation syncSinceLastAttempt wraps around it, extracted from
 *  AccountAggregatorWebhookDispatcher's own data.ready case so the webhook path and the new
 *  reconciliation sweep can never compute it differently. */
class SetuDataFetchServiceSyncSinceLastAttemptTest {

    private AccountAggregatorLinkRepository links;
    private SetuDataFetchService service;

    @BeforeEach
    void setUp() {
        // Only the seam this test exercises is real; everything sync() itself needs is mocked the
        // same way SetuDataFetchServiceTest already does, so this class stays focused on the range
        // math, not re-proving sync()'s own persistence behavior.
        links = mock(AccountAggregatorLinkRepository.class);
        service = new SetuDataFetchService(mock(SetuDataFetchGateway.class),
                mock(AccountAggregatorTransactionMapper.class), mock(com.finora.repository.TransactionRepository.class),
                links, mock(com.finora.service.EntitlementService.class), mock(com.finora.service.AuditService.class),
                mock(com.finora.service.ReconciliationService.class));
    }

    @Test
    void usesTheThreeMonthWindowWhenNeverSynced() {
        AccountAggregatorLink link = spy(new AccountAggregatorLink());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // lastSyncedAt left null.

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.now().minusMonths(3)), eq(LocalDate.now()));
    }

    @Test
    void usesTheDayAfterLastSyncedAt() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.of(2026, 9, 2)), any());
    }

    @Test
    void skipsAndReturnsFalseWhenAlreadySyncedThroughToday() {
        // Regression case Plan 2's own review already found once (data.ready arriving same-day as
        // the initial backfill) -- from would invert past to. Preserved here, not just moved.
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.now().minusSeconds(60));

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        assertThat(attempted).isFalse();
        verify(spied, never()).sync(any(), any(), any());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceSyncSinceLastAttemptTest`
Expected: FAIL to compile — `syncSinceLastAttempt` doesn't exist yet.

- [ ] **Step 3: Add the method**

```java
    /** Computes the "since last attempt" range -- the day after lastSyncedAt, or the 3-month
     *  backfill range if never synced -- and calls sync() if the range is non-empty. "Attempt," not
     *  "sync," because sync() itself sets lastSyncedAt on both success and failure (see that
     *  method's own doc comment): this is genuinely "since we last tried," not "since it last
     *  worked." Shared by AccountAggregatorWebhookDispatcher's data.ready case and
     *  AccountAggregatorReconciliationSweepService's force-fetch path, so the two can never compute
     *  this range differently.
     *
     *  @return whether a fetch was actually attempted -- false means the range was empty (already
     *          synced through today), the caller's own signal for whether to log a skip. */
    public boolean syncSinceLastAttempt(AccountAggregatorLink link) {
        LocalDate to = LocalDate.now();
        LocalDate from = link.getLastSyncedAt() != null
                ? link.getLastSyncedAt().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                : to.minusMonths(3);
        if (from.isAfter(to)) {
            return false;
        }
        sync(link, from, to);
        return true;
    }
```

Add `import java.time.ZoneOffset;` alongside the existing `java.time.Instant`/`java.time.LocalDate`
imports.

- [ ] **Step 4: Refactor the webhook dispatcher to delegate**

```java
            case "data.ready" -> {
                if (link.getStatus() != AccountAggregatorLinkStatus.ACTIVE) {
                    log.info("Ignoring data.ready for link {} not yet ACTIVE (status {}).",
                            link.getId(), link.getStatus());
                } else if (!fetchService.syncSinceLastAttempt(link)) {
                    log.info("Skipping data.ready for link {}: already synced through today.", link.getId());
                }
            }
```

removing the now-duplicated inline range computation. Update the 4 existing `data.ready` tests in
`AccountAggregatorWebhookDispatcherTest` to verify delegation (`verify(fetchService).syncSinceLastAttempt(link)`
/ `verifyNoInteractions(fetchService)`) instead of asserting on `sync(...)`'s own arguments directly
— the range-computation assertions now live in `SetuDataFetchServiceSyncSinceLastAttemptTest`
(Step 1), not duplicated here.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceSyncSinceLastAttemptTest,AccountAggregatorWebhookDispatcherTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java \
        backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceSyncSinceLastAttemptTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "refactor(backend): extract syncSinceLastAttempt, shared by webhook and sweep"
```

---

### Task 2: `AccountAggregatorReconciliationSweepService`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepService.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepServiceTest.java`

**Interfaces:**
- Produces: `int sweep()` — force-fetches every stale `ACTIVE` link, returns the count actually
  force-fetched (mirroring every other sweep's own test-facing shape in this package).

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorReconciliationSweepServiceTest {

    @Test
    void forceFetchesAStaleActiveLinkAndAuditsIt() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        UUID userId = UUID.randomUUID();
        AccountAggregatorLink stale = new AccountAggregatorLink();
        stale.setUserId(userId);
        stale.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(stale));
        when(staleness.isStale(stale)).thenReturn(true);
        when(fetchService.syncSinceLastAttempt(stale)).thenReturn(true);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isEqualTo(1);
        verify(fetchService).syncSinceLastAttempt(stale);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void leavesAHealthyActiveLinkAlone() {
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink healthy = new AccountAggregatorLink();
        healthy.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(healthy));
        when(staleness.isStale(healthy)).thenReturn(false);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isZero();
        verifyNoInteractions(fetchService);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotCountOrAuditWhenTheForceFetchFindsNothingToDo() {
        // A link the staleness threshold flags as overdue but whose computed range is somehow
        // already empty (e.g. a sync just landed in the gap between the staleness read and this
        // tick) -- syncSinceLastAttempt's own false return is the honest signal, not "found stale
        // therefore counted."
        AccountAggregatorLinkRepository links = mock(AccountAggregatorLinkRepository.class);
        AccountAggregatorLinkStalenessService staleness = mock(AccountAggregatorLinkStalenessService.class);
        SetuDataFetchService fetchService = mock(SetuDataFetchService.class);
        AuditService auditService = mock(AuditService.class);
        AccountAggregatorLink edgeCase = new AccountAggregatorLink();
        edgeCase.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        when(links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)).thenReturn(List.of(edgeCase));
        when(staleness.isStale(edgeCase)).thenReturn(true);
        when(fetchService.syncSinceLastAttempt(edgeCase)).thenReturn(false);

        AccountAggregatorReconciliationSweepService sweep = new AccountAggregatorReconciliationSweepService(
                links, staleness, fetchService, auditService);

        assertThat(sweep.sweep()).isZero();
        verifyNoInteractions(auditService);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorReconciliationSweepServiceTest`
Expected: FAIL to compile — the class doesn't exist yet.

- [ ] **Step 3: Implement the sweep**

```java
package com.finora.integrations.setu;

import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The force-fetch safety net the design spec's own "Architecture" section named back in Plan 2
 * but was never built (confirmed via grep -rn "@Scheduled" across the whole integrations/setu/
 * package during Plan 6's scoping -- three sweeps exist, none of them fetch data). Today the
 * data.ready webhook is the ONLY thing that triggers an ongoing sync; if it's lost for a link, that
 * link never syncs again. This closes that gap directly, reusing AccountAggregatorLinkStalenessService's
 * existing 3x-expected-cadence threshold (the same one the outage escape hatch and
 * AccountAggregatorOutageSweepService's gauge already use) rather than inventing a second number.
 *
 * <p>Deliberately a separate service from AccountAggregatorOutageSweepService, not a merge into it,
 * even though both read the same findByStatus(ACTIVE)-filtered-by-staleness set -- see this plan's
 * own "Why a new service" section. That class mutates nothing by design; this one does, and needs
 * its own independent enabled flag so force-fetching can be paused without losing observability, or
 * vice versa.
 *
 * <p>Mirrors every other sweep's scheduling shape in this package: fixedDelay, gated by a flag
 * application-test.yml turns off, tests call sweep() directly.
 */
@Service
public class AccountAggregatorReconciliationSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorReconciliationSweepService.class);

    private final AccountAggregatorLinkRepository links;
    private final AccountAggregatorLinkStalenessService staleness;
    private final SetuDataFetchService fetchService;
    private final AuditService auditService;

    @Value("${app.integrations.setu.reconciliation-sweep.enabled:true}")
    private boolean sweepEnabled;

    public AccountAggregatorReconciliationSweepService(AccountAggregatorLinkRepository links,
                                                          AccountAggregatorLinkStalenessService staleness,
                                                          SetuDataFetchService fetchService,
                                                          AuditService auditService) {
        this.links = links;
        this.staleness = staleness;
        this.fetchService = fetchService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.integrations.setu.reconciliation-sweep.interval-ms:3600000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int forced = sweep();
        if (forced > 0) {
            log.info("Account Aggregator reconciliation sweep: force-fetched {} stale link(s).", forced);
        }
    }

    public int sweep() {
        int forced = 0;
        for (AccountAggregatorLink link : links.findByStatus(AccountAggregatorLinkStatus.ACTIVE)) {
            if (!staleness.isStale(link)) continue;
            if (fetchService.syncSinceLastAttempt(link)) {
                auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_FORCE_FETCH_TRIGGERED",
                        "AccountAggregatorLink", link.getId());
                forced++;
            }
        }
        return forced;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorReconciliationSweepServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepServiceTest.java
git commit -m "feat(backend): add AccountAggregatorReconciliationSweepService force-fetch safety net"
```

---

### Task 3: Disable under the test profile

**Files:**
- Modify: `backend/src/main/resources/application-test.yml`

- [ ] **Step 1: Add the flag**

Alongside the existing `integrations.setu` block's `outage-sweep`/`lifecycle-sweep` entries:

```yaml
    setu:
      outage-sweep:
        enabled: false
      lifecycle-sweep:
        enabled: false
      reconciliation-sweep:
        enabled: false
```

Same reasoning as the sibling flags immediately above it: a scheduled tick during an integration
test would force-fetch (a real, billable Setu call once a real gateway exists) whatever AA link rows
that test just inserted. Tests call `AccountAggregatorReconciliationSweepService.sweep()` directly,
which ignores this flag.

- [ ] **Step 2: Verify no YAML key collision**

Run: `cd backend && ./mvnw test -Dtest=RateLimitFilterTest` (or any test that boots the Spring
context) and confirm it still boots — this exact file had two independently-added `setu:` keys
silently collide once already, during Plan 5's own merge with Plan 4 (fixed by hand at the time).
Read the file after editing, not just trust the diff, to confirm there is exactly one `setu:` key
under `integrations:` with all four sweep flags nested under it.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/application-test.yml
git commit -m "test(backend): disable the reconciliation sweep under the test profile"
```

---

### Task 4: Real-Postgres IT proving a force-fetch actually persists data

**Files:**
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepServiceIT.java`

**Why this task exists, not just the unit tests above:** this session's own established discipline
for every AA plan so far (Plan 4's audit-rollback finding, Plan 5's sweep IT) has required a real
Postgres proof for any sweep that mutates data on a schedule, not just a mocked unit test. This
sweep's whole point is "make a real Setu call and persist real transactions when nothing else
would" — the highest-value thing to prove for real is that it actually does that, through the real
`SetuDataFetchService` → `AccountAggregatorTransactionMapper` → `TransactionRepository` chain, not a
mock standing in for all three.

- [ ] **Step 1: Write the IT**

```java
package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Proves the force-fetch safety net through the real fetch/map/persist chain, not a mock standing
 * in for it -- same discipline as every other AA plan's own real-Postgres proof for a sweep that
 * mutates data on a schedule (Plan 4's AccountAggregatorGuardIT, Plan 5's own lifecycle-sweep IT).
 * SetuDataFetchGateway is the one seam still mocked -- there is no real Setu sandbox access in this
 * environment, same ceiling every AA plan has had since Plan 1.
 */
class AccountAggregatorReconciliationSweepServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository links;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountAggregatorReconciliationSweepService sweepService;
    @MockitoBean private SetuDataFetchGateway gateway;
    @MockitoBean private EntitlementService entitlementService;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-reconciliation-sweep-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Reconciliation Sweep IT Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Linked Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();

        when(entitlementService.hasEntitlement(eq(userId), eq(FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)))
                .thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
    }

    @Test
    void forceFetchesAStaleLinkAndPersistsARealTransaction() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        // Stale by AccountAggregatorLinkStalenessService's default 3x24h=72h threshold -- see that
        // class's own doc comment for the multiplier.
        link.setLastSyncedAt(Instant.now().minus(100, ChronoUnit.HOURS));
        link.setLinkIdempotencyKey("aa-reconciliation-sweep-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        when(gateway.fetchTransactions(eq(link.getConsentHandleId()), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new SetuFiDataFetchResult(null, null, List.of(
                        new SetuFiDataTransaction("txn-1", "DEBIT", new BigDecimal("450.00"),
                                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                                "Force-fetched transaction", new BigDecimal("1000.00"), null))));

        int forced = sweepService.sweep();

        assertThat(forced).isEqualTo(1);
        List<Transaction> persisted = transactionRepository.findByUserIdAndAccountIdIn(userId, List.of(accountId));
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getAmount()).isEqualByComparingTo("450.00");
        assertThat(persisted.get(0).getSource()).isEqualTo(Transaction.Source.ACCOUNT_AGGREGATOR);

        AccountAggregatorLink reloaded = links.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
    }

    @Test
    void leavesARecentlySyncedLinkUntouched() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.now().minusSeconds(60));
        link.setLinkIdempotencyKey("aa-reconciliation-sweep-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        links.save(link);

        int forced = sweepService.sweep();

        assertThat(forced).isZero();
        assertThat(transactionRepository.findByUserIdAndAccountIdIn(userId, List.of(accountId))).isEmpty();
    }
}
```

Verified against the real config, not assumed: neither `application.yml` nor `application-test.yml`
overrides `app.integrations.setu.expected-cadence-hours`, so `AccountAggregatorLinkStalenessService`'s
own `@Value(...:24)` default applies under test too — a 72h (3×24h) staleness threshold. The
fixture's `minus(100, ChronoUnit.HOURS)` is comfortably past that. If a future change adds an
override, re-verify this fixture against it rather than assuming the number above still holds.

- [ ] **Step 2: Run the IT**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorReconciliationSweepServiceIT`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/finora/integrations/setu/AccountAggregatorReconciliationSweepServiceIT.java
git commit -m "test(backend): prove the reconciliation sweep against real Postgres"
```

---

## After Task 4: run the full suite

```bash
cd backend && ./mvnw test
```

Expected: PASS, zero failures, zero errors — same bar every prior AA plan held. Per this project's
standing "mandatory post-implementation verification" rule, do not stop at green tests:

- Confirm the 4 existing `data.ready` webhook tests still pass after Task 1's refactor and now
  assert on delegation, not on `sync(...)`'s own arguments (the range-computation coverage moved to
  `SetuDataFetchServiceSyncSinceLastAttemptTest`, not lost).
- Confirm `AccountAggregatorOutageSweepService` and its own test suite are completely unaffected —
  this plan reads the same underlying data but is a separate class with no shared state.
- Reread this plan's own "Why a new service" section and confirm the two sweeps really do have
  independent `enabled` flags in the final diff, not one flag accidentally gating both.
- Confirm no `Track B` territory was accidentally touched: `grep -rn "transactionFingerprint\b"` in
  the diff should show zero changes to fingerprint computation, and `git diff --stat` should show no
  changes to `Transaction.java`'s own fields or `ReconciliationStatus` enum.
