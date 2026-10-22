# AI Insights Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Sid's rule for this repo: inline execution, no subagents.**

**Goal:** Build the shared AI-insights pipeline and three uses on top of it:
- **The month story**, on mobile and in the website's existing slot.
- **"Why?"** on running-high rows (mobile).
- **AI ranking of "To look at"**, built but switched off.

Every number and name is rendered by the server, and the AI only chooses facts and words the sentences
around them.

**Architecture:**
- New server classes in `com.finora.service`, next to the existing `Fyn*` classes, because they need
  `InsightsService`'s package-private `Pipeline`.
- **Fact sheets** are built from existing services through typed allowlist records.
- **The model** returns JSON templates with fact placeholders. A deterministic checker (§5.2) and a
  renderer turn them into text.
- **A text table** gives single-flight caching, and a usage table gives an atomic Free allowance.
- **The "To look at" list** is ported from mobile to the server.
- **Mobile 1.1.0 and the website** call the new endpoints. Mobile 1.0.0 is untouched.

**Tech Stack:** Spring Boot 3 / Java 21 / Postgres (Flyway) / JUnit 5 + Mockito + Testcontainers;
React Native (Expo 57) + React Query + jest/RNTL; React + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md` (approved by Sid 2026-10-22).
The spec's section numbers (§) are cited throughout. Read the spec first.

## Global Constraints

- **Commits:** never add a `Co-Authored-By` or any AI-attribution line. The commitlint subject is lower
  case, and the scope comes from the repo list (`backend`, `mobile`, `web`, `frontend`, `db`, `docs`, …).
- **Worktrees only.** The primary checkout `/Users/sid/Downloads/finora` is read-only.
  - PR 1 is built on this worktree's branch.
  - PR 2 and PR 3 each get a FRESH worktree from `origin/main`, after the previous PR merges. Main is
    squash-merged, so a merged branch is never reused.
- **Bash in a worktree session:** one plain command per call.
- **Node 22 for JS checks.** The worktree guard refuses `$(...)` forms. So:
  1. Run `npx --yes node@22 -p "process.execPath"` once and note the directory it prints (`<node22dir>`).
  2. Run tsc, eslint, vitest, `npm run …` and `vite build` with `PATH=<node22dir>:$PATH` written literally.
  3. Run mobile jest as `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js --forceExit <pattern>`.

  Wherever a task below says "with Node 22", it means this. Run root `npm ci` once per fresh worktree so
  the commit hook works.
- **Synthetic data only** in tests and docs: `vpa:flatmate.a`, `100000000001`, "Friend K",
  `user.sample@okbank`. No real names, ids or amounts, including in comments.
- **Migration number:** the next free number after the highest in `origin/main`
  `backend/src/main/resources/db/migration`. It is V282 at plan time; re-check with `git fetch` and
  `git ls-tree` before creating it. Never edit an existing migration.
- **Never sent to the model (§4.1):** payee names (companies included), people's names, UPI ids, account
  names or numbers, card last four, narrations, notes, transaction ids, and the names of user-created
  categories.
- **Mobile releases:** mobile changes ship in the 1.1.0 binary. Never OTA mobile JS to 1.0.0.
- **Free taster:** the story for the newest reporting month only; 5 "Why?" a calendar month in the user's
  timezone; no ranking. Plus and Premium have no count limit. The plan codes "PLUS" and "PREMIUM" are
  uncapped; any other code, or none, counts as Free.
- **Defaults (§6, §7.1):**
  - `FYN_FREE_MONTHLY_BUDGET_USD=10`
  - `FYN_INSIGHTS_AUDIENCE=internal`
  - `FYN_INSIGHTS_AI_RANKING=false`
  - at most 4 insight model calls at once
  - a PENDING row is stale after 60 s
  - FAILED cooldown 10 min
  - a story is rewritten at most once a day; Free stories at most 4 per calendar month
- **Exact copy:**
  - Free limit line (§6): "You've used this month's 5 AI explanations. Plus explains every row."
  - Pricing line, Free: "AI month story and 5 AI explanations a month"
  - Pricing line, Plus: "AI insights with no monthly limit"
  - Label: "Written by AI"

## Review Focus

1. **A user's first month, or a category with fewer than 2 baseline months.** The story has no comparison
   facts, and "Why?" isn't offered on that row. Test owner: Task 4 (sheet) and Task 9 (why not offered).
2. **A Free user picking an older month in the month picker.** No model call, `canGenerate=false`, and the
   app keeps its month line. Test owner: Task 9.
3. **A Free allowance at a month boundary in the user's timezone.** For example, 00:30 IST on the 1st,
   which is still the previous month in UTC. The use counts in the new local month. Test owner: Task 1
   (repository) and Task 9.
4. **Valid JSON with 0 sentences or more than 4 sentences.** Rules text, reason `SCHEMA_MISMATCH`. Test
   owner: Task 7.
5. **The stored order versus the current rows.** A row marked "Got it" after the order was stored is
   dropped. A row that is new since then is appended in rules order. Test owner: Task 9.

---

# PR 1 — Server (branch `worktree-ai-insights-foundation`, which already holds the spec commits)

## File map (PR 1)

| File | Responsibility |
|---|---|
| `db/migration/V282__ai_insight_texts.sql` | the text table, the usage table, `ai_audit_log` columns, the `FYN_INSIGHTS` grant |
| `entity/AiInsightText.java`, `entity/AiInsightUsage.java` | entities |
| `repository/AiInsightTextRepository.java`, `repository/AiInsightUsageRepository.java` | single-flight insert, atomic usage |
| `service/AiInsightReason.java` | the reason-code enum (§5.5) |
| `service/AiInsightFacts.java` | `Fact`, `Subject`, `FactSheet`, hashing, display forms, category tokens |
| `service/AiInsightMonthSheetBuilder.java` | the month sheet (§4.2) |
| `service/AiInsightRowSheetBuilder.java` | the row sheet (§4.2) |
| `service/LookAtService.java`, `dto/LookAtRowDto.java` | the "To look at" list ported from mobile (§8.2) |
| `service/AiInsightTemplate.java` | parses model JSON into templates |
| `service/AiInsightChecker.java` | checks 1–10 (§5.2), and the word lists |
| `service/AiInsightRenderer.java` | template → text; rules text (§5.3) |
| `service/AiInsightGenerator.java` | prompts, model call, concurrency bound, audit row |
| `service/AiInsightService.java` | story, why, order: audience, plan, budgets, single-flight, limits |
| `controller/AiInsightController.java`, `dto/AiInsightDtos.java` | endpoints (§8.1) |
| modified: `FynProperties`, `FynCostGovernanceService`, `AiAuditLog`, `InsightsController`, `AccountPurgeSweepService`, `application.yml` | config, free spend, columns, narration rewire, purge |

### Task 1: Schema, entities, repositories

**Files:**
- Create:
  - `backend/src/main/resources/db/migration/V282__ai_insight_texts.sql`
  - `backend/src/main/java/com/finora/entity/AiInsightText.java`
  - `backend/src/main/java/com/finora/entity/AiInsightUsage.java`
  - `backend/src/main/java/com/finora/repository/AiInsightTextRepository.java`
  - `backend/src/main/java/com/finora/repository/AiInsightUsageRepository.java`
- Modify:
  - `entity/AiAuditLog.java`: add `planCode` and `reasonCode`
  - `service/AccountPurgeSweepService.java`, near line 834 (`aiAuditLogRepository.deleteByUserId`): delete
    the user's text and usage rows there too
- Test: `backend/src/test/java/com/finora/repository/AiInsightRepositoriesIT.java`

**Interfaces — Produces:**
- **Table `ai_insight_texts`:**
  - Columns:
    - `id uuid pk`
    - `user_id` → users ON DELETE CASCADE
    - `kind varchar(16)` (STORY | WHY)
    - `subject varchar(200)` (month: `"MONTH"`; why: the row's `kind|itemKey`)
    - `month char(7)`
    - `facts_hash char(64)`
    - `prompt_version`, `model`, `policy_version` (varchar)
    - `status varchar(10)` (PENDING | READY | FAILED)
    - `sheet jsonb`, `text text`, `row_order jsonb`
    - `reason_code varchar(32)`, `plan_code varchar(20)`, `regenerated_for_version boolean not null default false`
    - `feedback varchar(16)`, `feedback_reason varchar(16)`
    - `created_at`, `updated_at`
  - UNIQUE on `(user_id, kind, subject, month, facts_hash, prompt_version, model, policy_version)`.
  - Index on `(user_id, kind, month, created_at)`.
- **Table `ai_insight_usage`:** `user_id` → users ON DELETE CASCADE, `month char(7)`, `why_used int not
  null default 0`, `story_generations int not null default 0`, PK `(user_id, month)`.
- **`ai_audit_log`:** add `plan_code varchar(20)` and `reason_code varchar(32)`, both nullable.
- **Grant:** `INSERT INTO feature_entitlements (plan_id, feature_key, enabled) SELECT id, 'FYN_INSIGHTS', true FROM plans WHERE code IN ('FREE','PLUS','PREMIUM')`.
  Follow V205's shape and guard against an existing row.
- **`AiInsightTextRepository`:**
  - `int insertPending(UUID id, UUID userId, String kind, String subject, String month, String factsHash, String promptVersion, String model, String policyVersion, String planCode)`:
    a native `INSERT … ON CONFLICT DO NOTHING`, returning the inserted count.
  - `Optional<AiInsightText> findByKey(...same 8 key fields)`
  - `int takeOverStale(UUID id, Instant staleBefore)`: `UPDATE … SET status='PENDING', updated_at=now() WHERE id=? AND ((status='PENDING' AND updated_at < ?) OR (status='FAILED' AND updated_at < ?))`.
    The FAILED cutoff is passed separately: `int takeOverStale(UUID id, Instant pendingBefore, Instant failedBefore)`.
  - `Optional<AiInsightText> findLatestReady(UUID userId, String kind, String subject, String month)`: newest
    READY by `created_at`.
  - `long countCreatedSince(UUID userId, String kind, String month, Instant since)`: for the once-a-day
    story limit.
  - `Optional<AiInsightText> findByIdAndUserId(UUID id, UUID userId)`
  - `int deleteByUserId(UUID userId)`
- **`AiInsightUsageRepository`:**
  - `int reserveWhy(UUID userId, String month, int limit)`: upsert the row, then `UPDATE … SET why_used = why_used + 1 WHERE user_id=? AND month=? AND why_used < ?`. Returns the updated count.
  - `int releaseWhy(UUID userId, String month)`: `why_used = greatest(why_used - 1, 0)`
  - `int reserveStory(UUID userId, String month, int limit)` and `int releaseStory(UUID userId, String month)`, the same shape on `story_generations`
  - `int whyUsed(UUID userId, String month)`
  - `int deleteByUserId(UUID userId)`

- [ ] **Step 1: Write the failing IT** `AiInsightRepositoriesIT extends AbstractIntegrationTest`, with tests:
  - `insertPendingWinsOnceForTheSameKey`: two calls with the same key return 1 then 0, and one row exists.
  - `parallelWhyReservationsNeverPassTheLimit`: 12 threads call `reserveWhy(u, "2026-10", 5)`. The sum of
    returns is 5 and `whyUsed` is 5.
  - `releaseNeverGoesBelowZero`
  - `takeOverStaleOnlyTakesOldPendingOrOldFailed`: a fresh PENDING returns 0, a PENDING 61 s old returns
    1, a FAILED 9 min old returns 0, a FAILED 11 min old returns 1.
  - `rowsGoWithTheUser`: deleting the user removes the text and usage rows (cascade).
  - `featureEntitlementGrantedToAllThreePlans`: a `feature_entitlements` row exists for FREE, PLUS and
    PREMIUM with `FYN_INSIGHTS`.
- [ ] **Step 2: Run it and confirm it fails.**
  Run: `cd backend && ./mvnw -q verify -Dit.test=AiInsightRepositoriesIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL, because the tables don't exist (Flyway has no V282).
- [ ] **Step 3: Write the migration, entities and repositories** above, and the purge-sweep lines with a
  unit assertion added to the existing `AccountPurgeSweepServiceTest`: `verify(aiInsightTextRepository).deleteByUserId(userId)` and the same for usage.
- [ ] **Step 4: Run the IT and `AccountPurgeSweepServiceTest`.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(db): ai insight text and usage tables, fyn insights grant`.

### Task 2: Config, Free spend, audit columns

**Files:**
- Modify:
  - `config/FynProperties.java`: add `freeMonthlyBudgetUsd` (BigDecimal, default 10.00),
    `insightsAudience` (String, default "internal"), `insightsAiRanking` (boolean, default false), and
    `insightsMaxConcurrent` (int, default 4)
  - `application.yml` under `app.fyn`: `free-monthly-budget-usd: ${FYN_FREE_MONTHLY_BUDGET_USD:10.00}`,
    `insights-audience: ${FYN_INSIGHTS_AUDIENCE:internal}`, `insights-ai-ranking: ${FYN_INSIGHTS_AI_RANKING:false}`
  - `service/FynCostGovernanceService.java`: add `public boolean freeBudgetSpent()`
  - `repository/AiAuditLogRepository.java`: add `BigDecimal sumCostByPlanSince(String planCode, Instant since)`
- Test: `FynCostGovernanceServiceTest` (add cases)

**Interfaces — Produces:**
- `FynProperties#getFreeMonthlyBudgetUsd()`, `#getInsightsAudience()`, `#isInsightsAiRanking()`,
  `#getInsightsMaxConcurrent()`
- `FynCostGovernanceService#freeBudgetSpent()`: true when the sum of `cost` where `plan_code='FREE'`, since
  the start of the UTC calendar month (the same boundary as `monthlyBudget()`), is at least
  `freeMonthlyBudgetUsd`.

- [ ] **Step 1: Failing tests:**
  - `freeBudgetSpentAtExactlyTheSlice`: the sum is 10.00 and the slice is 10.00, so true.
  - `freeBudgetNotSpentJustBelow`: 9.99, so false.
  - `freeSpendIgnoresPlusRows`: the repository is called with "FREE" only.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=FynCostGovernanceServiceTest`. Expected: FAIL to
  compile.
- [ ] **Step 3: Implement** the properties, the yml, the repository query and the method.
- [ ] **Step 4: Run it again.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): free ai budget slice and insights settings`.

### Task 3: Facts model (allowlist, category tokens, display forms, hash)

**Files:**
- Create: `backend/src/main/java/com/finora/service/AiInsightFacts.java`
- Test: `backend/src/test/java/com/finora/service/AiInsightFactsTest.java`

**Interfaces — Produces** (nested types in `AiInsightFacts`):
- **`enum Unit { RUPEES, COUNT, PERCENT, DAY }`**
- **`enum Period { THIS_MONTH, USUAL }`**
- **`record Subject(String id, String displayName, String modelName)`**:
  - the month subject has id `"month"`;
  - a category's id is a slug like `"food"`;
  - `modelName` equals `displayName` for a built-in category, and is `"[category-N]"` for a user-created
    one.
- **`record Fact(String id, String subjectId, String metric, Period period, Unit unit, BigDecimal value, String display)`**:
  - the id is `subjectId + "." + metric` (period is part of the metric name, for example
    `this_month`/`usual`);
  - `display` is rendered by `displayFor`.
- **`record FactSheet(String month, List<Subject> subjects, List<Fact> facts, List<String> rowKeys)`**:
  - `String hash()`: SHA-256 hex of a canonical JSON of the sheet, excluding display names;
  - `String modelJson()`: JSON of subjects (id plus `modelName`) and facts (id, subject, metric, period,
    unit, display). It never contains `displayName` for a user-created category.
- **`static String displayFor(Unit unit, Period period, BigDecimal value, String countNoun)`**:
  - rupees use Indian grouping with no decimals (₹1,23,456);
  - percent is a whole number with `%`;
  - count is `"6 payments"`;
  - day is `"3rd"`;
  - a USUAL period appends `" in a usual month"`.
- **`static Subject categorySubject(Category c, Map<UUID,String> tokenById)`**: `c.isSystem()` decides
  between the name and a token. Tokens are numbered in a stable order (category id ascending).

- [ ] **Step 1: Failing tests:**
  - `rupeesUseIndianGrouping`: 123456 → "₹1,23,456".
  - `usualPeriodCarriesItsWords`: (RUPEES, USUAL, 5000) → "₹5,000 in a usual month".
  - `percentIsWhole`: 14.6 → "15%".
  - `aUserCategoryIsNeverNamedToTheModel`: a user category "Friend K rent" → `modelName` is
    "[category-1]", and `sheet.modelJson()` doesn't contain "Friend K".
  - `aBuiltInCategoryIsSentByName`: "Food" → modelName "Food".
  - `hashIsStableAcrossFactOrder`: same facts in a different list order give the same hash. Sort facts by
    id before hashing.
  - `hashChangesWhenAValueChanges`
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AiInsightFactsTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use the existing Jackson `ObjectMapper` with
  `ORDER_MAP_ENTRIES_BY_KEYS` for the canonical JSON.
- [ ] **Step 4: Run it again.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): ai insight fact sheet model`.

### Task 4: Month sheet builder (with parity)

**Files:**
- Create: `service/AiInsightMonthSheetBuilder.java`
- Modify: `service/InsightsService.java`: add the baseline month count per mover through a new package-private method `Map<String,Integer> baselineMonthsByCategory(Pipeline p)`, using the same `firstMonthByCat`/`priorMonths` logic `build` uses. Extract that logic so `build` and the new method share it; don't duplicate it.
- Test: `service/AiInsightMonthSheetBuilderTest.java` (unit, mocks), `service/AiInsightMonthSheetParityIT.java`

**Interfaces:**
- **Consumes:**
  - `InsightsService.build(userId, month)` and `pipeline(userId, month)`;
  - `DashboardService.summarize(userId)`;
  - `RecurringService.detectForUser(userId)`;
  - `RecurringAnswerService.changedAmounts(userId)`;
  - `PeopleService.people(userId)`;
  - `LookAtService.rows(userId, month)` (Task 6) for `rowKeys`;
  - `AiInsightFacts`.
- **Produces:** `Optional<FactSheet> AiInsightMonthSheetBuilder.build(UUID userId, String month)`. It is
  empty when `InsightsService` has no pipeline for the month.
- **Facts and metric ids:**
  - `month.total`
  - `month.biggest` (subject = that category)
  - per mover with a baseline of at least 2 months: `<cat>.this_month`, `<cat>.usual`, `<cat>.change_pct`.
    A mover whose baseline is under 2 gives `<cat>.this_month` only.
  - `month.coverage_gap` (COUNT 1, only when there is a caveat)
  - `month.card_bills_count`, `month.card_bills_total`
  - when the dashboard's reporting month equals the sheet month: `month.money_in`, `month.money_out`,
    `month.money_out_change_pct`
  - `month.recurring_to_answer`
  - `month.amount_changes`, with each change as `change_N.before` / `change_N.after`. N is in row order,
    with no payee.
  - `month.owed_to_you`, `month.you_owe`: sums of positive and negative `owedToUser`, family excluded, the
    same as mobile `withPeople`.

- [ ] **Step 1: Failing unit tests:**
  - `aWeakBaselineGivesNoComparison`: a mover with 1 baseline month has `this_month` only.
  - `dashboardFactsOnlyForTheSameMonth`
  - `noPayeeOrPersonNameAppearsInModelJson`: fixture recurring merchant "vpa:flatmate.a", person label
    "Friend K", category "Friend K rent" (user). None of them appear in `modelJson()`.
  - `familyBalancesAreLeftOut`
- [ ] **Step 2: Failing parity IT** `AiInsightMonthSheetParityIT`. Seed one user with synthetic rows that
  include a refund, a transfer pair, a shared bill with a share link, a loan entry, a card bill, a statement
  gap month, a first-month category, and a timezone of Asia/Kolkata with a transaction at 23:30 on the last
  day of the month. Assert:
  - `month.total` equals the sum of `InsightsDto` `currentByCat`, and equals the first sentence's figure;
  - each mover's `this_month`, `usual` and `change_pct` equal `InsightsDto.movers()`;
  - `money_in`/`money_out` equal `DashboardSummaryDto` `monthlyIncome`/`monthlyExpense`.
- [ ] **Step 3: Run both.** Expected: FAIL.
- [ ] **Step 4: Implement** the builder and the `InsightsService` extraction. Run the existing
  `InsightsServiceTest`, `InsightsServiceCardBillTest` and `InsightsServiceBudgetRecommendationTest` too, to
  prove the extraction changed nothing.
- [ ] **Step 5: Run all of them.** Expected: PASS.
- [ ] **Step 6: Commit** with `feat(backend): ai insight month fact sheet`.

### Task 5: Row sheet builder (with parity)

**Files:**
- Create: `service/AiInsightRowSheetBuilder.java`
- Test: `service/AiInsightRowSheetBuilderTest.java`, and extend `AiInsightMonthSheetParityIT` with a row case

**Interfaces:**
- **Produces:** `Optional<FactSheet> AiInsightRowSheetBuilder.build(UUID userId, String month, String category)`.
  It is empty when the category's baseline is under 2 months, or when it isn't a mover that month.
- **Facts:**
  - `<cat>.this_month` and `<cat>.usual`, the same values as the mover;
  - `<cat>.payments_this_month` and `<cat>.payments_usual` (COUNT, the average over the baseline months,
    HALF_UP to a whole number);
  - `<cat>.avg_payment_this_month` and `<cat>.avg_payment_usual`;
  - `<cat>.largest_1..3` (RUPEES), each with a sibling `<cat>.largest_N_day` (DAY);
  - `<cat>.weekend_payments_this_month` and `<cat>.weekend_payments_usual` (Saturday or Sunday in the
    user's zone);
  - `<cat>.new_payees_count` and `<cat>.new_payees_total`. A payee is new when its counterparty key has no
    row in the baseline months. Rows without a key count as not new.
- **Rows:** pipeline rows of that category in the month, with `refunds.spendAmount(t) > 0`, card bills
  excluded.

- [ ] **Step 1: Failing tests:**
  - `paymentsTotalEqualsTheMover`: the sum of row spend amounts equals `<cat>.this_month`. That is parity
    against `InsightsDto.movers()` in the IT.
  - `weakBaselineGivesNoSheet`
  - `weekendIsInTheUsersZone`: a payment at 23:30 IST on a Friday counts as Friday, not Saturday UTC.
  - `noPayeeNameInModelJson`
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them again.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): ai insight row fact sheet`.

### Task 6: "To look at" on the server

**Files:**
- Create: `service/LookAtService.java`, `dto/LookAtRowDto.java`
- Test: `service/LookAtServiceTest.java`. Port every case in
  `mobile/src/lib/insightsFeed.test.ts`'s `buildToLookAt` describe block, one Java test per case, with the
  same inputs and expected kinds, order and keys.

**Interfaces:**
- **Consumes:** `InsightsService.build`, `RecurringService.detectForUser`,
  `RecurringAnswerService.changedAmounts`, `PeopleService.suggestions`, `InsightAcknowledgementService.list`.
- **Produces:**
  - **`record LookAtRowDto(String kind, String ackKind, String ackItemKey, String key, ... )`**:
    - kinds are `card-statement`, `people`, `new-recurring`, `amount-changed` and `running-high`, with the
      same fields as mobile's `LookAtRow` for each kind;
    - `key` is `ackKind + "|" + ackItemKey`;
    - for `new-recurring`, which has no ack, the key is `"NEW_RECURRING|" + normalizedMerchant`.
  - **`List<LookAtRowDto> LookAtService.rows(UUID userId, String month)`**. The constants match mobile:
    `RUNNING_HIGH_MIN_PCT = 15`, `RUNNING_HIGH_MIN_RUPEES = 500`, `RUNNING_HIGH_MAX_ROWS = 3`. The ack keys
    match `ackKey()` in `insightsFeed.ts` exactly (`category|month`, `normalizedMerchant|date`, `month`).
  - **`List<LookAtRowDto> LookAtService.applyOrder(List<LookAtRowDto> rules, List<String> storedOrder)`**:
    stored keys still present come first, in stored order, then the remaining rows in rules order.
  - The `running-high` row also carries `boolean whyAvailable`, true when the category's baseline holds at
    least 2 months (Task 4's `baselineMonthsByCategory`).

- [ ] **Step 1: Failing tests:** every ported case, plus:
  - `applyOrderDropsAcknowledgedAndAppendsNew` (Review Focus 5);
  - `whyAvailableOnlyWithTwoBaselineMonths` (Review Focus 1).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=LookAtServiceTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run it again.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): build insights to-look-at rows on the server`.

### Task 7: Template parsing, checker, renderer

**Files:**
- Create: `service/AiInsightReason.java`, `service/AiInsightTemplate.java`, `service/AiInsightChecker.java`,
  `service/AiInsightRenderer.java`
- Test: `service/AiInsightCheckerTest.java` (the checker suite, §7.2.1), `service/AiInsightRendererTest.java`

**Interfaces — Produces:**
- **`enum AiInsightReason`:** exactly the §5.5 list:
  - `OK`, `PROVIDER_TIMEOUT`, `PROVIDER_ERROR`, `RATE_LIMITED`;
  - `INVALID_JSON`, `SCHEMA_MISMATCH`, `UNKNOWN_FACT`, `LITERAL_NUMBER`;
  - `FACT_BINDING`, `DIRECTION_MISMATCH`, `PERIOD_MISMATCH`, `ADVICE`, `PRODUCT_WORD`, `PREDICTION`;
  - `BAD_ORDER`, `EMPTY`, `PERSIST_FAILED`;
  - `FREE_ALLOWANCE_USED`, `FREE_BUDGET_SPENT`, `ORG_BUDGET_SPENT`, `USER_DAILY_CAP`;
  - `CONCURRENCY_LIMIT`, `NOT_IN_AUDIENCE`.
- **`record AiInsightTemplate(List<Sentence> sentences, List<String> order)`** with
  `record Sentence(String type, String template)`, and
  `static Parsed parse(String modelReply)`, where `Parsed` is either a template or an `AiInsightReason`
  (`INVALID_JSON`, `SCHEMA_MISMATCH`).
- **`AiInsightChecker`:**
  - `static final String POLICY_VERSION = "policy-v1"`
  - `AiInsightReason check(AiInsightTemplate t, FactSheet sheet, boolean expectOrder)`: rules 1–10 in §5.2
    order, returning the first failure or `OK`. The word lists are `static final List<String>` fields
    copied from §5.2 rules 3, 5, 6, 7, 8 and 9 (rule 8 includes ELSS, 80C and tax saving). Sentence count
    is 1–4. `type` is one of OBSERVATION, COMPARISON, EXPLANATION. Words match on word boundaries, case
    insensitive.
- **`AiInsightRenderer`:**
  - `String render(AiInsightTemplate t, FactSheet sheet)`: replaces `{subjectId}` with `displayName` and
    `{factId}` with `display`;
  - `String whyRulesText(FactSheet rowSheet)`: the §5.3 shape, "{payments_this_month} this month against
    {payments_usual}; the largest was {largest_1}.", built from the facts' display forms;
  - `String basisFor(FactSheet sheet)`: "Compared with your usual month (Jul–Sep)", built from the
    baseline months. With no usual facts it is "This month only".

- [ ] **Step 1: Failing checker suite.** Use a parameterised test with a fixture sheet: Food this month
  ₹6,200; Food usual ₹5,000 (+24%); Shopping this month ₹4,000; categories Insurance and Loan EMI as
  built-ins.
  - **Must FAIL (with reason):**
    - `"{food} came to {shopping.this_month}."` → FACT_BINDING
    - `"{food} fell to {food.this_month}."` with a positive change → DIRECTION_MISMATCH
    - `"{food} usually costs {food.this_month}."` → PERIOD_MISMATCH
    - `"{food} came to 6200."` → LITERAL_NUMBER
    - `"{food} doubled."` → LITERAL_NUMBER
    - `"{food} rose by half."` → LITERAL_NUMBER
    - `"{food} rose in October."` → LITERAL_NUMBER (month names fall under rule 3)
    - `"You should spend less on {food}."` → ADVICE
    - `"Many people in your situation move this to a fund."` → PRODUCT_WORD
    - `"You might not want to keep {food} this high."` → ADVICE (add "might not want" and "want to" to the
      advice list)
    - `"Cut back on {food}."` → ADVICE (bare-verb opener)
    - `"{food} will keep rising."` → PREDICTION
    - `"Consider an ELSS for tax saving."` → ADVICE
    - `"{unknown.fact} was high."` → UNKNOWN_FACT
    - `{"sentences":[]}` → SCHEMA_MISMATCH
    - 5 sentences → SCHEMA_MISMATCH
    - `type: "ADVICE"` → SCHEMA_MISMATCH
    - order with a duplicate or unknown key → BAD_ORDER
  - **Must PASS:**
    - `"{food} came to {food.this_month}, above {food.usual}."`
    - `"{insurance} was {insurance.this_month}."`
    - `"{loan_emi} stayed at {loan_emi.this_month}."`
    - `"Most of the rise came from {food.weekend_payments_this_month}."`
- [ ] **Step 2: Failing renderer tests:**
  - `rendersNamesAndFigures`: the first must-pass case renders "Food came to ₹6,200, above ₹5,000 in a
    usual month.";
  - `aUserCategoryRendersWithItsOwnName`;
  - `whyRulesTextShape`;
  - `basisNamesTheBaselineMonths`.
- [ ] **Step 3: Run** `./mvnw -q test -Dtest='AiInsightCheckerTest,AiInsightRendererTest'`. Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run them again.** Expected: PASS, with every listed case giving its listed reason.
- [ ] **Step 6: Commit** with `feat(backend): ai insight template checks and renderer`.

### Task 8: Generator (prompt, model call, bound, audit)

**Files:**
- Create: `service/AiInsightGenerator.java`
- Test: `service/AiInsightGeneratorTest.java`

**Interfaces:**
- **Consumes:** `LlmClient`, `FynNameShields`, `AiAuditLogRepository`, `FynPricing`, `FynProperties`,
  `AiInsightTemplate`, `AiInsightChecker`.
- **Produces:**
  - **`record Result(AiInsightReason reason, AiInsightTemplate template, String model)`**
  - **`Result generate(UUID userId, String kind, FactSheet sheet, boolean wantOrder, String planCode)`**:
    - It acquires a `Semaphore(insightsMaxConcurrent)` with `tryAcquire()`. When none is free it returns
      `CONCURRENCY_LIMIT` without calling the model.
    - It sends `LlmRequest.singleTurn(systemPrompt(kind, wantOrder), shield.shield(sheet.modelJson()), maxTokens)`.
    - `maxTokens` is 400 for STORY and 200 for WHY.
    - It parses, then checks, and writes one `ai_audit_log` row with `plan_code`, `reason_code`, tokens,
      cost and latency. `tool_name` is "AI_INSIGHT_STORY" or "AI_INSIGHT_WHY". There is no prompt or reply
      text in `tool_inputs`/`tool_outputs`.
    - It maps exceptions: a timeout → `PROVIDER_TIMEOUT`, HTTP 429 → `RATE_LIMITED`, other errors →
      `PROVIDER_ERROR`.
  - **`static final String STORY_PROMPT_VERSION = "ai-story-v1"`, `WHY_PROMPT_VERSION = "ai-why-v1"`**
  - **The system prompt** states:
    - the JSON shape (§5.1);
    - the three sentence types;
    - only placeholders for figures and names, never digits or month names;
    - history only: no advice, instructions, products or predictions;
    - plain words;
    - 2–4 sentences for STORY and 1–2 for WHY;
    - when `wantOrder` is true, the §7.3 ranking criteria in order.

- [ ] **Step 1: Failing tests:**
  - `outboundRequestCarriesNoPrivateData`: capture the `LlmRequest`. Its system prompt plus messages
    contain none of "vpa:flatmate.a", "Friend K", "100000000001", "user.sample@okbank", or the user
    category's name, and they do contain "[category-1]".
  - `auditRowHasPlanAndReasonButNoText`: for a passing reply the row has `plan_code` "FREE",
    `reason_code` "OK", and null `tool_inputs`/`tool_outputs`.
  - `timeoutMapsToProviderTimeout`
  - `rateLimitMapsToRateLimited`
  - `fifthConcurrentCallIsRefusedWithoutCallingTheModel`: hold 4 calls on a latch, and the 5th returns
    `CONCURRENCY_LIMIT` with `verifyNoMoreInteractions(llmClient)` after 4.
  - `aFailingCheckIsReportedNotThrown`: a reply with a literal number gives `LITERAL_NUMBER`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AiInsightGeneratorTest`. Expected: FAIL.
- [ ] **Step 3: Implement.** Check how `AnthropicClient` surfaces a read timeout and a 429; it already
  classifies them (see its line ~186). Map those classes, not message strings.
- [ ] **Step 4: Run it again.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): ai insight generator with bounded calls`.

### Task 9: Orchestration (story, why, order, limits)

**Files:**
- Create: `service/AiInsightService.java`
- Test: `service/AiInsightServiceTest.java` (unit) and `service/AiInsightConcurrencyIT.java`

**Interfaces:**
- **Consumes:**
  - Tasks 1–8;
  - `EntitlementService.planCodeFor` / `hasEntitlement(FYN_INSIGHTS)`;
  - `FynAvailabilityGuard.insightsAvailableFor`;
  - `FynCostGovernanceService.freeBudgetSpent`;
  - `UserRepository` for `internal_account`;
  - `UserZone`;
  - `Clock` (injected, for tests).
- **Produces:**
  - **`record TextView(String status /* ready|none|generating */, UUID id, String text, boolean writtenByAi, String basis, boolean canGenerate, Integer freeLeftThisMonth)`**
  - **`TextView getStory(UUID userId, String month)`**: read only, never generates.
  - **`TextView generateStory(UUID userId, String month)`**: idempotent.
  - **`TextView why(UUID userId, String rowKey, String month)`**: idempotent per key.
  - **`List<LookAtRowDto> lookAt(UUID userId, String month)`**: rules rows, then
    `LookAtService.applyOrder` with the latest READY story's `row_order` for the current fact hash, only
    when the plan is PLUS or PREMIUM and `insightsAiRanking` is on. Also returns `orderedByAi`.
  - **`void feedback(UUID userId, UUID textId, String rating, String reason)`**: 404 when the row isn't the
    user's.
- **Decision order in `generateStory` / `why`.** A refusal in steps 1–3 or 6–7 happens before any model
  call. It writes no text row and no audit row; it logs the reason code at INFO and returns the rules view.
  1. not in the audience → rules (`NOT_IN_AUDIENCE`);
  2. the guard is unavailable → rules. The reason is:
     - `ORG_BUDGET_SPENT` when `monthlyBudget().status()==STOPPED`;
     - `USER_DAILY_CAP` when `userDailyCapReached`;
     - otherwise (disabled, or no key) `PROVIDER_ERROR`;
  3. the plan is Free:
     - a story for a month other than the newest reporting month → `canGenerate=false`, rules;
     - `freeBudgetSpent` → rules (`FREE_BUDGET_SPENT`);
  4. build the sheet. If it is empty → none;
  5. look up the key (versions: the prompt version, `FynProperties` model, `AiInsightChecker.POLICY_VERSION`):
     - READY → serve it;
     - PENDING and fresh → `generating`;
  6. story limits:
     - `countCreatedSince(…, now - 24h) > 0` and there is a READY row for an older hash → serve that older
       row;
     - Free: `reserveStory(limit 4)` returns 0 → serve the latest READY or rules;
  7. why limit (Free): `reserveWhy(month in user zone, 5)` returns 0 → rules plus `FREE_ALLOWANCE_USED`.
     The view sets `freeLeftThisMonth = 0` and the client shows the §6 line;
  8. `insertPending`, or `takeOverStale`. The loser returns `generating`;
  9. generate:
     - not OK → mark FAILED with the reason, release the reservation, return rules;
     - OK → render, mark READY, return the AI view.
- **A version-bump regeneration** (the key is absent but a READY row exists for the same
  user/kind/subject/month under older versions) sets `regenerated_for_version=true` and does not reserve
  allowance.

- [ ] **Step 1: Failing unit tests**, with a fixed `Clock`:
  - `getStoryNeverCallsTheModel`
  - `freeOlderMonthCannotGenerate` (Review Focus 2)
  - `freeWhyCountsInTheUsersLocalMonth`: the clock is 2026-10-31T19:00Z, which is 00:30 IST on 1 Nov, so
    the reservation goes to "2026-11" (Review Focus 3).
  - `sixthFreeWhyIsRulesWithZeroLeft`
  - `aFailedCheckReleasesTheReservation`
  - `cachedWhyReopenIsFree`: `reserveWhy` is not called the second time.
  - `versionBumpRegenerationDoesNotReserve`
  - `storyRewrittenAtMostOnceADay`
  - `freeStoryAtMostFourAMonth`
  - `plusGetsAiOrderOnlyWhenRankingIsOn`
  - `freeNeverGetsAiOrder`
  - `notInAudienceIsRules`: an internal user is in the audience when the setting is "internal"; a normal
    user isn't.
  - `freeBudgetSpentGivesRulesButPlusCarriesOn`
  - `feedbackOnAnotherUsersTextIs404`
  - `downgradedUserStillReadsStoredText`: a READY row made on PLUS is served by `getStory` after the plan
    becomes FREE, including for an older month (§6 plan changes).
- [ ] **Step 2: Failing IT** `AiInsightConcurrencyIT`, with `@MockitoBean LlmClient` answering a valid
  template after a 300 ms sleep:
  - `parallelStoryGenerationCallsTheModelOnce`: 8 threads call `generateStory`.
    `verify(llmClient, times(1)).complete(any())`, and every thread gets `ready` or `generating`.
  - `parallelFreeWhyTapsNeverPassFive`: 10 parallel `why` calls on 10 different running-high rows, with a
    Free user whose allowance already has 3 used. Exactly 2 AI texts result.
- [ ] **Step 3: Run both.** Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run both again.** Expected: PASS.
- [ ] **Step 6: Commit** with `feat(backend): ai insight story, why and ranking orchestration`.

### Task 10: Endpoints, narration rewire, OpenAPI

**Files:**
- Create: `controller/AiInsightController.java`, `dto/AiInsightDtos.java`
- Modify:
  - `controller/InsightsController.java`: `narration()` returns `AiInsightService.getStory`/`generateStory`
    text. Keep the path and `FynInsightsNarrationDto`. A rules or none result keeps today's contract of an
    `ApiException` NOT_FOUND, which the web catches.
  - Delete `FynInsightsNarrationService` and its test once nothing references it.
  - `backend/openapi/openapi.json` (regenerated);
    `frontend|mobile|admin-portal/src/api/generated-types.ts` (regenerated).
- Test: `controller/AiInsightControllerIT.java`

**Interfaces — Produces** (`/api/v1/insights`, §8.1):
- `GET /story?month=` → `ApiResponse<StoryDto>`, where `StoryDto(status, id, text, writtenByAi, basis, canGenerate)`
- `POST /story` with body `{ month }` → `StoryDto`
- `GET /look-at?month=` → `ApiResponse<LookAtDto(List<LookAtRowDto> rows, boolean orderedByAi)>`
- `POST /look-at/why` with body `{ kind, itemKey, month }` → `WhyDto(status, id, text, writtenByAi, basis, freeLeftThisMonth)`
- `PATCH /ai-texts/{id}/feedback` with body `{ rating: HELPFUL|NOT_HELPFUL|null, reason: WRONG_NUMBER|NOT_USEFUL|CONFUSING|null }`
- **Validation:** `month` matches `^\d{4}-\d{2}$`. `kind` uses the same pattern as
  `InsightAcknowledgementDto.kind`. `itemKey` is `@Size(max=300)`.

- [ ] **Step 1: Failing IT:**
  - `storyReadThenGenerate`
  - `whyForAnotherUsersRowKeyGivesNoData`: the key isn't among the caller's rows → 404.
  - `feedbackOnAnotherUsersTextIs404`
  - `badMonthIs400`
  - `narrationServesTheStory`
  - `lookAtMatchesTheMobileRules`: a fixture with one of each kind returns them in rules order.
- [ ] **Step 2: Run it.** Expected: FAIL.
- [ ] **Step 3: Implement.** Then run `backend/scripts/generate-openapi-spec.sh`, then `npm run
  generate:types` in `frontend`, `mobile` and `admin-portal`, each with Node 22. The diff must be
  additions only, apart from the narration description if it changed.
- [ ] **Step 4: Run the IT.** Expected: PASS.
- [ ] **Step 5: Commit** with `feat(backend): ai insight endpoints` (the generated files in the same
  commit).

### Task 11: Evaluation harness (launch gate, §7.2.2 and §7.3)

**Files:**
- Create: `backend/src/test/java/com/finora/service/AiInsightsEvaluationIT.java`. Model it on the existing
  `FynChatEvaluationIT`: opt-in and skipped unless a real key is set. Read that class for its
  property/assumption pattern.

**Interfaces — Produces:** a run writes `target/ai-insights-eval/` containing:
- `outputs.jsonl`: one line per history, with the story, its why texts, and both orders;
- `side-by-side.md`: the AI top 3 against the rules top 3 for each history, labelled A/B at random, with a
  separate key file.

The 30 synthetic histories are built in the test from synthetic rows. They cover:
- refunds, shares, a gap month, a first month, a weak baseline, a big one-off, a user category, card bills,
  people balances;
- 3 plain months.

- [ ] **Step 1: Write the harness.** It asserts only that every output passed the checker or recorded a
  reason. Judging is by hand.
- [ ] **Step 2: Run** without a key: `./mvnw -q verify -Dit.test=AiInsightsEvaluationIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: SKIPPED.
- [ ] **Step 3: Commit** with `test(backend): ai insights evaluation harness`.
- The run with a real key, and the review, happen before audience `all` (§10.4). They are not part of this
  PR's checks.

### Task 12: PR 1 verification and PR

- [ ] **Full backend suite:** `cd backend && ./mvnw verify > target/verify.log 2>&1`. Expected:
  `BUILD SUCCESS`, 0 failures and 0 errors. Report the unit and IT counts from the log. Don't commit while
  it runs.
- [ ] **Clients:** `(Node 22) npm run typecheck` in mobile and `tsc -b` in frontend, because
  the generated types changed. Expected: clean.
- [ ] **Read the whole diff** for real-looking data, AI attribution, and any field outside the allowlist
  reaching `modelJson()`.
- [ ] **Push and open the PR** with `gh pr create`. The body lists:
  - the audience default `internal`;
  - ranking off;
  - that the Privacy change (PR 3) must merge before audience `all`;
  - what wasn't run: the real-key evaluation.
- [ ] **Bind the PR** with the ccd_pr tools, and read CI.

---

# PR 2 — Mobile (fresh worktree from `origin/main` after PR 1 merges; ships in 1.1.0)

### Task 13: API types and calls

**Files:**
- Modify: `mobile/src/api/endpoints.ts`. Add `aiInsightsApi`:
  - `story(month?)`
  - `generateStory(month?)`
  - `lookAt(month?)`
  - `why(kind, itemKey, month)`
  - `feedback(id, rating, reason)`

  Its types (`AiStory`, `LookAtResponse`, `AiWhy`) are checked against `generated-types.ts` with the same
  compile-time pattern as `_peopleShapesMatchTheServer`.
- Modify: `mobile/src/lib/invalidateFinancialData.ts`: add `'ai-insights'` to `FINANCIAL_QUERY_KEYS`.
- Test: `npm run typecheck`

- [ ] Write the types, the calls and the shape check. Run `(Node 22) npm run typecheck`.
  Expected: clean.
- [ ] Commit with `feat(mobile): ai insights api calls`.

### Task 14: `AiText` component

**Files:**
- Create: `mobile/src/components/insights/AiText.tsx`, `AiText.test.tsx`

**Interfaces — Produces:** `AiText({ id, text, writtenByAi, basis, freeLeft?: number | null })`. It renders:
- the text;
- when `writtenByAi`, a "Written by AI" label;
- the basis line;
- thumbs up and down;
- after a thumbs-down, the 3 reasons: "Wrong number", "Not useful", "Confusing".

It calls `aiInsightsApi.feedback`. Every control has an `accessibilityLabel`. Haptics use `lib/haptics`
(the existing selection haptic).

- [ ] **Failing tests:**
  - `showsTheLabelOnlyForAiText`
  - `thumbsDownOffersThreeReasonsAndSendsTheChosenOne`
  - `aSecondTapOnTheSameThumbClearsIt` (sends null)
  - `controlsHaveAccessibilityLabels`
- [ ] **Run** `(Node 22) npm test -- AiText`. Expected: FAIL. Then implement. Expected: PASS.
- [ ] Commit with `feat(mobile): ai text block with label and feedback`.

### Task 15: Story at the top of Insights

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`, near line 336 where `monthLine` renders.
- Test: `mobile/src/screens/InsightsScreen.test.tsx` (extend, or create following the existing mocks)

- [ ] **Failing tests:**
  - `showsTheMonthLineWhileTheStoryIsNotReady`
  - `generatesWhenAllowedAndShowsTheStory`: GET returns `none` with `canGenerate`, POST returns `ready`,
    and the story with its label shows.
  - `pollsWhileGenerating`: at most 2 refetches, 2 s apart.
  - `aFailedStoryKeepsTheMonthLineSilently`
  - `refetchesLookAtAfterTheStoryArrives`
- [ ] **Implement:** query key `['ai-insights','story',month]`. The mutation is guarded by a ref so a
  re-render can't POST twice. The month line stays until `status==='ready'`.
- [ ] **Run** `(Node 22) npm test -- InsightsScreen`. Expected: PASS.
- [ ] Commit with `feat(mobile): ai month story on insights`.

### Task 16: Server rows, "Why?", Free line

**Files:**
- Modify:
  - `mobile/src/screens/InsightsScreen.tsx`: rows come from `aiInsightsApi.lookAt(month)`. On error, it
    falls back to `buildToLookAt(...)` exactly as today.
  - `mobile/src/components/insights/LookAtSection.tsx`, near line 139 (`running-high`): when
    `row.whyAvailable`, add a "Why?" link that opens the `AiText` under the row. It shows "N of 5 left this
    month" when `freeLeftThisMonth` is set and at least 1. At 0 it shows the §6 line and a "See Plus"
    button calling `navigation.navigate('Subscription')`.
  - `mobile/src/lib/insightsFeed.ts`: export the server row type mapping to `LookAtRow`. Keep
    `buildToLookAt` for the fallback.
- Test: `LookAtSection.test.tsx`, `InsightsScreen.test.tsx`

- [ ] **Failing tests:**
  - `usesServerRowsInServerOrder`
  - `fallsBackToLocalRowsWhenLookAtFails`
  - `whyOnlyOnRunningHighWithWhyAvailable`
  - `whyOpensTheAnswerUnderTheRow`
  - `showsFreeLeft`
  - `atZeroShowsTheLimitLineAndSeePlus`: the exact §6 copy.
  - `doubleTapOnWhySendsOneRequest` (ref guard)
  - `anOpenedAnswerIsAnnounced`: `AccessibilityInfo.announceForAccessibility` is called with the answer
    text (§8.3).
- [ ] **Implement.** Then run `(Node 22) npm test`, `run typecheck` and `run lint`. Expected:
  all clean. Report the counts.
- [ ] Commit with `feat(mobile): server to-look-at rows and why`.

### Task 17: PR 2 verification and PR

- [ ] Run the full mobile jest suite, tsc and eslint with Node 22. Expected: clean.
- [ ] **Release-build check (§8.3):** this needs Sid's device or sign-in. Ask Sid, and record it as not done
  until he confirms.
- [ ] Open the PR. The body says it ships with 1.1.0 and is never OTA'd to 1.0.0. Bind it and read CI.

---

# PR 3 — Website (fresh worktree from `origin/main` after PR 1 merges)

### Task 18: Story slot with label and feedback

**Files:**
- Modify:
  - `frontend/src/api/endpoints.ts`: `aiInsightsApi.story`/`generateStory`/`feedback`
  - `frontend/src/pages/Insights.tsx`, lines ~99–110 and ~190 (the narration effect) and ~269 (render):
    use story GET then POST, guarded by the existing `narrationRequested` ref. Render the text with "Written
    by AI", basis and thumbs.
- Create: `frontend/src/components/insights/AiText.tsx` and its test, mirroring mobile Task 14's interface
  and tests.
- Test: `Insights.test.tsx` (extend), `AiText.test.tsx`

- [ ] **Failing tests:**
  - `storyShowsWithLabelAndBasis`
  - `noStoryKeepsTheRulesSentences`
  - `thumbsDownReasons`
  - `postsAtMostOnce`
- [ ] **Implement.** Then run `(Node 22) npm test` in frontend. Expected: PASS.
- [ ] Commit with `feat(web): ai month story with label and feedback`.

### Task 19: Pricing and Privacy copy

**Files:**
- Modify:
  - `frontend/src/pages/landing/plans.ts`: Free features gain "AI month story and 5 AI explanations a
    month"; Plus features gain "AI insights with no monthly limit".
  - `frontend/src/pages/landing/landing-claims.test.tsx`: assert both lines, and that each is backed by
    `FYN_INSIGHTS` being granted (a comment pointing to the migration).
  - `frontend/src/pages/Privacy.tsx`:
    - line ~129 changes "your category names" to "built-in category names (names of categories you create
      are never sent)";
    - add an "AI insights" paragraph after it, with the §8.4 content: what is sent; history, not advice;
      what is stored and that it goes with the account; Anthropic's standard terms (no training, deleted
      within 30 days, up to 2 years if flagged for misuse);
    - update the subtitle's "Last updated" month if it changes.
- Test: `landing-claims.test.tsx`, and the existing `Privacy` test if there is one.

- [ ] **Failing test:** the `landing-claims` assertions for the two lines. Run, expect FAIL, implement,
  expect PASS.
- [ ] **Run** the full frontend vitest, `tsc -b`, `eslint` and `vite build` with Node 22. Expected: clean.
- [ ] Commit with `feat(web): pricing and privacy copy for ai insights`.

### Task 20: PR 3 verification and PR

- [ ] Check the pages in a browser. This needs Sid's sign-in for `/app/insights`; the public Privacy and
  Pricing pages can be checked without it.
- [ ] Open the PR. The body says this must merge before the audience goes to `all`. Bind it and read CI.

---

# After all three merge (§10.4) — not code

- [ ] Sid runs `AiInsightsEvaluationIT` with a real key, or I run it if he provides the key in his
  environment. Review every output by hand. Required: **0** incorrect claims and **0**
  advice/prediction/instruction outputs, and a check pass rate of at least 90%.
- [ ] Sid judges `side-by-side.md`. Ranking is turned on (`FYN_INSIGHTS_AI_RANKING=true`) only if the AI
  order wins the majority.
- [ ] Internal cohort for at least 2 weeks with `FYN_INSIGHTS_AUDIENCE=internal`. Sid runs read-only
  queries on `ai_audit_log` (pass rate per prompt version, reasons, cost per user, latency) and
  `ai_insight_texts` (thumbs).
- [ ] Then `FYN_INSIGHTS_AUDIENCE=all`, set in Railway by Sid.
