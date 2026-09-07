# Financial Health Score Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Dashboard's progress-bar Financial Health Score card with a semi-circular
gauge, individual factor cards, an AI Insight card, and a 6-month trend sparkline — backed by a new
persisted monthly snapshot so the trend/delta/insight are real, not fabricated.

**Architecture:** Backend gains a `health_score_snapshot` table written via an idempotent
`INSERT ... ON CONFLICT` upsert (mirroring the existing `NetWorthSnapshot` pattern exactly): an
on-demand call from `DashboardService.summarize()` keeps the current month fresh on every dashboard
load, and a new flag-gated nightly sweep (mirroring `NetWorthSnapshotSweepService`) fills gaps for
users who don't open the dashboard that month. Four new fields ride on the existing
`DashboardSummaryDto`. Frontend replaces the progress-bar section of the Financial Health Score
card in `Dashboard.tsx` with four new presentational pieces, reusing existing design-system
primitives (`FinoraCard`, `Badge`) rather than inventing new ones.

**Tech Stack:** Spring Boot / JPA / PostgreSQL (Flyway migrations) on the backend; React / TypeScript
/ Tailwind on the frontend. No new dependencies on either side.

**Spec:** [docs/superpowers/specs/2026-09-07-health-score-redesign-design.md](../specs/2026-09-07-health-score-redesign-design.md)

## Global Constraints

- No AI/ML-generated copy anywhere — every string (improvement suggestions, insight framing) is a
  deterministic template, not free text.
- The insight formula's weights must be the SAME values `computeHealthScore()`'s overall-score
  formula uses — one shared constant, never two copies that could drift.
- The AI Insight card (and the matching factor-card badge) is hidden whenever the best potential
  gain rounds to under 3 points.
- The snapshot upsert must never run inside `DashboardService.summarize()`'s own
  `@Transactional(readOnly = true)` transaction — it silently no-ops there. It runs via
  `@Transactional(propagation = Propagation.REQUIRES_NEW)` on the repository method itself.
- Snapshot rows for past months are never rewritten, only the current month's row is upserted.
- No prefill plumbing for "Create Goal" — it's a plain link to `/app/goals`.
- Commit messages carry no AI-attribution trailer (repository-wide rule, `CLAUDE.md`).

---

## Task 1: `health_score_snapshot` table + entity + repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V164__health_score_snapshot.sql` (confirm `164`
  is still free against `origin/main` before writing this file — see step 1)
- Create: `backend/src/main/java/com/finora/entity/HealthScoreSnapshot.java`
- Create: `backend/src/main/java/com/finora/repository/HealthScoreSnapshotRepository.java`
- Test: `backend/src/test/java/com/finora/repository/HealthScoreSnapshotRepositoryIT.java`

**Interfaces:**
- Produces: `HealthScoreSnapshot` entity (`id`, `userId`, `yearMonth` (`"2026-09"` format),
  `overallScore`, `label`, `savingsRateScore`, `debtScore`, `emergencyFundScore`,
  `spendConsistencyScore`, `cashFlowStabilityScore`, `computedAt`).
- Produces: `HealthScoreSnapshotRepository.upsertForMonth(userId, yearMonth, overallScore, label,
  savingsRateScore, debtScore, emergencyFundScore, spendConsistencyScore, cashFlowStabilityScore)`
  — void, idempotent, its own `REQUIRES_NEW` transaction.
- Produces: `HealthScoreSnapshotRepository.findTop6ByUserIdOrderByYearMonthDesc(userId)` — newest
  first (callers reverse for oldest-first display).
- Produces: `HealthScoreSnapshotRepository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(userId, yearMonth)`
  — most recent snapshot strictly before `yearMonth`, or empty.

- [ ] **Step 1: Confirm the next free Flyway version**

```bash
git fetch origin
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | grep -E '^[0-9]+$' | sort -n | tail -3
```

If `164` is already taken on `origin/main`, use the next free number instead and adjust every
reference to `V164`/`164` in this task accordingly.

- [ ] **Step 2: Write the migration**

```sql
-- V164__health_score_snapshot.sql
CREATE TABLE health_score_snapshot (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year_month                VARCHAR(7) NOT NULL,
    overall_score             INT NOT NULL,
    label                     VARCHAR(32) NOT NULL,
    savings_rate_score        DOUBLE PRECISION NOT NULL,
    debt_score                DOUBLE PRECISION NOT NULL,
    emergency_fund_score      DOUBLE PRECISION NOT NULL,
    spend_consistency_score   DOUBLE PRECISION NOT NULL,
    cash_flow_stability_score DOUBLE PRECISION NOT NULL,
    computed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, year_month)
);
```

(No separate `CREATE INDEX` — Postgres already creates one for the `UNIQUE` constraint, matching
`net_worth_snapshots`' own `UNIQUE(user_id, snapshot_date)` in `V1__init_schema.sql`, which has no
extra index either.)

- [ ] **Step 3: Write the entity**

```java
// backend/src/main/java/com/finora/entity/HealthScoreSnapshot.java
package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "health_score_snapshot")
public class HealthScoreSnapshot {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "year_month", nullable = false)
    private String yearMonth;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Column(nullable = false)
    private String label;

    @Column(name = "savings_rate_score", nullable = false)
    private double savingsRateScore;

    @Column(name = "debt_score", nullable = false)
    private double debtScore;

    @Column(name = "emergency_fund_score", nullable = false)
    private double emergencyFundScore;

    @Column(name = "spend_consistency_score", nullable = false)
    private double spendConsistencyScore;

    @Column(name = "cash_flow_stability_score", nullable = false)
    private double cashFlowStabilityScore;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getYearMonth() { return yearMonth; }
    public void setYearMonth(String yearMonth) { this.yearMonth = yearMonth; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int overallScore) { this.overallScore = overallScore; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public double getSavingsRateScore() { return savingsRateScore; }
    public void setSavingsRateScore(double savingsRateScore) { this.savingsRateScore = savingsRateScore; }
    public double getDebtScore() { return debtScore; }
    public void setDebtScore(double debtScore) { this.debtScore = debtScore; }
    public double getEmergencyFundScore() { return emergencyFundScore; }
    public void setEmergencyFundScore(double emergencyFundScore) { this.emergencyFundScore = emergencyFundScore; }
    public double getSpendConsistencyScore() { return spendConsistencyScore; }
    public void setSpendConsistencyScore(double spendConsistencyScore) { this.spendConsistencyScore = spendConsistencyScore; }
    public double getCashFlowStabilityScore() { return cashFlowStabilityScore; }
    public void setCashFlowStabilityScore(double cashFlowStabilityScore) { this.cashFlowStabilityScore = cashFlowStabilityScore; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
```

- [ ] **Step 4: Write the repository**

```java
// backend/src/main/java/com/finora/repository/HealthScoreSnapshotRepository.java
package com.finora.repository;

import com.finora.entity.HealthScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthScoreSnapshotRepository extends JpaRepository<HealthScoreSnapshot, UUID> {

    List<HealthScoreSnapshot> findTop6ByUserIdOrderByYearMonthDesc(UUID userId);

    Optional<HealthScoreSnapshot> findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(
            UUID userId, String yearMonth);

    /**
     * Writes this month's snapshot -- inserting it, or overwriting the figures on the row already
     * there for {@code (user_id, year_month)} -- as one atomic statement. Same shape as
     * {@code NetWorthSnapshotRepository#upsertForToday} and for the same reason: the database
     * resolves the conflict atomically, so two concurrent callers (a dashboard load racing the
     * nightly sweep, or a double-click) can never both attempt the INSERT and never raise an
     * exception either way.
     *
     * <p>{@code REQUIRES_NEW} for two reasons at once: a {@code @Modifying} query needs some active
     * transaction to run in, and -- critically -- {@code DashboardService.summarize()} (the main
     * caller) is {@code @Transactional(readOnly = true)}, under which a nested write silently
     * no-ops (read-only sets Hibernate's flush mode to MANUAL). {@code REQUIRES_NEW} keeps this
     * write in its own, genuinely writable transaction regardless of the caller's own transactional
     * state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO health_score_snapshot
               (id, user_id, year_month, overall_score, label, savings_rate_score, debt_score,
                emergency_fund_score, spend_consistency_score, cash_flow_stability_score, computed_at)
           VALUES
               (gen_random_uuid(), :userId, :yearMonth, :overallScore, :label, :savingsRateScore,
                :debtScore, :emergencyFundScore, :spendConsistencyScore, :cashFlowStabilityScore, now())
           ON CONFLICT (user_id, year_month) DO UPDATE SET
               overall_score             = EXCLUDED.overall_score,
               label                     = EXCLUDED.label,
               savings_rate_score        = EXCLUDED.savings_rate_score,
               debt_score                = EXCLUDED.debt_score,
               emergency_fund_score      = EXCLUDED.emergency_fund_score,
               spend_consistency_score   = EXCLUDED.spend_consistency_score,
               cash_flow_stability_score = EXCLUDED.cash_flow_stability_score,
               computed_at               = now()
           """, nativeQuery = true)
    void upsertForMonth(@Param("userId") UUID userId, @Param("yearMonth") String yearMonth,
                         @Param("overallScore") int overallScore, @Param("label") String label,
                         @Param("savingsRateScore") double savingsRateScore,
                         @Param("debtScore") double debtScore,
                         @Param("emergencyFundScore") double emergencyFundScore,
                         @Param("spendConsistencyScore") double spendConsistencyScore,
                         @Param("cashFlowStabilityScore") double cashFlowStabilityScore);
}
```

- [ ] **Step 5: Write the integration test**

```java
// backend/src/test/java/com/finora/repository/HealthScoreSnapshotRepositoryIT.java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Extends AbstractIntegrationTest (real Postgres via Testcontainers, singleton container/context
// shared across every *IT class) rather than rolling @SpringBootTest/@ActiveProfiles/@Transactional
// by hand -- that's the actual, verified convention every other repository IT in this codebase
// follows (see e.g. HeldStatementRepositoryIT), not a guess. No @Transactional: this base class's
// subclasses don't rely on rollback for cleanup -- each test uses a fresh random-UUID user, so
// there's nothing for a shared row to collide with.
class HealthScoreSnapshotRepositoryIT extends AbstractIntegrationTest {

    @Autowired private HealthScoreSnapshotRepository repository;
    @Autowired private UserRepository userRepository;

    private UUID persistUser() {
        User u = new User();
        u.setEmail("health-score-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Health Score Test");
        return userRepository.save(u).getId();
    }

    @Test
    void upsertInsertsThenUpdatesTheSameMonthRow() {
        UUID userId = persistUser();

        repository.upsertForMonth(userId, "2026-09", 51, "Fair", 0, 100, 2, 100, 100);
        List<HealthScoreSnapshot> afterFirst = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getOverallScore()).isEqualTo(51);

        repository.upsertForMonth(userId, "2026-09", 60, "Good", 20, 100, 10, 100, 100);
        List<HealthScoreSnapshot> afterSecond = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(afterSecond).hasSize(1); // still one row -- updated, not duplicated
        assertThat(afterSecond.get(0).getOverallScore()).isEqualTo(60);
        assertThat(afterSecond.get(0).getLabel()).isEqualTo("Good");
    }

    @Test
    void pastMonthsAreUntouchedByALaterMonthsUpsert() {
        UUID userId = persistUser();

        repository.upsertForMonth(userId, "2026-07", 40, "Fair", 0, 80, 0, 80, 80);
        repository.upsertForMonth(userId, "2026-08", 45, "Fair", 5, 80, 5, 80, 80);
        repository.upsertForMonth(userId, "2026-09", 51, "Fair", 0, 100, 2, 100, 100);

        List<HealthScoreSnapshot> all = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(all).extracting(HealthScoreSnapshot::getYearMonth)
                .containsExactly("2026-09", "2026-08", "2026-07"); // newest first
        assertThat(all).extracting(HealthScoreSnapshot::getOverallScore)
                .containsExactly(51, 45, 40); // each month's own value, untouched by later upserts
    }

    @Test
    void findsMostRecentPriorSnapshotStrictlyBeforeGivenMonth() {
        UUID userId = persistUser();
        repository.upsertForMonth(userId, "2026-06", 30, "Needs Attention", 0, 60, 0, 60, 60);
        repository.upsertForMonth(userId, "2026-08", 45, "Fair", 5, 80, 5, 80, 80);

        Optional<HealthScoreSnapshot> prior =
                repository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(userId, "2026-09");

        assertThat(prior).isPresent();
        assertThat(prior.get().getYearMonth()).isEqualTo("2026-08"); // most recent before 2026-09, gap at 07 is fine
    }

    @Test
    void findsNoPriorSnapshotWhenNoneExists() {
        UUID userId = persistUser();
        Optional<HealthScoreSnapshot> prior =
                repository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(userId, "2026-09");
        assertThat(prior).isEmpty();
    }
}
```

- [ ] **Step 6: Run the test**

```bash
cd backend && ./mvnw -q -Dtest=NoSuchTestEverMatches -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HealthScoreSnapshotRepositoryIT verify
```

Expected: all 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V164__health_score_snapshot.sql \
        backend/src/main/java/com/finora/entity/HealthScoreSnapshot.java \
        backend/src/main/java/com/finora/repository/HealthScoreSnapshotRepository.java \
        backend/src/test/java/com/finora/repository/HealthScoreSnapshotRepositoryIT.java
git commit -m "feat(backend): add health_score_snapshot table, entity, and upsert repository"
```

---

## Task 2: Extract `HEALTH_SCORE_WEIGHTS` as a shared constant

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java` (the `overall` score
  computation inside `computeHealthScore`, currently hardcoded literals)
- Test: `backend/src/test/java/com/finora/service/DashboardServiceTest.java` (add a regression
  assertion; existing health-score tests must keep passing unchanged)

**Interfaces:**
- Produces: `static final Map<String, Double> HEALTH_SCORE_WEIGHTS` on `DashboardService`, keyed by
  the exact same 5 strings already used as `breakdown` map keys ("Savings Rate", "Debt Score",
  "Emergency Fund", "Spend Consistency", "Cash Flow Stability"). Task 4's insight formula reads this
  same constant — this task exists so that reuse is real, not just documented.

This is a behavior-preserving refactor: the weight VALUES don't change (0.25/0.20/0.25/0.15/0.15),
only where they live. `computeHealthScore`'s existing tests (there are several already covering
specific score values in `DashboardServiceTest.java` — search for `healthScore` in that file to
find them) must produce byte-identical results before and after.

- [ ] **Step 1: Add the constant, near the existing `MIN_TRANSACTIONS_FOR_HEALTH_SCORE` constant**

```java
// Shared with the AI Insight "potential gain" formula in computeTopOpportunity (Task 4) -- one
// source of truth for these five weights, so the insight can never silently drift out of sync
// with the overall score it's explaining.
static final Map<String, Double> HEALTH_SCORE_WEIGHTS = Map.of(
        "Savings Rate", 0.25,
        "Debt Score", 0.20,
        "Emergency Fund", 0.25,
        "Spend Consistency", 0.15,
        "Cash Flow Stability", 0.15
);
```

- [ ] **Step 2: Replace the hardcoded weights in the `overall` computation**

Find (around line 553 in the current file):

```java
int overall = (int) Math.round(savingsRateScore * 0.25 + debtScore * 0.20 + emergencyScore * 0.25
        + consistencyScore * 0.15 + cashFlowScore * 0.15);
```

Replace with:

```java
int overall = (int) Math.round(
        savingsRateScore * HEALTH_SCORE_WEIGHTS.get("Savings Rate")
                + debtScore * HEALTH_SCORE_WEIGHTS.get("Debt Score")
                + emergencyScore * HEALTH_SCORE_WEIGHTS.get("Emergency Fund")
                + consistencyScore * HEALTH_SCORE_WEIGHTS.get("Spend Consistency")
                + cashFlowScore * HEALTH_SCORE_WEIGHTS.get("Cash Flow Stability"));
```

- [ ] **Step 3: Run the existing DashboardService tests to confirm nothing shifted**

```bash
cd backend && ./mvnw -q -Dtest=DashboardServiceTest test
```

Expected: PASS, same pass count as before this change (this step is a regression guard, not a
new-behavior test — if any existing assertion on `healthScore`/`healthBreakdown` now fails, the
substitution above has a bug, not the test).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/finora/service/DashboardService.java
git commit -m "refactor(backend): extract HEALTH_SCORE_WEIGHTS as a shared constant"
```

---

## Task 3: Wire the snapshot upsert into `summarize()`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java` (constructor +
  `summarize()`)
- Modify: `backend/src/test/java/com/finora/service/DashboardServiceTest.java` (constructor call in
  `setUp()`)
- Test: same file, new cases

**Interfaces:**
- Consumes: `HealthScoreSnapshotRepository.upsertForMonth(...)` from Task 1.
- Produces: `DashboardService` now takes an 8th constructor parameter,
  `HealthScoreSnapshotRepository healthScoreSnapshotRepository`.

- [ ] **Step 1: Add the constructor parameter**

In `DashboardService.java`, add the field and constructor parameter, following the exact pattern
the existing `statementImportRepository` field already uses:

```java
private final HealthScoreSnapshotRepository healthScoreSnapshotRepository;

public DashboardService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                         CategoryRepository categoryRepository, BudgetRepository budgetRepository,
                         UserRepository userRepository,
                         com.finora.repository.StatementImportRepository statementImportRepository,
                         TransactionGraphService transactionGraphService,
                         HealthScoreSnapshotRepository healthScoreSnapshotRepository) {
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.categoryRepository = categoryRepository;
    this.budgetRepository = budgetRepository;
    this.userRepository = userRepository;
    this.statementImportRepository = statementImportRepository;
    this.transactionGraphService = transactionGraphService;
    this.healthScoreSnapshotRepository = healthScoreSnapshotRepository;
}
```

Add the import: `import com.finora.repository.HealthScoreSnapshotRepository;`

- [ ] **Step 2: Upsert the snapshot right after `computeHealthScore` is called**

Find (around line 170):

```java
var health = computeHealthScore(accounts, activeForTotals, months, liquid, refunds);
```

Add immediately after it:

```java
// Write-on-read: keeps this month's snapshot fresh the moment the user opens their dashboard.
// The nightly HealthScoreSnapshotSweepService (Task 6) covers users who don't. Never runs for an
// unavailable score -- there is nothing real to persist yet (see healthScoreAvailable gating
// throughout this method already).
if (health.available()) {
    healthScoreSnapshotRepository.upsertForMonth(
            userId, period.calendarMonth(), health.score(), health.label(),
            health.breakdown().get("Savings Rate"), health.breakdown().get("Debt Score"),
            health.breakdown().get("Emergency Fund"), health.breakdown().get("Spend Consistency"),
            health.breakdown().get("Cash Flow Stability"));
}
```

**Wait** — `period` is not yet defined at this point in the method (`ReportingPeriod period` is
resolved a few lines earlier, at line 136, before `computeHealthScore` is called at line 170 — so
it IS in scope; double check this against the actual current file before writing the edit, since
this plan was written against a snapshot of the file that may have shifted by the time this task
runs). If `period` has moved relative to this call site, use whatever the current file's already-computed
`ReportingPeriod` variable name is — do not introduce a second call to `ReportingPeriod.resolve(...)`.

- [ ] **Step 3: Add the new dependency to the existing unit test — all THREE constructor call sites**

`DashboardServiceTest.java` constructs `DashboardService` in three places, not one: `setUp()`
(around line 76), and two individual tests that rebuild it with a swapped-out repository (search
for `new DashboardService(` — as of this plan's writing they're at roughly lines 672 and 930). Add
the field, mock, and constructor argument to **all three**.

Field, alongside the other repository fields:

```java
private HealthScoreSnapshotRepository healthScoreSnapshotRepository;
```

In `setUp()`, alongside the other `mock(...)` calls:

```java
healthScoreSnapshotRepository = mock(HealthScoreSnapshotRepository.class);
```

Every `new DashboardService(...)` call (all three) gets `healthScoreSnapshotRepository` appended as
the 8th argument — e.g. the one in `setUp()`:

```java
dashboardService = new DashboardService(accountRepository, transactionRepository, categoryRepository,
        budgetRepository, userRepository, statementImportRepository, transactionGraphService,
        healthScoreSnapshotRepository);
```

The other two rebuild `dashboardService`/a locally-named service variable with a different
`accountRepository`/`userRepository` but otherwise reuse the class's shared mocks — pass the same
`healthScoreSnapshotRepository` field into those too.

Add the import: `import com.finora.repository.HealthScoreSnapshotRepository;`

No stubbing needed for `upsertForMonth` — it's `void`, and Mockito mocks no-op on unstubbed void
methods by default, so every existing test in this file keeps passing unchanged.

- [ ] **Step 4: Add a new test verifying the upsert is called with the right arguments**

```java
@Test
@DisplayName("summarize() upserts this month's health score snapshot when the score is available")
void summarize_upsertsHealthScoreSnapshotWhenAvailable() {
    LocalDate aug = LocalDate.of(2026, 8, 15);
    List<Transaction> txns = List.of(
            txn(new BigDecimal("50000"), Transaction.Type.INCOME, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("2000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK)
    ); // 11 transactions, clears MIN_TRANSACTIONS_FOR_HEALTH_SCORE (10)
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);

    DashboardSummaryDto result = dashboardService.summarize(userId);

    assertThat(result.healthScoreAvailable()).isTrue();
    // The upsert's month is period.calendarMonth() -- the REAL current month (YearMonth.now(zone)),
    // NOT the reporting month (here "2026-08", the newest month with data) -- these deliberately
    // diverge in this fixture. The mock User has no timezone set, so UserZone.forUser falls back
    // to UserZone.DEFAULT (Asia/Kolkata) -- verified by actually running this test, not assumed.
    String expectedYearMonth = java.time.YearMonth.now(com.finora.util.UserZone.DEFAULT).toString();
    org.mockito.Mockito.verify(healthScoreSnapshotRepository).upsertForMonth(
            eq(userId), eq(expectedYearMonth),
            eq(result.healthScore()), eq(result.healthLabel()),
            eq(result.healthBreakdown().get("Savings Rate")), eq(result.healthBreakdown().get("Debt Score")),
            eq(result.healthBreakdown().get("Emergency Fund")), eq(result.healthBreakdown().get("Spend Consistency")),
            eq(result.healthBreakdown().get("Cash Flow Stability")));
}
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && ./mvnw -q -Dtest=DashboardServiceTest test
```

Expected: all PASS, including the new test.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/DashboardService.java \
        backend/src/test/java/com/finora/service/DashboardServiceTest.java
git commit -m "feat(backend): upsert this month's health score snapshot on every dashboard load"
```

---

## Task 4: Insight formula + new `DashboardSummaryDto` fields

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/DashboardSummaryDto.java`
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java`
- Test: `backend/src/test/java/com/finora/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `HEALTH_SCORE_WEIGHTS` (Task 2), `HealthScoreSnapshotRepository` queries (Task 1).
- Produces: `DashboardSummaryDto` gains `healthScoreDeltaVsLastMonth: Integer`,
  `healthSparkline: List<HealthScorePoint>`, `healthTopOpportunityFactor: String`,
  `healthTopOpportunityPotentialGain: Integer`, and a nested
  `record HealthScorePoint(String yearMonth, int score)`.

- [ ] **Step 1: Add the new fields to `DashboardSummaryDto`**

Insert right after `healthScoreMinTransactions` (grouping every health-score-related field
together, matching how the DTO already groups related fields):

```java
        boolean healthScoreAvailable,
        int healthScoreTransactionCount,
        int healthScoreMinTransactions,

        /*
         * This month's healthScore minus the most recent PRIOR snapshot's score (which may not be
         * last calendar month -- see HealthScoreSnapshotRepository -- a gap is skipped over, not
         * treated as a missing delta). Null when no prior snapshot exists yet, or when
         * healthScoreAvailable is false -- never "+0" standing in for "nothing to compare against".
         */
        Integer healthScoreDeltaVsLastMonth,

        /*
         * Up to 6 trailing (yearMonth, score) points, oldest-to-newest, from persisted
         * HealthScoreSnapshot rows -- includes the row this same request just upserted (see
         * DashboardService.summarize). A month nobody opened the dashboard in is simply absent, not
         * interpolated. Empty when healthScoreAvailable is false.
         */
        List<HealthScorePoint> healthSparkline,

        /*
         * The single healthBreakdown factor with the largest realistic point-gain opportunity --
         * weight(factor) * (80 - factor's current score), for factors scoring below 80 -- and that
         * gain, rounded. Both null when every factor already scores >= 80, when the best gain rounds
         * under 3 points (too small to be a real signal, not just numeric noise), or when
         * healthScoreAvailable is false. See DashboardService.computeTopOpportunity.
         */
        String healthTopOpportunityFactor,
        Integer healthTopOpportunityPotentialGain,

        Map<String, BigDecimal> spendByCategory,
```

(Removed the old standalone `Map<String, BigDecimal> spendByCategory,` line from its previous
position since it's now the line right after the new block above — don't leave two copies of it.)

Add the nested record at the bottom of the file, alongside `CategoryMover`/`DetectedDuplicate`:

```java
    public record HealthScorePoint(String yearMonth, int score) {}
```

- [ ] **Step 2: Update the `DashboardSummaryDto` construction call site in `DashboardService.summarize()`**

Find the `return new DashboardSummaryDto(...)` call (around line 297) and insert the 4 new
arguments in the matching position, right after `health.available(), health.transactionCount(),
health.minTransactions(),`:

```java
                health.score(), health.label(), health.breakdown(), health.breakdownDetail(),
                health.available(), health.transactionCount(), health.minTransactions(),
                healthScoreDeltaVsLastMonth, healthSparkline,
                topOpportunity.map(Opportunity::factor).orElse(null),
                topOpportunity.map(Opportunity::potentialGain).orElse(null),
                spendByCategory, notifications,
```

(computing `healthScoreDeltaVsLastMonth`, `healthSparkline`, and `topOpportunity` is Step 3 below —
they must be computed before this return statement, after the Step-3-of-Task-3 upsert call so the
sparkline includes the row just written)

- [ ] **Step 3: Compute the three new values, right after the Task 3 upsert block**

```java
Integer healthScoreDeltaVsLastMonth = null;
List<DashboardSummaryDto.HealthScorePoint> healthSparkline = List.of();
Optional<Opportunity> topOpportunity = Optional.empty();

if (health.available()) {
    Optional<HealthScoreSnapshot> priorSnapshot =
            healthScoreSnapshotRepository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(
                    userId, period.calendarMonth());
    healthScoreDeltaVsLastMonth = priorSnapshot
            .map(s -> health.score() - s.getOverallScore())
            .orElse(null);

    healthSparkline = healthScoreSnapshotRepository.findTop6ByUserIdOrderByYearMonthDesc(userId)
            .reversed().stream()
            .map(s -> new DashboardSummaryDto.HealthScorePoint(s.getYearMonth(), s.getOverallScore()))
            .toList();

    topOpportunity = computeTopOpportunity(health.breakdown());
}
```

Add the imports: `import com.finora.entity.HealthScoreSnapshot;` (if not already present via the
constructor change in Task 3) and confirm `java.util.Optional` is already imported (it is, via
`java.util.*`).

- [ ] **Step 4: Add the `Opportunity` record and `computeTopOpportunity` method**

Place near `HealthResult`/`computeHealthScore`:

```java
private record Opportunity(String factor, int potentialGain) {}

/**
 * The single factor with the largest realistic point-gain opportunity: weight(factor) * (80 -
 * factor's current score), for every factor scoring below 80 (the existing "Good" threshold --
 * see scoreLabel/healthColor in Dashboard.tsx for where that cutoff already lives on the
 * frontend). Uses HEALTH_SCORE_WEIGHTS (Task 2) -- the SAME weights the overall score itself is
 * built from, so this can never disagree with the number it's explaining.
 *
 * <p>Empty when no factor scores below 80, or when the best candidate's gain rounds under 3
 * points -- a "+1 point" or "+2 point" opportunity reads as noise, not insight, and undermines
 * the credibility of the ones that are real.
 */
private Optional<Opportunity> computeTopOpportunity(Map<String, Double> breakdown) {
    return breakdown.entrySet().stream()
            .filter(e -> e.getValue() < 80)
            .map(e -> new Opportunity(e.getKey(),
                    (int) Math.round(HEALTH_SCORE_WEIGHTS.get(e.getKey()) * (80 - e.getValue()))))
            .filter(o -> o.potentialGain() >= 3)
            .max(Comparator.comparingInt(Opportunity::potentialGain));
}
```

- [ ] **Step 5: Write unit tests for `computeTopOpportunity`**

`computeHealthScore`/`computeTopOpportunity` are private, so these are exercised the same way the
rest of this file's health-score logic already is: through `summarize()`, asserting on the returned
DTO's `healthTopOpportunityFactor`/`healthTopOpportunityPotentialGain`.

**Before writing the "picks the largest gain" test's final assertion, run it once with a temporary
debug print** (`System.out.println(result.healthBreakdown())`) to see the REAL computed scores for
your fixture, then hardcode the real winning factor/gain — do not guess the formula's output on
paper. Verified once already for the fixture below (zero liquid savings + a 90%-utilized card, both
below 80): `breakdown = {Savings Rate=100.0, Debt Score=10.0, Emergency Fund=0.0, Spend
Consistency=100.0, Cash Flow Stability=100.0}`, giving Debt Score a gain of `0.20*(80-10)=14` and
Emergency Fund `0.25*(80-0)=20` — Emergency Fund wins despite Debt Score's lower raw score, because
its weight is higher. Add to `DashboardServiceTest.java`:

```java
@Test
@DisplayName("surfaces the factor with the largest realistic point-gain opportunity")
void topOpportunityPicksTheLargestRealisticGain() {
    // Zero liquid savings (Emergency Fund score 0, weight 0.25 -> gain 20) alongside a
    // near-maxed credit card (Debt Score 10, weight 0.20 -> gain 14): exercises that this picks
    // the largest WEIGHTED gain (Emergency Fund), not just the lowest raw score -- both are low,
    // but Debt Score's smaller weight keeps its gain behind Emergency Fund's. Real values
    // confirmed by running this exact fixture: breakdown = {Savings Rate=100.0, Debt Score=10.0,
    // Emergency Fund=0.0, Spend Consistency=100.0, Cash Flow Stability=100.0}.
    savings.setBalance(BigDecimal.ZERO);
    Account card = new Account();
    ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
    card.setUserId(userId);
    card.setAccountType(Account.Type.CREDIT_CARD);
    card.setBalance(new BigDecimal("9000"));
    card.setCreditLimit(new BigDecimal("10000")); // 90% utilization -> debtScore = 10
    when(accountRepository.findByUserId(any())).thenReturn(List.of(savings, card));

    LocalDate aug = LocalDate.of(2026, 8, 15);
    List<Transaction> txns = List.of(
            txn(new BigDecimal("50000"), Transaction.Type.INCOME, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK)
    );
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);

    DashboardSummaryDto result = dashboardService.summarize(userId);

    assertThat(result.healthTopOpportunityFactor()).isEqualTo("Emergency Fund");
    assertThat(result.healthTopOpportunityPotentialGain()).isEqualTo(20);
}

@Test
@DisplayName("hides the opportunity when every factor already scores at or above 80")
void noOpportunityWhenEveryFactorIsAlreadyGood() {
    // Ample liquid savings, zero credit cards (debtScore=100 by construction), steady positive
    // cash flow: every factor clears 80. Confirmed by asserting the real breakdown below, not
    // just assuming the fixture achieves it.
    savings.setBalance(new BigDecimal("500000"));

    LocalDate aug = LocalDate.of(2026, 8, 15);
    List<Transaction> txns = List.of(
            txn(new BigDecimal("50000"), Transaction.Type.INCOME, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK)
    );
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);

    DashboardSummaryDto result = dashboardService.summarize(userId);

    assertThat(result.healthBreakdown().values()).allMatch(v -> v >= 80);
    assertThat(result.healthTopOpportunityFactor()).isNull();
    assertThat(result.healthTopOpportunityPotentialGain()).isNull();
}

@Test
@DisplayName("delta and sparkline are null/empty with no prior snapshot")
void deltaAndSparklineEmptyWithNoHistory() {
    LocalDate aug = LocalDate.of(2026, 8, 15);
    List<Transaction> txns = List.of(
            txn(new BigDecimal("50000"), Transaction.Type.INCOME, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK),
            txn(new BigDecimal("1000"), Transaction.Type.EXPENSE, aug, Transaction.ReconciliationStatus.OK)
    );
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);
    // Unstubbed healthScoreSnapshotRepository already returns Optional.empty()/List.of() by
    // Mockito's smart defaults -- no history exists for this fresh mock.

    DashboardSummaryDto result = dashboardService.summarize(userId);

    assertThat(result.healthScoreDeltaVsLastMonth()).isNull();
    assertThat(result.healthSparkline()).isEmpty();
}

@Test
@DisplayName("all four new health-score fields are null/empty when the score itself is unavailable")
void newFieldsGatedByHealthScoreAvailable() {
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of()); // 0 transactions

    DashboardSummaryDto result = dashboardService.summarize(userId);

    assertThat(result.healthScoreAvailable()).isFalse();
    assertThat(result.healthScoreDeltaVsLastMonth()).isNull();
    assertThat(result.healthSparkline()).isEmpty();
    assertThat(result.healthTopOpportunityFactor()).isNull();
    assertThat(result.healthTopOpportunityPotentialGain()).isNull();
    org.mockito.Mockito.verify(healthScoreSnapshotRepository, org.mockito.Mockito.never()).upsertForMonth(
            any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble());
}
```

(Fully-qualified `org.mockito.Mockito.never()`/`org.mockito.ArgumentMatchers.anyInt()`/`anyDouble()`
rather than new static imports — this file already fully-qualifies occasional one-off Mockito calls
elsewhere, e.g. `org.mockito.Mockito.verify` in the pre-existing
`summarize_scopesTransactionAndStatementQueries_toLiveAccountIdsOnly` test, so this follows that
existing convention instead of adding imports used in only one place.)

- [ ] **Step 6: Run the tests**

```bash
cd backend && ./mvnw -q -Dtest=DashboardServiceTest test
```

Expected: all PASS (51 tests total: the 46 pre-existing + 1 from Task 3 + 4 from this task).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/dto/DashboardSummaryDto.java \
        backend/src/main/java/com/finora/service/DashboardService.java \
        backend/src/test/java/com/finora/service/DashboardServiceTest.java
git commit -m "feat(backend): compute health score delta, sparkline, and top opportunity"
```

---

## Task 5: Nightly sweep service + config

**Files:**
- Create: `backend/src/main/java/com/finora/service/HealthScoreSnapshotSweepService.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java`

**Interfaces:**
- Consumes: `DashboardService.summarize(UUID)` (existing, unchanged signature),
  `AccountRepository.findDistinctUserIds()` (existing), `UserRepository.findByIdInAndStatus(...)`
  (existing).
- Produces: `HealthScoreSnapshotSweepService.sweep()` returning a `Result(int saved, int skipped,
  int failed)`, callable directly from tests without going through the scheduler.

- [ ] **Step 1: Add the config, mirroring `net-worth-snapshot` exactly**

In `application.yml`, near the existing `net-worth-snapshot` block:

```yaml
  health-score-snapshot:
    sweep:
      # HealthScoreSnapshotSweepService. DashboardService.summarize() already upserts the current
      # month's snapshot on every dashboard load -- this just means a user's sparkline doesn't have
      # a gap for a month they had enough transaction history to score but never opened the
      # dashboard in. Off under test for the same reason every other sweep here is: a background
      # thread writing health_score_snapshot mid-test is the cross-test pollution BH-058 was about.
      # Tests call HealthScoreSnapshotSweepService.sweep() directly, which does not consult this flag.
      enabled: ${HEALTH_SCORE_SNAPSHOT_SWEEP_ENABLED:true}
      interval-ms: ${HEALTH_SCORE_SNAPSHOT_SWEEP_INTERVAL_MS:14400000}
      initial-delay-ms: ${HEALTH_SCORE_SNAPSHOT_SWEEP_INITIAL_DELAY_MS:300000}
```

In `application-test.yml`, near the existing `net-worth-snapshot` block:

```yaml
  # Same reasoning again: a background thread writing health_score_snapshot mid-test is the
  # cross-test pollution BH-058 was about. Tests that exercise the sweep call
  # HealthScoreSnapshotSweepService.sweep() directly, which does not consult this flag.
  health-score-snapshot:
    sweep:
      enabled: false
```

- [ ] **Step 2: Write the sweep service**

```java
// backend/src/main/java/com/finora/service/HealthScoreSnapshotSweepService.java
package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Fills the gap DashboardService.summarize()'s on-demand upsert leaves: a user with enough
 * transaction history to score, who simply doesn't open the dashboard in a given month, would
 * otherwise have a hole in their 6-month sparkline. Structured identically to
 * NetWorthSnapshotSweepService -- same fixedDelay/initial-delay shape, same per-user try/catch so
 * one user's failure doesn't stop the batch, same flag-gating reasoning (BH-058).
 *
 * <p>Deliberately calls the full DashboardService.summarize() per user rather than a hand-optimized
 * subset -- summarize() already IS what runs on every real dashboard load, so this sweep costs
 * nothing structurally new, just runs an already-exercised, already-tested path on a schedule
 * instead of on click. Reusing it also means the sweep's score can never disagree with what the
 * live dashboard would have shown that same user, since it's the exact same code.
 */
@Service
public class HealthScoreSnapshotSweepService {

    private static final Logger log = LoggerFactory.getLogger(HealthScoreSnapshotSweepService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final DashboardService dashboardService;

    @Value("${app.health-score-snapshot.sweep.enabled:true}")
    private boolean sweepEnabled;

    public HealthScoreSnapshotSweepService(AccountRepository accountRepository, UserRepository userRepository,
                                            DashboardService dashboardService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.dashboardService = dashboardService;
    }

    @Scheduled(fixedDelayString = "${app.health-score-snapshot.sweep.interval-ms:14400000}",
            initialDelayString = "${app.health-score-snapshot.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        log.info("Health score snapshot sweep: {} saved, {} skipped, {} failed.",
                result.saved(), result.skipped(), result.failed());
    }

    /**
     * One sweep pass: every ACTIVE user with at least one account gets summarize() called for
     * them, which upserts their health score snapshot as a side effect when available (unavailable
     * -- too few transactions -- is not a failure, just nothing to persist this pass). One user's
     * failure is caught and does not stop the batch; that user is simply retried whole next run.
     *
     * @return how many users were saved (summarize() ran, regardless of whether a score happened
     *         to be available -- matching "attempted", not "score was available", since this
     *         service has no cheap way to know in advance which candidates will score), skipped
     *         (user not ACTIVE), or failed (summarize() threw)
     */
    public Result sweep() {
        List<UUID> candidates = accountRepository.findDistinctUserIds();
        List<User> activeUsers = userRepository.findByIdInAndStatus(candidates, User.STATUS_ACTIVE);
        int skipped = candidates.size() - activeUsers.size();

        int saved = 0;
        int failed = 0;
        for (User user : activeUsers) {
            try {
                dashboardService.summarize(user.getId());
                saved++;
            } catch (Exception e) {
                log.warn("Health score snapshot sweep failed for user {}: {}", user.getId(), e.getMessage());
                failed++;
            }
        }
        return new Result(saved, skipped, failed);
    }

    public record Result(int saved, int skipped, int failed) {}
}
```

- [ ] **Step 3: Write the test**

```java
// backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java
package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HealthScoreSnapshotSweepServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private DashboardService dashboardService;
    private HealthScoreSnapshotSweepService sweepService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        dashboardService = mock(DashboardService.class);
        sweepService = new HealthScoreSnapshotSweepService(accountRepository, userRepository, dashboardService);
    }

    private User activeUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void callsSummarizeForEveryActiveUser() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(u1, u2));
        when(userRepository.findByIdInAndStatus(List.of(u1, u2), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(u1), activeUser(u2)));

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(u1);
        verify(dashboardService).summarize(u2);
        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void countsInactiveUsersAsSkippedWithoutCallingSummarize() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(active, inactive));
        when(userRepository.findByIdInAndStatus(List.of(active, inactive), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(active))); // inactive filtered out by the query itself

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(active);
        verify(dashboardService, never()).summarize(inactive);
        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void oneUsersFailureDoesNotStopTheBatch() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(failing, ok));
        when(userRepository.findByIdInAndStatus(List.of(failing, ok), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(failing), activeUser(ok)));
        doThrow(new RuntimeException("boom")).when(dashboardService).summarize(failing);

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(ok); // still ran despite the other user's failure
        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void scheduledSweepDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(sweepService, "sweepEnabled", false);
        sweepService.scheduledSweep();
        verifyNoInteractions(accountRepository, userRepository, dashboardService);
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
cd backend && ./mvnw -q -Dtest=HealthScoreSnapshotSweepServiceTest test
```

Expected: all 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/HealthScoreSnapshotSweepService.java \
        backend/src/main/resources/application.yml \
        backend/src/main/resources/application-test.yml \
        backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java
git commit -m "feat(backend): add flag-gated nightly health score snapshot sweep"
```

---

## Task 6: Frontend types + test fixture

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/pages/Dashboard.test.tsx` (the `summary()` fixture only, in this task)

**Interfaces:**
- Produces: `DashboardSummary` gains `healthScoreDeltaVsLastMonth: number | null`,
  `healthSparkline: HealthScorePoint[]`, `healthTopOpportunityFactor: string | null`,
  `healthTopOpportunityPotentialGain: number | null`, and `interface HealthScorePoint { yearMonth:
  string; score: number }`.

- [ ] **Step 1: Add the type fields**

In `frontend/src/types/index.ts`, right after `healthScoreMinTransactions: number;`:

```typescript
  healthScoreMinTransactions: number;
  /**
   * This month's healthScore minus the most recent PRIOR snapshot's score (which may not be last
   * calendar month -- a gap is skipped over). null when no prior snapshot exists yet, or when
   * healthScoreAvailable is false.
   */
  healthScoreDeltaVsLastMonth: number | null;
  /**
   * Up to 6 trailing (yearMonth, score) points, oldest-to-newest. A month nobody opened the
   * dashboard in is simply absent, not interpolated. Empty when healthScoreAvailable is false.
   */
  healthSparkline: HealthScorePoint[];
  /**
   * The single healthBreakdown factor with the largest realistic point-gain opportunity, and that
   * gain (rounded). Both null when every factor already scores >= 80, the best gain rounds under 3
   * points, or healthScoreAvailable is false.
   */
  healthTopOpportunityFactor: string | null;
  healthTopOpportunityPotentialGain: number | null;
  spendByCategory: Record<string, number>;
```

Add the new interface near the top of the file, alongside other small shared shapes:

```typescript
export interface HealthScorePoint {
  yearMonth: string;
  score: number;
}
```

- [ ] **Step 2: Extend the `summary()` test fixture**

In `Dashboard.test.tsx`, right after `healthScoreMinTransactions: 10,`:

```typescript
    healthScoreMinTransactions: 10,
    // Defaults to "no history yet" so existing tests, none of which cares about these fields,
    // keep rendering exactly as they did before these fields existed.
    healthScoreDeltaVsLastMonth: null,
    healthSparkline: [],
    healthTopOpportunityFactor: null,
    healthTopOpportunityPotentialGain: null,
    spendByCategory: {},
```

(the existing `spendByCategory: {},` line stays — just don't duplicate it; the block above shows it
once, in its new position right after the 4 new lines)

- [ ] **Step 3: Run the existing Dashboard test suite to confirm nothing broke**

```bash
cd frontend && npx vitest run src/pages/Dashboard.test.tsx
```

Expected: PASS, same count as before this change (TypeScript will fail to compile if any field is
missing from the fixture — that's the signal this step is checking for, not new behavior).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/pages/Dashboard.test.tsx
git commit -m "feat(frontend): add health score trend/insight fields to DashboardSummary"
```

---

## Task 7: `HealthScoreGauge` component

**Files:**
- Create: `frontend/src/design-system/HealthScoreGauge.tsx`
- Create: `frontend/src/design-system/HealthScoreGauge.test.tsx`
- Modify: `frontend/src/design-system/index.ts` (export it)

**Interfaces:**
- Produces: `<HealthScoreGauge score={number} />` — a semi-circular SVG gauge with 3 color zones
  (red 0-30, amber 31-60, green 61-100).

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/design-system/HealthScoreGauge.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreGauge } from './HealthScoreGauge';

describe('HealthScoreGauge', () => {
  it('renders an accessible label stating the score out of 100', () => {
    render(<HealthScoreGauge score={51} />);
    expect(screen.getByRole('img', { name: /51 out of 100/i })).toBeInTheDocument();
  });

  it('uses the red zone color at a score in 0-30', () => {
    render(<HealthScoreGauge score={20} />);
    const arc = screen.getByTestId('health-score-gauge-fill');
    expect(arc).toHaveAttribute('data-zone', 'red');
  });

  it('uses the amber zone color at a score in 31-60', () => {
    render(<HealthScoreGauge score={45} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
  });

  it('uses the green zone color at a score in 61-100', () => {
    render(<HealthScoreGauge score={85} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'green');
  });

  it('treats the 30/31 and 60/61 boundaries correctly', () => {
    const { rerender } = render(<HealthScoreGauge score={30} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'red');
    rerender(<HealthScoreGauge score={31} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
    rerender(<HealthScoreGauge score={60} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'amber');
    rerender(<HealthScoreGauge score={61} />);
    expect(screen.getByTestId('health-score-gauge-fill')).toHaveAttribute('data-zone', 'green');
  });
});
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd frontend && npx vitest run src/design-system/HealthScoreGauge.test.tsx
```

Expected: FAIL — `Cannot find module './HealthScoreGauge'`.

- [ ] **Step 3: Write the component**

```typescript
// frontend/src/design-system/HealthScoreGauge.tsx
const RADIUS = 70;
const HALF_CIRCUMFERENCE = Math.PI * RADIUS;

function zoneFor(score: number): 'red' | 'amber' | 'green' {
  if (score <= 30) return 'red';
  if (score <= 60) return 'amber';
  return 'green';
}

const ZONE_COLOR: Record<ReturnType<typeof zoneFor>, string> = {
  red: 'var(--color-danger)',
  amber: 'var(--color-warning)',
  green: 'var(--color-success)',
};

/**
 * Semi-circular 0-100 gauge with 3 color zones (red 0-30, amber 31-60, green 61-100) -- a coarser
 * read than the existing 4-tier scoreLabel/healthColor cutoffs (80/60/40) used for the text label
 * shown beneath this gauge. Both are deliberate: real credit-score dashboards commonly pair a
 * coarse gauge color with a finer text label.
 */
export function HealthScoreGauge({ score }: { score: number }) {
  const clamped = Math.max(0, Math.min(100, score));
  const zone = zoneFor(clamped);
  const fillLength = (clamped / 100) * HALF_CIRCUMFERENCE;

  return (
    <svg
      viewBox="0 0 160 90"
      className="w-full max-w-[220px]"
      role="img"
      aria-label={`Financial health score ${clamped} out of 100`}
    >
      <path
        d={`M 10 80 A ${RADIUS} ${RADIUS} 0 0 1 150 80`}
        fill="none"
        stroke="var(--color-border)"
        strokeWidth="14"
        strokeLinecap="round"
      />
      <path
        data-testid="health-score-gauge-fill"
        data-zone={zone}
        d={`M 10 80 A ${RADIUS} ${RADIUS} 0 0 1 150 80`}
        fill="none"
        stroke={ZONE_COLOR[zone]}
        strokeWidth="14"
        strokeLinecap="round"
        strokeDasharray={`${fillLength} ${HALF_CIRCUMFERENCE - fillLength}`}
      />
    </svg>
  );
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
cd frontend && npx vitest run src/design-system/HealthScoreGauge.test.tsx
```

Expected: all 5 PASS.

- [ ] **Step 5: Export it from the design-system index**

```typescript
// frontend/src/design-system/index.ts -- add alongside the other exports
export { HealthScoreGauge } from './HealthScoreGauge';
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/design-system/HealthScoreGauge.tsx \
        frontend/src/design-system/HealthScoreGauge.test.tsx \
        frontend/src/design-system/index.ts
git commit -m "feat(frontend): add semi-circular HealthScoreGauge component"
```

---

## Task 8: `HealthScoreRangeLegend`, `HealthScoreSparkline`, and factor-card copy helpers

**Files:**
- Create: `frontend/src/design-system/HealthScoreRangeLegend.tsx`
- Create: `frontend/src/design-system/HealthScoreRangeLegend.test.tsx`
- Create: `frontend/src/design-system/HealthScoreSparkline.tsx`
- Create: `frontend/src/design-system/HealthScoreSparkline.test.tsx`
- Modify: `frontend/src/design-system/index.ts`
- Modify: `frontend/src/pages/Dashboard.tsx` (add the copy-template helper functions near the
  existing `healthColor`/`healthItemBarColor`/`scoreLabel` helpers)

**Interfaces:**
- Produces: `<HealthScoreRangeLegend score={number} />`.
- Produces: `<HealthScoreSparkline points={{ yearMonth: string; score: number }[]} />` — a gap in
  `points` (a missing month) renders as a break in the line, never interpolated.
- Produces (in `Dashboard.tsx`): `healthImprovementSuggestion(factor: string, score: number):
  string` and `badgeToneForScore(score: number): 'success' | 'primary' | 'warning' | 'danger'`.

- [ ] **Step 1: Write the range legend test**

```typescript
// frontend/src/design-system/HealthScoreRangeLegend.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreRangeLegend } from './HealthScoreRangeLegend';

describe('HealthScoreRangeLegend', () => {
  it('renders all four tiers', () => {
    render(<HealthScoreRangeLegend score={51} />);
    expect(screen.getByText('0-40')).toBeInTheDocument();
    expect(screen.getByText('41-60')).toBeInTheDocument();
    expect(screen.getByText('61-80')).toBeInTheDocument();
    expect(screen.getByText('81-100')).toBeInTheDocument();
  });

  it('marks the tier containing the current score as current', () => {
    render(<HealthScoreRangeLegend score={51} />);
    expect(screen.getByText('41-60').closest('[data-current]')).toHaveAttribute('data-current', 'true');
    expect(screen.getByText('0-40').closest('[data-current]')).toHaveAttribute('data-current', 'false');
  });

  it('marks the top tier as current at the boundary score of 100', () => {
    render(<HealthScoreRangeLegend score={100} />);
    expect(screen.getByText('81-100').closest('[data-current]')).toHaveAttribute('data-current', 'true');
  });
});
```

- [ ] **Step 2: Run it, confirm it fails, then write the component**

```typescript
// frontend/src/design-system/HealthScoreRangeLegend.tsx
const TIERS = [
  { range: '0-40', label: 'Needs Attention', min: 0, max: 40 },
  { range: '41-60', label: 'Fair', min: 41, max: 60 },
  { range: '61-80', label: 'Good', min: 61, max: 80 },
  { range: '81-100', label: 'Excellent', min: 81, max: 100 },
] as const;

/**
 * Answers "what does 51 mean, how far to the next tier" directly under the gauge -- unlike a
 * credit score, this score has no externally-understood meaning on its own.
 */
export function HealthScoreRangeLegend({ score }: { score: number }) {
  return (
    <div className="space-y-1">
      {TIERS.map((tier) => {
        const isCurrent = score >= tier.min && score <= tier.max;
        return (
          <div
            key={tier.range}
            data-current={isCurrent}
            className={`flex items-center justify-between text-[11px] rounded px-1.5 py-0.5 ${
              isCurrent ? 'bg-surface font-semibold text-ink' : 'text-muted'
            }`}
          >
            <span>{tier.range}</span>
            <span>{tier.label}</span>
          </div>
        );
      })}
    </div>
  );
}
```

```bash
cd frontend && npx vitest run src/design-system/HealthScoreRangeLegend.test.tsx
```

Expected: FAIL first (missing module), then PASS (all 3) after the component is written.

- [ ] **Step 3: Write the sparkline test**

```typescript
// frontend/src/design-system/HealthScoreSparkline.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { HealthScoreSparkline } from './HealthScoreSparkline';

describe('HealthScoreSparkline', () => {
  it('renders one polyline segment per contiguous run of months', () => {
    render(
      <HealthScoreSparkline
        points={[
          { yearMonth: '2026-04', score: 40 },
          { yearMonth: '2026-05', score: 45 },
          // gap at 2026-06 -- nobody opened the dashboard that month
          { yearMonth: '2026-07', score: 55 },
          { yearMonth: '2026-08', score: 51 },
        ]}
      />
    );
    // Two contiguous runs (Apr-May, Jul-Aug) around the June gap -> two polylines, not one
    // continuous line that would visually paper over the missing month.
    const svg = screen.getByTestId('health-score-sparkline');
    expect(svg.querySelectorAll('polyline')).toHaveLength(2);
  });

  it('renders nothing but an empty svg for fewer than 2 points', () => {
    render(<HealthScoreSparkline points={[{ yearMonth: '2026-08', score: 51 }]} />);
    expect(screen.getByTestId('health-score-sparkline').querySelectorAll('polyline')).toHaveLength(0);
  });
});
```

- [ ] **Step 4: Run it, confirm it fails, then write the component**

```typescript
// frontend/src/design-system/HealthScoreSparkline.tsx
interface Point { yearMonth: string; score: number }

/** True when b is exactly one calendar month after a ("2026-05" after "2026-04"). */
function isNextMonth(a: string, b: string): boolean {
  const [ay, am] = a.split('-').map(Number);
  const [by, bm] = b.split('-').map(Number);
  const aTotal = ay * 12 + am;
  const bTotal = by * 12 + bm;
  return bTotal === aTotal + 1;
}

/** Splits points into contiguous-month runs, so a gap renders as a break, never interpolated. */
function splitIntoRuns(points: Point[]): Point[][] {
  if (points.length === 0) return [];
  const runs: Point[][] = [[points[0]]];
  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const cur = points[i];
    if (isNextMonth(prev.yearMonth, cur.yearMonth)) {
      runs[runs.length - 1].push(cur);
    } else {
      runs.push([cur]);
    }
  }
  return runs;
}

export function HealthScoreSparkline({ points }: { points: Point[] }) {
  const runs = splitIntoRuns(points).filter((run) => run.length >= 2);
  const width = 200;
  const height = 40;
  const xFor = (i: number) => (points.length <= 1 ? 0 : (i / (points.length - 1)) * width);
  const yFor = (score: number) => height - (score / 100) * height;

  return (
    <svg data-testid="health-score-sparkline" viewBox={`0 0 ${width} ${height}`} className="w-full h-10" aria-hidden="true">
      {runs.map((run) => (
        <polyline
          key={run[0].yearMonth}
          points={run.map((p) => `${xFor(points.indexOf(p))},${yFor(p.score)}`).join(' ')}
          fill="none"
          className="stroke-primary"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </svg>
  );
}
```

```bash
cd frontend && npx vitest run src/design-system/HealthScoreSparkline.test.tsx
```

Expected: FAIL first, then PASS (both tests) after the component is written.

- [ ] **Step 5: Export both from the design-system index**

```typescript
// frontend/src/design-system/index.ts
export { HealthScoreRangeLegend } from './HealthScoreRangeLegend';
export { HealthScoreSparkline } from './HealthScoreSparkline';
```

- [ ] **Step 6: Add the copy-template helpers to `Dashboard.tsx`**

Place these right after the existing `scoreLabel` function:

```typescript
// Deterministic, per-factor -- never AI-generated prose. Each threshold is the exact 80-point
// cutoff computeTopOpportunity (backend) and scoreLabel (above) both already use, so a card never
// tells a user to do something their own score already shows they've done.
function healthImprovementSuggestion(factor: string, score: number): string {
  const good = score >= 80;
  switch (factor) {
    case 'Savings Rate':
      return good ? "You're saving well — keep it up." : 'Aim to save at least 24% of your income each month.';
    case 'Debt Score':
      return good ? 'Your credit utilization is in good shape.' : 'Pay down credit card balances to bring utilization under 20%.';
    case 'Emergency Fund':
      return good ? 'You have a solid safety net.' : 'Build your emergency fund toward 4-5 months of expenses.';
    case 'Spend Consistency':
      return good ? 'Your spending has been consistent.' : 'Try to keep monthly spending within about 20% of your average.';
    case 'Cash Flow Stability':
      return good ? 'Your cash flow has been stable.' : 'Work toward income meeting or exceeding expenses most months.';
    default:
      return '';
  }
}

// Reuses the same 80/60/40 cutoffs as healthColor/scoreLabel -- the Badge design-system component
// already covers exactly this vocabulary (see Budgets' status pills).
function badgeToneForScore(score: number): 'success' | 'primary' | 'warning' | 'danger' {
  if (score >= 80) return 'success';
  if (score >= 60) return 'primary';
  if (score >= 40) return 'warning';
  return 'danger';
}
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/design-system/HealthScoreRangeLegend.tsx \
        frontend/src/design-system/HealthScoreRangeLegend.test.tsx \
        frontend/src/design-system/HealthScoreSparkline.tsx \
        frontend/src/design-system/HealthScoreSparkline.test.tsx \
        frontend/src/design-system/index.ts \
        frontend/src/pages/Dashboard.tsx
git commit -m "feat(frontend): add health score range legend, sparkline, and factor-card copy helpers"
```

---

## Task 9: Rebuild the Financial Health Score card in `Dashboard.tsx`

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx` (the Financial Health Score `FinoraCard` block, lines
  ~435-512 as of this plan's writing — re-locate by searching for `Financial Health Score` before
  editing, since earlier tasks in this plan and other work landing on `main` in the meantime may
  have shifted line numbers)
- Modify: `frontend/src/pages/Dashboard.test.tsx`

**Interfaces:**
- Consumes: `HealthScoreGauge`, `HealthScoreRangeLegend`, `HealthScoreSparkline` (Tasks 7-8),
  `healthImprovementSuggestion`, `badgeToneForScore` (Task 8), `Badge` (existing), all 4 new
  `DashboardSummary` fields (Task 6).

- [ ] **Step 1: Add the new design-system imports**

`Dashboard.tsx` already imports `Badge` from `'../design-system'` — add the three new components
from Tasks 7-8 to that same import line:

```tsx
import { FinoraCard, MetricCard, EmptyState, SectionHeader, QuickActionCard, ChartContainer, Badge, baseChartOptions, Button, Skeleton, HealthScoreGauge, HealthScoreRangeLegend, HealthScoreSparkline } from '../design-system';
```

- [ ] **Step 2: Replace the `summary.healthScoreAvailable` branch's inner markup**

Keep the outer `{!isEmpty && (<FinoraCard ...>` wrapper, the header row (`ShieldCheck` icon +
"Financial Health Score" title), and the `!summary.healthScoreAvailable` ("Getting Started")
branch exactly as they are today — only the *available* branch's inner content changes:

```tsx
{summary.healthScoreAvailable ? (
  <div className="space-y-6">
    {/* Gauge + range legend: ~40% of this card's visual weight, factor cards below take the
        rest -- an unfamiliar "51/100" needs the explanation more than it needs a bigger gauge,
        since (unlike a credit score) this number has no meaning outside this app. */}
    <div className="grid md:grid-cols-[auto_1fr] gap-6 items-center">
      <div className="flex flex-col items-center">
        <HealthScoreGauge score={summary.healthScore!} />
        <p className={`text-3xl font-bold -mt-2 ${healthColor(summary.healthLabel!)}`}>{summary.healthScore}</p>
        <p className={`text-sm font-medium ${healthColor(summary.healthLabel!)}`}>{summary.healthLabel}</p>
        {summary.healthScoreDeltaVsLastMonth !== null && (
          <p className={`text-xs mt-1 ${summary.healthScoreDeltaVsLastMonth >= 0 ? 'text-success' : 'text-danger'}`}>
            {summary.healthScoreDeltaVsLastMonth >= 0 ? '↑' : '↓'} {Math.abs(summary.healthScoreDeltaVsLastMonth)} vs last month
          </p>
        )}
        <p className="text-[11px] text-muted mt-2 text-center max-w-[200px]">
          Calculated from savings, debt, emergency fund, spending consistency, and cash-flow stability.
        </p>
      </div>
      <div className="w-full max-w-[220px] md:max-w-none">
        <HealthScoreRangeLegend score={summary.healthScore!} />
      </div>
    </div>

    {summary.healthSparkline.length >= 2 && (
      <div>
        <p className="text-xs font-medium text-ink mb-1">6-month trend</p>
        <HealthScoreSparkline points={summary.healthSparkline} />
      </div>
    )}

    {/* Factor cards -- replaces the old horizontal progress bars. */}
    <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-3">
      {Object.entries(summary.healthBreakdown).map(([name, score]) => {
        const isTopOpportunity = name === summary.healthTopOpportunityFactor;
        return (
          <div key={name} className="rounded-xl2 border border-border bg-bg p-4">
            <div className="flex items-center justify-between mb-1">
              <span className="text-sm font-medium text-ink">{name}</span>
              <Badge tone={badgeToneForScore(score)} label={scoreLabel(score)} />
            </div>
            <p className="text-lg font-bold text-ink mb-1">{Math.round(score)} / 100</p>
            <p className="text-xs text-muted">{summary.healthBreakdownDetail[name]}</p>
            <p className="text-xs text-ink mt-1.5">{healthImprovementSuggestion(name, score)}</p>
            {isTopOpportunity && summary.healthTopOpportunityPotentialGain !== null && (
              <p className="text-xs font-semibold text-primary mt-1.5">
                ↑ +{summary.healthTopOpportunityPotentialGain} point opportunity
              </p>
            )}
          </div>
        );
      })}
    </div>

    {/* AI Insight card -- only when there's a real (>= 3 point) opportunity. */}
    {summary.healthTopOpportunityFactor && summary.healthTopOpportunityPotentialGain !== null && (
      <div className="rounded-xl2 border border-primary/30 bg-primary-light p-4 flex items-start justify-between gap-4 flex-wrap">
        <div>
          <p className="text-sm font-medium text-ink">
            Your {summary.healthTopOpportunityFactor.toLowerCase()} is the biggest opportunity to improve your score.
          </p>
          <p className="text-xs text-muted mt-0.5">
            Potential gain: <span className="font-semibold text-primary">+{summary.healthTopOpportunityPotentialGain} points</span>
          </p>
        </div>
        <Link
          to="/app/goals"
          className="inline-flex items-center gap-1.5 rounded-lg bg-primary text-on-primary hover:bg-primary-dark px-3.5 py-2 text-xs font-semibold transition-colors flex-shrink-0"
        >
          Create Goal
        </Link>
      </div>
    )}
  </div>
) : (
  // ... existing "Getting Started" branch, unchanged ...
)}
```

Remove the now-unused `expandedHealthDetail` state and its "Why?" toggle button from the old
per-bar markup — the new factor cards always show the explanation text (`healthBreakdownDetail`)
inline, there's no longer a collapsed/expanded state to toggle. Search for
`expandedHealthDetail`/`setExpandedHealthDetail` in `Dashboard.tsx` and remove the `useState`
declaration if this was its only use (confirm with a grep before deleting — don't remove it if
something else in the file also reads it).

- [ ] **Step 3: Update `Dashboard.test.tsx`**

Find the existing tests that assert on the old progress-bar markup (search for `healthItemBarColor`,
`Why?`, or the old bar `style={{ width:` pattern in test assertions) and replace them with
assertions against the new structure. At minimum, add:

```typescript
it('renders the gauge, range legend, and factor cards with NN/100 scores', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
    healthScore: 51, healthLabel: 'Fair',
    healthBreakdown: { 'Savings Rate': 83, 'Debt Score': 100, 'Emergency Fund': 42, 'Spend Consistency': 50, 'Cash Flow Stability': 80 },
    healthBreakdownDetail: {
      'Savings Rate': 'Your savings rate was 18.5%.',
      'Debt Score': 'You have no credit cards on file.',
      'Emergency Fund': 'Your liquid savings would cover 2.1 months of expenses.',
      'Spend Consistency': 'Your monthly spending has varied by about 40% around its average recently.',
      'Cash Flow Stability': '2 of the last 3 full months had income meeting or exceeding expenses.',
    },
  }));
  renderDashboard();

  expect(await screen.findByRole('img', { name: /51 out of 100/i })).toBeInTheDocument();
  expect(screen.getByText('42 / 100')).toBeInTheDocument(); // Emergency Fund's score, NN/100 format
  expect(screen.getByText('0-40')).toBeInTheDocument(); // range legend renders
});

it('shows the monthly change indicator when a delta is present, hides it when null', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: 8 }));
  renderDashboard();
  expect(await screen.findByText(/↑ 8 vs last month/)).toBeInTheDocument();
});

it('hides the monthly change indicator when there is no prior snapshot', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({ healthScoreDeltaVsLastMonth: null }));
  renderDashboard();
  await screen.findByText('Financial Health Score');
  expect(screen.queryByText(/vs last month/)).not.toBeInTheDocument();
});

it('renders the AI Insight card with a Create Goal link when a real opportunity exists', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
    healthTopOpportunityFactor: 'Emergency Fund', healthTopOpportunityPotentialGain: 18,
  }));
  renderDashboard();

  expect(await screen.findByText(/emergency fund is the biggest opportunity/i)).toBeInTheDocument();
  expect(screen.getByText('+18 points')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /create goal/i })).toHaveAttribute('href', '/app/goals');
});

it('hides the AI Insight card when there is no real opportunity', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
    healthTopOpportunityFactor: null, healthTopOpportunityPotentialGain: null,
  }));
  renderDashboard();
  await screen.findByText('Financial Health Score');
  expect(screen.queryByText(/biggest opportunity/i)).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /create goal/i })).not.toBeInTheDocument();
});

it('shows the point-opportunity badge only on the top opportunity factor\'s own card', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
    healthTopOpportunityFactor: 'Emergency Fund', healthTopOpportunityPotentialGain: 18,
  }));
  renderDashboard();
  expect(await screen.findByText('↑ +18 point opportunity')).toBeInTheDocument();
  // Exactly one badge -- not repeated on every card.
  expect(screen.getAllByText(/point opportunity/)).toHaveLength(1);
});

it('renders the sparkline only with at least 2 points, splitting across a gap month', async () => {
  vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
    healthSparkline: [
      { yearMonth: '2026-06', score: 45 },
      { yearMonth: '2026-08', score: 51 }, // gap at 2026-07
    ],
  }));
  renderDashboard();
  await screen.findByText('Financial Health Score');
  const sparkline = screen.getByTestId('health-score-sparkline');
  expect(sparkline.querySelectorAll('polyline')).toHaveLength(0); // two 1-point runs, neither drawable
});
```

Remove or rewrite any pre-existing test that specifically asserted on the old "Why?" toggle button
or the old bar `<div>` structure — grep for `expandedHealthDetail`, `'Why?'`, and `healthItemBarColor`
usages inside `Dashboard.test.tsx` and adjust each one found; don't leave a test asserting on markup
that no longer exists.

- [ ] **Step 4: Run the full Dashboard test suite**

```bash
cd frontend && npx vitest run src/pages/Dashboard.test.tsx
```

Expected: all PASS.

- [ ] **Step 5: Run typecheck and lint**

```bash
cd frontend && npx tsc -b && npx eslint src/pages/Dashboard.tsx src/pages/Dashboard.test.tsx --max-warnings 0
```

Expected: both clean.

- [ ] **Step 6: Run the full frontend test suite**

```bash
cd frontend && npx vitest run
```

Expected: all files pass (this catches any other test elsewhere in the app that happened to depend
on the old Financial Health Score markup, e.g. a smoke test rendering the full Dashboard page).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx frontend/src/pages/Dashboard.test.tsx
git commit -m "feat(frontend): rebuild Financial Health Score card with gauge, factor cards, and AI insight"
```

---

## Task 10: Manual verification in the browser

**Files:** none (verification only)

- [ ] **Step 1: Start the backend and frontend dev servers**, sign in as a seeded user with at
  least `MIN_TRANSACTIONS_FOR_HEALTH_SCORE` (10) transactions across a few months.

- [ ] **Step 2: Load the Dashboard** and visually confirm: the gauge renders with the correct zone
  color for the account's actual score, the range legend highlights the right tier, factor cards
  show `NN / 100` plus a badge plus a suggestion, the top-opportunity card (if any) shows the point
  badge, the AI Insight card (if any) links to `/app/goals`, and the sparkline renders once at
  least 2 months of snapshot history exist (a brand-new seeded account will show none yet — that's
  expected on the first load; reload the dashboard once more to confirm the current month's
  snapshot got written, or query `health_score_snapshot` directly to confirm the row exists).

- [ ] **Step 3: Query the database directly** to confirm the on-demand upsert actually wrote a row:

```sql
SELECT * FROM health_score_snapshot WHERE user_id = '<the test user's id>';
```

- [ ] **Step 4: Run the sweep manually** (e.g. a temporary test endpoint, or
  `HealthScoreSnapshotSweepService.sweep()` invoked from a scratch integration test) against a
  second seeded user who has never opened the dashboard, and confirm a snapshot row appears for
  them too.

No commit for this task — it's verification only. If any issue is found, fix it in the relevant
earlier task's files and re-run that task's tests before returning here.
