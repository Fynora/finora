# Mobile Insights Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Sid's rule for this repository: executing-plans, inline, no agents.

**Goal:** Replace the mobile Insights tabs with one screen of what's worth knowing: questions, "To look at", "Coming up" and "Paid regularly", with card bills never shown as a spike and a server-stored "Got it".

**Architecture:** Two PRs. PR A (server) adds a shared card-bill predicate, keeps card bills out of insight movers, adds `unlinkedCardBills` to `/insights`, and adds an `insight_acknowledgements` table with GET/POST. PR B (mobile) moves all row-building into a pure module (`lib/insightsFeed.ts`), renders it in a rewritten `InsightsScreen`, and adds the tab dot. PR B tolerates a server without PR A (missing field, 404 on acknowledgements).

**Tech Stack:** Spring Boot / JPA / Flyway / JUnit 5 + Testcontainers (backend); Expo SDK 57 / RN 0.86 / TanStack Query / Jest 30 + RNTL (mobile).

**Spec:** `docs/superpowers/specs/2026-10-16-mobile-insights-redesign-design.md`

## Global Constraints

- Card bill = linked `CC_PAYMENT` OR category named "Credit Card bill" (case-insensitive) OR description matching `BankActivityCategory`'s card-bill patterns. Card bills stay in spending totals; never a mover.
- "Running high": `pctChange >= 15` AND `current - priorAverage >= 500`, at most 3, largest rupee gap first; no percentage in the wording.
- Amounts through `fmtCurrency` (Indian grouping); short months through `withSep` (already in `lib/format.ts`, #2251 merged).
- Acknowledgement kinds `CATEGORY_HIGH` (`<category>|<yyyy-MM>`), `AMOUNT_CHANGED` (`<merchant trimmed lower-cased>|<latestDate>`), `CARD_STATEMENT_MISSING` (`<yyyy-MM>`).
- Monthly equivalents: Weekly × 52 / 12, Biweekly × 26 / 12, Monthly × 1, Quarterly / 3.
- Mobile ships with binary 1.1.0; never an OTA to 1.0.0.
- Every navigation the screen starts calls `trackNavigation` (`scripts/check-nav-tracking-coverage.py`).
- Commit messages: commitlint scopes (`backend`, `mobile`, `db`, ...), lower-case subject, no AI-attribution trailer.
- Migration version taken from `origin/main` at build time (V275 is the latest on 2026-10-16); never reuse one.
- Backend tests through `mvn verify` for ITs (memory: `mvn test` skips ITs); mobile jest through `NODE_OPTIONS=--experimental-vm-modules npx -y node@22 node_modules/.bin/jest`.
- No real names or amounts in repo text, tests, commits or PRs.

## Review Focus

1. A recurring payment whose `nextEstimate` is before today must never be counted in "Still to go out this month", even when it falls in the current calendar month (e.g. today 16 Oct, estimate 5 Oct). Pinned in Task 4.
2. The same payee arriving as both `state === 'AMOUNT_CHANGED'` in `/recurring` and in `/recurring/changed-amounts` must show once. Pinned in Task 4.
3. An older server: `unlinkedCardBills` absent and `/insights/acknowledgements` 404 must render the screen with nothing hidden and no error. Pinned in Task 6.
4. A card bill under a category other than "Credit Card bill" (e.g. "Other" with a CRED description) must still never be a mover and must count in `unlinkedCardBills`. Pinned in Task 2.
5. "Got it" tapped twice quickly, or for a key already stored, must not error (idempotent insert). Pinned in Task 3.

---

## PR A: server

### Task 1: One card-bill predicate

**Files:**
- Modify: `backend/src/main/java/com/finora/util/BankActivityCategory.java` (expose the existing patterns)
- Create: `backend/src/main/java/com/finora/util/CardBills.java`
- Test: `backend/src/test/java/com/finora/util/BankActivityCategoryTest.java`, `backend/src/test/java/com/finora/util/CardBillsTest.java`

**Interfaces:**
- Produces: `BankActivityCategory.paysCardBill(String description): boolean` (true for `CARD_BILL_PAID`, or `NET_BANKING_BILL_PAY` with `MASKED_CARD_NUMBER`; false for null/blank). `of(...)` calls it instead of its inline condition.
- Produces: `CardBills.isCardBill(Transaction t, Map<UUID, Category> categoriesById): boolean` — EXPENSE only; category name equals "Credit Card bill" ignoring case, or `paysCardBill(t.getDescription())`. (Linked payments never reach it: the pipeline already removed them.)

- [ ] **Step 1: Write failing tests**
  - `paysCardBill_trueForCardBillAppAndSelfTransferWording`: `"UPI/CREDCLUB/100000000001/payment"` → true; `"CC BILLPAY 4000XXXXXX0001"` → true.
  - `paysCardBill_netBankingNeedsAMaskedCard`: `"IB BILLPAY 4000XXXXXX0001"` → true; `"IB BILLPAY ELECTRICITY 100000000001"` → false.
  - `paysCardBill_falseForNullAndBlank`.
  - `CardBillsTest.categoryNameMatchesIgnoringCase` ("credit card BILL" → true), `descriptionAloneMarksIt` (category "Other", description `"UPI/CREDCLUB/..."` → true), `neither_false`, `incomeIsNeverACardBill`.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest='BankActivityCategoryTest,CardBillsTest'` — Expected: compile failure (methods missing).
- [ ] **Step 3: Implement** both methods; `of()` keeps returning "Transfer" for the same inputs (existing `BankActivityCategoryTest` cases unchanged).
- [ ] **Step 4: Run** the same command — Expected: all pass, including the existing cases.
- [ ] **Step 5: Commit** `feat(backend): one card-bill predicate shared by insights and categorising`

### Task 2: Insights leave card bills out of movers and report unlinked card bills

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/InsightsDto.java`, `backend/src/main/java/com/finora/service/InsightsService.java`, `backend/src/test/java/com/finora/service/FynInsightsNarrationServiceTest.java` (constructor arity)
- Regenerate: `backend/openapi/openapi.json` via `backend/scripts/generate-openapi-spec.sh`
- Test: `backend/src/test/java/com/finora/service/InsightsServiceTest.java`

**Interfaces:**
- Consumes: `CardBills.isCardBill` (Task 1).
- Produces: `InsightsDto.UnlinkedCardBills(String month, BigDecimal total, int count)` as a new last component `unlinkedCardBills` (null when none). JSON: `unlinkedCardBills: { month, total, count } | null`.

Approach: in `build`, compute `currentByCat`/`priorByCat` for movers from the card-bill-free txns; keep the full maps for the total sentence and `biggestCategory`. The budget-recommendation, mover and "new category" sentences all read the filtered movers. `unlinkedCardBills` sums `refunds.spendAmount` over current-month card bills in `txns`.

- [ ] **Step 1: Write failing tests** (existing `givenTransactions` helper)
  - `cardBillCategory_isNeverAMover`: prior months 1 small "Credit Card bill" row, current month a large one → `movers` has no "Credit Card bill"; no sentence contains "Credit Card bill spend was".
  - `cardBillByDescription_isNeverAMover_evenUnderOther`: a CRED-described row in "Other" growing 10× → no mover for it from that row (the "Other" mover, if any, excludes its amount).
  - `cardBills_stayInTheTotalSentence`: total sentence equals all spend including the card bill.
  - `unlinkedCardBills_sumsCurrentMonthCardBills`: two card bills (one by category, one by description) → `month` = current, `total` = sum, `count` = 2.
  - `unlinkedCardBills_nullWithoutCardBills`.
  - `linkedCardPayment_isNotUnlinked`: stub `transactionGraphService.ccPaymentFromTransactionIds` to return the bill's id → `unlinkedCardBills` null.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=InsightsServiceTest` — Expected: FAIL (no field / card bill still a mover).
- [ ] **Step 3: Implement** as described; update both `new InsightsDto(...)` calls and the narration test.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest='InsightsService*Test,FynInsightsNarrationServiceTest'` — Expected: PASS. Then `./scripts/generate-openapi-spec.sh` and confirm `git diff --stat backend/openapi/openapi.json` shows only the new schema.
- [ ] **Step 5: Commit** `feat(backend): card bills are never an insight mover; insights report unlinked card bills`

### Task 3: "Got it" acknowledgements

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__insight_acknowledgements.sql`, `backend/src/main/java/com/finora/entity/InsightAcknowledgement.java`, `backend/src/main/java/com/finora/repository/InsightAcknowledgementRepository.java`, `backend/src/main/java/com/finora/dto/InsightAcknowledgementDto.java`
- Modify: `backend/src/main/java/com/finora/controller/InsightsController.java`, `backend/src/main/java/com/finora/service/AccountPurgeSweepService.java`, `backend/src/main/java/com/finora/service/DataExportService.java`, `backend/src/main/java/com/finora/dto/DataExportDto.java`, `backend/openapi/openapi.json`
- Test: `backend/src/test/java/com/finora/controller/InsightAcknowledgementsIT.java`; extend the purge and export tests that already cover `recurring_dismissals` (find them with `grep -rln recurringDismissal backend/src/test`).

**Interfaces:**
- Table (copy `recurring_dismissals`' shape and ON DELETE CASCADE): `id UUID PK`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `kind VARCHAR(40) NOT NULL CHECK (kind IN ('CATEGORY_HIGH','AMOUNT_CHANGED','CARD_STATEMENT_MISSING'))`, `item_key VARCHAR(300) NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `UNIQUE (user_id, kind, item_key)`, index on `user_id`.
- Repository: `findByUserId(UUID)`, `insertIfAbsent(userId, kind, itemKey)` (native `ON CONFLICT DO NOTHING`, as `RecurringDismissalRepository`), `deleteByUserId(UUID)`.
- `InsightAcknowledgementDto(String kind, String itemKey)`; request validated: `kind` one of the three, `itemKey` not blank, ≤ 300 chars → else 400.
- `GET /api/v1/insights/acknowledgements` → `ApiResponse<List<InsightAcknowledgementDto>>` (signed-in user's only); `POST` same path, body `InsightAcknowledgementDto` → `ApiResponse<Void>`.

- [ ] **Step 1: Check the migration number:** `git fetch origin && git ls-tree --name-only origin/main backend/src/main/resources/db/migration/ | sort -V | tail -1`; use the next one.
- [ ] **Step 2: Write failing IT** (`AbstractIntegrationTest`, `TestSessions` as in `InsightsControllerIT`)
  - `postThenGet_returnsTheKey`; `postTwice_isIdempotent` (both 200, one row); `getReturnsOnlyOwnKeys` (second user sees nothing); `unknownKind_is400`; `blankKey_is400`; `unauthenticated_is401`.
  - Purge test: an acknowledgement row is gone after the sweep. Export test: `insight_acknowledgements.json` lists it.
- [ ] **Step 3: Run** `./mvnw -q verify -Dit.test=InsightAcknowledgementsIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` — Expected: FAIL (404).
- [ ] **Step 4: Implement**; purge line next to `recurringDismissalRepository.deleteByUserId(userId)` with the same anonymize-not-delete comment; export entry + manifest line "Insights you marked as seen."
- [ ] **Step 5: Run** the IT, the purge/export tests, then the full `./mvnw verify` (no commits while it runs) — Expected: all pass. Regenerate `openapi.json`.
- Change-sync stamp: not joined. `recurring_dismissals`, the same kind of per-user preference, is in no stamp either (checked 2026-10-16 with `grep -rln recurring_dismissals backend/src/main/java`); the screen refetches acknowledgements on focus and pull.
- [ ] **Step 6: Commit** `feat(backend): remember which insights a person marked as seen`
- [ ] **Step 7: PR A** — push, open PR, verify CI; after merge check `headRefOid` and content on main.

---

## PR B: mobile (fresh worktree from main after PR A merges)

### Task 4: The pure feed builder

**Files:**
- Modify: `mobile/src/api/endpoints.ts` (types and `insightsApi.acknowledgements()`, `insightsApi.acknowledge(kind, itemKey)`); run `npm run generate:types` in `mobile/`
- Create: `mobile/src/lib/insightsFeed.ts`
- Test: `mobile/src/lib/insightsFeed.test.ts`

**Interfaces:**
- Produces types: `AckKind = 'CATEGORY_HIGH' | 'AMOUNT_CHANGED' | 'CARD_STATEMENT_MISSING'`; `InsightsData.unlinkedCardBills?: { month: string; total: number; count: number } | null`.
- Produces:
  - `ackKey(kind: AckKind, parts: { category?: string; month?: string; merchant?: string; date?: string }): string` — keys exactly as in Global Constraints.
  - `buildToLookAt(input: { insights?: InsightsData; recurring: RecurringItem[]; changed: ChangedAmountItem[]; acks: Set<string> /* `${kind}|${itemKey}` */ }): LookAtRow[]` where `LookAtRow = { kind: 'card-statement'; month; total; count } | { kind: 'new-recurring'; item: RecurringItem } | { kind: 'amount-changed'; merchant; category; latestAmount; latestDate; state: 'AMOUNT_CHANGED' } | { kind: 'running-high'; category; current; priorAverage; month }`, each also carrying `ackKind`/`ackItemKey` when it can be dismissed. Order: card statement, new recurring, amount changed, running high.
  - `comingUp(recurring: RecurringItem[], today: string /* YYYY-MM-DD */): { rows: { item: RecurringItem; past: boolean }[]; stillToGoOut: number }` — soonest first; `stillToGoOut` sums `averageAmount` of rows with `nextEstimate >= today` and in today's calendar month.
  - `paidRegularly(recurring: RecurringItem[]): { rows: { item; monthly: number; rising: boolean }[]; monthlyTotal: number; yearlyTotal: number }` — `rising` when `latestAmount > averageAmount`.
- [ ] **Step 1: Write failing tests**
  - running high: `{Food, current 9400, priorAverage 6300, pct 49}` kept; `{Fuel, 800, 400, 100}` dropped (gap < 500); `{X, 20000, 18000, 11}` dropped (< 15%); null pct dropped; more than 3 → top 3 by rupee gap.
  - acked `CATEGORY_HIGH|Food|2026-09` hides Food for 2026-09 only.
  - Review Focus 2: same payee as `AMOUNT_CHANGED` in `recurring` and in `changed` (differing case/space in merchant) → one row.
  - Card statement row present when `unlinkedCardBills` set, absent when null or undefined, hidden when acked.
  - `new-recurring` only for `state === 'NEEDS_ANSWER'`; never dismissible.
  - Review Focus 1: today `2026-10-16`; estimates `2026-10-05` (past, not counted), `2026-10-20` (counted), `2026-11-02` (future, not counted) → `stillToGoOut` = the 20 Oct amount; past row flagged.
  - `paidRegularly`: Weekly 100 → monthly 433.33 (× 52 / 12); Quarterly 900 → 300; yearly = monthly × 12; rising flag.
- [ ] **Step 2: Run** jest on `src/lib/insightsFeed.test.ts` — Expected: FAIL (module missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS. Then `npx tsc --noEmit` clean.
- [ ] **Step 5: Commit** `feat(mobile): build the insights feed from server data in one tested place`

### Task 5: Section components

**Files:**
- Create: `mobile/src/components/insights/InsightsQuestions.tsx`, `LookAtSection.tsx`, `ComingUpSection.tsx`, `PaidRegularlySection.tsx` (+ one `.test.tsx` each)

**Interfaces:**
- Consumes: Task 4 row types.
- Props: `InsightsQuestions({ counts: { lookAt: number; comingUp: number; paidRegularly: number }; onJump(section: 'lookAt' | 'comingUp' | 'paidRegularly'): void; onOpenReports(): void })` — a question is hidden when its count is 0. `LookAtSection({ rows; monthLong: string; onOpenRow(row); onAck(row); onUpload(); })`. `ComingUpSection({ rows; stillToGoOut; latestStatementEnd: string | null })`. `PaidRegularlySection({ rows; monthlyTotal; yearlyTotal; olderMonthPicked: boolean; onDismiss(merchant) })`.
- Copy (exact): card row "You paid {₹total} in card bills in {Month YYYY} with no card statement uploaded for it. Upload it to see what you bought." with buttons "Upload statement" / "Got it"; running high "{Category} is {₹gap} above your usual month" and second line "{₹current} vs {₹priorAverage}"; empty "Nothing new to look at for {Month YYYY}."; past note "Your latest statement ends {d Mon}. Upload {Month}'s to see if these were paid."; past row "expected around {d Mon}"; totals "{₹m} a month · {₹y} a year"; older month header note "Based on all your statements".
- [ ] **Step 1: Write failing tests** for each component: exact copy above via `fmtCurrency`/`withSep`; every row has an `accessibilityLabel` that reads the full sentence; "Got it" calls `onAck` with the row; `new-recurring` renders `RecurringQuestion`; past rows have no counted amount styling; a 0-count question is absent; fontScale 2 renders without throwing.
- [ ] **Step 2: Run** — Expected: FAIL. **Step 3: Implement** with `GlassSurface` cards and theme tokens (no opaque `c.card` without a `glass-exempt` marker). **Step 4: Run** — PASS. **Step 5: Commit** `feat(mobile): insights sections for look at, coming up and paid regularly`

### Task 6: The screen

**Files:**
- Modify (rewrite): `mobile/src/screens/InsightsScreen.tsx`, `mobile/src/screens/InsightsScreen.test.tsx`
- Modify: `mobile/src/navigation/types.ts` (`Reports: { month?: string } | undefined`), `mobile/src/screens/ReportsScreen.tsx` (initial `pickedMonth` from `route.params?.month`) + its test

**Interfaces:**
- Consumes: Tasks 4–5; queries `['insights'|month]`, `['recurring']`, `['recurring-changed-amounts']`, `['report-months']`, `['insight-acknowledgements']` (`retry: false`; failure = empty set).
- "Got it": `useMutation` on `insightsApi.acknowledge`, optimistic add to `['insight-acknowledgements']`, rollback + `hapticError` on failure, `hapticSuccess` on success.
- Kept from today (spec "Kept"): `usePreventScreenCapture`, `useScrollToTop`, checklist `VIEW_INSIGHTS` 1.5 s dwell, `usageApi.recordView('insights')` per focus, coverage caveat banner, `recurringApi.dismiss` with optimistic removal, pull to refresh with `withBypass`, settings gear, skeletons on first load, the rule-based note once at the bottom.
- Removed: tabs, donut, glance card, income card/trend, "Heads up"/"on track" banners, Key Insights list, `useDashboardKpis` and `dashboardApi.summary` (the month line reads `InsightsData` only).
- Month line: "You spent {₹expense} in {Month YYYY}, {n}% more/less than {previous Month}." Both months from `reportsApi.trend(month, 2)` (`ReportTrendPoint.expense`), so the line always equals Reports for the same month (anti-goal: never disagree with Reports). Previous month missing or 0 → omit the comparison clause; `complete === false` → "You've spent {₹expense} in {Month} so far." with no comparison.
- Latest statement end (for "Coming up"'s past note): the newest `Account.lastStatementPeriodEnd` across `accountsApi.list()` (query key `['accounts']`, shared with Accounts); null → the note reads "Upload your latest statements to see if these were paid."
- Navigation: running-high row → `openTransactionsFiltered({ categoryName, ...monthDateRange(month), label })` (existing helper); amount-changed row → Transactions filtered by merchant keyword; "Upload statement" → `trackNavigation('import-statement','contextual'); navigation.navigate('Import')`; "Where did my money go?" and footer → `trackNavigation('reports','contextual'); navigate('More', { screen: 'Reports', params: { month } })`.
- [ ] **Step 1: Write failing tests** (replace the old file; keep the mocks block shape, add `insightsApi.acknowledgements/acknowledge`, `reportsApi.trend`, `accountsApi.list`):
  - renders the four sections from the fixtures; no tab pills exist (`queryByRole('button', { name: 'Spending' })` null).
  - month line text with Indian grouping and "Sep" never "Sept".
  - Review Focus 3: `acknowledgements` rejects with 404 and `unlinkedCardBills` absent → sections render, no error text.
  - "Got it" on a running-high row → `acknowledge('CATEGORY_HIGH', 'Food|2026-09')` called once, row disappears, `expectHaptics(['success'])`; rejected → row returns, `expectHaptics(['error'])`.
  - a question tap scrolls (mock `scrollTo` called with the measured y); "Where did my money go?" navigates to Reports with the month and calls `trackNavigation`.
  - kept behaviours: screen capture prevented, checklist dwell, usage record, scroll-to-top, dismiss recurring.
  - `ReportsScreen` opens on `route.params.month` when given.
- [ ] **Step 2: Run** — Expected: FAIL. **Step 3: Implement.** **Step 4: Run** the two files, then the full mobile suite, `npx tsc --noEmit`, `npx eslint . --max-warnings 0` — Expected: all clean.
- [ ] **Step 5: Commit** `feat(mobile): insights as one screen of what is worth knowing`

### Task 7: The tab dot

**Files:**
- Create: `mobile/src/lib/useInsightsLookAtCount.ts` (+ test)
- Modify: `mobile/src/navigation/AppTabs.tsx` (Insights `tabBarIcon` draws a small dot when the count > 0) + `AppTabs`/`FloatingTabBar` test

**Interfaces:**
- `useInsightsLookAtCount(): number` — same query keys as Task 6, `buildToLookAt(...).length`; 0 while loading or on error.
- Dot: 8×8, `c.danger`, top-right of the icon, `accessibilityLabel` on the tab becomes "Insights, has things to look at".
- [ ] **Step 1: Failing tests:** count 0 → no dot and plain label; count 2 → dot and the longer label; error → no dot.
- [ ] **Step 2–4:** run (FAIL), implement, run (PASS) + full suite.
- [ ] **Step 5: Commit** `feat(mobile): a dot on the insights tab while something needs a look`

### Task 8: Verify on the emulator and open PR B

- [ ] Run the hygiene checks locally (`python3 scripts/check-nav-tracking-coverage.py` and the repo's other hygiene scripts the CI job runs).
- [ ] Android emulator against production data, read-only (no "Got it", no answers, no dismissals on real data): screenshots of top and bottom, large text, dark mode; confirm card bills are absent from running high and the card-statement row appears for a month with unlinked bills; compare every figure with the Ledger filtered the same way.
- [ ] iOS simulator: same screens; VoiceOver labels read whole sentences.
- [ ] Self-review the whole diff (no agents), push, open PR B noting "ships with binary 1.1.0; do not OTA to 1.0.0"; after merge check `headRefOid` and content on main.
