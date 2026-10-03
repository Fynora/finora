# Quick sort Part 4: a few payee questions instead of every waiting row — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After an import, the user answers at most 10 short questions (one per payee, biggest money first) instead of studying every waiting row, and can stop asking about the rest in one step.

**Architecture:** A server service groups the waiting rows by payee, ranks the groups by money, and cuts a batch at 80% of the waiting money or 10 questions (both application properties). Each question carries the payee's readable name, its payments, and up to 5 likely answers. Answering goes through a new endpoint that reuses `TransactionService.updateCategory` (`SIMILAR` when the key names one payee, `ONLY_THIS` otherwise) and stamps `quick_sorted_at`. "Stop asking about these" clears the waiting flag on the rest and keeps their categories. Micrometer counters measure use. Web and mobile show the questions one at a time on top of the existing review area, with the old per-row lists behind "See all waiting payments".

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway, Micrometer; React + TanStack Query + Vitest (web); React Native + Jest (mobile); OpenAPI-generated types.

**Spec:** `docs/superpowers/specs/2026-10-03-quick-sort-review-design.md` (Part 4 and "Usage measurement").

## Global Constraints

- Batch cut: `app.quick-sort.coverage-target` (default `0.80`) and `app.quick-sort.max-questions` (default `10`). Not constants.
- Group by `(counterparty_key, txn_type)` only when `CounterpartyIdentity.identifiesOnePayee(key)`; otherwise each row is its own question (#1930, #1947).
- Candidates: rows waiting for review on the user's live accounts, not `DUPLICATE` — the same set `TransactionGroupingService.needsReviewCandidates` uses.
- Default answers when the user's own history gives fewer than 5 (only categories the user still has; the user's own most-used categories for the same payee type and direction come first):
  - Person paid (expense, `STRUCTURAL_P2P`): `Personal Transfer`, `Friend Repayment`. No spending category is offered by default for a person.
  - Shop (expense, `MERCHANT_DEFAULT`): `Groceries`, `Dining`, `Shopping`, `Transport`, `Health`.
  - Guess (the row's decision source is neither `MERCHANT_DEFAULT` nor `STRUCTURAL_P2P`): the current category first, shown as "Correct".
  - Money in (income): `Friend Repayment`, `Salary`, `Gifts & Donations`, `Transfer`.
- A single payment of at least Rs 5,000 carries `largeOneOff = true` (display only; the threshold is a proposal the spec marks for confirmation).
- The keep-rest button reads exactly "Stop asking about these", with the line "N payments (Rs X) stay as Personal Transfer or Other. You can change any of them later from the Ledger." Never "correct", "done" or "keep as they are".
- Counters carry no payee data and no per-user amounts.
- Commit messages: no `Co-Authored-By` or any AI trailer; lower-case subject after the scope.
- Backend full run `./mvnw verify`; client tests under `npx -y node@22`; never two `mvnw` in one worktree at once.
- After the API changes: regenerate `openapi.json` and the three `generated-types.ts` (web, mobile, admin) the way the repo's OpenAPI drift check expects, and confirm a clean drift check.

## Review Focus

1. **A key that names no one payee** (a cut `cut:` id, a gateway id, no key): every row is its own question, and answering one never files another row. Test in Task 1 and Task 2.
2. **A row answered or deleted between fetching the batch and answering**: the answer endpoint returns the current state and does not fail; `keep-rest` skips ids no longer waiting, chosen by the user, or not owned. Test in Task 2.
3. **The cut at exactly 80%, one rupee short of it, exactly 10 groups, and ties in money**: deterministic order (money desc, then latest date desc, then group id). Test in Task 1.
4. **A user with fewer than 5 own categories, or who deleted a default category**: answers are only categories the user has; never an invented name. Test in Task 1.
5. **Answering with "Personal Transfer" or "Other" on purpose**: it is the user's choice, so the row leaves the queue as MANUAL (existing `markChosen` behaviour) and is not asked again. Test in Task 2.

---

### Task 1: The question service (server)

**Files:**
- Create: `backend/src/main/java/com/finora/util/PayeeLabel.java`
- Modify: `backend/src/main/java/com/finora/inflow/SenderLabel.java` (delegate to `PayeeLabel`)
- Create: `backend/src/main/java/com/finora/transactions/QuickSortService.java`
- Create: `backend/src/main/java/com/finora/transactions/QuickSortDto.java`
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java` (one aggregate query)
- Modify: `backend/src/main/resources/application.yml` (`app.quick-sort.*`)
- Test: `backend/src/test/java/com/finora/transactions/QuickSortServiceTest.java`

**Interfaces:**
- Produces:
  - `PayeeLabel.of(Transaction t): String`
  - `QuickSortDto.Batch(List<Question> questions, BigDecimal waitingTotal, Rest rest)`
  - `QuickSortDto.Question(String id, UUID anchorTransactionId, Kind kind, String payee, int payments, BigDecimal total, LocalDate latestDate, boolean largeOneOff, String currentCategory, List<String> answers, List<Sample> samples)`
  - `QuickSortDto.Sample(UUID id, LocalDate date, String description, BigDecimal amount, String type)`
  - `QuickSortDto.Rest(int questions, int payments, BigDecimal amount, List<UUID> transactionIds)`
  - `QuickSortDto.Kind { PERSON_PAID, SHOP, GUESS, MONEY_IN }`
  - `QuickSortService.batch(UUID userId, int skip): QuickSortDto.Batch`
  - `TransactionRepository.countManualChoicesByCategory(UUID userId, CounterpartyType type, Transaction.Type direction): List<CategoryCount>` with projection `CategoryCount { UUID getCategoryId(); long getCount(); }`

- [ ] **Step 1: Write the failing unit test**

`QuickSortServiceTest` builds `Transaction` objects in memory (no database) and mocks `TransactionRepository`, `AccountRepository` and `CategoryRepository`. Cases, one `@Test` each:

1. `groupsOnePayeeIntoOneQuestionAndRanksByMoney` — payee A: 3 expenses of 100 (`vpa:samplea`, synthetic); payee B: one expense of 500 (`vpa:sampleb`). Expect B first (500), then A (300, payments 3).
2. `aKeyThatNamesNoOnePayeeIsOneQuestionPerRow` — two rows on `cut:samplepay.12` (or no key): two questions, each with `payments = 1`.
3. `stopsAtTheCoverageTarget` — groups of 50, 30, 10, 10 (total 100): batch is the first two (80% reached exactly at 80); `rest.questions = 2`, `rest.amount = 20`, `rest.transactionIds` = the last two groups' ids.
4. `oneRupeeShortOfTheTargetTakesTheNextGroup` — 50, 29, 11, 10 (total 100): 79 < 80, so the batch is three groups.
5. `neverMoreThanMaxQuestions` — twelve equal groups: batch of 10, rest 2.
6. `tiesAreOrderedByLatestDateThenId` — two groups of equal money: the one with the later latest date first.
7. `skipStartsTheBatchAfterTheFirstNGroups` — skip=1 with groups 50, 30, 20: batch starts at 30; the coverage target is measured against the money from the skip point on (50 of 50 = 30+20 → both).
8. `personQuestionsOfferNoSpendingCategoryByDefault` — user has every default category, no history: answers for a person-paid question are exactly `[Personal Transfer, Friend Repayment]`.
9. `theUsersOwnChoicesComeFirst` — repository returns `Rent` × 4 for PERSON/EXPENSE: answers `[Rent, Personal Transfer, Friend Repayment]`.
10. `onlyCategoriesTheUserHasAreOffered` — user deleted `Dining`: a shop question's answers omit it and are never longer than 5.
11. `aRowWithAGuessIsAGuessQuestionWithItsCategoryFirst` — waiting row with decision source `SHARED_CORPUS`, category `Groceries`: kind `GUESS`, `currentCategory = "Groceries"`, answers start with `Groceries`.
12. `incomeIsMoneyIn` — an income row: kind `MONEY_IN`.
13. `aSinglePaymentOfFiveThousandIsALargeOneOff` — one row of 5000.00: `largeOneOff = true`; 4999.99: false; two rows totalling 6000: false.
14. `nothingWaitingIsAnEmptyBatch` — no rows: empty questions, `waitingTotal = 0`, rest all zero.
15. `duplicatesAreNotAsked` — a waiting row with reconciliation status `DUPLICATE` is not in any question or the rest.
16. `thePayeeIsTheReadableNameNotTheKey` — narration `UPI/DR/900011112201/SAMPLE ST/HDFC/samplestore/` (synthetic): `payee` is the name slot `PayeeLabel` reads, never `vpa:...`/`cut:...`.

Every narration in the test ends its line with `// synthetic-ok`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=QuickSortServiceTest`
Expected: compilation errors (`QuickSortService`, `QuickSortDto`, `PayeeLabel` missing).

- [ ] **Step 3: Implement**

`PayeeLabel` (public, `com.finora.util`) is `SenderLabel.of` moved: the counterparty slot `OwnAccountEvidence.counterpartySlot` reads, else the trimmed narration, else the merchant, else "Unknown payee". `SenderLabel.of` becomes `return PayeeLabel.of(t)` with its "Unknown sender" fallback kept for inflow callers (the empty-narration and empty-merchant case returns "Unknown sender" there).

`QuickSortService`:

```java
@Service
public class QuickSortService {
    static final BigDecimal LARGE_ONE_OFF = new BigDecimal("5000");
    static final List<String> PERSON_DEFAULTS = List.of("Personal Transfer", "Friend Repayment");
    static final List<String> SHOP_DEFAULTS = List.of("Groceries", "Dining", "Shopping", "Transport", "Health");
    static final List<String> MONEY_IN_DEFAULTS = List.of("Friend Repayment", "Salary", "Gifts & Donations", "Transfer");
    static final int MAX_ANSWERS = 5;
    static final int MAX_SAMPLES = 3;

    @Value("${app.quick-sort.coverage-target:0.80}") private BigDecimal coverageTarget;
    @Value("${app.quick-sort.max-questions:10}") private int maxQuestions;
    // constructor: TransactionRepository, AccountRepository, CategoryRepository

    @Transactional(readOnly = true)
    public QuickSortDto.Batch batch(UUID userId, int skip) { ... }
}
```

`batch`:
1. Live account ids (`accountRepository.findByUserId`); none → empty batch.
2. Rows: `findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc`, minus `DUPLICATE`.
3. Group into a `LinkedHashMap<String, List<Transaction>>`: key `t.getCounterpartyKey() + "|" + t.getTxnType()` when `identifiesOnePayee`, else `"row:" + t.getId()`.
4. Each group's total = sum of `amount.abs()`; latest date = first row's date (input is date-desc).
5. Sort: total desc, latest date desc, group id asc.
6. `waitingTotal` = sum of all totals. From index `skip` (clamped to size): `remaining` = sum of totals from there; take groups while `taken < maxQuestions` and (`taken == 0` or `covered < coverageTarget × remaining`), adding each group's total to `covered` after taking it. (So: take, then check; stop once covered ≥ target or max reached.)
7. `rest` = every group after the batch: count, rows, money, ids (groups before `skip` are not "rest"; they are still waiting and the client asked to skip them).
8. Each question: id = group key; anchor = the group's latest row; kind:
   - income → `MONEY_IN`;
   - anchor decision source is neither `MERCHANT_DEFAULT` nor `STRUCTURAL_P2P` → `GUESS`;
   - `STRUCTURAL_P2P` → `PERSON_PAID`;
   - else `SHOP`.
9. Answers: `currentCategory` = anchor's category name (from one `categoryRepository.findByUserId` map). Ordered list: for `GUESS`, the current category first; then the user's own choices for `(anchor.counterpartyType, anchor.txnType)` from `countManualChoicesByCategory`, count desc; then the kind's defaults; filtered to names the user has, de-duplicated, capped at 5.
10. Samples: up to 3 latest rows as `Sample`.

Repository query:

```java
interface CategoryCount { UUID getCategoryId(); long getCount(); }

@Query("""
        SELECT t.categoryId AS categoryId, COUNT(t) AS count
        FROM Transaction t
        WHERE t.userId = :userId AND t.categoryManuallySet = true
          AND t.counterpartyType = :type AND t.txnType = :direction AND t.categoryId IS NOT NULL
        GROUP BY t.categoryId
        ORDER BY COUNT(t) DESC
        """)
List<CategoryCount> countManualChoicesByCategory(@Param("userId") UUID userId,
                                                 @Param("type") com.finora.util.CounterpartyType type,
                                                 @Param("direction") Transaction.Type direction);
```

Cache the per-(type, direction) lookups within one `batch` call (at most 4 combinations per user × kind).

`application.yml`:

```yaml
  quick-sort:
    # QuickSortService. A batch stops once it covers this share of the waiting money, or at
    # max-questions. 0.80 rests on one tester's account (its 10 biggest payees held 78%); the usage
    # counters may move it.
    coverage-target: ${QUICK_SORT_COVERAGE_TARGET:0.80}
    max-questions: ${QUICK_SORT_MAX_QUESTIONS:10}
```

- [ ] **Step 4: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=QuickSortServiceTest,SenderLabelTest` (the latter only if it exists: `ls backend/src/test/java/com/finora/inflow | grep SenderLabel`).
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/PayeeLabel.java backend/src/main/java/com/finora/inflow/SenderLabel.java \
  backend/src/main/java/com/finora/transactions/QuickSortService.java backend/src/main/java/com/finora/transactions/QuickSortDto.java \
  backend/src/main/java/com/finora/repository/TransactionRepository.java backend/src/main/resources/application.yml \
  backend/src/test/java/com/finora/transactions/QuickSortServiceTest.java
git commit -m "feat(transactions): group waiting rows into a few payee questions, biggest money first"
```

---

### Task 2: Endpoints, answer stamp, keep-rest and usage counters (server)

**Files:**
- Create: `backend/src/main/resources/db/migration/V253__transactions_quick_sorted_at.sql` (number confirmed first, as in Part 1)
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java` (`quickSortedAt`)
- Create: `backend/src/main/java/com/finora/observability/QuickSortMetrics.java`
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortService.java` (`answer`, `keepRest`)
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java` (changed-later counter in `updateCategory`)
- Modify: `backend/src/main/java/com/finora/transactions/TransactionController.java` (3 endpoints)
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java` (keep-rest bulk update)
- Modify: `backend/src/test/java/com/finora/service/ChangeStampBulkWriteGuardTest.java` only if the keep-rest update does not bump `version` (it must bump: the waiting flag is visible)
- Test: `backend/src/test/java/com/finora/transactions/QuickSortIT.java`

**Interfaces:**
- Consumes: Task 1's `QuickSortService.batch`, `QuickSortDto`.
- Produces:
  - `GET /api/v1/transactions/quick-sort?skip=0` → `ApiResponse<QuickSortDto.Batch>` (records `questions shown` and `batches shown`)
  - `POST /api/v1/transactions/quick-sort/answer` body `QuickSortDto.AnswerRequest(UUID anchorTransactionId, String category, Kind kind)` → `ApiResponse<QuickSortDto.AnswerResult(int filed)>`
  - `POST /api/v1/transactions/quick-sort/more` → `ApiResponse<Void>` (records "Sort 10 more")
  - `POST /api/v1/transactions/quick-sort/keep-rest` body `QuickSortDto.KeepRestRequest(List<UUID> transactionIds)` (max 2,000 ids, `@Size`) → `ApiResponse<QuickSortDto.KeepRestResult(int cleared)>`
  - `TransactionRepository.stopAsking(UUID userId, Collection<UUID> ids): int`

- [ ] **Step 1: Confirm V253 is free** (same commands as Part 1's Task 1 Step 1).

- [ ] **Step 2: Write the failing IT** — `QuickSortIT extends AbstractIntegrationTest`, a real user, account and categories, rows seeded waiting. Cases:
  1. `answeringFilesEveryWaitingPaymentOfThatPayeeAndRemembersIt` — three waiting rows on `vpa:samplea` (synthetic); answer `Groceries` on the anchor → `filed = 3`, all three MANUAL and not waiting, all `quick_sorted_at` set; a fresh `batch` no longer contains the payee; `userMerchantCategoryResolutionRepository` has a pin for the key.
  2. `answeringARowOnAKeyThatNamesNoOneFilesOnlyThatRow` — two rows on `cut:samplepay.12`: answer one → `filed = 1`, the other still waiting.
  3. `answeringOnPurposeWithPersonalTransferResolvesIt` — answer `Personal Transfer` → row MANUAL, not waiting.
  4. `answeringARowAlreadyChosenElsewhereDoesNotFail` — the anchor is set MANUAL first; answer returns 200 and `filed` reflects only rows still waiting.
  5. `keepRestClearsTheFlagAndKeepsTheCategory` — rows waiting with `Other`/`Personal Transfer`: `cleared` = their count, categories unchanged, decision source unchanged, `category_manually_set` still false, `version` bumped.
  6. `keepRestSkipsRowsNotWaitingChosenOrNotOwned` — include another user's row id, a chosen row id and a non-waiting row id: `cleared` excludes all three and none of them changes.
  7. `keepRestRejectsMoreThanTwoThousandIds` — 400.
  8. `changingAnAnsweredRowLaterIsCounted` — answer via Quick sort, then `PATCH /transactions/{id}/category` to another category: `finora.quick_sort.answer_changed_later` increments by 1; a PATCH on a never-quick-sorted row does not.
  9. `countersMove` — GET batch increments `finora.quick_sort.batches_shown` by 1 and `questions_shown` by the batch size; an answer increments `answered{kind=...}`; `/more` increments `more_taken`; keep-rest increments `stop_asking_taken` and `stop_asking_rows` by `cleared`.

- [ ] **Step 3: Run it; expect compile failures.** `cd backend && ./mvnw verify -Dtest=QuickSortIT -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 4: Implement**

Migration:

```sql
-- When the user answered this row in Quick sort (QuickSortService.answer). Lets the usage counters
-- tell an answer the user later changed (finora.quick_sort.answer_changed_later) from any other edit.
ALTER TABLE transactions ADD COLUMN quick_sorted_at TIMESTAMPTZ;
```

Entity: `@Column(name = "quick_sorted_at") private Instant quickSortedAt;` with getter/setter.

`QuickSortService.answer(userId, req)` (`@Transactional`):
1. Load the anchor with `getOwned`-equivalent ownership (reuse `TransactionService`'s public path: call `transactionService.updateCategory(userId, anchorId, category, scope)` where `scope = identifiesOnePayee(anchor.key) ? SIMILAR : ONLY_THIS`).
2. Count `filed` = the anchor plus the same-payee rows that were not chosen before the call, read first with `findByUserIdAndCounterpartyKeyAndTxnTypeAndIdNotAndAccountIdIn` on live accounts (the query `samePayeeRows` uses); for `ONLY_THIS`, 1.
3. Stamp `quick_sorted_at = now()` on exactly those rows (one bulk update with `version = version + 1`, guarded by `user_id`).
4. `metrics.answered(req.kind())`.

`TransactionService.updateCategory`: before applying, `if (t.getQuickSortedAt() != null) metrics.answerChangedLater();`. Quick sort's own call never trips it: `answer` stamps `quick_sorted_at` only after `updateCategory` returns, and a row it stamped is no longer waiting, so it is never asked again.

`stopAsking` bulk update:

```java
@Modifying
@Query("""
        UPDATE Transaction t
        SET t.needsCategoryReview = false, t.version = t.version + 1
        WHERE t.userId = :userId AND t.id IN :ids
          AND t.needsCategoryReview = true AND t.categoryManuallySet = false
        """)
int stopAsking(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);
```

`QuickSortMetrics` (same shape as `NavigationMetrics`): counters `finora.quick_sort.batches_shown`, `finora.quick_sort.questions_shown`, `finora.quick_sort.answered` (tag `kind`), `finora.quick_sort.more_taken`, `finora.quick_sort.stop_asking_taken`, `finora.quick_sort.stop_asking_rows`, `finora.quick_sort.answer_changed_later`.

Controller: the three POSTs and the GET above, each with `currentUser.id()`; `skip` defaults to 0 and is clamped to `>= 0`.

- [ ] **Step 5: Run the IT and the guard**: `./mvnw verify -Dtest=QuickSortIT,ChangeStampBulkWriteGuardTest,QuickSortServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → all pass.

- [ ] **Step 6: Regenerate the API contract and client types**: run the repo's generator (see `mvn-test-skips-it-verify-clobbers-jar` memory: OpenAPI regen happens in `verify`; copy the generated spec as the drift check expects), regenerate `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts` with the repo's script (`grep -rn "openapi-typescript" frontend/package.json mobile/package.json admin-portal/package.json`). Expected: only the new paths and schemas appear in the diff.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V253__transactions_quick_sorted_at.sql backend/src/main/java backend/src/test/java openapi.json frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts admin-portal/src/api/generated-types.ts
git commit -m "feat(transactions): answer a payee question, stop asking about the rest, and count how quick sort is used"
```

(Use the real path of the committed spec file, found with `git ls-files | grep -i openapi.json`.)

---

### Task 3: Web Quick sort

**Files:**
- Modify: `frontend/src/api/endpoints.ts` (`quickSort`, `quickSortAnswer`, `quickSortMore`, `quickSortKeepRest`)
- Modify: `frontend/src/types/index.ts` (re-export the generated types)
- Create: `frontend/src/components/QuickSortCard.tsx`
- Create: `frontend/src/components/QuickSortCard.test.tsx`
- Modify: `frontend/src/pages/Ledger.tsx` (Quick sort first; the three existing cards behind "See all waiting payments")
- Modify: `frontend/src/pages/Import.tsx` (summary screen: "Sort N questions" when the batch has questions)
- Test: `frontend/src/pages/Ledger.test.tsx`, `frontend/src/pages/Import.test.tsx` (new cases only)

**Behaviour of `QuickSortCard`** (one question at a time):
- Header: "Quick sort — N questions" and the progress line "You've sorted P% of your waiting money", where the first fetch's `waitingTotal` is the start and P = (start − current waitingTotal) / start, rounded down.
- Question body: payee name, "K payments · Rs T · latest DATE", "Large one-off payment" badge when `largeOneOff`, up to 3 sample rows collapsed behind "Show payments".
- Kind copy: PERSON_PAID "What was this person paid for?"; SHOP "What kind of shop is this?"; GUESS "Fynora thinks: CATEGORY" with buttons "Correct" and "Change"; MONEY_IN "What was this money?".
- Answers: one button per `answers` entry, then "More…" opening the existing `CategoryCombobox` (full list, create allowed via `CategoryCreateEditPanel`, same as `AskOnceCard`).
- "Skip" moves to the next question without answering (the client adds 1 to `skip` for the next fetch).
- After the last question of a batch: if `rest.questions > 0`, two buttons — "Sort 10 more" (calls `quickSortMore()`, then refetches with the accumulated `skip`) and "Stop asking about these" with the exact constraint copy; else "All sorted".
- On every answer and on keep-rest: invalidate `['transactions']`, `['recent-transactions']`, `['dashboard-summary']`, `['insights']`, `['budgets']` (the set the existing review cards invalidate).
- Renders nothing when the first fetch returns no questions.

- [ ] **Step 1: Write failing tests** (`QuickSortCard.test.tsx`, mocking `transactionsApi`): renders nothing for an empty batch; shows the first question and kind copy; answering calls `quickSortAnswer` with anchor, category and kind and shows the next question; GUESS shows "Correct" and answers with the current category; "Skip" advances without a call; end of batch shows "Sort 10 more" and the exact "Stop asking about these" copy with N and Rs X; "Stop asking about these" calls `quickSortKeepRest` with `rest.transactionIds`; progress text uses the start total; an API error shows "Couldn't save that answer — please try again." and keeps the question. Ledger: Quick sort renders above the review area and the three old cards are hidden until "See all waiting payments" is clicked. Import summary: "Sort N questions" appears when the batch has N > 0 and links to `/ledger`.
- [ ] **Step 2: Run** `cd frontend && npx -y node@22 node_modules/.bin/vitest run src/components/QuickSortCard.test.tsx src/pages/Ledger.test.tsx src/pages/Import.test.tsx` → new cases fail.
- [ ] **Step 3: Implement** the component and the two page changes.
- [ ] **Step 4: Run the same tests, then** `npx -y node@22 node_modules/.bin/tsc --noEmit -p .` **and** the repo's lint command (`grep '"lint"' frontend/package.json`) → all clean.
- [ ] **Step 5: Commit** `git commit -m "feat(web): quick sort asks a few payee questions before the full review list"`

---

### Task 4: Mobile Quick sort

**Files:**
- Modify: `mobile/src/api/endpoints.ts`, `mobile/src/types/index.ts`
- Create: `mobile/src/components/QuickSortPanel.tsx`, `mobile/src/components/QuickSortPanel.test.tsx`
- Modify: `mobile/src/screens/CategoryReviewScreen.tsx` (Quick sort first; existing lists behind "See all waiting payments")
- Modify: `mobile/src/screens/import/ImportScreen.tsx` (success state: "Sort N questions" navigating to `CategoryReview`)
- Test: `mobile/src/screens/CategoryReviewScreen.test.tsx`, the ImportScreen test file (new cases)

Same behaviour and copy as Task 3; answer buttons are `Pressable` chips; "More…" opens the existing category picker the screen already uses. Use `AppModal`/`AppAlert` patterns, never `return null` inside a navigator wrapper (memory: AppLockGate). Query keys invalidated: the ones `CategoryReviewScreen` already invalidates after an answer.

- [ ] **Step 1: Write failing tests** mirroring Task 3's list (render, answer, guess, skip, end of batch copy, keep-rest call, error), plus: the screen shows Quick sort first and the old lists only after "See all waiting payments"; the import success state shows "Sort N questions" and navigates to `CategoryReview`.
- [ ] **Step 2: Run** `cd mobile && npx -y node@22 node_modules/.bin/jest src/components/QuickSortPanel.test.tsx src/screens/CategoryReviewScreen.test.tsx` (+ the import test) → new cases fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run tests, `tsc --noEmit`, lint** under Node 22 → clean.
- [ ] **Step 5: Commit** `git commit -m "feat(mobile): quick sort asks a few payee questions before the full review list"`

---

### Task 5: Verification, measurement, PR

- [ ] **Step 1:** `cd backend && FINORA_CORPUS_DIR="$HOME/Downloads/Bank statement" ./mvnw verify` → 0 failures, corpus IT not skipped.
- [ ] **Step 2:** Web full test run and mobile full test run under Node 22 → 0 failures (any failure unrelated to this diff is named, not fixed).
- [ ] **Step 3: Question-count measurement.** A throwaway probe (never committed) stages each corpus statement plus the tester's statements, treats rows whose static suggestion is "Other" or the person guess as waiting, runs the same grouping and cut, and prints per statement: waiting rows, payees, first-batch questions, coverage. Record the table in the PR.
- [ ] **Step 4:** Self-review `git diff origin/main...HEAD` against the Review Focus list; push; open the PR with the measurement table and the production check (the owner opens Quick sort on web, answers the first questions, and runs the waiting-by-source query before and after).
