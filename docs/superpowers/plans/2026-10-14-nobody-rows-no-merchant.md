# Rows That Name Nobody Get No Merchant -- Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A transaction whose narration names no payee (a bare UPI/NACH reference, an ATM/ATW/NWD withdrawal) gets no merchant, and the fake merchants already made for such rows are detached and removed by a one-time, dry-run-first sweep.

**Architecture:** One rule, `MerchantNames.narrationNamesNobody`, decides. `MerchantNormalizationEngine.resolve` applies it first and returns `Optional<Merchant>`; the compiler then names every caller, and `CategorizationService` handles the empty case the way its read-only path already does. A Flyway-queued, flag-gated `JdbcTemplate` sweep (the pattern of `MerchantRailNameSweepService`) cleans up existing rows.

**Tech Stack:** Java 25, Spring Boot, JPA + JdbcTemplate, Flyway, JUnit 5 + AssertJ + Mockito, Testcontainers Postgres (`AbstractIntegrationTest`).

**Spec:** `docs/superpowers/specs/2026-10-14-nobody-rows-no-merchant-design.md`

## Global Constraints

- Worktree `/Users/sid/Downloads/finora-nobody-no-merchant`, branch `feature/nobody-rows-no-merchant`. Absolute paths for every command. Never write in `/Users/sid/Downloads/finora`.
- Before Task 1: if #2153 has merged, `git fetch origin && git rebase origin/main`; if not, continue and rebase before Task 6. #2153 adds `"pos" -> "Card payment"` to `MerchantNames.RAIL_PAYMENTS`; the rule picks it up through `RAIL_PAYMENTS.containsValue`, no code here names it.
- Migration version `V269`. Re-check it is free on `origin/main` and in open PRs right before Task 5's commit and again before pushing.
- Every narration, name and reference in code, tests and comments is invented. Reference-like numbers use the placeholder forms `100000000001`, `200000000001`, `0000000001`; card masks `512345XXXXXX1234`; IFSC `UTIB0XXXXXX`.
- Commit messages carry no `Co-Authored-By` or any AI attribution.
- Never commit while `./mvnw verify` is running in this worktree.
- Unit run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='<Classes>' -Dsurefire.failIfNoSpecifiedTests=false`. IT run: same with `verify` and `-DfailIfNoTests=false`. Results: `backend/target/surefire-reports/TEST-<fqcn>.xml`.
- Behaviour that does not change and must not: rows that name a payee keep exactly the merchant they get today (Task 6 measures it on the corpus).

## Review Focus

1. **An alias written before this change for a nobody narration.** Expect `resolve` and both `resolveReadOnly` overloads to return empty anyway (the rule runs before the alias lookup). Test in Task 2.
2. **A structured narration whose payee field names someone but that opens with a rail** ("UPI/DR/100000000001/SAMPLE STORE/..."). Expect a merchant as today. Test in Task 1 (rule) and Task 2 (engine).
3. **A person sets a category on a nobody row.** Expect the category stored on the row, no `merchant_category_learning` row, no learning event, no exception. Test in Task 4 (IT).
4. **The sweep meets a queued merchant whose rows are mixed** (some name nobody, some name a payee). Expect only the nobody rows detached and the merchant kept. Test in Task 5.
5. **The sweep meets a merchant a person approved, or one with a learned category.** Expect: approved untouched; learned kept after its nobody rows are detached. Test in Task 5.

---

### Task 1: The rule -- `MerchantNames.narrationNamesNobody`

**Files:**
- Modify: `backend/src/main/java/com/finora/util/MerchantNames.java`
- Test: `backend/src/test/java/com/finora/util/MerchantNamesTest.java`

**Interfaces:**
- Produces: `public static boolean narrationNamesNobody(String description)` in `com.finora.util.MerchantNames`.

- [ ] **Step 1: Write the failing tests** (append inside `MerchantNamesTest`, before the closing brace)

```java
    // ---- whether a narration names anybody a merchant could stand for ----

    @Test
    void aNarrationOfNothingButARailAndAReferenceNamesNobody() {
        assertThat(MerchantNames.narrationNamesNobody("UPI/RRN 100000000001/UPI")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("UPI 100000000001")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("NACH CHQ 004471")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("UPI/100000000001/12:30:11/UPI/q12345678@ybl/UPI")).isTrue();
        // Left with a direction word: stored whole as "Upi Dr", shown as the rail's payment.
        assertThat(MerchantNames.narrationNamesNobody("UPI/DR/100000000001")).isTrue();
    }

    @Test
    void aCashWithdrawalNamesNobodyInEveryFormatSeen() {
        assertThat(MerchantNames.narrationNamesNobody("ATM WDL 100000000001 SAMPLETOWN")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("ATM/CASH WDL/141023/SAMPLETOWN")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("ATW-512345XXXXXX1234-S1ANBG12-SAMPLETOWN")).isTrue();
        assertThat(MerchantNames.narrationNamesNobody("NWD-512345XXXXXX1234-SAMPLETOWN")).isTrue();
    }

    @Test
    void aNarrationThatNamesAPayeeNamesSomebody() {
        assertThat(MerchantNames.narrationNamesNobody("UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc")).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("UPI SAMPLE STORE 100000000001")).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("NEFT TRANSFER SAMPLE TRADERS")).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("ACH D- SAMPLE FINANCE LTD-0000000001")).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("POS 512345XXXXXX1234 SAMPLE MART")).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("SAMPLE CAFE 4471")).isFalse();
        // A payment app's own name can be the payee (a wallet top-up).
        assertThat(MerchantNames.narrationNamesNobody("UPI PAYTM 100000000001")).isFalse();
        // "ATM" further along is not a withdrawal.
        assertThat(MerchantNames.narrationNamesNobody("SAMPLE ATM SERVICES LTD")).isFalse();
    }

    @Test
    void anEmptyNarrationIsLeftToTheEngine() {
        assertThat(MerchantNames.narrationNamesNobody(null)).isFalse();
        assertThat(MerchantNames.narrationNamesNobody("   ")).isFalse();
    }
```

- [ ] **Step 2: Run, expect a compile failure** ("cannot find symbol narrationNamesNobody")

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='MerchantNamesTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** (in `MerchantNames`, after `importedNameFor`)

```java
    /** The first words of a cash withdrawal: "ATM ..." and HDFC's own-ATM "ATW-" and other-bank "NWD-". */
    private static final Set<String> CASH_WITHDRAWAL_WORDS = Set.of("atm", "atw", "nwd");

    /**
     * Whether a narration names nobody a merchant could stand for: a payment that carries only its
     * rail and a reference ("UPI/RRN &lt;n&gt;/UPI"), or a cash withdrawal. Such a row gets no
     * merchant -- MerchantNormalizationEngine.resolve asks this first.
     *
     * <p>True when the narration opens with a cash-withdrawal word, or when it has no structured
     * payee field and the name {@link #forNarration} gives it is shown as a rail's payment ("UPI
     * payment", "NACH payment", ...). A narration with a payee field always names somebody.
     */
    public static boolean narrationNamesNobody(String description) {
        if (description == null || description.isBlank()) return false;
        String first = CategoryRules.normalize(description).split(" ")[0];
        if (CASH_WITHDRAWAL_WORDS.contains(first)) return true;
        if (CategoryRules.structuredPayee(description) != null) return false;
        return RAIL_PAYMENTS.containsValue(forDisplay(forNarration(description)));
    }
```

- [ ] **Step 4: Run, expect PASS** (same command). If an assertion fails, print `forNarration` and `forDisplay(forNarration(..))` for that narration with a scratch `jshell`/probe before changing anything, and report the values.

- [ ] **Step 5: Commit**

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant
git add backend/src/main/java/com/finora/util/MerchantNames.java backend/src/test/java/com/finora/util/MerchantNamesTest.java
git commit -m "feat(transactions): tell a narration that names nobody from one that names a payee"
```

---

### Task 2: The engine returns no merchant for such a row

**Files:**
- Modify: `backend/src/main/java/com/finora/service/MerchantNormalizationEngine.java` (`resolve` at ~121-185, `resolveReadOnly(UUID,String)` ~344-359, `resolveReadOnly(UUID,String,MerchantIndex)` ~391-398)
- Modify (mechanical): `backend/src/test/java/com/finora/service/MerchantNormalizationEngineTest.java`, `backend/src/test/java/com/finora/service/MerchantOversizedDescriptionIT.java`, any other test the compiler names.
- Test: `backend/src/test/java/com/finora/service/MerchantNormalizationEngineTest.java`

**Interfaces:**
- Consumes: `MerchantNames.narrationNamesNobody(String)` (Task 1).
- Produces: `public Optional<Merchant> resolve(UUID userId, String description)` (was `Merchant`). `resolveReadOnly` overloads keep `Optional<Merchant>` and return empty for such a row.

- [ ] **Step 1: Replace the test that locks in today's behaviour, and add the alias case**

In `MerchantNormalizationEngineTest`, replace the whole test `paymentsNamingNobodyShareANameButNotAMerchant` (and its javadoc) with:

```java
    /**
     * A narration that names nobody gets no merchant and leaves no alias: each one used to make a
     * merchant of its own, holding one row and named after its rail.
     */
    @Test
    @DisplayName("a payment that names nobody, or a cash withdrawal, gets no merchant")
    void aNarrationThatNamesNobodyGetsNoMerchant() {
        assertThat(engine.resolve(userId, "UPI/RRN 000000000001/UPI")).isEmpty();
        assertThat(engine.resolve(userId, "NACH CHQ 004471")).isEmpty();
        assertThat(engine.resolve(userId, "ATW-512345XXXXXX1234-S1ANBG12-SAMPLETOWN")).isEmpty();
        assertThat(engine.resolveReadOnly(userId, "UPI/RRN 000000000002/UPI")).isEmpty();
        assertThat(merchants).isEmpty();
        assertThat(aliases).isEmpty();
    }

    /** The rule runs before the exact-alias lookup: an alias written before it existed is not used. */
    @Test
    @DisplayName("an alias written before for a narration that names nobody is not used")
    void anOldAliasForANarrationThatNamesNobodyIsNotUsed() {
        String narration = "UPI/RRN 000000000001/UPI";
        Merchant old = merchantNamed("Upi Upi");
        MerchantAlias alias = new MerchantAlias();
        alias.setMerchantId(old.getId());
        alias.setUserId(userId);
        alias.setNormalizedAlias(CategoryRules.normalize(narration));
        aliases.add(alias);

        assertThat(engine.resolve(userId, narration)).isEmpty();
        assertThat(engine.resolveReadOnly(userId, narration)).isEmpty();
        assertThat(engine.resolveReadOnly(userId, narration, engine.indexFor(userId))).isEmpty();
    }

    @Test
    @DisplayName("a rail narration that names its payee still gets a merchant")
    void aRailNarrationThatNamesItsPayeeStillGetsAMerchant() {
        assertThat(engine.resolve(userId, "UPI/DR/100000000001/SAMPLEPA/HDFC/samplepayee21@okhdfc"))
                .get().extracting(Merchant::getCanonicalName).isEqualTo("Samplepa");
    }
```

Add imports if missing: `com.finora.entity.MerchantAlias`, `com.finora.util.CategoryRules`.

- [ ] **Step 2: Run, expect compile failure** (`resolve` returns `Merchant`, so `.isEmpty()` / `.get()` do not compile)

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='MerchantNormalizationEngineTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Change the engine**

`resolve`: change the signature and add the rule as its first statement; wrap every return.

```java
    @Transactional
    public java.util.Optional<Merchant> resolve(UUID userId, String description) {
        // A narration that names nobody -- a bare UPI/NACH reference, a cash withdrawal -- gets no
        // merchant. Asked before the alias lookup, so an alias written before this rule existed
        // cannot hand back the merchant it made. See MerchantNames.narrationNamesNobody.
        if (MerchantNames.narrationNamesNobody(description)) return java.util.Optional.empty();
        String rawAlias = CategoryRules.normalize(description);
```

and in the same method: `if (byAlias.isPresent()) return byAlias;` (it is already an `Optional<Merchant>`), `return java.util.Optional.of(repointDanglingAlias(userId, description, existingAlias.get()));`, `return java.util.Optional.of(candidate);`, `return java.util.Optional.of(created);`.

Both `resolveReadOnly` overloads: add as their first statement

```java
        if (MerchantNames.narrationNamesNobody(description)) return java.util.Optional.empty();
```

Update the class/method javadoc sentence in `resolve` that says every caller dereferences the result: it now returns empty for a narration that names nobody, and a null is still never returned.

- [ ] **Step 4: Fix the test call sites the compiler names.** Rule: where a test needs the merchant, append `.orElseThrow()` to `engine.resolve(...)` (e.g. `Merchant first = engine.resolve(userId, "...").orElseThrow();`, `engine.resolve(userId, "...").orElseThrow().getId()`). Do not change any other assertion. Run `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test-compile 2>&1 | grep -E "ERROR.*\.java" | head -40` until it prints nothing.

- [ ] **Step 5: Run, expect PASS**

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='MerchantNormalizationEngineTest,MerchantNamesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 0 failures. Production code still fails to compile in `CategorizationService` only if Task 3 is not done -- if `test-compile` stops on main code, do Task 3 Step 3 before this run and say so in the commit of Task 3.

- [ ] **Step 6: Commit** (together with Task 3 if the main code needed it to compile)

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant
git add -u backend/src
git commit -m "feat(transactions): the merchant engine gives a row that names nobody no merchant"
```

---

### Task 3: `CategorizationService` handles a row with no merchant

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (`suggest` ~236-300, `learn` ~714, `queueLearning` ~752, `resolveMerchantId` ~770, `applySideEffectRules` ~826)
- Modify (mechanical): `backend/src/test/java/com/finora/service/CategorizationServiceTest.java` (stubs of `merchantNormalizationEngine.resolve` return `Optional.of(merchant)`)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: `Optional<Merchant> MerchantNormalizationEngine.resolve(UUID, String)` (Task 2).
- Produces: unchanged public signatures. `Suggestion.merchantId()` and `resolveMerchantId(..)` may be null; `learn`/`queueLearning` do nothing when there is no merchant.

- [ ] **Step 1: Write the failing tests** (in `CategorizationServiceTest`, using its existing fields `categorizationService`, `merchantNormalizationEngine`, `merchantLearningService`, `learningEventPublisher`, `ruleEngineService`, `userId`)

```java
    @Test
    void aRowThatNamesNobodyIsSuggestedACategoryWithNoMerchant() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(Optional.empty());
        when(ruleEngineService.evaluateCategoryRule(eq(userId), anyString(), any(), isNull(), any(), any()))
                .thenReturn(Optional.empty());

        var suggestion = categorizationService.suggest(userId, "ATM WDL 100000000001 SAMPLETOWN",
                new BigDecimal("500.00"), null, Transaction.Type.EXPENSE);

        assertThat(suggestion.merchantId()).isNull();
        assertThat(suggestion.category()).isEqualTo("Cash Withdrawal");
        verify(learningRepository, never()).findByUserIdAndMerchantId(any(), any());
    }

    @Test
    void learningFromARowThatNamesNobodyTeachesNothing() {
        when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(Optional.empty());

        categorizationService.learn(userId, "UPI/RRN 100000000001/UPI", UUID.randomUUID());
        categorizationService.queueLearning(userId, "UPI/RRN 100000000001/UPI", UUID.randomUUID());

        verifyNoInteractions(merchantLearningService, learningEventPublisher);
        assertThat(categorizationService.resolveMerchantId(userId, "UPI/RRN 100000000001/UPI")).isNull();
    }
```

Add imports as needed: `java.util.Optional`, `java.math.BigDecimal`, `com.finora.entity.Transaction`, `static org.mockito.ArgumentMatchers.isNull`, `static org.mockito.Mockito.never`, `static org.mockito.Mockito.verifyNoInteractions`. If `evaluateCategoryRule`'s 6-argument userId overload differs from the call in `suggest` (~238), stub the exact overload `suggest` calls.

- [ ] **Step 2: Run, expect compile failure / FAIL**

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='CategorizationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement**

`suggest` -- replace the first two statements and every `merchant.getId()` with `merchantId`:

```java
        // Empty for a narration that names nobody (MerchantNames.narrationNamesNobody): the row gets
        // a category the same way suggestReadOnly gives one to a row with no merchant yet.
        java.util.Optional<Merchant> merchant = merchantNormalizationEngine.resolve(userId, description);
        String merchantName = merchant.map(Merchant::getCanonicalName).orElse(null);
        UUID merchantId = merchant.map(Merchant::getId).orElse(null);

        var ruleMatch = ruleEngineService.evaluateCategoryRule(userId, description, amount, merchantName, accountType, direction);
```

the learned block becomes `if (merchantId != null) { List<MerchantCategoryLearning> distribution = learningRepository.findByUserIdAndMerchantId(userId, merchantId); ... }` (body unchanged except `merchantId`), and the keyword step `suggestCategoryWithMerchantFallback(description, trustedNameOf(merchant.orElse(null)))`.

`learn`:

```java
    public void learn(UUID userId, String description, UUID categoryId) {
        // A row that names nobody has no merchant to learn for: the correction stays on that row.
        merchantNormalizationEngine.resolve(userId, description)
                .ifPresent(merchant -> merchantLearningService.confirm(userId, merchant.getId(), categoryId));
    }
```

`queueLearning`:

```java
    public void queueLearning(UUID userId, String description, UUID categoryId) {
        merchantNormalizationEngine.resolve(userId, description)
                .ifPresent(merchant -> learningEventPublisher.enqueue(userId, merchant.getId(), categoryId, null, null));
    }
```

`resolveMerchantId`: `return merchantNormalizationEngine.resolve(userId, description).map(Merchant::getId).orElse(null);` and update its javadoc: "every transaction gets a merchant_id" becomes "every transaction whose narration names a payee gets a merchant_id; one that names nobody gets null".

`applySideEffectRules(UUID, Transaction, List<CategoryRule>)`:

```java
        String merchantName = merchantNormalizationEngine.resolve(userId, t.getDescription())
                .map(Merchant::getCanonicalName).orElse(null);
```

and pass `merchantName` where `merchant.getCanonicalName()` was passed (both branches).

- [ ] **Step 4: Fix existing stubs.** In `CategorizationServiceTest` every `when(merchantNormalizationEngine.resolve(...)).thenReturn(x)` becomes `.thenReturn(Optional.of(x))`. Compile until clean, then run the class.

Expected: all pass. If `suggestion.category()` is not "Cash Withdrawal", print what the keyword step returns for that narration before changing anything.

- [ ] **Step 5: Full main compile + the three classes**

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q test -Dtest='CategorizationServiceTest,MerchantNormalizationEngineTest,MerchantNamesTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 6: Commit**

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant
git add -u backend/src
git commit -m "feat(transactions): categorise a row that names nobody without a merchant, and learn nothing from it"
```

---

### Task 4: End to end against Postgres -- create and import

**Files:**
- Create: `backend/src/test/java/com/finora/transactions/NobodyRowsGetNoMerchantIT.java`
- Modify only if a test fails for a reason traced to them: `ImportService.java` (~1382), `StatementRefreshService.java` (~346, ~402), `TransactionService.java` (~357, ~372)

**Interfaces:**
- Consumes: Tasks 1-3. `TransactionService.create(UUID, TransactionDto.CreateRequest)`; `PreviewGenerator.generate(UUID, String, InputStream)`; `ImportSessionService.createSession(UUID, String, byte[], List<StagedRow>, Object)`; `ImportService.confirmSession(UUID, ConfirmRequest)`; `AnalyticsService.topMerchants(UUID, YearMonth)`. The preview's no-merchant handling is already unit-tested (`TransactionNormalizerTest.normalize_leavesMerchantFieldsNull_whenIndexHasNoMatch`); this IT checks it with the real engine.

- [ ] **Step 1: Write the IT**

```java
package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A row whose narration names nobody -- a bare UPI reference, a cash withdrawal -- is stored with no
 * merchant, still gets a category, teaches nothing, and stays out of Top Merchants. Every narration
 * is invented.
 */
class NobodyRowsGetNoMerchantIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("nobody-rows-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        user.setPhoneVerified(true);
        userId = userRepository.save(user).getId();
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(account).getId();
    }

    private TransactionDto create(String narration, String categoryName) {
        return transactionService.create(userId, new TransactionDto.CreateRequest(
                accountId, categoryName, LocalDate.of(2026, 9, 15), narration,
                new BigDecimal("500.00"), "EXPENSE", null));
    }

    private Transaction stored(TransactionDto dto) {
        return transactionRepository.findById(dto.id()).orElseThrow();
    }

    @Test
    void aCashWithdrawalIsStoredWithNoMerchantAndStillFiledAsCash() {
        Transaction t = stored(create("ATM WDL 100000000001 SAMPLETOWN", null));

        assertThat(t.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(t.getCategoryId()).orElseThrow().getName()).isEqualTo("Cash Withdrawal");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchants WHERE user_id = ? AND lifecycle_status = 'TEMPORARY'",
                Long.class, userId)).isZero();
    }

    @Test
    void aCategoryAPersonGivesARowThatNamesNobodyStaysOnThatRow() {
        Transaction t = stored(create("UPI/RRN 100000000001/UPI", "Shopping"));

        assertThat(t.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(t.getCategoryId()).orElseThrow().getName()).isEqualTo("Shopping");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_category_learning WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_learning_events WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    void aRowThatNamesAPayeeStillGetsItsMerchant_andOnlyItReachesTopMerchants() {
        Transaction named = stored(create("UPI/DR/100000000002/SAMPLEPA/HDFC/samplepayee21@okhdfc", null));
        stored(create("UPI/RRN 100000000003/UPI", null));
        stored(create("NWD-512345XXXXXX1234-SAMPLETOWN", null));

        assertThat(named.getMerchantId()).isNotNull();
        assertThat(analyticsService.topMerchants(userId, null))
                .extracting(top -> top.merchantId())
                .containsExactly(named.getMerchantId());
    }
}
```

Then add the import path (preview with the real engine, then confirm). Add these fields and imports to the class: `@Autowired private com.finora.imports.PreviewGenerator previewGenerator;`, `@Autowired private com.finora.imports.ImportSessionService importSessionService;`, `@Autowired private com.finora.imports.ImportService importService;`, imports `com.finora.dto.ImportDto.ConfirmRequest`, `com.finora.dto.ImportDto.ConfirmedRow`, `com.finora.dto.ImportDto.StagedRow`, `com.finora.entity.ImportSession`, `java.io.ByteArrayInputStream`, `java.nio.charset.StandardCharsets`, `java.util.List`. (If a class is in another package, follow `InvestmentRulePrecedenceIT`'s imports.)

```java
    private ConfirmedRow confirmed(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(), true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void anImportShowsAndStoresARowThatNamesNobodyWithNoMerchant() throws Exception {
        String csv = "Date,Description,Amount,Type\n"
                + "2026-07-01,UPI/RRN 100000000004/UPI,500.00,DEBIT\n"
                + "2026-07-02,UPI/DR/100000000005/SAMPLEPA/HDFC/samplepayee21@okhdfc,500.00,DEBIT\n";
        List<StagedRow> rows = previewGenerator.generate(userId, "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).rows();
        assertThat(rows.get(0).merchant()).isNull();

        List<StagedRow> positioned = List.of(rows.get(0).withRowPosition(0), rows.get(1).withRowPosition(1));
        ImportSession session = importSessionService.createSession(userId, "statement.csv",
                csv.getBytes(StandardCharsets.UTF_8), positioned, null);
        importService.confirmSession(userId, new ConfirmRequest(session.getId(),
                positioned.stream().map(this::confirmed).toList(), accountId, null, null, null, null));

        List<Transaction> stored = transactionRepository.findByUserId(userId);
        assertThat(stored).filteredOn(t -> t.getDescription().startsWith("UPI/RRN")).singleElement()
                .extracting(Transaction::getMerchantId).isNull();
        assertThat(stored).filteredOn(t -> t.getDescription().startsWith("UPI/DR")).singleElement()
                .extracting(Transaction::getMerchantId).isNotNull();
    }
```

Check the table name `merchant_learning_events` against `V62__merchant_learning_events.sql` before running.

- [ ] **Step 2: Run, expect PASS** (Tasks 1-3 already implement the behaviour; this IT is the end-to-end proof). To prove the IT can fail, temporarily comment out the rule line in `MerchantNormalizationEngine.resolve`, run, confirm the merchant assertions fail, restore the line, run again.

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q verify -Dtest='NobodyRowsGetNoMerchantIT' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`

- [ ] **Step 3: Commit**

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant
git add backend/src/test/java/com/finora/transactions/NobodyRowsGetNoMerchantIT.java
git commit -m "test(transactions): rows that name nobody are stored with no merchant, end to end"
```

---

### Task 5: Detach existing rows -- `V269` queue and `MerchantNobodyDetachSweepService`

**Files:**
- Create: `backend/src/main/resources/db/migration/V269__merchant_nobody_detach.sql`
- Create: `backend/src/main/java/com/finora/service/MerchantNobodyDetachSweepService.java`
- Test: `backend/src/test/java/com/finora/service/MerchantNobodyDetachSweepIT.java`

**Interfaces:**
- Consumes: `MerchantNames.narrationNamesNobody(String)`; `AuditService.record(UUID, String, String, UUID, Map<String,Object>)`.
- Produces: `MerchantNobodyDetachSweepService.apply()` and `.dryRun()` returning `Result(int rowsDetached, int merchantsDeleted, int merchantsKept, int unchanged, boolean drained)`.

- [ ] **Step 1: Re-check V269 is free**

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant && git fetch -q origin && git ls-tree -r --name-only origin/main backend/src/main/resources/db/migration | grep -c 'V269__'
```
Expected `0`; also scan open PRs as in the #2144 work. If taken, use the next free number everywhere below.

- [ ] **Step 2: Write the migration**

```sql
-- Engine-made merchants whose rows name nobody lose those rows, and are removed when nothing is
-- left on them.
--
-- MerchantNormalizationEngine used to make a merchant for every narration, including one that names
-- no payee (a bare UPI or NACH reference) and a cash withdrawal (ATM, HDFC's ATW and NWD). Each such
-- merchant held one row and was shown in Top Merchants as if it were a shop. New rows now get no
-- merchant (MerchantNames.narrationNamesNobody). This snapshots the merchants that could hold such
-- rows -- engine guesses (TEMPORARY), never one a person approved, whose name opens with a payment
-- word -- and MerchantNobodyDetachSweepService decides row by row in Java, where the rule lives.
--
-- The service starts in dry-run mode: it counts what it would do, logs the counts and writes
-- nothing, until app.merchant-nobody-detach.sweep.mode is set to apply (owner's decision,
-- 2026-10-14).
CREATE TABLE merchant_nobody_detach (
    merchant_id UUID PRIMARY KEY REFERENCES merchants (id) ON DELETE CASCADE
);

INSERT INTO merchant_nobody_detach (merchant_id)
SELECT id
FROM merchants
WHERE lifecycle_status = 'TEMPORARY'
  AND split_part(lower(btrim(canonical_name)), ' ', 1)
      IN ('upi', 'imps', 'neft', 'rtgs', 'mob', 'mmt', 'ib', 'ach', 'nach', 'pos', 'atm', 'atw', 'nwd');
```

- [ ] **Step 3: Write the failing IT**

```java
package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-time detach against a real Postgres: the queue V269 creates, the conditional unlink, the
 * delete, the audit rows. Every name and narration is invented. The queue is this class's own table
 * and no other test writes it, so it is emptied before each test.
 */
class MerchantNobodyDetachSweepIT extends AbstractIntegrationTest {

    @Autowired private MerchantNobodyDetachSweepService sweepService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM merchant_nobody_detach");
        User user = new User();
        user.setEmail("merchant-nobody-detach-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();
    }

    @Test
    void aMerchantHoldingOnlyRowsThatNameNobodyLosesThemAndIsRemoved_andBothAreAudited() {
        Merchant fake = merchant("Upi Upi", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(fake, "UPI/RRN 100000000001/UPI");
        enqueue(fake);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isNull();
        assertThat(merchantRepository.findById(fake.getId())).isEmpty();
        assertThat(result.rowsDetached()).isEqualTo(1);
        assertThat(result.merchantsDeleted()).isEqualTo(1);
        assertThat(queued()).isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs WHERE entity_id = ?", String.class, fake.getId()))
                .containsExactlyInAnyOrder("MERCHANT_ROWS_DETACHED", "MERCHANT_DISCARDED");
    }

    @Test
    void aMerchantWithRowsThatNameAPayeeKeepsThemAndStays() {
        Merchant mixed = merchant("Upi Dr Samplepa Hdfc", Merchant.Lifecycle.TEMPORARY);
        UUID nobody = expense(mixed, "UPI/RRN 100000000002/UPI");
        UUID named = expense(mixed, "UPI/DR/100000000003/SAMPLEPA/HDFC/samplepayee21@okhdfc");
        enqueue(mixed);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(nobody)).isNull();
        assertThat(merchantOf(named)).isEqualTo(mixed.getId());
        assertThat(merchantRepository.findById(mixed.getId())).isPresent();
        assertThat(result.merchantsKept()).isEqualTo(1);
    }

    @Test
    void aMerchantWithALearnedCategoryLosesItsNobodyRowsButIsKept() {
        Merchant learned = merchant("Atm Wdl Sampletown", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(learned, "ATM WDL 100000000004 SAMPLETOWN");
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, user_id, name) VALUES (?, ?, 'Sample Category')", categoryId, userId);
        jdbc.update("INSERT INTO merchant_category_learning (merchant_id, user_id, category_id) VALUES (?, ?, ?)",
                learned.getId(), userId, categoryId);
        enqueue(learned);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isNull();
        assertThat(merchantRepository.findById(learned.getId())).isPresent();
        assertThat(result.merchantsKept()).isEqualTo(1);
    }

    @Test
    void anApprovedMerchantAndOneNoLongerQueuedAreLeftAsTheyAre() {
        Merchant approved = merchant("Upi Upi", Merchant.Lifecycle.APPROVED);
        UUID row = expense(approved, "UPI/RRN 100000000005/UPI");
        enqueue(approved);

        MerchantNobodyDetachSweepService.Result result = sweepService.apply();

        assertThat(merchantOf(row)).isEqualTo(approved.getId());
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(queued()).isEmpty();
    }

    @Test
    void aDryRunCountsWhatItWouldDoAndWritesNothing() {
        Merchant fake = merchant("Atw 512345xxxxxx1234 S1anbg12 Sampletown", Merchant.Lifecycle.TEMPORARY);
        UUID row = expense(fake, "ATW-512345XXXXXX1234-S1ANBG12-SAMPLETOWN");
        enqueue(fake);

        MerchantNobodyDetachSweepService.Result result = sweepService.dryRun();

        assertThat(result.rowsDetached()).isEqualTo(1);
        assertThat(result.merchantsDeleted()).isEqualTo(1);
        assertThat(merchantOf(row)).isEqualTo(fake.getId());
        assertThat(queued()).containsExactly(fake.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ?", Long.class, fake.getId())).isZero();
    }

    private Merchant merchant(String name, Merchant.Lifecycle lifecycle) {
        Merchant m = new Merchant();
        m.setUserId(userId);
        m.setCanonicalName(name);
        m.setLifecycleStatus(lifecycle);
        return merchantRepository.save(m);
    }

    private UUID expense(Merchant merchant, String narration) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setMerchantId(merchant.getId());
        t.setTxnDate(LocalDate.of(2026, 9, 15));
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(narration);
        return transactionRepository.save(t).getId();
    }

    private void enqueue(Merchant merchant) {
        jdbc.update("INSERT INTO merchant_nobody_detach (merchant_id) VALUES (?)", merchant.getId());
    }

    private List<UUID> queued() {
        return jdbc.queryForList("SELECT merchant_id FROM merchant_nobody_detach", UUID.class);
    }

    private UUID merchantOf(UUID transactionId) {
        return jdbc.queryForObject("SELECT merchant_id FROM transactions WHERE id = ?", UUID.class, transactionId);
    }
}
```

Remove the unused `Category` import if the compiler warns. Check the `categories` insert against its NOT NULL columns (`id, user_id, name` with defaults for the rest, as of V267).

- [ ] **Step 4: Run, expect compile failure** (no `MerchantNobodyDetachSweepService`)

Run: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q verify -Dtest='MerchantNobodyDetachSweepIT' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`

- [ ] **Step 5: Implement the service**

```java
package com.finora.service;

import com.finora.util.MerchantNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detaches the rows that name nobody from the engine-made merchants V269 queued
 * ({@code merchant_nobody_detach}), and removes a merchant left with no rows.
 *
 * <p>Until this change MerchantNormalizationEngine made a merchant for every narration, including a
 * bare UPI or NACH reference and a cash withdrawal; each held one row and was shown in Top Merchants
 * as if it were a shop. New rows get no merchant now ({@link MerchantNames#narrationNamesNobody}).
 *
 * <p>Per queued merchant that is still TEMPORARY: every live row whose narration names nobody gets
 * {@code merchant_id = NULL}, written only where it still points at this merchant. The merchant is
 * then deleted only if it has no live row left <b>and</b> no learned category -- deleting would
 * cascade the learning and its audit history. Its aliases go with it. A merchant a person approved
 * is left as it is.
 *
 * <p>Modes ({@code app.merchant-nobody-detach.sweep.mode}): {@code dry-run}, the default, reads the
 * whole queue once per start, logs what it would do and writes nothing; {@code apply} works through
 * the queue in bounded batches; {@code off} does nothing. Each detach is audited
 * ({@code MERCHANT_ROWS_DETACHED}) and each removal ({@code MERCHANT_DISCARDED}), counts only.
 */
@Component
public class MerchantNobodyDetachSweepService {

    private static final Logger log = LoggerFactory.getLogger(MerchantNobodyDetachSweepService.class);
    private static final String ACTOR = "merchant-nobody-detach";

    @Value("${app.merchant-nobody-detach.sweep.mode:dry-run}")
    private String mode;

    @Value("${app.merchant-nobody-detach.sweep.batch-size:200}")
    private int batchSize;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    private volatile boolean dryRunReported;

    public MerchantNobodyDetachSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, AuditService auditService) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.merchant-nobody-detach.sweep.interval-ms:300000}",
            initialDelayString = "${app.merchant-nobody-detach.sweep.initial-delay-ms:240000}")
    public void scheduledSweep() {
        if ("apply".equals(mode)) {
            Result r = apply();
            if (r.decided() > 0) {
                log.info("Merchant nobody detach: {} rows detached, {} merchants removed, {} kept, {} left as they were.{}",
                        r.rowsDetached(), r.merchantsDeleted(), r.merchantsKept(), r.unchanged(),
                        r.drained() ? " Queue drained." : "");
            }
        } else if ("dry-run".equals(mode) && !dryRunReported) {
            dryRunReported = true;
            Result r = dryRun();
            log.info("Merchant nobody detach DRY RUN (nothing written): {} rows would be detached, {} merchants removed, "
                            + "{} kept, {} left as they were, {} queued. Set app.merchant-nobody-detach.sweep.mode=apply to write.",
                    r.rowsDetached(), r.merchantsDeleted(), r.merchantsKept(), r.unchanged(), r.decided());
        }
    }

    private record Queued(UUID merchantId, UUID userId, String lifecycle) {}

    private record Row(UUID id, String description) {}

    private static final class Tally {
        int rowsDetached, deleted, kept, unchanged;
    }

    /** One batch of up to {@code batchSize} queued merchants, in one transaction; each is dequeued. */
    public Result apply() {
        List<Queued> batch = queued(batchSize);
        if (batch.isEmpty()) return new Result(0, 0, 0, 0, true);
        Tally tally = new Tally();
        transactionTemplate.executeWithoutResult(tx -> {
            for (Queued merchant : batch) {
                decide(merchant, tally, true);
                jdbc.update("DELETE FROM merchant_nobody_detach WHERE merchant_id = ?", merchant.merchantId());
            }
        });
        return new Result(tally.rowsDetached, tally.deleted, tally.kept, tally.unchanged, batch.size() < batchSize);
    }

    /** What {@link #apply} would do to the whole queue, writing nothing. */
    public Result dryRun() {
        Tally tally = new Tally();
        for (Queued merchant : queued(Integer.MAX_VALUE)) decide(merchant, tally, false);
        return new Result(tally.rowsDetached, tally.deleted, tally.kept, tally.unchanged, true);
    }

    private List<Queued> queued(int limit) {
        // A LEFT JOIN, so a queued merchant that is gone is still read -- and dropped.
        return jdbc.query("""
                SELECT q.merchant_id, m.user_id, m.lifecycle_status
                FROM merchant_nobody_detach q
                LEFT JOIN merchants m ON m.id = q.merchant_id
                ORDER BY q.merchant_id
                LIMIT ?
                """, (rs, n) -> new Queued(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)), limit);
    }

    private void decide(Queued merchant, Tally tally, boolean write) {
        if (merchant.userId() == null || !"TEMPORARY".equals(merchant.lifecycle())) {
            tally.unchanged++;
            return;
        }
        List<Row> rows = jdbc.query("SELECT id, description FROM transactions WHERE merchant_id = ? AND deleted_at IS NULL",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)), merchant.merchantId());
        List<UUID> nobody = rows.stream().filter(r -> MerchantNames.narrationNamesNobody(r.description())).map(Row::id).toList();
        if (nobody.isEmpty()) {
            tally.unchanged++;
            return;
        }
        boolean learned = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM merchant_category_learning WHERE merchant_id = ?)", Boolean.class, merchant.merchantId()));
        boolean wouldDelete = nobody.size() == rows.size() && !learned;
        if (!write) {
            tally.rowsDetached += nobody.size();
            if (wouldDelete) tally.deleted++; else tally.kept++;
            return;
        }
        int detached = 0;
        for (UUID id : nobody) {
            detached += jdbc.update("UPDATE transactions SET merchant_id = NULL WHERE id = ? AND merchant_id = ?", id, merchant.merchantId());
        }
        tally.rowsDetached += detached;
        if (detached > 0) {
            auditService.record(merchant.userId(), "MERCHANT_ROWS_DETACHED", "Merchant", merchant.merchantId(),
                    Map.of("rows", detached, "actor", ACTOR));
        }
        // Re-checked in the statement itself, so a row linked or a category learned in between wins.
        int deleted = jdbc.update("""
                DELETE FROM merchants m
                WHERE m.id = ? AND m.lifecycle_status = 'TEMPORARY'
                  AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.merchant_id = m.id AND t.deleted_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM merchant_category_learning l WHERE l.merchant_id = m.id)
                """, merchant.merchantId());
        if (deleted > 0) {
            auditService.record(merchant.userId(), "MERCHANT_DISCARDED", "Merchant", merchant.merchantId(), Map.of("actor", ACTOR));
            tally.deleted++;
        } else {
            tally.kept++;
        }
    }

    /**
     * @param rowsDetached     rows unlinked from their merchant (in a dry run: would be)
     * @param merchantsDeleted merchants removed because nothing was left on them
     * @param merchantsKept    merchants that lost rows but keep others, or a learned category
     * @param unchanged        queued merchants left as they were (approved, gone, or no row naming nobody)
     * @param drained          true when this pass emptied the queue
     */
    public record Result(int rowsDetached, int merchantsDeleted, int merchantsKept, int unchanged, boolean drained) {
        int decided() { return merchantsDeleted + merchantsKept + unchanged; }
    }
}
```

Note: a soft-deleted row still pointing at a removed merchant is set to NULL by `transactions.merchant_id ... ON DELETE SET NULL` (V7). The audit row of a deleted merchant keeps its `entity_id`: `audit_logs.entity_id` has no foreign key (checked in its migration on 2026-10-14).

- [ ] **Step 6: Run, expect PASS.** Then prove two guards can fail: (a) change `wouldDelete`/the DELETE's learning clause to ignore learning, run, confirm `aMerchantWithALearnedCategoryLosesItsNobodyRowsButIsKept` fails, restore; (b) change the lifecycle check to accept APPROVED, run, confirm `anApprovedMerchant...` fails, restore.

- [ ] **Step 7: Commit**

```bash
cd /Users/sid/Downloads/finora-nobody-no-merchant
git add backend/src/main/resources/db/migration/V269__merchant_nobody_detach.sql backend/src/main/java/com/finora/service/MerchantNobodyDetachSweepService.java backend/src/test/java/com/finora/service/MerchantNobodyDetachSweepIT.java
git commit -m "feat(transactions): detach existing rows that name nobody from their merchants, dry-run first"
```

---

### Task 6: Measure, verify, hand over

**Files:**
- Create (outside the repo, not committed): a corpus probe in the session scratchpad; production count SQL in `/Users/sid/Library/Application Support/Claude/side-session-notes/local_e51ff498-5123-48c2-8f5b-eaed3ae98e37/`.

- [ ] **Step 1: Corpus, before and after.** Reuse the scratchpad `GroupingPartitionProbe` (it prints `R statement row group` per corpus row) built against `origin/main` and against this branch, changed only so that a row with no merchant prints group `-`. Expected: every line differs only where the after-build prints `-`, and those lines' count is near 165 + 2. Report: rows changed to `-`, rows changed otherwise (expected 0), merchants before and after. Any other changed line is a bug: read it before going on.

- [ ] **Step 2: Production count SQL** (counts only, for the owner to run before `apply`), saved as `nobody-detach-production-counts.sql`:

```sql
-- Merchants V269 will queue, by first word, with their live rows and whether any has a learned
-- category. Counts only.
SELECT split_part(lower(btrim(m.canonical_name)), ' ', 1) AS first_word,
       count(DISTINCT m.id)                                AS merchants,
       count(DISTINCT m.user_id)                           AS users,
       count(t.id)                                         AS live_rows,
       count(DISTINCT m.id) FILTER (WHERE EXISTS (SELECT 1 FROM merchant_category_learning l WHERE l.merchant_id = m.id)) AS merchants_with_learning
FROM merchants m
LEFT JOIN transactions t ON t.merchant_id = m.id AND t.deleted_at IS NULL
WHERE m.lifecycle_status = 'TEMPORARY'
  AND split_part(lower(btrim(m.canonical_name)), ' ', 1)
      IN ('upi', 'imps', 'neft', 'rtgs', 'mob', 'mmt', 'ib', 'ach', 'nach', 'pos', 'atm', 'atw', 'nwd')
GROUP BY 1
ORDER BY 1;
```

Check it on a throwaway Postgres built from the migrations with invented rows (the `sqltest` recipe in the scratchpad) before handing it over.

- [ ] **Step 3: Self-review the whole diff** (`git diff origin/main...HEAD`), then the full verify in the background: `cd /Users/sid/Downloads/finora-nobody-no-merchant/backend && ./mvnw -q verify`. Report totals from `target/surefire-reports`.

- [ ] **Step 4: Merge state by content, rebase if main moved, re-check V269**, then ask the owner before pushing (the repository is private). After the OK: push, `gh pr create` with the 🤖 line in the body and none in commits, `full-ci` label, empty `ci: full run` commit, bind with ccd_pr.

- [ ] **Step 5: Rollout note in the PR:** deploy; read the dry-run line `Merchant nobody detach DRY RUN ...`; compare with the production count; set `APP_MERCHANT_NOBODY_DETACH_SWEEP_MODE=apply`; after `Queue drained.`, set it to `off` or delete it.
