# Smarter Person Question Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quick sort questions (full and Dashboard card, web and mobile) show a tappable pattern line ("14 payments since Aug · usually ₹150–₹400") that opens the payee's latest payments, and person questions offer Groceries, Transport and Rent after the neutral answers.

**Architecture:** The server adds pattern numbers and up to 10 recent payments to each one-payee question, computed in one pure helper from one query per batch. Clients only format and toggle. The existing `samples` field stays as it is for older app builds.

**Tech Stack:** Spring Boot (backend), React + Vitest (web), Expo React Native + Jest/RNTL (mobile), openapi-typescript.

**Spec:** `docs/superpowers/specs/2026-11-14-smarter-person-question-design.md`

## Global Constraints

- PERSON_PAID defaults, in order: `Personal Transfer, Friend Repayment, Groceries, Transport, Rent`; the customer's own past picks still come first; at most 5 (`MAX_ANSWERS`); a category the customer no longer has is never offered.
- Pattern only for a question whose group id is a one-payee key (not `row:…`).
- Pattern counts all live payments to that payee in that direction on live accounts, excluding `DUPLICATE` rows — not only waiting rows.
- Range: 4+ payments → nearest-rank 25th–75th percentile (`sorted[ceil(0.25n)-1]`, `sorted[ceil(0.75n)-1]`); 2–3 → min–max; 1 → none; absolute amounts, rounded HALF_UP to whole rupees on the server.
- Copy: `{n} payments since {Mon}` (+ ` {YYYY}` when the first payment's year differs from the latest payment's year) then ` · usually ₹{low}–₹{high}`, or ` · usually ₹{x}` when low = high; one payment: `1 payment in {Mon}` (same year rule), no "usually". Amounts with `en-IN` grouping.
- Recent list: newest first, at most 10, each `{date} · ₹{rounded amount} · {narration}`; `See all {n} in Transactions` only when n > list length.
- No new categories, no change to which rows wait or their order, no change to how answers file.
- Commits: commitlint-clean (lower-case subject, allowed scope), **no `Co-Authored-By` or any AI trailer**. Node tooling under `npx --yes node@22`. Mobile ships with the next store build only. Repo is public: no production figures in code, docs or PR text.

## Review Focus

1. A payee whose earlier payments are already sorted (not waiting): the pattern still counts them — pinned in Task 1 `patternCountsPaymentsThatAreNotWaiting`.
2. A payment on a deleted account or marked DUPLICATE must not inflate the count — Task 1 `patternSkipsDuplicatesAndOtherAccounts`.
3. First payment in the previous year: "since Dec 2025" — Tasks 2 and 3 `showsYearWhenFirstPaymentIsInAnotherYear`.
4. Older server/question without pattern fields: the card falls back to today's line and shows no toggle — Tasks 2 and 3 `fallsBackWithoutPattern`.
5. "See all" with no handler (mobile Import screen) shows no link rather than a dead one — Task 3 `noSeeAllWithoutHandler`.

---

### Task 1: Server — pattern and wider person answers

**Files:**
- Create: `backend/src/main/java/com/finora/transactions/PayeePattern.java`, `backend/src/test/java/com/finora/transactions/PayeePatternTest.java`
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortDto.java` (`Question`), `QuickSortService.java` (`PERSON_DEFAULTS`, `batch`, `question`)
- Test: `backend/src/test/java/com/finora/transactions/QuickSortServiceTest.java`, `QuickSortIT.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend|mobile|admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Produces: `record PayeePattern(int payments, LocalDate firstDate, LocalDate latestDate, BigDecimal typicalLow, BigDecimal typicalHigh, List<QuickSortDto.Sample> recent)` with `static PayeePattern of(List<Transaction> payments)` (input any order; `typicalLow/High` null for one payment).
- `QuickSortDto.Question` gains trailing nullable fields `Integer allPayments, LocalDate firstDate, BigDecimal typicalLow, BigDecimal typicalHigh, List<Sample> recent` (all null/empty for `row:` questions). `latestDate` stays the waiting anchor's date as today.

- [ ] **Step 1: Failing `PayeePatternTest`:**
  - `sevenPaymentsUseTheMiddleHalf`: amounts 100,150,200,250,300,400,1000 → low 150, high 400, payments 7.
  - `twoOrThreePaymentsUseLowestToHighest`: 120.40, 300 → 120, 300; three payments 50,75,90 → 50, 90.
  - `onePaymentHasNoRange`: low and high null.
  - `fourPaymentsBoundary`: 10,20,30,40 → low 10 (`ceil(1)-1=0`), high 30 (`ceil(3)-1=2`).
  - `roundsHalfUpToWholeRupees`: 149.50 → 150.
  - `recentIsNewestFirstAndCappedAtTen`: 12 payments → 10 recent, first is the latest date; first/latest dates correct.
  - `usesAbsoluteAmounts`: negative stored amounts give positive range.
- [ ] **Step 2:** `cd backend && ./mvnw -q test -Dtest=PayeePatternTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (class missing). Implement; rerun → PASS.
- [ ] **Step 3: Failing `QuickSortServiceTest` cases:** `personQuestionOffersNeutralThenSpendingAnswers` (no own picks → exactly `[Personal Transfer, Friend Repayment, Groceries, Transport, Rent]`); `personQuestionSkipsADeletedCategory` (no Rent category → Rent absent, still ≤5); `ownPicksStillComeFirstForPeople`. Run → FAIL; change `PERSON_DEFAULTS`; PASS.
- [ ] **Step 4: Wire `batch`:** collect the one-payee keys of the questions being built; one `transactionRepository.findByUserIdAndCounterpartyKeyIn(userId, keys)`; per question keep rows of the same `txnType`, on `liveAccountIds`, not `DUPLICATE`; pass `PayeePattern.of(...)` into `question(...)`.
- [ ] **Step 5: `QuickSortIT` cases:** `patternCountsPaymentsThatAreNotWaiting` (2 waiting + 2 already sorted for one key → `allPayments` 4, `recent` size 4); `patternSkipsDuplicatesAndOtherAccounts`; `rowQuestionHasNoPattern` (key that names no one payee → `allPayments` null, `recent` empty). Run `./mvnw -q verify -Dit.test=QuickSortIT -Dtest='PayeePatternTest,QuickSortServiceTest' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` → PASS.
- [ ] **Step 6:** Rebuild jar, regenerate `openapi.json` (needs a Postgres; `DB_PORT` to a throwaway container), then `npx --yes openapi-typescript@7.13.0 ../backend/openapi/openapi.json -o src/api/generated-types.ts` in `frontend/`, `mobile/`, `admin-portal/`. Diff shows only the new Question fields.
- [ ] **Step 7: Commit** `feat(backend): quick sort shows each payee's pattern and offers spending answers for people`.

### Task 2: Web — pattern line and payments list

**Files:**
- Create: `frontend/src/lib/payeePattern.ts`, `frontend/src/lib/payeePattern.test.ts`
- Modify: `frontend/src/types/index.ts` (`QuickSortQuestion` new optional fields), `frontend/src/components/QuickSortCard.tsx`, `QuickSortCard.test.tsx`

**Interfaces:**
- Consumes: Task 1 Question fields.
- Produces: `patternLine(q: QuickSortQuestion): string | null` (null when `allPayments` is null); `seeAllHref(payee: string): string` → `/app/transactions?q=<encoded payee>`.

- [ ] **Step 1: Failing helper tests:** `formatsCountMonthAndRange` (14, first 2026-08-03, latest 2026-11-12, 150/400 → `14 payments since Aug · usually ₹150–₹400`); `showsYearWhenFirstPaymentIsInAnotherYear` (first 2025-12-20 → `since Dec 2025`); `oneValueWhenEndsMatch` (`usually ₹200`); `onePayment` (`1 payment in Nov`); `groupsLargeAmounts` (1500/12000 → `₹1,500–₹12,000`); `nullWithoutPattern`. Run vitest on the file → FAIL; implement; PASS.
- [ ] **Step 2: Failing card tests (both variants):** pattern line renders as a button with `aria-expanded="false"`; clicking lists `recent` rows (`{date} · ₹{amount} · {description}`) and sets `aria-expanded="true"`; clicking again hides them; `See all 14 in Transactions` link with `href` from `seeAllHref` appears only when `allPayments > recent.length`, and clicking it calls `trackNavigation('transactions', 'contextual')`; answering with the list open still sends the answer; `fallsBackWithoutPattern` (no new fields → today's `N payments · ₹total` line, no toggle; full variant keeps its old "Show payments" samples block only in this fallback).
- [ ] **Step 3:** Implement in `QuickSortCard.tsx` (one toggle state per question, reset when the question changes). Run card tests → PASS.
- [ ] **Step 4: Gates:** full vitest, `tsc --noEmit`, eslint `--max-warnings 0`, `python3 -I scripts/check-nav-tracking-coverage.py` → all clean.
- [ ] **Step 5: Commit** `feat(web): quick sort shows each payee's pattern and their payments`.

### Task 3: Mobile — pattern line and payments list

**Files:**
- Create: `mobile/src/lib/payeePattern.ts`, `mobile/src/lib/payeePattern.test.ts` (same `patternLine` and tests as Task 2; the apps share no code)
- Modify: `mobile/src/types/index.ts`, `mobile/src/components/QuickSortPanel.tsx`, `QuickSortPanel.test.tsx`, `QuickSortPanel.dashboard.test.tsx`, `mobile/src/screens/DashboardScreen.tsx`, `mobile/src/screens/CategoryReviewScreen.tsx`

**Interfaces:**
- Consumes: Task 1 fields; Task 2 `patternLine` semantics.
- Produces: `QuickSortPanel` prop `onSeeAll?: (payee: string) => void`; the link renders only when it is given. Callers navigate `navigation.navigate('Transactions', { filters: { keyword: payee, label: payee, nonce: Date.now() } })` after `trackNavigation('transactions', 'contextual')`.

- [ ] **Step 1:** Helper tests (Jest, Node 22) → FAIL → implement → PASS.
- [ ] **Step 2: Failing panel tests** mirroring Task 2 Step 2 (`accessibilityState={{ expanded }}` instead of `aria-expanded`), plus `noSeeAllWithoutHandler`. Use `not.toBeOnTheScreen()`. Run → FAIL; implement; PASS.
- [ ] **Step 3:** Pass `onSeeAll` from `DashboardScreen` (dashboard panel) and `CategoryReviewScreen` (full panel); `ImportScreen` passes none. Add one test per screen that pressing `See all` navigates with `filters.keyword` = payee.
- [ ] **Step 4: Gates:** full Jest, `tsc --noEmit`, `expo lint --max-warnings 0`, nav-tracking script → clean.
- [ ] **Step 5: Commit** `feat(mobile): quick sort shows each payee's pattern and their payments`.

### Task 4: Verify end to end

- [ ] **Step 1:** Backend `./mvnw -q verify` → BUILD SUCCESS.
- [ ] **Step 2:** Local stack + vite (5173) in Chrome with a synthetic user (one person paid 12+ times across two calendar years, some already sorted): pattern line text, list opens/closes, 10 rows newest first, "See all" opens Transactions with the payee searched and shows those payments, person answers list, dark and light. Screenshot.
- [ ] **Step 3:** Confirm the Transactions search actually finds the payee by its label (web `?q=`; mobile `keyword`); if it does not, record what it matches and fix or drop the link with a ruling.
- [ ] **Step 4:** Re-read the spec; tick every requirement against a test or check; list anything deferred with why. Mobile on a device stays owed to the store build.
