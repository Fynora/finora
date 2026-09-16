# AI-Created Categories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the AI categorization fallback into a global "what kind of merchant is this"
understanding and a per-user "which category does this map to, or what should a new one be
called" resolution, so AI-created categories are tagged with why they exist and correctly
personalized per user instead of a single name reused across everyone.

**Architecture:** Two new Postgres tables (`merchant_understanding`, global;
`user_merchant_category_resolution`, per-user) sit behind a rewritten
`FynCategorizationFallbackService` whose public `suggest()` signature is unchanged, so
`CategorizationService`'s existing call sites need zero changes. `Category` gains one nullable
column tagging AI-created rows. Category deletion, manual correction, and concurrent requests
all hook into patterns that already exist in this codebase rather than new ones.

**Tech Stack:** Spring Boot / JPA / PostgreSQL / Flyway, the existing `LlmClient` tool-use
interface (Anthropic), React + TanStack Query (web), React Native + TanStack Query (mobile),
React + TanStack Query (admin portal), JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-15-ai-category-creation-design.md`

## Global Constraints

- Flyway migration version: `V214` (confirmed against `origin/main` immediately before Task 1 —
  re-verify before writing the migration if any time has passed, per this repo's own
  instructions).
- No AI attribution in any commit message.
- `resolveOrCreateCategory`'s existing 2-arg signature (`UUID userId, String name`) must not be
  widened in place — every existing caller and every test mocking `CategorizationService`
  depends on that exact arity, and this plan's own predecessor (the shared merchant corpus plan)
  hit a real, expensive bug from exactly this mistake (silently-unmatched Mockito stubs, not a
  compile error). Add a new 3-arg overload instead; the 2-arg one delegates to it with a null
  reason.
- `FynCategorizationFallbackService.suggest(UUID userId, String counterpartyKey,
  Transaction.Type direction, String description)`'s signature must not change —
  `CategorizationService`'s two call sites (in `suggest()` and `suggestReadOnly()`) call it
  exactly as today; only its internals change.
- Every entity in this plan uses `@Id @GeneratedValue private UUID id;` and
  `gen_random_uuid()` in its migration, matching `SharedMerchantCategory` /
  `CounterpartyCategoryObservation`'s own pattern.
- Every task ends with its own test run green before commit; no task depends on manual QA to
  call itself done.

---

### Task 1: Data model — `merchant_understanding`, `user_merchant_category_resolution`, `categories.ai_creation_reason`

**Files:**
- Create: `backend/src/main/resources/db/migration/V214__ai_category_creation.sql`
- Create: `backend/src/main/java/com/finora/entity/MerchantUnderstanding.java`
- Create: `backend/src/main/java/com/finora/entity/UserMerchantCategoryResolution.java`
- Modify: `backend/src/main/java/com/finora/entity/Category.java`
- Create: `backend/src/main/java/com/finora/repository/MerchantUnderstandingRepository.java`
- Create: `backend/src/main/java/com/finora/repository/UserMerchantCategoryResolutionRepository.java`
- Test: `backend/src/test/java/com/finora/repository/AiCategoryCreationRepositoriesIT.java`

**Interfaces:**
- Produces: `MerchantUnderstanding{id, counterpartyKey, direction, understanding, model,
  generatedAt}`; `UserMerchantCategoryResolution{id, userId, counterpartyKey, direction,
  categoryId, resolvedAt}`; `Category.getAiCreationReason()`/`setAiCreationReason(String)`.
- Produces repository methods later tasks depend on:
  `MerchantUnderstandingRepository.findByCounterpartyKeyAndDirection(String, Transaction.Type)`,
  `.upsert(String, String, String, String, Instant)`;
  `UserMerchantCategoryResolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(UUID,
  String, Transaction.Type)`, `.insertIfAbsent(UUID, String, String, UUID, Instant)` (returns
  `int` rows-affected — 0 means another request already won),
  `.upsertPinned(UUID, String, String, UUID, Instant)` (unconditional overwrite, for human
  override), `.countByUserIdAndCategoryId(UUID, UUID)`,
  `.repointCategory(UUID userId, UUID fromCategoryId, UUID toCategoryId)`.

- [ ] **Step 1: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V214__ai_category_creation.sql
-- AI-created categories -- docs/superpowers/specs/2026-09-15-ai-category-creation-design.md.
--
-- Two-tier AI resolution: merchant_understanding is global (what kind of merchant is this),
-- shared across every user; user_merchant_category_resolution is per-user (which of THIS
-- user's categories it maps to, or what a new one should be called). Reusing one cached
-- category name across users was the bug this design fixes -- category naming is inherently
-- per-user, merchant understanding is not.

ALTER TABLE categories ADD COLUMN ai_creation_reason TEXT;

CREATE TABLE merchant_understanding (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    understanding     TEXT NOT NULL,
    model             VARCHAR(64) NOT NULL,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_merchant_understanding_key_direction UNIQUE (counterparty_key, direction)
);

CREATE TABLE user_merchant_category_resolution (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    category_id       UUID NOT NULL REFERENCES categories(id),
    resolved_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_merchant_resolution_user_key_direction
        UNIQUE (user_id, counterparty_key, direction)
);

-- CategoryService.delete's dependency check (Task 6): "does this category have any resolutions
-- pointing at it" needs to be cheap, same reasoning as every other dependent lookup there.
CREATE INDEX idx_user_merchant_resolution_category
    ON user_merchant_category_resolution (category_id);
```

- [ ] **Step 2: Run the migration against a real database to confirm it applies cleanly**

Run: `cd backend && mvn -q -o flyway:info -Dflyway.url=$TEST_DB_URL` (or start the app once
against a scratch Postgres) — Expected: `V214` listed as a pending/applied migration with no
error. If no scratch DB is handy, Step 6's integration test (which boots a real Testcontainers
Postgres through `AbstractIntegrationTest`) is the real verification; this step is a fast local
sanity check where available.

- [ ] **Step 3: Write the entities**

```java
// backend/src/main/java/com/finora/entity/MerchantUnderstanding.java
package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Tier 1 of the AI-category-creation design (spec §3/§4) -- global, free-text understanding
 *  of a merchant, shared across every user. Never contains a category name. */
@Entity
@Table(name = "merchant_understanding")
public class MerchantUnderstanding {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(nullable = false, columnDefinition = "text")
    private String understanding;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public String getUnderstanding() { return understanding; }
    public void setUnderstanding(String understanding) { this.understanding = understanding; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}
```

```java
// backend/src/main/java/com/finora/entity/UserMerchantCategoryResolution.java
package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Tier 2 of the AI-category-creation design (spec §3/§4) -- per-user resolution of a
 *  merchant to a category. Stores category_id, never a name (spec §6: rename-safe by
 *  construction). One row per (user, counterparty_key, direction), ever -- upserted by AI
 *  resolution, re-upserted (never merged) by a human correction, per spec §8. */
@Entity
@Table(name = "user_merchant_category_resolution")
public class UserMerchantCategoryResolution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Transaction.Type direction;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public Transaction.Type getDirection() { return direction; }
    public void setDirection(Transaction.Type direction) { this.direction = direction; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
```

Modify `Category.java` to add the tag field, right after `color`:

```java
    @Column(name = "ai_creation_reason", columnDefinition = "text")
    private String aiCreationReason;
```

and its getter/setter alongside the others:

```java
    public String getAiCreationReason() { return aiCreationReason; }
    public void setAiCreationReason(String aiCreationReason) { this.aiCreationReason = aiCreationReason; }
```

- [ ] **Step 4: Write the repositories**

```java
// backend/src/main/java/com/finora/repository/MerchantUnderstandingRepository.java
package com.finora.repository;

import com.finora.entity.MerchantUnderstanding;
import com.finora.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface MerchantUnderstandingRepository extends JpaRepository<MerchantUnderstanding, java.util.UUID> {

    Optional<MerchantUnderstanding> findByCounterpartyKeyAndDirection(String counterpartyKey, Transaction.Type direction);

    /** Atomic upsert, same ON CONFLICT shape as SharedMerchantCategoryAiSuggestionRepository's own
     *  -- REQUIRES_NEW because the caller (MerchantUnderstandingService, Task 3) is deliberately
     *  not @Transactional, to avoid holding a DB connection across the LLM HTTP call. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO merchant_understanding
               (id, counterparty_key, direction, understanding, model, generated_at)
           VALUES (gen_random_uuid(), :counterpartyKey, :direction, :understanding, :model, :generatedAt)
           ON CONFLICT (counterparty_key, direction)
           DO UPDATE SET understanding = :understanding, model = :model, generated_at = :generatedAt
           """, nativeQuery = true)
    void upsert(@Param("counterpartyKey") String counterpartyKey, @Param("direction") String direction,
                @Param("understanding") String understanding, @Param("model") String model,
                @Param("generatedAt") Instant generatedAt);
}
```

```java
// backend/src/main/java/com/finora/repository/UserMerchantCategoryResolutionRepository.java
package com.finora.repository;

import com.finora.entity.Transaction;
import com.finora.entity.UserMerchantCategoryResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserMerchantCategoryResolutionRepository extends JpaRepository<UserMerchantCategoryResolution, UUID> {

    Optional<UserMerchantCategoryResolution> findByUserIdAndCounterpartyKeyAndDirection(
            UUID userId, String counterpartyKey, Transaction.Type direction);

    /** Spec §7's concurrency guard: a losing concurrent insert no-ops instead of throwing or
     *  overwriting -- callers check the returned row count, not an exception, to know who won.
     *  REQUIRES_NEW for the same not-@Transactional-caller reason as the AI-suggestion cache. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO user_merchant_category_resolution
               (id, user_id, counterparty_key, direction, category_id, resolved_at)
           VALUES (gen_random_uuid(), :userId, :counterpartyKey, :direction, :categoryId, :resolvedAt)
           ON CONFLICT (user_id, counterparty_key, direction) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("counterpartyKey") String counterpartyKey,
                        @Param("direction") String direction, @Param("categoryId") UUID categoryId,
                        @Param("resolvedAt") Instant resolvedAt);

    /** Human override (spec §8): unconditional overwrite, unlike insertIfAbsent -- "the most
     *  recent manual correction always wins," not "first write wins." */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO user_merchant_category_resolution
               (id, user_id, counterparty_key, direction, category_id, resolved_at)
           VALUES (gen_random_uuid(), :userId, :counterpartyKey, :direction, :categoryId, :resolvedAt)
           ON CONFLICT (user_id, counterparty_key, direction)
           DO UPDATE SET category_id = :categoryId, resolved_at = :resolvedAt
           """, nativeQuery = true)
    void upsertPinned(@Param("userId") UUID userId, @Param("counterpartyKey") String counterpartyKey,
                       @Param("direction") String direction, @Param("categoryId") UUID categoryId,
                       @Param("resolvedAt") Instant resolvedAt);

    /** Task 6's deletion-dependency count. */
    long countByUserIdAndCategoryId(UUID userId, UUID categoryId);

    /** Task 6's deletion-dependency repoint -- plain bulk update, no merge-conflict scenario
     *  possible (unlike MerchantLearningService's repointCategory): the unique constraint is on
     *  (user_id, counterparty_key, direction), not including category_id, so changing a row's
     *  category_id can never collide with another row. */
    @Modifying
    @Query("UPDATE UserMerchantCategoryResolution r SET r.categoryId = :toCategoryId " +
           "WHERE r.userId = :userId AND r.categoryId = :fromCategoryId")
    void repointCategory(@Param("userId") UUID userId, @Param("fromCategoryId") UUID fromCategoryId,
                          @Param("toCategoryId") UUID toCategoryId);
}
```

- [ ] **Step 5: Write the failing integration test**

```java
// backend/src/test/java/com/finora/repository/AiCategoryCreationRepositoriesIT.java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiCategoryCreationRepositoriesIT extends AbstractIntegrationTest {

    @Autowired private MerchantUnderstandingRepository understandingRepo;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepo;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    void understanding_upsertTwice_replacesRatherThanDuplicates() {
        understandingRepo.upsert("vpa:headsupfortails", "EXPENSE", "A pet store", "claude-haiku-4-5-20251001", Instant.now());
        understandingRepo.upsert("vpa:headsupfortails", "EXPENSE", "A pet supplies retailer", "claude-haiku-4-5-20251001", Instant.now());

        var found = understandingRepo.findByCounterpartyKeyAndDirection("vpa:headsupfortails", Transaction.Type.EXPENSE);
        assertThat(found).isPresent();
        assertThat(found.get().getUnderstanding()).isEqualTo("A pet supplies retailer");
    }

    @Test
    void resolution_insertIfAbsent_secondCallForSameKeyNoOps() {
        UUID userId = UUID.randomUUID();
        Category category = new Category();
        category.setUserId(userId);
        category.setName("Pet Care");
        category.setSystem(false);
        category = categoryRepository.save(category);

        int first = resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", category.getId(), Instant.now());
        int second = resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", UUID.randomUUID(), Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
        assertThat(resolutionRepo.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE)
                .orElseThrow().getCategoryId()).isEqualTo(category.getId());
    }

    @Test
    void resolution_upsertPinned_overwritesExisting() {
        UUID userId = UUID.randomUUID();
        Category first = categoryRepository.save(newCategory(userId, "Pet Care"));
        Category second = categoryRepository.save(newCategory(userId, "Pets"));
        resolutionRepo.insertIfAbsent(userId, "vpa:headsupfortails", "EXPENSE", first.getId(), Instant.now());

        resolutionRepo.upsertPinned(userId, "vpa:headsupfortails", "EXPENSE", second.getId(), Instant.now());

        assertThat(resolutionRepo.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE)
                .orElseThrow().getCategoryId()).isEqualTo(second.getId());
    }

    private static Category newCategory(UUID userId, String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        c.setSystem(false);
        return c;
    }
}
```

- [ ] **Step 6: Run it, verify it fails**

Run: `cd backend && mvn -q -o test -Dtest=AiCategoryCreationRepositoriesIT`
Expected: FAIL to compile (repositories/entities don't exist) if run before Steps 1/3/4, or PASS
immediately if run after them — this task's TDD unit is the migration/schema shape itself, so
"write the test first" here means "write it before running the full suite," not before writing
the schema. Run it once *before* Step 1 with just an empty repository shell to confirm it fails
for the right reason (table doesn't exist), if you want the stricter TDD loop; otherwise proceed
straight to Step 7 once Steps 1-5 are all in place.

- [ ] **Step 7: Run it again, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=AiCategoryCreationRepositoriesIT`
Expected: PASS, 3/3.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V214__ai_category_creation.sql \
        backend/src/main/java/com/finora/entity/MerchantUnderstanding.java \
        backend/src/main/java/com/finora/entity/UserMerchantCategoryResolution.java \
        backend/src/main/java/com/finora/entity/Category.java \
        backend/src/main/java/com/finora/repository/MerchantUnderstandingRepository.java \
        backend/src/main/java/com/finora/repository/UserMerchantCategoryResolutionRepository.java \
        backend/src/test/java/com/finora/repository/AiCategoryCreationRepositoriesIT.java
git commit -m "feat(rules): add data model for two-tier AI category resolution"
```

---

### Task 2: Widen `resolveOrCreateCategory` with an optional AI creation reason

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java:684` (`resolveOrCreateCategory`)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Category resolveOrCreateCategory(UUID userId, String name, String aiCreationReason)`
  — new overload. `resolveOrCreateCategory(UUID userId, String name)` (existing, 2-arg) keeps its
  exact signature, delegating to the 3-arg one with `null`. `aiCreationReason` is set on the
  entity *only* on the branch that inserts a new row; a matched existing category is returned
  untouched, reason ignored — this is what makes §3 of the spec's "matching isn't creating" rule
  real, not just documentation.

- [ ] **Step 1: Write the failing test**

```java
// in CategorizationServiceTest.java
@Test
void resolveOrCreateCategory_newName_withReason_setsReason() {
    Category created = categorizationService.resolveOrCreateCategory(userId, "Pet Care", "Pet store, no existing match");

    assertThat(created.getAiCreationReason()).isEqualTo("Pet store, no existing match");
}

@Test
void resolveOrCreateCategory_matchesExisting_reasonIgnoredNotOverwritten() {
    Category existing = categorizationService.resolveOrCreateCategory(userId, "Pet Care", null);

    Category matched = categorizationService.resolveOrCreateCategory(userId, "pet care", "some new reason");

    assertThat(matched.getId()).isEqualTo(existing.getId());
    assertThat(matched.getAiCreationReason()).isNull();
}

@Test
void resolveOrCreateCategory_twoArgOverload_stillWorksUnchanged() {
    Category created = categorizationService.resolveOrCreateCategory(userId, "Groceries");

    assertThat(created.getAiCreationReason()).isNull();
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `cd backend && mvn -q -o test -Dtest=CategorizationServiceTest#resolveOrCreateCategory_newName_withReason_setsReason`
Expected: FAIL to compile — no 3-arg overload exists yet.

- [ ] **Step 3: Implement**

```java
// CategorizationService.java, replacing the existing resolveOrCreateCategory(UUID, String):
public Category resolveOrCreateCategory(UUID userId, String name) {
    return resolveOrCreateCategory(userId, name, null);
}

/** As {@link #resolveOrCreateCategory(UUID, String)}, with an AI-generated reason attached only
 *  if this call actually creates a new category -- spec .../2026-09-15-ai-category-creation-design.md
 *  §3: matching an existing category is not creating one, so a match never gets tagged, no matter
 *  what reason was passed. {@code aiCreationReason} is null for every non-AI caller. */
public Category resolveOrCreateCategory(UUID userId, String name, String aiCreationReason) {
    if (name == null || name.isBlank()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Category name can't be blank.");
    }
    String trimmed = name.trim();
    String safeName = trimmed.length() <= MAX_CATEGORY_NAME_LENGTH ? trimmed : truncateForColumn(trimmed);
    List<Category> matches = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, safeName);
    if (!matches.isEmpty()) {
        return matches.get(0);
    }
    Category c = new Category();
    c.setUserId(userId);
    c.setName(safeName);
    c.setSystem(false);
    c.setAiCreationReason(aiCreationReason);
    return categoryRepository.save(c);
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=CategorizationServiceTest`
Expected: PASS, including the 3 new tests and every pre-existing one (the 2-arg overload's
behavior is byte-for-byte unchanged).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategorizationService.java \
        backend/src/test/java/com/finora/service/CategorizationServiceTest.java
git commit -m "feat(rules): widen resolveOrCreateCategory with an optional AI creation reason"
```

---

### Task 3: Tier 1 — `MerchantUnderstandingService`

**Files:**
- Create: `backend/src/main/java/com/finora/service/MerchantUnderstandingService.java`
- Test: `backend/src/test/java/com/finora/service/MerchantUnderstandingServiceTest.java`

**Interfaces:**
- Consumes: `LlmClient` (`complete`, `LlmRequest.withTools`, `LlmTool`), `FynAvailabilityGuard`
  (reuses `categorizationAvailableFor`, already added by the shared-corpus plan's Task 7 — no
  change needed there), `AiAuditLogRepository`, `MerchantUnderstandingRepository` (Task 1).
- Produces: `Optional<String> understand(UUID userId, String counterpartyKey, Transaction.Type
  direction, String description)` — returns the merchant's understanding (cache hit or a fresh
  tool-use call), or empty on any failure or unavailability. Never throws.

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/MerchantUnderstandingServiceTest.java
package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.MerchantUnderstanding;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.MerchantUnderstandingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantUnderstandingServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private MerchantUnderstandingRepository understandingRepository;
    private MerchantUnderstandingService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        understandingRepository = mock(MerchantUnderstandingRepository.class);
        service = new MerchantUnderstandingService(availabilityGuard, llmClient, aiAuditLogRepository, understandingRepository);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void understand_cacheHit_returnsWithoutCallingLlm() {
        MerchantUnderstanding cached = new MerchantUnderstanding();
        cached.setUnderstanding("A pet supplies retailer");
        when(understandingRepository.findByCounterpartyKeyAndDirection("vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("A pet supplies retailer");
        verifyNoInteractions(llmClient);
    }

    @Test
    void understand_cacheMiss_callsToolAndUpsertsCache() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        ToolUse toolUse = new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "A pet supplies retailer"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("A pet supplies retailer");
        verify(understandingRepository).upsert(eq("vpa:headsupfortails"), eq("EXPENSE"),
                eq("A pet supplies retailer"), eq("claude-haiku-4-5-20251001"), any());
        verify(aiAuditLogRepository).save(any(AiAuditLog.class));
    }

    @Test
    void understand_notAvailable_returnsEmptyWithoutCallingLlm() {
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(false);

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void understand_llmThrows_writesFailureAuditAndReturnsEmptyWithoutCaching() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verifyNoInteractions(understandingRepository);
    }
}
```

Note the last test asserts `verifyNoInteractions(understandingRepository)` even though the test
itself stubs `findByCounterpartyKeyAndDirection` on it — that stub is set up via `when(...)`
before the call, which does register as an interaction in strict Mockito styles; if this fails
for that reason when actually run, replace with `verify(understandingRepository,
never()).upsert(any(), any(), any(), any(), any())`, matching the equivalent assertion already
used for `FynCategorizationFallbackServiceTest`'s own failure-path test. Treat any such mismatch
as a real thing to fix, not noise to suppress.

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=MerchantUnderstandingServiceTest`
Expected: FAIL to compile — the service doesn't exist yet.

- [ ] **Step 3: Implement**

```java
// backend/src/main/java/com/finora/service/MerchantUnderstandingService.java
package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.*;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.MerchantUnderstandingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 1 of the AI-category-creation design (spec §3/§4) -- a free-text, global understanding
 * of what kind of merchant a counterparty is, cached forever across every user. Never contains a
 * category name (that's Tier 2, UserMerchantCategoryResolutionService). Deliberately not
 * @Transactional, mirroring FynCategorizationFallbackService -- avoids holding a DB connection
 * across the outbound LLM HTTP call.
 */
@Service
public class MerchantUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(MerchantUnderstandingService.class);

    private static final String PROMPT_VERSION = "merchant-understanding-v1";
    private static final String TOOL_NAME = "UNDERSTAND_MERCHANT";
    private static final int MAX_TOKENS = 80;

    private static final String SYSTEM_PROMPT = """
            You are Fyn, describing what kind of merchant a bank transaction narration belongs \
            to. You are given only the raw narration text -- never the amount, account, or any \
            other user-identifying detail. Call the understand_merchant tool with a short, \
            free-text description of the merchant's business (e.g. "Pet supplies retailer \
            selling food, toys, and grooming products"). Do not suggest a spending category --\
            describe the merchant, not how to file it.
            """;

    private static final LlmTool TOOL = new LlmTool(TOOL_NAME,
            "Record a free-text understanding of what kind of merchant this is.",
            Map.of("type", "object",
                    "properties", Map.of("understanding", Map.of("type", "string")),
                    "required", List.of("understanding")));

    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final MerchantUnderstandingRepository understandingRepository;

    public MerchantUnderstandingService(FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                         AiAuditLogRepository aiAuditLogRepository,
                                         MerchantUnderstandingRepository understandingRepository) {
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.understandingRepository = understandingRepository;
    }

    public Optional<String> understand(UUID userId, String counterpartyKey, Transaction.Type direction,
                                        String description) {
        var cached = understandingRepository.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        if (cached.isPresent()) {
            return Optional.of(cached.get().getUnderstanding());
        }
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        LlmRequest request = LlmRequest.withTools(SYSTEM_PROMPT, List.of(LlmMessage.user(description)),
                MAX_TOKENS, List.of(TOOL));
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
            log.error("Fyn merchant-understanding call succeeded but has no known price for model {}", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(), cost, latencyMs, costError);

        if (!completion.requestsToolUse()) {
            return Optional.empty();
        }
        Object raw = completion.toolUses().get(0).input().get("understanding");
        if (!(raw instanceof String understanding) || understanding.isBlank()) {
            return Optional.empty();
        }

        understandingRepository.upsert(counterpartyKey, direction.name(), understanding, completion.model(), Instant.now());
        return Optional.of(understanding);
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
            log.warn("Fyn merchant-understanding call issue (user {}): {}", userId, error);
        }
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=MerchantUnderstandingServiceTest`
Expected: PASS, 4/4 (fixing the noted `verifyNoInteractions` assertion if it turns out to be the
wrong shape once actually run — see the note after Step 1).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/MerchantUnderstandingService.java \
        backend/src/test/java/com/finora/service/MerchantUnderstandingServiceTest.java
git commit -m "feat(rules): add Tier 1 merchant-understanding AI service"
```

---

### Task 4: Tier 2 — `UserMerchantCategoryResolutionService`

**Files:**
- Create: `backend/src/main/java/com/finora/service/UserMerchantCategoryResolutionService.java`
- Test: `backend/src/test/java/com/finora/service/UserMerchantCategoryResolutionServiceTest.java`

**Interfaces:**
- Consumes: `MerchantUnderstandingService.understand(...)` (Task 3), `LlmClient`,
  `FynAvailabilityGuard`, `AiAuditLogRepository`, `UserMerchantCategoryResolutionRepository`
  (Task 1), `CategoryRepository` (existing, `findByUserId`), `CategorizationService`'s new 3-arg
  `resolveOrCreateCategory` (Task 2).
- Produces: `Optional<String> resolve(UUID userId, String counterpartyKey, Transaction.Type
  direction, String description)` — returns the resolved category *name* (matching
  `FynCategorizationFallbackService.suggest()`'s existing return shape, so Task 5 can swap it in
  without changing what callers receive), or empty if nothing could be resolved. Never throws.

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/finora/service/UserMerchantCategoryResolutionServiceTest.java
package com.finora.service;

import com.finora.entity.*;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMerchantCategoryResolutionServiceTest {

    private MerchantUnderstandingService understandingService;
    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private UserMerchantCategoryResolutionRepository resolutionRepository;
    private CategoryRepository categoryRepository;
    private CategorizationService categorizationService;
    private UserMerchantCategoryResolutionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        understandingService = mock(MerchantUnderstandingService.class);
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        resolutionRepository = mock(UserMerchantCategoryResolutionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        categorizationService = mock(CategorizationService.class);
        service = new UserMerchantCategoryResolutionService(understandingService, availabilityGuard,
                llmClient, aiAuditLogRepository, resolutionRepository, categoryRepository, categorizationService);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void resolve_cacheHit_returnsExistingCategoryNameWithoutCallingLlm() {
        UUID categoryId = UUID.randomUUID();
        UserMerchantCategoryResolution cached = new UserMerchantCategoryResolution();
        cached.setCategoryId(categoryId);
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));
        Category category = new Category();
        category.setName("Pet Care");
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        verifyNoInteractions(llmClient);
    }

    @Test
    void resolve_cacheMiss_reusesExistingCategoryTheModelNamed_createsNothing() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Pet Care");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(existing));
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "Pet Care"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", null)).thenReturn(existing);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        // Model named an EXISTING category, so no reason should be passed through as a create reason.
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", null);
    }

    @Test
    void resolve_cacheMiss_inventsNewCategory_passesReasonAndPinsResolution() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY",
                Map.of("category", "Pet Care", "reason", "Pet supplies retailer, no existing match"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 12, "tool_use"));
        Category created = new Category();
        UUID createdId = UUID.randomUUID();
        created.setUserId(userId);
        created.setName("Pet Care");
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match"))
                .thenAnswer(inv -> { created.setAiCreationReason(inv.getArgument(2)); return created; });
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match");
        verify(resolutionRepository).insertIfAbsent(eq(userId), eq("vpa:headsupfortails"), eq("EXPENSE"), any(), any());
    }

    @Test
    void resolve_understandingUnavailable_fallsThroughWithoutCallingResolutionLlm() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
        verifyNoInteractions(categorizationService);
    }

    @Test
    void resolve_llmThrows_writesFailureAuditReturnsEmptyNoPlaceholderRow() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verify(resolutionRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=UserMerchantCategoryResolutionServiceTest`
Expected: FAIL to compile — the service doesn't exist yet.

- [ ] **Step 3: Implement**

```java
// backend/src/main/java/com/finora/service/UserMerchantCategoryResolutionService.java
package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.UserMerchantCategoryResolution;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.*;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tier 2 of the AI-category-creation design (spec §3/§4) -- resolves ONE user's category for a
 * merchant, given Tier 1's (MerchantUnderstandingService) global understanding of it. Cached per
 * (user, counterparty_key, direction) forever, per spec §7/§8: an AI resolution never
 * re-triggers for the same pair once written, and a human correction re-pins it (see
 * {@link #pin}, wired in Task 7). Not @Transactional, same HikariCP-pool-exhaustion reasoning as
 * every other Fyn caller in this codebase.
 */
@Service
public class UserMerchantCategoryResolutionService {

    private static final Logger log = LoggerFactory.getLogger(UserMerchantCategoryResolutionService.class);

    private static final String PROMPT_VERSION = "user-category-resolution-v1";
    private static final String TOOL_NAME = "RESOLVE_CATEGORY";
    private static final int MAX_TOKENS = 60;
    private static final int MAX_CATEGORIES_SENT = 100;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are Fyn, choosing a spending category for a bank transaction. The merchant is \
            understood to be: %s

            The user's existing categories are: %s

            Call the resolve_category tool. If one of the user's existing categories fits, reply \
            with that EXACT name and no reason. If none fit, reply with a new, short category \
            name and a brief reason why it doesn't match any existing one.
            """;

    private static final LlmTool TOOL = new LlmTool(TOOL_NAME,
            "Record the resolved category for this user and merchant.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "category", Map.of("type", "string"),
                            "reason", Map.of("type", "string")),
                    "required", List.of("category")));

    private final MerchantUnderstandingService understandingService;
    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final UserMerchantCategoryResolutionRepository resolutionRepository;
    private final CategoryRepository categoryRepository;
    private final CategorizationService categorizationService;

    public UserMerchantCategoryResolutionService(MerchantUnderstandingService understandingService,
                                                  FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                                  AiAuditLogRepository aiAuditLogRepository,
                                                  UserMerchantCategoryResolutionRepository resolutionRepository,
                                                  CategoryRepository categoryRepository,
                                                  CategorizationService categorizationService) {
        this.understandingService = understandingService;
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.categoryRepository = categoryRepository;
        this.categorizationService = categorizationService;
    }

    public Optional<String> resolve(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        var cached = resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, counterpartyKey, direction);
        if (cached.isPresent()) {
            return categoryRepository.findById(cached.get().getCategoryId()).map(Category::getName);
        }

        Optional<String> understanding = understandingService.understand(userId, counterpartyKey, direction, description);
        if (understanding.isEmpty()) {
            return Optional.empty();
        }
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        String categoryList = categoryRepository.findByUserId(userId).stream()
                .map(Category::getName)
                .limit(MAX_CATEGORIES_SENT)
                .collect(Collectors.joining(", "));
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, understanding.get(),
                categoryList.isBlank() ? "(none yet)" : categoryList);

        LlmRequest request = LlmRequest.withTools(systemPrompt, List.of(LlmMessage.user(description)),
                MAX_TOKENS, List.of(TOOL));
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
            log.error("Fyn category-resolution call succeeded but has no known price for model {}", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(), cost, latencyMs, costError);

        if (!completion.requestsToolUse()) {
            return Optional.empty();
        }
        Map<String, Object> input = completion.toolUses().get(0).input();
        Object rawCategory = input.get("category");
        if (!(rawCategory instanceof String categoryName) || categoryName.isBlank()) {
            return Optional.empty();
        }
        String reason = input.get("reason") instanceof String r && !r.isBlank() ? r : null;

        Category resolved = categorizationService.resolveOrCreateCategory(userId, categoryName, reason);
        resolutionRepository.insertIfAbsent(userId, counterpartyKey, direction.name(), resolved.getId(), Instant.now());
        return Optional.of(resolved.getName());
    }

    /** Human override (spec §8), called from Task 7's wiring whenever a user manually sets or
     *  corrects a transaction's category -- unconditional pin, always the latest correction, per
     *  UserMerchantCategoryResolutionRepository.upsertPinned's own doc comment. */
    public void pin(UUID userId, String counterpartyKey, Transaction.Type direction, UUID categoryId) {
        resolutionRepository.upsertPinned(userId, counterpartyKey, direction.name(), categoryId, Instant.now());
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
            log.warn("Fyn category-resolution call issue (user {}): {}", userId, error);
        }
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=UserMerchantCategoryResolutionServiceTest`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/UserMerchantCategoryResolutionService.java \
        backend/src/test/java/com/finora/service/UserMerchantCategoryResolutionServiceTest.java
git commit -m "feat(rules): add Tier 2 per-user category resolution AI service"
```

---

### Task 5: Rewire `FynCategorizationFallbackService` to orchestrate Tier 1 + Tier 2

**Files:**
- Modify: `backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java`
- Modify: `backend/src/test/java/com/finora/service/FynCategorizationFallbackServiceTest.java`

**Interfaces:**
- Consumes: `UserMerchantCategoryResolutionService.resolve(...)` (Task 4).
- Produces: `Optional<String> suggest(UUID userId, String counterpartyKey, Transaction.Type
  direction, String description)` — **signature completely unchanged** from today. This is what
  keeps `CategorizationService`'s two call sites (spec's whole reason this task exists) untouched.

This is the one task in this plan that *deletes* more than it adds — the single-shot LLM call,
its own audit-log writing, and its own AI-suggestion-cache read/write all move into Tier 1/Tier
2 (already built and tested in Tasks 3/4); this service becomes a thin orchestrator.

- [ ] **Step 1: Write the failing test**

```java
// Replaces FynCategorizationFallbackServiceTest.java's body entirely.
package com.finora.service;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FynCategorizationFallbackServiceTest {

    private UserMerchantCategoryResolutionService resolutionService;
    private FynCategorizationFallbackService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolutionService = mock(UserMerchantCategoryResolutionService.class);
        service = new FynCategorizationFallbackService(resolutionService);
    }

    @Test
    void suggest_delegatesToResolutionServiceWithSameArguments() {
        when(resolutionService.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/..."))
                .thenReturn(Optional.of("Pet Care"));

        Optional<String> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
    }

    @Test
    void suggest_resolutionServiceReturnsEmpty_propagatesEmpty() {
        when(resolutionService.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.suggest(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=FynCategorizationFallbackServiceTest`
Expected: FAIL — old constructor/tests still reference the removed LLM-call fields.

- [ ] **Step 3: Implement**

```java
// backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java
package com.finora.service;

import com.finora.entity.Transaction;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Fyn categorization fallback -- consulted only when the shared corpus and every deterministic
 * layer have nothing for a key (spec docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md
 * §8). A thin orchestration seam kept for CategorizationService's two existing call sites, which
 * this signature must never change for (see the plan's Global Constraints) -- the actual AI work
 * (understanding a merchant, resolving it to one user's own category) lives in
 * MerchantUnderstandingService/UserMerchantCategoryResolutionService, per
 * docs/superpowers/specs/2026-09-15-ai-category-creation-design.md.
 */
@Service
public class FynCategorizationFallbackService {

    private final UserMerchantCategoryResolutionService resolutionService;

    public FynCategorizationFallbackService(UserMerchantCategoryResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    public Optional<String> suggest(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        return resolutionService.resolve(userId, counterpartyKey, direction, description);
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=FynCategorizationFallbackServiceTest`
Expected: PASS, 2/2.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn -q -o test`
Expected: PASS. `CategorizationServiceTest`'s existing AI-fallback-path tests (constructed
against a mocked `FynCategorizationFallbackService`, unaffected by this internal rewrite) should
be unaffected — if any fail, that's a real signature-mismatch gap this step surfaced, not noise;
fix it before moving on, per this plan's Global Constraints.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/FynCategorizationFallbackService.java \
        backend/src/test/java/com/finora/service/FynCategorizationFallbackServiceTest.java
git commit -m "refactor(rules): rewire the AI categorization fallback onto the two-tier resolver"
```

---

### Task 6: Category deletion — register `user_merchant_category_resolution` as a dependent

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategoryService.java`
- Test: `backend/src/test/java/com/finora/service/CategoryServiceTest.java` (existing file —
  extend it; if it doesn't exist yet, create it following this codebase's standard mocked-service
  test shape)

**Interfaces:**
- Consumes: `UserMerchantCategoryResolutionRepository.countByUserIdAndCategoryId` /
  `.repointCategory` (Task 1).
- Produces: no new public method — `CategoryService.delete()`'s existing behavior gains one more
  dependent, per spec §6.

- [ ] **Step 1: Write the failing tests**

```java
// in CategoryServiceTest.java (add resolutionRepository as a new mocked constructor dependency
// to whatever setUp() already builds CategoryService with, then:)

@Test
void delete_categoryHasResolutionRowsOnly_requiresReassignTarget() {
    when(resolutionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(1L);

    assertThatThrownBy(() -> categoryService.delete(userId, categoryId, null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("pick a category to reassign");
}

@Test
void delete_categoryHasResolutionRows_repointsToTarget() {
    when(resolutionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(1L);
    when(categoryRepository.findById(targetId)).thenReturn(Optional.of(targetCategory));

    categoryService.delete(userId, categoryId, targetId);

    verify(resolutionRepository).repointCategory(userId, categoryId, targetId);
}

@Test
void delete_categoryHasNoDependentsAtAll_noReassignNeeded_resolutionRepointStillCalledWithNullTarget() {
    when(resolutionRepository.countByUserIdAndCategoryId(userId, categoryId)).thenReturn(0L);

    categoryService.delete(userId, categoryId, null);

    // No dependents at all -- delete proceeds with no reassignTo, and there's nothing to repoint,
    // but the call should still be made unconditionally (same shape as
    // merchantLearningService.onCategoryDeleted's own unconditional call) rather than gated on
    // hasDependents, so a resolution row that somehow outlives everything else is never missed.
    verify(resolutionRepository, never()).repointCategory(any(), any(), any());
}
```

Adjust exact assertion syntax/fixture names to match whatever `CategoryServiceTest.java` already
uses for its other three dependent types (transactions/budget/rules) — mirror that file's
existing style precisely rather than introducing a new one.

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=CategoryServiceTest`
Expected: FAIL to compile — `CategoryService`'s constructor doesn't take a
`UserMerchantCategoryResolutionRepository` yet.

- [ ] **Step 3: Implement**

Add the new dependency to `CategoryService`'s constructor (append, last parameter, matching this
plan's minimal-diff convention):

```java
private final UserMerchantCategoryResolutionRepository resolutionRepository;

public CategoryService(CategoryRepository categoryRepository,
                        CategoryRuleRepository categoryRuleRepository,
                        TransactionRepository transactionRepository,
                        BudgetRepository budgetRepository,
                        MerchantLearningService merchantLearningService,
                        AuditService auditService,
                        UserMerchantCategoryResolutionRepository resolutionRepository) {
    this.categoryRepository = categoryRepository;
    this.categoryRuleRepository = categoryRuleRepository;
    this.transactionRepository = transactionRepository;
    this.budgetRepository = budgetRepository;
    this.merchantLearningService = merchantLearningService;
    this.auditService = auditService;
    this.resolutionRepository = resolutionRepository;
}
```

In `delete()`, add the resolution count into `hasDependents` and the repoint call alongside
`onCategoryDeleted`:

```java
long learningRowCount = merchantLearningService.learningRowCount(userId, categoryId);
long resolutionRowCount = resolutionRepository.countByUserIdAndCategoryId(userId, categoryId);
boolean hasDependents = transactionCount > 0 || existingBudget.isPresent()
        || !affectedRules.isEmpty() || learningRowCount > 0 || resolutionRowCount > 0;
```

and, right after the existing `merchantLearningService.onCategoryDeleted(...)` call (same
unconditional placement, same reasoning as that call's own comment):

```java
// Unconditional, same reasoning as merchantLearningService.onCategoryDeleted above -- a
// resolution can outlive the category's own transactions, so it isn't gated on hasDependents.
if (reassignTo != null && !reassignTo.equals(categoryId)) {
    resolutionRepository.repointCategory(userId, categoryId, reassignTo);
}
```

- [ ] **Step 4: Fix every other `new CategoryService(` call site**

Run: `grep -rn "new CategoryService(" backend/src/main/java backend/src/test/java`
Add `mock(UserMerchantCategoryResolutionRepository.class)` (tests) as the new last constructor
argument at every site found. Production wiring needs no change — Spring autowires the new
dependency.

- [ ] **Step 5: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=CategoryServiceTest`
Expected: PASS, including the 3 new tests and every pre-existing one unchanged.

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && mvn -q -o test`
Expected: PASS — this is the step most likely to surface a test file constructing
`CategoryService` that Step 4's grep missed; treat any such failure as a real gap, not noise.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategoryService.java \
        backend/src/test/java/com/finora/service/CategoryServiceTest.java
git commit -m "feat(rules): repoint AI category resolutions on category delete"
```

---

### Task 7: Human override — pin Tier 2 wherever a manual correction is recorded

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java` (the same 4
  call sites that already call `sharedCorpusService.recordObservation(...)`)
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` (the same call site that
  already calls `sharedCorpusService.recordObservation(...)`, gated by `decision.worthLearning()`)
- Test: `backend/src/test/java/com/finora/transactions/TransactionServiceTest.java`,
  `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java`

**Interfaces:**
- Consumes: `UserMerchantCategoryResolutionService.pin(UUID, String, Transaction.Type, UUID)`
  (Task 4).

- [ ] **Step 1: Find the exact 5 call sites**

Run: `grep -rn "sharedCorpusService.recordObservation" backend/src/main/java`
Confirm it's exactly 4 in `TransactionService.java` and 1 in `ImportService.java` (matching this
plan's assumption, itself matching the shared-merchant-corpus plan's own Task 4/5 wiring) — if
the count differs, treat every site found as needing the same pin call, not just the ones listed
here.

- [ ] **Step 2: Write the failing tests**

```java
// in TransactionServiceTest.java, alongside whatever test already asserts
// sharedCorpusService.recordObservation(...) is called on a manual category update:
@Test
void updateCategory_manualCorrection_pinsResolution() {
    // ... existing arrange matching the recordObservation test's own fixture ...

    transactionService.updateCategory(userId, txnId, "Pet Care");

    verify(userMerchantCategoryResolutionService).pin(eq(userId), eq(t.getCounterpartyKey()),
            eq(t.getTxnType()), eq(category.getId()));
}
```

```java
// in ImportServiceAskOnceTest.java, mirroring confirm_learnsFromAnEligibleBusinessCounterparty_recordsSharedCorpusObservation:
@Test
void confirm_learnsFromAnEligibleBusinessCounterparty_pinsResolution() throws Exception {
    var row = new ConfirmedRow(LocalDate.of(2026, 7, 10), "UPI/ZEPTO/ZEPTOONLINE@YBL/0000000000@PTAXIS",
            BigDecimal.valueOf(486), "EXPENSE", "Dining", true, "rule", null, false, null, null);

    importService.confirm(userId, dummyFile(), requestWith(row));

    verify(resolutionService).pin(eq(userId), eq("vpa:zeptoonline"), eq(Transaction.Type.EXPENSE), any());
}
```

Add `resolutionService`/`userMerchantCategoryResolutionService` mock fields alongside the
existing `sharedCorpusService` ones in both test files' setup, matching their established
pattern exactly (same constructor-injection style used for `sharedCorpusService` itself).

- [ ] **Step 3: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=TransactionServiceTest,ImportServiceAskOnceTest`
Expected: FAIL to compile — neither service's constructor takes the new dependency yet.

- [ ] **Step 4: Implement**

Add `UserMerchantCategoryResolutionService` as the new last constructor parameter to both
`TransactionService` and `ImportService`, matching the exact append-only convention already used
for `sharedCorpusService` in both files. At each of the 4 `TransactionService` call sites, right
after the existing `sharedCorpusService.recordObservation(...)` line:

```java
userMerchantCategoryResolutionService.pin(userId, t.getCounterpartyKey(), t.getTxnType(), category.getId());
```

At `ImportService`'s single call site, inside the same `if (decision.worthLearning())` block,
right after its `sharedCorpusService.recordObservation(...)` call:

```java
userMerchantCategoryResolutionService.pin(userId, t.getCounterpartyKey(),
        com.finora.util.EnumParsing.parse(Transaction.Type.class, row.type(), "type"), category.getId());
```

(same direction-parsing call already used for that file's `recordObservation` invocation, kept
identical rather than re-derived).

- [ ] **Step 5: Fix every other constructor call site for both classes**

Run: `grep -rln "new TransactionService(\|new ImportService(" backend/src/test/java`
Add a `mock(UserMerchantCategoryResolutionService.class)` argument at every site found, matching
how this exact plan (and the shared-corpus plan before it) already handled adding
`sharedCorpusService` to both classes' constructors.

- [ ] **Step 6: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=TransactionServiceTest,ImportServiceAskOnceTest`
Expected: PASS.

- [ ] **Step 7: Run the full backend suite**

Run: `cd backend && mvn -q -o test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/transactions/TransactionService.java \
        backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/transactions/TransactionServiceTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java
git commit -m "feat(rules): pin AI category resolutions on every manual correction"
```

---

### Task 8: Expose `aiCreationReason` through the API

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/CategoryDto.java`
- Modify: `backend/src/main/java/com/finora/controller/CategoryController.java`
- Modify: `frontend/src/api/endpoints.ts` (`CategoryOption`)
- Test: `backend/src/test/java/com/finora/controller/CategoryControllerTest.java` (existing file
  — extend it)

**Interfaces:**
- Produces: `CategoryDto(UUID id, String name, boolean isSystem, String icon, String color,
  String aiCreationReason)` — one new field, nullable, appended last.

- [ ] **Step 1: Write the failing test**

```java
// in CategoryControllerTest.java, wherever list()/create()/update() are already tested:
@Test
void list_includesAiCreationReasonWhenPresent() {
    // ... existing arrange, but seed a category with a non-null aiCreationReason ...

    var response = categoryController.list();

    assertThat(response.data().get(0).aiCreationReason()).isEqualTo("Pet supplies retailer, no existing match");
}
```

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=CategoryControllerTest`
Expected: FAIL to compile — `CategoryDto` has no such field/constructor arg yet.

- [ ] **Step 3: Implement**

```java
// CategoryDto.java
package com.finora.dto;

import java.util.UUID;

public record CategoryDto(UUID id, String name, boolean isSystem, String icon, String color,
                           String aiCreationReason) {}
```

Update all 3 `new CategoryDto(...)` call sites in `CategoryController.java` (`list`, `create`,
`update`) to pass `c.getAiCreationReason()` as the sixth argument.

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=CategoryControllerTest`
Expected: PASS.

- [ ] **Step 5: Regenerate the committed OpenAPI spec**

Run: `cd backend && ./scripts/generate-openapi-spec.sh` — this is the exact script CI's
"OpenAPI contract drift" job runs (boot-and-curl `/v3/api-docs`, not a Maven plugin — see the
script's own header for why), writing `backend/openapi/openapi.json`.
Run: `git diff --stat -- backend/openapi/openapi.json` — Expected: `aiCreationReason` appears as
a new optional `CategoryDto` property, no other diff.

- [ ] **Step 6: Regenerate each frontend package's typed client from the updated spec**

Run, in each of `frontend/`, `mobile/`, `admin-portal/`: `npm run generate:types` (all three run
the identical `npx openapi-typescript@7.13.0 ../backend/openapi/openapi.json -o
src/api/generated-types.ts`). Expected: each package's `src/api/generated-types.ts` picks up
`aiCreationReason` on the generated `CategoryDto` schema type — this file is a full schema
mirror, separate from the hand-written `CategoryOption` interfaces below.

- [ ] **Step 7: Hand-edit `CategoryOption` in web and mobile — it is not auto-generated**

Confirmed: `CategoryOption` in both `frontend/src/api/endpoints.ts` and
`mobile/src/api/endpoints.ts` is a hand-written interface (not derived from
`generated-types.ts`), so Step 6's regen does not touch it. Add the same field to both:

```typescript
// frontend/src/api/endpoints.ts AND mobile/src/api/endpoints.ts, in each file's own
// CategoryOption interface
export interface CategoryOption {
  id: string;
  name: string;
  isSystem: boolean;
  icon: string;
  color: string;
  aiCreationReason?: string | null;
}
```

- [ ] **Step 8: Run the full backend suite**

Run: `cd backend && mvn -q -o test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/dto/CategoryDto.java \
        backend/src/main/java/com/finora/controller/CategoryController.java \
        backend/src/test/java/com/finora/controller/CategoryControllerTest.java \
        backend/openapi/openapi.json \
        frontend/src/api/endpoints.ts frontend/src/api/generated-types.ts \
        mobile/src/api/endpoints.ts mobile/src/api/generated-types.ts \
        admin-portal/src/api/generated-types.ts
git commit -m "feat(rules): expose aiCreationReason on the category API"
```

---

### Task 9: Web — badge for AI-created categories

**Files:**
- Modify: `frontend/src/components/CategoryCombobox.tsx`
- Test: `frontend/src/components/CategoryCombobox.test.tsx` (existing file — extend it)

**Interfaces:**
- Consumes: `CategoryOption.aiCreationReason` (Task 8).

- [ ] **Step 1: Write the failing test**

```tsx
// in CategoryCombobox.test.tsx, extending its existing category-list fixture with one
// AI-created category:
it('shows a badge with the reason for AI-created categories', async () => {
  // ... existing render setup, with categories including one whose aiCreationReason is set ...
  await userEvent.click(screen.getByRole('combobox'));

  expect(screen.getByLabelText(/created by fynora/i)).toBeInTheDocument();
});
```

Match whatever query/render helpers this file's existing tests already use rather than
introducing new ones.

- [ ] **Step 2: Run, verify failure**

Run: `cd frontend && npx vitest run CategoryCombobox`
Expected: FAIL — no such label exists in the rendered output yet.

- [ ] **Step 3: Implement**

In the `exactMatches.map((c) => ...)` row (the block rendering `<span className="truncate">{c.name}</span>`
around line 268), add, right after that span:

```tsx
{c.aiCreationReason && (
  <span
    aria-label={`Created by Fynora: ${c.aiCreationReason}`}
    title={c.aiCreationReason}
    className="flex-shrink-0 text-primary"
  >
    <Sparkles size={12} />
  </span>
)}
```

Add `Sparkles` to the existing `lucide-react` import at the top of the file (already imports
`Check, ChevronDown, Pencil, Plus, Trash2` from the same package — append, don't duplicate the
import statement).

- [ ] **Step 4: Run, verify it passes**

Run: `cd frontend && npx vitest run CategoryCombobox`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/CategoryCombobox.tsx frontend/src/components/CategoryCombobox.test.tsx
git commit -m "feat(rules): show a Fynora-created badge on AI-invented categories"
```

---

### Task 10: Mobile — badge for AI-created categories

**Files:**
- Modify: `mobile/src/components/CategoryPickerModal.tsx`
- Test: `mobile/src/components/CategoryPickerModal.test.tsx` (existing file — extend it)

**Interfaces:**
- Consumes: `CategoryOption.aiCreationReason` (Task 8, propagated to mobile's own generated type).

Per this worktree's `mobile/AGENTS.md`: read the exact versioned Expo docs at
https://docs.expo.dev/versions/v57.0.0/ before writing any RN-specific code in this task, not
just this plan's own snippet below.

- [ ] **Step 1: Write the failing test**

```tsx
// in CategoryPickerModal.test.tsx, extending its existing category-list fixture with one
// AI-created category:
it('shows a badge for AI-created categories', () => {
  // ... existing render setup with categories including one whose aiCreationReason is set ...

  expect(screen.getByLabelText(/created by fynora/i)).toBeTruthy();
});
```

- [ ] **Step 2: Run, verify failure**

Run: `cd mobile && npx jest CategoryPickerModal`
Expected: FAIL — no such label exists yet.

- [ ] **Step 3: Implement**

In `renderItem` (around line 161), right after the `<Text>` showing `item.name` (line 175-177),
add:

```tsx
                    <Text style={[styles.rowText, { color: isSelected ? c.primary : c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {item.name}
                    </Text>
                    {item.aiCreationReason ? (
                      <Ionicons
                        name="sparkles"
                        size={12}
                        color={c.primary}
                        accessibilityLabel={`Created by Fynora: ${item.aiCreationReason}`}
                      />
                    ) : null}
```

(The first two lines above are the existing, unchanged `<Text>` block, shown for anchoring —
only the `{item.aiCreationReason ? ... : null}` block after it is new.) `Ionicons` is already
imported in this file (`@expo/vector-icons/Ionicons`, used for the close button and the
edit/delete row icons) — reuse the same import, don't add a second icon library.

- [ ] **Step 4: Run, verify it passes**

Run: `cd mobile && npx jest CategoryPickerModal`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/components/CategoryPickerModal.tsx mobile/src/components/CategoryPickerModal.test.tsx
git commit -m "feat(rules): show a Fynora-created badge on AI-invented categories (mobile)"
```

---

### Task 11: Admin portal — Fynora-created categories section on `UserDetail`

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminUserCategoriesController.java`
- Create: `admin-portal/src/pages/user-detail/CategoriesSection.tsx`
- Modify: `admin-portal/src/api/endpoints.ts` (new `adminUserCategoriesApi`)
- Modify: `admin-portal/src/pages/UserDetail.tsx` (mount the new section)
- Test: `backend/src/test/java/com/finora/controller/AdminUserCategoriesControllerTest.java`,
  `admin-portal/src/pages/user-detail/CategoriesSection.test.tsx`

**Interfaces:**
- Produces: `GET /api/v1/admin/users/{userId}/categories/ai-created` → `List<CategoryDto>`
  filtered to `aiCreationReason != null`.

- [ ] **Step 1: Write the failing backend test**

```java
// backend/src/test/java/com/finora/controller/AdminUserCategoriesControllerTest.java
package com.finora.controller;

import com.finora.dto.CategoryDto;
import com.finora.entity.Category;
import com.finora.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminUserCategoriesControllerTest {

    private CategoryRepository categoryRepository;
    private AdminUserCategoriesController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        controller = new AdminUserCategoriesController(categoryRepository);
    }

    @Test
    void aiCreated_filtersToCategoriesWithAReason() {
        Category aiCreated = new Category();
        aiCreated.setName("Pet Care");
        aiCreated.setAiCreationReason("Pet supplies retailer, no existing match");
        Category manual = new Category();
        manual.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(aiCreated, manual));

        List<CategoryDto> result = controller.aiCreated(userId).data();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Pet Care");
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run: `cd backend && mvn -q -o test -Dtest=AdminUserCategoriesControllerTest`
Expected: FAIL to compile — the controller doesn't exist yet.

- [ ] **Step 3: Implement the backend endpoint**

```java
// backend/src/main/java/com/finora/controller/AdminUserCategoriesController.java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.CategoryDto;
import com.finora.repository.CategoryRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted visibility into what Fynora's AI has created on a user's behalf -- same
 * thin-proxy, same MERCHANT_MANAGE-reuse pattern as AdminUserLearningController. Read-only.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/categories")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminUserCategoriesController {

    private final CategoryRepository categoryRepository;

    public AdminUserCategoriesController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping("/ai-created")
    public ApiResponse<List<CategoryDto>> aiCreated(@PathVariable UUID userId) {
        var categories = categoryRepository.findByUserId(userId).stream()
                .filter(c -> c.getAiCreationReason() != null)
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor(), c.getAiCreationReason()))
                .toList();
        return ApiResponse.ok(categories);
    }
}
```

- [ ] **Step 4: Run, verify it passes**

Run: `cd backend && mvn -q -o test -Dtest=AdminUserCategoriesControllerTest`
Expected: PASS.

- [ ] **Step 5: Add the frontend API client**

Confirmed: `admin-portal` has no existing `CategoryDto`/`CategoryOption` type anywhere (no
category feature exists there yet) — define it fresh, no duplicate to worry about.

```typescript
// admin-portal/src/api/endpoints.ts, alongside adminUserLearningApi
export interface CategoryDto {
  id: string;
  name: string;
  isSystem: boolean;
  icon: string;
  color: string;
  aiCreationReason: string | null;
}

export const adminUserCategoriesApi = {
  aiCreated: (userId: string) => api.get<CategoryDto[]>(`/admin/users/${userId}/categories/ai-created`).then((r) => r.data),
};
```

- [ ] **Step 6: Write the failing frontend test**

```tsx
// admin-portal/src/pages/user-detail/CategoriesSection.test.tsx
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { vi } from 'vitest';
import { CategoriesSection } from './CategoriesSection';
import { adminUserCategoriesApi } from '../../api/endpoints';

vi.mock('../../api/endpoints');

it('lists AI-created categories with their reasons', async () => {
  vi.mocked(adminUserCategoriesApi.aiCreated).mockResolvedValue([
    { id: '1', name: 'Pet Care', isSystem: false, icon: 'tag', color: 'gray',
      aiCreationReason: 'Pet supplies retailer, no existing match' },
  ]);
  const client = new QueryClient();

  render(
    <QueryClientProvider client={client}>
      <CategoriesSection userId="u1" />
    </QueryClientProvider>
  );

  await waitFor(() => expect(screen.getByText('Pet Care')).toBeInTheDocument());
  expect(screen.getByText(/pet supplies retailer/i)).toBeInTheDocument();
});
```

- [ ] **Step 7: Run, verify failure**

Run: `cd admin-portal && npx vitest run CategoriesSection`
Expected: FAIL — the component doesn't exist yet.

- [ ] **Step 8: Implement the section, mirroring `LearningSection.tsx`'s exact shape**

```tsx
// admin-portal/src/pages/user-detail/CategoriesSection.tsx
import { useQuery } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { adminUserCategoriesApi } from '../../api/endpoints';

export function CategoriesSection({ userId }: { userId: string }) {
  const { data: categories, isLoading } = useQuery({
    queryKey: ['admin-user-ai-categories', userId],
    queryFn: () => adminUserCategoriesApi.aiCreated(userId),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <Sparkles size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Fynora-Created Categories</h3>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (categories ?? []).length === 0 && (
        <p className="text-sm text-muted">This user has no Fynora-created categories.</p>
      )}
      <div>
        {categories?.map((cat) => (
          <div key={cat.id} className="py-2 border-b border-border last:border-b-0">
            <p className="text-ink font-medium">{cat.name}</p>
            <p className="text-xs text-muted">{cat.aiCreationReason}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
```

Mount it in `UserDetail.tsx`, importing alongside the existing `LearningSection` import
(line 25) and rendering alongside its existing gate at line 189:

```tsx
import { CategoriesSection } from './user-detail/CategoriesSection';
// ...
{hasPermission('MERCHANT_MANAGE') && <LearningSection userId={id} />}
{hasPermission('MERCHANT_MANAGE') && <CategoriesSection userId={id} />}
```

Same `MERCHANT_MANAGE` permission gate as `LearningSection` — matches `AdminUserCategoriesController`'s
own `@PreAuthorize` in Step 3.

- [ ] **Step 9: Run, verify it passes**

Run: `cd admin-portal && npx vitest run CategoriesSection`
Expected: PASS.

- [ ] **Step 10: Run each touched package's full test suite**

Run: `cd backend && mvn -q -o test`, `cd admin-portal && npx vitest run`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/finora/controller/AdminUserCategoriesController.java \
        backend/src/test/java/com/finora/controller/AdminUserCategoriesControllerTest.java \
        admin-portal/src/api/endpoints.ts \
        admin-portal/src/pages/user-detail/CategoriesSection.tsx \
        admin-portal/src/pages/user-detail/CategoriesSection.test.tsx \
        admin-portal/src/pages/UserDetail.tsx
git commit -m "feat(rules): show Fynora-created categories in the admin portal"
```

---

## Execution Handoff

After all 11 tasks are complete, run the full backend suite (`cd backend && mvn -q -o test`) and
each touched frontend package's suite one more time as a final regression pass, then follow
`superpowers:finishing-a-development-branch`.
