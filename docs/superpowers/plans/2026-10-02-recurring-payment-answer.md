# Ask Once About a Repeating Payment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This repository forbids agent delegation** (global CLAUDE.md and the repo's "No agent delegation
> for implementation verification" rule): execute with superpowers:executing-plans, inline, in one
> session.

**Goal:** When the app spots a repeating payment that is still "Other" or a guessed "Personal Transfer", ask the user once what it is, re-file past payments, and file future ones by a payee-plus-amount user rule.

**Architecture:** A new `CategoryRule.Field.PAYEE` matched against `CategoryRules.extractMerchantLabel(description)`, money going out only, plus optional `amount_min`/`amount_max` bounds on any rule, all inside `RuleEngineService.matches`. `RecurringService` reports a per-group question state; a new `RecurringAnswerService` creates/updates the rule, re-files past rows and lists answered payees whose amount moved out of range. Web and mobile render one shared question component in the existing recurring rows on Dashboard and Insights.

**Tech Stack:** Java 21 / Spring Boot / JPA / Flyway / Postgres (Testcontainers ITs); React + TanStack Query + Vitest (web); React Native + Expo + Jest (mobile); TypeScript admin portal.

**Spec:** `docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md` (approved by Sid 2026-10-02). Read it before starting; this plan argues from it.

## Global Constraints

- Worktree `/Users/sid/Downloads/finora-recurring-answer`, branch `feature/recurring-payment-answer`. Never write to `/Users/sid/Downloads/finora` (shared primary checkout). Use absolute paths for every git/build command.
- `node_modules` symlinks (root, `frontend/`, `mobile/`, `admin-portal/`) point at the primary checkout and are never committed: `git add` named files only.
- Commit messages: no `Co-Authored-By` or any AI attribution. Scope from the commitlint enum (`transactions`, `rules`… use `transactions` when unsure). Header ≤ 100 characters.
- Never `git commit` while a `./mvnw verify` runs in this worktree: the pre-commit hook recompiles `target/classes`.
- Flyway: next free version at build time. V247 is the latest on main as of the spec. Before Task 1, run `git fetch origin && git ls-tree --name-only origin/main backend/src/main/resources/db/migration/ | sort -V | tail -3` and check open PRs (`gh pr list --state open --json number,files`) for the same version. This plan writes `V248`; renumber everywhere if taken.
- Test fixtures are synthetic only: payee names like `SAMPLE LANDLORD`, IFSC `YESB0XXXXXX`, digits `000000`. Never copy a corpus value into code, comments or tests.
- Tolerance everywhere: `t(a) = a × 0.20 + ₹1`, rounded to 2 decimals (same as `RecurringService`'s amount-consistency test).
- PAYEE rules match only when direction is `EXPENSE`; a null direction never matches a PAYEE rule (fails closed). Every other rule field ignores direction.
- Short-list categories, in this order: `Rent`, `Loan EMI`, `Subscriptions`, `Education`, `Insurance`, `Utilities`, `Investments`, then "Something else".
- JS checks run under Node 22: `npx --yes node@22 node_modules/.bin/<tool>`.
- No guessing: every "it works" claim needs a command that ran in this session.

## Review Focus

1. **A rule with only one bound, or a rule whose amount is null** — a half-set range must still bound on the side that is set, and a null amount against any bound must not match (Task 2 tests).
2. **Payee label casing and blanks** — a description whose label is null (no payee, e.g. a reference-only narration) must never match a PAYEE rule, even one with a blank-ish comparison value (Task 2 tests; Task 4 validation rejects a blank comparison value).
3. **Double submit / two tabs answering at once** — exactly one PAYEE rule per user and label, the second request updates it (Task 7 concurrency IT).
4. **Answer "Other" or "Personal Transfer"** — the question must stop, not reappear on every load (Task 6 test).
5. **Old app versions** — `GET /recurring` must keep every existing field with the same meaning and never return a null `label`/`nextEstimate`; new data rides in new fields and a new endpoint only (Task 6 test asserts the existing six fields unchanged).

---

### Task 1: Schema — PAYEE field, amount bounds, one answer per payee

**Files:**
- Create: `backend/src/main/resources/db/migration/V248__category_rule_payee_and_amount_bounds.sql`
- Modify: `backend/src/main/java/com/finora/entity/CategoryRule.java`
- Modify: `backend/src/main/java/com/finora/repository/CategoryRuleRepository.java`
- Test: `backend/src/test/java/com/finora/service/V248CategoryRulePayeeMigrationIT.java`

**Interfaces:**
- Produces: `CategoryRule.Field.PAYEE`; `BigDecimal getAmountMin()/setAmountMin`, `getAmountMax()/setAmountMax`; `CategoryRuleRepository.findUserPayeeRules(UUID userId)`; `CategoryRuleRepository.insertPayeeRuleIfAbsent(UUID id, UUID userId, String label, String category, BigDecimal min, BigDecimal max)` returning `int` rows inserted; `CategoryRuleRepository.findUserPayeeRule(UUID userId, String label)` returning `Optional<CategoryRule>`.

- [ ] **Step 1: Write the failing migration IT** (copy the container/migrate scaffolding from `V247PersonalCareCategoryMigrationIT`, baseline `migrateTo("247")`):

```java
@Test
void addsNullableAmountBounds_andExistingRulesKeepWorking() throws SQLException {
    UUID user = seedUser();
    insertRule(user, "DESCRIPTION", "sample shop", null, null);   // a pre-existing rule
    migrateTo("248");
    assertThat(string("SELECT coalesce(amount_min::text,'null') || '/' || coalesce(amount_max::text,'null') "
            + "FROM category_rules WHERE user_id = ?", user)).isEqualTo("null/null");
}

@Test
void onlyOnePayeeRulePerUserAndLabel_ignoringCase() throws SQLException {
    migrateTo("248");
    UUID user = seedUser();
    insertRule(user, "PAYEE", "sample landlord", "8000.00", "12000.00");
    assertThatThrownBy(() -> insertRule(user, "PAYEE", "SAMPLE LANDLORD", "1.00", "2.00"))
            .isInstanceOf(SQLException.class).hasMessageContaining("uq_category_rules_user_payee");
    // A DESCRIPTION rule with the same text is unaffected by the index.
    assertThatCode(() -> insertRule(user, "DESCRIPTION", "sample landlord", null, null)).doesNotThrowAnyException();
}
```

`insertRule` inserts `(id, user_id, scope='USER', field, operator='EQUALS', comparison_value, action_type='ASSIGN_CATEGORY', action_value='Rent', priority=100, enabled=true, created_at=now(), updated_at=now(), amount_min, amount_max)`; before V248 it must omit the two amount columns (first test passes `null, null` and the helper skips them when the migration is not applied — use two helpers, `insertRuleV247` and `insertRule`).

- [ ] **Step 2: Run it, expect FAIL** (no V248):
`cd /Users/sid/Downloads/finora-recurring-answer/backend && ./mvnw -o verify -Dit.test=V248CategoryRulePayeeMigrationIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`

- [ ] **Step 3: Write the migration**

```sql
-- Payee rules and amount bounds, for the recurring-payment question
-- (docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md).
--   amount_min / amount_max: optional bounds, inclusive, checked in addition to a rule's own
--   condition. Null on every existing rule, so existing rules behave exactly as before.
--   field = 'PAYEE': matched against the payee label (CategoryRules.extractMerchantLabel), money
--   going out only -- enforced in RuleEngineService, not here.
--   uq_category_rules_user_payee: one saved answer per user and payee, so a double submit updates
--   the answer instead of creating a second rule (RecurringAnswerService inserts with ON CONFLICT).
ALTER TABLE category_rules ADD COLUMN amount_min NUMERIC(15, 2);
ALTER TABLE category_rules ADD COLUMN amount_max NUMERIC(15, 2);
CREATE UNIQUE INDEX uq_category_rules_user_payee
    ON category_rules (user_id, lower(comparison_value))
    WHERE field = 'PAYEE' AND scope = 'USER';
```

Check `transactions.amount`'s precision first (`grep -n "amount" backend/src/main/resources/db/migration/V1__*.sql`) and use the same `NUMERIC(p, s)`.

- [ ] **Step 4: Entity and repository**

`CategoryRule`: add `PAYEE` to `Field` (comment: "the payee label, money going out only — see RuleEngineService"), and

```java
@Column(name = "amount_min")
private BigDecimal amountMin;
@Column(name = "amount_max")
private BigDecimal amountMax;
public BigDecimal getAmountMin() { return amountMin; }
public void setAmountMin(BigDecimal amountMin) { this.amountMin = amountMin; }
public BigDecimal getAmountMax() { return amountMax; }
public void setAmountMax(BigDecimal amountMax) { this.amountMax = amountMax; }
```

`CategoryRuleRepository`:

```java
@Query("SELECT r FROM CategoryRule r WHERE r.userId = :userId AND r.scope = com.finora.entity.CategoryRule.Scope.USER "
        + "AND r.field = com.finora.entity.CategoryRule.Field.PAYEE")
List<CategoryRule> findUserPayeeRules(@Param("userId") UUID userId);

@Query("SELECT r FROM CategoryRule r WHERE r.userId = :userId AND r.scope = com.finora.entity.CategoryRule.Scope.USER "
        + "AND r.field = com.finora.entity.CategoryRule.Field.PAYEE AND lower(r.comparisonValue) = lower(:label)")
Optional<CategoryRule> findUserPayeeRule(@Param("userId") UUID userId, @Param("label") String label);

/** Insert-or-nothing against uq_category_rules_user_payee; the caller then loads and updates. */
@Modifying
@Query(value = "INSERT INTO category_rules (id, user_id, scope, field, operator, comparison_value, action_type, "
        + "action_value, priority, enabled, created_at, updated_at, match_count, amount_min, amount_max) "
        + "VALUES (:id, :userId, 'USER', 'PAYEE', 'EQUALS', :label, 'ASSIGN_CATEGORY', :category, 100, true, now(), now(), 0, :min, :max) "
        + "ON CONFLICT (user_id, lower(comparison_value)) WHERE field = 'PAYEE' AND scope = 'USER' DO NOTHING",
        nativeQuery = true)
int insertPayeeRuleIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("label") String label,
                            @Param("category") String category, @Param("min") BigDecimal min, @Param("max") BigDecimal max);
```

Check `match_count`'s and `created_at`'s defaults in V17/V21 and drop columns from the INSERT that have DB defaults only if they do.

- [ ] **Step 5: Run the IT, expect PASS** (same command as Step 2).
- [ ] **Step 6: Commit** — `git add` the four files; message `feat(transactions): add payee rules and amount bounds to category rules`.

---

### Task 2: Rule engine — payee match, amount bounds, direction

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RuleEngineService.java`
- Test: `backend/src/test/java/com/finora/service/RuleEngineServiceTest.java`

**Interfaces:**
- Consumes: Task 1 entity fields.
- Produces: `Optional<RuleMatch> evaluateCategoryRule(List<CategoryRule> rules, String description, BigDecimal amount, String merchantName, String accountType, Transaction.Type direction)` and `Optional<RuleMatch> evaluateCategoryRule(UUID userId, String description, BigDecimal amount, String merchantName, String accountType, Transaction.Type direction)`. The existing five-argument overloads delegate with `direction = null`. `evaluateSideEffectRules` and `evaluate` pass `null`. `testMatch(..., String sampleDirection, BigDecimal amountMin, BigDecimal amountMax)` (Task 4 wires the controller).

- [ ] **Step 1: Failing tests** (build rules with setters, call the `List` overload):

```java
private static CategoryRule payeeRule(String label, String min, String max) {
    CategoryRule r = new CategoryRule();
    r.setScope(CategoryRule.Scope.USER);
    r.setField(CategoryRule.Field.PAYEE);
    r.setOperator(CategoryRule.Operator.EQUALS);
    r.setComparisonValue(label);
    r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
    r.setActionValue("Rent");
    r.setAmountMin(min == null ? null : new BigDecimal(min));
    r.setAmountMax(max == null ? null : new BigDecimal(max));
    return r;
}
private static final String RENT = "UPI-SAMPLE LANDLORD-sample.landlord@okaxis-YESB0XXXXXX-000000000000-RENT";

@Test void payeeRule_matchesTheLabel_ignoringCase_onMoneyGoingOut() {
    assertThat(engine.evaluateCategoryRule(List.of(payeeRule("Sample Landlord", "8000", "12000")),
            RENT, new BigDecimal("10000"), null, null, Transaction.Type.EXPENSE)).isPresent();
}
@Test void payeeRule_neverMatchesMoneyComingIn_orAnUnknownDirection() {
    var rules = List.of(payeeRule("sample landlord", "8000", "12000"));
    assertThat(engine.evaluateCategoryRule(rules, RENT, new BigDecimal("10000"), null, null, Transaction.Type.INCOME)).isEmpty();
    assertThat(engine.evaluateCategoryRule(rules, RENT, new BigDecimal("10000"), null, null, null)).isEmpty();
    assertThat(engine.evaluateCategoryRule(rules, RENT, new BigDecimal("10000"), null, null)).isEmpty();
}
@Test void amountBounds_areInclusive_andOnePaisaPastEitherEndDoesNotMatch() {
    var rules = List.of(payeeRule("sample landlord", "8000.00", "12000.00"));
    for (String inside : List.of("8000.00", "12000.00")) {
        assertThat(engine.evaluateCategoryRule(rules, RENT, new BigDecimal(inside), null, null, Transaction.Type.EXPENSE)).isPresent();
    }
    for (String outside : List.of("7999.99", "12000.01")) {
        assertThat(engine.evaluateCategoryRule(rules, RENT, new BigDecimal(outside), null, null, Transaction.Type.EXPENSE)).isEmpty();
    }
}
@Test void oneBoundOnly_boundsThatSide() {
    assertThat(engine.evaluateCategoryRule(List.of(payeeRule("sample landlord", "8000", null)),
            RENT, new BigDecimal("999999"), null, null, Transaction.Type.EXPENSE)).isPresent();
    assertThat(engine.evaluateCategoryRule(List.of(payeeRule("sample landlord", null, "12000")),
            RENT, new BigDecimal("12000.01"), null, null, Transaction.Type.EXPENSE)).isEmpty();
}
@Test void aNullAmount_neverMatchesABoundedRule() {
    assertThat(engine.evaluateCategoryRule(List.of(payeeRule("sample landlord", "8000", "12000")),
            RENT, null, null, null, Transaction.Type.EXPENSE)).isEmpty();
}
@Test void aNarrationWithNoPayee_neverMatchesAPayeeRule() {
    assertThat(engine.evaluateCategoryRule(List.of(payeeRule("upi", null, null)),
            "UPI/000000000000/UPI", new BigDecimal("10"), null, null, Transaction.Type.EXPENSE)).isEmpty();
}
@Test void boundsAlsoApplyToOtherFields_andOtherFieldsIgnoreDirection() {
    CategoryRule desc = payeeRule("landlord", "8000", "12000");
    desc.setField(CategoryRule.Field.DESCRIPTION);
    desc.setOperator(CategoryRule.Operator.CONTAINS);
    assertThat(engine.evaluateCategoryRule(List.of(desc), RENT, new BigDecimal("10000"), null, null, Transaction.Type.INCOME)).isPresent();
    assertThat(engine.evaluateCategoryRule(List.of(desc), RENT, new BigDecimal("20000"), null, null, Transaction.Type.INCOME)).isEmpty();
}
```

- [ ] **Step 2: Run, expect compile FAIL** — `./mvnw -o test -Dtest=RuleEngineServiceTest -Djacoco.skip=true`.

- [ ] **Step 3: Implement.** In `matches` add a `Transaction.Type direction` parameter and a lazily computed payee label:

```java
private boolean matches(CategoryRule rule, String description, BigDecimal amount, String merchantName,
                        String accountType, Transaction.Type direction) {
    if (!withinBounds(rule, amount)) return false;
    if (rule.getField() == CategoryRule.Field.PAYEE) {
        // Money going out only: the question that creates these rules is only ever asked about
        // outgoing payments, and a landlord's deposit return must not be filed as rent. A caller
        // with no direction gets no match (fails closed).
        if (direction != Transaction.Type.EXPENSE || description == null) return false;
        String payee = CategoryRules.extractMerchantLabel(description);
        return payee != null && rule.getOperator() == CategoryRule.Operator.EQUALS
                && payee.equalsIgnoreCase(rule.getComparisonValue().trim());
    }
    String actual = switch (rule.getField()) {
        case DESCRIPTION -> description;
        case MERCHANT -> merchantName;
        case ACCOUNT_TYPE -> accountType;
        case AMOUNT -> amount != null ? amount.toPlainString() : null;
        case PAYEE -> throw new IllegalStateException("handled above");
    };
    // ... existing operator switch unchanged ...
}

/** Optional bounds on any rule, inclusive. A bounded rule never matches a missing amount. */
private static boolean withinBounds(CategoryRule rule, BigDecimal amount) {
    if (rule.getAmountMin() == null && rule.getAmountMax() == null) return true;
    if (amount == null) return false;
    if (rule.getAmountMin() != null && amount.compareTo(rule.getAmountMin()) < 0) return false;
    return rule.getAmountMax() == null || amount.compareTo(rule.getAmountMax()) <= 0;
}
```

`extractMerchantLabel` is regex-heavy; it only runs for PAYEE rules, which only exist for users who answered the question. Add the six-argument overloads; make the five-argument ones delegate with `null`; pass `null` from `evaluate` and both `evaluateSideEffectRules`. Update the class doc's field list.

- [ ] **Step 4: Run, expect PASS** (whole `RuleEngineServiceTest`, so existing cases prove unchanged behaviour).
- [ ] **Step 5: Commit** — `feat(transactions): match payee rules on outgoing payments within amount bounds`.

---

### Task 3: Categorisation passes the direction to the rule engine

**Files:**
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (the two `evaluateCategoryRule` calls, in `suggest` ~line 229 and the 8-arg `suggestReadOnly` ~line 399)
- Test: `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: Task 2 six-argument `evaluateCategoryRule`.

- [ ] **Step 1: Failing tests.** `ruleEngineService` is a mock in this test class, so assert the direction is passed:

```java
@Test
void suggest_passesTheDirectionToTheRuleEngine() {
    UUID merchantId = UUID.randomUUID();
    when(merchantNormalizationEngine.resolve(eq(userId), anyString())).thenReturn(merchantWithId(merchantId));
    categorizationService.suggest(userId, "UPI-SAMPLE LANDLORD-x", new BigDecimal("10000"), null, Transaction.Type.EXPENSE);
    verify(ruleEngineService).evaluateCategoryRule(eq(userId), anyString(), any(), any(), any(), eq(Transaction.Type.EXPENSE));
}

@Test
void suggestReadOnly_passesTheDirectionToTheRuleEngine() {
    categorizationService.suggestReadOnly(List.of(), userId, "UPI-SAMPLE LANDLORD-x", new BigDecimal("10000"), null,
            null, Transaction.Type.EXPENSE, null);
    verify(ruleEngineService).evaluateCategoryRule(anyList(), anyString(), any(), any(), any(), eq(Transaction.Type.EXPENSE));
}
```

Also stub the six-argument overload wherever this test class stubs the five-argument one (search `evaluateCategoryRule(` in the test file and switch each stub to the six-argument form with `any()` for direction).

- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3:** Change both calls to pass `direction`.
- [ ] **Step 4: Run `CategorizationServiceTest`, `TransactionNormalizerTest`, `RuleEngineServiceTest`, expect PASS.**
- [ ] **Step 5: Commit** — `feat(transactions): give rules the transaction direction during categorisation`.

---

### Task 4: Rules API — bounds in DTOs, validation, rule tester

**Files:**
- Modify: `backend/src/main/java/com/finora/rules/RuleDto.java`, `backend/src/main/java/com/finora/rules/RuleService.java`, `backend/src/main/java/com/finora/controller/AdminRuleController.java`, `backend/src/main/java/com/finora/service/RuleEngineService.java` (`testMatch`)
- Test: `backend/src/test/java/com/finora/service/RuleServiceTest.java`, `RuleEngineServiceTest.java`

**Interfaces:**
- Produces: `RuleDto` gains `BigDecimal amountMin, BigDecimal amountMax` (appended after `lastMatchedAt`); `CreateRequest`/`UpdateRequest` gain optional `BigDecimal amountMin, BigDecimal amountMax`; `TestRequest` gains optional `String sampleDirection, BigDecimal amountMin, BigDecimal amountMax`.

- [ ] **Step 1: Failing tests** in `RuleServiceTest`:
  - creating a PAYEE rule with operator `CONTAINS` → `ApiException` 400 "A payee rule must use EQUALS";
  - `amountMin` greater than `amountMax` → 400 "Minimum amount is above maximum";
  - a negative bound → 400 "Amount bounds cannot be negative";
  - a valid PAYEE rule with bounds round-trips both bounds through `RuleDto.from`.
  In `RuleEngineServiceTest`: `testMatch("PAYEE","EQUALS","sample landlord", RENT, new BigDecimal("10000"), null, null, null, new BigDecimal("8000"), new BigDecimal("12000"))` is true — the tester defaults a missing direction to `EXPENSE`, because its samples are spending examples; `"INCOME"` gives false.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** In `create`: `rule.setAmountMin(req.amountMin()); rule.setAmountMax(req.amountMax());`. In `update`: `if (req.amountMin() != null) rule.setAmountMin(req.amountMin()); if (req.amountMax() != null) rule.setAmountMax(req.amountMax());` (no "clear" — out of scope). Append to `validateRule`:

```java
if (rule.getField() == CategoryRule.Field.PAYEE && rule.getOperator() != CategoryRule.Operator.EQUALS) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "A payee rule must use EQUALS.");
}
if ((rule.getAmountMin() != null && rule.getAmountMin().signum() < 0)
        || (rule.getAmountMax() != null && rule.getAmountMax().signum() < 0)) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "Amount bounds cannot be negative.");
}
if (rule.getAmountMin() != null && rule.getAmountMax() != null
        && rule.getAmountMin().compareTo(rule.getAmountMax()) > 0) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "Minimum amount is above maximum.");
}
```

`RuleDto.from` passes `r.getAmountMin(), r.getAmountMax()`. `testMatch`:

```java
public boolean testMatch(String field, String operator, String comparisonValue, String description,
                         BigDecimal amount, String merchantName, String accountType,
                         String sampleDirection, BigDecimal amountMin, BigDecimal amountMax) {
    CategoryRule probe = new CategoryRule();
    probe.setField(parseField(field));
    probe.setOperator(parseOperator(operator));
    probe.setComparisonValue(comparisonValue);
    probe.setAmountMin(amountMin);
    probe.setAmountMax(amountMax);
    // The tester's samples are spending examples, so a missing direction means money going out.
    Transaction.Type direction = sampleDirection == null ? Transaction.Type.EXPENSE
            : EnumParsing.parse(Transaction.Type.class, sampleDirection, "sampleDirection");
    return matches(probe, description, amount, merchantName, accountType, direction);
}
```

Keep the old seven-argument `testMatch` delegating with `null, null, null` so its existing tests stay. `AdminRuleController.test` passes `request.sampleDirection(), request.amountMin(), request.amountMax()`.
- [ ] **Step 4: Run both test classes, expect PASS.**
- [ ] **Step 5: Commit** — `feat(transactions): expose rule amount bounds and payee rules in the rules API`.

---

### Task 5: Share the investment recount

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java` (`reconcileIfInvestmentExclusionMayChange`, ~line 1369, and its three callers)
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java`
- Test: `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java`

**Interfaces:**
- Produces: `public void ReconciliationService.reconcileIfInvestmentExclusionMayChange(UUID userId, List<Transaction> edited, Category newCategory)` — same body as today's private method, calling `this.reconcileForUser(userId)`.

- [ ] **Step 1: Failing test** in `ReconciliationServiceTest` (use `Mockito.spy` on the service under test the way that class builds it):

```java
@Test
void recountRunsWhenARowMovesIntoInvestments_andNotBetweenTwoOrdinaryCategories() {
    ReconciliationService spy = spy(reconciliationService);
    doNothing().when(spy).reconcileForUser(userId);
    Transaction row = new Transaction();            // reconciliationStatus defaults to not INVESTMENT_TRANSFER
    Category investments = new Category(); investments.setName("Investments");
    Category dining = new Category(); dining.setName("Dining");

    spy.reconcileIfInvestmentExclusionMayChange(userId, List.of(row), investments);
    spy.reconcileIfInvestmentExclusionMayChange(userId, List.of(row), dining);

    verify(spy, times(1)).reconcileForUser(userId);
}
```
- [ ] **Step 2: Run, expect FAIL** (method missing).
- [ ] **Step 3:** Move the method (keep its doc comment), delete the private copy, call the shared one from `TransactionService`'s three sites.
- [ ] **Step 4: Run `ReconciliationServiceTest` and `TransactionServiceTest`, expect PASS.**
- [ ] **Step 5: Commit** — `refactor(transactions): share the investment recount after a category change`.

---

### Task 6: Recurring list reports the question state

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/RecurringDto.java`, `backend/src/main/java/com/finora/service/RecurringService.java`
- Modify: `backend/src/test/java/com/finora/service/RecurringServiceTest.java`, `backend/src/test/java/com/finora/service/ReconciliationScalingBenchmark.java` (constructor only)

**Interfaces:**
- Consumes: Task 1 `findUserPayeeRules`.
- Produces:

```java
public record RecurringDto(
        String merchant, String label, BigDecimal averageAmount, int occurrences,
        LocalDate lastDate, LocalDate nextEstimate,
        String category,          // most common category among the group's rows; null if none
        BigDecimal latestAmount,  // amount of the most recent payment
        String answer,            // the saved answer's category, or null
        QuestionState state) {
    public enum QuestionState { NEEDS_ANSWER, ANSWERED, AMOUNT_CHANGED, NONE }
    /** The six original fields, for callers and tests that predate the question. */
    public RecurringDto(String merchant, String label, BigDecimal averageAmount, int occurrences,
                        LocalDate lastDate, LocalDate nextEstimate) {
        this(merchant, label, averageAmount, occurrences, lastDate, nextEstimate, null, null, null, QuestionState.NONE);
    }
}
```

`RecurringService` constructor gains `CategoryRuleRepository categoryRuleRepository, CategoryRepository categoryRepository` (appended).

- [ ] **Step 1: Failing tests** in `RecurringServiceTest` (stub `categoryRepository.findByUserId(userId)` with categories "Other", "Personal Transfer", "Rent", "Entertainment" and `categoryRuleRepository.findUserPayeeRules(userId)`); three monthly payments of 10,000 to `SAMPLE LANDLORD`:
  - all three "Other" (`MERCHANT_DEFAULT`), no rule → `NEEDS_ANSWER`;
  - all three "Personal Transfer" with `DecisionSource.STRUCTURAL_P2P` → `NEEDS_ANSWER`;
  - one of them `categoryManuallySet` → `NONE`;
  - all three "Entertainment" via keyword → `NONE`;
  - a PAYEE rule for `sample landlord` with bounds 8,000–12,000 and answer "Rent" → `ANSWERED`, `answer = "Rent"`;
  - the same rule with answer **"Other"** and all rows "Other" → `ANSWERED` (question stops, Review Focus 4);
  - rule bounds 8,000–9,000 → `AMOUNT_CHANGED`, `latestAmount = 10000.00`;
  - `averageAmount`, `occurrences`, `lastDate`, `nextEstimate`, `label`, `merchant` are exactly what they were before this change for the same input (Review Focus 5 — compare against values computed by the pre-change code path in the same test).
  - A dismissal still writes no question-related state; dismissing records an audit `RECURRING_DISMISSED` with `merchant`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** In `detectForUser`, load `Map<UUID,String> categoryNames` once from `categoryRepository.findByUserId(userId)` and `Map<String, CategoryRule> answers` keyed by `comparisonValue.trim().toLowerCase(Locale.ROOT)` once from `findUserPayeeRules(userId)`. For each detected group:

```java
private static boolean isUnconfirmedGuess(Transaction t, Map<UUID, String> names) {
    if (t.isCategoryManuallySet()) return false;
    String name = names.get(t.getCategoryId());
    if ("Other".equals(name)) return true;
    return CategorizationService.P2P_CATEGORY.equals(name)
            && t.getDecisionSource() == Transaction.DecisionSource.STRUCTURAL_P2P;
}

CategoryRule answer = answers.get(entry.getKey().trim().toLowerCase(Locale.ROOT));
Transaction latest = group.get(group.size() - 1);           // group is sorted by date above
QuestionState state;
if (answer != null) {
    state = inBounds(answer, latest.getAmount()) ? QuestionState.ANSWERED : QuestionState.AMOUNT_CHANGED;
} else if (group.stream().allMatch(t -> isUnconfirmedGuess(t, categoryNames))) {
    state = QuestionState.NEEDS_ANSWER;
} else {
    state = QuestionState.NONE;
}
String category = group.stream().map(t -> categoryNames.get(t.getCategoryId())).filter(Objects::nonNull)
        .collect(Collectors.groupingBy(n -> n, LinkedHashMap::new, Collectors.counting()))
        .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
```

`inBounds` mirrors `RuleEngineService.withinBounds` (null bounds = unbounded). In `dismiss`, add `auditService.record(userId, "RECURRING_DISMISSED", "Transaction", null, Map.of("merchant", merchant));`. Update both constructor call sites in tests (`mock(CategoryRuleRepository.class), mock(CategoryRepository.class)` in the benchmark).
- [ ] **Step 4: Run `RecurringServiceTest`, expect PASS.**
- [ ] **Step 5: Commit** — `feat(transactions): report the question state on recurring payments`.

---

### Task 7: Answering — rule, re-filing, changed amounts

**Files:**
- Create: `backend/src/main/java/com/finora/service/RecurringAnswerService.java`
- Create: `backend/src/main/java/com/finora/dto/CategorizeRecurringRequest.java`, `backend/src/main/java/com/finora/dto/ChangedAmountDto.java`
- Modify: `backend/src/main/java/com/finora/controller/RecurringController.java`
- Test: `backend/src/test/java/com/finora/service/RecurringAnswerServiceTest.java` (unit), `backend/src/test/java/com/finora/service/RecurringAnswerIT.java` (Postgres)

**Interfaces:**
- Consumes: Tasks 1, 2, 5, 6.
- Produces:
  - `record CategorizeRecurringRequest(@NotBlank @Size(max = 255) String merchant, @NotBlank @Size(max = 100) String category)`
  - `record ChangedAmountDto(String merchant, String category, BigDecimal latestAmount, LocalDate latestDate, BigDecimal amountMin, BigDecimal amountMax)`
  - `RecurringAnswerService.categorize(UUID userId, String merchant, String category)` returning `RecurringAnswerService.Result(int refiled, String kind)`; `List<ChangedAmountDto> changedAmounts(UUID userId)`
  - `POST /api/v1/recurring/categorize` → `ApiResponse<Void>` message "Saved"; `GET /api/v1/recurring/changed-amounts` → `ApiResponse<List<ChangedAmountDto>>`.

- [ ] **Step 1: Failing unit tests** (`RecurringAnswerServiceTest`, mocks for repositories, `RecurringService`, `CategorizationService`, `ReconciliationService`, `AuditService`, `FeatureFlagService`):
  1. flag off → `ApiException` 404, nothing written;
  2. no detected group and no saved answer for the label → 404;
  3. first answer on a group of 10,000 ×3: `insertPayeeRuleIfAbsent(_, userId, "sample landlord", "Rent", 7999.00, 12001.00)` (10,000 ± (2,000 + 1)), audit `RECURRING_ANSWERED` with `kind = FIRST`;
  4. range widened to cover every group payment: amounts 9,000 / 10,000 / 11,500 (avg 10,166.67, t = 2,034.33) → min `8132.34`, max `12201.00`;
  5. re-files only rows that are EXPENSE, same label ignoring case, amount in range, not `categoryManuallySet` — each gets the category id, `DecisionSource.USER_RULE`, the rule id, `ConfidenceEngine.INITIAL_RULE_CONFIDENCE`, `needsCategoryReview = false`, and is saved; a manual row, an INCOME row, an out-of-range row and another payee's row are untouched; `queueLearning` and `sharedCorpusService` are never called;
  6. category "Investments" → `reconciliationService.reconcileIfInvestmentExclusionMayChange` called with the re-filed rows;
  7. existing answer "Rent", new answer "Rent", latest payment 12,500 outside 8,000–12,000, payee not detected any more → range becomes min 8,000, max 15,001 (12,500 + 2,501), `kind = STILL`;
  8. existing "Rent", new "Loan EMI" → `kind = CHANGE`, rule's `actionValue` updated;
  9. `recurringService.confirm(userId, merchant)` called on success;
  10. `changedAmounts`: a saved answer whose payee is in the detected list is never returned; one whose latest EXPENSE payment is outside the range is returned with its bounds; one inside the range is not.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** `RecurringAnswerService`:

```java
@Service
public class RecurringAnswerService {

    /** Same tolerance as RecurringService's amount-consistency test: 20% of the amount plus ₹1. */
    static BigDecimal tolerance(BigDecimal a) {
        return a.multiply(new BigDecimal("0.20")).add(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);
    }

    /** An inclusive amount range; null on either side means unbounded on that side. */
    record Range(BigDecimal min, BigDecimal max) {
        static Range around(BigDecimal a, BigDecimal t) {
            BigDecimal center = a.setScale(2, RoundingMode.HALF_UP);
            return new Range(center.subtract(t), center.add(t));
        }
        Range cover(BigDecimal a) { return union(new Range(a, a)); }
        Range union(Range o) {
            BigDecimal lo = min == null || o.min == null ? null : min.min(o.min);
            BigDecimal hi = max == null || o.max == null ? null : max.max(o.max);
            return new Range(lo, hi);
        }
        boolean contains(BigDecimal a) {
            return a != null && (min == null || a.compareTo(min) >= 0) && (max == null || a.compareTo(max) <= 0);
        }
    }

    public record Result(int refiled, String kind) {}

    // constructor injects: RecurringService, CategoryRuleRepository, CategorizationService,
    // TransactionRepository, AccountRepository, ReconciliationService, AuditService, FeatureFlagService

    @Transactional
    public Result categorize(UUID userId, String merchant, String categoryName) {
        if (!featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")) throw notFound();
        String label = merchant.trim();
        Optional<RecurringDto> group = recurringService.detectForUser(userId).stream()
                .filter(r -> r.merchant().equalsIgnoreCase(label)).findFirst();
        Optional<CategoryRule> existing = categoryRuleRepository.findUserPayeeRule(userId, label);
        if (group.isEmpty() && existing.isEmpty()) throw notFound();

        List<Transaction> payeeRows = payeeExpenseRows(userId, label); // sorted by date, oldest first

        Range range = null;
        if (group.isPresent()) {
            range = Range.around(group.get().averageAmount(), tolerance(group.get().averageAmount()));
            // The rows RecurringService grouped: same label, EXPENSE, not a transfer, not a duplicate.
            for (Transaction t : payeeRows) {
                if (!t.isTransfer() && t.getIsDuplicateOf() == null) range = range.cover(t.getAmount());
            }
        }
        if (existing.isPresent()) {
            Range old = new Range(existing.get().getAmountMin(), existing.get().getAmountMax());
            range = range == null ? old : range.union(old);
            if (!payeeRows.isEmpty()) {
                BigDecimal latest = payeeRows.get(payeeRows.size() - 1).getAmount();
                range = range.union(Range.around(latest, tolerance(latest)));
            }
        }

        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        String kind = existing.isEmpty() ? "FIRST"
                : existing.get().getActionValue().equalsIgnoreCase(category.getName()) ? "STILL" : "CHANGE";
        categoryRuleRepository.insertPayeeRuleIfAbsent(UUID.randomUUID(), userId, label, category.getName(),
                range.min(), range.max());
        CategoryRule rule = categoryRuleRepository.findUserPayeeRule(userId, label).orElseThrow();
        rule.setActionValue(category.getName());
        rule.setAmountMin(range.min());
        rule.setAmountMax(range.max());
        rule.setEnabled(true);
        rule.setUpdatedAt(Instant.now());
        categoryRuleRepository.save(rule);

        List<Transaction> refiled = new ArrayList<>();
        for (Transaction t : payeeRows) {
            if (t.isCategoryManuallySet() || !range.contains(t.getAmount())) continue;
            t.setCategoryId(category.getId());
            t.setDecisionSource(Transaction.DecisionSource.USER_RULE);
            t.setDecisionRuleId(rule.getId());
            t.setDecisionConfidence(ConfidenceEngine.INITIAL_RULE_CONFIDENCE);
            t.setNeedsCategoryReview(false);
            refiled.add(t);
        }
        transactionRepository.saveAll(refiled);   // entity saves: version bumps, mobile change stamp moves
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, refiled, category);
        auditService.record(userId, "RECURRING_ANSWERED", "CategoryRule", rule.getId(),
                Map.of("merchant", label, "category", category.getName(), "kind", kind, "refiled", refiled.size()));
        recurringService.confirm(userId, label);
        return new Result(refiled.size(), kind);
    }

    /** The user's live-account EXPENSE rows whose payee label is {@code label}, oldest first. */
    private List<Transaction> payeeExpenseRows(UUID userId, String label) {
        List<UUID> live = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (live.isEmpty()) return List.of();
        return transactionRepository.findByUserIdAndAccountIdIn(userId, live).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && t.getMerchant() != null
                        && t.getMerchant().equalsIgnoreCase(label))
                .sorted(Comparator.comparing(Transaction::getTxnDate))
                .toList();
    }

    public List<ChangedAmountDto> changedAmounts(UUID userId) {
        if (!featureFlagService.isEnabled("RECURRING_DETECTION_ENABLED")) return List.of();
        Set<String> detected = recurringService.detectForUser(userId).stream()
                .map(r -> r.merchant().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<ChangedAmountDto> out = new ArrayList<>();
        for (CategoryRule rule : categoryRuleRepository.findUserPayeeRules(userId)) {
            String label = rule.getComparisonValue().trim();
            if (detected.contains(label.toLowerCase(Locale.ROOT))) continue;
            List<Transaction> rows = payeeExpenseRows(userId, label);
            if (rows.isEmpty()) continue;
            Transaction latest = rows.get(rows.size() - 1);
            if (new Range(rule.getAmountMin(), rule.getAmountMax()).contains(latest.getAmount())) continue;
            out.add(new ChangedAmountDto(label, rule.getActionValue(), latest.getAmount(), latest.getTxnDate(),
                    rule.getAmountMin(), rule.getAmountMax()));
        }
        return out;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "No recurring payment found for that payee.");
    }
}
```

`changedAmounts` loads the payee rows once per saved answer; a user has a handful of answers, but if a test shows more than a few, load the live rows once and group them by lower-cased label instead. `RecurringDto.averageAmount` comes back at scale 4; `Range.around` rounds the centre to 2 decimals, which is what makes test 4's expected bounds exact. The controller:

```java
@PostMapping("/categorize")
public ApiResponse<Void> categorize(@Valid @RequestBody CategorizeRecurringRequest request) {
    recurringAnswerService.categorize(currentUser.id(), request.merchant(), request.category());
    return ApiResponse.ok(null, "Saved");
}

@GetMapping("/changed-amounts")
public ApiResponse<List<ChangedAmountDto>> changedAmounts() {
    return ApiResponse.ok(recurringAnswerService.changedAmounts(currentUser.id()));
}
```

- [ ] **Step 4: Run unit tests, expect PASS.**
- [ ] **Step 5: Failing ITs** (`RecurringAnswerIT extends AbstractIntegrationTest`, real Postgres, feature flag enabled the way `RecurringService`'s ITs enable it):
  - **end to end:** a user with three confirmed monthly "Other" payments of 10,000 to `UPI-SAMPLE LANDLORD-…`; `categorize(user, "sample landlord", "Rent")`; then `importService.parseAndStageAnyFormat` (or `TransactionNormalizer` through the staging path the existing CSV ITs use) on a synthetic CSV with one 10,000 payment and one 25,000 payment to the same payee, plus one 10,000 **credit** from it → the 10,000 debit stages as "Rent" with source `user_rule`, the 25,000 debit and the credit do not;
  - **double submit:** two threads call `categorize` for the same user and label at once (`CountDownLatch`, separate transactions) → exactly one row in `category_rules` for that label, both calls succeed;
  - **re-filing visible to mobile:** a re-filed row's `version` increased.
- [ ] **Step 6: Run the ITs, expect FAIL then implement any missing piece, then PASS.** Command: `./mvnw -o verify -Dit.test='RecurringAnswerIT' -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- [ ] **Step 7: Commit** — `feat(transactions): answer a repeating payment once and file it by payee and amount`.

---

### Task 8: API contract and admin portal

**Files:**
- Modify: `backend/openapi/openapi.json` (regenerated), `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts` (regenerated)
- Modify: `admin-portal/src/types/index.ts` (`RuleDto`, `CreateRuleRequest` + optional `amountMin?: number | null; amountMax?: number | null`), `admin-portal/src/pages/GlobalRules.tsx` and `admin-portal/src/pages/user-detail/RulesSection.tsx` (add `'PAYEE'` to the field lists; in each rule row and in the edit form, show `₹min – ₹max` read-only when either bound is set)
- Test: the admin pages' existing test files (`grep -l RulesSection admin-portal/src -r --include=*.test.tsx`), adding: a rule with bounds renders its range; `PAYEE` is offered as a field.

- [ ] **Step 1:** `cd /Users/sid/Downloads/finora-recurring-answer/backend && ./scripts/generate-openapi-spec.sh` (read its header first; it boots the app), then in `frontend/` and `mobile/`: `npm run generate:types` (under Node 22 if it shells to node). Check `git diff --stat` touches only the three generated files.
- [ ] **Step 2:** Admin tests first (expect FAIL), then the changes, then PASS: `cd admin-portal && npx --yes node@22 node_modules/.bin/vitest run <files>`; `tsc --noEmit`; eslint on changed files.
- [ ] **Step 3: Commit** — `feat(transactions): publish payee rules and amount bounds to the API and admin portal`.

---

### Task 9: Web — the question on the recurring rows

**Files:**
- Create: `frontend/src/components/RecurringQuestion.tsx`, `frontend/src/components/RecurringQuestion.test.tsx`
- Modify: `frontend/src/api/endpoints.ts` (`RecurringItem` gains `category: string | null; latestAmount: number | null; answer: string | null; state: 'NEEDS_ANSWER' | 'ANSWERED' | 'AMOUNT_CHANGED' | 'NONE'`; `recurringApi.categorize(merchant, category)`; `recurringApi.changedAmounts()`; `interface ChangedAmountItem { merchant: string; category: string; latestAmount: number; latestDate: string; amountMin: number | null; amountMax: number | null }`)
- Modify: `frontend/src/pages/Dashboard.tsx` (recurring list, ~line 1571), `frontend/src/pages/Insights.tsx` (~line 294)

**Interfaces:**
- Produces: `export const SHORT_LIST = ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments'] as const;` and

```tsx
export interface RecurringQuestionProps {
  merchant: string;
  state: 'NEEDS_ANSWER' | 'ANSWERED' | 'AMOUNT_CHANGED' | 'NONE';
  answer: string | null;
  amount: number;            // averageAmount for NEEDS_ANSWER, latestAmount otherwise
}
export function RecurringQuestion(props: RecurringQuestionProps): JSX.Element | null
```

- [ ] **Step 1: Failing tests** (mock `recurringApi` and `categoriesApi`; render in a `QueryClientProvider`):
  - `NEEDS_ANSWER` shows "What is this ₹10,000 monthly payment?" and a button per short-list category the user has; with categories lacking "Rent", no Rent button;
  - clicking "Rent" calls `recurringApi.categorize('sample landlord', 'Rent')` once and, on success, invalidates `['recurring']` and the money-figure keys (`useInvalidateMoneyFigures`);
  - "Something else" reveals a `CategoryCombobox`; choosing a category submits it;
  - `ANSWERED` renders "Rent · Change"; Change shows the chips again;
  - `AMOUNT_CHANGED` renders "₹12,500 to sample landlord — still Rent?" with Yes (submits "Rent") and Change;
  - `NONE` renders nothing;
  - while the request is pending the buttons are disabled (no double submit from one click).
- [ ] **Step 2: Run, expect FAIL** — `cd frontend && npx --yes node@22 node_modules/.bin/vitest run src/components/RecurringQuestion.test.tsx`.
- [ ] **Step 3: Implement** the component (buttons are real `<button type="button">` with `aria-label`s; amounts via the page's existing `fmt` helper or `Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })` — match what Dashboard uses). Wire it under each recurring row on Dashboard and Insights (pass `amount = state === 'NEEDS_ANSWER' ? r.averageAmount : (r.latestAmount ?? r.averageAmount)`), and render `recurringApi.changedAmounts()` items (query key `['recurring-changed-amounts']`, invalidated with `['recurring']`) as extra rows in the same card with `state = 'AMOUNT_CHANGED'`.
- [ ] **Step 4: Run** the new test plus `src/pages/Dashboard.test.tsx` and `src/pages/Insights.test.tsx`; `tsc -b`; eslint on changed files. Expect PASS.
- [ ] **Step 5: Commit** — `feat(transactions): ask what a repeating payment is on the web recurring card`.

---

### Task 10: Mobile — the same question

**Files:**
- Create: `mobile/src/components/RecurringQuestion.tsx`, `mobile/src/components/RecurringQuestion.test.tsx`
- Modify: `mobile/src/api/endpoints.ts` (same type and API additions as web), `mobile/src/screens/DashboardScreen.tsx` (~line 740), `mobile/src/screens/InsightsScreen.tsx` (~line 598), `mobile/src/lib/invalidateFinancialData.ts` (add `'recurring-changed-amounts'` to `FINANCIAL_QUERY_KEYS`)

**Interfaces:**
- Same props as web. "Something else" opens `CategoryPickerModal` (`visible`, `selectedName`, `onSelect(category)`, `onClose`, `allowManage={false}`).

- [ ] **Step 1: Failing tests** — same cases as Task 9, with React Native Testing Library. Use `.not.toBeOnTheScreen()` (never `waitFor(() => expect(...).toBeNull())`: it pretty-prints the fiber tree on every poll and starves timers — see memory `waitfor-tobenull-fiber-format-blocks-timers`). Chips meet the 44pt touch target (`minHeight: 44`).
- [ ] **Step 2: Run, expect FAIL** — `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 node_modules/.bin/jest src/components/RecurringQuestion.test.tsx`.
- [ ] **Step 3: Implement**; on success call `invalidateFinancialData(queryClient)` (covers `recurring` and transaction keys through the gated change-sync client). Wire into both screens and render changed-amount rows as on web.
- [ ] **Step 4: Run** the new test plus `DashboardScreen.test.tsx`, `InsightsScreen.test.tsx`, `invalidateFinancialData.test.ts`; `tsc --noEmit`; eslint on changed files. Expect PASS.
- [ ] **Step 5: Commit** — `feat(transactions): ask what a repeating payment is on the mobile recurring card`.

---

### Task 11: Verification, corpus check, PR

**Files:** none committed (local probe only).

- [ ] **Step 1: Full backend** — `cd /Users/sid/Downloads/finora-recurring-answer/backend && ./mvnw -o verify` (≈15 min; do not commit while it runs). Expect `BUILD SUCCESS`, report the unit and IT totals.
- [ ] **Step 2: Corpus, no answers.** Copy the local probe pattern from `categorisation-p2-pr1876.md` (stage every corpus file for a fresh user with default categories seeded; write file, date, type, amount, category, source, description). Run on a detached `origin/main` worktree and on this branch; compare row by row. Expected: **zero** changed rows. Any change is a bug — read it before theorising.
- [ ] **Step 3: Corpus, one simulated answer.** In the branch probe, for the one corpus statement with an "Other" recurring group, confirm its rows, call `categorize` on that group with "Loan EMI", re-stage the statement, and list every row whose category changed. Expected: only that payee's in-range debits. Read each one; describe, never quote, in the PR.
- [ ] **Step 4: Hygiene** — `sh scripts/check-fixture-hygiene.sh --each origin/main HEAD`; `python3 scripts/check-corpus-leakage.py "/Users/sid/Downloads/Bank statement/Savings accounts"` and `… "Credit cards"` (generate `backend/target/corpus-classpath.txt` first if missing). All clean.
- [ ] **Step 5: Self-review the whole diff** for bugs and gaps against the spec's sections 1–4, fixes 1–7 and the Review Focus list; fix and re-run what is touched.
- [ ] **Step 6: Merge state** — `git fetch origin && git log --oneline origin/main..HEAD`; re-check the migration version is still free; rebase or sync only through a fresh branch if main moved a migration.
- [ ] **Step 7: Push and PR** — `git push -u origin feature/recurring-payment-answer`; `gh pr create` with a body that ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`; bind with `mcp__ccd_pr__bind_pr`; do not poll CI.
- [ ] **Step 8: After merge** — `gh pr view <n> --json state,headRefOid` equals the branch tip; every changed file matches `origin/main`; remove the worktree; update memory.
