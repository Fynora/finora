# Quick sort Part 1: re-check waiting rows when the rules change — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A transaction still waiting for review gets today's category rules applied to it, without the user re-importing, and without ever touching a row the user chose.

**Architecture:** A version number for the suggestion rules (`CategorizationService.SUGGESTION_VERSION`) and a matching column on each transaction. A scheduled sweep, shaped like `CounterpartyBackfillSweepService`, finds waiting rows stamped below the current version, re-runs the read-only suggestion, and writes the new category with a guarded bulk update. A row that still lands on "Other" or "Personal Transfer" is only stamped.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA (JPQL bulk updates), Flyway, PostgreSQL, JUnit 5, Mockito, Testcontainers (`AbstractIntegrationTest`).

**Spec:** `docs/superpowers/specs/2026-10-03-quick-sort-review-design.md` (Part 1). Parts 2-4 get their own plans.

## Global Constraints

- Never change a row with `category_manually_set = true`. Never change a row with `needs_category_review = false`.
- Only rows whose `decision_source` is `MERCHANT_DEFAULT` or `STRUCTURAL_P2P` are re-checked: those are the two unresolved guesses (`CategorizationService.isUnconfirmedGuess`). Rule, learned, corpus, AI, printed-card and file categories are left alone.
- The suggestion is computed with the same read-only waterfall staging uses (`suggestReadOnly(..., direction)`), with `accountType = null`, exactly as `TransactionNormalizer` calls it. The sweep never calls the AI model.
- A bulk UPDATE on `Transaction` that changes what a user sees must bump `version` (`ChangeStampBulkWriteGuardTest`); one that only stamps a derived column must not, and is listed in that test's `EXEMPT` map with its reason.
- Flyway: confirm the next free version on `origin/main` and in open PRs before writing the migration (V251 is the latest on `origin/main` as of 2026-10-03; no open PR adds a migration). Never renumber an existing migration.
- Commit messages: no `Co-Authored-By` or any AI trailer (repo rule, `CLAUDE.md`). Commit subject lower-case after the scope (commitlint `subject-case`).
- Test fixtures with invented narrations end their line with `// synthetic-ok`. Code comments describe real evidence; they never quote a real statement's values.
- What else follows a category change, checked on `main` 2026-10-03: `TransactionService.updateCategory` saves and calls `ReconciliationService.reconcileIfInvestmentExclusionMayChange`; the sweep re-runs `reconcileForUser` for each changed user instead (wider, and what `CounterpartyBackfillSweepService` already does). Flow class is not stored on `transactions` (no column; `FlowClassifier` runs at read time), so nothing else needs re-running. Side-effect rules (`applySideEffectRules`) already ran at import and read the narration, not the category, so they are not re-run.
- Backend runs: `./mvnw verify` for the full module (unit + IT); never two `mvnw` processes in one worktree at once.

## Review Focus

1. **The user edits a row while the sweep holds it in a batch.** The user's choice must win: the guarded UPDATE matches 0 rows once `category_manually_set` is true, and the row keeps the user's category. Test in Task 1 (repository IT) and Task 2 (sweep IT).
2. **A suggestion that throws for one row.** The other rows in the batch are still written and stamped; the failing row is left unstamped and logged without its narration. Test in Task 2 (unit test).
3. **A row the new rules still cannot place.** It is stamped (so it is not re-checked every 5 minutes forever) but its `version` is not bumped (the phone must not be told something changed). Test in Task 1 and Task 2.
4. **A suggested category the user does not have** (an older account without "Interest & Cashback"). It is created through `resolveOrCreateCategory`, the same as an import confirm. Test in Task 2 (the IT user starts with no categories).
5. **A shared-corpus or AI answer reached through the read-only cache.** The row takes the category but stays waiting, as at import (`isUnconfirmedGuess` is true for those sources). Test in Task 2 (unit test).

---

### Task 1: Column, entity field and guarded repository writes

**Files:**
- Create: `backend/src/main/resources/db/migration/V252__transactions_suggestion_version.sql` (number confirmed in Step 1)
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java` (new field next to `counterpartyClassifierVersion`, ~line 119; getter/setter next to its accessors, ~line 385)
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (new constant next to `P2P_CATEGORY`, ~line 81)
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java` (new projection + 3 queries after `applyCounterpartyTyping`, ~line 165)
- Modify: `backend/src/test/java/com/finora/service/ChangeStampBulkWriteGuardTest.java` (`EXEMPT` map)
- Test: `backend/src/test/java/com/finora/repository/CategorySuggestionRepositoryIT.java`

**Interfaces:**
- Produces:
  - `CategorizationService.SUGGESTION_VERSION` — `public static final short`, value `1`.
  - `Transaction#getSuggestionVersion(): short`, `Transaction#setSuggestionVersion(short)`.
  - `TransactionRepository.CategorySuggestionRow` — projection with `UUID getId()`, `UUID getUserId()`, `String getDescription()`, `BigDecimal getAmount()`, `Transaction.Type getTxnType()`.
  - `List<CategorySuggestionRow> findWaitingRowsBelowSuggestionVersion(short version, Pageable pageable)`
  - `int applyCategorySuggestion(UUID id, UUID categoryId, Transaction.DecisionSource decisionSource, UUID ruleId, Integer confidence, boolean needsReview, short version)` — returns 1 when written, 0 when the row no longer qualifies.
  - `int stampSuggestionVersion(UUID id, short version)` — returns 1 when stamped, 0 otherwise.

- [ ] **Step 1: Confirm the migration number is free**

Run:
```bash
git fetch origin
git ls-tree --name-only origin/main backend/src/main/resources/db/migration/ | sort -V | tail -2
gh pr list --state open --json number,files --limit 100 -q '.[] | select(any(.files[]; .path|test("db/migration/V"))) | .number'
```
Expected: the last file is `V251__...` and no open PR lists a migration. If either shows V252 taken, use the next free number in every step below.

- [ ] **Step 2: Write the failing repository IT**

Create `backend/src/test/java/com/finora/repository/CategorySuggestionRepositoryIT.java`:

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JPQL behind the suggestion re-check, against real Postgres: which rows it finds, and that
 * its guarded writes refuse a row the user has since chosen. Written to survive other classes'
 * rows (the discovery query is table-wide): every assertion is on an id seeded here.
 */
class CategorySuggestionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID userId;
    private UUID accountId;
    private UUID otherCategoryId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("suggestion-version-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();

        Category other = new Category();
        other.setUserId(userId);
        other.setName("Other");
        otherCategoryId = categoryRepository.save(other).getId();
    }

    @Test
    void findsOnlyWaitingUnchosenGuessesBelowTheVersion() {
        UUID waitingDefault = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        UUID waitingPerson = seed(Transaction.DecisionSource.STRUCTURAL_P2P, true, false, (short) 0);
        UUID chosen = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, true, (short) 0);
        UUID notWaiting = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, false, false, (short) 0);
        UUID ruleMatched = seed(Transaction.DecisionSource.KEYWORD_MATCH, true, false, (short) 0);
        UUID alreadyStamped = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false,
                CategorizationService.SUGGESTION_VERSION);

        List<UUID> found = transactionRepository
                .findWaitingRowsBelowSuggestionVersion(CategorizationService.SUGGESTION_VERSION, PageRequest.of(0, 10_000))
                .stream().map(TransactionRepository.CategorySuggestionRow::getId).toList();

        assertThat(found).contains(waitingDefault, waitingPerson);
        assertThat(found).doesNotContain(chosen, notWaiting, ruleMatched, alreadyStamped);
    }

    @Test
    void applyWritesTheAnswerAndBumpsTheVersion() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        long versionBefore = reload(id).getVersion();
        UUID newCategory = category("Interest & Cashback");

        int written = inTx(() -> transactionRepository.applyCategorySuggestion(id, newCategory,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION));

        Transaction t = reload(id);
        assertThat(written).isEqualTo(1);
        assertThat(t.getCategoryId()).isEqualTo(newCategory);
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(t.getDecisionConfidence()).isEqualTo(70);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        // The phone learns of a change from version (ChangeStampService).
        assertThat(t.getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void applyRefusesARowTheUserHasSinceChosen() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, true, (short) 0);

        int written = inTx(() -> transactionRepository.applyCategorySuggestion(id, category("Dining"),
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION));

        assertThat(written).isZero();
        assertThat(reload(id).getCategoryId()).isEqualTo(otherCategoryId);
    }

    @Test
    void stampMovesOnlyTheSuggestionVersion() {
        UUID id = seed(Transaction.DecisionSource.MERCHANT_DEFAULT, true, false, (short) 0);
        long versionBefore = reload(id).getVersion();

        int stamped = inTx(() -> transactionRepository.stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION));

        Transaction t = reload(id);
        assertThat(stamped).isEqualTo(1);
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        assertThat(t.getCategoryId()).isEqualTo(otherCategoryId);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.getVersion()).isEqualTo(versionBefore);
    }

    private UUID seed(Transaction.DecisionSource source, boolean waiting, boolean chosen, short suggestionVersion) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(otherCategoryId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(120));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("UPI/REF91/UPI"); // synthetic-ok
        t.setDecisionSource(source);
        t.setNeedsCategoryReview(waiting);
        t.setCategoryManuallySet(chosen);
        t.setSuggestionVersion(suggestionVersion);
        return transactionRepository.save(t).getId();
    }

    private UUID category(String name) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        return categoryRepository.save(c).getId();
    }

    private int inTx(java.util.function.Supplier<Integer> write) {
        return transactionTemplate.execute(status -> write.get());
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }
}
```

Before running, check the setter/getter names this test uses against the entities: `Category#setUserId`, `Category#setName`, `Transaction#isNeedsCategoryReview`, `Transaction#isCategoryManuallySet`, `Transaction#getDecisionConfidence`, `BaseEntity#getVersion` (`grep -n "public .*NeedsCategoryReview\|public .*CategoryManuallySet" backend/src/main/java/com/finora/entity/Transaction.java`). Fix the test to the real names; do not rename entity methods.

- [ ] **Step 3: Run it to verify it fails to compile**

Run: `cd backend && ./mvnw -q test-compile`
Expected: compilation errors for `SUGGESTION_VERSION`, `setSuggestionVersion`, `findWaitingRowsBelowSuggestionVersion`, `applyCategorySuggestion`, `stampSuggestionVersion`.

- [ ] **Step 4: Add the migration**

Create `backend/src/main/resources/db/migration/V252__transactions_suggestion_version.sql`:

```sql
-- Which revision of the category-suggestion rules last examined this row
-- (CategorizationService.SUGGESTION_VERSION). Existing rows start at 0, so every row still waiting
-- for review is re-checked once by CategorySuggestionSweepService.
ALTER TABLE transactions
    ADD COLUMN suggestion_version SMALLINT NOT NULL DEFAULT 0;

-- Partial: the sweep only ever looks at rows still waiting and not chosen by the user, a small
-- slice of the table, so a drained sweep costs an empty index probe.
CREATE INDEX idx_transactions_waiting_suggestion_version
    ON transactions (suggestion_version)
    WHERE needs_category_review AND NOT category_manually_set;
```

- [ ] **Step 5: Add the entity field**

In `Transaction.java`, after the `counterpartyClassifierVersion` field:

```java
    /**
     * Which revision of the category-suggestion rules last examined this row
     * ({@link com.finora.service.CategorizationService#SUGGESTION_VERSION}). 0 for a row never
     * re-checked, including every new row: the sweep re-checks a waiting row once even when it was
     * just imported, which costs one read-only suggestion and closes the window where a row staged
     * under older rules is confirmed after a deploy. Written by a bulk update only.
     */
    @Column(name = "suggestion_version", nullable = false)
    private short suggestionVersion = 0;
```

and after `setCounterpartyClassifierVersion`:

```java
    public short getSuggestionVersion() { return suggestionVersion; }
    public void setSuggestionVersion(short suggestionVersion) { this.suggestionVersion = suggestionVersion; }
```

- [ ] **Step 6: Add the version constant**

In `CategorizationService.java`, after `P2P_CATEGORY`:

```java
    /**
     * Revision of the suggestion rules: the keyword table (CategoryRules), ShopTradeCategory,
     * BankActivityCategory, and the order of this class's waterfall. Raise it with any change to
     * those that can move a row off "Other" or "Personal Transfer"; CategorySuggestionSweepService
     * then re-checks every row still waiting for review. Raising it when nothing changed only costs
     * one read-only pass over the waiting rows.
     */
    public static final short SUGGESTION_VERSION = 1;
```

- [ ] **Step 7: Add the projection and queries**

In `TransactionRepository.java`, after `applyCounterpartyTyping`:

```java
    /** What the suggestion re-check needs of a row: the waterfall's inputs and whose row it is. */
    interface CategorySuggestionRow {
        UUID getId();
        UUID getUserId();
        String getDescription();
        java.math.BigDecimal getAmount();
        Transaction.Type getTxnType();
    }

    /**
     * Rows still waiting for review whose category is one of the two unresolved guesses ("Other",
     * or the structural person guess) and which the current suggestion rules have not examined.
     * A row the user chose is never a candidate; neither is one a rule, learning, the corpus, the
     * AI cache, a card's printed category or a file decided.
     */
    @Query("""
            SELECT t.id AS id, t.userId AS userId, t.description AS description, t.amount AS amount,
                   t.txnType AS txnType
            FROM Transaction t
            WHERE t.needsCategoryReview = true
              AND t.categoryManuallySet = false
              AND t.decisionSource IN (com.finora.entity.Transaction.DecisionSource.MERCHANT_DEFAULT,
                                       com.finora.entity.Transaction.DecisionSource.STRUCTURAL_P2P)
              AND t.suggestionVersion < :version
            """)
    List<CategorySuggestionRow> findWaitingRowsBelowSuggestionVersion(@Param("version") short version,
                                                                      Pageable pageable);

    /**
     * Writes a re-checked row's new category. Guarded on the discovery predicate, so a row the user
     * chose, or one answered elsewhere, between discovery and this write is left alone (returns 0).
     * Bumps {@code version}: the category is something the user sees, so another device must learn
     * of it (ChangeStampService).
     */
    @Modifying
    @Query("""
            UPDATE Transaction t
            SET t.categoryId = :categoryId,
                t.decisionSource = :decisionSource,
                t.decisionRuleId = :ruleId,
                t.decisionConfidence = :confidence,
                t.needsCategoryReview = :needsReview,
                t.suggestionVersion = :version,
                t.version = t.version + 1
            WHERE t.id = :id
              AND t.needsCategoryReview = true
              AND t.categoryManuallySet = false
              AND t.decisionSource IN (com.finora.entity.Transaction.DecisionSource.MERCHANT_DEFAULT,
                                       com.finora.entity.Transaction.DecisionSource.STRUCTURAL_P2P)
              AND t.suggestionVersion < :version
            """)
    int applyCategorySuggestion(@Param("id") UUID id,
                                @Param("categoryId") UUID categoryId,
                                @Param("decisionSource") Transaction.DecisionSource decisionSource,
                                @Param("ruleId") UUID ruleId,
                                @Param("confidence") Integer confidence,
                                @Param("needsReview") boolean needsReview,
                                @Param("version") short version);

    /** Records that the current rules examined a row and found nothing better. Nothing the user sees
     *  changes, so {@code version} is left alone -- see ChangeStampBulkWriteGuardTest's exemption. */
    @Modifying
    @Query("""
            UPDATE Transaction t
            SET t.suggestionVersion = :version
            WHERE t.id = :id
              AND t.suggestionVersion < :version
            """)
    int stampSuggestionVersion(@Param("id") UUID id, @Param("version") short version);
```

- [ ] **Step 8: Exempt the stamp in the change-stamp guard**

In `ChangeStampBulkWriteGuardTest.java`, replace the `EXEMPT` map:

```java
    private static final Map<String, String> EXEMPT = Map.of(
            "applyCounterpartyTyping",
            "A backfill of a derived column, not something the user did. It must not look like an edit "
                    + "to another device, and bumping the version would make it cause or lose optimistic-lock "
                    + "races against a person genuinely editing the same row (see its own doc comment).",
            "stampSuggestionVersion",
            "Records that the suggestion rules examined a row and changed nothing the user sees. Bumping "
                    + "the version would tell every device a row changed when it did not.");
```

- [ ] **Step 9: Run the new IT and the guard**

Run: `cd backend && ./mvnw -q verify -Dtest=ChangeStampBulkWriteGuardTest,CategorySuggestionRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=CategorySuggestionRepositoryIT`
Expected: both pass. If the IT is reported as not run, use the form that executed ITs before in this repo: `./mvnw verify -Dtest=CategorySuggestionRepositoryIT` and check the report under `backend/target/failsafe-reports` or `surefire-reports` for 4 tests run, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/db/migration/V252__transactions_suggestion_version.sql \
  backend/src/main/java/com/finora/entity/Transaction.java \
  backend/src/main/java/com/finora/service/CategorizationService.java \
  backend/src/main/java/com/finora/repository/TransactionRepository.java \
  backend/src/test/java/com/finora/service/ChangeStampBulkWriteGuardTest.java \
  backend/src/test/java/com/finora/repository/CategorySuggestionRepositoryIT.java
git commit -m "feat(transactions): record which suggestion rules last examined a waiting row"
```

---

### Task 2: The re-check sweep

**Files:**
- Create: `backend/src/main/java/com/finora/service/CategorySuggestionSweepService.java`
- Modify: `backend/src/main/resources/application.yml` (new block after `counterparty-backfill`, ~line 445)
- Modify: `backend/src/main/resources/application-test.yml` (new block after `counterparty-backfill`, ~line 88)
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java`, `backend/src/main/java/com/finora/util/ShopTradeCategory.java`, `backend/src/main/java/com/finora/util/BankActivityCategory.java` (one comment line each, near the word lists)
- Test: `backend/src/test/java/com/finora/service/CategorySuggestionSweepServiceTest.java`
- Test: `backend/src/test/java/com/finora/service/CategorySuggestionSweepIT.java`

**Interfaces:**
- Consumes (Task 1): `SUGGESTION_VERSION`, `CategorySuggestionRow`, `findWaitingRowsBelowSuggestionVersion`, `applyCategorySuggestion`, `stampSuggestionVersion`.
- Consumes (existing): `CategorizationService.ruleSetFor(UUID): List<CategoryRule>`; `CategorizationService.suggestReadOnly(List<CategoryRule>, UUID, String, BigDecimal, String, MerchantIndex, Transaction.Type): Suggestion`; `CategorizationService.resolveOrCreateCategory(UUID, String): Category`; `CategorizationService.needsCategoryReview(UUID, boolean, Integer): boolean`; `CategorizationService.isUnconfirmedGuess(String, String): boolean` (static); `CategorizationService.recordRuleMatch(UUID)`; `ReconciliationService.reconcileForUser(UUID)`.
- Produces: `CategorySuggestionSweepService.sweep(): Result` where `record Result(int changed, int stamped, int skipped, int failed, boolean drained)`.

- [ ] **Step 1: Write the failing unit test**

Create `backend/src/test/java/com/finora/service/CategorySuggestionSweepServiceTest.java`:

```java
package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CategorySuggestionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategorySuggestionSweepServiceTest {

    private TransactionRepository transactionRepository;
    private CategorizationService categorizationService;
    private ReconciliationService reconciliationService;
    private CategorySuggestionSweepService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categorizationService = mock(CategorizationService.class);
        reconciliationService = mock(ReconciliationService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        // executeWithoutResult is a default method a Mockito mock does not fall through to -- the
        // established fix here, see CounterpartyBackfillSweepServiceTest.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new CategorySuggestionSweepService(transactionRepository, categorizationService,
                reconciliationService, transactionTemplate);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        when(categorizationService.ruleSetFor(userId)).thenReturn(List.of());
    }

    @Test
    void aRowTheRulesNowPlaceTakesTheCategoryAndLeavesTheQueue() {
        UUID id = UUID.randomUUID();
        given(row(id, "1234567890:Int.Pd:01-03-2026 to 31-05-2026", Transaction.Type.INCOME)); // synthetic-ok
        suggest(new CategorizationService.Suggestion("Interest & Cashback", "rule", null,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70));
        UUID categoryId = category("Interest & Cashback");
        when(categorizationService.needsCategoryReview(userId, false, 70)).thenReturn(false);
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository).applyCategorySuggestion(id, categoryId,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70, false, CategorizationService.SUGGESTION_VERSION);
        verify(reconciliationService).reconcileForUser(userId);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.drained()).isTrue();
    }

    @Test
    void aRowTheRulesStillCannotPlaceIsOnlyStamped() {
        UUID id = UUID.randomUUID();
        given(row(id, "UPI/REF92/UPI", Transaction.Type.EXPENSE)); // synthetic-ok
        suggest(new CategorizationService.Suggestion("Other", "default", null,
                Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20));
        when(transactionRepository.stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION)).thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository).stampSuggestionVersion(id, CategorizationService.SUGGESTION_VERSION);
        verify(transactionRepository, never()).applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort());
        verify(categorizationService, never()).resolveOrCreateCategory(any(), any());
        verify(reconciliationService, never()).reconcileForUser(any());
        assertThat(result.stamped()).isEqualTo(1);
    }

    @Test
    void aStillUnconfirmedAnswerTakesTheCategoryButKeepsWaiting() {
        UUID id = UUID.randomUUID();
        given(row(id, "UPI/SAMPLE STORE/REF93", Transaction.Type.EXPENSE)); // synthetic-ok
        suggest(new CategorizationService.Suggestion("Groceries", CategorizationService.SHARED_CORPUS_SOURCE, null,
                Transaction.DecisionSource.SHARED_CORPUS, null, 60));
        UUID categoryId = category("Groceries");
        when(categorizationService.needsCategoryReview(userId, true, 60)).thenReturn(true);
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(1);

        service.sweep();

        verify(transactionRepository).applyCategorySuggestion(id, categoryId,
                Transaction.DecisionSource.SHARED_CORPUS, null, 60, true, CategorizationService.SUGGESTION_VERSION);
    }

    @Test
    void oneRowThrowingLeavesItUnstampedAndTheRestWritten() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        given(row(bad, "UPI/REF94/UPI", Transaction.Type.EXPENSE), // synthetic-ok
              row(good, "UPI/REF95/UPI", Transaction.Type.EXPENSE)); // synthetic-ok
        when(categorizationService.suggestReadOnly(any(), eq(userId), eq("UPI/REF94/UPI"), any(), isNull(), isNull(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(categorizationService.suggestReadOnly(any(), eq(userId), eq("UPI/REF95/UPI"), any(), isNull(), isNull(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null,
                        Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20));
        when(transactionRepository.stampSuggestionVersion(good, CategorizationService.SUGGESTION_VERSION)).thenReturn(1);

        CategorySuggestionSweepService.Result result = service.sweep();

        verify(transactionRepository, never()).stampSuggestionVersion(eq(bad), anyShort());
        verify(transactionRepository).stampSuggestionVersion(good, CategorizationService.SUGGESTION_VERSION);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.drained()).isFalse();
    }

    @Test
    void aRowAnsweredBetweenDiscoveryAndWriteIsCountedSkipped() {
        UUID id = UUID.randomUUID();
        given(row(id, "1234567890:Int.Pd:01-03-2026 to 31-05-2026", Transaction.Type.INCOME)); // synthetic-ok
        suggest(new CategorizationService.Suggestion("Interest & Cashback", "rule", null,
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70));
        category("Interest & Cashback");
        when(transactionRepository.applyCategorySuggestion(any(), any(), any(), any(), any(), anyBoolean(), anyShort()))
                .thenReturn(0);

        CategorySuggestionSweepService.Result result = service.sweep();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.changed()).isZero();
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    private void given(CategorySuggestionRow... rows) {
        when(transactionRepository.findWaitingRowsBelowSuggestionVersion(eq(CategorizationService.SUGGESTION_VERSION), any()))
                .thenReturn(List.of(rows));
    }

    private void suggest(CategorizationService.Suggestion suggestion) {
        when(categorizationService.suggestReadOnly(any(), eq(userId), any(), any(), isNull(), isNull(), any()))
                .thenReturn(suggestion);
    }

    private UUID category(String name) {
        Category c = mock(Category.class);
        UUID id = UUID.randomUUID();
        when(c.getId()).thenReturn(id);
        when(categorizationService.resolveOrCreateCategory(userId, name)).thenReturn(c);
        return id;
    }

    private CategorySuggestionRow row(UUID id, String description, Transaction.Type type) {
        CategorySuggestionRow r = mock(CategorySuggestionRow.class);
        when(r.getId()).thenReturn(id);
        when(r.getUserId()).thenReturn(userId);
        when(r.getDescription()).thenReturn(description);
        when(r.getAmount()).thenReturn(BigDecimal.valueOf(250));
        when(r.getTxnType()).thenReturn(type);
        return r;
    }
}
```

Check before running: `CategorizationService.SHARED_CORPUS_SOURCE` is public (`grep -n "SHARED_CORPUS_SOURCE =" backend/src/main/java/com/finora/service/CategorizationService.java`). If it is not public, use its string value from that line. The `row(...)` mocks are created inside `given(...)`'s arguments, before `when(...)` on the repository: that is the safe order (see memory "Mockito projection-mock nesting trap"); if Mockito reports `UnfinishedStubbingException`, build the rows into local variables first.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=CategorySuggestionSweepServiceTest`
Expected: compilation error, `CategorySuggestionSweepService` does not exist.

- [ ] **Step 3: Write the sweep**

Create `backend/src/main/java/com/finora/service/CategorySuggestionSweepService.java`:

```java
package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CategorySuggestionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Re-checks rows still waiting for review against the current suggestion rules.
 *
 * <h2>Why</h2>
 *
 * <p>A row's category is suggested once, when it is imported. A rule added later only helped the
 * next import: measured on a tester's account (2026-10-03), bank-interest credits and a card-bill
 * credit imported the day before #1876 added rules for them were still "Other" and waiting for the
 * user. Raising {@link CategorizationService#SUGGESTION_VERSION} makes this sweep re-run the
 * suggestion on every row that is still waiting.
 *
 * <h2>What it may change</h2>
 *
 * <p>Only a row that is waiting, not chosen by the user, and still carrying one of the two
 * unresolved guesses ("Other" or the structural person guess). The suggestion is the read-only
 * waterfall staging uses, with the same inputs ({@code accountType} null, the row's direction), so
 * a re-checked row gets exactly what a fresh import of it would. It never calls the AI model.
 * A row the rules still cannot place is only stamped.
 *
 * <h2>Shape</h2>
 *
 * <p>Same as {@link CounterpartyBackfillSweepService}: no job table, a discovery predicate a done
 * row no longer matches, one transaction per batch, a failing row left unstamped and logged without
 * its narration, and each user whose categories changed reconciled afterwards in their own
 * transaction.
 */
@Component
public class CategorySuggestionSweepService {

    private static final Logger log = LoggerFactory.getLogger(CategorySuggestionSweepService.class);

    @Value("${app.category-suggestion-sweep.enabled:true}")
    private boolean sweepEnabled;

    /** Per row: one read-only suggestion (a few indexed reads) and one single-row UPDATE. */
    @Value("${app.category-suggestion-sweep.batch-size:500}")
    private int batchSize;

    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final TransactionTemplate transactionTemplate;

    public CategorySuggestionSweepService(TransactionRepository transactionRepository,
                                          CategorizationService categorizationService,
                                          ReconciliationService reconciliationService,
                                          TransactionTemplate transactionTemplate) {
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.transactionTemplate = transactionTemplate;
    }

    /** Flag-gated and fixedDelay for the reasons CounterpartyBackfillSweepService.scheduledSweep gives. */
    @Scheduled(fixedDelayString = "${app.category-suggestion-sweep.interval-ms:300000}",
            initialDelayString = "${app.category-suggestion-sweep.initial-delay-ms:180000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.changed() > 0 || result.stamped() > 0 || result.failed() > 0) {
            log.info("Category suggestion re-check at v{}: {} row(s) given a category, {} unchanged, {} skipped, {} failed.{}",
                    CategorizationService.SUGGESTION_VERSION, result.changed(), result.stamped(),
                    result.skipped(), result.failed(), result.drained() ? " Backlog drained." : "");
        }
    }

    public Result sweep() {
        short version = CategorizationService.SUGGESTION_VERSION;
        List<CategorySuggestionRow> candidates = transactionRepository
                .findWaitingRowsBelowSuggestionVersion(version, PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return new Result(0, 0, 0, 0, true);

        int[] counts = new int[4]; // changed, stamped, skipped, failed
        Set<UUID> changedUsers = new LinkedHashSet<>();
        Map<UUID, List<CategoryRule>> rulesByUser = new HashMap<>();
        transactionTemplate.executeWithoutResult(tx -> {
            for (CategorySuggestionRow row : candidates) {
                try {
                    List<CategoryRule> rules = rulesByUser.computeIfAbsent(row.getUserId(), categorizationService::ruleSetFor);
                    CategorizationService.Suggestion s = categorizationService.suggestReadOnly(rules, row.getUserId(),
                            row.getDescription(), row.getAmount(), null, null, row.getTxnType());
                    if (isStillAGuess(s)) {
                        if (transactionRepository.stampSuggestionVersion(row.getId(), version) > 0) counts[1]++;
                        else counts[2]++;
                        continue;
                    }
                    Category category = categorizationService.resolveOrCreateCategory(row.getUserId(), s.category());
                    boolean needsReview = categorizationService.needsCategoryReview(row.getUserId(),
                            CategorizationService.isUnconfirmedGuess(s.source(), s.category()), s.confidence());
                    int written = transactionRepository.applyCategorySuggestion(row.getId(), category.getId(),
                            s.decisionSource(), s.ruleId(), s.confidence(), needsReview, version);
                    if (written > 0) {
                        counts[0]++;
                        changedUsers.add(row.getUserId());
                        categorizationService.recordRuleMatch(s.ruleId());
                    } else {
                        counts[2]++;
                    }
                } catch (RuntimeException e) {
                    // Left unstamped so the next pass retries it; the narration is user financial
                    // data and is not logged.
                    counts[3]++;
                    log.error("Category suggestion re-check failed for transaction {}: {}", row.getId(), e.toString());
                }
            }
        });

        // A new category can change what reconciliation concludes (an investment's exclusion, a card
        // bill), and nothing else re-runs it until the user next imports or edits. One transaction
        // per user, after the batch committed, for the reasons CounterpartyBackfillSweepService gives.
        for (UUID userId : changedUsers) {
            try {
                transactionTemplate.executeWithoutResult(tx -> reconciliationService.reconcileForUser(userId));
            } catch (RuntimeException e) {
                log.error("Reconciliation after category re-check failed for user {}: {}", userId, e.toString());
            }
        }

        boolean drained = candidates.size() < batchSize && counts[3] == 0;
        return new Result(counts[0], counts[1], counts[2], counts[3], drained);
    }

    /** The waterfall still ends on one of the two unresolved guesses: nothing better to write. */
    private static boolean isStillAGuess(CategorizationService.Suggestion s) {
        return s.decisionSource() == Transaction.DecisionSource.MERCHANT_DEFAULT
                || s.decisionSource() == Transaction.DecisionSource.STRUCTURAL_P2P;
    }

    /**
     * @param changed rows given a new category
     * @param stamped rows the current rules examined and left as they were
     * @param skipped rows that stopped qualifying between discovery and write (chosen by the user,
     *                answered elsewhere, deleted)
     * @param failed  rows the suggestion threw on; left for the next pass
     * @param drained whether nothing is left to examine
     */
    public record Result(int changed, int stamped, int skipped, int failed, boolean drained) {
    }
}
```

Check `CategoryRule`'s package before compiling (`grep -rn "class CategoryRule\b" backend/src/main/java`) and fix the import if it is not `com.finora.entity`.

- [ ] **Step 4: Add the configuration**

In `application.yml`, after the `counterparty-backfill` block:

```yaml
  category-suggestion-sweep:
    # CategorySuggestionSweepService. Re-runs the read-only category suggestion on rows still
    # waiting for review whenever CategorizationService.SUGGESTION_VERSION is raised. Off under test
    # for the same reason as every sweep here (BH-058); tests call sweep() directly.
    enabled: ${CATEGORY_SUGGESTION_SWEEP_ENABLED:true}
    batch-size: ${CATEGORY_SUGGESTION_SWEEP_BATCH_SIZE:500}
    interval-ms: ${CATEGORY_SUGGESTION_SWEEP_INTERVAL_MS:300000}
    # Three minutes, one after the counterparty backfill's two: a re-typed payee can change what
    # the waterfall answers, so the typing goes first on a fresh boot.
    initial-delay-ms: ${CATEGORY_SUGGESTION_SWEEP_INITIAL_DELAY_MS:180000}
```

Check that the block sits under the same parent key as `counterparty-backfill` (`app:`); the `@Value` keys above read `app.category-suggestion-sweep.*`. In `application-test.yml`, after the `counterparty-backfill` block:

```yaml
  category-suggestion-sweep:
    enabled: false
```

- [ ] **Step 5: Run the unit test**

Run: `cd backend && ./mvnw -q test -Dtest=CategorySuggestionSweepServiceTest`
Expected: 5 tests, 0 failures.

- [ ] **Step 6: Write the IT against real Postgres and the real waterfall**

Create `backend/src/test/java/com/finora/service/CategorySuggestionSweepIT.java`:

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.BankActivityCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-check end to end: real Postgres, the real waterfall, the real category resolver. Survives
 * other classes' rows the same way CounterpartyBackfillSweepIT does: drains, and asserts only on
 * ids seeded here.
 */
class CategorySuggestionSweepIT extends AbstractIntegrationTest {

    // The bank's own interest credit in the shape the rules read ("Int.Pd" with the period after it).
    private static final String INTEREST = "1234567890:Int.Pd:01-03-2026 to 31-05-2026"; // synthetic-ok

    @Autowired private CategorySuggestionSweepService sweepService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;

    private UUID userId;
    private UUID accountId;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("suggestion-sweep-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();

        Category other = new Category();
        other.setUserId(userId);
        other.setName("Other");
        otherId = categoryRepository.save(other).getId();

        ReflectionTestUtils.setField(sweepService, "batchSize", 500);
    }

    @Test
    void aWaitingInterestCreditImportedBeforeTheRuleIsFiledAndLeavesTheQueue() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, true, false);

        drain();

        Transaction t = reload(id);
        Category category = categoryRepository.findById(t.getCategoryId()).orElseThrow();
        // The user had no such category: the re-check created it, as an import confirm would.
        assertThat(category.getName()).isEqualTo(BankActivityCategory.INTEREST_AND_CASHBACK);
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isCategoryManuallySet()).isFalse();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
    }

    @Test
    void aRowTheUserChoseIsNeverTouched() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, true, true);

        drain();

        Transaction t = reload(id);
        assertThat(t.getCategoryId()).isEqualTo(otherId);
        assertThat(t.getSuggestionVersion()).isZero();
    }

    @Test
    void aRowNotWaitingIsNeverTouched() {
        UUID id = seed(INTEREST, Transaction.Type.INCOME, false, false);

        drain();

        assertThat(reload(id).getCategoryId()).isEqualTo(otherId);
    }

    @Test
    void aRowTheRulesStillCannotPlaceStaysWaitingAndIsNotReExamined() {
        UUID id = seed("UPI/REF96/UPI", Transaction.Type.EXPENSE, true, false); // synthetic-ok
        long versionBefore = reload(id).getVersion();

        drain();

        Transaction t = reload(id);
        assertThat(t.getCategoryId()).isEqualTo(otherId);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.getSuggestionVersion()).isEqualTo(CategorizationService.SUGGESTION_VERSION);
        assertThat(t.getVersion()).isEqualTo(versionBefore);
    }

    private void drain() {
        for (int pass = 0; pass < 20; pass++) {
            if (sweepService.sweep().drained()) return;
        }
        throw new AssertionError("Re-check did not drain in 20 passes -- the sweep is not making progress.");
    }

    private UUID seed(String description, Transaction.Type type, boolean waiting, boolean chosen) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(otherId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(812));
        t.setTxnType(type);
        t.setDescription(description);
        t.applyCounterpartyTyping(description);
        t.setDecisionSource(Transaction.DecisionSource.MERCHANT_DEFAULT);
        t.setNeedsCategoryReview(waiting);
        t.setCategoryManuallySet(chosen);
        return transactionRepository.save(t).getId();
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }
}
```

- [ ] **Step 7: Run the IT**

Run: `cd backend && ./mvnw verify -Dtest=CategorySuggestionSweepIT`
Expected: 4 tests, 0 failures (check `backend/target/*-reports` for the class). If `aWaitingInterestCreditImportedBeforeTheRuleIsFiledAndLeavesTheQueue` fails on the category, print what the waterfall answers for `INTEREST` with direction INCOME before changing anything (a one-off `System.out.println` of `suggestReadOnly(...)` in the test), and fix the cause shown, not the assertion.

- [ ] **Step 8: Tell the rule files about the version**

At the top of the word list in each of `CategoryRules.java`, `ShopTradeCategory.java`, `BankActivityCategory.java`, add one comment line:

```java
    // A change here that can move a row off "Other" or "Personal Transfer": raise
    // CategorizationService.SUGGESTION_VERSION so rows already waiting are re-checked.
```

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/service/CategorySuggestionSweepService.java \
  backend/src/main/resources/application.yml backend/src/main/resources/application-test.yml \
  backend/src/main/java/com/finora/util/CategoryRules.java \
  backend/src/main/java/com/finora/util/ShopTradeCategory.java \
  backend/src/main/java/com/finora/util/BankActivityCategory.java \
  backend/src/test/java/com/finora/service/CategorySuggestionSweepServiceTest.java \
  backend/src/test/java/com/finora/service/CategorySuggestionSweepIT.java
git commit -m "feat(transactions): re-check rows waiting for review when the suggestion rules change"
```

---

### Task 3: Full verification, corpus check, PR and production check

**Files:** none new.

- [ ] **Step 1: Full backend run**

Run: `cd backend && ./mvnw verify`
Expected: BUILD SUCCESS, 0 failures, 0 errors. Record the unit and integration test counts for the PR.

- [ ] **Step 2: Corpus check: only waiting rows can change**

The sweep changes nothing at import time (staging is untouched), so the corpus import output must be unchanged. Run `./mvnw verify -Dtest=RealCorpusImportEndToEndIT` on `origin/main` and on this branch and compare the per-statement results it reports. Expected: no difference. A difference means an import path changed and is a bug in this branch.

- [ ] **Step 3: Self-review the diff**

Run: `git diff origin/main...HEAD`
Check by reading: every write is guarded on `category_manually_set = false` and `needs_category_review = true`; the stamp does not bump `version`; the apply does; no narration is logged; the flag is off in `application-test.yml`.

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin HEAD
gh pr create --title "feat(transactions): re-check rows waiting for review when the suggestion rules change" --body-file <scratchpad body file>
```
Body: why (tester's interest and card-bill rows stuck after #1876), what (column, version, sweep, guarded writes), verification (counts from Step 1, corpus diff from Step 2), and the production check below. End with the Claude Code line.

- [ ] **Step 5: Production check (owner runs SQL, after deploy and one sweep interval)**

Before deploy and again 10 minutes after:

```sql
SELECT decision_source,
       count(*) FILTER (WHERE needs_category_review) AS waiting,
       count(*) FILTER (WHERE suggestion_version > 0) AS rechecked
FROM transactions
WHERE user_id = '930d0b0d-7a68-40b1-9a22-723dc80bdda5'
  AND deleted_at IS NULL
GROUP BY 1
ORDER BY 2 DESC;
```

(The `suggestion_version` column does not exist before deploy; run the query without that column before.) Expected after: `MERCHANT_DEFAULT` waiting below 96; `KEYWORD_MATCH` higher by the same number; `MANUAL` unchanged at 91.
