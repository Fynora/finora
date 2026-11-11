# Dashboard Money-In Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dashboard cards name the real dates they cover and show money not counted as income on the
Income card; the import Done step asks what the uncounted money was.

**Architecture:** One new server field (`figuresThrough`) and one new query parameter
(`statementImportId` on `/transactions/unresolved-inflows`). Everything else is client work on web
(`frontend/`) and mobile (`mobile/`) reusing the existing Money Review pieces (`inflowApi`,
`InflowKindPicker`). Quick sort stops asking about unresolved inflows, after a measurement.

**Tech Stack:** Spring Boot (Java 25), React + Vitest (web), React Native + Jest (mobile), OpenAPI
generated types.

**Spec:** `docs/superpowers/specs/2026-11-10-dashboard-money-in-clarity-design.md`

## Global Constraints

- No change to `FlowClassifier`, `FlowTotals` or what counts as income or expense.
- Copy exactly as the spec's Copy table. Card label separator is a middle dot with spaces: `Income · 1–8 Nov`. The day range uses an en dash.
- Income stays success ink, expenses danger ink; the "not counted" line uses warning ink.
- Mobile ships in the next store build only, never OTA.
- Repository is public: synthetic names and amounts only in tests, fixtures and commits.
- Commit messages carry no `Co-Authored-By` or other AI-attribution trailer (repo CLAUDE.md).
- Run Node tooling with `npx node@22` semantics (CI is Node 22; local is 26): `npx -y node@22 node_modules/.bin/vitest run ...` and the same for jest.
- Fresh worktree: run `npm ci` at the repo root (commit hook), in `frontend/` and in `mobile/` before the first test run.

## Review Focus

1. Reporting month has no rows (new account): `figuresThrough` is null; labels fall back to today's "This Month" / month name, never "1–null". Test in Task 2.
2. Data ends on the 1st: label is `1 Nov` (single day, no range). Test in Task 2.
3. Data reaches the month's last day: label is the month name (`November`), not `1–30 Nov`. Test in Task 2.
4. Unresolved request on the Done step fails or returns an empty list: no card, no error, the rest of Done unchanged. Tests in Tasks 6 and 7.
5. A `statementImportId` from another user: empty list, never their rows. Test in Task 5.

---

### Task 1: `figuresThrough` on the dashboard summary

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/DashboardSummaryDto.java` (add last component)
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java:365-395` (pass it)
- Modify: `backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java` (constructor call)
- Test: `backend/src/test/java/com/finora/service/DashboardServiceTest.java`
- Regenerate: `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`, admin-portal's `generated-types.ts`
- Modify: `frontend/src/types/index.ts`, `mobile/src/types/index.ts` (add `figuresThrough: string | null` to the summary type)

**Interfaces:**
- Produces: `DashboardSummaryDto.figuresThrough` (`LocalDate`, nullable, JSON `"YYYY-MM-DD"`): the newest `txnDate` among `active` rows whose month equals the reporting month; null when the reporting month is null or has no rows.

- [ ] **Step 1: Write failing tests** in `DashboardServiceTest`:
  - `figuresThroughIsNewestRowInReportingMonth`: rows on Nov 1, Nov 6, Nov 8 (and one Oct 30) → `figuresThrough == 2026-11-08`.
  - `figuresThroughIsNullWithNoRows`: no transactions → `null`.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=DashboardServiceTest` → FAIL (no such accessor).
- [ ] **Step 3: Implement.** Add `LocalDate figuresThrough` as the record's last component; compute it in `DashboardService` from `active` filtered to `currentMonth` (use `active`, not `activeForTotals`, so a transfer-only last day still counts as covered).
- [ ] **Step 4: Run** the same test → PASS. Then `./mvnw -q test -Dtest='DashboardService*,HealthScoreSnapshotSweepServiceTest'` → PASS.
- [ ] **Step 5: Regenerate contract.** `cd backend && ./scripts/generate-openapi-spec.sh`, then `npm run generate:types` in `frontend/`, `mobile/`, `admin-portal/`. Add the field to both hand-written summary types.
- [ ] **Step 6: Commit** `feat(dashboard): report the newest day the month's figures cover`.

### Task 2: Card label helpers (web and mobile)

**Files:**
- Create: `frontend/src/lib/periodCardLabel.ts`, `frontend/src/lib/periodCardLabel.test.ts`
- Create: `mobile/src/lib/periodCardLabel.ts`, `mobile/src/lib/periodCardLabel.test.ts` (same code, same tests)

**Interfaces:**
- Produces: `monthCardLabel(reportingMonth: string | null, figuresThrough: string | null, fallback: string): string` and `rangeCardLabel(startDate: string, endDate: string): string`.

- [ ] **Step 1: Write failing tests** (identical in both apps):
  - `monthCardLabel('2026-11', '2026-11-08', 'This Month') === '1–8 Nov'`
  - `monthCardLabel('2026-11', '2026-11-01', 'This Month') === '1 Nov'`
  - `monthCardLabel('2026-11', '2026-11-30', 'This Month') === 'November'`
  - `monthCardLabel('2026-02', '2026-02-28', 'x') === 'February'` (month length from the calendar, not 30/31)
  - `monthCardLabel('2026-11', null, 'This Month') === 'This Month'`; `monthCardLabel(null, null, 'This Month') === 'This Month'`
  - `rangeCardLabel('2026-06-01', '2026-11-30') === 'Jun–Nov 2026'`
  - `rangeCardLabel('2025-12-01', '2026-02-28') === 'Dec 2025–Feb 2026'`
- [ ] **Step 2: Run** `cd frontend && npx -y node@22 node_modules/.bin/vitest run src/lib/periodCardLabel.test.ts` and `cd mobile && npx -y node@22 node_modules/.bin/jest src/lib/periodCardLabel.test.ts` → FAIL.
- [ ] **Step 3: Implement.** Parse dates as plain `YYYY-MM-DD` parts (no `new Date(str)`, which shifts by time zone). English month names, short form `Nov`, long form `November`.
- [ ] **Step 4: Run** both → PASS.
- [ ] **Step 5: Commit** `feat(dashboard): label helpers that name the days the cards cover`.

### Task 3: Web dashboard cards

**Files:**
- Modify: `frontend/src/design-system/MetricCard.tsx` (new optional `footer?: React.ReactNode`, rendered last in the `elevated` variant only)
- Modify: `frontend/src/pages/Dashboard.tsx:558-660` (labels), `:806-840` (remove `unresolved-inflow-banner`)
- Test: `frontend/src/pages/Dashboard.test.tsx`

**Interfaces:**
- Consumes: `monthCardLabel`, `rangeCardLabel` (Task 2); `summary.figuresThrough` (Task 1).

- [ ] **Step 1: Write failing tests** in `Dashboard.test.tsx` (synthetic summary: reportingMonth `2026-11`, figuresThrough `2026-11-08`, monthlyIncome 120, unresolvedInflow 15000, unresolvedInflowCount 2, unresolvedTopReason `PERSON_INFLOW`):
  - card labels read `Income · 1–8 Nov`, `Expenses · 1–8 Nov`, `Net Savings · 1–8 Nov`, `Savings Rate · 1–8 Nov`
  - Income card contains `+ ₹15,000 not counted yet`, `Mostly money received from people`, and a link `Tell us what it is` to `/app/money-review?start=2026-11-01&end=2026-11-30`
  - `queryByTestId('unresolved-inflow-banner')` is null
  - with `unresolvedInflowCount: 0` the Income card has no "not counted" line
  - range view LAST_6_MONTHS (startDate `2026-06-01`, endDate `2026-11-30`) → `Income · Jun–Nov 2026`
  - Move the two existing banner assertions to the card; delete none without replacing them.
- [ ] **Step 2: Run** `npx -y node@22 node_modules/.bin/vitest run src/pages/Dashboard.test.tsx` → FAIL.
- [ ] **Step 3: Implement.** Replace `monthCardSuffix`/`rangeCardSuffix` in card labels with the helpers (keep `CUSTOM`'s existing day range). Build the Income card `footer` from the same `unresolved` object the banner used (`text-warning` for the amount line). Keep `monthDeltaLabel` text unchanged.
- [ ] **Step 4: Run** the file, then the whole web suite `npx -y node@22 node_modules/.bin/vitest run`, `npm run lint`, `npx tsc --noEmit` → all PASS.
- [ ] **Step 5: Commit** `feat(web): dashboard cards name their days and show money not counted on Income`.

### Task 4: Mobile dashboard

**Files:**
- Modify: `mobile/src/lib/useDashboardKpis.ts` (`periodTitle` from `monthCardLabel`)
- Modify: `mobile/src/components/dashboard/LedgerSnapshotCard.tsx` (`KpiItem.footer?: React.ReactNode`, rendered at the tile's bottom; included in the tile's accessibility label as text)
- Modify: `mobile/src/screens/DashboardScreen.tsx:606-633` (remove the banner; pass the Income footer)
- Test: `mobile/src/screens/DashboardScreen.test.tsx`, `mobile/src/components/dashboard/LedgerSnapshotCard.test.tsx` (if absent, create)

**Interfaces:**
- Consumes: `monthCardLabel` (Task 2), `summary.figuresThrough` (Task 1).

- [ ] **Step 1: Write failing tests** with the Task 3 synthetic summary:
  - section title reads `1–8 Nov`
  - `kpi-tile-Income` contains `+ ₹15,000 not counted yet` and `Tell us what it is`; pressing it navigates `More` → `MoneyReview` with `{ start: '2026-11-01', end: '2026-11-30' }`
  - `queryByTestId('unresolved-inflow-banner')` is null
  - at `fontScale` 2 (jest default here) the title is not truncated (`numberOfLines` not set on it)
- [ ] **Step 2: Run** `cd mobile && npx -y node@22 node_modules/.bin/jest src/screens/DashboardScreen.test.tsx src/components/dashboard` → FAIL.
- [ ] **Step 3: Implement.** `periodTitle = monthCardLabel(summary.reportingMonth, summary.figuresThrough, periodIsCurrent ? 'This Month' : monthLabel(...))`. Footer text in `c.warningInk`, link in `c.primary`, `Pressable` with `accessibilityRole="link"`.
- [ ] **Step 4: Run** the whole mobile suite `npx -y node@22 node_modules/.bin/jest`, `npm run lint`, `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(mobile): dashboard names its days and shows money not counted on Income`.

### Task 5: Unresolved inflows for one import

**Files:**
- Modify: `backend/src/main/java/com/finora/inflow/InflowController.java:78-83`
- Modify: `backend/src/main/java/com/finora/inflow/InflowReviewService.java` (grouping into a private method; new public method)
- Test: `backend/src/test/java/com/finora/inflow/InflowReviewServiceTest.java`, `backend/src/test/java/com/finora/inflow/InflowControllerIT.java`
- Regenerate contract and client types as in Task 1 Step 5.

**Interfaces:**
- Produces: `GET /transactions/unresolved-inflows?statementImportId=<uuid>` (start/end become optional; exactly one of `statementImportId` or both dates is required, else 400). Service: `List<InflowDtos.UnresolvedSenderDto> groupsForImport(UUID userId, UUID statementImportId)`.
- Client: `inflowApi.unresolvedForImport(statementImportId: string): Promise<UnresolvedSender[]>` in `frontend/src/api/endpoints.ts` and `mobile/src/api/endpoints.ts`.

- [ ] **Step 1: Write failing tests:**
  - service: `groupsForImportListsOnlyThatImportsUnresolvedRows`: two imports each with a person credit → only the asked import's sender comes back.
  - service: `groupsForImportIsEmptyForAnotherUsersImport`: import owned by user B, asked as user A → `[]`.
  - service: `groupsForImportIsEmptyWhenNothingUnresolved`.
  - IT: no params → 400; `statementImportId` alone → 200.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=InflowReviewServiceTest` → FAIL.
- [ ] **Step 3: Implement.** Load `transactions.findByStatementImportId(id)`, keep rows with `userId` match, take min/max `txnDate`, call `reportService.unresolvedInflows(userId, min, max)`, keep rows whose `statementImportId` equals `id`, then the existing grouping.
- [ ] **Step 4: Run** unit test → PASS; IT with `./mvnw -q verify -Dit.test=InflowControllerIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` → PASS. Do not commit while `verify` runs.
- [ ] **Step 5:** Regenerate contract; add the client method in both apps.
- [ ] **Step 6: Commit** `feat(inflow): list one import's money not counted as income`.

### Task 6: Web import Done card

**Files:**
- Create: `frontend/src/components/inflow/UnresolvedSenderRow.tsx` (lifted from `MoneyReview.tsx` lines 103-156: header button, picker, "Every payment from…"/"Just this one")
- Create: `frontend/src/components/inflow/ImportMoneyInCard.tsx`, `frontend/src/components/inflow/ImportMoneyInCard.test.tsx`
- Modify: `frontend/src/pages/MoneyReview.tsx` (use `UnresolvedSenderRow`)
- Modify: `frontend/src/pages/Import.tsx` (`ImportSummaryScreen` and `MultiImportSummaryScreen`: render the card above `QuickSortCta`)

**Interfaces:**
- Consumes: `inflowApi.unresolvedForImport` (Task 5).
- Produces: `<ImportMoneyInCard statementImportIds={string[]} />`; `<UnresolvedSenderRow sender kinds open onToggle onApply busy />` with `onApply(transactionId: string, scope: ChoiceScope, label: string)`.

- [ ] **Step 1: Write failing tests** in `ImportMoneyInCard.test.tsx` (mock `inflowApi`):
  - 2 senders → heading `2 payments you received aren't counted as income`, body `What were they? This decides if they count as income.`
  - 5 senders → 3 rows shown, biggest total first, and `See all` linking to `/app/money-review` with the senders' date bounds
  - `Skip for now` removes the card and calls no write API
  - picking a kind then `Just this one` calls `setChoice(id, kindId, 'ROW')`, shows `Saved for <label>.` with `Undo`, and invalidates money figures
  - empty list → renders nothing; rejected request → renders nothing, no error text
  - two `statementImportIds` → results merged, one heading counting all payments
  - `MoneyReview.test.tsx` still passes unchanged after the row is lifted out.
- [ ] **Step 2: Run** `npx -y node@22 node_modules/.bin/vitest run src/components/inflow src/pages/MoneyReview.test.tsx` → FAIL.
- [ ] **Step 3: Implement.** Heading count = sum of `count` over all senders (payments, not senders).
- [ ] **Step 4: Run** whole web suite, lint, `tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(web): ask what uncounted money was at the end of an import`.

### Task 7: Mobile import Done card

**Files:**
- Create: `mobile/src/components/inflow/UnresolvedSenderRow.tsx` (lifted from `MoneyReviewScreen.tsx`)
- Create: `mobile/src/components/inflow/ImportMoneyInCard.tsx`, `.test.tsx`
- Modify: `mobile/src/screens/MoneyReviewScreen.tsx`, `mobile/src/screens/import/ImportScreen.tsx` (above `QuickSortCta` in `import-summary-actions`)

**Interfaces:** as Task 6, with `invalidateFinancialData` and navigation to `More` → `MoneyReview` `{ start, end }` for `See all`.

- [ ] **Step 1: Write failing tests** mirroring Task 6's list, plus: card fits at `fontScale` 2 without clipping the heading (no `numberOfLines` on it).
- [ ] **Step 2: Run** `npx -y node@22 node_modules/.bin/jest src/components/inflow src/screens/MoneyReviewScreen.test.tsx src/screens/import` → FAIL.
- [ ] **Step 3: Implement.** `ImportSummary.statementImportId` is nullable on mobile: skip null ids; with none left, render nothing.
- [ ] **Step 4: Run** whole mobile suite, lint, `tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(mobile): ask what uncounted money was at the end of an import`.

### Task 8: Quick sort leaves unresolved inflows to the new card

**Files:**
- Create (scratchpad, not repo): `quick-sort-overlap.sql` for Sid
- Modify: `backend/src/main/java/com/finora/transactions/QuickSortService.java:126-131` (filter)
- Test: `backend/src/test/java/com/finora/transactions/QuickSortServiceTest.java`

**Interfaces:**
- Consumes: `InflowChoiceService.contextFor(userId, accounts, categories)`, `FlowTotals.isUnresolvedInflow(t, ctx)`.

- [ ] **Step 1: Measure first.** Write a read-only SQL for Sid counting, per user, `INCOME` rows with `needs_category_review = true` and `counterparty_type = 'PERSON'` and `inflow_kind_id IS NULL` (the main unresolved shape). Send it; record his numbers in the PR description. If both the seeded check and prod show zero overlap, stop this task and report that; otherwise continue.
- [ ] **Step 2: Write failing test** `batchLeavesOutUnresolvedInflows`: one waiting person credit (unresolved) and one waiting shop debit → batch has only the shop question; `waitingTotal` excludes the credit.
- [ ] **Step 3: Run** `./mvnw -q test -Dtest=QuickSortServiceTest` → FAIL.
- [ ] **Step 4: Implement.** In `batch`, drop rows where `FlowTotals.isUnresolvedInflow(t, ctx)` before `groups(...)`. Inject `InflowChoiceService` and `CategoryRepository` is already there.
- [ ] **Step 5: Run** `QuickSortServiceTest` and `QuickSortIT` (via `verify` as in Task 5) → PASS.
- [ ] **Step 6: Commit** `fix(quick-sort): leave money not counted as income to its own question`.

### Task 9: Value-level check and branch verification

- [ ] **Step 1:** Full backend `./mvnw -q verify`, web and mobile full suites, lint and type-check in all three clients, OpenAPI drift (`git diff --exit-code backend/openapi/openapi.json` after regenerating) → all clean.
- [ ] **Step 2:** On a local stack, seed a synthetic account: interest credit ₹120 on the 1st, person UPI credits ₹6,000 and ₹9,000 on the 6th and 7th, SMS charge ₹30 on the 8th, nothing after the 8th. Import it.
- [ ] **Step 3:** Done step shows `2 payments you received aren't counted as income`. Mark the ₹6,000 sender as Income.
- [ ] **Step 4:** Dashboard (web and mobile simulator): `Income · 1–8 Nov` shows ₹6,120; line shows `+ ₹9,000 not counted yet`; Net Savings ₹6,090. Undo on Money Review returns Income to ₹120 and the line to ₹15,000. Screenshot web and mobile.
- [ ] **Step 5:** Record the results (commands and screenshots) in the PR description; anything not checked is listed as not checked.
