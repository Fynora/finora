# Dashboard Quick Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show up to 3 Quick sort questions at the top of the web and mobile Dashboards on every visit, until nothing is waiting, with "Not now" hiding the card until the next local day.

**Architecture:** No new endpoint. The existing Quick sort batch and answer endpoints gain an optional `source` used only as a metrics tag. The existing web `QuickSortCard` and mobile `QuickSortPanel` gain a `variant="dashboard"` mode; a small pure helper per app owns the "Not now" value and the question limit.

**Tech Stack:** Spring Boot + Micrometer (backend), React + Vite + Vitest (web), Expo React Native + Jest/RNTL (mobile), openapi-typescript for generated types.

**Spec:** `docs/superpowers/specs/2026-11-13-dashboard-quick-sort-design.md`

## Global Constraints

- Up to **3** questions per visit, in the server's batch order (biggest money first). Never re-rank on the client.
- "Not now" hides the card until the next **local calendar day**, per device, tied to the signed-in account's email.
- The dashboard variant never offers "Stop asking about these" (keep-rest) and never offers "Sort 10 more".
- Empty batch, failed first fetch, or hidden-for-today: the card renders nothing. No new Dashboard error state.
- Mobile: when the card renders nothing, today's "N transactions need a quick look" nudge shows exactly as now.
- Web: the "Spending needs category review" warning is unchanged.
- Copy: card title "Sort a few payments"; buttons "Skip" and "Not now"; end state "{n} payees still waiting" + "Open Quick sort". Sentence case, no "please".
- Mobile ships with the next store build only (no OTA).
- Commits: conventional commits passing commitlint (lower-case subject, scope from the allowed list), and **no `Co-Authored-By` or any AI trailer** (repo CLAUDE.md).
- Mobile and web tests run under Node 22: `npx --yes node@22 ...` (repo memory: local Node 26 differs from CI).

## Review Focus

1. Day boundary: "Not now" at 23:59 local must show the card again at 00:00 the next day, not 24 hours later — pinned in Tasks 2 and 3 helper tests.
2. Second account on the same device: account B must still see the card after account A pressed "Not now" — pinned in Tasks 2 and 3 helper tests.
3. Storage failure (private window, SecureStore error): the card must still show, never crash — pinned in Tasks 2 and 3 component tests.
4. A batch with fewer than 3 questions (e.g. 1): the card asks 1 and then shows the end state with 0 waiting as "nothing left" (no "0 payees" copy) — pinned in Tasks 2 and 3 component tests.
5. Answer request without `source` from an older app build must keep working and count as `QUICK_SORT` — pinned in Task 1 IT.

---

### Task 1: Server — `source` tag on Quick sort

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortDto.java` (new enum, `AnswerRequest` field)
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortController.java:34-39`
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortService.java:114`
- Modify: `backend/src/main/java/com/finora/observability/QuickSortMetrics.java`
- Create: `backend/src/test/java/com/finora/observability/QuickSortMetricsTest.java`
- Modify: `backend/src/test/java/com/finora/transactions/QuickSortIT.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`

**Interfaces:**
- Produces: `QuickSortDto.Source { QUICK_SORT, DASHBOARD }`; `GET /api/v1/transactions/quick-sort?source=DASHBOARD`; `AnswerRequest(UUID anchorTransactionId, String category, Kind kind, Source source)` with `source` nullable (null = `QUICK_SORT`); counters `finora.quick_sort.batches_shown`, `finora.quick_sort.questions_shown`, `finora.quick_sort.answered` all carry tag `source` (lower-case enum name). Generated TS type `QuickSortSource = 'QUICK_SORT' | 'DASHBOARD'` via `components['schemas']`.

- [ ] **Step 1: Write the failing metrics test** `QuickSortMetricsTest` with a `SimpleMeterRegistry`:
  - `batchShownIsTaggedWithItsSource`: `batchShown(3, Source.DASHBOARD)` → `registry.get("finora.quick_sort.batches_shown").tag("source","dashboard").counter().count() == 1.0` and `questions_shown` with the same tag `== 3.0`.
  - `answeredIsTaggedWithKindAndSource`: `answered("SHOP", Source.QUICK_SORT)` → `registry.get("finora.quick_sort.answered").tag("kind","SHOP").tag("source","quick_sort").counter().count() == 1.0`.
- [ ] **Step 2: Run it, expect a compile failure**
  Run: `cd backend && ./mvnw -q test -Dtest=QuickSortMetricsTest -Dsurefire.failIfNoSpecifiedTests=false`
  Expected: FAIL (no `Source`, wrong signatures).
- [ ] **Step 3: Implement**
  - `QuickSortDto.Source` enum; `AnswerRequest` gains trailing `Source source` (no validation annotation).
  - `QuickSortMetrics.batchShown(int questions, QuickSortDto.Source source)` and `answered(String kind, QuickSortDto.Source source)`; tag value `source.name().toLowerCase(Locale.ROOT)`. Every registration of these three counter names carries the `source` tag (Prometheus needs one tag-key set per name).
  - Controller: `@RequestParam(defaultValue = "QUICK_SORT") QuickSortDto.Source source`, passed to `batchShown`.
  - Service line 114: `metrics.answered(req.kind().name(), req.source() == null ? Source.QUICK_SORT : req.source())`.
  - Fix any other caller the compiler reports (`QuickSortServiceTest` mocks the metrics; Mockito matchers may need `any()` for the new argument).
- [ ] **Step 4: Run the metrics test, expect PASS** (same command).
- [ ] **Step 5: Add two cases to `QuickSortIT`:**
  - `answerWithoutSourceStillFilesThePayee`: the existing JSON body (no `source`) → `filed == 3` (reuse `answeringFilesEveryWaitingPaymentOfThatPayeeAndRemembersIt`'s seed shape).
  - `dashboardSourceIsAcceptedOnBatchAndAnswer`: `GET /api/v1/transactions/quick-sort?source=DASHBOARD` returns the same question ids as `batch(0)`; posting an answer with `"source":"DASHBOARD"` returns `filed == 3`.
- [ ] **Step 6: Run** `cd backend && ./mvnw -q verify -Dit.test=QuickSortIT -Dtest=QuickSortServiceTest,QuickSortMetricsTest -Dsurefire.failIfNoSpecifiedTests=false` — Expected: all PASS. (Repo memory: `mvn test` skips ITs; never commit while `verify` runs.)
- [ ] **Step 7: Regenerate the API contract:** `backend/scripts/generate-openapi-spec.sh`, then `npm run generate:types` in `frontend/` and in `mobile/`. Check `git diff --stat` shows only the Quick sort schema changes (`source` param, `AnswerRequest.source`, the enum).
- [ ] **Step 8: Commit**
```bash
git add backend/src backend/openapi/openapi.json frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts
git commit -m "feat(backend): tag quick sort counters with where the question was asked"
```

### Task 2: Web — Dashboard variant of `QuickSortCard`

**Files:**
- Create: `frontend/src/lib/dashboardQuickSort.ts`, `frontend/src/lib/dashboardQuickSort.test.ts`
- Modify: `frontend/src/api/endpoints.ts:328-333` (`quickSort`, `quickSortAnswer`)
- Modify: `frontend/src/components/QuickSortCard.tsx`, `frontend/src/components/QuickSortCard.test.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx` (mount at the top of the returned page, before the first summary section)

**Interfaces:**
- Consumes: `QuickSortSource` from Task 1's generated types.
- Produces (identical names in Task 3):
  - `DASHBOARD_QUICK_SORT_KEY = 'finora_dashboard_quick_sort_hidden'`
  - `DASHBOARD_QUESTION_LIMIT = 3`
  - `localDateKey(now: Date): string` → `YYYY-MM-DD` from local date parts
  - `notNowValue(email: string | null, now: Date): string` → `` `${(email ?? '').toLowerCase()}|${localDateKey(now)}` ``
  - `isHiddenToday(stored: string | null, email: string | null, now: Date): boolean` → `stored === notNowValue(email, now)`
  - `transactionsApi.quickSort(skip = 0, preview = false, source?: QuickSortSource)`; `quickSortAnswer(anchorTransactionId, category, kind, source?: QuickSortSource)` — `source` sent only when given.
  - `<QuickSortCard variant?: 'full' | 'dashboard' />` (default `'full'`, today's behaviour untouched).

- [ ] **Step 1: Write failing helper tests** (`dashboardQuickSort.test.ts`):
  - `hiddenForTheRestOfTheSameDay`: value from `notNowValue('A@example.com', new Date(2026,10,13,9,0))` → `isHiddenToday(v,'a@example.com', new Date(2026,10,13,23,59)) === true`.
  - `backAtLocalMidnight`: same value, `now = new Date(2026,10,14,0,0)` → `false`.
  - `anotherAccountStillSeesIt`: value for `'a@example.com'`, check with `'b@example.com'` same day → `false`.
  - `nothingStoredMeansShown`: `isHiddenToday(null, 'a@example.com', now) === false`.
- [ ] **Step 2: Run** `cd frontend && npx --yes node@22 node_modules/vitest/vitest.mjs run src/lib/dashboardQuickSort.test.ts` — Expected: FAIL (module missing).
- [ ] **Step 3: Implement the helper** with the signatures above. Step 4: rerun, expect PASS.
- [ ] **Step 5: Write failing card tests** in `QuickSortCard.test.tsx` (mock `transactionsApi` as the file already does; mock `useAuth` to return `email: 'a@example.com'`):
  - `dashboardAsksAtMostThreeThenShowsWhatIsLeft`: batch with 5 questions and `rest.questions = 4` → after answering 3, text `6 payees still waiting` and a link `Open Quick sort` to `/app/transactions`; the fourth payee is never shown.
  - `dashboardSendsTheDashboardSource`: `quickSort` called with `(0, false, 'DASHBOARD')`; an answer calls `quickSortAnswer(anchor, category, kind, 'DASHBOARD')`.
  - `dashboardNeverOffersStopAskingOrSortMore`: no `Stop asking about these` and no `Sort 10 more` at any point.
  - `notNowHidesTheCardAndRemembersToday`: click `Not now` → card gone; `localStorage[DASHBOARD_QUICK_SORT_KEY] === notNowValue('a@example.com', now)`.
  - `hiddenTodayRendersNothingAndDoesNotFetch`: storage pre-set for today → nothing rendered, `quickSort` not called.
  - `storageThrowingStillShowsTheCard`: `localStorage.getItem` throws → card renders the first question.
  - `oneQuestionBatchEndsWithoutAZeroCount`: 1 question, `rest.questions = 0` → after answering, the card renders nothing (no `0 payees` text).
  - `emptyOrFailedBatchRendersNothing`: empty questions → null; rejected fetch → null and no error text.
  - Existing full-variant tests stay green unchanged.
- [ ] **Step 6: Run** `npx --yes node@22 node_modules/vitest/vitest.mjs run src/components/QuickSortCard.test.tsx` — Expected: new tests FAIL.
- [ ] **Step 7: Implement `variant="dashboard"` in `QuickSortCard.tsx`:** read storage via `safeStorage.getItem(DASHBOARD_QUICK_SORT_KEY)` and `useAuth().email` before the first fetch; slice the batch's questions to `DASHBOARD_QUESTION_LIMIT`; pass `'DASHBOARD'` as source; hide the progress bar, "Sort 10 more", "Ask skipped again" and "Stop asking about these"; add `Not now` beside `Skip`; end state when the 3 are done: remaining = `batch.questions.length - shown + batch.rest.questions`; if 0 render nothing, else the end-state copy and link. Secondary button style only.
- [ ] **Step 8: Mount** `<QuickSortCard variant="dashboard" />` at the top of `Dashboard.tsx`'s page. Add one `Dashboard.test.tsx` case `rendersDashboardQuickSortAtTheTop` if that file mocks `transactionsApi`; otherwise assert via the card's own tests only and note it.
- [ ] **Step 9: Run the web gates:** `npx --yes node@22 node_modules/vitest/vitest.mjs run`, `npx --yes node@22 node_modules/typescript/bin/tsc --noEmit`, `npx --yes node@22 node_modules/eslint/bin/eslint.js . --max-warnings 0` (check `package.json` scripts for the exact lint invocation). Expected: all clean.
- [ ] **Step 10: Commit**
```bash
git add frontend/src
git commit -m "feat(web): ask a few quick sort questions on the dashboard"
```

### Task 3: Mobile — Dashboard variant of `QuickSortPanel`

**Files:**
- Create: `mobile/src/lib/dashboardQuickSort.ts`, `mobile/src/lib/dashboardQuickSort.test.ts` (same exports and tests as Task 2's helper)
- Modify: `mobile/src/api/endpoints.ts:311-316`
- Modify: `mobile/src/components/QuickSortPanel.tsx`, `mobile/src/components/QuickSortPanel.test.tsx`
- Modify: `mobile/src/screens/DashboardScreen.tsx:544-560` (nudge block), `mobile/src/screens/DashboardScreen.test.tsx`

**Interfaces:**
- Consumes: Task 1's `QuickSortSource`; Task 2's helper names and semantics (copy, not import — the apps share no code).
- Produces: `<QuickSortPanel variant?: 'full' | 'dashboard' onLoaded?: (questions: number) => void onOpenFull?: () => void />`. In the dashboard variant `onLoaded(0)` is also called when hidden for today, when the first fetch fails, after `Not now`, and when the last question leaves nothing waiting — i.e. whenever the panel renders nothing.

- [ ] **Step 1: Helper tests** — the four Task 2 helper tests, in Jest. Note `safeStorage` here is async (`expo-secure-store`). Run `cd mobile && NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js src/lib/dashboardQuickSort.test.ts --forceExit` — FAIL, then implement, then PASS.
- [ ] **Step 2: Write failing panel tests** (`QuickSortPanel.test.tsx`, mocking `safeStorage` and `useAuth`): the same eight behaviours as Task 2 Step 5, with `onOpenFull` called from `Open Quick sort`, plus `dashboardReportsNothingToShowSoTheNudgeCanReturn`: hidden-for-today, failed fetch and `Not now` each call `onLoaded(0)`. Use `not.toBeOnTheScreen()` rather than `waitFor(...toBeNull())` (repo memory: the latter blocks timers).
- [ ] **Step 3: Run** `NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js src/components/QuickSortPanel.test.tsx --forceExit` — Expected: new tests FAIL.
- [ ] **Step 4: Implement `variant="dashboard"`** as in Task 2 Step 7, with `await safeStorage.getItem` before the first fetch (render nothing until it resolves), `CategoryPickerModal` opened from the panel only (never inside another open modal), `onOpenFull` for the end-state link.
- [ ] **Step 5: Wire `DashboardScreen`:** state `quickSortShowing: boolean | null` (null while checking). Render `<QuickSortPanel variant="dashboard" onLoaded={(n) => setQuickSortShowing(n > 0)} onOpenFull={...same navigation as the nudge: trackNavigation('review-categories','contextual') then More > CategoryReview} />` where the nudge sits; render the existing nudge only when `quickSortShowing === false`.
- [ ] **Step 6: DashboardScreen tests:** `showsQuickSortInPlaceOfTheNudge` (batch with questions → panel visible, nudge text `need a quick look` not on screen) and `nudgeReturnsWhenQuickSortHasNothing` (empty batch → nudge visible). Run the file — PASS.
- [ ] **Step 7: Run the mobile gates:** full Jest suite (`NODE_OPTIONS=--experimental-vm-modules npx --yes node@22 ./node_modules/jest/bin/jest.js --forceExit`), `tsc --noEmit` and lint under Node 22 (exact scripts from `mobile/package.json`). Expected: all clean.
- [ ] **Step 8: Commit**
```bash
git add mobile/src
git commit -m "feat(mobile): ask a few quick sort questions on the dashboard"
```

### Task 4: End-to-end verification

**Files:** none changed unless a check finds a bug (then fix in the owning task's files and re-run that task's gates).

- [ ] **Step 1: Backend full run:** `cd backend && ./mvnw -q verify` (heavy; repo memory: at most 2 heavy jobs on this Mac). Expected: BUILD SUCCESS.
- [ ] **Step 2: Web in a browser against a local stack** (`node e2e/scripts/stack.mjs up`; vite on port 5173 with `FINORA_API_PROXY_TARGET=http://localhost:8081`), seeded user with ≥5 waiting payees: card at top, 3 questions max, answer files the payee (Ledger shows it), Skip, Not now hides until a changed date, end state link opens `/app/transactions`, dark mode, phone width 375px, no console errors. Screenshot as proof.
- [ ] **Step 3: Mobile on a simulator** if the build works in this worktree (repo memory: Firebase config is gitignored and prebuild may fail). If it cannot run, say so explicitly; the RNTL tests are then the only mobile evidence, and largest-text and dark-mode checks remain owed on Sid's device with the store build.
- [ ] **Step 4: Re-read the spec** and tick each requirement against a test or a check from this task; list anything deferred with the reason.
