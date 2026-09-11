# Fynora Identity Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Financial Journey Timeline (a curated, permanent-vs-dynamic, Minor/Major/Landmark event log) as Fynora's identity/exclusivity moat, plus the two features that consume it now — a Dashboard "My Journey" surface and derived badges — and the two features that reuse the same underlying data — Wealth Wrapped and goal-based momentum.

**Architecture:** A new `timeline_events` table is the single source of truth, written to at the moment each milestone fires (not reconstructed later from `AuditLog`, which redacts its metadata payload after a retention window — see spec §3). `GoalService`, `BudgetService`, and `NetWorthService` each gain a small hook that calls `TimelineEventService.record(...)` right after their existing writes. A one-time lazy backfill materializes Starting-bucket history for existing users from data that already has real historical timestamps (`Goal`/`Budget`/`StatementImport` "earliest ever" queries). Wrapped and momentum are read-side aggregations over the same table plus `GoalContribution`; badges are a static label lookup keyed by event type, attached at read time — never a second, independently-tracked state machine.

**Tech Stack:** Spring Boot / JPA / PostgreSQL (backend), React + TanStack Query + TypeScript (frontend). Web only for this plan — mobile has no consumer of any of this today and is out of scope (see Global Constraints).

**Spec:** [docs/superpowers/specs/2026-09-11-identity-engine-design.md](../specs/2026-09-11-identity-engine-design.md)

## Global Constraints

- **No wealth- or income-based milestones, ever.** Every event type in this plan is behavior-based (an action taken, a threshold of behavior crossed) — never a raw net-worth or income figure shown as a ranking or score. (Spec §5)
- **No hard consecutive streaks that reset to zero on one miss.** Momentum is a rolling "N of last M months" count. (Spec §4, Layer 4)
- **No percentile or cross-user comparison of any kind in this plan.** That's Layer 5, explicitly deferred pending data-trust and scale preconditions this plan does not attempt to satisfy. (Spec §4, Layer 5)
- **Only `Landmark`-importance events are eligible for Wrapped, share cards, or profile highlights.** `Minor`/`Major` events are timeline-only. (Spec §4.3)
- **Timeline is the source of truth; badges are derived, never separately tracked state.** (Spec §4)
- **Web only.** No mobile screens, no mobile API client changes, in this plan.
- **Every new backend endpoint requires an OpenAPI regen** (`backend/scripts/generate-openapi-spec.sh`, then `npm run generate:types` in `frontend/`) before the corresponding frontend task — this repo's CI treats OpenAPI drift as blocking, not advisory.
- **Flyway version:** this plan uses `V193`. Before running Task 1's migration, re-run `git fetch origin && ls backend/src/main/resources/db/migration/ | sort -t V -k2 -n | tail -5` to confirm `V193` is still free — this repo has had three prior version collisions from concurrent sessions.

---

### Task 1: `TimelineEvent` entity, migration, and repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V193__timeline_events.sql`
- Create: `backend/src/main/java/com/finora/timeline/TimelineEvent.java`
- Create: `backend/src/main/java/com/finora/timeline/TimelineEventRepository.java`
- Test: `backend/src/test/java/com/finora/timeline/TimelineEventRepositoryIT.java`

**Interfaces:**
- Produces: `TimelineEvent` (fields: `id: UUID`, `userId: UUID`, `eventType: String`, `bucket: String`, `importance: String`, `permanent: boolean`, `referenceId: UUID` nullable, `title: String`, `detail: String` nullable, `occurredAt: Instant`, `createdAt: Instant`); `TimelineEventRepository.findByUserIdOrderByOccurredAtDesc(UUID): List<TimelineEvent>`; `TimelineEventRepository.existsByUserId(UUID): boolean`; `TimelineEventRepository.insertIfNew(...)` (native, idempotent).

- [ ] **Step 1: Write the migration**

```sql
-- V193__timeline_events.sql
CREATE TABLE timeline_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    event_type    VARCHAR(64) NOT NULL,
    bucket        VARCHAR(20) NOT NULL,
    importance    VARCHAR(10) NOT NULL,
    permanent     BOOLEAN NOT NULL,
    reference_id  UUID,
    title         TEXT NOT NULL,
    detail        TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_timeline_events_user_occurred ON timeline_events(user_id, occurred_at DESC);

-- Enforces "fires at most once" for account-wide singleton milestones (reference_id IS NULL,
-- e.g. FIRST_GOAL_CREATED) and "fires at most once per referenced entity" for per-goal
-- milestones (reference_id = the goal's id, e.g. GOAL_COMPLETED). Two partial indexes because
-- Postgres treats every NULL as distinct under a plain UNIQUE(user_id, event_type,
-- reference_id) -- that alone would never block a duplicate singleton insert.
CREATE UNIQUE INDEX ux_timeline_events_singleton ON timeline_events(user_id, event_type)
    WHERE reference_id IS NULL;
CREATE UNIQUE INDEX ux_timeline_events_per_reference ON timeline_events(user_id, event_type, reference_id)
    WHERE reference_id IS NOT NULL;
```

- [ ] **Step 2: Write the entity**

```java
// backend/src/main/java/com/finora/timeline/TimelineEvent.java
package com.finora.timeline;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Append-only, immutable once written -- deliberately not extending BaseEntity (no
 *  soft-delete, no optimistic version, same shape as GoalContribution/NetWorthSnapshot).
 *  See the design spec's Layer 1 section for why every field is persisted at event time
 *  rather than derived on read from AuditLog. */
@Entity
@Table(name = "timeline_events")
public class TimelineEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String bucket;

    @Column(nullable = false)
    private String importance;

    @Column(nullable = false)
    private boolean permanent;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(nullable = false)
    private String title;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getImportance() { return importance; }
    public void setImportance(String importance) { this.importance = importance; }
    public boolean isPermanent() { return permanent; }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID referenceId) { this.referenceId = referenceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 3: Write the repository, with the failing test first**

Note: this codebase has no `@DataJpaTest` usage anywhere (confirmed by repo-wide grep) —
repository-level tests that need real Postgres semantics (native queries, partial unique
indexes) follow the established `*IT` + `AbstractIntegrationTest` + Testcontainers pattern
instead (see `backend/src/test/java/com/finora/AbstractIntegrationTest.java` and any existing
`*RepositoryIT.java` for the precedent). Run via `./mvnw verify -Dit.test=<ClassName>
-DfailIfNoTests=false`, not `./mvnw test` (failsafe, not surefire, runs `*IT` classes).

```java
// backend/src/test/java/com/finora/timeline/TimelineEventRepositoryIT.java
package com.finora.timeline;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineEventRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private TimelineEventRepository repository;

    @Test
    @Transactional
    void insertIfNew_blocksASecondSingletonEvent_forTheSameUserAndType() {
        UUID userId = UUID.randomUUID();
        repository.insertIfNew(userId, "FIRST_GOAL_CREATED", "STARTING", "LANDMARK", true,
                null, "Started your first goal", null, Instant.now());
        repository.insertIfNew(userId, "FIRST_GOAL_CREATED", "STARTING", "LANDMARK", true,
                null, "Started your first goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(1);
    }

    @Test
    @Transactional
    void insertIfNew_allowsTheSameEventType_forTwoDifferentReferenceIds() {
        UUID userId = UUID.randomUUID();
        UUID goalA = UUID.randomUUID();
        UUID goalB = UUID.randomUUID();
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalA, "Completed a goal", null, Instant.now());
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalB, "Completed a goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(2);
    }

    @Test
    @Transactional
    void insertIfNew_blocksASecondEvent_forTheSameUserTypeAndReferenceId() {
        UUID userId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalId, "Completed a goal", null, Instant.now());
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalId, "Completed a goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(1);
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw verify -Dit.test=TimelineEventRepositoryIT -DfailIfNoTests=false` (from `backend/`)
Expected: FAIL (compile error) — `TimelineEventRepository` does not exist yet.

- [ ] **Step 5: Write the repository**

```java
// backend/src/main/java/com/finora/timeline/TimelineEventRepository.java
package com.finora.timeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    List<TimelineEvent> findByUserIdOrderByOccurredAtDesc(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Idempotent by construction (ON CONFLICT DO NOTHING against the two partial unique
     *  indexes from V193), not check-then-insert -- a check-then-insert here would hit the
     *  exact "the INSERT is deferred to flush at commit" trap BudgetService.upsert's own doc
     *  comment already documents for this codebase: a try/catch around a plain save() cannot
     *  observe the constraint violation in time to react to it. This also means a call site
     *  never needs to catch anything -- a duplicate call is simply a no-op. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO timeline_events
            (id, user_id, event_type, bucket, importance, permanent, reference_id, title, detail, occurred_at, created_at)
        VALUES
            (gen_random_uuid(), :userId, :eventType, :bucket, :importance, :permanent, :referenceId, :title, :detail, :occurredAt, now())
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    void insertIfNew(@Param("userId") UUID userId, @Param("eventType") String eventType,
                      @Param("bucket") String bucket, @Param("importance") String importance,
                      @Param("permanent") boolean permanent, @Param("referenceId") UUID referenceId,
                      @Param("title") String title, @Param("detail") String detail,
                      @Param("occurredAt") Instant occurredAt);
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw verify -Dit.test=TimelineEventRepositoryIT -DfailIfNoTests=false` (from `backend/`)
Expected: `Tests run: 3, Failures: 0, Errors: 0` (check `target/surefire-reports/com.finora.timeline.TimelineEventRepositoryIT.txt` — failsafe writes its reports into surefire's report directory in this repo's pom.xml, not `target/failsafe-reports/`)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V193__timeline_events.sql \
        backend/src/main/java/com/finora/timeline/TimelineEvent.java \
        backend/src/main/java/com/finora/timeline/TimelineEventRepository.java \
        backend/src/test/java/com/finora/timeline/TimelineEventRepositoryIT.java
git commit -m "feat(backend): add TimelineEvent entity, migration, and idempotent repository"
```

**Note:** this task's commit message uses `feat(backend)`, not `feat(timeline)` — this repo's
commitlint config restricts `scope` to a fixed enum (`backend`, `frontend`, `admin-portal`,
`web`, `mobile`, `mobile-api`, `shared`, `transactions`, `accounts`, `budgets`, `goals`,
`imports`, `analytics`, `reports`, `rules`, `settings`, `users`, `auth`, `security`, `support`,
`db`, `infra`, `ci`, `docs`, `deps`) that does not include `timeline` or `wrapped` — every commit
message in the remaining tasks below should use `backend` or `frontend` (matching whichever
side of the stack the task touches), not the scope name shown.

---

### Task 2: `TimelineEventType` catalog, `TimelineEventDto`, and `TimelineEventService`

**Files:**
- Create: `backend/src/main/java/com/finora/timeline/TimelineEventType.java`
- Create: `backend/src/main/java/com/finora/dto/TimelineEventDto.java`
- Create: `backend/src/main/java/com/finora/timeline/TimelineEventService.java`
- Test: `backend/src/test/java/com/finora/timeline/TimelineEventServiceTest.java`

**Interfaces:**
- Consumes: `TimelineEventRepository` (Task 1).
- Produces: `TimelineEventType` (constants: `FIRST_GOAL_CREATED`, `FIRST_BUDGET_CREATED`, `FIRST_IMPORT`, `GOAL_COMPLETED`, `GOAL_PROGRESS_50`, `NET_WORTH_10K`, `NET_WORTH_100K` — each mapped to a fixed `bucket`/`importance`/`permanent` triple); `TimelineEventDto(String eventType, String bucket, String importance, boolean permanent, String title, String detail, Instant occurredAt)`; `TimelineEventService.record(UUID userId, String eventType, UUID referenceId, String title, String detail, Instant occurredAt)`; `TimelineEventService.listForUser(UUID userId): List<TimelineEventDto>`.

- [ ] **Step 1: Write the event type catalog**

```java
// backend/src/main/java/com/finora/timeline/TimelineEventType.java
package com.finora.timeline;

import java.util.Map;

/** Single source of truth for which (bucket, importance, permanent) triple a given event type
 *  carries -- see design spec §4.1-§4.3. Callers pass only the event type constant;
 *  TimelineEventService looks up the rest here so a caller can never mismatch a type against
 *  the wrong bucket/importance. */
public final class TimelineEventType {

    public static final String FIRST_GOAL_CREATED = "FIRST_GOAL_CREATED";
    public static final String FIRST_BUDGET_CREATED = "FIRST_BUDGET_CREATED";
    public static final String FIRST_IMPORT = "FIRST_IMPORT";
    public static final String GOAL_COMPLETED = "GOAL_COMPLETED";
    public static final String GOAL_PROGRESS_50 = "GOAL_PROGRESS_50";
    public static final String NET_WORTH_10K = "NET_WORTH_10K";
    public static final String NET_WORTH_100K = "NET_WORTH_100K";

    public record Definition(String bucket, String importance, boolean permanent) {}

    private static final Map<String, Definition> DEFINITIONS = Map.of(
            FIRST_GOAL_CREATED, new Definition("STARTING", "LANDMARK", true),
            FIRST_BUDGET_CREATED, new Definition("STARTING", "MAJOR", true),
            FIRST_IMPORT, new Definition("STARTING", "MAJOR", true),
            GOAL_COMPLETED, new Definition("TRANSFORMATION", "LANDMARK", true),
            GOAL_PROGRESS_50, new Definition("PROGRESS", "MINOR", false),
            NET_WORTH_10K, new Definition("TRANSFORMATION", "LANDMARK", true),
            NET_WORTH_100K, new Definition("TRANSFORMATION", "LANDMARK", true)
    );

    public static Definition definitionOf(String eventType) {
        Definition d = DEFINITIONS.get(eventType);
        if (d == null) throw new IllegalArgumentException("Unknown timeline event type: " + eventType);
        return d;
    }

    private TimelineEventType() {}
}
```

- [ ] **Step 2: Write the DTO**

```java
// backend/src/main/java/com/finora/dto/TimelineEventDto.java
package com.finora.dto;

import java.time.Instant;

public record TimelineEventDto(String eventType, String bucket, String importance,
                                boolean permanent, String title, String detail, Instant occurredAt) {}
```

- [ ] **Step 3: Write the failing service test**

```java
// backend/src/test/java/com/finora/timeline/TimelineEventServiceTest.java
package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TimelineEventServiceTest {

    private TimelineEventRepository repository;
    private TimelineEventService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(TimelineEventRepository.class);
        service = new TimelineEventService(repository);
    }

    @Test
    void record_looksUpBucketImportanceAndPermanent_fromTheEventTypeCatalog_andInsertsIdempotently() {
        Instant occurredAt = Instant.parse("2026-03-01T00:00:00Z");

        service.record(userId, TimelineEventType.GOAL_COMPLETED, UUID.randomUUID(),
                "Completed Emergency Fund", null, occurredAt);

        verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
                eq("TRANSFORMATION"), eq("LANDMARK"), eq(true), any(), eq("Completed Emergency Fund"),
                isNull(), eq(occurredAt));
    }

    @Test
    void listForUser_returnsEventsNewestFirst_asDtos() {
        TimelineEvent e = new TimelineEvent();
        e.setEventType(TimelineEventType.GOAL_COMPLETED);
        e.setBucket("TRANSFORMATION");
        e.setImportance("LANDMARK");
        e.setPermanent(true);
        e.setTitle("Completed Emergency Fund");
        e.setOccurredAt(Instant.parse("2026-03-01T00:00:00Z"));
        when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(e));

        List<TimelineEventDto> result = service.listForUser(userId);

        assertThat(result).containsExactly(new TimelineEventDto(
                TimelineEventType.GOAL_COMPLETED, "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.parse("2026-03-01T00:00:00Z")));
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw test -Dtest=TimelineEventServiceTest` (from `backend/`)
Expected: FAIL — `TimelineEventService` does not exist yet.

- [ ] **Step 5: Write the service**

```java
// backend/src/main/java/com/finora/timeline/TimelineEventService.java
package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TimelineEventService {

    private final TimelineEventRepository repository;

    public TimelineEventService(TimelineEventRepository repository) {
        this.repository = repository;
    }

    /** Called from GoalService/BudgetService/NetWorthService right after their own write.
     *  Idempotent (see TimelineEventRepository.insertIfNew's own doc comment) -- safe to call
     *  on every goal contribution even though most calls will be no-ops for singleton event
     *  types. Runs in the caller's existing transaction deliberately: if the caller's write
     *  rolls back, the timeline entry should roll back with it. */
    @Transactional
    public void record(UUID userId, String eventType, UUID referenceId, String title, String detail, Instant occurredAt) {
        TimelineEventType.Definition def = TimelineEventType.definitionOf(eventType);
        repository.insertIfNew(userId, eventType, def.bucket(), def.importance(), def.permanent(),
                referenceId, title, detail, occurredAt);
    }

    @Transactional(readOnly = true)
    public List<TimelineEventDto> listForUser(UUID userId) {
        return repository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(e -> new TimelineEventDto(e.getEventType(), e.getBucket(), e.getImportance(),
                        e.isPermanent(), e.getTitle(), e.getDetail(), e.getOccurredAt()))
                .toList();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=TimelineEventServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/timeline/TimelineEventType.java \
        backend/src/main/java/com/finora/dto/TimelineEventDto.java \
        backend/src/main/java/com/finora/timeline/TimelineEventService.java \
        backend/src/test/java/com/finora/timeline/TimelineEventServiceTest.java
git commit -m "feat(backend): add event type catalog and TimelineEventService"
```

---

### Task 3: Lazy backfill of Starting-bucket history for existing users

**Files:**
- Modify: `backend/src/main/java/com/finora/timeline/TimelineEventService.java`
- Modify: `backend/src/main/java/com/finora/timeline/TimelineEventRepository.java` (no change needed — reuses `existsByUserId` from Task 1)
- Test: `backend/src/test/java/com/finora/timeline/TimelineEventServiceTest.java`

**Interfaces:**
- Consumes: `GoalRepository.findEarliestCreatedAtEverEpochMillis`/`findEarliestCompletedAtEverEpochMillis` (existing), `BudgetRepository.findEarliestCreatedAtEverEpochMillis` (existing), `StatementImportRepository.findEarliestImportedAtEverEpochMillis` (existing) — the same three "earliest ever" queries `FinancialJourneyService` used, being repurposed rather than duplicated.
- Produces: `TimelineEventService.listForUser` now backfills on first call for a user with zero rows.

- [ ] **Step 1: Write the failing test**

```java
// add to TimelineEventServiceTest.java
@Test
void listForUser_backfillsStartingMilestones_onFirstCallForAUserWithNoTimelineRowsYet() {
    var goalRepository = mock(com.finora.goals.GoalRepository.class);
    var budgetRepository = mock(com.finora.repository.BudgetRepository.class);
    var statementImportRepository = mock(com.finora.repository.StatementImportRepository.class);
    service = new TimelineEventService(repository, goalRepository, budgetRepository, statementImportRepository);

    when(repository.existsByUserId(userId)).thenReturn(false);
    when(goalRepository.findEarliestCreatedAtEverEpochMillis(userId))
            .thenReturn(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    when(budgetRepository.findEarliestCreatedAtEverEpochMillis(userId)).thenReturn(null);
    when(statementImportRepository.findEarliestImportedAtEverEpochMillis(userId)).thenReturn(null);
    when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

    service.listForUser(userId);

    verify(repository).insertIfNew(eq(userId), eq(TimelineEventType.FIRST_GOAL_CREATED),
            eq("STARTING"), eq("LANDMARK"), eq(true), isNull(), any(), isNull(),
            eq(Instant.parse("2026-01-01T00:00:00Z")));
    verify(repository, never()).insertIfNew(eq(userId), eq(TimelineEventType.FIRST_BUDGET_CREATED),
            any(), any(), anyBoolean(), any(), any(), any(), any());
}

@Test
void listForUser_skipsBackfill_whenTheUserAlreadyHasTimelineRows() {
    when(repository.existsByUserId(userId)).thenReturn(true);
    when(repository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());

    service.listForUser(userId);

    verify(repository, never()).insertIfNew(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=TimelineEventServiceTest` (from `backend/`)
Expected: FAIL — constructor signature doesn't match yet.

- [ ] **Step 3: Update the service**

```java
// backend/src/main/java/com/finora/timeline/TimelineEventService.java
package com.finora.timeline;

import com.finora.dto.TimelineEventDto;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TimelineEventService {

    private final TimelineEventRepository repository;
    private final GoalRepository goalRepository;
    private final BudgetRepository budgetRepository;
    private final StatementImportRepository statementImportRepository;

    public TimelineEventService(TimelineEventRepository repository, GoalRepository goalRepository,
                                 BudgetRepository budgetRepository,
                                 StatementImportRepository statementImportRepository) {
        this.repository = repository;
        this.goalRepository = goalRepository;
        this.budgetRepository = budgetRepository;
        this.statementImportRepository = statementImportRepository;
    }

    @Transactional
    public void record(UUID userId, String eventType, UUID referenceId, String title, String detail, Instant occurredAt) {
        TimelineEventType.Definition def = TimelineEventType.definitionOf(eventType);
        repository.insertIfNew(userId, eventType, def.bucket(), def.importance(), def.permanent(),
                referenceId, title, detail, occurredAt);
    }

    /** Materializes Starting-bucket history the first time a given user's timeline is read, from
     *  the same "earliest ever" native queries FinancialJourneyService used (see Task 7, which
     *  removes that now-superseded service) -- so an existing user's history isn't empty just
     *  because the timeline table didn't exist yet when they signed up. Guarded by
     *  existsByUserId so this only ever runs once per user; record()'s own idempotency (Task 1)
     *  is a second, redundant safety net if two requests race this on the same user. */
    @Transactional
    public List<TimelineEventDto> listForUser(UUID userId) {
        if (!repository.existsByUserId(userId)) {
            backfillStartingMilestones(userId);
        }
        return repository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(e -> new TimelineEventDto(e.getEventType(), e.getBucket(), e.getImportance(),
                        e.isPermanent(), e.getTitle(), e.getDetail(), e.getOccurredAt()))
                .toList();
    }

    private void backfillStartingMilestones(UUID userId) {
        Long firstGoal = goalRepository.findEarliestCreatedAtEverEpochMillis(userId);
        if (firstGoal != null) {
            record(userId, TimelineEventType.FIRST_GOAL_CREATED, null, "Started your first goal",
                    null, Instant.ofEpochMilli(firstGoal));
        }
        Long firstBudget = budgetRepository.findEarliestCreatedAtEverEpochMillis(userId);
        if (firstBudget != null) {
            record(userId, TimelineEventType.FIRST_BUDGET_CREATED, null, "Created your first budget",
                    null, Instant.ofEpochMilli(firstBudget));
        }
        Long firstImport = statementImportRepository.findEarliestImportedAtEverEpochMillis(userId);
        if (firstImport != null) {
            record(userId, TimelineEventType.FIRST_IMPORT, null, "Imported your first statement",
                    null, Instant.ofEpochMilli(firstImport));
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=TimelineEventServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/timeline/TimelineEventService.java \
        backend/src/test/java/com/finora/timeline/TimelineEventServiceTest.java
git commit -m "feat(backend): backfill Starting-bucket history on first read for existing users"
```

---

### Task 4: Wire goal milestones (`FIRST_GOAL_CREATED`, `GOAL_COMPLETED`, `GOAL_PROGRESS_50`)

**Files:**
- Modify: `backend/src/main/java/com/finora/goals/GoalService.java`
- Test: `backend/src/test/java/com/finora/goals/GoalServiceTest.java`

**Interfaces:**
- Consumes: `TimelineEventService.record(...)` (Task 2/3).

- [ ] **Step 1: Write the failing tests**

```java
// add to GoalServiceTest.java — adjust the existing setUp()'s `service = new GoalService(...)`
// call to pass a mocked TimelineEventService as the new final constructor argument, then add:

@Test
void create_recordsGoalCompleted_whenTheInitialCurrentAmountAlreadyMeetsTheTarget() {
    var req = new GoalDto.CreateRequest("Emergency Fund", BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), null);
    when(userRepository.findById(userId)).thenReturn(Optional.of(userWithZone("Asia/Kolkata")));
    when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    GoalDto result = service.create(userId, req);

    verify(timelineEventService).record(eq(userId), eq(TimelineEventType.GOAL_COMPLETED),
            eq(result.id()), anyString(), isNull(), any());
}

@Test
void create_recordsFirstGoalCreated_unconditionally() {
    var req = new GoalDto.CreateRequest("Trip", BigDecimal.valueOf(50000), BigDecimal.ZERO, null);
    when(userRepository.findById(userId)).thenReturn(Optional.of(userWithZone("Asia/Kolkata")));
    when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(userId, req);

    verify(timelineEventService).record(eq(userId), eq(TimelineEventType.FIRST_GOAL_CREATED),
            isNull(), anyString(), isNull(), any());
}

@Test
void addContribution_recordsGoalProgress50_whenCrossingHalfway_butNotAgainOnASecondCrossing() {
    Goal goal = goalWith(BigDecimal.valueOf(1000), BigDecimal.valueOf(400)); // 40% before
    when(goalRepository.findById(goal.getId())).thenReturn(Optional.of(goal));
    when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(userRepository.findById(userId)).thenReturn(Optional.of(userWithZone("Asia/Kolkata")));

    service.addContribution(userId, goal.getId(), BigDecimal.valueOf(200)); // -> 60%, crosses 50%

    verify(timelineEventService).record(eq(userId), eq(TimelineEventType.GOAL_PROGRESS_50),
            eq(goal.getId()), anyString(), isNull(), any());
}
```

(These tests assume `goalWith(target, current)` and `userWithZone(zone)` helper methods already exist in `GoalServiceTest.java` in an equivalent form — if they don't, add them as small private helpers that construct a `Goal`/`User` with the given fields via `ReflectionTestUtils.setField`, following the existing test file's own pattern for constructing entities with a generated id.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=GoalServiceTest` (from `backend/`)
Expected: FAIL — `GoalService` constructor doesn't accept a `TimelineEventService` yet, and the milestone types don't fire.

- [ ] **Step 3: Update `GoalService`**

```java
// GoalService.java — add the import and constructor field
import com.finora.timeline.TimelineEventService;
import com.finora.timeline.TimelineEventType;

// ...
private final TimelineEventService timelineEventService;

public GoalService(GoalRepository goalRepository, GoalContributionRepository contributionRepository,
                    UserRepository userRepository, AuditService auditService,
                    TimelineEventService timelineEventService) {
    this.goalRepository = goalRepository;
    this.contributionRepository = contributionRepository;
    this.userRepository = userRepository;
    this.auditService = auditService;
    this.timelineEventService = timelineEventService;
}
```

```java
// create() — after the existing auditService.record(...) call, before the return:
timelineEventService.record(userId, TimelineEventType.FIRST_GOAL_CREATED, null,
        "Started your first goal", null, Instant.now());
if (saved.getCompletedAt() != null) {
    timelineEventService.record(userId, TimelineEventType.GOAL_COMPLETED, saved.getId(),
            "Completed " + saved.getName(), null, saved.getCompletedAt());
}
```

```java
// addContribution() — capture the before/after percentage around the existing
// markCompletedIfReached(g) call:
boolean crossedHalfway = crossedThreshold(g.getCurrentAmount().subtract(amount), newAmount, g.getTargetAmount(), 0.5);
Instant completedAtBefore = g.getCompletedAt();
markCompletedIfReached(g);
Goal saved = goalRepository.save(g);

GoalContribution gc = new GoalContribution();
gc.setGoalId(goalId);
gc.setAmount(amount);
gc.setContributedAt(LocalDate.now(safeZoneId(userId)));
contributionRepository.save(gc);

auditService.record(userId, "GOAL_CONTRIBUTION_ADDED", "Goal", goalId, Map.of("amount", amount));
if (crossedHalfway) {
    timelineEventService.record(userId, TimelineEventType.GOAL_PROGRESS_50, goalId,
            "Halfway to " + saved.getName(), null, Instant.now());
}
if (completedAtBefore == null && saved.getCompletedAt() != null) {
    timelineEventService.record(userId, TimelineEventType.GOAL_COMPLETED, goalId,
            "Completed " + saved.getName(), null, saved.getCompletedAt());
}
```

```java
// new private helper, alongside markCompletedIfReached:
/** True only the FIRST time current crosses fraction*target -- before <= threshold < after,
 *  strictly, so a contribution that starts already past 50% (e.g. a second contribution in the
 *  same session) never re-fires this. */
private boolean crossedThreshold(BigDecimal before, BigDecimal after, BigDecimal target, double fraction) {
    if (target.compareTo(BigDecimal.ZERO) <= 0) return false;
    BigDecimal thresholdAmount = target.multiply(BigDecimal.valueOf(fraction));
    return before.compareTo(thresholdAmount) < 0 && after.compareTo(thresholdAmount) >= 0;
}
```

- [ ] **Step 4: Update every other `new GoalService(...)` call site**

Run: `grep -rn "new GoalService(" backend/src/main/java backend/src/test/java`

Add the `TimelineEventService` bean (constructor-injected the same way `AuditService` already is; no `@Bean` config needed since it's a plain `@Service`) to each production call site found, and a `mock(TimelineEventService.class)` to each test call site found.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=GoalServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 6: Run the full goals test suite for regressions**

Run: `./mvnw test -Dtest="com.finora.goals.*"` (from `backend/`)
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/goals/GoalService.java \
        backend/src/test/java/com/finora/goals/GoalServiceTest.java
git commit -m "feat(backend): fire FIRST_GOAL_CREATED/GOAL_COMPLETED/GOAL_PROGRESS_50 from GoalService"
```

---

### Task 5: Wire `FIRST_BUDGET_CREATED`

**Files:**
- Modify: `backend/src/main/java/com/finora/budgets/BudgetService.java`
- Test: `backend/src/test/java/com/finora/budgets/BudgetServiceTest.java`

**Interfaces:**
- Consumes: `TimelineEventService.record(...)`.

- [ ] **Step 1: Write the failing test**

```java
// add to BudgetServiceTest.java — adjust setUp() to pass a mocked TimelineEventService

@Test
void upsert_recordsFirstBudgetCreated() {
    var req = new BudgetDto.UpsertRequest("Dining", BigDecimal.valueOf(5000));
    when(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, "Dining")).thenReturn(List.of());
    when(categoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(budgetRepository.findByUserIdAndCategoryId(any(), any())).thenReturn(Optional.empty());
    when(budgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    budgetService.upsert(userId, req);

    verify(timelineEventService).record(eq(userId), eq(TimelineEventType.FIRST_BUDGET_CREATED),
            isNull(), anyString(), isNull(), any());
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BudgetServiceTest` (from `backend/`)
Expected: FAIL — constructor mismatch.

- [ ] **Step 3: Update `BudgetService`**

```java
// BudgetService.java — add the import, field, and constructor argument
import com.finora.timeline.TimelineEventService;
import com.finora.timeline.TimelineEventType;

private final TimelineEventService timelineEventService;

public BudgetService(BudgetRepository budgetRepository, CategoryRepository categoryRepository,
                      TransactionRepository transactionRepository, AccountRepository accountRepository,
                      UserRepository userRepository, AuditService auditService,
                      TransactionGraphService transactionGraphService,
                      TimelineEventService timelineEventService) {
    this.budgetRepository = budgetRepository;
    this.categoryRepository = categoryRepository;
    this.transactionRepository = transactionRepository;
    this.accountRepository = accountRepository;
    this.userRepository = userRepository;
    this.transactionGraphService = transactionGraphService;
    this.auditService = auditService;
    this.timelineEventService = timelineEventService;
}
```

```java
// upsert() — right after the existing auditService.record("BUDGET_UPSERTED", ...) call:
timelineEventService.record(userId, TimelineEventType.FIRST_BUDGET_CREATED, null,
        "Created your first budget", null, java.time.Instant.now());
```

- [ ] **Step 4: Update every other `new BudgetService(...)` call site**

Run: `grep -rn "new BudgetService(" backend/src/main/java backend/src/test/java`

Same treatment as Task 4 Step 4.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest="com.finora.budgets.*"` (from `backend/`)
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/budgets/BudgetService.java \
        backend/src/test/java/com/finora/budgets/BudgetServiceTest.java
git commit -m "feat(backend): fire FIRST_BUDGET_CREATED from BudgetService"
```

---

### Task 6: Wire `NET_WORTH_10K` / `NET_WORTH_100K`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/NetWorthService.java`
- Test: `backend/src/test/java/com/finora/service/NetWorthServiceTest.java`

**Interfaces:**
- Consumes: `TimelineEventService.record(...)`.

- [ ] **Step 1: Write the failing tests**

```java
// add to NetWorthServiceTest.java (create the file, following the constructor-mock pattern
// every other service test in this codebase uses, if it doesn't already exist)

@Test
void snapshotForTodayOnly_recordsNetWorth10k_whenCrossingUpwardForTheFirstTime() {
    UUID userId = UUID.randomUUID();
    when(accountRepository.findByUserId(userId)).thenReturn(List.of(savingsAccountWithBalance(BigDecimal.valueOf(12000))));
    when(snapshotRepository.findTopByUserIdOrderBySnapshotDateDesc(userId)).thenReturn(Optional.empty());

    service.snapshotForTodayOnly(userId, "Asia/Kolkata");

    verify(timelineEventService).record(eq(userId), eq(TimelineEventType.NET_WORTH_10K),
            isNull(), anyString(), isNull(), any());
}

@Test
void snapshotForTodayOnly_doesNotRecordNetWorth10k_whenAlreadyAboveItYesterday() {
    UUID userId = UUID.randomUUID();
    when(accountRepository.findByUserId(userId)).thenReturn(List.of(savingsAccountWithBalance(BigDecimal.valueOf(12000))));
    var yesterday = mock(com.finora.entity.NetWorthSnapshot.class);
    when(yesterday.getNetWorth()).thenReturn(BigDecimal.valueOf(11000));
    when(snapshotRepository.findTopByUserIdOrderBySnapshotDateDesc(userId)).thenReturn(Optional.of(yesterday));

    service.snapshotForTodayOnly(userId, "Asia/Kolkata");

    verify(timelineEventService, never()).record(any(), eq(TimelineEventType.NET_WORTH_10K),
            any(), any(), any(), any());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=NetWorthServiceTest` (from `backend/`)
Expected: FAIL — `snapshotRepository.findTopByUserIdOrderBySnapshotDateDesc` and the constructor argument don't exist yet.

- [ ] **Step 3: Add the "yesterday's net worth" query**

```java
// NetWorthSnapshotRepository.java — add:
Optional<NetWorthSnapshot> findTopByUserIdOrderBySnapshotDateDesc(UUID userId);
```

- [ ] **Step 4: Update `NetWorthService`**

```java
// NetWorthService.java — add the import, field, and constructor argument
import com.finora.timeline.TimelineEventService;
import com.finora.timeline.TimelineEventType;
import java.math.BigDecimal;

private final TimelineEventService timelineEventService;

public NetWorthService(AccountRepository accountRepository, NetWorthSnapshotRepository snapshotRepository,
                        UserRepository userRepository, TimelineEventService timelineEventService) {
    this.accountRepository = accountRepository;
    this.snapshotRepository = snapshotRepository;
    this.userRepository = userRepository;
    this.timelineEventService = timelineEventService;
}
```

```java
// snapshotForTodayOnly() — after the existing snapshotRepository.upsertForToday(...) call:
BigDecimal priorNetWorth = snapshotRepository.findTopByUserIdOrderBySnapshotDateDesc(userId)
        .map(com.finora.entity.NetWorthSnapshot::getNetWorth).orElse(BigDecimal.ZERO);
recordNetWorthMilestoneIfCrossed(userId, priorNetWorth, netWorth, BigDecimal.valueOf(10_000),
        TimelineEventType.NET_WORTH_10K, "Saved your first ₹10,000");
recordNetWorthMilestoneIfCrossed(userId, priorNetWorth, netWorth, BigDecimal.valueOf(100_000),
        TimelineEventType.NET_WORTH_100K, "Reached ₹1,00,000 saved");
```

```java
// new private helper:
/** Fires the given milestone the first time net worth crosses `threshold` upward. Reads
 *  yesterday's snapshot (queried BEFORE today's upsertForToday overwrote it, so this must run
 *  after that call but compare against the value read before it) rather than any in-memory
 *  running max, so a user who later drops back below the threshold and crosses it again never
 *  re-fires this -- record()'s own idempotency (a singleton, reference_id-null event type) is
 *  the actual guarantee; this check is just what decides whether to attempt the call at all. */
private void recordNetWorthMilestoneIfCrossed(UUID userId, BigDecimal before, BigDecimal after,
                                               BigDecimal threshold, String eventType, String title) {
    if (before.compareTo(threshold) < 0 && after.compareTo(threshold) >= 0) {
        timelineEventService.record(userId, eventType, null, title, null, Instant.now());
    }
}
```

Note: `priorNetWorth` must be captured before `snapshotRepository.upsertForToday(...)` runs (that call overwrites today's row, not yesterday's, so ordering only matters if this is ever called twice in the same day — reading it before the upsert is the safer, always-correct order; place the `findTopByUserIdOrderBySnapshotDateDesc` read immediately before the `upsertForToday` call, not after, and pass its result into the milestone check after the upsert).

- [ ] **Step 5: Update every other `new NetWorthService(...)` call site**

Run: `grep -rn "new NetWorthService(" backend/src/main/java backend/src/test/java`

Same treatment as Task 4 Step 4.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=NetWorthServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/NetWorthService.java \
        backend/src/main/java/com/finora/repository/NetWorthSnapshotRepository.java \
        backend/src/test/java/com/finora/service/NetWorthServiceTest.java
git commit -m "feat(backend): fire NET_WORTH_10K/NET_WORTH_100K from NetWorthService"
```

---

### Task 7: Remove the dead `FinancialJourneyService` and its endpoint/client

**Files:**
- Delete: `backend/src/main/java/com/finora/service/FinancialJourneyService.java`
- Delete: `backend/src/main/java/com/finora/dto/FinancialJourneyDto.java`
- Delete: `backend/src/test/java/com/finora/service/FinancialJourneyServiceTest.java`
- Modify: `backend/src/main/java/com/finora/controller/DashboardController.java` (remove the `journey()` endpoint and `FinancialJourneyService` field/import)
- Modify: `frontend/src/api/endpoints.ts` (remove the `journey` client method)
- Modify: `frontend/src/types/index.ts` (remove `FinancialJourney`/`JourneyMilestone`)
- Modify: `backend/src/main/java/com/finora/goals/GoalRepository.java`, `backend/src/main/java/com/finora/repository/BudgetRepository.java` (update doc comments that name `FinancialJourneyService` — see spec's correction note)

**Interfaces:**
- Produces: nothing new — this task only removes now-superseded code. `GoalRepository.findEarliestCreatedAtEverEpochMillis`/`findEarliestCompletedAtEverEpochMillis` and `BudgetRepository.findEarliestCreatedAtEverEpochMillis` are kept (Task 3 now calls them).

- [ ] **Step 1: Confirm nothing else references the doomed files**

Run: `grep -rln "FinancialJourney" backend/src frontend/src mobile/src 2>/dev/null`
Expected output: only the six files listed above, plus `frontend/src/api/generated-types.ts` (regenerated in Step 4, not hand-edited).

- [ ] **Step 2: Delete the backend files**

```bash
git rm backend/src/main/java/com/finora/service/FinancialJourneyService.java
git rm backend/src/main/java/com/finora/dto/FinancialJourneyDto.java
git rm backend/src/test/java/com/finora/service/FinancialJourneyServiceTest.java
```

- [ ] **Step 3: Update `DashboardController`**

```java
// DashboardController.java — remove the FinancialJourneyDto import, the
// FinancialJourneyService import and field, its constructor argument, and the whole
// @GetMapping("/journey") method. Resulting file:
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DashboardSummaryDto;
import com.finora.security.CurrentUser;
import com.finora.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService dashboardService, CurrentUser currentUser) {
        this.dashboardService = dashboardService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> summary() {
        return ApiResponse.ok(dashboardService.summarize(currentUser.id()));
    }
}
```

- [ ] **Step 4: Update the doc comments in `GoalRepository`/`BudgetRepository` that name the removed service**

In `GoalRepository.java`, change the two doc comments reading `` `FinancialJourneyService`'s FIRST_GOAL milestone `` and `` `FinancialJourneyService`'s FIRST_GOAL_ACHIEVED milestone `` to `` `TimelineEventService`'s Starting-bucket backfill (see TimelineEventService.backfillStartingMilestones) ``. Same edit in `BudgetRepository.java` for its one matching comment.

- [ ] **Step 5: Remove the frontend client method and types**

```typescript
// frontend/src/api/endpoints.ts — remove this line from the dashboard API object:
  journey: () => api.get<FinancialJourney>('/dashboard/journey').then((r) => r.data),
```

Also remove `FinancialJourney` from the destructured import list at the top of the file (the `import { ..., FinancialJourney, ... } from '../types'` line).

```typescript
// frontend/src/types/index.ts — remove the JourneyMilestone interface and the
// FinancialJourney interface (and the comment block immediately above them referencing
// FinancialJourneyDto's constants).
```

- [ ] **Step 6: Run the backend build to confirm no dangling references**

Run: `./mvnw compile test-compile` (from `backend/`)
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run the frontend typecheck**

Run: `npm run typecheck` (from `frontend/`)
Expected: no errors

- [ ] **Step 8: Commit**

```bash
git add -A -- backend/src/main/java/com/finora/controller/DashboardController.java \
              backend/src/main/java/com/finora/goals/GoalRepository.java \
              backend/src/main/java/com/finora/repository/BudgetRepository.java \
              frontend/src/api/endpoints.ts frontend/src/types/index.ts
git commit -m "refactor(backend): remove dead FinancialJourneyService, superseded by TimelineEventService"
```

---

### Task 8: `TimelineController` REST API + OpenAPI regen

**Files:**
- Create: `backend/src/main/java/com/finora/controller/TimelineController.java`
- Test: `backend/src/test/java/com/finora/controller/TimelineControllerTest.java` (follow this codebase's existing controller test pattern — check `DashboardController`'s own test, if one exists, for the `MockMvc`/`@WebMvcTest` shape to mirror; otherwise a plain unit test constructing the controller with a mocked `TimelineEventService` and `CurrentUser` is sufficient, matching how `FinancialJourneyServiceTest` tested at the service layer one level down)
- Modify: `backend/openapi/openapi.json` (regenerated, not hand-edited)
- Modify: `frontend/src/api/generated-types.ts` (regenerated, not hand-edited)

**Interfaces:**
- Consumes: `TimelineEventService.listForUser(UUID)` (Task 2/3).
- Produces: `GET /api/v1/timeline` → `ApiResponse<List<TimelineEventDto>>`.

- [ ] **Step 1: Write the controller**

```java
// backend/src/main/java/com/finora/controller/TimelineController.java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.TimelineEventDto;
import com.finora.security.CurrentUser;
import com.finora.timeline.TimelineEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/timeline")
public class TimelineController {

    private final TimelineEventService timelineEventService;
    private final CurrentUser currentUser;

    public TimelineController(TimelineEventService timelineEventService, CurrentUser currentUser) {
        this.timelineEventService = timelineEventService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<TimelineEventDto>> list() {
        return ApiResponse.ok(timelineEventService.listForUser(currentUser.id()));
    }
}
```

- [ ] **Step 2: Write the controller test**

```java
// backend/src/test/java/com/finora/controller/TimelineControllerTest.java
package com.finora.controller;

import com.finora.dto.TimelineEventDto;
import com.finora.security.CurrentUser;
import com.finora.timeline.TimelineEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimelineControllerTest {

    @Test
    void list_returnsTheCurrentUsersTimelineEvents() {
        TimelineEventService service = mock(TimelineEventService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        List<TimelineEventDto> events = List.of(new TimelineEventDto(
                "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.now()));
        when(service.listForUser(userId)).thenReturn(events);

        var controller = new TimelineController(service, currentUser);

        assertThat(controller.list().data()).isEqualTo(events);
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `./mvnw test -Dtest=TimelineControllerTest` (from `backend/`)
Expected: PASS (no failing-first step needed here — the controller is a thin, direct pass-through with no branching logic to drive out via a red step)

- [ ] **Step 4: Regenerate the OpenAPI spec and frontend types**

Run: `cd backend && ./scripts/generate-openapi-spec.sh`
Run: `cd frontend && npm run generate:types`
Expected: `backend/openapi/openapi.json` gains the `/api/v1/timeline` path (and loses `/api/v1/dashboard/journey`, from Task 7); `frontend/src/api/generated-types.ts` reflects both changes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/controller/TimelineController.java \
        backend/src/test/java/com/finora/controller/TimelineControllerTest.java \
        backend/openapi/openapi.json frontend/src/api/generated-types.ts
git commit -m "feat(backend): add GET /api/v1/timeline endpoint"
```

---

### Task 9: Frontend — "My Journey" Dashboard widget

**Files:**
- Create: `frontend/src/components/JourneyWidget.tsx`
- Create: `frontend/src/components/JourneyWidget.test.tsx`
- Modify: `frontend/src/api/endpoints.ts` (add the `timeline` client method)
- Modify: `frontend/src/pages/Dashboard.tsx` (mount `<JourneyWidget />`)

**Interfaces:**
- Consumes: `GET /api/v1/timeline` (Task 8).
- Produces: `<JourneyWidget />`, mounted directly below `<ChecklistWidget />` in `Dashboard.tsx`.

- [ ] **Step 1: Add the API client method**

```typescript
// frontend/src/api/endpoints.ts — add alongside the (now-removed) journey() method's old spot,
// inside the same dashboardApi object:
  timeline: () => api.get<TimelineEvent[]>('/timeline').then((r) => r.data),
```

Add `TimelineEvent` to this file's type imports from `../types` (defined in Step 2).

- [ ] **Step 2: Add the type**

```typescript
// frontend/src/types/index.ts — add:
export interface TimelineEvent {
  eventType: string;
  bucket: 'STARTING' | 'CONSISTENCY' | 'PROGRESS' | 'TRANSFORMATION';
  importance: 'MINOR' | 'MAJOR' | 'LANDMARK';
  permanent: boolean;
  title: string;
  detail: string | null;
  occurredAt: string;
}
```

- [ ] **Step 3: Write the failing widget test**

```typescript
// frontend/src/components/JourneyWidget.test.tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { JourneyWidget } from './JourneyWidget';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { timeline: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('JourneyWidget', () => {
  it('shows the most recent Landmark event title', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'GOAL_COMPLETED', bucket: 'TRANSFORMATION', importance: 'LANDMARK', permanent: true,
        title: 'Completed Emergency Fund', detail: null, occurredAt: '2026-03-01T00:00:00Z' },
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<JourneyWidget />);

    expect(await screen.findByText('Completed Emergency Fund')).toBeInTheDocument();
  });

  it('falls back to the most recent Major event when there is no Landmark event yet', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<JourneyWidget />);

    expect(await screen.findByText('Created your first budget')).toBeInTheDocument();
  });

  it('renders nothing when the timeline is empty', () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([]);

    const { container } = renderWithClient(<JourneyWidget />);

    expect(container).toBeEmptyDOMElement();
  });
});
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `npm test -- JourneyWidget` (from `frontend/`)
Expected: FAIL — `JourneyWidget` does not exist yet.

- [ ] **Step 5: Write the widget**

```tsx
// frontend/src/components/JourneyWidget.tsx
import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { FinoraCard } from '../design-system';
import { dashboardApi } from '../api/endpoints';
import type { TimelineEvent } from '../types';

// Most recent Landmark event, falling back to the most recent Major event when the user has
// no Landmark event yet -- see the design spec's importance tiers (§4.3): Minor events never
// surface here, only in the full timeline list (a later task).
function mostRecentHighlight(events: TimelineEvent[]): TimelineEvent | undefined {
  return events.find((e) => e.importance === 'LANDMARK') ?? events.find((e) => e.importance === 'MAJOR');
}

export function JourneyWidget() {
  const { data } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const highlight = data ? mostRecentHighlight(data) : undefined;

  if (!highlight) return null;

  return (
    <FinoraCard padding="lg" className="mb-6">
      <div className="flex items-center gap-2 mb-2">
        <div className="w-8 h-8 rounded-full bg-primary-light flex items-center justify-center">
          <Sparkles size={15} className="text-primary" />
        </div>
        <h2 className="font-semibold text-ink">Your Journey</h2>
      </div>
      <p className="text-sm text-ink">{highlight.title}</p>
    </FinoraCard>
  );
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npm test -- JourneyWidget` (from `frontend/`)
Expected: PASS

- [ ] **Step 7: Mount it on the Dashboard**

```tsx
// frontend/src/pages/Dashboard.tsx — add the import near ChecklistWidget's:
import { JourneyWidget } from '../components/JourneyWidget';
```

```tsx
// Dashboard.tsx — right after the existing <ChecklistWidget /> line:
<ChecklistWidget />
<JourneyWidget />
```

- [ ] **Step 8: Run the Dashboard test suite for regressions**

Run: `npm test -- Dashboard` (from `frontend/`)
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/JourneyWidget.tsx frontend/src/components/JourneyWidget.test.tsx \
        frontend/src/api/endpoints.ts frontend/src/types/index.ts frontend/src/pages/Dashboard.tsx
git commit -m "feat(frontend): add My Journey Dashboard widget"
```

---

### Task 10: Frontend — full Timeline page

**Files:**
- Create: `frontend/src/pages/Timeline.tsx`
- Create: `frontend/src/pages/Timeline.test.tsx`
- Modify: `frontend/src/App.tsx` (add the route — check this file's existing route list for the exact pattern other authenticated pages like `Goals`/`Budgets` use, and mirror it)

**Interfaces:**
- Consumes: `dashboardApi.timeline()` (Task 9).
- Produces: a `/app/journey` route rendering every event, grouped by year, newest first.

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/pages/Timeline.test.tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { Timeline } from './Timeline';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { timeline: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('Timeline', () => {
  it('groups events by year, newest year first', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([
      { eventType: 'GOAL_COMPLETED', bucket: 'TRANSFORMATION', importance: 'LANDMARK', permanent: true,
        title: 'Completed Emergency Fund', detail: null, occurredAt: '2027-02-01T00:00:00Z' },
      { eventType: 'FIRST_BUDGET_CREATED', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
        title: 'Created your first budget', detail: null, occurredAt: '2026-01-01T00:00:00Z' },
    ]);

    renderWithClient(<Timeline />);

    const headings = await screen.findAllByRole('heading', { level: 3 });
    expect(headings.map((h) => h.textContent)).toEqual(['2027', '2026']);
  });

  it('shows an empty state when there are no events yet', async () => {
    vi.mocked(dashboardApi.timeline).mockResolvedValue([]);

    renderWithClient(<Timeline />);

    expect(await screen.findByText(/your journey starts here/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- Timeline` (from `frontend/`)
Expected: FAIL — `Timeline` page does not exist yet.

- [ ] **Step 3: Write the page**

```tsx
// frontend/src/pages/Timeline.tsx
import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { FinoraCard, EmptyState, SectionHeader } from '../design-system';
import { dashboardApi } from '../api/endpoints';
import type { TimelineEvent } from '../types';

function groupByYear(events: TimelineEvent[]): [string, TimelineEvent[]][] {
  const groups = new Map<string, TimelineEvent[]>();
  for (const e of events) {
    const year = new Date(e.occurredAt).getFullYear().toString();
    groups.set(year, [...(groups.get(year) ?? []), e]);
  }
  return [...groups.entries()].sort((a, b) => b[0].localeCompare(a[0]));
}

export function Timeline() {
  const { data } = useQuery({ queryKey: ['timeline'], queryFn: dashboardApi.timeline });
  const groups = data ? groupByYear(data) : [];

  return (
    <div>
      <SectionHeader title="Your Journey" />
      {groups.length === 0 && (
        <EmptyState
          icon={Sparkles}
          iconBg="bg-primary-light"
          iconColor="text-primary"
          title="Your journey starts here"
          desc="Milestones you reach will show up on this page."
        />
      )}
      {groups.map(([year, events]) => (
        <FinoraCard key={year} padding="lg" className="mb-6">
          {/* h3, not h2 -- SectionHeader above already renders the page's one h2 ("Your
              Journey"); a year group is a subsection of that, and Timeline.test.tsx's heading
              assertion depends on year groups being the only level-3 headings on the page. */}
          <h3 className="font-semibold text-ink mb-4">{year}</h3>
          <ol>
            {events.map((e) => (
              <li key={e.eventType + e.occurredAt} className="mb-3">
                <p className="text-sm font-medium text-ink">{e.title}</p>
                {e.detail && <p className="text-xs text-muted">{e.detail}</p>}
              </li>
            ))}
          </ol>
        </FinoraCard>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm test -- Timeline` (from `frontend/`)
Expected: PASS

- [ ] **Step 5: Add the route**

Find the existing authenticated-route block in `frontend/src/App.tsx` (e.g. the `<Route path="/app/goals" element={<Goals />} />` line) and add, following the exact same pattern:

```tsx
<Route path="/app/journey" element={<Timeline />} />
```

Add the `Timeline` import alongside the other page imports in the same file.

- [ ] **Step 6: Link to it from the Dashboard widget**

```tsx
// frontend/src/components/JourneyWidget.tsx — wrap the returned <FinoraCard> in a
// react-router Link to /app/journey, following the same MotionLink/Link pattern already used
// elsewhere in Dashboard.tsx for card-level navigation (check that file's existing
// QuickActionCard usage for the exact wrapping convention and mirror it).
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Timeline.tsx frontend/src/pages/Timeline.test.tsx \
        frontend/src/App.tsx frontend/src/components/JourneyWidget.tsx
git commit -m "feat(frontend): add full Timeline page at /app/journey"
```

---

### Task 11: Derived badges

**Files:**
- Create: `frontend/src/lib/timelineBadges.ts`
- Create: `frontend/src/lib/timelineBadges.test.ts`
- Modify: `frontend/src/pages/Timeline.tsx`

**Interfaces:**
- Produces: `badgeForEvent(event: TimelineEvent): string | null` — a pure display-label lookup, per the spec's "badges are derived, not separately tracked" requirement (§4). No new backend state.

- [ ] **Step 1: Write the failing test**

```typescript
// frontend/src/lib/timelineBadges.test.ts
import { describe, expect, it } from 'vitest';
import { badgeForEvent } from './timelineBadges';
import type { TimelineEvent } from '../types';

function eventOf(eventType: string, importance: TimelineEvent['importance'] = 'LANDMARK'): TimelineEvent {
  return { eventType, bucket: 'TRANSFORMATION', importance, permanent: true, title: 't', detail: null, occurredAt: '2026-01-01T00:00:00Z' };
}

describe('badgeForEvent', () => {
  it('returns a badge label for a known Landmark event type', () => {
    expect(badgeForEvent(eventOf('GOAL_COMPLETED'))).toBe('Goal Achiever');
  });

  it('returns null for a Minor/Major event type with no badge defined', () => {
    expect(badgeForEvent(eventOf('GOAL_PROGRESS_50', 'MINOR'))).toBeNull();
  });

  it('returns null for an unknown event type', () => {
    expect(badgeForEvent(eventOf('SOMETHING_NEW'))).toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- timelineBadges` (from `frontend/`)
Expected: FAIL — `badgeForEvent` does not exist yet.

- [ ] **Step 3: Write the lookup**

```typescript
// frontend/src/lib/timelineBadges.ts
import type { TimelineEvent } from '../types';

// Badges are a display label derived from a Landmark timeline event -- never a separately
// tracked state, per the design spec's Layer 3 requirement. Only Landmark-importance event
// types appear here; Minor/Major events are timeline texture, not badge-worthy.
const BADGE_LABELS: Record<string, string> = {
  FIRST_GOAL_CREATED: 'Goal Setter',
  GOAL_COMPLETED: 'Goal Achiever',
  NET_WORTH_10K: 'First ₹10K Saved',
  NET_WORTH_100K: 'Six-Figure Saver',
};

export function badgeForEvent(event: TimelineEvent): string | null {
  if (event.importance !== 'LANDMARK') return null;
  return BADGE_LABELS[event.eventType] ?? null;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm test -- timelineBadges` (from `frontend/`)
Expected: PASS

- [ ] **Step 5: Show the badge in the Timeline page**

```tsx
// Timeline.tsx — import badgeForEvent, and inside the events.map(...) <li>, after the
// existing detail paragraph:
{badgeForEvent(e) && (
  <span className="inline-block mt-1 text-xs font-medium text-primary bg-primary-light rounded-full px-2 py-0.5">
    {badgeForEvent(e)}
  </span>
)}
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/timelineBadges.ts frontend/src/lib/timelineBadges.test.ts frontend/src/pages/Timeline.tsx
git commit -m "feat(frontend): add derived badges for Landmark events"
```

---

### Task 12: Goal-based momentum ("N of last M months")

**Files:**
- Create: `backend/src/main/java/com/finora/goals/GoalMomentumService.java`
- Create: `backend/src/main/java/com/finora/dto/GoalMomentumDto.java`
- Test: `backend/src/test/java/com/finora/goals/GoalMomentumServiceTest.java`
- Modify: `backend/src/main/java/com/finora/goals/GoalContributionRepository.java` (add a user-scoped query)
- Modify: `backend/src/main/java/com/finora/controller/TimelineController.java` (add the endpoint)
- Modify: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts` (regenerated)
- Modify: `frontend/src/components/JourneyWidget.tsx` (surface the momentum count)

**Interfaces:**
- Produces: `GoalMomentumDto(int activeMonths, int windowMonths)`; `GoalMomentumService.compute(UUID userId): GoalMomentumDto`; `GET /api/v1/timeline/momentum`.

- [ ] **Step 1: Add the user-scoped contribution query**

```java
// GoalContributionRepository.java — add:
/** All of a user's contributions across every one of their goals, for momentum computation
 *  (GoalMomentumService) -- goal_contributions has no user_id column of its own, so this joins
 *  through goals. */
@org.springframework.data.jpa.repository.Query("""
    SELECT gc FROM GoalContribution gc, Goal g
    WHERE gc.goalId = g.id AND g.userId = :userId
    """)
List<GoalContribution> findByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);
```

- [ ] **Step 2: Write the failing test**

```java
// backend/src/test/java/com/finora/goals/GoalMomentumServiceTest.java
package com.finora.goals;

import com.finora.dto.GoalMomentumDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalMomentumServiceTest {

    private GoalContributionRepository contributionRepository;
    private GoalMomentumService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contributionRepository = mock(GoalContributionRepository.class);
        service = new GoalMomentumService(contributionRepository);
    }

    private GoalContribution contributionOn(LocalDate date) {
        GoalContribution gc = new GoalContribution();
        ReflectionTestUtils.setField(gc, "contributedAt", date);
        return gc;
    }

    @Test
    void compute_countsDistinctMonthsWithAtLeastOneContribution_inTheLast6Months() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(
                contributionOn(LocalDate.of(2026, 9, 1)),   // this month
                contributionOn(LocalDate.of(2026, 8, 10)),  // last month
                contributionOn(LocalDate.of(2026, 8, 20)),  // same month as above -- counts once
                contributionOn(LocalDate.of(2026, 3, 1))    // outside the 6-month window
        ));

        GoalMomentumDto result = service.compute(userId, today);

        assertThat(result.activeMonths()).isEqualTo(2);
        assertThat(result.windowMonths()).isEqualTo(6);
    }

    @Test
    void compute_returnsZero_whenTheUserHasNeverContributed() {
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of());

        GoalMomentumDto result = service.compute(userId, LocalDate.of(2026, 9, 15));

        assertThat(result.activeMonths()).isEqualTo(0);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=GoalMomentumServiceTest` (from `backend/`)
Expected: FAIL — `GoalMomentumService`/`GoalMomentumDto` don't exist yet.

- [ ] **Step 4: Write the DTO and service**

```java
// backend/src/main/java/com/finora/dto/GoalMomentumDto.java
package com.finora.dto;

public record GoalMomentumDto(int activeMonths, int windowMonths) {}
```

```java
// backend/src/main/java/com/finora/goals/GoalMomentumService.java
package com.finora.goals;

import com.finora.dto.GoalMomentumDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** "N of last M months" momentum -- deliberately NOT a hard consecutive streak that resets to
 *  zero on one miss (design spec §4, Layer 4): a rolling window count is fair to freelancers/
 *  commission earners/seasonal workers whose cash flow isn't monthly-regular. */
@Service
public class GoalMomentumService {

    private static final int WINDOW_MONTHS = 6;

    private final GoalContributionRepository contributionRepository;

    public GoalMomentumService(GoalContributionRepository contributionRepository) {
        this.contributionRepository = contributionRepository;
    }

    @Transactional(readOnly = true)
    public GoalMomentumDto compute(UUID userId, LocalDate today) {
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth windowStart = currentMonth.minusMonths(WINDOW_MONTHS - 1L);

        long activeMonths = contributionRepository.findByUserId(userId).stream()
                .map(GoalContribution::getContributedAt)
                .map(YearMonth::from)
                .filter(m -> !m.isBefore(windowStart) && !m.isAfter(currentMonth))
                .distinct()
                .count();

        return new GoalMomentumDto((int) activeMonths, WINDOW_MONTHS);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=GoalMomentumServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 6: Add the endpoint**

```java
// TimelineController.java — add the field, constructor argument, and:
import com.finora.dto.GoalMomentumDto;
import com.finora.goals.GoalMomentumService;
import java.time.LocalDate;

private final GoalMomentumService goalMomentumService;

public TimelineController(TimelineEventService timelineEventService, GoalMomentumService goalMomentumService,
                           CurrentUser currentUser) {
    this.timelineEventService = timelineEventService;
    this.goalMomentumService = goalMomentumService;
    this.currentUser = currentUser;
}

@GetMapping("/momentum")
public ApiResponse<GoalMomentumDto> momentum() {
    return ApiResponse.ok(goalMomentumService.compute(currentUser.id(), LocalDate.now()));
}
```

- [ ] **Step 7: Regenerate OpenAPI/types**

Run: `cd backend && ./scripts/generate-openapi-spec.sh && cd ../frontend && npm run generate:types`

- [ ] **Step 8: Surface it on the Dashboard widget**

```tsx
// JourneyWidget.tsx — add a second useQuery for dashboardApi.momentum() (add that client
// method to endpoints.ts the same way timeline() was added in Task 9), and render, below the
// highlight title, when momentum.activeMonths > 0:
<p className="text-xs text-muted mt-1">
  Active {momentum.activeMonths} of the last {momentum.windowMonths} months
</p>
```

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/goals/GoalMomentumService.java \
        backend/src/main/java/com/finora/dto/GoalMomentumDto.java \
        backend/src/test/java/com/finora/goals/GoalMomentumServiceTest.java \
        backend/src/main/java/com/finora/goals/GoalContributionRepository.java \
        backend/src/main/java/com/finora/controller/TimelineController.java \
        backend/openapi/openapi.json frontend/src/api/generated-types.ts \
        frontend/src/components/JourneyWidget.tsx frontend/src/api/endpoints.ts
git commit -m "feat(backend): add goal-based momentum (N of last 6 months)"
```

---

### Task 13: Wealth Wrapped — backend

**Files:**
- Create: `backend/src/main/java/com/finora/service/WrappedService.java`
- Create: `backend/src/main/java/com/finora/dto/WrappedDto.java`
- Test: `backend/src/test/java/com/finora/service/WrappedServiceTest.java`
- Modify: `backend/src/main/java/com/finora/controller/TimelineController.java`
- Modify: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts` (regenerated)

**Interfaces:**
- Consumes: `TimelineEventRepository.findByUserIdOrderByOccurredAtDesc` (filtered to `LANDMARK` + the target year), `GoalContributionRepository.findByUserId` (Task 12).
- Produces: `WrappedDto(int year, int landmarksReached, int goalContributions, List<String> landmarkTitles)`; `WrappedService.build(UUID userId, int year): WrappedDto`; `GET /api/v1/timeline/wrapped?year=`.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/finora/service/WrappedServiceTest.java
package com.finora.service;

import com.finora.dto.WrappedDto;
import com.finora.goals.GoalContribution;
import com.finora.goals.GoalContributionRepository;
import com.finora.timeline.TimelineEvent;
import com.finora.timeline.TimelineEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WrappedServiceTest {

    private TimelineEventRepository timelineEventRepository;
    private GoalContributionRepository contributionRepository;
    private WrappedService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        timelineEventRepository = mock(TimelineEventRepository.class);
        contributionRepository = mock(GoalContributionRepository.class);
        service = new WrappedService(timelineEventRepository, contributionRepository);
    }

    private TimelineEvent eventOf(String importance, String title, Instant occurredAt) {
        TimelineEvent e = new TimelineEvent();
        ReflectionTestUtils.setField(e, "importance", importance);
        ReflectionTestUtils.setField(e, "title", title);
        ReflectionTestUtils.setField(e, "occurredAt", occurredAt);
        return e;
    }

    private GoalContribution contributionOn(LocalDate date) {
        GoalContribution gc = new GoalContribution();
        ReflectionTestUtils.setField(gc, "contributedAt", date);
        return gc;
    }

    @Test
    void build_countsOnlyLandmarkEvents_inTheRequestedYear() {
        when(timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(
                eventOf("LANDMARK", "Completed Emergency Fund", Instant.parse("2026-06-01T00:00:00Z")),
                eventOf("MAJOR", "Created your first budget", Instant.parse("2026-01-01T00:00:00Z")),
                eventOf("LANDMARK", "Saved your first ₹10,000", Instant.parse("2025-12-01T00:00:00Z"))
        ));
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(
                contributionOn(LocalDate.of(2026, 3, 1)), contributionOn(LocalDate.of(2026, 6, 1))
        ));

        WrappedDto result = service.build(userId, 2026);

        assertThat(result.year()).isEqualTo(2026);
        assertThat(result.landmarksReached()).isEqualTo(1);
        assertThat(result.landmarkTitles()).containsExactly("Completed Emergency Fund");
        assertThat(result.goalContributions()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=WrappedServiceTest` (from `backend/`)
Expected: FAIL — `WrappedService`/`WrappedDto` don't exist yet.

- [ ] **Step 3: Write the DTO and service**

```java
// backend/src/main/java/com/finora/dto/WrappedDto.java
package com.finora.dto;

import java.util.List;

public record WrappedDto(int year, int landmarksReached, int goalContributions, List<String> landmarkTitles) {}
```

```java
// backend/src/main/java/com/finora/service/WrappedService.java
package com.finora.service;

import com.finora.dto.WrappedDto;
import com.finora.goals.GoalContributionRepository;
import com.finora.timeline.TimelineEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Layer 2 of the design spec: the marketing vehicle built entirely from Layer 1
 *  (TimelineEvent) and existing GoalContribution data -- no separate computation of its own.
 *  Only LANDMARK-importance events are eligible, per spec §4.3. */
@Service
public class WrappedService {

    private final TimelineEventRepository timelineEventRepository;
    private final GoalContributionRepository contributionRepository;

    public WrappedService(TimelineEventRepository timelineEventRepository,
                           GoalContributionRepository contributionRepository) {
        this.timelineEventRepository = timelineEventRepository;
        this.contributionRepository = contributionRepository;
    }

    @Transactional(readOnly = true)
    public WrappedDto build(UUID userId, int year) {
        List<String> landmarkTitles = timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .filter(e -> "LANDMARK".equals(e.getImportance()))
                .filter(e -> Year.from(e.getOccurredAt().atZone(java.time.ZoneOffset.UTC)).getValue() == year)
                .map(com.finora.timeline.TimelineEvent::getTitle)
                .toList();

        long contributions = contributionRepository.findByUserId(userId).stream()
                .filter(c -> YearMonth.from(c.getContributedAt()).getYear() == year)
                .count();

        return new WrappedDto(year, landmarkTitles.size(), (int) contributions, landmarkTitles);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=WrappedServiceTest` (from `backend/`)
Expected: PASS

- [ ] **Step 5: Add the endpoint**

```java
// TimelineController.java — add the field, constructor argument, and:
import com.finora.dto.WrappedDto;
import com.finora.service.WrappedService;
import org.springframework.web.bind.annotation.RequestParam;

private final WrappedService wrappedService;

// add wrappedService to the constructor signature and assignment, alongside goalMomentumService

@GetMapping("/wrapped")
public ApiResponse<WrappedDto> wrapped(@RequestParam int year) {
    return ApiResponse.ok(wrappedService.build(currentUser.id(), year));
}
```

- [ ] **Step 6: Regenerate OpenAPI/types**

Run: `cd backend && ./scripts/generate-openapi-spec.sh && cd ../frontend && npm run generate:types`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/WrappedService.java \
        backend/src/main/java/com/finora/dto/WrappedDto.java \
        backend/src/test/java/com/finora/service/WrappedServiceTest.java \
        backend/src/main/java/com/finora/controller/TimelineController.java \
        backend/openapi/openapi.json frontend/src/api/generated-types.ts
git commit -m "feat(backend): add WrappedService and GET /api/v1/timeline/wrapped"
```

---

### Task 14: Wealth Wrapped — frontend share card

**Files:**
- Create: `frontend/src/pages/Wrapped.tsx`
- Create: `frontend/src/pages/Wrapped.test.tsx`
- Modify: `frontend/src/App.tsx` (route)
- Modify: `frontend/src/api/endpoints.ts` (client method)

**Interfaces:**
- Consumes: `GET /api/v1/timeline/wrapped?year=` (Task 13).
- Produces: a `/app/wrapped` route rendering the current year's card.

- [ ] **Step 1: Add the API client method**

```typescript
// frontend/src/api/endpoints.ts — inside dashboardApi:
  wrapped: (year: number) => api.get<Wrapped>(`/timeline/wrapped?year=${year}`).then((r) => r.data),
```

```typescript
// frontend/src/types/index.ts — add:
export interface Wrapped {
  year: number;
  landmarksReached: number;
  goalContributions: number;
  landmarkTitles: string[];
}
```

- [ ] **Step 2: Write the failing test**

```typescript
// frontend/src/pages/Wrapped.test.tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { Wrapped } from './Wrapped';
import { dashboardApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ dashboardApi: { wrapped: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('Wrapped', () => {
  it('shows the year, landmark count, and contribution count', async () => {
    vi.mocked(dashboardApi.wrapped).mockResolvedValue({
      year: 2026, landmarksReached: 2, goalContributions: 14,
      landmarkTitles: ['Completed Emergency Fund', 'Saved your first ₹10,000'],
    });

    renderWithClient(<Wrapped />);

    expect(await screen.findByText('2026')).toBeInTheDocument();
    expect(await screen.findByText('Completed Emergency Fund')).toBeInTheDocument();
    expect(await screen.findByText('14')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `npm test -- Wrapped` (from `frontend/`)
Expected: FAIL — `Wrapped` page does not exist yet.

- [ ] **Step 4: Write the page**

```tsx
// frontend/src/pages/Wrapped.tsx
import { useQuery } from '@tanstack/react-query';
import { FinoraCard } from '../design-system';
import { dashboardApi } from '../api/endpoints';

export function Wrapped() {
  const currentYear = new Date().getFullYear();
  const { data } = useQuery({ queryKey: ['wrapped', currentYear], queryFn: () => dashboardApi.wrapped(currentYear) });

  if (!data) return null;

  return (
    <FinoraCard padding="lg" className="max-w-md mx-auto bg-ink text-on-primary">
      <p className="text-sm opacity-70">Your Financial Journey</p>
      <h1 className="text-4xl font-bold mb-4">{data.year}</h1>
      <p className="text-lg mb-1">{data.goalContributions}</p>
      <p className="text-sm opacity-70 mb-4">goal contributions this year</p>
      <ul>
        {data.landmarkTitles.map((title) => (
          <li key={title} className="text-sm mb-2">{title}</li>
        ))}
      </ul>
    </FinoraCard>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm test -- Wrapped` (from `frontend/`)
Expected: PASS

- [ ] **Step 6: Add the route**

Same pattern as Task 10 Step 5:

```tsx
<Route path="/app/wrapped" element={<Wrapped />} />
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/Wrapped.tsx frontend/src/pages/Wrapped.test.tsx \
        frontend/src/App.tsx frontend/src/api/endpoints.ts frontend/src/types/index.ts
git commit -m "feat(frontend): add Wealth Wrapped share-card page at /app/wrapped"
```

---

## Explicitly out of scope for this plan

Per spec §6, these are follow-on plans, not part of this one:

- **Dynamic/spend-based milestones** (spec §4.2's own example, "Dining spend fell 18%"). Every
  event type this plan implements (Tasks 4-6) is Starting/Progress/Transformation, sourced from
  a single point-in-time fact (`Goal`/`Budget`/`NetWorthSnapshot`). A spend-change milestone
  needs a month-close comparison (reusing `DashboardService.categoryMovers`/
  `categorySpendForMonth`'s existing logic) with no natural trigger point this plan wires up —
  left for a follow-on task once Layer 1's foundation is proven out.
- Budget-based momentum ("stayed within budget N months") — needs new monthly budget-snapshot infrastructure that doesn't exist yet (spec §3, `Budget` row).
- Layer 5 (Financial Health Score, Black Circle, percentiles, any cross-user comparison) — gated on data-trust and scale preconditions this plan does not address.
- Mobile screens and mobile API client changes.
- Including `TimelineEvent` rows in `DataExportService`'s export bundle (spec §7, open question — needs Sid's decision first).
- Copy/tone review of milestone titles (spec §7, open question) — the titles used in this plan's code samples are illustrative and should be reviewed before shipping, especially anything that could drift toward the "Financial Age" shame framing the spec explicitly rejects (§5).
