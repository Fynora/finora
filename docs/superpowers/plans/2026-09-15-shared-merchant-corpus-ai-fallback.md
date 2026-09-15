# Shared Merchant Category Corpus + AI Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a cross-user "who is this merchant, what category do they belong to" corpus that only accepts corroborated, cross-user evidence, plus an LLM fallback (via Fyn) for merchants nothing else can answer — both wired into the existing `CategorizationService` waterfall.

**Architecture:** Two new tables separate a private, append-only observation log (every human correction, gated to `vpa:`-keyed `BUSINESS`/`FINANCIAL_INSTITUTION` counterparties) from a durable corpus table that only gets a row once ≥3 distinct users corroborate a mapping ("promotion"). A third table caches the latest AI answer per key, structurally separate from human evidence. `SharedCorpusService` owns eligibility, promotion, decay, and contradiction ("Revalidating") logic; `FynCategorizationFallbackService` mirrors the existing `FynImportDiagnosisService` shape to call the LLM. Both plug into `CategorizationService.suggest()`/`suggestReadOnly()` between the keyword layer and structural P2P detection.

**Tech Stack:** Spring Boot, Spring Data JPA, PostgreSQL/Flyway, JUnit 5 + AssertJ + Mockito, the existing `LlmClient`/`FynAvailabilityGuard`/`FynCostGovernanceService` infrastructure.

**Spec:** `docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md`

## Global Constraints

- Eligibility (write-time invariant, spec §3): an observation is recorded only if `counterpartyKey` starts with `vpa:` AND `counterpartyType` is `BUSINESS` or `FINANCIAL_INSTITUTION`. Never `PERSON`/`GOVERNMENT`/`UNKNOWN`.
- Promotion thresholds (spec §5): `Trusted` requires ≥3 distinct human voters, the winning category holding ≥70% of decay-weighted observations, AND ≥3 raw (undecayed) votes for the winning category itself. Anything with ≥3 distinct voters not clearing 70% is `Disputed`. Fewer than 3 distinct voters never gets a `shared_merchant_category` row at all.
- Decay half-life: 12 months (365 days), applied only to the 70%-share computation — never to the distinct-voter or winning-vote-count safeguards, which use raw counts (spec §5/§6).
- Revalidation window (spec §7): a contradicting observation against an already-`TRUSTED` row flips it to `REVALIDATING` immediately, regardless of historical vote count. It re-promotes only after 3 more observations on that key OR 90 days, whichever comes first.
- Retention (spec §4): observations that never get promoted are purged — 6 months at 1 distinct voter, 12 months at 2. Observations backing a promoted row are kept indefinitely.
- AI answers never count as evidence anywhere in promotion or tier computation — they live in a fully separate table (spec §8).
- Waterfall order (spec §9): `User rules → Global rules → Per-user learned distribution → CategoryRules → Shared corpus (Trusted only) → AI fallback → Structural P2P → Other`. Both `CategorizationService.suggest()` and `suggestReadOnly()` must get the identical new steps — they are two independently-maintained copies of the same waterfall (confirmed by reading the current file; this codebase has a documented history of these two diverging, e.g. #743).
- No AI attribution in commit messages — see the repository's own `CLAUDE.md`.

---

## Task 1: Data model — migration, entities, repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V212__shared_merchant_corpus.sql`
- Create: `backend/src/main/java/com/finora/entity/CounterpartyCategoryObservation.java`
- Create: `backend/src/main/java/com/finora/entity/SharedMerchantCategory.java`
- Create: `backend/src/main/java/com/finora/entity/SharedMerchantCategoryAiSuggestion.java`
- Create: `backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java`
- Create: `backend/src/main/java/com/finora/repository/SharedMerchantCategoryRepository.java`
- Create: `backend/src/main/java/com/finora/repository/SharedMerchantCategoryAiSuggestionRepository.java`
- Test: `backend/src/test/java/com/finora/repository/SharedCorpusRepositoriesIT.java`

**Interfaces:**
- Produces: `CounterpartyCategoryObservation` (fields: `id`, `counterpartyKey`, `direction` (`Transaction.Type`), `category`, `userId`, `counterpartyTypeAtVote` (`CounterpartyType`), `createdAt`); `SharedMerchantCategory` (fields: `id`, `counterpartyKey`, `direction`, `status` (enum `TRUSTED`/`DISPUTED`/`REVALIDATING`), `category`, `categoryDistribution` (`Map<String, BigDecimal>`), `distinctUserCount`, `promotedAt`, `lastRecomputedAt`, `revalidatingSince`); `SharedMerchantCategoryAiSuggestion` (fields: `id`, `counterpartyKey`, `direction`, `category`, `model`, `generatedAt`). Repository methods used by Task 2+: `CounterpartyCategoryObservationRepository.findByCounterpartyKeyAndDirection(String, Transaction.Type)`, `.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(String, Transaction.Type, Instant)`; `SharedMerchantCategoryRepository.findByCounterpartyKeyAndDirection(String, Transaction.Type)`; `SharedMerchantCategoryAiSuggestionRepository.upsert(String, Transaction.Type, String, String, Instant)`.

Before writing the migration: fetch `origin/main` and re-list `backend/src/main/resources/db/migration` to confirm `V212` is still free (another in-flight branch may have taken it — this repo's own `CLAUDE.md` requires this check every time).

- [ ] **Step 1: Fetch and confirm the migration version**

```bash
git fetch origin
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -3
```

Expected: highest is `211`. If it's higher, use the next free number instead of 212 throughout this plan.

- [ ] **Step 2: Write the migration**

```sql
-- Shared merchant category corpus -- docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md.
--
-- Three tables, deliberately separate (spec SS4):
--   counterparty_category_observation: private, append-only log of human corrections. Never
--     read by the categorization waterfall.
--   shared_merchant_category: the actual corpus. One row per (counterparty_key, direction),
--     created only by promotion once >=3 distinct human voters corroborate a mapping.
--   shared_merchant_category_ai_suggestion: latest AI answer per key, upserted, never evidence.
--
-- direction reuses Transaction.Type's own value set (INCOME/EXPENSE) rather than inventing a
-- new DEBIT/CREDIT enum -- same column width as txn_type (V1__init_schema.sql).
-- counterparty_type_at_vote reuses CounterpartyType's value set, same width as
-- transactions.counterparty_type (V142__transaction_counterparty.sql).

CREATE TABLE counterparty_category_observation (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key           VARCHAR(255) NOT NULL,
    direction                  VARCHAR(10) NOT NULL,
    category                   VARCHAR(80) NOT NULL,
    user_id                    UUID NOT NULL,
    counterparty_type_at_vote  VARCHAR(24) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only access pattern Task 2's promotion logic uses: every observation for one key+direction.
CREATE INDEX idx_observation_key_direction
    ON counterparty_category_observation (counterparty_key, direction);

-- Task 6's retention sweep: find keys whose newest observation is older than a cutoff.
CREATE INDEX idx_observation_key_direction_created_at
    ON counterparty_category_observation (counterparty_key, direction, created_at);

CREATE TABLE shared_merchant_category (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key      VARCHAR(255) NOT NULL,
    direction             VARCHAR(10) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    category              VARCHAR(80) NOT NULL,
    category_distribution JSONB NOT NULL,
    distinct_user_count   INT NOT NULL,
    promoted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_recomputed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revalidating_since    TIMESTAMPTZ,
    CONSTRAINT uq_shared_merchant_category_key_direction UNIQUE (counterparty_key, direction)
);

-- The waterfall's only read: "is there a TRUSTED row for this key+direction". Partial index
-- keeps it small -- Disputed/Revalidating rows are never read by the waterfall (spec SS9).
CREATE INDEX idx_shared_merchant_category_trusted
    ON shared_merchant_category (counterparty_key, direction)
    WHERE status = 'TRUSTED';

CREATE TABLE shared_merchant_category_ai_suggestion (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    category          VARCHAR(80) NOT NULL,
    model             VARCHAR(64) NOT NULL,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shared_merchant_category_ai_suggestion_key_direction UNIQUE (counterparty_key, direction)
);
```

- [ ] **Step 3: Write the entities**

```java
// backend/src/main/java/com/finora/entity/CounterpartyCategoryObservation.java
package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One human correction against an eligible (vpa:-keyed, BUSINESS/FINANCIAL_INSTITUTION) merchant
 * -- private, append-only, never read by the categorization waterfall. See
 * docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md SS4.
 */
@Entity
@Table(name = "counterparty_category_observation")
public class CounterpartyCategoryObservation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_type_at_vote", nullable = false, length = 24)
    private CounterpartyType counterpartyTypeAtVote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public CounterpartyType getCounterpartyTypeAtVote() { return counterpartyTypeAtVote; }
    public void setCounterpartyTypeAtVote(CounterpartyType counterpartyTypeAtVote) { this.counterpartyTypeAtVote = counterpartyTypeAtVote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

```java
// backend/src/main/java/com/finora/entity/SharedMerchantCategory.java
package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The actual corpus -- one row per (counterparty_key, direction), created only by promotion
 * (SharedCorpusService), never written to directly. See spec SS4/SS5.
 */
@Entity
@Table(name = "shared_merchant_category")
public class SharedMerchantCategory {

    public enum Status { TRUSTED, DISPUTED, REVALIDATING }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 80)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_distribution", nullable = false, columnDefinition = "jsonb")
    private Map<String, BigDecimal> categoryDistribution;

    @Column(name = "distinct_user_count", nullable = false)
    private int distinctUserCount;

    @Column(name = "promoted_at", nullable = false)
    private Instant promotedAt = Instant.now();

    @Column(name = "last_recomputed_at", nullable = false)
    private Instant lastRecomputedAt = Instant.now();

    @Column(name = "revalidating_since")
    private Instant revalidatingSince;

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Map<String, BigDecimal> getCategoryDistribution() { return categoryDistribution; }
    public void setCategoryDistribution(Map<String, BigDecimal> categoryDistribution) { this.categoryDistribution = categoryDistribution; }
    public int getDistinctUserCount() { return distinctUserCount; }
    public void setDistinctUserCount(int distinctUserCount) { this.distinctUserCount = distinctUserCount; }
    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant promotedAt) { this.promotedAt = promotedAt; }
    public Instant getLastRecomputedAt() { return lastRecomputedAt; }
    public void setLastRecomputedAt(Instant lastRecomputedAt) { this.lastRecomputedAt = lastRecomputedAt; }
    public Instant getRevalidatingSince() { return revalidatingSince; }
    public void setRevalidatingSince(Instant revalidatingSince) { this.revalidatingSince = revalidatingSince; }
}
```

```java
// backend/src/main/java/com/finora/entity/SharedMerchantCategoryAiSuggestion.java
package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** The latest AI-guessed category per (counterparty_key, direction) -- upserted, never evidence.
 *  See spec SS4/SS8. */
@Entity
@Table(name = "shared_merchant_category_ai_suggestion")
public class SharedMerchantCategoryAiSuggestion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
```

- [ ] **Step 4: Write the repositories**

```java
// backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java
package com.finora.repository;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CounterpartyCategoryObservationRepository extends JpaRepository<CounterpartyCategoryObservation, UUID> {

    List<CounterpartyCategoryObservation> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    long countByCounterpartyKeyAndDirectionAndCreatedAtAfter(String counterpartyKey, Transaction.Type direction, Instant after);

    /**
     * Task 6's retention sweep: distinct (key, direction) pairs with <=2 distinct voters, whose
     * newest observation is older than {@code cutoff}, and which have never been promoted (no
     * matching shared_merchant_category row) -- exactly the population spec SS4's retention table
     * targets. Bounded by {@code limit} -- see CounterpartyBackfillSweepService for the same
     * bounded-batch precedent this mirrors.
     */
    @Query(value = """
           SELECT o.counterparty_key, o.direction
           FROM counterparty_category_observation o
           LEFT JOIN shared_merchant_category c
               ON c.counterparty_key = o.counterparty_key AND c.direction = o.direction
           WHERE c.id IS NULL
           GROUP BY o.counterparty_key, o.direction
           HAVING COUNT(DISTINCT o.user_id) <= 2 AND MAX(o.created_at) < :cutoffFor2Voters
              AND (COUNT(DISTINCT o.user_id) < 2 OR MAX(o.created_at) < :cutoffFor2Voters)
           LIMIT :limit
           """, nativeQuery = true)
    List<Object[]> findUnpromotedKeysPastRetention(@Param("cutoffFor2Voters") Instant cutoffFor2Voters,
                                                     @Param("limit") int limit);

    void deleteByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);
}
```

Note for Task 6: the query above is a starting shape, not final — the 1-voter (6mo) vs. 2-voter (12mo) split needs two cutoffs, not one. Task 6 revises this into two queries (or one query taking both cutoffs) once the retention sweep's own tests pin the exact behavior; don't treat this file as frozen.

```java
// backend/src/main/java/com/finora/repository/SharedMerchantCategoryRepository.java
package com.finora.repository;

import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SharedMerchantCategoryRepository extends JpaRepository<SharedMerchantCategory, UUID> {
    Optional<SharedMerchantCategory> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);
    long countByStatus(SharedMerchantCategory.Status status);
}
```

```java
// backend/src/main/java/com/finora/repository/SharedMerchantCategoryAiSuggestionRepository.java
package com.finora.repository;

import com.finora.entity.SharedMerchantCategoryAiSuggestion;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SharedMerchantCategoryAiSuggestionRepository extends JpaRepository<SharedMerchantCategoryAiSuggestion, UUID> {

    Optional<SharedMerchantCategoryAiSuggestion> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    /** Atomic upsert -- mirrors MerchantCategoryLearningRepository.ensurePairExists's ON CONFLICT
     *  pattern. An AI suggestion is a cache entry (spec SS4/SS8): replace, don't accumulate. */
    @Modifying
    @Query(value = """
           INSERT INTO shared_merchant_category_ai_suggestion
               (id, counterparty_key, direction, category, model, generated_at)
           VALUES (gen_random_uuid(), :counterpartyKey, :direction, :category, :model, :generatedAt)
           ON CONFLICT (counterparty_key, direction)
           DO UPDATE SET category = :category, model = :model, generated_at = :generatedAt
           """, nativeQuery = true)
    void upsert(@Param("counterpartyKey") String counterpartyKey, @Param("direction") String direction,
                @Param("category") String category, @Param("model") String model,
                @Param("generatedAt") Instant generatedAt);
}
```

- [ ] **Step 5: Write the repository integration test**

```java
// backend/src/test/java/com/finora/repository/SharedCorpusRepositoriesIT.java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SharedCorpusRepositoriesIT extends AbstractIntegrationTest {

    @Autowired CounterpartyCategoryObservationRepository observations;
    @Autowired SharedMerchantCategoryRepository corpus;
    @Autowired SharedMerchantCategoryAiSuggestionRepository aiSuggestions;

    @Test
    void savesAndFindsAnObservationByKeyAndDirection() {
        CounterpartyCategoryObservation obs = new CounterpartyCategoryObservation();
        obs.setCounterpartyKey("vpa:zeptoonline");
        obs.setDirection(Transaction.Type.EXPENSE);
        obs.setCategory("Shopping");
        obs.setUserId(UUID.randomUUID());
        obs.setCounterpartyTypeAtVote(CounterpartyType.BUSINESS);
        observations.save(obs);

        assertThat(observations.findByCounterpartyKeyAndDirection("vpa:zeptoonline", Transaction.Type.EXPENSE))
                .hasSize(1);
        assertThat(observations.findByCounterpartyKeyAndDirection("vpa:zeptoonline", Transaction.Type.INCOME))
                .isEmpty();
    }

    @Test
    void enforcesOneCorpusRowPerKeyAndDirection() {
        SharedMerchantCategory row = new SharedMerchantCategory();
        row.setCounterpartyKey("vpa:kronos");
        row.setDirection(Transaction.Type.INCOME);
        row.setStatus(SharedMerchantCategory.Status.TRUSTED);
        row.setCategory("Salary");
        row.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        row.setDistinctUserCount(3);
        corpus.save(row);

        assertThat(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .isPresent()
                .get().extracting(SharedMerchantCategory::getStatus).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
    }

    @Test
    void upsertReplacesRatherThanDuplicatingAnAiSuggestion() {
        aiSuggestions.upsert("vpa:newmerchant", "EXPENSE", "Dining", "claude-haiku-4-5-20251001", Instant.now());
        aiSuggestions.upsert("vpa:newmerchant", "EXPENSE", "Shopping", "claude-haiku-4-5-20251001", Instant.now());

        assertThat(aiSuggestions.findByCounterpartyKeyAndDirection("vpa:newmerchant", Transaction.Type.EXPENSE))
                .isPresent()
                .get().extracting(SharedMerchantCategoryAiSuggestion::getCategory).isEqualTo("Shopping");
    }
}
```

- [ ] **Step 6: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusRepositoriesIT`
Expected: PASS, 3/3. If Postgres/Testcontainers isn't reachable in this environment, this is expected to fail with a container-startup error, not an assertion failure — note that explicitly rather than treating it as a code bug.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V212__shared_merchant_corpus.sql \
        backend/src/main/java/com/finora/entity/CounterpartyCategoryObservation.java \
        backend/src/main/java/com/finora/entity/SharedMerchantCategory.java \
        backend/src/main/java/com/finora/entity/SharedMerchantCategoryAiSuggestion.java \
        backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java \
        backend/src/main/java/com/finora/repository/SharedMerchantCategoryRepository.java \
        backend/src/main/java/com/finora/repository/SharedMerchantCategoryAiSuggestionRepository.java \
        backend/src/test/java/com/finora/repository/SharedCorpusRepositoriesIT.java
git commit -m "feat(rules): add shared merchant corpus data model"
```

---

## Task 2: `SharedCorpusService` — eligibility and promotion

**Files:**
- Create: `backend/src/main/java/com/finora/service/SharedCorpusService.java`
- Test: `backend/src/test/java/com/finora/service/SharedCorpusServiceTest.java`

**Interfaces:**
- Consumes: `CounterpartyCategoryObservationRepository`, `SharedMerchantCategoryRepository` (Task 1); `CounterpartyType` (existing).
- Produces: `SharedCorpusService.recordObservation(UUID userId, String counterpartyKey, CounterpartyType counterpartyType, Transaction.Type direction, String category)` (void); `SharedCorpusService.findTrustedSuggestion(String counterpartyKey, CounterpartyType counterpartyType, Transaction.Type direction)` returns `Optional<String>`. Static `SharedCorpusService.isEligible(String counterpartyKey, CounterpartyType counterpartyType)` returns `boolean` (used by Task 5/6/9).

This task covers eligibility + first-time promotion + ordinary recomputation. Contradiction/revalidation is Task 3 — deliberately split, since a reviewer could accept plain promotion and reject revalidation (or vice versa) independently.

- [ ] **Step 1: Write the failing tests for eligibility and promotion**

```java
// backend/src/test/java/com/finora/service/SharedCorpusServiceTest.java
package com.finora.service;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.CounterpartyType;
import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SharedCorpusServiceTest {

    private CounterpartyCategoryObservationRepository observations;
    private SharedMerchantCategoryRepository corpus;
    private SharedCorpusService service;
    private final List<CounterpartyCategoryObservation> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        observations = mock(CounterpartyCategoryObservationRepository.class);
        corpus = mock(SharedMerchantCategoryRepository.class);
        service = new SharedCorpusService(observations, corpus);

        stored.clear();
        when(observations.save(any())).thenAnswer(inv -> {
            CounterpartyCategoryObservation o = inv.getArgument(0);
            stored.add(o);
            return o;
        });
        when(observations.findByCounterpartyKeyAndDirection(any(), any()))
                .thenAnswer(inv -> new ArrayList<>(stored));
    }

    @Test
    void isEligible_vpaKeyBusinessType_true() {
        assertThat(SharedCorpusService.isEligible("vpa:zepto", CounterpartyType.BUSINESS)).isTrue();
        assertThat(SharedCorpusService.isEligible("vpa:groww", CounterpartyType.FINANCIAL_INSTITUTION)).isTrue();
    }

    @Test
    void isEligible_nameKeyOrPersonType_false() {
        assertThat(SharedCorpusService.isEligible("name:rahul", CounterpartyType.BUSINESS)).isFalse();
        assertThat(SharedCorpusService.isEligible("vpa:rahul", CounterpartyType.PERSON)).isFalse();
        assertThat(SharedCorpusService.isEligible("vpa:incometax", CounterpartyType.GOVERNMENT)).isFalse();
        assertThat(SharedCorpusService.isEligible(null, CounterpartyType.BUSINESS)).isFalse();
    }

    @Test
    void recordObservation_ineligibleCounterparty_writesNothing() {
        service.recordObservation(UUID.randomUUID(), "name:rahul", CounterpartyType.PERSON,
                Transaction.Type.EXPENSE, "Dining");

        verifyNoInteractions(observations, corpus);
    }

    @Test
    void recordObservation_twoVotersOnly_staysUnpromoted() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");

        assertThat(stored).hasSize(2);
        verify(corpus, never()).save(any());
    }

    @Test
    void recordObservation_thirdDistinctVoterWithClearMajority_promotesToTrusted() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        for (int i = 0; i < 3; i++) {
            service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                    Transaction.Type.EXPENSE, "Dining");
        }

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
        assertThat(saved.getCategory()).isEqualTo("Dining");
        assertThat(saved.getDistinctUserCount()).isEqualTo(3);
    }

    @Test
    void recordObservation_threeVotersNoMajority_promotesToDisputed() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");
        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Groceries");
        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Electronics");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.DISPUTED);
    }

    @Test
    void recordObservation_threeVotersTwoOneSplit_notTrusted_lowWinningVoteGuard() {
        // 2-of-3 clears 70% (66.7% rounds up? no -- 2/3 = 66.7%, below 70%, so this is actually
        // Disputed by the share test alone. Use 3 voters where one category gets exactly 3 votes
        // via a 4th voter instead, to isolate the >=3-winning-votes guard from the >=70% guard.
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");

        // 2/3 = 66.7% < 70%: correctly Disputed via the share test. Confirms the share guard
        // alone already rejects this shape; the winning-vote-count guard in the next test covers
        // the case the share test alone would NOT catch.
        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.DISPUTED);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — `SharedCorpusService` does not exist yet.

- [ ] **Step 3: Implement `SharedCorpusService`**

```java
// backend/src/main/java/com/finora/service/SharedCorpusService.java
package com.finora.service;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.CounterpartyType;
import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the shared merchant corpus's write path: eligibility (spec SS3), the private observation
 * log, and promotion into the durable corpus (spec SS4/SS5). Contradiction/revalidation handling
 * (spec SS7) is added in Task 3.
 */
@Service
public class SharedCorpusService {

    static final int TRUSTED_MIN_DISTINCT_VOTERS = 3;
    static final BigDecimal TRUSTED_MIN_SHARE = new BigDecimal("0.70");
    static final int TRUSTED_MIN_WINNING_VOTES = 3;
    static final double DECAY_HALF_LIFE_DAYS = 365.0;

    private final CounterpartyCategoryObservationRepository observations;
    private final SharedMerchantCategoryRepository corpus;

    public SharedCorpusService(CounterpartyCategoryObservationRepository observations,
                                SharedMerchantCategoryRepository corpus) {
        this.observations = observations;
        this.corpus = corpus;
    }

    /** Spec SS3's write-time invariant. Static so Task 5/6/9 can check eligibility without a
     *  service instance where that's more convenient. */
    public static boolean isEligible(String counterpartyKey, CounterpartyType counterpartyType) {
        return counterpartyKey != null && counterpartyKey.startsWith("vpa:")
                && (counterpartyType == CounterpartyType.BUSINESS
                    || counterpartyType == CounterpartyType.FINANCIAL_INSTITUTION);
    }

    /** Trusted-only, per spec SS9 -- Disputed/Revalidating rows are corpus knowledge but never a
     *  suggestion. */
    public Optional<String> findTrustedSuggestion(String counterpartyKey, CounterpartyType counterpartyType,
                                                   Transaction.Type direction) {
        if (!isEligible(counterpartyKey, counterpartyType)) return Optional.empty();
        return corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction)
                .filter(row -> row.getStatus() == SharedMerchantCategory.Status.TRUSTED)
                .map(SharedMerchantCategory::getCategory);
    }

    @Transactional
    public void recordObservation(UUID userId, String counterpartyKey, CounterpartyType counterpartyType,
                                   Transaction.Type direction, String category) {
        if (!isEligible(counterpartyKey, counterpartyType)) return;

        CounterpartyCategoryObservation obs = new CounterpartyCategoryObservation();
        obs.setCounterpartyKey(counterpartyKey);
        obs.setDirection(direction);
        obs.setCategory(category);
        obs.setUserId(userId);
        obs.setCounterpartyTypeAtVote(counterpartyType);
        observations.save(obs);

        recomputeAndPromote(counterpartyKey, direction);
    }

    private void recomputeAndPromote(String counterpartyKey, Transaction.Type direction) {
        List<CounterpartyCategoryObservation> all =
                observations.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        Tier tier = computeTier(all);
        if (tier == null) return; // still Empty/Provisional -- fewer than 3 distinct voters

        SharedMerchantCategory row = corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction)
                .orElseGet(() -> {
                    SharedMerchantCategory fresh = new SharedMerchantCategory();
                    fresh.setCounterpartyKey(counterpartyKey);
                    fresh.setDirection(direction);
                    fresh.setPromotedAt(Instant.now());
                    return fresh;
                });
        applyTier(row, tier);
        corpus.save(row);
    }

    private static void applyTier(SharedMerchantCategory row, Tier tier) {
        row.setStatus(tier.status());
        row.setCategory(tier.category());
        row.setCategoryDistribution(tier.distribution());
        row.setDistinctUserCount(tier.distinctUserCount());
        row.setLastRecomputedAt(Instant.now());
    }

    record Tier(SharedMerchantCategory.Status status, String category,
                Map<String, BigDecimal> distribution, int distinctUserCount) {}

    /** @return null if fewer than 3 distinct voters (Empty/Provisional -- no corpus row yet). */
    static Tier computeTier(List<CounterpartyCategoryObservation> obs) {
        Set<UUID> distinctUsers = obs.stream().map(CounterpartyCategoryObservation::getUserId)
                .collect(Collectors.toSet());
        if (distinctUsers.size() < TRUSTED_MIN_DISTINCT_VOTERS) return null;

        Map<String, Long> rawCounts = obs.stream()
                .collect(Collectors.groupingBy(CounterpartyCategoryObservation::getCategory, Collectors.counting()));

        Instant now = Instant.now();
        Map<String, BigDecimal> weighted = new HashMap<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (CounterpartyCategoryObservation o : obs) {
            BigDecimal weight = decayWeight(now, o.getCreatedAt());
            weighted.merge(o.getCategory(), weight, BigDecimal::add);
            totalWeight = totalWeight.add(weight);
        }

        String winner = weighted.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        BigDecimal winnerShare = totalWeight.signum() == 0 ? BigDecimal.ZERO
                : weighted.get(winner).divide(totalWeight, 6, RoundingMode.HALF_UP);
        long winnerRawVotes = rawCounts.getOrDefault(winner, 0L);

        Map<String, BigDecimal> distribution = new HashMap<>();
        for (var e : weighted.entrySet()) {
            distribution.put(e.getKey(), totalWeight.signum() == 0 ? BigDecimal.ZERO
                    : e.getValue().divide(totalWeight, 6, RoundingMode.HALF_UP));
        }

        boolean trusted = winnerShare.compareTo(TRUSTED_MIN_SHARE) >= 0
                && winnerRawVotes >= TRUSTED_MIN_WINNING_VOTES;
        var status = trusted ? SharedMerchantCategory.Status.TRUSTED : SharedMerchantCategory.Status.DISPUTED;
        return new Tier(status, winner, distribution, distinctUsers.size());
    }

    static BigDecimal decayWeight(Instant now, Instant createdAt) {
        double ageDays = Duration.between(createdAt, now).toDays();
        return BigDecimal.valueOf(Math.pow(0.5, ageDays / DECAY_HALF_LIFE_DAYS));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusServiceTest`
Expected: PASS, 7/7.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/SharedCorpusService.java \
        backend/src/test/java/com/finora/service/SharedCorpusServiceTest.java
git commit -m "feat(rules): promote corpus rows once corroborated by 3+ distinct voters"
```

---

## Task 3: Contradiction handling (Revalidating)

**Files:**
- Modify: `backend/src/main/java/com/finora/service/SharedCorpusService.java`
- Modify: `backend/src/test/java/com/finora/service/SharedCorpusServiceTest.java`

**Interfaces:**
- Consumes: everything from Task 2.
- Produces: no new public methods — `recordObservation` now also handles spec SS7's contradiction rule internally.

- [ ] **Step 1: Write the failing tests**

```java
// append to SharedCorpusServiceTest.java

    @Test
    void recordObservation_contradictsTrustedRow_flipsToRevalidatingImmediately() {
        SharedMerchantCategory trusted = new SharedMerchantCategory();
        trusted.setCounterpartyKey("vpa:kronos");
        trusted.setDirection(Transaction.Type.INCOME);
        trusted.setStatus(SharedMerchantCategory.Status.TRUSTED);
        trusted.setCategory("Salary");
        trusted.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        trusted.setDistinctUserCount(8);
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(trusted));

        // One contradicting vote against an 8-voter Trusted row -- spec SS7: flips immediately,
        // regardless of how lopsided the history is (an aggregate recompute would never flip this).
        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus, atLeastOnce()).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SharedMerchantCategory.Status.REVALIDATING);
        assertThat(saved.getRevalidatingSince()).isNotNull();
        // The Trusted category itself is untouched while cooling down -- only status changes.
        assertThat(saved.getCategory()).isEqualTo("Salary");
    }

    @Test
    void recordObservation_agreesWithTrustedRow_doesNotEnterRevalidating() {
        SharedMerchantCategory trusted = new SharedMerchantCategory();
        trusted.setCounterpartyKey("vpa:zepto");
        trusted.setDirection(Transaction.Type.EXPENSE);
        trusted.setStatus(SharedMerchantCategory.Status.TRUSTED);
        trusted.setCategory("Shopping");
        trusted.setCategoryDistribution(Map.of("Shopping", BigDecimal.ONE));
        trusted.setDistinctUserCount(5);
        when(corpus.findByCounterpartyKeyAndDirection("vpa:zepto", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(trusted));

        service.recordObservation(UUID.randomUUID(), "vpa:zepto", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
    }

    @Test
    void recordObservation_whileRevalidatingAndCooldownNotElapsed_staysRevalidating() {
        SharedMerchantCategory revalidating = new SharedMerchantCategory();
        revalidating.setCounterpartyKey("vpa:kronos");
        revalidating.setDirection(Transaction.Type.INCOME);
        revalidating.setStatus(SharedMerchantCategory.Status.REVALIDATING);
        revalidating.setCategory("Salary");
        revalidating.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        revalidating.setDistinctUserCount(8);
        revalidating.setRevalidatingSince(Instant.now().minus(Duration.ofDays(1)));
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(revalidating));
        // Only 1 observation recorded since revalidatingSince (this one) -- fewer than the
        // 3-observation exit condition, and 1 day is far short of the 90-day exit condition.
        when(observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(1L);

        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        // Still Revalidating -- the cooldown exit condition hasn't been met, so no full recompute
        // runs yet, and the row must not silently flip back to Trusted on this observation alone.
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.REVALIDATING);
    }

    @Test
    void recordObservation_whileRevalidatingAndThreeObservationsElapsed_recomputesNormally() {
        SharedMerchantCategory revalidating = new SharedMerchantCategory();
        revalidating.setCounterpartyKey("vpa:kronos");
        revalidating.setDirection(Transaction.Type.INCOME);
        revalidating.setStatus(SharedMerchantCategory.Status.REVALIDATING);
        revalidating.setCategory("Salary");
        revalidating.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        revalidating.setDistinctUserCount(8);
        revalidating.setRevalidatingSince(Instant.now().minus(Duration.ofDays(5)));
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(revalidating));
        when(observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(3L);
        // The full history (mocked) now has 8 old Salary votes + 3 new Business Expenses votes --
        // decay-weighted, the old majority (older, per DECAY_HALF_LIFE_DAYS) still wins here, so
        // this asserts the mandatory-cooldown mechanism runs a real recompute, not that any
        // particular category wins it -- see Task 2's computeTier tests for the decay math itself.
        List<CounterpartyCategoryObservation> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(observationOf("Salary", Instant.now().minus(Duration.ofDays(400))));
        for (int i = 0; i < 3; i++) history.add(observationOf("Business Expenses", Instant.now()));
        when(observations.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(history);

        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus, atLeastOnce()).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isIn(SharedMerchantCategory.Status.TRUSTED, SharedMerchantCategory.Status.DISPUTED);
        assertThat(saved.getRevalidatingSince()).isNull();
    }

    private static CounterpartyCategoryObservation observationOf(String category, Instant createdAt) {
        CounterpartyCategoryObservation o = new CounterpartyCategoryObservation();
        o.setCounterpartyKey("vpa:kronos");
        o.setDirection(Transaction.Type.INCOME);
        o.setCategory(category);
        o.setUserId(UUID.randomUUID());
        o.setCounterpartyTypeAtVote(CounterpartyType.BUSINESS);
        o.setCreatedAt(createdAt);
        return o;
    }
```

Add the matching imports (`ArgumentCaptor`, `java.time.Duration`) to the test file's import list.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusServiceTest`
Expected: FAIL — the 4 new tests fail (no Revalidating handling yet); the 7 from Task 2 still pass.

- [ ] **Step 3: Implement contradiction handling**

Replace `recordObservation` and add the helper methods:

```java
    static final int REVALIDATION_MIN_OBSERVATIONS = 3;
    static final int REVALIDATION_MAX_DAYS = 90;

    @Transactional
    public void recordObservation(UUID userId, String counterpartyKey, CounterpartyType counterpartyType,
                                   Transaction.Type direction, String category) {
        if (!isEligible(counterpartyKey, counterpartyType)) return;

        CounterpartyCategoryObservation obs = new CounterpartyCategoryObservation();
        obs.setCounterpartyKey(counterpartyKey);
        obs.setDirection(direction);
        obs.setCategory(category);
        obs.setUserId(userId);
        obs.setCounterpartyTypeAtVote(counterpartyType);
        observations.save(obs);

        SharedMerchantCategory existing =
                corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction).orElse(null);

        if (existing != null && existing.getStatus() == SharedMerchantCategory.Status.TRUSTED
                && !category.equals(existing.getCategory())) {
            // Spec SS7: a contradiction against an already-Trusted row flips it immediately,
            // regardless of the historical vote count -- deliberately NOT gated on whether the
            // full decay-weighted recompute below would actually flip the winner, which for a
            // well-established row it typically would not.
            existing.setStatus(SharedMerchantCategory.Status.REVALIDATING);
            existing.setRevalidatingSince(Instant.now());
            existing.setLastRecomputedAt(Instant.now());
            corpus.save(existing);
            return;
        }

        if (existing != null && existing.getStatus() == SharedMerchantCategory.Status.REVALIDATING
                && !revalidationWindowElapsed(existing, counterpartyKey, direction)) {
            return; // still cooling down -- stays Revalidating, no recompute yet
        }

        recomputeAndPromote(counterpartyKey, direction);
    }

    private boolean revalidationWindowElapsed(SharedMerchantCategory row, String counterpartyKey,
                                               Transaction.Type direction) {
        long daysSince = Duration.between(row.getRevalidatingSince(), Instant.now()).toDays();
        if (daysSince >= REVALIDATION_MAX_DAYS) return true;
        long countSince = observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(
                counterpartyKey, direction, row.getRevalidatingSince());
        return countSince >= REVALIDATION_MIN_OBSERVATIONS;
    }
```

And in `recomputeAndPromote`, clear `revalidatingSince` when a row is found (an existing row leaving Revalidating always lands on Trusted or Disputed, never keeps a stale timestamp):

```java
    private void recomputeAndPromote(String counterpartyKey, Transaction.Type direction) {
        List<CounterpartyCategoryObservation> all =
                observations.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        Tier tier = computeTier(all);
        if (tier == null) return;

        SharedMerchantCategory row = corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction)
                .orElseGet(() -> {
                    SharedMerchantCategory fresh = new SharedMerchantCategory();
                    fresh.setCounterpartyKey(counterpartyKey);
                    fresh.setDirection(direction);
                    fresh.setPromotedAt(Instant.now());
                    return fresh;
                });
        applyTier(row, tier);
        row.setRevalidatingSince(null);
        corpus.save(row);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusServiceTest`
Expected: PASS, 11/11.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/SharedCorpusService.java \
        backend/src/test/java/com/finora/service/SharedCorpusServiceTest.java
git commit -m "feat(rules): flip Trusted corpus rows to Revalidating on contradiction"
```

---

## Task 4: Record observations from manual transaction corrections

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java` (5 call sites, at the lines confirmed by reading the file: ~295, ~497, ~530, ~840, plus the `queueLearning` call at ~1033 handled in Task 5's sibling pattern already gated by `t.getCounterpartyType() != PERSON`)
- Test: `backend/src/test/java/com/finora/transactions/TransactionServiceSharedCorpusTest.java` (or add to the existing `TransactionServiceTest` if one exists at that path — check first)

**Interfaces:**
- Consumes: `SharedCorpusService.recordObservation` (Task 2/3).
- Produces: nothing new — this task only adds call sites.

Each of the 4 `categorizationService.learn(...)` call sites in `TransactionService.java` (confirmed real, by line, during planning) already has a fully-typed `Transaction t` (via `t.applyCounterpartyTyping(...)` at creation, or loaded from the DB with the columns already populated) and a `Category category` in scope. Add `sharedCorpusService.recordObservation(userId, t.getCounterpartyKey(), t.getCounterpartyType(), t.getTxnType(), category.getName())` immediately after each `categorizationService.learn(...)` call — reusing the already-computed typing rather than re-deriving it, per `CounterpartyTyping`'s own "one derivation, many callers" design.

- [ ] **Step 1: Add `SharedCorpusService` to `TransactionService`'s constructor**

Find `TransactionService`'s constructor and field declarations (near the top of the class, alongside `categorizationService`). Add:

```java
    private final SharedCorpusService sharedCorpusService;
```

Append it as the LAST constructor parameter (after the existing `transactionGraphService` param) rather than inserting it between existing positional params — this constructor already has 15 parameters, confirmed by reading `TransactionServiceTest.java`'s instantiation call, and appending keeps this a minimal, non-reordering diff. Assign `this.sharedCorpusService = sharedCorpusService;` alongside the existing assignments.

- [ ] **Step 2: Update the test file's constructor call and add the failing tests**

In `TransactionServiceTest.java`, confirmed by reading it during planning: add a field `private SharedCorpusService sharedCorpusService;`, mock it in `setUp()` (`sharedCorpusService = mock(SharedCorpusService.class);`), and append it as the last argument to the existing `new TransactionService(...)` call (currently ending `..., transactionGroupingService, reconciliationMetrics, transactionGraphService);` — becomes `..., transactionGraphService, sharedCorpusService);`).

Add these two tests, mirroring the file's own existing `create_withExplicitCategory_resolvesMerchantAndLearnsFromIt_doesNotFlagForReview` (line 533) and `create_typesTheCounterparty_evenWhenTheUserSuppliedTheCategoryThemselves` (line 1774) fixture style exactly — same `TransactionDto.CreateRequest` constructor, same mock setup pattern:

```java
    @Test
    void create_withExplicitCategoryOnEligibleBusinessCounterparty_recordsSharedCorpusObservation() {
        when(categorizationService.resolveMerchantId(eq(userId), anyString())).thenReturn(UUID.randomUUID());
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Shopping"))).thenReturn(dummyCategory);

        // Confirmed BUSINESS-typed, vpa:zeptoonline-keyed by this codebase's own real classifier
        // pipeline -- same narration this session's own shared-corpus audit measured directly.
        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), "Shopping", LocalDate.now(),
                "UPI/ZEPTO/ZEPTOONLINE@YBL/0000000000@PTAXIS", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(sharedCorpusService).recordObservation(eq(userId), eq("vpa:zeptoonline"),
                eq(com.finora.util.CounterpartyType.BUSINESS), eq(Transaction.Type.EXPENSE), eq("Shopping"));
    }

    @Test
    void create_withExplicitCategoryOnPersonCounterparty_recordsNoSharedCorpusObservation() {
        when(categorizationService.resolveMerchantId(eq(userId), anyString())).thenReturn(UUID.randomUUID());
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        // Same person-shaped narration this file's own
        // create_typesTheCounterparty_evenWhenTheUserSuppliedTheCategoryThemselves test already
        // pins as CounterpartyType.PERSON, key vpa:sampleuser.
        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "UPI-SUNIL VERMA-sampleuser@ybl-REF61", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        // SharedCorpusService.isEligible itself already refuses a PERSON-typed counterparty
        // (Task 2) -- this test pins the WIRING (recordObservation is actually called with the
        // real typed values), not the eligibility logic itself.
        verify(sharedCorpusService, never()).recordObservation(any(), any(), any(), any(), any());
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionServiceTest#create_withExplicitCategoryOnEligibleBusinessCounterparty_recordsSharedCorpusObservation`
Expected: FAIL — `sharedCorpusService` not called yet (or constructor doesn't accept it yet).

- [ ] **Step 4: Wire the 4 call sites**

At each of the following (line numbers as read during planning — re-confirm with `grep -n "categorizationService.learn("  backend/src/main/java/com/finora/transactions/TransactionService.java` before editing, since Task 1-3's changes elsewhere don't touch this file but line numbers can still drift from any other concurrent work):

1. Manual create with explicit category (~line 295): after `categorizationService.learn(userId, req.description(), category.getId());`, add:
   ```java
   sharedCorpusService.recordObservation(userId, t.getCounterpartyKey(), t.getCounterpartyType(),
           t.getTxnType(), category.getName());
   ```
2. `update(...)`'s explicit-category branch (~line 497): same pattern, using that method's local `t`/`category`.
3. `updateCategory(...)` (~line 530): same pattern.
4. `confirmMerchantCategory(...)` (~line 840): same pattern.

In each case `t` is already the fully-typed `Transaction` (its `counterpartyKey`/`counterpartyType` are already set, either by `applyCounterpartyTyping` at creation or by having been loaded from the DB) and `category` is the already-resolved `Category` with `.getName()` available — no new lookups needed.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=TransactionServiceTest`
Expected: PASS, including the 2 new tests, and no regression in the rest of the class's existing tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/transactions/TransactionService.java \
        backend/src/test/java/com/finora/transactions/TransactionServiceTest.java
git commit -m "feat(rules): record shared corpus observations from manual category corrections"
```

---

## Task 5: Record observations from the import-confirm path

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` (the `pendingLearning`/`learningEventPublisher.enqueue` loop around line 1287, confirmed during planning)
- Modify: `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java` — confirmed during planning to be the file exercising this exact path (`confirm_learns_whenRuleEngineWasConfident`, `confirm_doesNotLearn_whenLowConfidenceGuessLeftUnresolvedAsOther`)

**Interfaces:**
- Consumes: `SharedCorpusService.recordObservation` (Task 2/3).
- Produces: nothing new.

**Before writing code:** read the ~30 lines above line 1287 in `ImportService.java` where `pendingLearning` is populated, to find the exact `Transaction row` reference and category name available at that point in the loop — the plan for Task 4 already confirms `row = toInsert.get(i)` is the fully-typed transaction being inserted in the same surrounding loop; confirm `pendingLearning`'s construction uses the same `row`/category-name values so the corpus write can piggyback on the identical eligibility decision the learning queue already made (`worthLearning` from `ImportRuleLearningService.recordDecision`), rather than re-deriving it.

- [ ] **Step 1: Add the failing tests**

In `ImportServiceAskOnceTest.java`: add a field `private SharedCorpusService sharedCorpusService;`, mock it (`sharedCorpusService = mock(SharedCorpusService.class);`) in `setUp()`, and append it as the last argument to the existing `new ImportService(...)` call (currently ending `..., entitlementService, mock(AccountAggregatorGuard.class));` — becomes `..., mock(AccountAggregatorGuard.class), sharedCorpusService);`). Mirror `confirm_learns_whenRuleEngineWasConfident` and `confirm_doesNotLearn_whenLowConfidenceGuessLeftUnresolvedAsOther`'s exact `ConfirmedRow`/`requestWith(...)` fixture pattern:

```java
    @Test
    void confirm_learnsFromAnEligibleBusinessCounterparty_recordsSharedCorpusObservation() throws Exception {
        // Same narration this session's own shared-corpus audit and Task 4's test both use,
        // confirmed BUSINESS-typed, vpa:zeptoonline-keyed by the real classifier pipeline.
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UPI/ZEPTO/ZEPTOONLINE@YBL/0000000000@PTAXIS",
                BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        verify(sharedCorpusService).recordObservation(eq(userId), eq("vpa:zeptoonline"),
                eq(com.finora.util.CounterpartyType.BUSINESS), eq(Transaction.Type.EXPENSE), eq("Dining"));
    }

    @Test
    void confirm_unresolvedGuessLeftAsOther_recordsNoSharedCorpusObservation() throws Exception {
        var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UPI/ZEPTO/ZEPTOONLINE@YBL/0000000000@PTAXIS",
                BigDecimal.valueOf(500), "EXPENSE", "Other", true, "default", null, false, null, null);

        importService.confirm(userId, dummyFile(), requestWith(row));

        // Same eligible BUSINESS counterparty as the test above -- the difference here is
        // "default"/"Other" (an unresolved guess, per CategorizationService.isUnconfirmedGuess),
        // which teaches neither the per-user learning map nor the shared corpus. Confirms this
        // task's wiring rides the SAME worthLearning decision the learning queue already made,
        // rather than a second, looser check.
        verify(sharedCorpusService, never()).recordObservation(any(), any(), any(), any(), any());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=ImportServiceAskOnceTest`
Expected: FAIL — `sharedCorpusService` not called yet (or constructor doesn't accept it yet).

- [ ] **Step 3: Wire the observation write into the confirm loop**

In `ImportService.java`, add `SharedCorpusService sharedCorpusService` as the LAST constructor parameter (after `AccountAggregatorGuard`, confirmed as the current last parameter by reading `ImportServiceAskOnceTest.java`'s instantiation call) — appending, not inserting between the existing ~18 positional params, for the same minimal-diff reason as Task 4. In the `for (int i = 0; i < toInsert.size(); i++)` loop (around line 1287), for each row whose `pendingLearning` entry indicates it was worth learning, add:

```java
if (SharedCorpusService.isEligible(row.getCounterpartyKey(), row.getCounterpartyType())) {
    sharedCorpusService.recordObservation(userId, row.getCounterpartyKey(), row.getCounterpartyType(),
            row.getTxnType(), /* the row's confirmed category name, from the same source pendingLearning's categoryId already resolves */);
}
```

Exact placement and the category-name source depend on how `pendingLearning` correlates rows to category names — found by the pre-Step-1 investigation above; mirror that correlation exactly rather than introducing a second, parallel lookup.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ImportServiceAskOnceTest`
Expected: PASS.

- [ ] **Step 5: Run the full import test suite for regressions**

Run: `cd backend && ./mvnw -q test -Dtest=com.finora.imports.**`
Expected: PASS, no regressions from adding the new constructor dependency.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java
git commit -m "feat(rules): record shared corpus observations from import-confirm corrections"
```

---

## Task 6: Retention sweep for unpromoted observations

**Files:**
- Create: `backend/src/main/java/com/finora/service/SharedCorpusRetentionSweepService.java`
- Modify: `backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java` (replace the placeholder query from Task 1 with the real two-cutoff version)
- Test: `backend/src/test/java/com/finora/service/SharedCorpusRetentionSweepServiceTest.java`

**Interfaces:**
- Consumes: `CounterpartyCategoryObservationRepository`, `SharedMerchantCategoryRepository`.
- Produces: `SharedCorpusRetentionSweepService.sweep()` (void, `@Scheduled`).

- [ ] **Step 1: Replace the Task 1 placeholder query with the real two-cutoff version**

```java
    /** Task 6: keys with exactly 1 distinct voter, unpromoted, whose newest observation predates
     *  {@code cutoff1Voter} (spec SS4: 6 months) -- OR 2 distinct voters, unpromoted, predating
     *  {@code cutoff2Voters} (12 months). Never returns a key with a shared_merchant_category row
     *  (promoted observations are retained indefinitely). Bounded by {@code limit}, mirroring
     *  CounterpartyBackfillSweepService's bounded-batch precedent. */
    @Query(value = """
           SELECT o.counterparty_key, o.direction
           FROM counterparty_category_observation o
           LEFT JOIN shared_merchant_category c
               ON c.counterparty_key = o.counterparty_key AND c.direction = o.direction
           WHERE c.id IS NULL
           GROUP BY o.counterparty_key, o.direction
           HAVING (COUNT(DISTINCT o.user_id) = 1 AND MAX(o.created_at) < :cutoff1Voter)
               OR (COUNT(DISTINCT o.user_id) = 2 AND MAX(o.created_at) < :cutoff2Voters)
           LIMIT :limit
           """, nativeQuery = true)
    List<Object[]> findUnpromotedKeysPastRetention(@Param("cutoff1Voter") Instant cutoff1Voter,
                                                     @Param("cutoff2Voters") Instant cutoff2Voters,
                                                     @Param("limit") int limit);
```

Delete the old single-cutoff version this replaces.

- [ ] **Step 2: Write the failing test**

```java
// backend/src/test/java/com/finora/service/SharedCorpusRetentionSweepServiceTest.java
package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SharedCorpusRetentionSweepServiceTest {

    private CounterpartyCategoryObservationRepository observations;
    private SharedCorpusRetentionSweepService sweep;

    @BeforeEach
    void setUp() {
        observations = mock(CounterpartyCategoryObservationRepository.class);
        sweep = new SharedCorpusRetentionSweepService(observations);
    }

    @Test
    void sweep_deletesEachExpiredUnpromotedKey() {
        when(observations.findUnpromotedKeysPastRetention(any(), any(), anyInt()))
                .thenReturn(List.of(new Object[]{"vpa:oneoffperson", "EXPENSE"}))
                .thenReturn(List.of()); // second call: nothing left, loop stops

        sweep.sweep();

        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:oneoffperson", Transaction.Type.EXPENSE);
    }

    @Test
    void sweep_processesInBoundedBatchesUntilExhausted() {
        when(observations.findUnpromotedKeysPastRetention(any(), any(), anyInt()))
                .thenReturn(List.of(new Object[]{"vpa:a", "EXPENSE"}))
                .thenReturn(List.of(new Object[]{"vpa:b", "EXPENSE"}))
                .thenReturn(List.of());

        sweep.sweep();

        verify(observations, times(3)).findUnpromotedKeysPastRetention(any(), any(), anyInt());
        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:a", Transaction.Type.EXPENSE);
        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:b", Transaction.Type.EXPENSE);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — `SharedCorpusRetentionSweepService` doesn't exist.

- [ ] **Step 4: Implement the sweep service**

```java
// backend/src/main/java/com/finora/service/SharedCorpusRetentionSweepService.java
package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Purges unpromoted observations past their retention window -- spec SS4: 6 months at 1 distinct
 * voter, 12 months at 2. A promoted (shared_merchant_category) key is never touched here; its
 * observations are corroborated evidence, retained indefinitely. Bounded batches, same shape as
 * CounterpartyBackfillSweepService, so a large backlog can't hold one sweep run indefinitely.
 */
@Component
public class SharedCorpusRetentionSweepService {

    private static final Logger log = LoggerFactory.getLogger(SharedCorpusRetentionSweepService.class);
    private static final int BATCH_SIZE = 500;

    private final CounterpartyCategoryObservationRepository observations;

    public SharedCorpusRetentionSweepService(CounterpartyCategoryObservationRepository observations) {
        this.observations = observations;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void sweep() {
        Instant cutoff1Voter = Instant.now().minus(180, ChronoUnit.DAYS);
        Instant cutoff2Voters = Instant.now().minus(365, ChronoUnit.DAYS);

        int purged = 0;
        while (true) {
            List<Object[]> expired = observations.findUnpromotedKeysPastRetention(
                    cutoff1Voter, cutoff2Voters, BATCH_SIZE);
            if (expired.isEmpty()) break;
            for (Object[] row : expired) {
                String counterpartyKey = (String) row[0];
                Transaction.Type direction = Transaction.Type.valueOf((String) row[1]);
                observations.deleteByCounterpartyKeyAndDirection(counterpartyKey, direction);
                purged++;
            }
            if (expired.size() < BATCH_SIZE) break;
        }
        if (purged > 0) {
            log.info("Shared corpus retention sweep purged {} unpromoted, expired observation key(s)", purged);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusRetentionSweepServiceTest`
Expected: PASS, 2/2.

- [ ] **Step 6: Confirm `@EnableScheduling` is already active**

Run: `grep -rn "@EnableScheduling" backend/src/main/java`
Expected: at least one match (this codebase already runs other `@Scheduled` jobs, e.g. Gmail sync backoff). If none exists, add `@EnableScheduling` to the main application class — but per the grep, this is expected to already be present.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/SharedCorpusRetentionSweepService.java \
        backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java \
        backend/src/test/java/com/finora/service/SharedCorpusRetentionSweepServiceTest.java
git commit -m "feat(rules): purge unpromoted observations past their retention window"
```

---

## Task 7: Fyn categorization-fallback plumbing

**Files:**
- Modify: `backend/src/main/java/com/finora/config/FynProperties.java`
- Modify: `backend/src/main/java/com/finora/service/FynAvailabilityGuard.java`
- Modify: `backend/src/test/java/com/finora/service/FynAvailabilityGuardTest.java` (locate via `find` first — if it doesn't exist, create it following `FynCostGovernanceServiceTest.java`'s style)

**Interfaces:**
- Produces: `FynProperties.isCategorizationEnabled()`/`setCategorizationEnabled(boolean)`; `FynAvailabilityGuard.categorizationAvailableFor(UUID userId)` returns `boolean`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void categorizationAvailableFor_allConditionsMet_true() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.hasApiKey()).thenReturn(true);
        when(properties.isCategorizationEnabled()).thenReturn(true);
        when(costGovernanceService.monthlyBudget())
                .thenReturn(new FynCostGovernanceService.MonthlyBudget(BigDecimal.ZERO, BigDecimal.TEN,
                        FynCostGovernanceService.BudgetStatus.OK));
        when(costGovernanceService.userDailyCapReached(userId)).thenReturn(false);

        assertThat(guard.categorizationAvailableFor(userId)).isTrue();
    }

    @Test
    void categorizationAvailableFor_featureFlagOff_false() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.hasApiKey()).thenReturn(true);
        when(properties.isCategorizationEnabled()).thenReturn(false);

        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
    }

    @Test
    void categorizationAvailableFor_userDailyCapReached_false() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.hasApiKey()).thenReturn(true);
        when(properties.isCategorizationEnabled()).thenReturn(true);
        when(costGovernanceService.monthlyBudget())
                .thenReturn(new FynCostGovernanceService.MonthlyBudget(BigDecimal.ZERO, BigDecimal.TEN,
                        FynCostGovernanceService.BudgetStatus.OK));
        when(costGovernanceService.userDailyCapReached(userId)).thenReturn(true);

        assertThat(guard.categorizationAvailableFor(userId)).isFalse();
    }
```

Read the existing test file first (if present) to match its exact `@BeforeEach`/mock-setup style before appending these; adapt field names accordingly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — `isCategorizationEnabled`/`categorizationAvailableFor` don't exist.

- [ ] **Step 3: Add the property**

In `FynProperties.java`, alongside the existing `chatEnabled`/`insightsEnabled`/`importAssistEnabled`:

```java
    private boolean categorizationEnabled = true;
    ...
    public boolean isCategorizationEnabled() { return categorizationEnabled; }
    public void setCategorizationEnabled(boolean categorizationEnabled) { this.categorizationEnabled = categorizationEnabled; }
```

- [ ] **Step 4: Add the guard method**

In `FynAvailabilityGuard.java`, per-user like `chatAvailableFor`/`insightsAvailableFor` (a categorization call is tied to one user's own transaction, same reasoning as chat):

```java
    public boolean categorizationAvailableFor(UUID userId) {
        return available() && properties.isCategorizationEnabled() && !costGovernanceService.userDailyCapReached(userId);
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=FynAvailabilityGuardTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/config/FynProperties.java \
        backend/src/main/java/com/finora/service/FynAvailabilityGuard.java \
        backend/src/test/java/com/finora/service/FynAvailabilityGuardTest.java
git commit -m "feat(rules): add a categorization feature flag/guard to Fyn's availability check"
```

---

## Task 8: `FynCategorizationFallbackService`

**Files:**
- Create: `backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java`
- Test: `backend/src/test/java/com/finora/service/FynCategorizationFallbackServiceTest.java`

**Interfaces:**
- Consumes: `FynAvailabilityGuard.categorizationAvailableFor` (Task 7), `LlmClient`, `AiAuditLogRepository`, `SharedMerchantCategoryAiSuggestionRepository` (Task 1), `FynPricing` (existing).
- Produces: `FynCategorizationFallbackService.suggest(UUID userId, String counterpartyKey, Transaction.Type direction, String description)` returns `Optional<String>`.

Mirrors `FynImportDiagnosisService`'s exact shape (Task 8's precedent): not `@Transactional` for the same HikariCP pool-exhaustion reason, audit-logs on both success and failure, narrow PII-scrubbed prompt (only the narration text, never account/user-identifying data beyond what's already in the description).

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/FynCategorizationFallbackServiceTest.java
package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.SharedMerchantCategoryAiSuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FynCategorizationFallbackServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository;
    private FynCategorizationFallbackService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        aiSuggestionRepository = mock(SharedMerchantCategoryAiSuggestionRepository.class);
        service = new FynCategorizationFallbackService(availabilityGuard, llmClient,
                aiAuditLogRepository, aiSuggestionRepository);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void suggest_available_returnsCategoryAndUpsertsSuggestionAndAudits() {
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(
                "Dining", List.of(), "claude-haiku-4-5-20251001", 50, 5, "end_turn"));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).contains("Dining");
        verify(aiSuggestionRepository).upsert(eq("vpa:newcafe"), eq("EXPENSE"), eq("Dining"),
                eq("claude-haiku-4-5-20251001"), any());
        verify(aiAuditLogRepository).save(any(AiAuditLog.class));
    }

    @Test
    void suggest_notAvailable_returnsEmptyWithoutCallingLlm() {
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(false);

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void suggest_llmThrows_writesFailureAuditLogAndReturnsEmpty() {
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verifyNoInteractions(aiSuggestionRepository);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement the service**

```java
// backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java
package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.SharedMerchantCategoryAiSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Fyn categorization fallback -- spec SS8. Consulted only when the shared corpus and every
 * deterministic layer have nothing for a key. Mirrors FynImportDiagnosisService's shape exactly:
 * narrow PII-scrubbed prompt, audit-logged on success and failure, deliberately not
 * @Transactional (same HikariCP pool-exhaustion reasoning -- this also makes an outbound HTTP call).
 */
@Service
public class FynCategorizationFallbackService {

    private static final Logger log = LoggerFactory.getLogger(FynCategorizationFallbackService.class);

    private static final String PROMPT_VERSION = "categorization-fallback-v1";
    private static final String TOOL_NAME = "SUGGEST_CATEGORY";
    private static final int MAX_TOKENS = 20;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, suggesting a spending category for a bank transaction narration. You are \
            given only the raw narration text -- never the amount, account, or any other \
            user-identifying detail. Reply with a short category name only (e.g. "Dining", \
            "Shopping", "Investments"), nothing else. If the narration gives you nothing to go on, \
            reply with exactly "Other".
            """;

    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository;

    public FynCategorizationFallbackService(FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                             AiAuditLogRepository aiAuditLogRepository,
                                             SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository) {
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.aiSuggestionRepository = aiSuggestionRepository;
    }

    public Optional<String> suggest(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        LlmRequest request = LlmRequest.singleTurn(SYSTEM_PROMPT, description, MAX_TOKENS);
        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            writeAuditLog(userId, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), e.getMessage());
            return Optional.empty();
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("Fyn categorization-fallback call succeeded but has no known price for model {}",
                    completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(),
                cost, latencyMs, costError);

        if (completion.content() == null || completion.content().isBlank()) {
            return Optional.empty();
        }

        String category = completion.content().trim();
        aiSuggestionRepository.upsert(counterpartyKey, direction.name(), category, completion.model(), Instant.now());
        return Optional.of(category);
    }

    private void writeAuditLog(UUID userId, String model, int tokensIn, int tokensOut,
                                BigDecimal cost, int latencyMs, String error) {
        AiAuditLog auditLog = new AiAuditLog();
        auditLog.setUserId(userId);
        auditLog.setModel(model != null ? model : "unknown");
        auditLog.setPromptVersion(PROMPT_VERSION);
        auditLog.setToolName(TOOL_NAME);
        auditLog.setTokensIn(tokensIn);
        auditLog.setTokensOut(tokensOut);
        auditLog.setCost(cost);
        auditLog.setLatencyMs(latencyMs);
        auditLog.setError(error);
        aiAuditLogRepository.save(auditLog);
        if (error != null) {
            log.warn("Fyn categorization-fallback call issue (user {}): {}", userId, error);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=FynCategorizationFallbackServiceTest`
Expected: PASS, 3/3.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java \
        backend/src/test/java/com/finora/service/FynCategorizationFallbackServiceTest.java
git commit -m "feat(rules): add Fyn categorization fallback, mirroring FynImportDiagnosisService"
```

---

## Task 9: Waterfall wiring

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java` (add `SHARED_CORPUS`, `AI_FALLBACK` to `DecisionSource`)
- Modify: `backend/src/main/java/com/finora/service/ConfidenceEngine.java` (two new confidence constants)
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (both `suggest()` and `suggestReadOnly()`, plus `isUnconfirmedGuess`/`decisionSourceFor`)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java` (locate first; add to existing file)

**Interfaces:**
- Consumes: `SharedCorpusService.findTrustedSuggestion` (Task 2), `FynCategorizationFallbackService.suggest` (Task 8).
- Produces: two new `Suggestion.source()` string values, `"shared_corpus"` and `"ai_fallback"`.

This is the highest-risk task in the plan: `suggest()` and `suggestReadOnly()` are two independently-maintained copies of the same waterfall (confirmed by reading the current file during planning), and this codebase has a documented history of exactly this divergence (#743). Both must get the identical new steps.

- [ ] **Step 1: Add the new `DecisionSource` values**

In `Transaction.java`, extend the existing enum:

```java
    public enum DecisionSource { GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, SHARED_CORPUS,
                                  AI_FALLBACK, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED, STRUCTURAL_P2P }
```

- [ ] **Step 2: Add the new confidence constants**

In `ConfidenceEngine.java`, alongside the existing three:

```java
    /** A Trusted shared-corpus row: >=3 distinct users corroborated it, >=70% agreement (spec SS5).
     *  Real cross-user evidence, but still statistical rather than a deterministic rule match --
     *  placed between INITIAL_STRUCTURAL_CONFIDENCE and INITIAL_RULE_CONFIDENCE. */
    public static final int INITIAL_SHARED_CORPUS_CONFIDENCE = 60;

    /** A single unconfirmed LLM guess -- weaker than structural P2P detection, which at least has
     *  a measured error bound; an AI guess has none yet. */
    public static final int INITIAL_AI_FALLBACK_CONFIDENCE = 30;
```

- [ ] **Step 3: Write the failing tests**

Confirmed by reading `CategorizationServiceTest.java` during planning: the real constructor call is 8 positional args ending `..., categoryRepository, ruleEngineService, workspaceSettingsService)`, `ConfidenceEngine` is a real (non-mocked) instance since it's pure logic, and existing tests call the 2-arg `suggest(userId, description)` convenience overload — which, per Step 5's `null`-direction delegation, will keep behaving identically for every existing test with zero changes needed there. Add `sharedCorpusService`/`fynCategorizationFallbackService` mocks, append them as the last two constructor args, and add:

```java
    @Test
    void suggest_trustedCorpusEntryAndNoKeywordMatch_returnsSharedCorpusSuggestion() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());
        // A corporate-suffix ("PVT LTD") business name invented for this test -- guaranteed not
        // to collide with any real CategoryRules keyword, same reasoning as this file's own
        // existing "SOME COMPLETELY UNKNOWN VENDOR" fixture, while still classifying BUSINESS/
        // vpa:brandnewvendor via the real CounterpartyTyping pipeline (not mocked).
        when(sharedCorpusService.findTrustedSuggestion(eq("vpa:brandnewvendor"),
                eq(com.finora.entity.CounterpartyType.BUSINESS), eq(Transaction.Type.EXPENSE)))
                .thenReturn(Optional.of("Investments"));

        var suggestion = categorizationService.suggest(userId,
                "UPI-BRAND NEW COMPLETELY UNKNOWN VENTURES PVT LTD-brandnewvendor@ybl-REF881234",
                null, null, Transaction.Type.EXPENSE);

        assertThat(suggestion.category()).isEqualTo("Investments");
        assertThat(suggestion.source()).isEqualTo("shared_corpus");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.SHARED_CORPUS);
        assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_SHARED_CORPUS_CONFIDENCE);
        verifyNoInteractions(fynCategorizationFallbackService);
    }

    @Test
    void suggest_noCorpusEntry_fallsBackToAi() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());
        when(sharedCorpusService.findTrustedSuggestion(any(), any(), any())).thenReturn(Optional.empty());
        when(fynCategorizationFallbackService.suggest(eq(userId), eq("vpa:brandnewvendor"),
                eq(Transaction.Type.EXPENSE), anyString())).thenReturn(Optional.of("Dining"));

        var suggestion = categorizationService.suggest(userId,
                "UPI-BRAND NEW COMPLETELY UNKNOWN VENTURES PVT LTD-brandnewvendor@ybl-REF881234",
                null, null, Transaction.Type.EXPENSE);

        assertThat(suggestion.category()).isEqualTo("Dining");
        assertThat(suggestion.source()).isEqualTo("ai_fallback");
        assertThat(suggestion.decisionSource()).isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
        assertThat(suggestion.confidence()).isEqualTo(ConfidenceEngine.INITIAL_AI_FALLBACK_CONFIDENCE);
    }

    @Test
    void suggest_noCorpusEntryAndNoAiAnswer_fallsThroughToStructuralP2pThenOther() {
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());
        when(sharedCorpusService.findTrustedSuggestion(any(), any(), any())).thenReturn(Optional.empty());
        when(fynCategorizationFallbackService.suggest(any(), any(), any(), any())).thenReturn(Optional.empty());

        var suggestion = categorizationService.suggest(userId,
                "UPI-BRAND NEW COMPLETELY UNKNOWN VENTURES PVT LTD-brandnewvendor@ybl-REF881234",
                null, null, Transaction.Type.EXPENSE);

        // A corporate-suffix business name isn't person-shaped, so this falls all the way through
        // to Other -- confirms a shared-corpus/AI miss changes nothing about existing behavior.
        assertThat(suggestion.category()).isEqualTo("Other");
        assertThat(suggestion.source()).isEqualTo("default");
    }

    @Test
    void suggestReadOnly_matchesSuggestForTheSameSharedCorpusCase() {
        // Parity test -- the exact bug class #743 already found once between these two methods.
        UUID merchantId = UUID.randomUUID();
        when(merchantNormalizationEngine.resolveReadOnly(eq(userId), anyString()))
                .thenReturn(Optional.of(merchantWithId(merchantId)));
        when(learningRepository.findByUserIdAndMerchantId(userId, merchantId)).thenReturn(List.of());
        when(sharedCorpusService.findTrustedSuggestion(eq("vpa:brandnewvendor"),
                eq(com.finora.entity.CounterpartyType.BUSINESS), eq(Transaction.Type.EXPENSE)))
                .thenReturn(Optional.of("Investments"));

        var suggestion = categorizationService.suggestReadOnly(List.of(), userId,
                "UPI-BRAND NEW COMPLETELY UNKNOWN VENTURES PVT LTD-brandnewvendor@ybl-REF881234",
                null, null, null, Transaction.Type.EXPENSE);

        assertThat(suggestion.category()).isEqualTo("Investments");
        assertThat(suggestion.source()).isEqualTo("shared_corpus");
    }
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — constructor doesn't accept the two new dependencies yet.

- [ ] **Step 5: Add the two new source constants and wire `suggest()`**

```java
    public static final String SHARED_CORPUS_SOURCE = "shared_corpus";
    public static final String AI_FALLBACK_SOURCE = "ai_fallback";
```

Add `SharedCorpusService sharedCorpusService` and `FynCategorizationFallbackService fynCategorizationFallbackService` as constructor dependencies (alongside the existing ones).

**`direction` availability, resolved by checking the real call sites (not guessed) during planning:**

- `suggest(UUID, String, BigDecimal, String)` has exactly one real caller:
  `TransactionService.java:304`, `categorizationService.suggest(userId, req.description(), req.amount(), null)`, inside `create()`'s "no explicit category" branch. `t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"))` already runs earlier in the same method, before the category branch — so `t.getTxnType()` is available at the call site. Add `Transaction.Type direction` as this overload's new 5th parameter, and update the one real call site to `categorizationService.suggest(userId, req.description(), req.amount(), null, t.getTxnType())`. The 2-arg `suggest(UUID, String)` convenience overload (which delegates with `null, null`) has no direction available at all — extend its delegation to pass `null` for direction too, matching how it already passes `null` for amount/accountType; `findTrustedSuggestion`/the AI fallback below must treat a `null` direction as "no corpus lookup possible" (return empty) rather than throwing.
- `suggestReadOnly(List<CategoryRule>, UUID, String, BigDecimal, String, MerchantIndex)` has exactly one real caller: `TransactionNormalizer.java:543`. A local `String type = isIncome ? "INCOME" : "EXPENSE";` is already computed earlier in the same method (confirmed by reading it), so the call site becomes `categorizationService.suggestReadOnly(rules, userId, description, amount, null, merchantIndex, Transaction.Type.valueOf(type))`. Add `Transaction.Type direction` as this overload's new 6th parameter, and thread `null` through the other `suggestReadOnly` overloads that delegate to it (they have no direction either, same reasoning as the 2-arg `suggest` above).

In `suggest(UUID userId, String description, BigDecimal amount, String accountType, Transaction.Type direction)`, insert between the `suggestCategoryWithMerchantFallback` block and the `PersonToPersonTransferDetector` check:

```java
        CounterpartyTyping typing = CounterpartyTyping.of(description);
        Optional<String> corpusMatch = direction == null ? Optional.empty()
                : sharedCorpusService.findTrustedSuggestion(typing.key(), typing.type(), direction);
        if (corpusMatch.isPresent()) {
            return new Suggestion(corpusMatch.get(), SHARED_CORPUS_SOURCE, merchant.getId(),
                    Transaction.DecisionSource.SHARED_CORPUS, null, ConfidenceEngine.INITIAL_SHARED_CORPUS_CONFIDENCE);
        }
        Optional<String> aiMatch = direction == null ? Optional.empty()
                : fynCategorizationFallbackService.suggest(userId, typing.key(), direction, description);
        if (aiMatch.isPresent()) {
            return new Suggestion(aiMatch.get(), AI_FALLBACK_SOURCE, merchant.getId(),
                    Transaction.DecisionSource.AI_FALLBACK, null, ConfidenceEngine.INITIAL_AI_FALLBACK_CONFIDENCE);
        }
```

`typing` is computed once and reused for both calls (don't derive it twice). Note `SharedCorpusService.isEligible` (Task 2) already re-checks `typing.type()`/`typing.key()` internally in `findTrustedSuggestion`, so an ineligible counterparty (e.g. `PERSON`) correctly yields `Optional.empty()` here without any extra check at this call site.

- [ ] **Step 6: Apply the identical change to `suggestReadOnly`**

In `suggestReadOnly(List<CategoryRule> rules, UUID userId, String description, BigDecimal amount, String accountType, MerchantIndex merchantIndex, Transaction.Type direction)` — the actual waterfall implementation the other `suggestReadOnly` overloads delegate to — insert the exact same block (same `typing`/`corpusMatch`/`aiMatch` shape, same insertion point relative to the keyword-fallback and P2P checks) so the two methods stay identical, per this task's own risk note.

- [ ] **Step 7: Wire `isUnconfirmedGuess` and `decisionSourceFor`**

In `isUnconfirmedGuess`, add the two new sources as unconditionally unconfirmed (a shared-corpus/AI guess should always be eligible for the confidence-based review check in `needsCategoryReview`, unlike a deterministic rule match — this is a real, deliberate behavior change, not a guess: without it, every AI/corpus suggestion would silently skip the review queue regardless of confidence, identical in kind to the exact bug this method's own doc comment describes being fixed for `structural_p2p`):

```java
    public static boolean isUnconfirmedGuess(String categorySource, String category) {
        if ("default".equals(categorySource)) return "Other".equals(category);
        if (STRUCTURAL_P2P_SOURCE.equals(categorySource)) return P2P_CATEGORY.equals(category);
        if (SHARED_CORPUS_SOURCE.equals(categorySource) || AI_FALLBACK_SOURCE.equals(categorySource)) return true;
        return false;
    }
```

In `decisionSourceFor`, add the two new mappings:

```java
            case SHARED_CORPUS_SOURCE -> Transaction.DecisionSource.SHARED_CORPUS;
            case AI_FALLBACK_SOURCE -> Transaction.DecisionSource.AI_FALLBACK;
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CategorizationServiceTest`
Expected: PASS, including the 4 new tests.

- [ ] **Step 9: Run the full backend suite for regressions**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. Given the constructor signature change and the `isUnconfirmedGuess` behavior change, this is the step most likely to surface a caller this plan didn't anticipate — treat any failure here as a real gap to fix, not noise to suppress.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/finora/entity/Transaction.java \
        backend/src/main/java/com/finora/service/ConfidenceEngine.java \
        backend/src/main/java/com/finora/service/CategorizationService.java \
        backend/src/test/java/com/finora/service/CategorizationServiceTest.java
git commit -m "feat(rules): wire shared corpus and AI fallback into the categorization waterfall"
```

---

## Task 10: Observability

**Files:**
- Create: `backend/src/main/java/com/finora/dto/SharedCorpusMetricsDto.java`
- Create: `backend/src/main/java/com/finora/service/SharedCorpusMetricsService.java`
- Modify: `backend/src/main/java/com/finora/repository/SharedMerchantCategoryRepository.java` (already has `countByStatus` from Task 1)
- Modify: `backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java` (promotion-rate query)
- Test: `backend/src/test/java/com/finora/service/SharedCorpusMetricsServiceTest.java`

**Interfaces:**
- Consumes: `SharedMerchantCategoryRepository`, `CounterpartyCategoryObservationRepository`.
- Produces: `SharedCorpusMetricsService.summary()` returns `SharedCorpusMetricsDto`.

Scoped deliberately narrow for v1: the repository-level aggregate queries and a service method returning a summary DTO, per spec §10's metric list — not a full admin UI page, which is a separate, larger surface. Wiring this DTO into an admin-facing endpoint/dashboard is a reasonable follow-up once this data exists to look at, not part of this plan.

- [ ] **Step 1: Add the promotion-rate query**

```java
    // in CounterpartyCategoryObservationRepository
    @Query(value = """
           SELECT COUNT(DISTINCT o.counterparty_key || ':' || o.direction)
           FROM counterparty_category_observation o
           """, nativeQuery = true)
    long countDistinctKeysEverObserved();
```

- [ ] **Step 2: Write the failing test**

```java
// backend/src/test/java/com/finora/service/SharedCorpusMetricsServiceTest.java
package com.finora.service;

import com.finora.entity.SharedMerchantCategory;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedCorpusMetricsServiceTest {

    private SharedMerchantCategoryRepository corpus;
    private CounterpartyCategoryObservationRepository observations;
    private SharedCorpusMetricsService service;

    @BeforeEach
    void setUp() {
        corpus = mock(SharedMerchantCategoryRepository.class);
        observations = mock(CounterpartyCategoryObservationRepository.class);
        service = new SharedCorpusMetricsService(corpus, observations);
    }

    @Test
    void summary_computesPromotionRateAndTierCounts() {
        when(corpus.countByStatus(SharedMerchantCategory.Status.TRUSTED)).thenReturn(40L);
        when(corpus.countByStatus(SharedMerchantCategory.Status.DISPUTED)).thenReturn(10L);
        when(corpus.countByStatus(SharedMerchantCategory.Status.REVALIDATING)).thenReturn(2L);
        when(observations.countDistinctKeysEverObserved()).thenReturn(200L);

        SharedCorpusMetricsDto summary = service.summary();

        assertThat(summary.trustedRows()).isEqualTo(40);
        assertThat(summary.disputedRows()).isEqualTo(10);
        assertThat(summary.revalidatingRows()).isEqualTo(2);
        // (40 + 10 + 2) promoted out of 200 ever-observed keys.
        assertThat(summary.promotionRate()).isEqualByComparingTo("0.260000");
    }

    @Test
    void summary_zeroObservedKeys_promotionRateIsZeroNotDivideByZero() {
        when(observations.countDistinctKeysEverObserved()).thenReturn(0L);

        SharedCorpusMetricsDto summary = service.summary();

        assertThat(summary.promotionRate()).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test-compile`
Expected: FAIL — classes don't exist.

- [ ] **Step 4: Implement the DTO and service**

```java
// backend/src/main/java/com/finora/dto/SharedCorpusMetricsDto.java
package com.finora.dto;

import java.math.BigDecimal;

/** Spec SS10's core metrics -- row counts per tier and the promotion rate. */
public record SharedCorpusMetricsDto(long trustedRows, long disputedRows, long revalidatingRows,
                                      BigDecimal promotionRate) {}
```

```java
// backend/src/main/java/com/finora/service/SharedCorpusMetricsService.java
package com.finora.service;

import com.finora.dto.SharedCorpusMetricsDto;
import com.finora.entity.SharedMerchantCategory;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class SharedCorpusMetricsService {

    private final SharedMerchantCategoryRepository corpus;
    private final CounterpartyCategoryObservationRepository observations;

    public SharedCorpusMetricsService(SharedMerchantCategoryRepository corpus,
                                       CounterpartyCategoryObservationRepository observations) {
        this.corpus = corpus;
        this.observations = observations;
    }

    public SharedCorpusMetricsDto summary() {
        long trusted = corpus.countByStatus(SharedMerchantCategory.Status.TRUSTED);
        long disputed = corpus.countByStatus(SharedMerchantCategory.Status.DISPUTED);
        long revalidating = corpus.countByStatus(SharedMerchantCategory.Status.REVALIDATING);
        long everObserved = observations.countDistinctKeysEverObserved();

        BigDecimal promotionRate = everObserved == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trusted + disputed + revalidating)
                        .divide(BigDecimal.valueOf(everObserved), 6, RoundingMode.HALF_UP);

        return new SharedCorpusMetricsDto(trusted, disputed, revalidating, promotionRate);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SharedCorpusMetricsServiceTest`
Expected: PASS, 2/2.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/dto/SharedCorpusMetricsDto.java \
        backend/src/main/java/com/finora/service/SharedCorpusMetricsService.java \
        backend/src/main/java/com/finora/repository/CounterpartyCategoryObservationRepository.java \
        backend/src/test/java/com/finora/service/SharedCorpusMetricsServiceTest.java
git commit -m "feat(rules): add shared corpus observability metrics"
```

---

## Final verification (all tasks complete)

- [ ] Run the full backend suite: `cd backend && ./mvnw -q test` — expect PASS, zero failures.
- [ ] Run `cd backend && ./mvnw -q -o test-compile` for a clean compile with no warnings introduced.
- [ ] Re-read spec §12's launch-blocking item: confirm the eligibility gate (Task 2's `isEligible`) is exactly `vpa:` + `BUSINESS`/`FINANCIAL_INSTITUTION`, matching what was actually audited — not a looser version drifted during implementation.
- [ ] Confirm `suggest()` and `suggestReadOnly()` (Task 9) produce identical results for the same fixture, per the parity test — this is the plan's single highest-risk regression class in this codebase's own history.
- [ ] Grep for any other caller of `CategorizationService`'s constructor or `isUnconfirmedGuess`/`decisionSourceFor` that this plan's signature changes might have missed: `grep -rn "new CategorizationService(" backend/src/main/java backend/src/test/java`.
