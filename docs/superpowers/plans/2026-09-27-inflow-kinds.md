# Inflow Kinds (Plan 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user say what money they received actually was — built-in or custom "inflow kinds", remembered per sender. Every income, spending and unresolved total follows that choice.

**Architecture:**
- Three schema changes:
  - two tables: `inflow_kinds` and `sender_inflow_rules`;
  - one column: `transactions.inflow_kind_id`.
- `FlowClassifier` stays a pure function. It gains one input: the chosen kind, which is the row's own choice first, then the sender's.
- `FlowTotals.Context` carries the user's choices, loaded once per totals call by `InflowChoiceService`. That service is the only place a Context is built in production code.
- A new `com.finora.inflow` package holds kind CRUD, setting and clearing a choice, the counts-as reading and the review list.
- Web and mobile get the same four surfaces:
  - a review screen;
  - a "Counts as" section in the transaction's "why" panel;
  - two settings sections (kinds and remembered senders);
  - income by kind on Reports.

**Tech Stack:** Spring Boot / JPA / Flyway / Postgres (backend); React + Vite + vitest (web); React Native + Expo + jest (mobile).

**Spec:** `docs/superpowers/specs/2026-09-27-inflow-kinds-design.md`

## Global Constraints

**Git and repository:**
- Never add a `Co-Authored-By` or any AI trailer to a commit message.
- Commit header ≤ 100 characters. Scope must be in the commitlint enum; use `transactions`, `web`, `mobile` or `backend`.
- Work only in `/Users/sid/Downloads/finora-inflow-kinds`. Use absolute paths for every git and build command.
- Before adding the migration, run `git fetch origin` and list `backend/src/main/resources/db/migration` on `origin/main`. Use V232 only if it is still free; otherwise use the next free number. Never renumber an existing migration.

**Test data:**
- No real narration, name, account number or IFSC enters the repository.
- 12-digit numbers in tests are `111111111111`-style.
- A masked IFSC is `HDFC0XXXXXX`.

**Code rules:**
- No main source file outside the `IncomeSingleEntryPointTest` allow-list may contain the text `Type.INCOME`. Express "a credit" as `getTxnType() != Transaction.Type.EXPENSE`.
- Any `@Modifying` UPDATE on `transactions` must also set `version = version + 1` (`ChangeStampBulkWriteGuardTest`).
- Never write inside a `@Transactional(readOnly = true)` method; Hibernate drops the write silently.
- Mobile: every modal is `AppModal`, never RN `Modal`. Every alert is `AppAlert`, never RN `Alert`.
- Mobile jest runs with `npm test` from `mobile/`, because it needs `--experimental-vm-modules`.
- Web and mobile lint and tsc are verified under Node 22 (`npx -y node@22 ...`), matching CI.

**Commands:**
- The full backend suite is `./mvnw verify` (unit + IT). `./mvnw test` skips every `*IT`.
- OpenAPI is regenerated after any controller or DTO change: `scripts/generate-openapi-spec.sh`, then `openapi-typescript@7.13.0` into all three `generated-types.ts` files.
  - Generation needs Postgres. Use a throwaway `postgres:16-alpine` on port 5499 with `DB_PORT=5499`.
  - Under local Node 26, install `typescript` `--no-save` in `mobile/` and run the generator from there.

**Behaviour:**
- Built-in kinds, in this order, with their fixed income flag:
  - Income: counts;
  - Family support: counts;
  - My own money: does not count;
  - Paid back to me: does not count;
  - Refund: does not count, and lowers spending.
- Ownership: an unknown id → 404. Another user's id → 403 (`OwnershipGuard`).

## Review Focus

1. **A sender rule and a later refund from the same sender.** A refund that reconciliation linked (status REFUND) must stay a linked refund even when the sender rule says "My own money". Pinned in Task 2: `linkedRefundBeatsAChosenKind`.
2. **Deleting a custom kind referenced only by a soft-deleted row.** This must succeed, not throw an FK violation or report "in use by 1". Pinned in Task 5: `deleteClearsSoftDeletedRowsFirst` (IT).
3. **The review list and the banner disagree on count.** Both must be built from the same row set. Pinned in Task 6: `unresolvedInflowsMatchForRangeCount`.
4. **Choosing SENDER when the row already has its own choice.** The row choice must be cleared, or the new sender choice silently doesn't apply to the very row the user tapped. Pinned in Task 5: `senderScopeClearsTheRowChoice`.
5. **Account purge with kinds and rules present.** The purge must not roll back on an FK. Pinned in Task 1: `purgeDeletesInflowKindsAndRules` (IT).

---

## File Structure

**Backend — new files:**
- `backend/src/main/resources/db/migration/V232__inflow_kinds.sql` — tables, indexes and column.
- `backend/src/main/java/com/finora/entity/InflowKind.java` — entity, plus the `BuiltIn` enum with the default name and income flag of each built-in.
- `backend/src/main/java/com/finora/entity/SenderInflowRule.java` — entity.
- `backend/src/main/java/com/finora/repository/InflowKindRepository.java`
- `backend/src/main/java/com/finora/repository/SenderInflowRuleRepository.java`
- `backend/src/main/java/com/finora/service/InflowChoices.java` — an immutable snapshot of one user's kinds and sender rules, with `chosenFor(Transaction)`.
- `backend/src/main/java/com/finora/service/InflowChoiceService.java` — loads the snapshot and builds a `FlowTotals.Context`.
- `backend/src/main/java/com/finora/inflow/InflowKindService.java` — kind CRUD, set/clear choice, counts-as, sender rules.
- `backend/src/main/java/com/finora/inflow/InflowReviewService.java` — review list grouped by sender.
- `backend/src/main/java/com/finora/inflow/InflowController.java` — every new endpoint.
- `backend/src/main/java/com/finora/inflow/InflowDtos.java` — request and response records.
- `backend/src/main/java/com/finora/inflow/SenderLabel.java` — the display name for a sender.

**Backend — modified files:**
- `entity/Transaction.java` — adds `inflowKindId`.
- `repository/TransactionRepository.java` — adds four queries.
- `service/FlowClassifier.java` — new reasons, the chosen-kind input, VERSION 5.
- `service/FlowTotals.java` — Context carries the choices; adds `incomeLabel`, `decision` and `chosen`.
- The totals services now get their Context from `InflowChoiceService`:
  - `ReportService` (also: income by kind, and the unresolved rows);
  - `DashboardService`;
  - `budgets/BudgetService`;
  - `InsightsService`;
  - `AnalyticsService`.
- `dto/ReportDto.java` — adds `incomeByKind`.
- `service/ChangeStampService.java` — the transactions section now includes kinds and sender rules.
- `service/AccountPurgeSweepService.java` — purges the new tables.
- `exception/ErrorCode.java` — adds TXN_005 and TXN_006.

**Web:**
- `frontend/src/api/endpoints.ts` — adds `inflowApi` and its types; `Report` gains `incomeByKind`.
- New components:
  - `frontend/src/components/inflow/InflowKindPicker.tsx` (+ test);
  - `frontend/src/components/inflow/CountsAsSection.tsx` (+ test);
  - `frontend/src/pages/settings/InflowKindsSection.tsx` (+ test).
- `frontend/src/pages/MoneyReview.tsx` (+ test) — new page; route `/app/money-review` in `App.tsx`.
- Modified pages:
  - `Ledger.tsx` — the ExplanationModal renders CountsAsSection;
  - `Dashboard.tsx` — the banner links to the review page;
  - `Reports.tsx` — income-by-kind list;
  - `settings/CategorizationPane.tsx` — renders InflowKindsSection.

**Mobile:**
- `mobile/src/api/endpoints.ts` and `mobile/src/types/index.ts` — the same API and types.
- New components:
  - `mobile/src/components/InflowKindPicker.tsx` (+ test);
  - `mobile/src/components/CountsAsSection.tsx` (+ test);
  - `mobile/src/components/InflowKindsSettings.tsx` (+ test).
- `mobile/src/screens/MoneyReviewScreen.tsx` (+ test) — new screen, registered as `MoneyReview` in `navigation/AppTabs.tsx` and `navigation/types.ts`.
- Modified:
  - `TransactionExplanationModal.tsx`, `DashboardScreen.tsx`, `ReportsScreen.tsx`, `SettingsCategorizationScreen.tsx`;
  - `lib/invalidateFinancialData.ts` — adds query keys.

---

### Task 1: Schema, entities, repositories, purge

**Files:**
- Create: `backend/src/main/resources/db/migration/V232__inflow_kinds.sql`
- Create: `backend/src/main/java/com/finora/entity/InflowKind.java`
- Create: `backend/src/main/java/com/finora/entity/SenderInflowRule.java`
- Create: `backend/src/main/java/com/finora/repository/InflowKindRepository.java`
- Create: `backend/src/main/java/com/finora/repository/SenderInflowRuleRepository.java`
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java` (field beside `transferRejectedAt`)
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java`
- Modify: `backend/src/main/java/com/finora/service/AccountPurgeSweepService.java` (constructor; after `transactionRepository.hardDeleteByUserId(userId);`)
- Test: `backend/src/test/java/com/finora/repository/InflowKindRepositoryIT.java`
- Test: `backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java` (add one test), `AccountPurgeSweepServiceTest.java` (constructor args)

**Interfaces:**
- Produces:
  - `InflowKind`: `getId()`, `getUserId()`, `getName()`, `setName(String)`, `isCountsAsIncome()`, `setCountsAsIncome(boolean)`, `getBuiltIn()`, `setUserId(UUID)`, `setBuiltIn(BuiltIn)`.
  - `InflowKind.BuiltIn { INCOME, FAMILY_SUPPORT, OWN_MONEY, PAID_BACK, REFUND }`, with `defaultName()` and `countsAsIncome()`.
  - `SenderInflowRule`: `getId()`, `getUserId()`, `getCounterpartyKey()`, `getInflowKindId()`, `setInflowKindId(UUID)`, setters, `touch()`.
  - `Transaction.getInflowKindId()` and `setInflowKindId(UUID)`.
  - `InflowKindRepository`:
    - `findByUserId(UUID)`;
    - `existsByUserIdAndNameIgnoreCase(UUID, String)`;
    - `existsByUserIdAndNameIgnoreCaseAndIdNot(UUID, String, UUID)`;
    - `countByUserIdAndBuiltInIsNotNull(UUID)`;
    - `insertBuiltInIfMissing(UUID userId, String name, boolean countsAsIncome, String builtIn)`;
    - `hardDeleteByUserId(UUID)`.
  - `SenderInflowRuleRepository`:
    - `findByUserId(UUID)`;
    - `findByUserIdAndCounterpartyKey(UUID, String)`;
    - `countByInflowKindId(UUID)`;
    - `hardDeleteByUserId(UUID)`.
  - `TransactionRepository`:
    - `countLiveByInflowKindId(UUID)`;
    - `clearInflowKindOnDeletedRows(UUID)`;
    - `countLiveCreditsBySender(UUID, String)`;
    - `findFirstByUserIdAndCounterpartyKeyOrderByTxnDateDesc(UUID, String)`.

- [ ] **Step 1: Confirm the migration number is free**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds && git fetch origin && git ls-tree --name-only origin/main backend/src/main/resources/db/migration/ | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | tail -1`
Expected: `231`. If it is higher, use that number + 1 everywhere this plan says V232.

Also run: `grep -n "txn_type\|counterparty_key" backend/src/main/resources/db/migration/*.sql | head` and confirm two things:
- `txn_type` stores the strings `INCOME`/`EXPENSE`;
- the type of `counterparty_key`, because `sender_inflow_rules.counterparty_key` must match it. Use `TEXT` if it is TEXT, or the same `VARCHAR(n)` otherwise.

- [ ] **Step 2: Write the failing IT**

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.InflowKind;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The constraints the inflow-kind service relies on, checked against real Postgres. */
class InflowKindRepositoryIT extends AbstractIntegrationTest {

    @Autowired private InflowKindRepository kinds;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID newUser() {
        User u = new User();
        u.setEmail("inflow-kind-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Inflow Kind IT");
        u.setAccountScope(User.SCOPE_USER);
        return users.save(u).getId();
    }

    @Test
    void builtInInsertIsIdempotent() {
        UUID userId = newUser();
        assertThat(kinds.insertBuiltInIfMissing(userId, "Income", true, "INCOME")).isEqualTo(1);
        assertThat(kinds.insertBuiltInIfMissing(userId, "Income", true, "INCOME")).isEqualTo(0);
        assertThat(kinds.countByUserIdAndBuiltInIsNotNull(userId)).isEqualTo(1);
    }

    @Test
    void namesAreUniquePerUserIgnoringCase() {
        UUID userId = newUser();
        InflowKind a = new InflowKind();
        a.setUserId(userId); a.setName("Rent from tenant"); a.setCountsAsIncome(true);
        kinds.saveAndFlush(a);
        InflowKind b = new InflowKind();
        b.setUserId(userId); b.setName("RENT FROM TENANT"); b.setCountsAsIncome(false);
        assertThatThrownBy(() -> kinds.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transactionColumnReferencesInflowKinds() {
        Integer fk = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage k ON k.constraint_name = tc.constraint_name
                WHERE tc.table_name = 'transactions' AND tc.constraint_type = 'FOREIGN KEY'
                  AND k.column_name = 'inflow_kind_id'""", Integer.class);
        assertThat(fk).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw -q -Dtest=InflowKindRepositoryIT test 2>&1 | tail -20`
Expected: compilation failure: `cannot find symbol ... InflowKindRepository`.

- [ ] **Step 4: Write the migration**

`V232__inflow_kinds.sql` (use the `counterparty_key` type found in Step 1):

```sql
-- Inflow kinds (Plan 2 of the financial flow program; spec
-- docs/superpowers/specs/2026-09-27-inflow-kinds-design.md).
--
-- A user says what a credit Fynora could not explain actually was: one of five built-in kinds or
-- one of their own. A kind either counts as income or does not. A choice is remembered for the
-- sender (sender_inflow_rules, keyed on transactions.counterparty_key) or for one row
-- (transactions.inflow_kind_id). FlowClassifier reads both at read time; nothing here is a cache.
CREATE TABLE inflow_kinds (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    name             VARCHAR(60) NOT NULL,
    counts_as_income BOOLEAN NOT NULL,
    built_in         VARCHAR(20),               -- INCOME | FAMILY_SUPPORT | OWN_MONEY | PAID_BACK | REFUND
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One of each built-in per user, so two parallel first requests cannot create a second set.
CREATE UNIQUE INDEX uq_inflow_kinds_user_built_in ON inflow_kinds(user_id, built_in) WHERE built_in IS NOT NULL;
CREATE UNIQUE INDEX uq_inflow_kinds_user_name ON inflow_kinds(user_id, lower(name));

CREATE TABLE sender_inflow_rules (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    counterparty_key TEXT NOT NULL,
    inflow_kind_id   UUID NOT NULL REFERENCES inflow_kinds(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sender_inflow_rules_user_key UNIQUE (user_id, counterparty_key)
);

CREATE INDEX idx_sender_inflow_rules_kind ON sender_inflow_rules(inflow_kind_id);

ALTER TABLE transactions ADD COLUMN inflow_kind_id UUID REFERENCES inflow_kinds(id);
CREATE INDEX idx_transactions_inflow_kind ON transactions(inflow_kind_id) WHERE inflow_kind_id IS NOT NULL;
```

- [ ] **Step 5: Write the entities**

`InflowKind.java`:

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * What a credit was, in the user's own words -- see V232 and the Plan 2 spec. A kind either counts
 * as income or does not; the five built-ins carry a fixed effect FlowClassifier reads by name.
 */
@Entity
@Table(name = "inflow_kinds")
public class InflowKind {

    public enum BuiltIn {
        INCOME("Income", true),
        FAMILY_SUPPORT("Family support", true),
        OWN_MONEY("My own money", false),
        PAID_BACK("Paid back to me", false),
        REFUND("Refund", false);

        private final String defaultName;
        private final boolean countsAsIncome;

        BuiltIn(String defaultName, boolean countsAsIncome) {
            this.defaultName = defaultName;
            this.countsAsIncome = countsAsIncome;
        }

        public String defaultName() { return defaultName; }
        public boolean countsAsIncome() { return countsAsIncome; }
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "counts_as_income", nullable = false)
    private boolean countsAsIncome;

    @Enumerated(EnumType.STRING)
    @Column(name = "built_in")
    private BuiltIn builtIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCountsAsIncome() { return countsAsIncome; }
    public void setCountsAsIncome(boolean countsAsIncome) { this.countsAsIncome = countsAsIncome; }
    public BuiltIn getBuiltIn() { return builtIn; }
    public void setBuiltIn(BuiltIn builtIn) { this.builtIn = builtIn; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`SenderInflowRule.java`:

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** "Every payment from this sender is <kind>". Keyed on Transaction.counterpartyKey, which is never
 *  shown to the user -- a name: key is a guess (CounterpartyIdentity). */
@Entity
@Table(name = "sender_inflow_rules")
public class SenderInflowRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "counterparty_key", nullable = false)
    private String counterpartyKey;

    @Column(name = "inflow_kind_id", nullable = false)
    private UUID inflowKindId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCounterpartyKey() { return counterpartyKey; }
    public void setCounterpartyKey(String counterpartyKey) { this.counterpartyKey = counterpartyKey; }
    public UUID getInflowKindId() { return inflowKindId; }
    public void setInflowKindId(UUID inflowKindId) { this.inflowKindId = inflowKindId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
```

In `Transaction.java`, next to `transferRejectedAt`:

```java
    /** The user's own answer to "what was this credit", for this row only -- see InflowChoices.
     *  Null when the row follows its sender's rule or the automatic reading. */
    @Column(name = "inflow_kind_id")
    private UUID inflowKindId;

    public UUID getInflowKindId() { return inflowKindId; }
    public void setInflowKindId(UUID inflowKindId) { this.inflowKindId = inflowKindId; }
```

- [ ] **Step 6: Write the repositories**

`InflowKindRepository.java`:

```java
package com.finora.repository;

import com.finora.entity.InflowKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InflowKindRepository extends JpaRepository<InflowKind, UUID> {

    List<InflowKind> findByUserId(UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);

    long countByUserIdAndBuiltInIsNotNull(UUID userId);

    /** ON CONFLICT DO NOTHING covers both unique indexes, so parallel first requests are safe. */
    @Modifying
    @Query(value = """
            INSERT INTO inflow_kinds (id, user_id, name, counts_as_income, built_in)
            VALUES (gen_random_uuid(), :userId, :name, :countsAsIncome, :builtIn)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertBuiltInIfMissing(@Param("userId") UUID userId, @Param("name") String name,
                               @Param("countsAsIncome") boolean countsAsIncome, @Param("builtIn") String builtIn);

    @Modifying
    @Query("DELETE FROM InflowKind k WHERE k.userId = :userId")
    int hardDeleteByUserId(@Param("userId") UUID userId);
}
```

`SenderInflowRuleRepository.java`:

```java
package com.finora.repository;

import com.finora.entity.SenderInflowRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SenderInflowRuleRepository extends JpaRepository<SenderInflowRule, UUID> {

    List<SenderInflowRule> findByUserId(UUID userId);

    Optional<SenderInflowRule> findByUserIdAndCounterpartyKey(UUID userId, String counterpartyKey);

    long countByInflowKindId(UUID inflowKindId);

    @Modifying
    @Query("DELETE FROM SenderInflowRule r WHERE r.userId = :userId")
    int hardDeleteByUserId(@Param("userId") UUID userId);
}
```

Add to `TransactionRepository.java` (these are native queries, because two of them must see soft-deleted rows, which `@SQLRestriction` hides from JPQL):

```java
    /** Live rows whose own inflow choice is this kind -- InflowKindService.delete's "in use" count. */
    @Query(value = "SELECT count(*) FROM transactions WHERE inflow_kind_id = :kindId AND deleted_at IS NULL",
            nativeQuery = true)
    long countLiveByInflowKindId(@Param("kindId") UUID kindId);

    /** A soft-deleted row still holds its FK; clearing it lets a kind the user can no longer see in
     *  use be deleted. Bumps version like every bulk transactions write (ChangeStampBulkWriteGuardTest). */
    @Modifying
    @Query(value = "UPDATE transactions SET inflow_kind_id = NULL, version = version + 1 "
            + "WHERE inflow_kind_id = :kindId AND deleted_at IS NOT NULL", nativeQuery = true)
    int clearInflowKindOnDeletedRows(@Param("kindId") UUID kindId);

    /** How many live credits a sender rule reaches -- shown before and after a SENDER choice. */
    @Query(value = "SELECT count(*) FROM transactions WHERE user_id = :userId AND counterparty_key = :key "
            + "AND txn_type <> 'EXPENSE' AND deleted_at IS NULL", nativeQuery = true)
    long countLiveCreditsBySender(@Param("userId") UUID userId, @Param("key") String key);

    Optional<Transaction> findFirstByUserIdAndCounterpartyKeyOrderByTxnDateDesc(UUID userId, String counterpartyKey);
```

(Add imports for `Modifying`, `Query`, `Param` or `Optional` only if the file lacks them.)

- [ ] **Step 7: Run the IT and watch it pass**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw -q -Dtest=InflowKindRepositoryIT test 2>&1 | tail -20`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 8: Write the failing purge test**

In `AccountPurgeSweepServiceIT.java`, add a test modelled on the file's existing purge test: same user and account setup, same way of invoking the purge. Before purging, insert:
- a custom kind;
- a sender rule on it;
- a transaction whose `inflow_kind_id` is that kind.

Then assert:

```java
    @Test
    void purgeDeletesInflowKindsAndRules() {
        // Arrange: reuse this class's existing helper(s) for a purge-eligible user with one account
        // and one transaction; then, through the repositories:
        //   InflowKind k = new InflowKind(); k.setUserId(userId); k.setName("Rent from tenant"); k.setCountsAsIncome(true);
        //   k = inflowKindRepository.save(k);
        //   SenderInflowRule r = new SenderInflowRule(); r.setUserId(userId); r.setCounterpartyKey("vpa:tenant1"); r.setInflowKindId(k.getId());
        //   senderInflowRuleRepository.save(r);
        //   txn.setInflowKindId(k.getId()); transactionRepository.save(txn);
        // Act: run the purge exactly as the existing test in this class does.
        // Assert:
        assertThat(inflowKindRepository.findByUserId(userId)).isEmpty();
        assertThat(senderInflowRuleRepository.findByUserId(userId)).isEmpty();
    }
```

The arrange and act lines above describe what to write. Write them as real code, copying the setup and purge call of the existing test in the same file verbatim.

Run: `./mvnw -q -Dtest=AccountPurgeSweepServiceIT test 2>&1 | tail -20`
Expected: FAIL — the purge throws on `sender_inflow_rules_user_id_fkey` / `inflow_kinds` FK, or leaves the rows behind.

- [ ] **Step 9: Purge the new tables**

In `AccountPurgeSweepService`:
- add constructor parameters `InflowKindRepository inflowKindRepository` and `SenderInflowRuleRepository senderInflowRuleRepository`, with fields;
- update `AccountPurgeSweepServiceTest`'s `new AccountPurgeSweepService(...)` to pass `mock(InflowKindRepository.class), mock(SenderInflowRuleRepository.class)`.

Directly after `transactionRepository.hardDeleteByUserId(userId);`:

```java
            // Plan 2 (V232). The purge never deletes the users row, so no ON DELETE CASCADE could
            // fire; same explicit-delete pattern as every table above. Rules first (they reference a
            // kind), kinds after the transactions that referenced them are gone.
            senderInflowRuleRepository.hardDeleteByUserId(userId);
            inflowKindRepository.hardDeleteByUserId(userId);
```

- [ ] **Step 10: Run the purge tests and watch them pass**

Run: `./mvnw -q -Dtest='AccountPurgeSweepServiceIT,AccountPurgeSweepServiceTest,InflowKindRepositoryIT' test 2>&1 | tail -20`
Expected: all pass.

- [ ] **Step 11: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add backend/src/main/resources/db/migration/V232__inflow_kinds.sql backend/src/main/java/com/finora/entity/InflowKind.java backend/src/main/java/com/finora/entity/SenderInflowRule.java backend/src/main/java/com/finora/repository/InflowKindRepository.java backend/src/main/java/com/finora/repository/SenderInflowRuleRepository.java backend/src/main/java/com/finora/entity/Transaction.java backend/src/main/java/com/finora/repository/TransactionRepository.java backend/src/main/java/com/finora/service/AccountPurgeSweepService.java backend/src/test/java/com/finora/repository/InflowKindRepositoryIT.java backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java backend/src/test/java/com/finora/service/AccountPurgeSweepServiceTest.java
git commit -m "feat(transactions): inflow kinds schema, entities and purge"
```

---

### Task 2: FlowClassifier reads a chosen kind

**Files:**
- Modify: `backend/src/main/java/com/finora/service/FlowClassifier.java`
- Test: `backend/src/test/java/com/finora/service/FlowClassifierTest.java`

**Interfaces:**
- Consumes: `InflowKind`, `InflowKind.BuiltIn` (Task 1).
- Produces:
  - `FlowClassifier.classify(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory, InflowKind chosen)`, where `chosen` may be null;
  - new `FlowReason` values `USER_KIND`, `FAMILY_SUPPORT`, `USER_OWN_MONEY`, `PAID_BACK`, `USER_KIND_EXCLUDED`;
  - `VERSION = 5`.

- [ ] **Step 1: Write the failing tests**

Add to `FlowClassifierTest` (it already has `credit(...)` and `debit(...)`):

```java
    // ---- the user's own kind (Plan 2) ----

    private static InflowKind builtIn(InflowKind.BuiltIn b) {
        InflowKind k = new InflowKind();
        k.setName(b.defaultName());
        k.setCountsAsIncome(b.countsAsIncome());
        k.setBuiltIn(b);
        return k;
    }

    private static InflowKind custom(String name, boolean countsAsIncome) {
        InflowKind k = new InflowKind();
        k.setName(name);
        k.setCountsAsIncome(countsAsIncome);
        return k;
    }

    private static FlowDecision chosen(Transaction t, InflowKind k) {
        return FlowClassifier.classify(t, Account.Type.SAVINGS, false, k);
    }

    @Test void chosenIncome_isIncome() {
        assertThat(chosen(credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI"), builtIn(InflowKind.BuiltIn.INCOME)))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.USER_KIND));
    }

    @Test void chosenFamilySupport_isIncomeWithItsOwnReason() {
        assertThat(chosen(credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI"), builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT)))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.FAMILY_SUPPORT));
    }

    @Test void chosenOwnMoney_isTransfer() {
        assertThat(chosen(credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI"), builtIn(InflowKind.BuiltIn.OWN_MONEY)))
                .isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.USER_OWN_MONEY));
    }

    @Test void chosenPaidBack_isNeitherIncomeNorSpend() {
        assertThat(chosen(credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI"), builtIn(InflowKind.BuiltIn.PAID_BACK)))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.PAID_BACK));
    }

    @Test void chosenRefund_isAnUnlinkedRefund() {
        assertThat(FlowClassifier.classify(credit("MERCHANTCO 1001"), Account.Type.CREDIT_CARD, false,
                builtIn(InflowKind.BuiltIn.REFUND)))
                .isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.UNLINKED_REFUND));
    }

    @Test void customKindThatCounts_isIncome() {
        assertThat(chosen(credit("NEFT CR-HDFC0XXXXXX-TENANT ONE"), custom("Rent from tenant", true)))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.USER_KIND));
    }

    @Test void customKindThatDoesNotCount_isExcluded() {
        assertThat(chosen(credit("NEFT CR-HDFC0XXXXXX-FLATMATE"), custom("Split with flatmate", false)))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.USER_KIND_EXCLUDED));
    }

    @Test void pairedTransferBeatsAChosenKind() {
        Transaction t = credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI");
        t.setTransfer(true);
        assertThat(chosen(t, builtIn(InflowKind.BuiltIn.INCOME)))
                .isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER));
    }

    @Test void linkedRefundBeatsAChosenKind() {
        Transaction t = credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        assertThat(chosen(t, builtIn(InflowKind.BuiltIn.OWN_MONEY)))
                .isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.LINKED_REFUND));
    }

    @Test void linkedReversalBeatsAChosenKind() {
        Transaction t = credit("MERCHANTCO 1001");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        assertThat(chosen(t, builtIn(InflowKind.BuiltIn.INCOME)))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.REVERSAL));
    }

    @Test void chosenKindBeatsTheNarrationRefundWord() {
        assertThat(chosen(credit("REFUND FROM ASHA VERMA"), builtIn(InflowKind.BuiltIn.PAID_BACK)))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.PAID_BACK));
    }

    @Test void aDebitIgnoresAChosenKind() {
        assertThat(chosen(debit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI"), builtIn(InflowKind.BuiltIn.INCOME)))
                .isEqualTo(new FlowDecision(FlowClass.EXPENSE, FlowReason.PURCHASE));
    }

    @Test void noChosenKindKeepsTheAutomaticReading() {
        Transaction t = credit("UPI-ASHA VERMA-asha@okbank-111111111111-UPI");
        t.setCounterpartyType(CounterpartyType.PERSON);
        assertThat(chosen(t, null)).isEqualTo(new FlowDecision(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW));
    }

    @Test void versionIsFive() {
        assertThat(FlowClassifier.VERSION).isEqualTo((short) 5);
    }
```

Add the imports `com.finora.entity.InflowKind` and, if missing, `com.finora.util.CounterpartyType`.

- [ ] **Step 2: Run and watch them fail**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw -q -Dtest=FlowClassifierTest test 2>&1 | tail -20`
Expected: compilation failure: `USER_KIND` / the 4-argument `classify` not found.

- [ ] **Step 3: Implement**

In `FlowClassifier.java`:

1. Add to the version comment and bump the version:

```java
    // 5: a kind the user chose for the row or its sender (Plan 2) outranks every automatic rule
    //    except a pairing reconciliation made (transfer, linked refund, linked reversal).
    public static final short VERSION = 5;
```

2. Replace the `FlowReason` enum:

```java
    public enum FlowReason {
        SALARY, INTEREST, DIVIDEND, REWARD, TAX_REFUND, OTHER_INCOME, USER_ENTERED,
        USER_KIND, FAMILY_SUPPORT,
        PURCHASE,
        LINKED_REFUND, UNLINKED_REFUND, REVERSAL, CARD_ADJUSTMENT, PAID_BACK, USER_KIND_EXCLUDED,
        OWN_ACCOUNT_TRANSFER, USER_OWN_MONEY, CARD_PAYMENT_RECEIVED,
        INVESTMENT_CONTRIBUTION, INVESTMENT_WITHDRAWAL,
        LOAN_DRAWDOWN,
        PERSON_INFLOW, CARD_UNEXPLAINED_CREDIT
    }
```

3. Make the 3-argument `classify` delegate, and add the 4-argument one:

```java
    public static FlowDecision classify(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory) {
        return classify(t, accountType, inUsersSalaryCategory, null);
    }

    /**
     * @param chosen the kind the user gave this row or its sender (InflowChoices.chosenFor), or null.
     *               A debit ignores it: kinds describe money coming in.
     */
    public static FlowDecision classify(Transaction t, Account.Type accountType, boolean inUsersSalaryCategory,
                                        InflowKind chosen) {
        return t.getTxnType() == Transaction.Type.EXPENSE
                ? outflow(t) : inflow(t, accountType, inUsersSalaryCategory, chosen);
    }
```

4. Change the `inflow` signature to take `InflowKind chosen`, and insert the chosen-kind branch after the REVERSAL status check, before `String description = ...`:

```java
        // The user's own answer outranks every rule below -- but not a pairing reconciliation made
        // above, which has its own undo ("not a transfer").
        if (chosen != null) return byKind(chosen);
```

5. Add:

```java
    private static FlowDecision byKind(InflowKind k) {
        if (k.getBuiltIn() != null) {
            return switch (k.getBuiltIn()) {
                case INCOME -> of(FlowClass.INCOME, FlowReason.USER_KIND);
                case FAMILY_SUPPORT -> of(FlowClass.INCOME, FlowReason.FAMILY_SUPPORT);
                case OWN_MONEY -> of(FlowClass.TRANSFER, FlowReason.USER_OWN_MONEY);
                case PAID_BACK -> of(FlowClass.ADJUSTMENT, FlowReason.PAID_BACK);
                // Gives spend back in its own month and category, like any unlinked refund
                // (FlowTotals.offsetsSpend reads this reason).
                case REFUND -> of(FlowClass.REFUND, FlowReason.UNLINKED_REFUND);
            };
        }
        return k.isCountsAsIncome()
                ? of(FlowClass.INCOME, FlowReason.USER_KIND)
                : of(FlowClass.ADJUSTMENT, FlowReason.USER_KIND_EXCLUDED);
    }
```

Add `import com.finora.entity.InflowKind;`.

- [ ] **Step 4: Check that no switch over `FlowReason` breaks**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds && grep -rn "FlowReason\.\|case PERSON_INFLOW\|UNRESOLVED_REASON_LINE" backend/src/main/java frontend/src mobile/src | grep -v "FlowClassifier.java" | head -30`
Expected: only uses that name individual values. For any exhaustive `switch` on `FlowReason` in main code, add the new values with a sensible default and record it in the ledger.

- [ ] **Step 5: Run and watch them pass**

Run: `./mvnw -q -Dtest='FlowClassifierTest,FlowTotalsTest,RefundNettingTest' test 2>&1 | tail -20`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add backend/src/main/java/com/finora/service/FlowClassifier.java backend/src/test/java/com/finora/service/FlowClassifierTest.java
git commit -m "feat(transactions): flow classifier reads the user's chosen inflow kind"
```

---

### Task 3: Choices snapshot and FlowTotals context

**Files:**
- Create: `backend/src/main/java/com/finora/service/InflowChoices.java`
- Create: `backend/src/main/java/com/finora/service/InflowChoiceService.java`
- Modify: `backend/src/main/java/com/finora/service/FlowTotals.java`
- Modify all production callers of `FlowTotals.context(`:
  - `ReportService` (2 sites);
  - `DashboardService`;
  - `budgets/BudgetService`;
  - `InsightsService`;
  - `AnalyticsService` (2 sites).
- Modify tests that call `FlowTotals.context(` (`FlowClassifierTest`, `FlowTotalsTest`, `RefundNettingTest`, `imports/analysis/FlowClassCorpusProbe`) and every test that constructs one of the five services (`new ReportService(`, `new DashboardService(`, `new BudgetService(`, `new InsightsService(`, `new AnalyticsService(`).
- Test: `backend/src/test/java/com/finora/service/InflowChoicesTest.java`, `FlowTotalsTest.java` (add)

**Interfaces:**
- Consumes: Task 1 repositories and entities; the Task 2 4-argument `classify`.
- Produces:
  - `InflowChoices(Map<UUID, InflowKind> kindsById, Map<String, UUID> kindIdBySenderKey)`, with `NONE`, `enum Scope { ROW, SENDER }`, `record Chosen(InflowKind kind, Scope scope)` and `Chosen chosenFor(Transaction t)` (nullable).
  - `InflowChoiceService.forUser(UUID) -> InflowChoices`.
  - `InflowChoiceService.contextFor(UUID userId, Collection<Account>, Collection<Category>) -> FlowTotals.Context`.
  - `FlowTotals.Context(Map<UUID, Account.Type> accountTypes, Set<UUID> salaryCategoryIds, InflowChoices choices)`.
  - `FlowTotals.context(Collection<Account>, Collection<Category>, InflowChoices)`. The 2-argument overload is removed, so the compiler finds every caller.
  - `FlowTotals.decision(Transaction, Context) -> FlowClassifier.FlowDecision` (public).
  - `FlowTotals.chosen(Transaction, Context) -> InflowChoices.Chosen`.
  - `FlowTotals.incomeLabel(Transaction, Context) -> String`.

- [ ] **Step 1: Write the failing tests**

`InflowChoicesTest.java`:

```java
package com.finora.service;

import com.finora.entity.InflowKind;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InflowChoicesTest {

    private static InflowKind kind(String name) {
        InflowKind k = new InflowKind();
        ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
        k.setName(name);
        k.setCountsAsIncome(true);
        return k;
    }

    private static Transaction credit(String key) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.INCOME);
        t.setCounterpartyKey(key);
        return t;
    }

    @Test void rowChoiceBeatsSenderRule() {
        InflowKind row = kind("Row kind"), sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(row.getId(), row, sender.getId(), sender),
                Map.of("vpa:asha", sender.getId()));
        Transaction t = credit("vpa:asha");
        t.setInflowKindId(row.getId());
        assertThat(c.chosenFor(t)).isEqualTo(new InflowChoices.Chosen(row, InflowChoices.Scope.ROW));
    }

    @Test void senderRuleAppliesWithoutARowChoice() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("vpa:asha", sender.getId()));
        assertThat(c.chosenFor(credit("vpa:asha"))).isEqualTo(new InflowChoices.Chosen(sender, InflowChoices.Scope.SENDER));
    }

    @Test void blankOrNullKeyNeverMatchesARule() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("", sender.getId()));
        assertThat(c.chosenFor(credit(""))).isNull();
        assertThat(c.chosenFor(credit(null))).isNull();
    }

    @Test void aDebitHasNoChoice() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("vpa:asha", sender.getId()));
        Transaction t = credit("vpa:asha");
        t.setTxnType(Transaction.Type.EXPENSE);
        assertThat(c.chosenFor(t)).isNull();
    }

    @Test void noneChoosesNothing() {
        assertThat(InflowChoices.NONE.chosenFor(credit("vpa:asha"))).isNull();
    }
}
```

`InflowChoicesTest` is a test file, not main source, so `Type.INCOME` is fine there.

In `FlowTotalsTest`, first change the existing `ctx(...)` helper and every other `FlowTotals.context(a, b)` call in the file to `FlowTotals.context(a, b, InflowChoices.NONE)`. Then add:

```java
    @Test void aSenderRuleMovesAPersonsCreditIntoIncomeUnderTheKindName() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "5000.00");
        t.setCounterpartyKey("vpa:asha");
        InflowKind family = new InflowKind();
        org.springframework.test.util.ReflectionTestUtils.setField(family, "id", UUID.randomUUID());
        family.setName("Family support");
        family.setCountsAsIncome(true);
        family.setBuiltIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        FlowTotals.Context ctx = FlowTotals.context(List.of(savings), List.of(),
                new InflowChoices(Map.of(family.getId(), family), Map.of("vpa:asha", family.getId())));

        assertThat(FlowTotals.isUnresolvedInflow(t, ctx)).isFalse();
        assertThat(FlowTotals.countsAsIncome(t, ctx)).isTrue();
        assertThat(FlowTotals.incomeLabel(t, ctx)).isEqualTo("Family support");
    }

    @Test void incomeLabelNamesTheAutomaticReason() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = credit(savings, "100.00", "SB INT CREDIT");
        assertThat(FlowTotals.incomeLabel(t, FlowTotals.context(List.of(savings), List.of(), InflowChoices.NONE)))
                .isEqualTo("Interest");
    }

    @Test void aRefundKindOffsetsSpend() {
        Account card = account(Account.Type.CREDIT_CARD);
        Transaction t = credit(card, "300.00", "MERCHANTCO 1001");
        t.setInflowKindId(UUID.randomUUID());
        InflowKind refund = new InflowKind();
        org.springframework.test.util.ReflectionTestUtils.setField(refund, "id", t.getInflowKindId());
        refund.setName("Refund");
        refund.setBuiltIn(InflowKind.BuiltIn.REFUND);
        FlowTotals.Context ctx = FlowTotals.context(List.of(card), List.of(),
                new InflowChoices(Map.of(refund.getId(), refund), Map.of()));
        assertThat(FlowTotals.offsetsSpend(t, ctx)).isTrue();
        assertThat(FlowTotals.isUnresolvedInflow(t, ctx)).isFalse();
    }
```

These use the file's existing `account(...)`, `credit(Account, String, String)` and `fromAPerson(...)` helpers. Before writing the tests, read those helpers and confirm `fromAPerson` sets PERSON and a CSV source. Add imports for `InflowKind` and `Map` if missing.

- [ ] **Step 2: Run and watch them fail**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw -q -Dtest='InflowChoicesTest,FlowTotalsTest' test 2>&1 | tail -20`
Expected: compilation failure: `InflowChoices` not found.

- [ ] **Step 3: Implement `InflowChoices`**

```java
package com.finora.service;

import com.finora.entity.InflowKind;
import com.finora.entity.Transaction;

import java.util.Map;
import java.util.UUID;

/**
 * One user's answers to "what was this credit" (Plan 2): their kinds, and which kind each sender
 * was given. Loaded once per totals call (InflowChoiceService) and read per row -- the row's own
 * choice first, then its sender's.
 */
public record InflowChoices(Map<UUID, InflowKind> kindsById, Map<String, UUID> kindIdBySenderKey) {

    public static final InflowChoices NONE = new InflowChoices(Map.of(), Map.of());

    /** Whether a choice came from this row or from the rule for its sender. */
    public enum Scope { ROW, SENDER }

    public record Chosen(InflowKind kind, Scope scope) {}

    /** The kind that applies to this row, or null. A debit never has one. */
    public Chosen chosenFor(Transaction t) {
        if (t.getTxnType() == Transaction.Type.EXPENSE) return null;
        if (t.getInflowKindId() != null) {
            InflowKind k = kindsById.get(t.getInflowKindId());
            if (k != null) return new Chosen(k, Scope.ROW);
        }
        String key = t.getCounterpartyKey();
        if (key == null || key.isBlank()) return null;
        UUID kindId = kindIdBySenderKey.get(key);
        InflowKind k = kindId == null ? null : kindsById.get(kindId);
        return k == null ? null : new Chosen(k, Scope.SENDER);
    }
}
```

- [ ] **Step 4: Change `FlowTotals`**

Replace the `Context` record and the `context(...)` factory:

```java
    public record Context(Map<UUID, Account.Type> accountTypes, Set<UUID> salaryCategoryIds, InflowChoices choices) {}

    /** @param choices the user's inflow kinds and sender rules -- production code gets this Context
     *                 from InflowChoiceService.contextFor, never builds one without them */
    public static Context context(Collection<Account> accounts, Collection<Category> categories, InflowChoices choices) {
        // (body unchanged down to the return)
        return new Context(types, salary, choices);
    }
```

Replace `private static FlowClassifier.FlowDecision decide(...)` with:

```java
    /** The flow reading every total in this class uses, exposed for the counts-as endpoint. */
    public static FlowClassifier.FlowDecision decision(Transaction t, Context ctx) {
        InflowChoices.Chosen chosen = ctx.choices().chosenFor(t);
        return FlowClassifier.classify(t,
                t.getAccountId() == null ? null : ctx.accountTypes().get(t.getAccountId()),
                t.getCategoryId() != null && ctx.salaryCategoryIds().contains(t.getCategoryId()),
                chosen == null ? null : chosen.kind());
    }

    public static InflowChoices.Chosen chosen(Transaction t, Context ctx) {
        return ctx.choices().chosenFor(t);
    }

    /** The line an income row is reported under: the user's kind when one made it income,
     *  otherwise what the automatic reading found. Only meaningful for a row countsAsIncome accepts. */
    public static String incomeLabel(Transaction t, Context ctx) {
        FlowClassifier.FlowDecision d = decision(t, ctx);
        if (d.reason() == FlowClassifier.FlowReason.USER_KIND || d.reason() == FlowClassifier.FlowReason.FAMILY_SUPPORT) {
            return ctx.choices().chosenFor(t).kind().getName();
        }
        return switch (d.reason()) {
            case SALARY -> "Salary";
            case INTEREST -> "Interest";
            case DIVIDEND -> "Dividends";
            case REWARD -> "Rewards and cashback";
            case TAX_REFUND -> "Tax refunds";
            default -> "Other income";
        };
    }
```

Rename the remaining internal `decide(t, ctx)` calls to `decision(t, ctx)`.

- [ ] **Step 5: Implement `InflowChoiceService`**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads a user's inflow choices, and is the one place production code builds a FlowTotals.Context. */
@Service
public class InflowChoiceService {

    private final InflowKindRepository kinds;
    private final SenderInflowRuleRepository rules;

    public InflowChoiceService(InflowKindRepository kinds, SenderInflowRuleRepository rules) {
        this.kinds = kinds;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public InflowChoices forUser(UUID userId) {
        List<InflowKind> all = kinds.findByUserId(userId);
        if (all.isEmpty()) return InflowChoices.NONE;
        Map<UUID, InflowKind> byId = new HashMap<>();
        for (InflowKind k : all) byId.put(k.getId(), k);
        Map<String, UUID> bySender = new HashMap<>();
        for (SenderInflowRule r : rules.findByUserId(userId)) bySender.put(r.getCounterpartyKey(), r.getInflowKindId());
        return new InflowChoices(byId, bySender);
    }

    public FlowTotals.Context contextFor(UUID userId, Collection<Account> accounts, Collection<Category> categories) {
        return FlowTotals.context(accounts, categories, forUser(userId));
    }
}
```

- [ ] **Step 6: Rewire the production callers**

At each site, add `InflowChoiceService inflowChoices` as the **last** constructor parameter, with a field, and replace the call:

- `ReportService.java:62`: `FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoriesById.values());`
- `ReportService.java:113`: `FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoryRepository.findByUserId(userId));`
- `DashboardService.java:150`: `FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoriesById.values());`
- `BudgetService.java:200`: replace `com.finora.service.FlowTotals.context(` with `inflowChoices.contextFor(userId, `, keeping its two existing arguments.
- `InsightsService.java:309`: `refunds.withUnlinkedOffsets(reportable, inflowChoices.contextFor(userId, accounts, categories))`
- `AnalyticsService.java:443` and `:665`: same replacement, with `userId` as the first argument.

Then compile: `./mvnw -q -DskipTests compile 2>&1 | tail -20`
Expected: no errors. Any remaining `FlowTotals.context(` with two arguments is a compile error. Fix each one with `inflowChoices.contextFor(userId, ...)` in main code, or with `InflowChoices.NONE` as the third argument in test code.

- [ ] **Step 7: Fix test construction**

Run: `grep -rln "new ReportService(\|new DashboardService(\|new BudgetService(\|new InsightsService(\|new AnalyticsService(\|FlowTotals.context(" backend/src/test/java`

In each file:
- Add, as the new last constructor argument: `new InflowChoiceService(mock(InflowKindRepository.class), mock(SenderInflowRuleRepository.class))`. Mockito returns an empty list from `findByUserId`, so these services see `InflowChoices.NONE` and behave exactly as before.
- Add `InflowChoices.NONE` to any 2-argument `FlowTotals.context(`.

Then: `./mvnw -q -DskipTests test-compile 2>&1 | tail -20`
Expected: no errors.

- [ ] **Step 8: Run the touched tests**

Run: `./mvnw -q -Dtest='InflowChoicesTest,FlowTotalsTest,FlowClassifierTest,RefundNettingTest,ReportServiceTest,DashboardServiceTest,BudgetServiceTest,InsightsServiceTest,InsightsExplorerServiceTest,InsightsServiceBudgetRecommendationTest,AnalyticsServiceTest,IncomeSingleEntryPointTest' test 2>&1 | tail -20`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add -A backend/src
git commit -m "feat(transactions): totals read the user's inflow choices through one context"
```

---

### Task 4: Report income by kind

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/ReportDto.java`
- Modify: `backend/src/main/java/com/finora/service/ReportService.java` (`forMonth`)
- Test: `backend/src/test/java/com/finora/service/ReportServiceTest.java`

**Interfaces:**
- Consumes: `FlowTotals.incomeLabel` (Task 3).
- Produces: `ReportDto.incomeByKind: List<ReportDto.IncomeLine>`, where `IncomeLine(String label, BigDecimal amount)` is sorted by amount descending.

- [ ] **Step 1: Write the failing test**

In `ReportServiceTest`, copy the fixture style of the existing flow-classified income test there (search for `unresolvedInflow`). The test builds a month with:
- one salary credit of 50,000;
- one person credit of 5,000 from `vpa:asha`.

The service must be built with an `InflowChoiceService` whose mocked repositories return:
- one FAMILY_SUPPORT kind;
- a rule for `vpa:asha`.

Then:

```java
    @Test
    void incomeByKindListsFamilySupportOnItsOwnLine() {
        // arrange as described above, with inflowKindRepository.findByUserId(userId) -> List.of(family)
        // and senderInflowRuleRepository.findByUserId(userId) -> List.of(ruleFor("vpa:asha", family))
        ReportDto report = reportService.forMonth(userId, "2026-08");
        assertThat(report.income()).isEqualByComparingTo("55000.00");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("0");
        assertThat(report.incomeByKind()).containsExactly(
                new ReportDto.IncomeLine("Salary", new BigDecimal("50000.00")),
                new ReportDto.IncomeLine("Family support", new BigDecimal("5000.00")));
    }
```

(Write the arrange part as real code in the file's own fixture style. `ruleFor` is a small local helper that builds a `SenderInflowRule`.)

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -Dtest=ReportServiceTest test 2>&1 | tail -20`
Expected: compilation failure on `incomeByKind`.

- [ ] **Step 3: Implement**

`ReportDto`:

```java
public record ReportDto(
        String month,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryAmount> categories,
        /* Credits Fynora cannot yet call income (money from a person, an unexplained credit-card
         * credit). Excluded from income, reported beside it so it is never silently dropped. */
        BigDecimal unresolvedInflow,
        /* Income split by what it was: the user's kind (Family support, their own kinds) or the
         * automatic reading (Salary, Interest, ...). Sums to income. Largest first. */
        List<IncomeLine> incomeByKind
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
    public record IncomeLine(String label, BigDecimal amount) {}
}
```

In `ReportService.forMonth`, before the `return`:

```java
        Map<String, BigDecimal> incomeByLabel = new java.util.LinkedHashMap<>();
        for (Transaction t : txnsForTotals) {
            if (!FlowTotals.countsAsIncome(t, flow)) continue;
            incomeByLabel.merge(FlowTotals.incomeLabel(t, flow), refunds.reportableAmount(t), BigDecimal::add);
        }
        List<ReportDto.IncomeLine> incomeByKind = incomeByLabel.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new ReportDto.IncomeLine(e.getKey(), e.getValue()))
                .toList();
```

Pass `incomeByKind` as the last argument of `new ReportDto(...)`. Run `grep -rn "new ReportDto(" backend/src` and add the argument at every site, using `List.of()` in tests.

- [ ] **Step 4: Run and watch it pass**

Run: `./mvnw -q -Dtest='ReportServiceTest' test 2>&1 | tail -20`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-inflow-kinds add -A backend/src
git -C /Users/sid/Downloads/finora-inflow-kinds commit -m "feat(transactions): monthly report splits income by kind"
```

---

### Task 5: Kind CRUD, choices, counts-as, sender rules (service)

**Files:**
- Create: `backend/src/main/java/com/finora/inflow/InflowDtos.java`
- Create: `backend/src/main/java/com/finora/inflow/SenderLabel.java`
- Create: `backend/src/main/java/com/finora/inflow/InflowKindService.java`
- Modify: `backend/src/main/java/com/finora/exception/ErrorCode.java`
- Test: `backend/src/test/java/com/finora/inflow/InflowKindServiceTest.java`
- Test: `backend/src/test/java/com/finora/inflow/InflowKindServiceIT.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `InflowKindService`:
    - `list(UUID)`, `create(UUID, CreateKindRequest)`, `update(UUID, UUID, UpdateKindRequest)`, `delete(UUID, UUID)`;
    - `setChoice(UUID userId, UUID txnId, SetChoiceRequest)` and `clearChoice(UUID, UUID, InflowChoices.Scope)`, both returning `CountsAsDto`;
    - `countsAs(UUID, UUID)`;
    - `senderRules(UUID) -> List<SenderRuleDto>` and `forgetSender(UUID userId, UUID ruleId)`.
  - `InflowDtos` records:
    - `InflowKindDto(UUID id, String name, boolean countsAsIncome, String builtIn)`;
    - `CreateKindRequest(String name, Boolean countsAsIncome)`;
    - `UpdateKindRequest(String name, Boolean countsAsIncome)`;
    - `SetChoiceRequest(UUID kindId, InflowChoices.Scope scope)`;
    - `CountsAsDto(String flowClass, String flowReason, InflowKindDto kind, String appliedBy, boolean choosable, String notChoosableReason, boolean senderAvailable, String senderLabel, long senderRowCount, String summary)`;
    - `SenderRuleDto(UUID id, String label, InflowKindDto kind, long rowCount)`;
    - `UnresolvedSenderDto(...)` and `UnresolvedRowDto(...)`, used in Task 6.
  - `SenderLabel.of(Transaction) -> String`.
  - `ErrorCode.INFLOW_KIND_IN_USE` (TXN_005, 409) and `ErrorCode.INFLOW_KIND_NAME_TAKEN` (TXN_006, 409).

- [ ] **Step 1: Write the failing unit tests**

`InflowKindServiceTest.java` builds the service with Mockito mocks:
- `InflowKindRepository kinds`, `SenderInflowRuleRepository rules`, `TransactionRepository txns`;
- `AccountRepository accounts`, `CategoryRepository categories`;
- a real `InflowChoiceService(kinds, rules)`.

```java
package com.finora.inflow;

import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.*;
import com.finora.service.InflowChoiceService;
import com.finora.service.InflowChoices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InflowKindServiceTest {

    private final UUID userId = UUID.randomUUID();
    private InflowKindRepository kinds;
    private SenderInflowRuleRepository rules;
    private TransactionRepository txns;
    private InflowKindService service;
    private final List<InflowKind> stored = new ArrayList<>();
    private final List<SenderInflowRule> storedRules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kinds = mock(InflowKindRepository.class);
        rules = mock(SenderInflowRuleRepository.class);
        txns = mock(TransactionRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        when(kinds.findByUserId(userId)).thenAnswer(i -> List.copyOf(stored));
        when(kinds.countByUserIdAndBuiltInIsNotNull(userId)).thenAnswer(i -> stored.stream().filter(k -> k.getBuiltIn() != null).count());
        when(kinds.insertBuiltInIfMissing(any(), any(), anyBoolean(), any())).thenAnswer(i -> {
            InflowKind.BuiltIn b = InflowKind.BuiltIn.valueOf(i.getArgument(3));
            if (stored.stream().anyMatch(k -> k.getBuiltIn() == b)) return 0;
            stored.add(kind(i.getArgument(1), i.getArgument(2), b));
            return 1;
        });
        when(kinds.save(any())).thenAnswer(i -> {
            InflowKind k = i.getArgument(0);
            if (k.getId() == null) ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
            if (!stored.contains(k)) stored.add(k);
            return k;
        });
        when(kinds.findById(any())).thenAnswer(i -> stored.stream().filter(k -> k.getId().equals(i.getArgument(0))).findFirst());
        when(rules.findByUserId(userId)).thenAnswer(i -> List.copyOf(storedRules));
        when(rules.findByUserIdAndCounterpartyKey(any(), any())).thenAnswer(i -> storedRules.stream()
                .filter(r -> r.getCounterpartyKey().equals(i.getArgument(1))).findFirst());
        when(rules.save(any())).thenAnswer(i -> {
            SenderInflowRule r = i.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            if (!storedRules.contains(r)) storedRules.add(r);
            return r;
        });
        when(txns.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new InflowKindService(kinds, rules, txns, accounts, categories, new InflowChoiceService(kinds, rules));
    }

    private InflowKind kind(String name, boolean countsAsIncome, InflowKind.BuiltIn b) {
        InflowKind k = new InflowKind();
        ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
        k.setUserId(userId); k.setName(name); k.setCountsAsIncome(countsAsIncome); k.setBuiltIn(b);
        return k;
    }

    private Transaction personCredit(String key) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setTxnType(Transaction.Type.INCOME);
        t.setSource(Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setAmount(new BigDecimal("5000.00"));
        t.setTxnDate(LocalDate.of(2026, 8, 3));
        t.setDescription("UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI");
        t.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        t.setCounterpartyKey(key);
        when(txns.findById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    private InflowKind builtIn(InflowKind.BuiltIn b) {
        service.list(userId);
        return stored.stream().filter(k -> k.getBuiltIn() == b).findFirst().orElseThrow();
    }

    @Test void listCreatesTheFiveBuiltInsOnce() {
        service.list(userId);
        service.list(userId);
        assertThat(service.list(userId)).extracting(InflowDtos.InflowKindDto::name)
                .containsExactly("Income", "Family support", "My own money", "Paid back to me", "Refund");
    }

    @Test void createRejectsATakenName() {
        service.list(userId);
        when(kinds.existsByUserIdAndNameIgnoreCase(userId, "income")).thenReturn(true);
        assertThatThrownBy(() -> service.create(userId, new InflowDtos.CreateKindRequest("income", true)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test void builtInIncomeFlagCannotChange() {
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.update(userId, income.getId(), new InflowDtos.UpdateKindRequest(null, false)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void builtInCanBeRenamed() {
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        assertThat(service.update(userId, family.getId(), new InflowDtos.UpdateKindRequest("From parents", null)).name())
                .isEqualTo("From parents");
    }

    @Test void builtInCannotBeDeleted() {
        InflowKind refund = builtIn(InflowKind.BuiltIn.REFUND);
        assertThatThrownBy(() -> service.delete(userId, refund.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void deletingAKindInUseIsRefusedWithCounts() {
        service.list(userId);
        InflowKind rent = kind("Rent from tenant", true, null);
        stored.add(rent);
        when(txns.countLiveByInflowKindId(rent.getId())).thenReturn(2L);
        when(rules.countByInflowKindId(rent.getId())).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(userId, rent.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException a = (ApiException) e;
                    assertThat(a.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(a.getDetails()).containsEntry("rows", 2L).containsEntry("senders", 1L);
                });
    }

    @Test void anotherUsersKindIsForbidden() {
        InflowKind theirs = kind("Theirs", true, null);
        theirs.setUserId(UUID.randomUUID());
        stored.add(theirs);
        assertThatThrownBy(() -> service.delete(userId, theirs.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test void senderScopeWritesTheRule() {
        Transaction t = personCredit("vpa:asha");
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        when(txns.countLiveCreditsBySender(userId, "vpa:asha")).thenReturn(3L);
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(family.getId(), InflowChoices.Scope.SENDER));
        assertThat(storedRules).singleElement().satisfies(r -> {
            assertThat(r.getCounterpartyKey()).isEqualTo("vpa:asha");
            assertThat(r.getInflowKindId()).isEqualTo(family.getId());
        });
        assertThat(dto.flowClass()).isEqualTo("INCOME");
        assertThat(dto.appliedBy()).isEqualTo("SENDER");
        assertThat(dto.summary()).isEqualTo("You marked payments from this sender as Family support");
    }

    @Test void senderScopeClearsTheRowChoice() {
        Transaction t = personCredit("vpa:asha");
        InflowKind paidBack = builtIn(InflowKind.BuiltIn.PAID_BACK);
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        t.setInflowKindId(paidBack.getId());
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(family.getId(), InflowChoices.Scope.SENDER));
        assertThat(t.getInflowKindId()).isNull();
        assertThat(dto.kind().name()).isEqualTo("Family support");
    }

    @Test void rowScopeSetsOnlyTheRow() {
        Transaction t = personCredit("vpa:asha");
        InflowKind paidBack = builtIn(InflowKind.BuiltIn.PAID_BACK);
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(paidBack.getId(), InflowChoices.Scope.ROW));
        assertThat(t.getInflowKindId()).isEqualTo(paidBack.getId());
        assertThat(storedRules).isEmpty();
        assertThat(dto.summary()).isEqualTo("You marked this payment as Paid back to me");
    }

    @Test void senderScopeWithoutAKeyIsRefused() {
        Transaction t = personCredit("");
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.SENDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void aDebitIsRefused() {
        Transaction t = personCredit("vpa:asha");
        t.setTxnType(Transaction.Type.EXPENSE);
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.ROW)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void aPairedTransferIsRefused() {
        Transaction t = personCredit("vpa:asha");
        t.setTransfer(true);
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.ROW)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void clearingTheRowFallsBackToTheAutomaticReading() {
        Transaction t = personCredit("vpa:asha");
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        t.setInflowKindId(income.getId());
        InflowDtos.CountsAsDto dto = service.clearChoice(userId, t.getId(), InflowChoices.Scope.ROW);
        assertThat(t.getInflowKindId()).isNull();
        assertThat(dto.flowClass()).isEqualTo("UNRESOLVED");
        assertThat(dto.kind()).isNull();
    }

    @Test void countsAsForAnUnexplainedPersonCredit() {
        Transaction t = personCredit("vpa:asha");
        when(txns.countLiveCreditsBySender(userId, "vpa:asha")).thenReturn(4L);
        InflowDtos.CountsAsDto dto = service.countsAs(userId, t.getId());
        assertThat(dto.flowClass()).isEqualTo("UNRESOLVED");
        assertThat(dto.choosable()).isTrue();
        assertThat(dto.senderAvailable()).isTrue();
        assertThat(dto.senderLabel()).isEqualTo("ASHA VERMA");
        assertThat(dto.senderRowCount()).isEqualTo(4L);
        assertThat(dto.summary()).isEqualTo("Not counted yet · from a person");
    }
}
```

Before running, confirm the setter names `setUserId`, `setTxnDate` and `setCounterpartyKey` exist on `Transaction` (`grep -n "public void set" backend/src/main/java/com/finora/entity/Transaction.java`), and adjust if they differ. Also check that `OwnAccountEvidence.counterpartySlot` returns `ASHA VERMA` for the narration used. That narration is the spec's UPI-dash shape.

- [ ] **Step 2: Run and watch it fail**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw -q -Dtest=InflowKindServiceTest test 2>&1 | tail -20`
Expected: compilation failure: package `com.finora.inflow` missing.

- [ ] **Step 3: Error codes**

In `ErrorCode.java`, after `TXN_IDEMPOTENCY_KEY_REUSED(...)`:

```java
    INFLOW_KIND_IN_USE("TXN_005", HttpStatus.CONFLICT,
            "This kind is still used. Move those payments and senders to another kind first."),
    INFLOW_KIND_NAME_TAKEN("TXN_006", HttpStatus.CONFLICT, "You already have a kind with this name."),
```

- [ ] **Step 4: DTOs**

`InflowDtos.java`:

```java
package com.finora.inflow;

import com.finora.entity.InflowKind;
import com.finora.service.InflowChoices;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the inflow-kind endpoints (Plan 2). */
public final class InflowDtos {

    private InflowDtos() {}

    public record InflowKindDto(UUID id, String name, boolean countsAsIncome, String builtIn) {
        static InflowKindDto from(InflowKind k) {
            return new InflowKindDto(k.getId(), k.getName(), k.isCountsAsIncome(),
                    k.getBuiltIn() == null ? null : k.getBuiltIn().name());
        }
    }

    public record CreateKindRequest(@NotBlank @Size(max = 60) String name, @NotNull Boolean countsAsIncome) {}

    public record UpdateKindRequest(@Size(min = 1, max = 60) String name, Boolean countsAsIncome) {}

    public record SetChoiceRequest(@NotNull UUID kindId, @NotNull InflowChoices.Scope scope) {}

    /**
     * What a credit counts as, for the "Counts as" row on the detail screen.
     *
     * @param appliedBy      ROW or SENDER when the user chose the kind, else null
     * @param senderLabel    the sender's name as printed on the payment -- never the raw key
     * @param senderRowCount live credits from the same sender (what a SENDER choice reaches)
     */
    public record CountsAsDto(String flowClass, String flowReason, InflowKindDto kind, String appliedBy,
                              boolean choosable, String notChoosableReason, boolean senderAvailable,
                              String senderLabel, long senderRowCount, String summary) {}

    public record SenderRuleDto(UUID id, String label, InflowKindDto kind, long rowCount) {}

    public record UnresolvedRowDto(UUID id, LocalDate date, BigDecimal amount, String description, String accountName) {}

    /**
     * One sender in the review list. Identified by any of its row ids (a SENDER choice on one of
     * them reaches all); the raw counterparty key never leaves the server.
     *
     * @param senderKnown false when the rows carry no sender key -- the client offers only
     *                    "Just this one" for such a group, which then holds exactly one row
     */
    public record UnresolvedSenderDto(UUID sampleTransactionId, String label, boolean senderKnown, int count,
                                      BigDecimal total, LocalDate latestDate, String accountName,
                                      List<UnresolvedRowDto> rows) {}
}
```

- [ ] **Step 5: `SenderLabel`**

```java
package com.finora.inflow;

import com.finora.entity.Transaction;
import com.finora.util.OwnAccountEvidence;

/**
 * The human-readable "who" for a sender: the name printed in the rail's sender/payee slot when the
 * narration has one of the shapes OwnAccountEvidence reads, else the narration itself -- the same
 * fallback TransactionGroupingService's counterparty groups use. Never the counterparty key.
 */
final class SenderLabel {

    private SenderLabel() {}

    static String of(Transaction t) {
        String description = t.getDescription();
        if (description != null && !description.isBlank()) {
            return OwnAccountEvidence.counterpartySlot(description).orElse(description.trim());
        }
        return t.getMerchant() == null ? "Unknown sender" : t.getMerchant();
    }
}
```

- [ ] **Step 6: `InflowKindService`**

```java
package com.finora.inflow;

import com.finora.entity.Account;
import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.FlowClassifier;
import com.finora.service.FlowTotals;
import com.finora.service.InflowChoiceService;
import com.finora.service.InflowChoices;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The user's inflow kinds and their choices (Plan 2, docs/superpowers/specs/2026-09-27-inflow-kinds-design.md).
 * Every method writes or may write (built-ins are created on first use), so none is readOnly.
 */
@Service
public class InflowKindService {

    private static final Map<FlowClassifier.FlowReason, String> AUTOMATIC_SUMMARY = new EnumMap<>(Map.ofEntries(
            Map.entry(FlowClassifier.FlowReason.SALARY, "Income · salary"),
            Map.entry(FlowClassifier.FlowReason.INTEREST, "Income · interest"),
            Map.entry(FlowClassifier.FlowReason.DIVIDEND, "Income · dividend"),
            Map.entry(FlowClassifier.FlowReason.REWARD, "Income · reward or cashback"),
            Map.entry(FlowClassifier.FlowReason.TAX_REFUND, "Income · tax refund"),
            Map.entry(FlowClassifier.FlowReason.OTHER_INCOME, "Income"),
            Map.entry(FlowClassifier.FlowReason.USER_ENTERED, "Income · added by you"),
            Map.entry(FlowClassifier.FlowReason.LINKED_REFUND, "Refund of a purchase"),
            Map.entry(FlowClassifier.FlowReason.UNLINKED_REFUND, "Refund"),
            Map.entry(FlowClassifier.FlowReason.REVERSAL, "Reversal"),
            Map.entry(FlowClassifier.FlowReason.CARD_ADJUSTMENT, "Card adjustment"),
            Map.entry(FlowClassifier.FlowReason.OWN_ACCOUNT_TRANSFER, "Transfer between your accounts"),
            Map.entry(FlowClassifier.FlowReason.CARD_PAYMENT_RECEIVED, "Card bill payment"),
            Map.entry(FlowClassifier.FlowReason.INVESTMENT_WITHDRAWAL, "Money back from an investment"),
            Map.entry(FlowClassifier.FlowReason.LOAN_DRAWDOWN, "Loan money received"),
            Map.entry(FlowClassifier.FlowReason.PERSON_INFLOW, "Not counted yet · from a person"),
            Map.entry(FlowClassifier.FlowReason.CARD_UNEXPLAINED_CREDIT, "Not counted yet · card credit")));

    private final InflowKindRepository kinds;
    private final SenderInflowRuleRepository rules;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final InflowChoiceService inflowChoices;

    public InflowKindService(InflowKindRepository kinds, SenderInflowRuleRepository rules,
                             TransactionRepository transactions, AccountRepository accounts,
                             CategoryRepository categories, InflowChoiceService inflowChoices) {
        this.kinds = kinds;
        this.rules = rules;
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.inflowChoices = inflowChoices;
    }

    // ---- kinds ----

    @Transactional
    public List<InflowDtos.InflowKindDto> list(UUID userId) {
        ensureBuiltIns(userId);
        List<InflowKind> all = new ArrayList<>(kinds.findByUserId(userId));
        all.sort(Comparator
                .comparing((InflowKind k) -> k.getBuiltIn() == null ? Integer.MAX_VALUE : k.getBuiltIn().ordinal())
                .thenComparing(k -> k.getName().toLowerCase(Locale.ROOT)));
        return all.stream().map(InflowDtos.InflowKindDto::from).toList();
    }

    @Transactional
    public InflowDtos.InflowKindDto create(UUID userId, InflowDtos.CreateKindRequest req) {
        ensureBuiltIns(userId);
        String name = req.name().trim();
        if (kinds.existsByUserIdAndNameIgnoreCase(userId, name)) throw new ApiException(ErrorCode.INFLOW_KIND_NAME_TAKEN);
        InflowKind k = new InflowKind();
        k.setUserId(userId);
        k.setName(name);
        k.setCountsAsIncome(req.countsAsIncome());
        return InflowDtos.InflowKindDto.from(kinds.save(k));
    }

    @Transactional
    public InflowDtos.InflowKindDto update(UUID userId, UUID kindId, InflowDtos.UpdateKindRequest req) {
        InflowKind k = ownedKind(userId, kindId);
        if (req.countsAsIncome() != null && k.getBuiltIn() != null && req.countsAsIncome() != k.isCountsAsIncome()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A built-in kind always " + (k.isCountsAsIncome() ? "counts" : "doesn't count")
                            + " as income. Create your own kind for a different rule.");
        }
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "A kind needs a name.");
            if (kinds.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, kindId)) {
                throw new ApiException(ErrorCode.INFLOW_KIND_NAME_TAKEN);
            }
            k.setName(name);
        }
        if (req.countsAsIncome() != null && k.getBuiltIn() == null) k.setCountsAsIncome(req.countsAsIncome());
        return InflowDtos.InflowKindDto.from(kinds.save(k));
    }

    @Transactional
    public void delete(UUID userId, UUID kindId) {
        InflowKind k = ownedKind(userId, kindId);
        if (k.getBuiltIn() != null) throw new ApiException(HttpStatus.BAD_REQUEST, "Built-in kinds can be renamed but not deleted.");
        // A deleted row the user can no longer see must not keep the kind "in use" or trip the FK.
        transactions.clearInflowKindOnDeletedRows(kindId);
        long rowCount = transactions.countLiveByInflowKindId(kindId);
        long senderCount = rules.countByInflowKindId(kindId);
        if (rowCount > 0 || senderCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INFLOW_KIND_IN_USE,
                    ErrorCode.INFLOW_KIND_IN_USE.getDefaultMessage(), Map.of("rows", rowCount, "senders", senderCount));
        }
        kinds.delete(k);
    }

    // ---- choices ----

    @Transactional
    public InflowDtos.CountsAsDto setChoice(UUID userId, UUID txnId, InflowDtos.SetChoiceRequest req) {
        Transaction t = ownedTransaction(userId, txnId);
        String refusal = notChoosableReason(t);
        if (refusal != null) throw new ApiException(HttpStatus.BAD_REQUEST, refusal);
        InflowKind kind = ownedKind(userId, req.kindId());
        if (req.scope() == InflowChoices.Scope.SENDER) {
            String key = t.getCounterpartyKey();
            if (key == null || key.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "We can't tell who sent this payment, so it can only be set on its own.");
            }
            SenderInflowRule rule = rules.findByUserIdAndCounterpartyKey(userId, key).orElseGet(() -> {
                SenderInflowRule r = new SenderInflowRule();
                r.setUserId(userId);
                r.setCounterpartyKey(key);
                return r;
            });
            rule.setInflowKindId(kind.getId());
            rule.touch();
            rules.save(rule);
            // Otherwise the sender choice would not reach the very row the user chose it on.
            if (t.getInflowKindId() != null) {
                t.setInflowKindId(null);
                transactions.save(t);
            }
        } else {
            t.setInflowKindId(kind.getId());
            transactions.save(t);
        }
        return countsAs(userId, txnId);
    }

    @Transactional
    public InflowDtos.CountsAsDto clearChoice(UUID userId, UUID txnId, InflowChoices.Scope scope) {
        Transaction t = ownedTransaction(userId, txnId);
        if (scope == InflowChoices.Scope.SENDER) {
            String key = t.getCounterpartyKey();
            if (key != null && !key.isBlank()) rules.findByUserIdAndCounterpartyKey(userId, key).ifPresent(rules::delete);
        } else if (t.getInflowKindId() != null) {
            t.setInflowKindId(null);
            transactions.save(t);
        }
        return countsAs(userId, txnId);
    }

    @Transactional
    public InflowDtos.CountsAsDto countsAs(UUID userId, UUID txnId) {
        Transaction t = ownedTransaction(userId, txnId);
        FlowTotals.Context ctx = inflowChoices.contextFor(userId, accounts.findByUserId(userId), categories.findByUserId(userId));
        FlowClassifier.FlowDecision d = FlowTotals.decision(t, ctx);
        InflowChoices.Chosen chosen = FlowTotals.chosen(t, ctx);
        // A pairing reconciliation made outranks the choice (FlowClassifier), so it is only "applied"
        // when it is what actually decided the row.
        boolean choiceDecided = chosen != null && notChoosableReason(t) == null;
        String key = t.getCounterpartyKey();
        boolean senderAvailable = key != null && !key.isBlank();
        String summary = choiceDecided
                ? (chosen.scope() == InflowChoices.Scope.SENDER ? "You marked payments from this sender as " : "You marked this payment as ")
                        + chosen.kind().getName()
                : AUTOMATIC_SUMMARY.getOrDefault(d.reason(), d.flowClass() == FlowClassifier.FlowClass.EXPENSE ? "Spending" : "Money in");
        String refusal = notChoosableReason(t);
        return new InflowDtos.CountsAsDto(d.flowClass().name(), d.reason().name(),
                choiceDecided ? InflowDtos.InflowKindDto.from(chosen.kind()) : null,
                choiceDecided ? chosen.scope().name() : null,
                refusal == null, refusal, senderAvailable,
                senderAvailable ? SenderLabel.of(t) : null,
                senderAvailable ? transactions.countLiveCreditsBySender(userId, key) : 0L,
                summary);
    }

    // ---- remembered senders ----

    @Transactional(readOnly = true)
    public List<InflowDtos.SenderRuleDto> senderRules(UUID userId) {
        Map<UUID, InflowKind> byId = new HashMap<>();
        for (InflowKind k : kinds.findByUserId(userId)) byId.put(k.getId(), k);
        List<InflowDtos.SenderRuleDto> out = new ArrayList<>();
        for (SenderInflowRule r : rules.findByUserId(userId)) {
            InflowKind k = byId.get(r.getInflowKindId());
            if (k == null) continue;
            String label = transactions.findFirstByUserIdAndCounterpartyKeyOrderByTxnDateDesc(userId, r.getCounterpartyKey())
                    .map(SenderLabel::of).orElse("A sender with no payments left");
            out.add(new InflowDtos.SenderRuleDto(r.getId(), label, InflowDtos.InflowKindDto.from(k),
                    transactions.countLiveCreditsBySender(userId, r.getCounterpartyKey())));
        }
        out.sort(Comparator.comparing(InflowDtos.SenderRuleDto::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    @Transactional
    public void forgetSender(UUID userId, UUID ruleId) {
        SenderInflowRule r = OwnershipGuard.requireOwned(rules.findById(ruleId), SenderInflowRule::getUserId, userId, "Remembered sender");
        rules.delete(r);
    }

    // ---- helpers ----

    private void ensureBuiltIns(UUID userId) {
        if (kinds.countByUserIdAndBuiltInIsNotNull(userId) >= InflowKind.BuiltIn.values().length) return;
        for (InflowKind.BuiltIn b : InflowKind.BuiltIn.values()) {
            kinds.insertBuiltInIfMissing(userId, b.defaultName(), b.countsAsIncome(), b.name());
        }
    }

    private InflowKind ownedKind(UUID userId, UUID kindId) {
        return OwnershipGuard.requireOwned(kinds.findById(kindId), InflowKind::getUserId, userId, "Kind");
    }

    private Transaction ownedTransaction(UUID userId, UUID txnId) {
        return OwnershipGuard.requireOwned(transactions.findById(txnId), Transaction::getUserId, userId, "Transaction");
    }

    /** Null when a kind may be set on this row; otherwise the plain reason it may not. */
    private static String notChoosableReason(Transaction t) {
        if (t.getTxnType() == Transaction.Type.EXPENSE) return "Only money coming in can be given a kind.";
        if (t.isTransfer()) {
            return "This payment is matched as a transfer between your accounts. Use \"Not a transfer\" first.";
        }
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND
                || t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) {
            return "This payment is already matched to the purchase it gives money back for.";
        }
        return null;
    }
}
```

Check `ErrorCode` for a getter for its default message (`grep -n "public String" backend/src/main/java/com/finora/exception/ErrorCode.java`) and use the real getter name in `delete`. If `ApiException(ErrorCode)` fills the status and message on its own but the details are needed, keep the 4-argument constructor with the real getter.

- [ ] **Step 7: Run and watch the unit tests pass**

Run: `./mvnw -q -Dtest=InflowKindServiceTest test 2>&1 | tail -30`
Expected: `Tests run: 15, Failures: 0`.

- [ ] **Step 8: Write the IT (real Postgres)**

`InflowKindServiceIT.java`:

```java
package com.finora.inflow;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.InflowKind;
import com.finora.entity.User;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class InflowKindServiceIT extends AbstractIntegrationTest {

    @Autowired private InflowKindService service;
    @Autowired private InflowKindRepository kinds;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private UUID newUser() {
        User u = new User();
        u.setEmail("inflow-svc-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Inflow Service IT");
        u.setAccountScope(User.SCOPE_USER);
        return users.save(u).getId();
    }

    @Test
    void parallelFirstCallsCreateExactlyFiveBuiltIns() throws Exception {
        UUID userId = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { go.await(); return service.list(userId); }));
            go.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(kinds.countByUserIdAndBuiltInIsNotNull(userId)).isEqualTo(5);
    }

    @Test
    void deleteClearsSoftDeletedRowsFirst() {
        UUID userId = newUser();
        InflowDtos.InflowKindDto rent = service.create(userId, new InflowDtos.CreateKindRequest("Rent from tenant", true));
        // A soft-deleted credit of this user still pointing at the kind. Insert it with the same
        // helper/fixture other transaction ITs in this repo use (search for an IT that saves a
        // Transaction with an Account), then:
        //   txn.setInflowKindId(rent.id()); transactionRepository.save(txn); transactionRepository.delete(txn);
        service.delete(userId, rent.id());
        assertThat(kinds.findById(rent.id())).isEmpty();
    }
}
```

The fixture lines in `deleteClearsSoftDeletedRowsFirst` describe what to write. Write them as real code, copying an existing IT's account and transaction setup; `grep -rln "transactionRepository.save" backend/src/test/java --include=*IT.java | head` finds one.

Run: `./mvnw -q -Dtest=InflowKindServiceIT test 2>&1 | tail -20`
Expected: pass. Watch it fail first by temporarily commenting out the `clearInflowKindOnDeletedRows` line, which should make it fail with 409 in-use; then restore the line.

- [ ] **Step 9: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add -A backend/src
git commit -m "feat(transactions): inflow kind CRUD, per-sender and per-row choices"
```

---

### Task 6: Review list

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReportService.java` (add `unresolvedInflows`)
- Create: `backend/src/main/java/com/finora/inflow/InflowReviewService.java`
- Test: `backend/src/test/java/com/finora/service/ReportServiceTest.java` (add), `backend/src/test/java/com/finora/inflow/InflowReviewServiceTest.java`

**Interfaces:**
- Consumes: `ReportService` (Task 3 wiring), `InflowDtos.UnresolvedSenderDto` and `SenderLabel` (Task 5).
- Produces:
  - `ReportService.unresolvedInflows(UUID userId, LocalDate from, LocalDate to) -> ReportService.UnresolvedRows(List<Transaction> rows, List<Account> accounts)`;
  - `InflowReviewService.groups(UUID userId, LocalDate from, LocalDate to) -> List<InflowDtos.UnresolvedSenderDto>`.

- [ ] **Step 1: Write the failing tests**

In `ReportServiceTest`, use the fixture of an existing `forRange` unresolved test (search `unresolvedInflowCount`):

```java
    @Test
    void unresolvedInflowsMatchForRangeCount() {
        // same arrange as the existing forRange unresolved test in this file
        ReportService.RangeTotals totals = reportService.forRange(userId, from, to);
        ReportService.UnresolvedRows rows = reportService.unresolvedInflows(userId, from, to);
        assertThat(rows.rows()).hasSize(totals.unresolvedInflowCount());
        assertThat(rows.rows().stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(totals.unresolvedInflow());
    }
```

`InflowReviewServiceTest.java`: mock `ReportService.unresolvedInflows` to return three rows:
- two from `vpa:asha` (5,000 on 2026-08-03 and 2,000 on 2026-08-20);
- one with a blank key (700).

All three are on account "Savings One". Then:

```java
    @Test
    void groupsBySenderLargestFirstAndKeepsKeylessRowsApart() {
        List<InflowDtos.UnresolvedSenderDto> groups = service.groups(userId, from, to);
        assertThat(groups).extracting(InflowDtos.UnresolvedSenderDto::count).containsExactly(2, 1);
        InflowDtos.UnresolvedSenderDto asha = groups.get(0);
        assertThat(asha.total()).isEqualByComparingTo("7000.00");
        assertThat(asha.latestDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(asha.label()).isEqualTo("ASHA VERMA");
        assertThat(asha.senderKnown()).isTrue();
        assertThat(asha.accountName()).isEqualTo("Savings One");
        assertThat(groups.get(1).senderKnown()).isFalse();
    }
```

(Write the rows with the `personCredit` style from Task 5, using the narration `UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI` for the Asha rows and `CASH DEPOSIT BRANCH` for the keyless row. Build an `Account` with name "Savings One" and a set id; mocking `getId`/`getName` is fine.)

- [ ] **Step 2: Run and watch them fail**

Run: `./mvnw -q -Dtest='ReportServiceTest,InflowReviewServiceTest' test 2>&1 | tail -20`
Expected: compilation failure: `unresolvedInflows` / `InflowReviewService` not found.

- [ ] **Step 3: Implement `ReportService.unresolvedInflows`**

After `forRange`:

```java
    /** The rows forRange counts as unresolved, built the same way, so the review list and the
     *  banner can never disagree. */
    public record UnresolvedRows(List<Transaction> rows, List<com.finora.entity.Account> accounts) {}

    @Transactional(readOnly = true)
    public UnresolvedRows unresolvedInflows(UUID userId, LocalDate from, LocalDate to) {
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        FlowTotals.Context flow = inflowChoices.contextFor(userId, accounts, categoryRepository.findByUserId(userId));
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        List<Transaction> txnsForTotals = RefundNetting.excludingInvestmentTransfers(RefundNetting.reportable(
                rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns)));
        return new UnresolvedRows(txnsForTotals.stream().filter(t -> FlowTotals.isUnresolvedInflow(t, flow)).toList(), accounts);
    }
```

- [ ] **Step 4: Implement `InflowReviewService`**

```java
package com.finora.inflow;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** "Money not counted yet": the unresolved credits in a period, one group per sender (Plan 2). */
@Service
public class InflowReviewService {

    private final ReportService reportService;

    public InflowReviewService(ReportService reportService) {
        this.reportService = reportService;
    }

    @Transactional(readOnly = true)
    public List<InflowDtos.UnresolvedSenderDto> groups(UUID userId, LocalDate from, LocalDate to) {
        ReportService.UnresolvedRows unresolved = reportService.unresolvedInflows(userId, from, to);
        Map<UUID, String> accountNames = new HashMap<>();
        for (Account a : unresolved.accounts()) accountNames.put(a.getId(), a.getName());

        Map<String, List<Transaction>> bySender = new LinkedHashMap<>();
        for (Transaction t : unresolved.rows()) {
            String key = t.getCounterpartyKey();
            // A row with no sender key cannot share a rule, so it is its own group.
            String group = key == null || key.isBlank() ? "row:" + t.getId() : key;
            bySender.computeIfAbsent(group, g -> new ArrayList<>()).add(t);
        }

        List<InflowDtos.UnresolvedSenderDto> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : bySender.entrySet()) {
            List<Transaction> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparing(Transaction::getTxnDate).reversed());
            Transaction latest = rows.get(0);
            BigDecimal total = rows.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new InflowDtos.UnresolvedSenderDto(latest.getId(), SenderLabel.of(latest),
                    !e.getKey().startsWith("row:"), rows.size(), total, latest.getTxnDate(),
                    accountNames.get(latest.getAccountId()),
                    rows.stream().map(t -> new InflowDtos.UnresolvedRowDto(t.getId(), t.getTxnDate(), t.getAmount(),
                            t.getDescription(), accountNames.get(t.getAccountId()))).toList()));
        }
        out.sort(Comparator.comparing(InflowDtos.UnresolvedSenderDto::total).reversed());
        return out;
    }
}
```

- [ ] **Step 5: Run and watch them pass**

Run: `./mvnw -q -Dtest='ReportServiceTest,InflowReviewServiceTest' test 2>&1 | tail -20`
Expected: pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora-inflow-kinds add -A backend/src
git -C /Users/sid/Downloads/finora-inflow-kinds commit -m "feat(transactions): review list of money not counted yet, grouped by sender"
```

---

### Task 7: Endpoints, change stamp, OpenAPI

**Files:**
- Create: `backend/src/main/java/com/finora/inflow/InflowController.java`
- Modify: `backend/src/main/java/com/finora/service/ChangeStampService.java`
- Test: `backend/src/test/java/com/finora/inflow/InflowControllerIT.java`
- Test: `backend/src/test/java/com/finora/controller/ChangeStampControllerIT.java` (add one test)
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts` (confirm the path with `ls */src/api/generated-types.ts`)

**Interfaces:**
- Consumes: Tasks 5–6.
- Produces the HTTP surface the clients use:
  - Kinds:
    - `GET /api/v1/inflow-kinds` → `InflowKindDto[]`;
    - `POST /api/v1/inflow-kinds` `{name, countsAsIncome}` → `InflowKindDto`;
    - `PATCH /api/v1/inflow-kinds/{id}` `{name?, countsAsIncome?}` → `InflowKindDto`;
    - `DELETE /api/v1/inflow-kinds/{id}` → void.
  - Choices:
    - `GET /api/v1/transactions/{id}/counts-as` → `CountsAsDto`;
    - `PUT /api/v1/transactions/{id}/inflow-kind` `{kindId, scope}` → `CountsAsDto`;
    - `DELETE /api/v1/transactions/{id}/inflow-kind?scope=ROW|SENDER` → `CountsAsDto`.
  - Remembered senders:
    - `GET /api/v1/sender-inflow-rules` → `SenderRuleDto[]`;
    - `DELETE /api/v1/sender-inflow-rules/{id}` → void.
  - Review list: `GET /api/v1/transactions/unresolved-inflows?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD` → `UnresolvedSenderDto[]`.

- [ ] **Step 1: Write the failing IT**

`InflowControllerIT.java`: copy the user, bearer and `get` helpers from `AnalyticsControllerIT`, and add `send(method, path, body, user)`. Tests:

```java
    @Test
    void kindsRoundTrip() throws Exception {
        User user = createUser();
        JsonNode list = mapper.readTree(get("/api/v1/inflow-kinds", user).getBody()).get("data");
        assertThat(list).hasSize(5);

        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"Rent from tenant\",\"countsAsIncome\":true}", user);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = mapper.readTree(created.getBody()).get("data").get("id").asText();

        ResponseEntity<String> dup = send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"rent from TENANT\",\"countsAsIncome\":false}", user);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(dup.getBody()).get("errorCode").asText()).isEqualTo("TXN_006");

        assertThat(send(HttpMethod.DELETE, "/api/v1/inflow-kinds/" + id, null, user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherUsersKindIsForbidden() throws Exception {
        User owner = createUser(), other = createUser();
        String id = mapper.readTree(send(HttpMethod.POST, "/api/v1/inflow-kinds",
                "{\"name\":\"Mine\",\"countsAsIncome\":true}", owner).getBody()).get("data").get("id").asText();
        assertThat(send(HttpMethod.DELETE, "/api/v1/inflow-kinds/" + id, null, other).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unresolvedInflowsRequiresBothDates() {
        User user = createUser();
        assertThat(get("/api/v1/transactions/unresolved-inflows?startDate=2026-08-01", user).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/transactions/unresolved-inflows?startDate=2026-08-01&endDate=2026-08-31", user)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }
```

Check the error JSON field name (`errorCode` vs `code`) against `AnalyticsControllerIT`'s assertions, which read `errorCode`.

In `ChangeStampControllerIT`, add a test modelled on that file's existing ones: read the stamp, create a custom kind through the service (or the endpoint), read the stamp again, and assert that the `transactions` section changed.

- [ ] **Step 2: Run and watch them fail**

Run: `./mvnw -q -Dtest='InflowControllerIT,ChangeStampControllerIT' test 2>&1 | tail -20`
Expected: 404 on the new paths, and the stamp test fails (unchanged).

- [ ] **Step 3: Controller**

```java
package com.finora.inflow;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import com.finora.service.InflowChoices;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Inflow kinds and the user's choices (Plan 2). */
@RestController
@RequestMapping("/api/v1")
public class InflowController {

    private final InflowKindService kinds;
    private final InflowReviewService review;
    private final CurrentUser currentUser;

    public InflowController(InflowKindService kinds, InflowReviewService review, CurrentUser currentUser) {
        this.kinds = kinds;
        this.review = review;
        this.currentUser = currentUser;
    }

    @GetMapping("/inflow-kinds")
    public ApiResponse<List<InflowDtos.InflowKindDto>> list() {
        return ApiResponse.ok(kinds.list(currentUser.id()));
    }

    @PostMapping("/inflow-kinds")
    public ApiResponse<InflowDtos.InflowKindDto> create(@Valid @RequestBody InflowDtos.CreateKindRequest req) {
        return ApiResponse.ok(kinds.create(currentUser.id(), req));
    }

    @PatchMapping("/inflow-kinds/{id}")
    public ApiResponse<InflowDtos.InflowKindDto> update(@PathVariable UUID id, @Valid @RequestBody InflowDtos.UpdateKindRequest req) {
        return ApiResponse.ok(kinds.update(currentUser.id(), id, req));
    }

    @DeleteMapping("/inflow-kinds/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        kinds.delete(currentUser.id(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/transactions/{id}/counts-as")
    public ApiResponse<InflowDtos.CountsAsDto> countsAs(@PathVariable UUID id) {
        return ApiResponse.ok(kinds.countsAs(currentUser.id(), id));
    }

    @PutMapping("/transactions/{id}/inflow-kind")
    public ApiResponse<InflowDtos.CountsAsDto> setChoice(@PathVariable UUID id, @Valid @RequestBody InflowDtos.SetChoiceRequest req) {
        return ApiResponse.ok(kinds.setChoice(currentUser.id(), id, req));
    }

    @DeleteMapping("/transactions/{id}/inflow-kind")
    public ApiResponse<InflowDtos.CountsAsDto> clearChoice(@PathVariable UUID id, @RequestParam InflowChoices.Scope scope) {
        return ApiResponse.ok(kinds.clearChoice(currentUser.id(), id, scope));
    }

    @GetMapping("/sender-inflow-rules")
    public ApiResponse<List<InflowDtos.SenderRuleDto>> senderRules() {
        return ApiResponse.ok(kinds.senderRules(currentUser.id()));
    }

    @DeleteMapping("/sender-inflow-rules/{id}")
    public ApiResponse<Void> forgetSender(@PathVariable UUID id) {
        kinds.forgetSender(currentUser.id(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/transactions/unresolved-inflows")
    public ApiResponse<List<InflowDtos.UnresolvedSenderDto>> unresolved(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(review.groups(currentUser.id(), startDate, endDate));
    }
}
```

Confirm the `ApiResponse` and `CurrentUser` import paths against `TransactionController`'s imports and fix them if they differ.

- [ ] **Step 4: Change stamp**

In `ChangeStampService.SQL`, replace the first subquery (transactions) with:

```sql
              (SELECT count(*) || ':' || coalesce(sum(version), 0) || ':' || coalesce(max(created_at)::text, '')
                 FROM transactions WHERE user_id = ? AND deleted_at IS NULL)
                || ':' || coalesce((SELECT md5(string_agg(k::text, ',' ORDER BY k.id)) FROM inflow_kinds k WHERE k.user_id = ?), '')
                || ':' || coalesce((SELECT md5(string_agg(r::text, ',' ORDER BY r.id)) FROM sender_inflow_rules r WHERE r.user_id = ?), ''),
```

Add two more `userId` arguments to the `jdbc.query(...)` call, making twelve in total. Every placeholder takes the same `userId`, so their order doesn't matter.

- [ ] **Step 5: Run and watch them pass**

Run: `./mvnw -q -Dtest='InflowControllerIT,ChangeStampControllerIT,ChangeStampCachingIT' test 2>&1 | tail -20`
Expected: pass.

- [ ] **Step 6: Regenerate OpenAPI and the three generated type files**

1. Start a throwaway Postgres: `docker run -d --name finora-openapi-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=finora -p 5499:5432 postgres:16-alpine`
2. Run `DB_PORT=5499 scripts/generate-openapi-spec.sh`. Read the script header first for any other env var it needs.
3. Generate each client's types, from `mobile/` after `npm i --no-save typescript`:
   `npx --yes openapi-typescript@7.13.0 ../backend/openapi/openapi.json -o <client>/src/api/generated-types.ts`
   Use the exact output paths the CI drift check uses: `grep -rn "generated-types" .github/workflows scripts | head`.
4. Stop the database: `docker rm -f finora-openapi-pg`.

Run: `git -C /Users/sid/Downloads/finora-inflow-kinds diff --stat backend/openapi frontend/src/api mobile/src/api admin-portal/src/api`
Expected: all four files changed, each containing `inflow-kinds`.

- [ ] **Step 7: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add -A backend/src backend/openapi frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts admin-portal/src/api/generated-types.ts
git commit -m "feat(transactions): inflow kind endpoints, change stamp, OpenAPI"
```

---

### Task 8: Web

**Files:**
- Modify: `frontend/src/api/endpoints.ts` (types + `inflowApi`; `incomeByKind` on the report type — find it with `grep -n "unresolvedInflow" frontend/src/api/endpoints.ts frontend/src/types/index.ts`)
- Create: `frontend/src/components/inflow/InflowKindPicker.tsx`, `InflowKindPicker.test.tsx`
- Create: `frontend/src/components/inflow/CountsAsSection.tsx`, `CountsAsSection.test.tsx`
- Create: `frontend/src/pages/MoneyReview.tsx`, `MoneyReview.test.tsx`
- Create: `frontend/src/pages/settings/InflowKindsSection.tsx`, `InflowKindsSection.test.tsx`
- Modify: `frontend/src/App.tsx` (route), `frontend/src/pages/Ledger.tsx` (ExplanationModal), `frontend/src/pages/Dashboard.tsx` (banner link), `frontend/src/pages/Reports.tsx` (income by kind), `frontend/src/pages/settings/CategorizationPane.tsx`

**Interfaces:**
- Consumes: the Task 7 endpoints.
- Produces: `inflowApi` with `kinds`, `createKind`, `updateKind`, `deleteKind`, `countsAs`, `setChoice`, `clearChoice`, `senderRules`, `forgetSender`, `unresolved`.

- [ ] **Step 1: API and types**

Append to `frontend/src/api/endpoints.ts`:

```ts
// ---- Inflow kinds (Plan 2): what money that came in actually was ----

export type InflowBuiltIn = 'INCOME' | 'FAMILY_SUPPORT' | 'OWN_MONEY' | 'PAID_BACK' | 'REFUND';
export type ChoiceScope = 'ROW' | 'SENDER';

export interface InflowKind {
  id: string;
  name: string;
  countsAsIncome: boolean;
  builtIn: InflowBuiltIn | null;
}

export interface CountsAs {
  flowClass: string;
  flowReason: string;
  kind: InflowKind | null;
  appliedBy: ChoiceScope | null;
  choosable: boolean;
  notChoosableReason: string | null;
  senderAvailable: boolean;
  senderLabel: string | null;
  senderRowCount: number;
  summary: string;
}

export interface SenderRule {
  id: string;
  label: string;
  kind: InflowKind;
  rowCount: number;
}

export interface UnresolvedRow {
  id: string;
  date: string;
  amount: number;
  description: string | null;
  accountName: string | null;
}

export interface UnresolvedSender {
  sampleTransactionId: string;
  label: string;
  senderKnown: boolean;
  count: number;
  total: number;
  latestDate: string;
  accountName: string | null;
  rows: UnresolvedRow[];
}

export const inflowApi = {
  kinds: () => api.get<InflowKind[]>('/inflow-kinds').then((r) => r.data),
  createKind: (name: string, countsAsIncome: boolean) =>
    api.post<InflowKind>('/inflow-kinds', { name, countsAsIncome }).then((r) => r.data),
  updateKind: (id: string, body: { name?: string; countsAsIncome?: boolean }) =>
    api.patch<InflowKind>(`/inflow-kinds/${id}`, body).then((r) => r.data),
  deleteKind: (id: string) => api.delete(`/inflow-kinds/${id}`),
  countsAs: (transactionId: string) =>
    api.get<CountsAs>(`/transactions/${transactionId}/counts-as`).then((r) => r.data),
  setChoice: (transactionId: string, kindId: string, scope: ChoiceScope) =>
    api.put<CountsAs>(`/transactions/${transactionId}/inflow-kind`, { kindId, scope }).then((r) => r.data),
  clearChoice: (transactionId: string, scope: ChoiceScope) =>
    api.delete<CountsAs>(`/transactions/${transactionId}/inflow-kind`, { params: { scope } }).then((r) => r.data),
  senderRules: () => api.get<SenderRule[]>('/sender-inflow-rules').then((r) => r.data),
  forgetSender: (id: string) => api.delete(`/sender-inflow-rules/${id}`),
  unresolved: (startDate: string, endDate: string) =>
    api.get<UnresolvedSender[]>('/transactions/unresolved-inflows', { params: { startDate, endDate } }).then((r) => r.data),
};
```

On the report type (where `unresolvedInflow` is declared for the monthly report), add:

```ts
  /** Income split by kind, largest first; sums to income. */
  incomeByKind?: { label: string; amount: number }[];
```

- [ ] **Step 2: Picker — failing test**

`InflowKindPicker.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InflowKindPicker } from './InflowKindPicker';
import type { InflowKind } from '../../api/endpoints';

const kinds: InflowKind[] = [
  { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' },
  { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' },
  { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null },
];

describe('InflowKindPicker', () => {
  it('picks a kind', async () => {
    const onPick = vi.fn();
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={onPick} onCreate={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /Family support/ }));
    expect(onPick).toHaveBeenCalledWith(kinds[1]);
  });

  it('creates a kind only after the income question is answered', async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={vi.fn()} onCreate={onCreate} />);
    await userEvent.click(screen.getByRole('button', { name: '+ New kind…' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Split with flatmate');
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.click(screen.getByRole('radio', { name: "No, don't count it" }));
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(onCreate).toHaveBeenCalledWith('Split with flatmate', false);
  });

  it('shows the server message when a name is taken', async () => {
    const onCreate = vi.fn().mockRejectedValue({ response: { data: { message: 'You already have a kind with this name.' } } });
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={vi.fn()} onCreate={onCreate} />);
    await userEvent.click(screen.getByRole('button', { name: '+ New kind…' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Income');
    await userEvent.click(screen.getByRole('radio', { name: 'Yes, count it' }));
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('You already have a kind with this name.')).toBeInTheDocument();
  });
});
```

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/frontend && npx vitest run src/components/inflow/InflowKindPicker.test.tsx 2>&1 | tail -15`
Expected: FAIL: cannot resolve `./InflowKindPicker`.

- [ ] **Step 3: Picker — implement**

```tsx
import { useState } from 'react';
import { Button } from '../../design-system/Button';
import type { InflowKind } from '../../api/endpoints';

/** The list of kinds a credit can be given, plus "+ New kind…" (Plan 2). Used by the review page
 *  and the "Counts as" section, so both offer exactly the same choices. */
export function InflowKindPicker({ kinds, selectedId, onPick, onCreate, busy }: {
  kinds: InflowKind[];
  selectedId: string | null;
  onPick: (kind: InflowKind) => void;
  onCreate: (name: string, countsAsIncome: boolean) => Promise<void>;
  busy?: boolean;
}) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [countsAsIncome, setCountsAsIncome] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!name.trim() || countsAsIncome === null) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(name.trim(), countsAsIncome);
      setCreating(false);
      setName('');
      setCountsAsIncome(null);
    } catch (e: unknown) {
      const message = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(message ?? 'Could not create this kind.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {kinds.map((k) => (
          <button
            key={k.id}
            type="button"
            disabled={busy}
            aria-pressed={selectedId === k.id}
            onClick={() => onPick(k)}
            className={`px-3 py-1.5 rounded-full border text-xs ${selectedId === k.id ? 'border-primary bg-primary-light text-ink' : 'border-border text-ink hover:bg-bg'}`}
          >
            {k.name}
            <span className="text-muted">{k.countsAsIncome ? ' · income' : ' · not income'}</span>
          </button>
        ))}
        {!creating && (
          <button type="button" onClick={() => setCreating(true)} className="px-3 py-1.5 rounded-full border border-dashed border-border text-xs text-muted">
            + New kind…
          </button>
        )}
      </div>
      {creating && (
        <div className="border border-border rounded-xl2 p-3 space-y-2">
          <label className="block text-xs text-muted">
            Name
            <input
              value={name}
              maxLength={60}
              onChange={(e) => setName(e.target.value)}
              className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm text-ink bg-card"
            />
          </label>
          <fieldset className="text-xs text-ink space-y-1">
            <legend className="text-muted">Count this as income?</legend>
            <label className="flex items-center gap-2">
              <input type="radio" name="counts-as-income" checked={countsAsIncome === true} onChange={() => setCountsAsIncome(true)} />
              Yes, count it
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name="counts-as-income" checked={countsAsIncome === false} onChange={() => setCountsAsIncome(false)} />
              No, don't count it
            </label>
          </fieldset>
          {error && <p className="text-danger text-xs">{error}</p>}
          <div className="flex gap-2">
            <Button size="sm" onClick={submit} loading={saving} disabled={!name.trim() || countsAsIncome === null}>Create</Button>
            <Button size="sm" variant="secondary" onClick={() => { setCreating(false); setError(null); }}>Cancel</Button>
          </div>
        </div>
      )}
    </div>
  );
}
```

Run the test again. Expected: 3 passed.

- [ ] **Step 4: CountsAsSection — failing test**

`CountsAsSection.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CountsAsSection } from './CountsAsSection';
import { inflowApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  inflowApi: { countsAs: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn() },
}));

const unresolved = {
  flowClass: 'UNRESOLVED', flowReason: 'PERSON_INFLOW', kind: null, appliedBy: null, choosable: true,
  notChoosableReason: null, senderAvailable: true, senderLabel: 'ASHA VERMA', senderRowCount: 3,
  summary: 'Not counted yet · from a person',
};
const family = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' as const };

describe('CountsAsSection', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue(unresolved);
    vi.mocked(inflowApi.kinds).mockResolvedValue([family]);
  });

  it('sets a kind for every payment from the sender by default', async () => {
    vi.mocked(inflowApi.setChoice).mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', flowReason: 'FAMILY_SUPPORT', kind: family, appliedBy: 'SENDER',
      summary: 'You marked payments from this sender as Family support',
    });
    const onChanged = vi.fn();
    render(<CountsAsSection transactionId="t1" onChanged={onChanged} />);
    expect(await screen.findByText('Not counted yet · from a person')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Every payment from ASHA VERMA (3)' }));
    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText('You marked payments from this sender as Family support')).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('offers only this payment when the sender is unknown', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({ ...unresolved, senderAvailable: false, senderLabel: null, senderRowCount: 0 });
    render(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    expect(screen.queryByRole('button', { name: /Every payment from/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Just this one' })).toBeInTheDocument();
  });

  it('shows the reason and no Change button when the row cannot take a kind', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({
      ...unresolved, flowClass: 'TRANSFER', flowReason: 'OWN_ACCOUNT_TRANSFER', choosable: false,
      notChoosableReason: 'This payment is matched as a transfer between your accounts. Use "Not a transfer" first.',
      summary: 'Transfer between your accounts',
    });
    render(<CountsAsSection transactionId="t1" />);
    expect(await screen.findByText(/Use "Not a transfer" first/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change' })).toBeNull();
  });

  it('clears the user choice', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', kind: family, appliedBy: 'ROW', summary: 'You marked this payment as Family support',
    });
    vi.mocked(inflowApi.clearChoice).mockResolvedValue(unresolved);
    render(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Clear my choice' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'ROW'));
  });
});
```

Run: `npx vitest run src/components/inflow/CountsAsSection.test.tsx 2>&1 | tail -15`
Expected: FAIL: cannot resolve `./CountsAsSection`.

- [ ] **Step 5: CountsAsSection — implement**

```tsx
import { useEffect, useState } from 'react';
import { inflowApi, type CountsAs, type InflowKind } from '../../api/endpoints';
import { Button } from '../../design-system/Button';
import { InflowKindPicker } from './InflowKindPicker';

/** "Counts as" on a transaction's detail panel: what this credit counts as, and the user's way to
 *  change or clear it (Plan 2). Hidden for debits by the caller. */
export function CountsAsSection({ transactionId, onChanged }: { transactionId: string; onChanged?: () => void }) {
  const [countsAs, setCountsAs] = useState<CountsAs | null>(null);
  const [kinds, setKinds] = useState<InflowKind[] | null>(null);
  const [editing, setEditing] = useState(false);
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    inflowApi.countsAs(transactionId)
      .then((r) => { if (!cancelled) setCountsAs(r); })
      .catch(() => { if (!cancelled) setError("Couldn't load what this payment counts as."); });
    return () => { cancelled = true; };
  }, [transactionId]);

  async function openEditor() {
    setEditing(true);
    setPicked(null);
    if (!kinds) setKinds(await inflowApi.kinds());
  }

  async function run(action: () => Promise<CountsAs>) {
    setBusy(true);
    setError(null);
    try {
      setCountsAs(await action());
      setEditing(false);
      onChanged?.();
    } catch (e: unknown) {
      setError((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Could not save this choice.');
    } finally {
      setBusy(false);
    }
  }

  if (error && !countsAs) return <p className="text-danger text-xs">{error}</p>;
  if (!countsAs) return null;

  return (
    <div className="space-y-2" data-testid="counts-as-section">
      <p className="text-xs text-muted">Counts as</p>
      <p className="text-ink text-sm">{countsAs.summary}</p>
      {!countsAs.choosable && countsAs.notChoosableReason && (
        <p className="text-xs text-muted">{countsAs.notChoosableReason}</p>
      )}
      {countsAs.choosable && !editing && (
        <div className="flex gap-2">
          <Button size="sm" variant="secondary" onClick={openEditor}>Change</Button>
          {countsAs.appliedBy && (
            <Button size="sm" variant="secondary" loading={busy}
              onClick={() => run(() => inflowApi.clearChoice(transactionId, countsAs.appliedBy!))}>
              Clear my choice
            </Button>
          )}
        </div>
      )}
      {editing && kinds && (
        <div className="space-y-2">
          <InflowKindPicker
            kinds={kinds}
            selectedId={picked?.id ?? null}
            onPick={setPicked}
            busy={busy}
            onCreate={async (name, countsAsIncome) => {
              const created = await inflowApi.createKind(name, countsAsIncome);
              setKinds([...kinds, created]);
              setPicked(created);
            }}
          />
          {picked && (
            <div className="flex flex-wrap gap-2">
              {countsAs.senderAvailable && (
                <Button size="sm" loading={busy} onClick={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'SENDER'))}>
                  {`Every payment from ${countsAs.senderLabel} (${countsAs.senderRowCount})`}
                </Button>
              )}
              <Button size="sm" variant={countsAs.senderAvailable ? 'secondary' : 'primary'} loading={busy}
                onClick={() => run(() => inflowApi.setChoice(transactionId, picked.id, 'ROW'))}>
                Just this one
              </Button>
            </div>
          )}
          <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      )}
      {error && countsAs && <p className="text-danger text-xs">{error}</p>}
    </div>
  );
}
```

Run the test. Expected: 4 passed.

- [ ] **Step 6: Wire into the Ledger ExplanationModal**

In `Ledger.tsx`'s `ExplanationModal`, directly after the block that renders `explanation.reconciliation` and before the category summary paragraph (around line 1092–1113), add:

```tsx
              {transaction.type !== 'EXPENSE' && (
                <div className="border-b border-border pb-3 mb-3">
                  <CountsAsSection transactionId={transaction.id} />
                </div>
              )}
```

and `import { CountsAsSection } from '../components/inflow/CountsAsSection';`. Check that the web `Transaction` type field is named `type`: `grep -n "type: 'INCOME'" frontend/src/types/index.ts`.

In `Ledger.test.tsx`, extend the existing `vi.mock('../api/endpoints', ...)` factory with an `inflowApi` whose `countsAs` resolves the `unresolved` object above. Then add one test: opening the explanation for an INCOME row shows "Counts as", and opening it for an EXPENSE row doesn't. Run: `npx vitest run src/pages/Ledger.test.tsx 2>&1 | tail -15`, and watch the new test fail before the wiring and pass after.

- [ ] **Step 7: Review page — failing test**

`MoneyReview.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { MoneyReview } from './MoneyReview';
import { inflowApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  inflowApi: { unresolved: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn() },
}));

const asha = {
  sampleTransactionId: 't1', label: 'ASHA VERMA', senderKnown: true, count: 2, total: 7000,
  latestDate: '2026-08-20', accountName: 'Savings One',
  rows: [
    { id: 't1', date: '2026-08-20', amount: 2000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
    { id: 't0', date: '2026-08-03', amount: 5000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
  ],
};
const family = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' as const };

function renderAt(url: string) {
  return render(<MemoryRouter initialEntries={[url]}><MoneyReview /></MemoryRouter>);
}

describe('MoneyReview', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.kinds).mockResolvedValue([family]);
  });

  it('loads the period from the URL and lists senders', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValue([asha]);
    renderAt('/app/money-review?start=2026-08-01&end=2026-08-31');
    expect(await screen.findByText('ASHA VERMA')).toBeInTheDocument();
    expect(inflowApi.unresolved).toHaveBeenCalledWith('2026-08-01', '2026-08-31');
  });

  it('removes a sender once every payment from them is set, and offers undo', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValueOnce([asha]).mockResolvedValue([]);
    vi.mocked(inflowApi.setChoice).mockResolvedValue({} as never);
    vi.mocked(inflowApi.clearChoice).mockResolvedValue({} as never);
    renderAt('/app/money-review?start=2026-08-01&end=2026-08-31');
    await userEvent.click(await screen.findByText('ASHA VERMA'));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Every payment from ASHA VERMA (2)' }));
    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText("Everything's sorted")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'SENDER'));
  });
});
```

Run: `npx vitest run src/pages/MoneyReview.test.tsx 2>&1 | tail -15`
Expected: FAIL: cannot resolve `./MoneyReview`.

- [ ] **Step 8: Review page — implement**

```tsx
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { inflowApi, type InflowKind, type UnresolvedSender } from '../api/endpoints';
import { Button } from '../design-system/Button';
import { FinoraCard } from '../design-system/FinoraCard';
import { InflowKindPicker } from '../components/inflow/InflowKindPicker';

function monthBounds(today = new Date()): [string, string] {
  const y = today.getFullYear(), m = today.getMonth();
  const pad = (n: number) => String(n).padStart(2, '0');
  const last = new Date(y, m + 1, 0).getDate();
  return [`${y}-${pad(m + 1)}-01`, `${y}-${pad(m + 1)}-${pad(last)}`];
}

const fmt = (n: number) => `₹${n.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;

/** "Money not counted yet" (Plan 2): the credits Fynora left out of income, one row per sender. */
export function MoneyReview() {
  const [params] = useSearchParams();
  const [defaultStart, defaultEnd] = monthBounds();
  const start = params.get('start') ?? defaultStart;
  const end = params.get('end') ?? defaultEnd;
  const [senders, setSenders] = useState<UnresolvedSender[] | null>(null);
  const [kinds, setKinds] = useState<InflowKind[]>([]);
  const [open, setOpen] = useState<UnresolvedSender | null>(null);
  const [picked, setPicked] = useState<InflowKind | null>(null);
  const [oneRow, setOneRow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ transactionId: string; scope: 'ROW' | 'SENDER'; label: string } | null>(null);

  const load = useCallback(() => {
    inflowApi.unresolved(start, end).then(setSenders).catch(() => setError("Couldn't load this list — please try again."));
  }, [start, end]);

  useEffect(() => { load(); inflowApi.kinds().then(setKinds).catch(() => undefined); }, [load]);

  async function apply(transactionId: string, scope: 'ROW' | 'SENDER', label: string) {
    if (!picked) return;
    setBusy(true);
    setError(null);
    try {
      await inflowApi.setChoice(transactionId, picked.id, scope);
      setUndo({ transactionId, scope, label });
      setOpen(null);
      setPicked(null);
      setOneRow(false);
      load();
    } catch (e: unknown) {
      setError((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'Could not save this choice.');
    } finally {
      setBusy(false);
    }
  }

  async function undoLast() {
    if (!undo) return;
    await inflowApi.clearChoice(undo.transactionId, undo.scope);
    setUndo(null);
    load();
  }

  return (
    <div className="max-w-3xl mx-auto p-4 space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-ink">Money not counted yet</h1>
        <p className="text-sm text-muted">{start} – {end}. Tell Fynora what each payment was; it remembers the sender.</p>
      </div>
      {undo && (
        <div className="flex items-center justify-between bg-card border border-border rounded-xl2 px-4 py-2 text-sm">
          <span className="text-ink">Saved for {undo.label}.</span>
          <Button size="sm" variant="secondary" onClick={undoLast}>Undo</Button>
        </div>
      )}
      {error && <p className="text-danger text-sm">{error}</p>}
      {senders && senders.length === 0 && (
        <FinoraCard><p className="text-ink text-sm text-center py-6">Everything's sorted</p></FinoraCard>
      )}
      {senders?.map((s) => (
        <FinoraCard key={s.sampleTransactionId}>
          <button type="button" className="w-full text-left" onClick={() => { setOpen(open === s ? null : s); setPicked(null); setOneRow(false); }}>
            <div className="flex justify-between gap-2">
              <span className="text-ink text-sm font-medium truncate">{s.label}</span>
              <span className="text-ink text-sm">{fmt(s.total)}</span>
            </div>
            <p className="text-xs text-muted">
              {s.count} {s.count === 1 ? 'payment' : 'payments'} · latest {s.latestDate}{s.accountName ? ` · ${s.accountName}` : ''}
            </p>
          </button>
          {open === s && (
            <div className="mt-3 space-y-3">
              <InflowKindPicker
                kinds={kinds}
                selectedId={picked?.id ?? null}
                onPick={setPicked}
                busy={busy}
                onCreate={async (name, countsAsIncome) => {
                  const created = await inflowApi.createKind(name, countsAsIncome);
                  setKinds([...kinds, created]);
                  setPicked(created);
                }}
              />
              {picked && !oneRow && (
                <div className="flex flex-wrap gap-2">
                  {s.senderKnown && (
                    <Button size="sm" loading={busy} onClick={() => apply(s.sampleTransactionId, 'SENDER', s.label)}>
                      {`Every payment from ${s.label} (${s.count})`}
                    </Button>
                  )}
                  <Button size="sm" variant={s.senderKnown ? 'secondary' : 'primary'}
                    onClick={() => (s.rows.length === 1 ? apply(s.rows[0].id, 'ROW', s.label) : setOneRow(true))}>
                    Just this one
                  </Button>
                </div>
              )}
              {picked && oneRow && (
                <ul className="space-y-1">
                  {s.rows.map((r) => (
                    <li key={r.id}>
                      <button type="button" className="w-full flex justify-between text-sm text-ink hover:bg-bg rounded px-2 py-1"
                        onClick={() => apply(r.id, 'ROW', s.label)}>
                        <span>{r.date}</span><span>{fmt(r.amount)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </FinoraCard>
      ))}
    </div>
  );
}
```

Add the route to `App.tsx` next to `/app/reports`:

```tsx
          <Route path="/app/money-review" element={<Protected><MoneyReview /></Protected>} />
```

together with `import { MoneyReview } from './pages/MoneyReview';`. Follow `App.tsx`'s import style: if pages are `lazy()`-imported there, add it the same way.

Run the test. Expected: 2 passed.

- [ ] **Step 9: Dashboard banner link and Reports lines**

`Dashboard.tsx`: inside the unresolved banner, after the reason line, add:

```tsx
              <Link
                to={`/app/money-review?start=${rangeSummary.startDate}&end=${rangeSummary.endDate}`}
                className="text-xs text-primary font-medium mt-1 inline-block"
              >
                Review these payments
              </Link>
```

`Reports.tsx`: directly after the metric-card grid, add:

```tsx
          {report.incomeByKind && report.incomeByKind.length > 1 && (
            <FinoraCard>
              <SectionHeader title="Income by kind" size="sm" />
              <ul className="divide-y divide-border">
                {report.incomeByKind.map((line) => (
                  <li key={line.label} className="flex justify-between py-2 text-sm">
                    <span className="text-ink">{line.label}</span>
                    <span className="text-ink">{fmt(line.amount)}</span>
                  </li>
                ))}
              </ul>
            </FinoraCard>
          )}
          {report.unresolvedInflow != null && report.unresolvedInflow > 0 && (
            <Link to={`/app/money-review?start=${report.month}-01&end=${lastDayOf(report.month)}`} className="text-xs text-primary font-medium">
              Review money not counted yet
            </Link>
          )}
```

Add a local helper in `Reports.tsx`:

```ts
function lastDayOf(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return `${month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`;
}
```

Also import `Link` from `react-router-dom`, and `SectionHeader` if it isn't already imported.

Add one test each in `Dashboard.test.tsx` and `Reports.test.tsx`, following each file's existing unresolved-banner / caption test:
- the banner link's `href` is `/app/money-review?start=…&end=…`;
- with two `incomeByKind` lines, both labels render.

Watch each test fail before its change and pass after.

- [ ] **Step 10: Settings sections — failing test, then implement**

`InflowKindsSection.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InflowKindsSection } from './InflowKindsSection';
import { inflowApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  inflowApi: { kinds: vi.fn(), createKind: vi.fn(), updateKind: vi.fn(), deleteKind: vi.fn(), senderRules: vi.fn(), forgetSender: vi.fn() },
}));

const income = { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' as const };
const rent = { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null };

describe('InflowKindsSection', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.kinds).mockResolvedValue([income, rent]);
    vi.mocked(inflowApi.senderRules).mockResolvedValue([{ id: 'r1', label: 'ASHA VERMA', kind: income, rowCount: 3 }]);
  });

  it('lists kinds and remembered senders', async () => {
    render(<InflowKindsSection />);
    expect(await screen.findByText('Rent from tenant')).toBeInTheDocument();
    expect(screen.getByText('ASHA VERMA')).toBeInTheDocument();
  });

  it('built-ins have no delete button and no income toggle', async () => {
    render(<InflowKindsSection />);
    await screen.findByText('Rent from tenant');
    expect(screen.queryByRole('button', { name: 'Delete Income' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Delete Rent from tenant' })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'Income counts as income' })).toBeNull();
  });

  it('explains why a kind in use cannot be deleted', async () => {
    vi.mocked(inflowApi.deleteKind).mockRejectedValue({
      response: { data: { message: 'This kind is still used. Move those payments and senders to another kind first.', details: { rows: 2, senders: 1 } } },
    });
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete Rent from tenant' }));
    expect(await screen.findByText(/Used by 2 payments and 1 sender/)).toBeInTheDocument();
  });

  it('forgets a sender', async () => {
    vi.mocked(inflowApi.forgetSender).mockResolvedValue({} as never);
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('button', { name: 'Forget ASHA VERMA' }));
    await waitFor(() => expect(inflowApi.forgetSender).toHaveBeenCalledWith('r1'));
  });
});
```

Check the error payload shape. `GlobalExceptionHandler` decides where `details` lands in the JSON; read it with `grep -n "details" backend/src/main/java/com/finora/exception/GlobalExceptionHandler.java`, and set the test's `response.data` shape and the component's read path to match.

Implement `InflowKindsSection.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { inflowApi, type InflowKind, type SenderRule } from '../../api/endpoints';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';
import { InflowKindPicker } from '../../components/inflow/InflowKindPicker';

type ApiErr = { response?: { data?: { message?: string; details?: { rows?: number; senders?: number } } } };

/** Settings → Categorization: the user's money kinds and remembered senders (Plan 2). */
export function InflowKindsSection() {
  const [kinds, setKinds] = useState<InflowKind[] | null>(null);
  const [rules, setRules] = useState<SenderRule[] | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  function load() {
    inflowApi.kinds().then(setKinds).catch(() => setMessage("Couldn't load your kinds."));
    inflowApi.senderRules().then(setRules).catch(() => setMessage("Couldn't load remembered senders."));
  }
  useEffect(load, []);

  async function remove(k: InflowKind) {
    setMessage(null);
    try {
      await inflowApi.deleteKind(k.id);
      load();
    } catch (e: unknown) {
      const d = (e as ApiErr).response?.data;
      const rows = d?.details?.rows ?? 0, senders = d?.details?.senders ?? 0;
      setMessage(`${d?.message ?? 'Could not delete this kind.'} Used by ${rows} ${rows === 1 ? 'payment' : 'payments'} and ${senders} ${senders === 1 ? 'sender' : 'senders'}.`);
    }
  }

  async function saveName(k: InflowKind) {
    try {
      await inflowApi.updateKind(k.id, { name: draft });
      setRenaming(null);
      load();
    } catch (e: unknown) {
      setMessage((e as ApiErr).response?.data?.message ?? 'Could not rename this kind.');
    }
  }

  return (
    <>
      <FinoraCard>
        <SectionHeader title="Money kinds" size="sm" />
        <p className="text-xs text-muted mb-3">What money coming in can be. Kinds that count as income add to your income.</p>
        {message && <p className="text-danger text-xs mb-2">{message}</p>}
        <ul className="divide-y divide-border">
          {kinds?.map((k) => (
            <li key={k.id} className="flex items-center justify-between gap-2 py-2">
              {renaming === k.id ? (
                <input aria-label={`Rename ${k.name}`} value={draft} maxLength={60} onChange={(e) => setDraft(e.target.value)}
                  className="flex-1 border border-border rounded-lg px-2 py-1 text-sm bg-card text-ink" />
              ) : (
                <span className="text-sm text-ink">{k.name}</span>
              )}
              <div className="flex items-center gap-2">
                {k.builtIn ? (
                  <span className="text-xs text-muted">{k.countsAsIncome ? 'Income' : 'Not income'}</span>
                ) : (
                  <label className="text-xs text-muted flex items-center gap-1">
                    <input type="checkbox" aria-label={`${k.name} counts as income`} checked={k.countsAsIncome}
                      onChange={async (e) => { await inflowApi.updateKind(k.id, { countsAsIncome: e.target.checked }); load(); }} />
                    Income
                  </label>
                )}
                {renaming === k.id ? (
                  <Button size="sm" onClick={() => saveName(k)}>Save</Button>
                ) : (
                  <Button size="sm" variant="secondary" onClick={() => { setRenaming(k.id); setDraft(k.name); }}>Rename</Button>
                )}
                {!k.builtIn && (
                  <Button size="sm" variant="danger" aria-label={`Delete ${k.name}`} onClick={() => remove(k)}>Delete</Button>
                )}
              </div>
            </li>
          ))}
        </ul>
        {kinds && (
          <div className="mt-3">
            <InflowKindPicker kinds={[]} selectedId={null} onPick={() => undefined}
              onCreate={async (name, countsAsIncome) => { await inflowApi.createKind(name, countsAsIncome); load(); }} />
          </div>
        )}
      </FinoraCard>
      <FinoraCard>
        <SectionHeader title="Remembered senders" size="sm" />
        {rules && rules.length === 0 && <p className="text-xs text-muted">No senders remembered yet.</p>}
        <ul className="divide-y divide-border">
          {rules?.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-2 py-2">
              <div className="min-w-0">
                <p className="text-sm text-ink truncate">{r.label}</p>
                <p className="text-xs text-muted">{r.kind.name} · {r.rowCount} {r.rowCount === 1 ? 'payment' : 'payments'}</p>
              </div>
              <Button size="sm" variant="secondary" aria-label={`Forget ${r.label}`}
                onClick={async () => { await inflowApi.forgetSender(r.id); load(); }}>Forget</Button>
            </li>
          ))}
        </ul>
      </FinoraCard>
    </>
  );
}
```

Render `<InflowKindsSection />` at the end of `CategorizationPane`'s returned JSX, then add `inflowApi` to `CategorizationPane.test.tsx`'s mock factory (`kinds`/`senderRules` resolving `[]`) so its two existing tests still pass.

Run: `npx vitest run src/pages/settings 2>&1 | tail -15`
Expected: all pass.

- [ ] **Step 11: Full web checks**

Run from `frontend/`:
- `npx vitest run 2>&1 | tail -8`
- `npx -y node@22 node_modules/.bin/eslint . 2>&1 | tail -8`
- `npx -y node@22 node_modules/.bin/tsc -b --noEmit 2>&1 | tail -8`

Use the repo's own lint and typecheck script names from `frontend/package.json` if they differ.
Expected: all tests pass; lint and tsc clean.

- [ ] **Step 12: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add -A frontend/src
git commit -m "feat(web): review money not counted yet, counts-as choice, kinds settings"
```

---

### Task 9: Mobile

**Files:**
- Modify: `mobile/src/api/endpoints.ts`, `mobile/src/types/index.ts`
- Create: `mobile/src/components/InflowKindPicker.tsx` (+ test), `mobile/src/components/CountsAsSection.tsx` (+ test), `mobile/src/components/InflowKindsSettings.tsx` (+ test), `mobile/src/screens/MoneyReviewScreen.tsx` (+ test)
- Modify: `mobile/src/components/TransactionExplanationModal.tsx`, `mobile/src/screens/DashboardScreen.tsx`, `mobile/src/screens/ReportsScreen.tsx`, `mobile/src/screens/SettingsCategorizationScreen.tsx`, `mobile/src/navigation/AppTabs.tsx`, `mobile/src/navigation/types.ts`, `mobile/src/lib/invalidateFinancialData.ts`

**Interfaces:**
- Consumes: the Task 7 endpoints.
- Produces: the same `inflowApi` surface as the web; the `MoneyReview` route with params `{ start: string; end: string } | undefined`; the query keys `inflow-kinds`, `sender-inflow-rules`, `unresolved-inflows` and `counts-as` in `FINANCIAL_QUERY_KEYS`.

- [ ] **Step 1: Types and API**

- Add to `mobile/src/types/index.ts` the same `InflowBuiltIn`, `ChoiceScope`, `InflowKind`, `CountsAs`, `SenderRule`, `UnresolvedRow` and `UnresolvedSender` interfaces as in Task 8 Step 1.
- Add `incomeByKind?: { label: string; amount: number }[];` to the report type.
- Add to `mobile/src/api/endpoints.ts` the same `inflowApi` object as in Task 8 Step 1, importing the types from `../types`.

- [ ] **Step 2: Query keys**

In `lib/invalidateFinancialData.ts`, append to `FINANCIAL_QUERY_KEYS`:

```ts
  // Plan 2 inflow kinds. A choice or a kind change moves income, the unresolved list and every
  // row's "counts as" reading -- and another device's choice arrives through the transactions
  // section of the change stamp, which now includes kinds and sender rules.
  'inflow-kinds',
  'sender-inflow-rules',
  'unresolved-inflows',
  'counts-as',
```

Run `npm test -- lib/changeSync lib/invalidateFinancialData 2>&1 | tail -8`. Expected: pass. If a test lists the keys exactly, update it and note that in the ledger.

- [ ] **Step 3: Picker — failing test, then implement**

`InflowKindPicker.test.tsx`:

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { InflowKindPicker } from './InflowKindPicker';
import type { InflowKind } from '../types';

const kinds: InflowKind[] = [
  { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' },
  { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' },
];

describe('InflowKindPicker', () => {
  it('picks a kind', () => {
    const onPick = jest.fn();
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={onPick} onCreate={jest.fn()} />);
    fireEvent.press(screen.getByText('Family support'));
    expect(onPick).toHaveBeenCalledWith(kinds[1]);
  });

  it('creates a kind after the income question', async () => {
    const onCreate = jest.fn().mockResolvedValue(undefined);
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={jest.fn()} onCreate={onCreate} />);
    fireEvent.press(screen.getByText('+ New kind…'));
    fireEvent.changeText(screen.getByLabelText('Name'), 'Split with flatmate');
    fireEvent.press(screen.getByText("No, don't count it"));
    fireEvent.press(screen.getByText('Create'));
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith('Split with flatmate', false));
  });
});
```

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/mobile && npm test -- components/InflowKindPicker 2>&1 | tail -10` and expect FAIL. Then implement:

```tsx
import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button } from './Button';
import { toUserMessage } from '../lib/apiError';
import type { InflowKind } from '../types';
import { radius, spacing, useTheme } from '../theme';

/** The kinds a credit can be given, plus "+ New kind…" (Plan 2). Mirrors
 *  frontend/src/components/inflow/InflowKindPicker.tsx. */
export function InflowKindPicker({ kinds, selectedId, onPick, onCreate, busy }: {
  kinds: InflowKind[];
  selectedId: string | null;
  onPick: (kind: InflowKind) => void;
  onCreate: (name: string, countsAsIncome: boolean) => Promise<void>;
  busy?: boolean;
}) {
  const c = useTheme();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [countsAsIncome, setCountsAsIncome] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!name.trim() || countsAsIncome === null) return;
    setSaving(true);
    setError(null);
    try {
      await onCreate(name.trim(), countsAsIncome);
      setCreating(false);
      setName('');
      setCountsAsIncome(null);
    } catch (e) {
      setError(toUserMessage(e, 'Could not create this kind.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.chips}>
        {kinds.map((k) => (
          <Pressable
            key={k.id}
            disabled={busy}
            accessibilityRole="button"
            accessibilityState={{ selected: selectedId === k.id }}
            onPress={() => onPick(k)}
            style={[styles.chip, { borderColor: selectedId === k.id ? c.primary : c.border }]}
          >
            <Text style={{ color: c.ink, fontSize: 13 }}>{k.name}</Text>
            <Text style={{ color: c.muted, fontSize: 11 }}>{k.countsAsIncome ? 'income' : 'not income'}</Text>
          </Pressable>
        ))}
        {!creating ? (
          <Pressable accessibilityRole="button" onPress={() => setCreating(true)} style={[styles.chip, styles.dashed, { borderColor: c.border }]}>
            <Text style={{ color: c.muted, fontSize: 13 }}>+ New kind…</Text>
          </Pressable>
        ) : null}
      </View>
      {creating ? (
        <View style={[styles.form, { borderColor: c.border }]}>
          <TextInput
            accessibilityLabel="Name"
            placeholder="Name"
            placeholderTextColor={c.muted}
            value={name}
            maxLength={60}
            onChangeText={setName}
            style={[styles.input, { borderColor: c.border, color: c.ink }]}
          />
          <Text style={{ color: c.muted, fontSize: 12 }}>Count this as income?</Text>
          {[true, false].map((v) => (
            <Pressable key={String(v)} accessibilityRole="radio" accessibilityState={{ checked: countsAsIncome === v }}
              onPress={() => setCountsAsIncome(v)} style={styles.radio}>
              <Text style={{ color: c.ink, fontSize: 13 }}>{countsAsIncome === v ? '● ' : '○ '}{v ? 'Yes, count it' : "No, don't count it"}</Text>
            </Pressable>
          ))}
          {error ? <Text style={{ color: c.danger, fontSize: 12 }}>{error}</Text> : null}
          <Button label="Create" onPress={submit} loading={saving} disabled={!name.trim() || countsAsIncome === null} />
          <Button label="Cancel" variant="secondary" onPress={() => { setCreating(false); setError(null); }} />
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: { borderWidth: 1, borderRadius: radius.lg, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs },
  dashed: { borderStyle: 'dashed' },
  form: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.sm, gap: spacing.xs },
  input: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, fontSize: 14 },
  radio: { paddingVertical: 4 },
});
```

Confirm that the theme exposes `spacing.xs`, `radius.md`, `c.primary`, `c.danger` and `c.muted` (`grep -n "xs\|md\|primary\|danger\|muted" mobile/src/theme/*.ts | head`), and that `Button` accepts `variant="secondary"`. Substitute the existing names if they differ.

Run the test. Expected: 2 passed.

- [ ] **Step 4: CountsAsSection — failing test, then implement**

`CountsAsSection.test.tsx`: the same four scenarios as the web test in Task 8 Step 4, written with `@testing-library/react-native`, `jest.mock('../api/endpoints', () => ({ inflowApi: {...jest.fn()} }))` and a `QueryClientProvider` wrapper (same pattern as `MarkTransferModal.test.tsx`). Button texts are identical to the web ones.

Implement `CountsAsSection.tsx` with `useQuery(['counts-as', transactionId], () => inflowApi.countsAs(transactionId))` and `useQuery(['inflow-kinds'], inflowApi.kinds, { enabled: editing })`. Its markup follows the web component above, using RN `Text`/`View`, the `Button` component and `InflowKindPicker`. After a successful `setChoice` or `clearChoice`, call `invalidateFinancialData(queryClient)` so the dashboard, reports and review list refresh.

Insert it in `TransactionExplanationModal.tsx` inside `styles.body`, before the category section:

```tsx
              {showCountsAs ? (
                <View style={[styles.section, styles.sectionDivider, { borderBottomColor: c.border }]}>
                  <CountsAsSection transactionId={transactionId} />
                </View>
              ) : null}
```

Add the `showCountsAs: boolean` prop. Every caller passes `transaction.type !== 'EXPENSE'`; find them with `grep -rn "TransactionExplanationModal" mobile/src --include=*.tsx`.

Run: `npm test -- components/CountsAsSection components/TransactionExplanationModal 2>&1 | tail -10`, watching the new tests fail before the change and pass after.

- [ ] **Step 5: MoneyReviewScreen — failing test, then implement**

`MoneyReviewScreen.test.tsx` covers the same two scenarios as the web `MoneyReview.test.tsx`:
- it lists senders for the route's `start`/`end`;
- setting "Every payment from ASHA VERMA (2)" calls `setChoice('t1','k2','SENDER')`, the list then shows "Everything's sorted", and Undo calls `clearChoice('t1','SENDER')`.

Render the screen the way the existing `CategoryReviewScreen.test.tsx` does (read its render helper and navigation mock first).

Implement `MoneyReviewScreen.tsx` following the web page's structure:
- a `ScrollView` of `Card`s; tapping a sender expands `InflowKindPicker` and the two action buttons;
- after an apply, a `Toast` with an "Undo" `Button`;
- data from `useQuery(['unresolved-inflows', start, end], ...)`;
- `invalidateFinancialData(queryClient)` after each apply or undo.

Route params: `route.params?.start` / `route.params?.end`, defaulting to the current calendar month.

Register the screen:
- `navigation/types.ts`, in `MoreStackParamList`: `MoneyReview: { start: string; end: string } | undefined;`
- `navigation/AppTabs.tsx`: `<MoreStack.Screen name="MoneyReview" component={MoneyReviewScreen} options={{ headerShown: false }} />`, next to `CategoryReview`.
- `navigation/AppTabs.test.tsx`: add `jest.mock('../screens/MoneyReviewScreen', () => ({ MoneyReviewScreen: () => null }));`

Run: `npm test -- screens/MoneyReviewScreen navigation/AppTabs 2>&1 | tail -10`. Expected: pass.

- [ ] **Step 6: Dashboard banner, Reports and Settings**

`DashboardScreen.tsx`: wrap the unresolved banner `Card` in a `Pressable` with `accessibilityRole="button"` and `accessibilityLabel="Review money not counted yet"`. Its `onPress`:

```tsx
() => {
  trackNavigation('money-review', 'contextual');
  const [y, m] = (summary.reportingMonth ?? '').split('-').map(Number);
  const params = y && m
    ? { start: `${summary.reportingMonth}-01`, end: `${summary.reportingMonth}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}` }
    : undefined;
  navigation.navigate('More', { screen: 'MoneyReview', params });
}
```

`ReportsScreen.tsx`: after the totals row, render an "Income by kind" `Card` when `report.incomeByKind?.length > 1`, with one row per line (label left, `fmtCurrency(amount)` right). Also add a pressable "Review money not counted yet" line when `report.unresolvedInflow > 0`, navigating the same way with the report's month.

`SettingsCategorizationScreen.tsx`: render `<InflowKindsSettings />` below the existing content.

`InflowKindsSettings.tsx` mirrors the web `InflowKindsSection`:
- queries `['inflow-kinds']` and `['sender-inflow-rules']`;
- rename through an inline `TextInput`, and a `Switch` for custom kinds' income flag;
- delete is confirmed through `AppAlert.alert` (import from `../lib/appAlert`), and the in-use refusal is shown as the same "Used by N payments and M senders" text;
- "Forget" on each remembered sender.

Tests:
- `InflowKindsSettings.test.tsx`: the same four scenarios as the web test.
- One test each in the existing `DashboardScreen.test.tsx` (the banner press navigates to `MoneyReview` with the month bounds) and `ReportsScreen.test.tsx` (two income lines render).

Watch each test fail first.

- [ ] **Step 7: Full mobile checks**

Run from `mobile/`:
- `npm test 2>&1 | tail -8`
- `npx -y node@22 node_modules/.bin/tsc --noEmit 2>&1 | tail -8`
- `npx -y node@22 node_modules/.bin/eslint . 2>&1 | tail -8`

Use the script names in `mobile/package.json` if they differ.
Expected: all tests pass; tsc and lint clean.

- [ ] **Step 8: Commit**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git add -A mobile/src
git commit -m "feat(mobile): review money not counted yet, counts-as choice, kinds settings"
```

---

### Task 10: Measurement and full verification

**Files:** none committed. The probe lives in the session scratchpad only.

- [ ] **Step 1: Corpus probe (throwaway)**

In the scratchpad, write a probe modelled on Plan 1's `FlowClassCorpusProbe`. It stages every PDF under `~/Downloads/Bank statement/` with the real parser and runs the real `ReconciliationService`, per owner, as in Plan 3. Then:

1. With `InflowChoices.NONE`, record each unresolved row: owner, sender key, amount and reason.
2. Build an `InflowChoices` giving every unresolved sender key the INCOME built-in, and each keyless unresolved row a row choice.
3. Re-run `FlowTotals` over the same rows.

Report, per owner:
- unresolved count before and after (after must be 0);
- the income delta, which must equal the unresolved total before;
- the number of rows whose flow class changed that were not in the unresolved set, which must be 0.

List every changed row: date, amount, sender label, before and after classes. Read every one. Any non-zero "changed outside the set" count blocks the PR until explained.

- [ ] **Step 2: Backend verify**

Run: `cd /Users/sid/Downloads/finora-inflow-kinds/backend && ./mvnw verify > /private/tmp/claude-501/-Users-sid-Downloads-finora/6876f904-5aaa-48f4-b7a4-4d622e673a15/scratchpad/verify-plan2.log 2>&1; echo exit=$?`

Then count failures and errors from the surefire and failsafe XML, not from a grep of the log's exit code:
`grep -h "<testsuite" target/surefire-reports/*.xml target/failsafe-reports/*.xml | sed -E 's/.*tests="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*/\1 \2 \3/' | awk '{t+=$1;f+=$2;e+=$3} END {print t, f, e}'`
Expected: `exit=0`, failures 0, errors 0.

- [ ] **Step 3: Web and mobile, full suites again**

Re-run Task 8 Step 11 and Task 9 Step 7.
Expected: green.

- [ ] **Step 4: Merge state before push**

```bash
cd /Users/sid/Downloads/finora-inflow-kinds
git fetch origin
git log --oneline origin/main..HEAD
git ls-tree --name-only origin/main backend/src/main/resources/db/migration/ | grep -c "V232__"
```

Expected: this branch's commits are listed, and the count is 0 (no one else took V232). If it is 1, renumber this branch's migration to the next free number in a new commit before pushing.

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin feature/inflow-kinds
gh pr create --title "feat(transactions): tell Fynora what money coming in was (Plan 2)" --body-file <scratchpad>/pr-body-plan2.md
```

The PR body covers:
- the summary;
- the measured corpus result from Step 1;
- verify, web and mobile counts;
- the rulings from the ledger.
