# AI Sorting After an Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A card on the import Done step that sorts the import's unsorted shop payments with AI on a tap, and asks a customer who said no to AI one more time.

**Architecture:** A new backend package `com.finora.aisort`: an offer check (who sees the card, with what numbers), a run (one row per tap, worked payee by payee on its own background thread through the existing `FynCategorizationFallbackService.suggest`), and a controller. The AI's answers file the waiting rows as `AI_FALLBACK` guesses that stay flagged for review, so Quick sort asks them as "Fynora thinks: …". Web and mobile each get an `AiSortCard` on the Done step, modelled on `ImportMoneyInCard` and the invite card.

**Tech Stack:** Spring Boot 3.5 / JPA / Flyway / Micrometer; React + Vitest; Expo React Native + Jest/RNTL.

**Spec:** `docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md`

## Global Constraints

- Payments to people are never sent to the model: everything goes through `FynCategorizationFallbackService.suggest`, which applies `CategorizationService.narrationForModel`; the offer counts only rows `mayGoToModel` allows.
- Nothing runs without a tap; nothing is filed for good: rows keep `needsCategoryReview = true`.
- The one-more question is asked at most once per customer (`users.ai_import_question_at` not null = never again), enforced on the server, not only in the apps.
- A never-asked customer (`ai_processing_consent` null) never gets the card.
- "Turn on AI and sort" records yes through `TermsConsentService.submit(userId, null, true)` — exactly what the Settings switch does.
- Copy (exact; the question is a draft for counsel):
  - Line: `AI can sort {n} shop payments (₹{amount})`; `1 shop payment` when n = 1; amount whole rupees, en-IN grouping.
  - Said yes: button `Sort with AI`.
  - Asked again: `Fynora can use AI to suggest categories for these payments. It sends the payment text, with names and account numbers removed, to Anthropic. You can turn this off any time in Settings.` Buttons `Turn on AI and sort`, `No thanks`.
  - Running: `Sorting… {done} of {total}`.
  - Done: `Sorted {sorted} — check them in Quick sort`, plus `{left} left for you` when left > 0.
  - Stopped by limit: `Sorted {sorted}; AI has reached today's limit — the rest stay in Quick sort`.
  - Stopped, AI off: `AI was turned off — the rest stay in Quick sort`.
  - Stopped otherwise (a restart, a full queue): `Sorted {sorted} — the rest stay in Quick sort`. (Plan's call; the spec names the limit and AI-off cases only.)
- Flyway: next free version on `origin/main` at the time of the migration commit (V322 today); check first.
- OpenAPI: regenerate the spec (throwaway Postgres, `DB_PORT=55434`) and the three clients' types (`openapi-typescript@7.13.0`).
- Tests on Node 22 (`npx --yes node@22 …`; mobile with `NODE_OPTIONS=--experimental-vm-modules`). Commits carry no AI trailer. Links carry `trackNavigation`.

## Review Focus

1. AI turned off in Settings while a run is going: the run stops before the next payee, makes no further model call, and the card says so. → Task 3 test `stopsWhenAiIsTurnedOffMidRun`.
2. A payee answered in Quick sort while the run is going: its rows keep the customer's answer. → Task 3 `leavesAPayeeTheCustomerAnsweredMidRun`.
3. Two taps (double click, two devices): one run, both see it. → Task 3 `secondTapReturnsTheSameRun`.
4. Server restarted mid-run: the run would read RUNNING forever. A run not updated for 10 minutes reads `STOPPED`; the card shows the stopped text instead of spinning. → Task 3 `aStaleRunReadsStopped`.
5. Someone else's statement import ids, or a customer who already answered the one-more question calling "Turn on AI and sort" again: no card, request refused. → Task 2 `noOfferForAnotherCustomersImport`, Task 3 `askAgainIsRefusedOnceAnswered`.

---

### Task 1: Data — the question stamp and the run table

**Files:**
- Create: `backend/src/main/resources/db/migration/V322__ai_sort_runs.sql`
- Modify: `backend/src/main/java/com/finora/entity/User.java` (field, getter, `recordAiImportQuestion(Instant)`)
- Create: `backend/src/main/java/com/finora/aisort/AiSortRun.java`, `AiSortRunRepository.java`
- Test: `backend/src/test/java/com/finora/aisort/AiSortRunRepositoryIT.java`

**Interfaces:**
- Produces: `User.getAiImportQuestionAt(): Instant`, `User.recordAiImportQuestion(Instant)`.
- Produces: entity `AiSortRun` (table `ai_sort_runs`): `id UUID`, `userId UUID`, `importsKey String` (the statement import ids sorted and comma-joined), `status AiSortRun.Status {RUNNING, DONE, STOPPED_LIMIT, STOPPED_AI_OFF, STOPPED}`, `paymentsTotal int`, `paymentsDone int`, `paymentsSorted int`, `createdAt`, `updatedAt Instant`, `finishedAt Instant`.
- Produces: `AiSortRunRepository.findByUserIdAndImportsKey(UUID, String): Optional<AiSortRun>`; `findByIdAndUserId(UUID, UUID): Optional<AiSortRun>`.

Migration: `ALTER TABLE users ADD COLUMN ai_import_question_at TIMESTAMPTZ NULL`; `CREATE TABLE ai_sort_runs (...)` with `UNIQUE (user_id, imports_key)`, FK to users, status CHECK.

- [ ] **Step 1: Write the failing IT** `oneRunPerCustomerAndImportSet` — saving a second run with the same `userId` + `importsKey` throws `DataIntegrityViolationException`; a different user with the same key saves.
- [ ] **Step 2: Run** `./mvnw verify -Dit.test=AiSortRunRepositoryIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` — Expected: compile failure / FAIL.
- [ ] **Step 3: Write migration, entity, repository, User field.**
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): ai sort run table and the one-more question stamp`.

### Task 2: The offer — who sees the card, with what numbers

**Files:**
- Create: `backend/src/main/java/com/finora/aisort/AiSortDto.java`, `AiSortService.java` (offer half)
- Modify: `backend/src/main/java/com/finora/service/FynAvailabilityGuard.java` (add `categorizationOffered()`), `backend/src/main/java/com/finora/service/CategorizationService.java` (`mayGoToModel` becomes `public`), `backend/src/main/java/com/finora/repository/TransactionRepository.java` (query below)
- Test: `backend/src/test/java/com/finora/aisort/AiSortOfferIT.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: `AiSortDto.Mode {SORT, ASK}`; `AiSortDto.Offer(boolean show, Mode mode, int payments, BigDecimal amount, int payees)` (mode null when not shown).
- Produces: `AiSortService.offer(UUID userId, List<UUID> statementImportIds): AiSortDto.Offer`.
- Produces: `AiSortService.sortablePayees(UUID userId, List<UUID> statementImportIds): List<SortablePayee>` where `record SortablePayee(String counterpartyKey, List<Transaction> importRows, BigDecimal amount)`, ordered by `amount` descending, then key — Task 3 works this list.
- Produces: `FynAvailabilityGuard.categorizationOffered(): boolean` = `available() && properties.isCategorizationEnabled()`.
- Produces: `TransactionRepository.findWaitingExpensesInImports(UUID userId, Collection<UUID> statementImportIds): List<Transaction>` — not deleted, `needsCategoryReview`, `txnType = EXPENSE`, `statementImportId IN`, account among the user's accounts.

Sortable payee (spec "Who sees the card" 2): `CounterpartyIdentity.identifiesOnePayee(key)`, `CategorizationService.mayGoToModel(description)` for at least one of its rows (rows that may not go are left out of the counts), and no saved answer (`FynCategorizationFallbackService.indexFor(userId)` → `suggestReadOnly(..., index)` empty). Show = `categorizationOffered()` && at least one sortable payee && (consent true && !`userDailyCapReached` → SORT; consent false && `aiImportQuestionAt` null → ASK). Statement import ids that are not the caller's are dropped (as `InvitePromptService` does with `successfulImportIds`); at most 20 ids (400 above, as `InvitePromptService.MAX_IDS`).

- [ ] **Step 1: Write failing ITs:**
  - `offersSortToACustomerWhoSaidYes` — two shop payees (₹500 + ₹300, ₹200) → `show=true, mode=SORT, payments=3, amount=1000, payees=2`.
  - `asksACustomerWhoSaidNoOnce` → `mode=ASK`; with `aiImportQuestionAt` set → `show=false`.
  - `noOfferForANeverAskedCustomer` (consent null) → `show=false`.
  - `leavesOutPaymentsToPeopleAndSavedPayees` — a `UPI/…/<person>` transfer row and a payee with a saved resolution are not counted.
  - `leavesOutMoneyIn` — a waiting INCOME row is not counted.
  - `noOfferWhenAiIsOffGlobally` (`finora.fyn.enabled=false` via `@TestPropertySource` or the property bean) → `show=false`.
  - `noOfferWhenTodaysCapIsReached` (an `ai_audit_log` row costing ≥ the cap today) → `show=false`.
  - `noOfferForAnotherCustomersImport` → `show=false`.
- [ ] **Step 2: Run** `./mvnw verify -Dit.test=AiSortOfferIT …` — Expected: FAIL (classes missing).
- [ ] **Step 3: Implement** the offer half of `AiSortService`, the guard method, the repository query, `mayGoToModel` public.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(backend): which imports get the ai sorting card`.

### Task 3: The run — tap, sort payee by payee, progress, controller, counters

**Files:**
- Modify: `backend/src/main/java/com/finora/aisort/AiSortService.java` (start, progress)
- Create: `backend/src/main/java/com/finora/aisort/AiSortRunner.java`, `AiSortController.java`, `backend/src/main/java/com/finora/observability/AiSortMetrics.java`
- Modify: `backend/src/main/java/com/finora/observability/QuickSortMetrics.java` and the Quick sort answer path in `backend/src/main/java/com/finora/transactions/QuickSortService.java` (the "kept or changed" counter below)
- Modify: `backend/src/main/java/com/finora/config/BackgroundWorkConfig.java` (bean `aiSortExecutor`)
- Test: `backend/src/test/java/com/finora/aisort/AiSortRunIT.java` (with the LLM client stubbed as other Fyn ITs do), `AiSortControllerIT` cases may live in the same class.

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `AiSortDto.Choice {SORT, TURN_ON_AND_SORT, NO_THANKS}`; `AiSortDto.StartRequest(@NotEmpty List<UUID> statementImportIds, @NotNull Choice choice)`; `AiSortDto.Run(UUID id, AiSortRun.Status status, int paymentsTotal, int paymentsDone, int paymentsSorted, int paymentsLeft)`.
- Produces endpoints (all for the signed-in caller only):
  - `GET /api/v1/ai-sort/offer?statementImportIds=a,b` → `Offer`.
  - `POST /api/v1/ai-sort/runs` → `Run` (null `data` for `NO_THANKS`).
  - `GET /api/v1/ai-sort/runs/{id}` → `Run` (404 when not the caller's).
- Produces: `AiSortService.start(UUID userId, StartRequest): Optional<Run>`, `AiSortService.run(UUID userId, UUID runId): Run`.

Rules:
- `NO_THANKS`: only valid when the offer is ASK; records `aiImportQuestionAt = now`; no run. `TURN_ON_AND_SORT`: only valid when the offer is ASK; records the stamp, then `TermsConsentService.submit(userId, null, true)`, then as SORT. `SORT`: only valid when the offer is SORT. Anything else → 400 (`askAgainIsRefusedOnceAnswered`).
- The run row is created with `paymentsTotal` = the offer's `payments`, status RUNNING; an existing run for the same user + imports key is returned instead (`secondTapReturnsTheSameRun`). Work is handed to `aiSortExecutor` after commit.
- `aiSortExecutor`: 2 threads, queue 20, `AbortPolicy` turned into a run marked `STOPPED` (never `CallerRunsPolicy`: it would run on the request thread); not waited for on shutdown.
- Runner, per payee in `sortablePayees` order, each payee in its own transaction: check `FynAvailabilityGuard.categorizationAvailableFor(userId)`; if false, finish as `STOPPED_AI_OFF` when consent is no longer true, else `STOPPED_LIMIT`. Call `FynCategorizationFallbackService.suggest(userId, key, EXPENSE, firstRowThatMayGo.description)`. With a model answer, file every row of that user + key + EXPENSE that is still `needsCategoryReview` and not `categoryManuallySet` (this import or earlier) with that category, `DecisionSource.AI_FALLBACK`, category source `CategorizationService.AI_FALLBACK_SOURCE`, confidence `ConfidenceEngine.INITIAL_AI_FALLBACK_CONFIDENCE`, review flag kept; `paymentsSorted += ` that payee's rows from these imports that were filed. Always `paymentsDone +=` its import rows; bump `updatedAt`. End: `DONE`, `finishedAt`.
- Progress read: a RUNNING run whose `updatedAt` is older than 10 minutes reads (and is saved as) `STOPPED`. `paymentsLeft = paymentsTotal - paymentsSorted` once finished, else 0.
- `AiSortMetrics`: counters `finora.ai_sort.offer` (tag `mode`), `finora.ai_sort.choice` (tag `choice`), `finora.ai_sort.run` (tag `status` at finish), `finora.ai_sort.payees` (tag `outcome`: `sorted` / `left`).
- Kept or changed (spec "Measuring"): when Quick sort answers a GUESS question whose anchor is `AI_FALLBACK`, `QuickSortMetrics` counts `finora.quick_sort.ai_guess_answered` with tag `outcome` = `kept` (answer equals `currentCategory`) or `changed`. Test `quickSortCountsAnAiGuessKeptOrChanged` in `QuickSortIT`.

- [ ] **Step 1: Write failing ITs** (model stubbed to answer "Food & Dining" unless a test says otherwise; wait for the run with Awaitility, as other async ITs do):
  - `sortsTheBiggestPayeeFirstAndFilesAsAGuess` — two payees; the stub records call order; rows end `AI_FALLBACK`, category "Food & Dining", `needsCategoryReview` true; run `DONE, paymentsSorted=3, paymentsLeft=0`.
  - `alsoFilesThatPayeesEarlierWaitingRows` — an earlier import's waiting row of the same payee gets the category; `paymentsSorted` counts only this import's rows.
  - `stopsWhenAiIsTurnedOffMidRun` — the stub flips consent to false on its first call → one payee sorted, run `STOPPED_AI_OFF`, stub called once.
  - `stopsAtTheDailyCap` — the stub writes an audit cost over the cap → `STOPPED_LIMIT`.
  - `leavesAPayeeTheCustomerAnsweredMidRun` — the stub sets the second payee's rows to `categoryManuallySet` before its turn → those rows keep their category.
  - `countsAPayeeTheAiCouldNotPlaceAsLeft` — stub answers nothing for one payee → `paymentsLeft` = its rows.
  - `secondTapReturnsTheSameRun` — two POSTs → same id, one run row.
  - `askAgainRecordsYesAndSorts` / `noThanksRecordsTheQuestionAndNeverOffersAgain` — stamp set, consent true / unchanged, audit row `AI_PROCESSING_CONSENT_CHANGED` for yes.
  - `askAgainIsRefusedOnceAnswered` — POST `TURN_ON_AND_SORT` after `NO_THANKS` → 400, consent unchanged.
  - `aStaleRunReadsStopped` — RUNNING with `updatedAt` 11 minutes ago → GET shows `STOPPED`.
  - `anotherCustomersRunIs404`.
- [ ] **Step 2: Run** `./mvnw verify -Dit.test=AiSortRunIT …` — Expected: FAIL.
- [ ] **Step 3: Implement** service start/progress, runner, executor bean, controller, metrics.
- [ ] **Step 4: Run** — Expected: PASS. Then mutation: drop the per-payee availability check → `stopsWhenAiIsTurnedOffMidRun` FAILS; restore.
- [ ] **Step 5: Regenerate OpenAPI** and the three clients' types; `git diff --stat` shows only the new paths/schemas.
- [ ] **Step 6: Commit** `feat(backend): sort an import's shop payments with ai on a tap`.

### Task 4: Web card on the Done step

**Files:**
- Create: `frontend/src/components/AiSortCard.tsx`, `frontend/src/components/AiSortCard.test.tsx`
- Modify: `frontend/src/api/endpoints.ts` (`aiSortApi.offer(ids)`, `start(ids, choice)`, `run(id)`), `frontend/src/types/index.ts` (types from generated schema), `frontend/src/pages/Import.tsx` (render `<AiSortCard statementImportIds=… />` beside both `ImportMoneyInCard` uses, lines ~3013 and ~3106)

**Interfaces:**
- Consumes: Task 3 endpoints.
- Produces: `AiSortCard({ statementImportIds }: { statementImportIds: string[] })` — renders nothing when `offer.show` is false or the offer fails to load.

Behaviour: polls `run(id)` every 2 s while RUNNING; on any finished status invalidates `['quick-sort']`, `['transactions']` and the dashboard queries (use the existing `invalidate…` helper the Done step uses); "check them in Quick sort" is a `Link` to `/app/transactions` with `trackNavigation('transactions', 'contextual')`. After `No thanks` the card disappears. Buttons disabled while a request is in flight.

- [ ] **Step 1: Write failing tests** (one per copy line in Global Constraints): yes → line + `Sort with AI`; singular line; ask → question text + both buttons; `No thanks` → `start(ids,'NO_THANKS')` and card gone; tap → `Sorting… 4 of 12` then `Sorted 9 — check them in Quick sort` + `3 left for you`; `STOPPED_LIMIT`, `STOPPED_AI_OFF`, `STOPPED` texts; offer `show=false` → nothing; offer error → nothing; link tracked.
- [ ] **Step 2: Run** `npx --yes node@22 node_modules/.bin/vitest run src/components/AiSortCard.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement** the API, card, and Done-step placement.
- [ ] **Step 4: Run** the card tests, then the full suite, `npx tsc -b --noEmit`, eslint on touched files, `python3 scripts/check-nav-tracking-coverage.py` — Expected: all pass.
- [ ] **Step 5: Commit** `feat(web): ai sorting card on the import done step`.

### Task 5: Mobile card on the Done step

**Files:**
- Create: `mobile/src/components/AiSortCard.tsx`, `mobile/src/components/AiSortCard.test.tsx`
- Modify: `mobile/src/api/endpoints.ts`, `mobile/src/types/index.ts`, `mobile/src/screens/import/ImportScreen.tsx` (beside `ImportMoneyInCard`, ~line 1697)

**Interfaces:**
- Consumes: Task 3 endpoints.
- Produces: `AiSortCard({ statementImportIds, onOpenQuickSort }: { statementImportIds: string[]; onOpenQuickSort: () => void })`.

Same behaviour and copy as Task 4; `onOpenQuickSort` in ImportScreen does `trackNavigation('review-categories', 'contextual')` and `navigation.navigate('More', { screen: 'CategoryReview' })` (as `QuickSortCta` does); on finish invalidates the quick-sort and financial queries (`invalidateFinancialData`) so `QuickSortCta`'s count refreshes. Buttons 44 pt tall, `accessibilityRole="button"`; progress text in a live region (`accessibilityLiveRegion="polite"`).

- [ ] **Step 1: Write failing tests** mirroring Task 4's list (RNTL; fake timers for polling).
- [ ] **Step 2: Run** `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 node_modules/.bin/jest src/components/AiSortCard.test.tsx` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the card tests, the ImportScreen tests, the full suite, `npx tsc --noEmit`, eslint — Expected: all pass.
- [ ] **Step 5: Commit** `feat(mobile): ai sorting card on the import done step`.

### Task 6: Whole-branch check and PR

- [ ] **Step 1:** Re-read the spec; tick each requirement against a test name. Run the backend suites touched (`AiSort*`, `QuickSortIT`, `TransactionServiceTest`, `LayerDependencyDirectionTest`, the consent ITs), web and mobile full suites, admin-portal `tsc` (after `npm ci` there).
- [ ] **Step 2:** Self-review the whole diff for the Review Focus lines and for any model call path that skips `narrationForModel`.
- [ ] **Step 3:** Update the spec's status line ("built in #…"), commit, merge `origin/main` if it moved (check migration numbers again), push, open the PR.
