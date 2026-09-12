# Insights Spending Tab + Navigation Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote Insights to a primary bottom tab (swapping with Goals, which moves into
`MoreStack`), add the 5-tab pill bar (Overview/Spending/Income/Recurring/Trends) to
`InsightsScreen.tsx`, and fully build the "Spending" tab — full observations, a month picker
backed by a new backend `month` param, the moved Recurring list, and an uncapped Category Movers
section with a "See All" expand. Income/Recurring/Trends get "coming soon" placeholders.

**Architecture:** Three independent tracks. Track A (bottom-tab swap) touches navigation
plumbing only, mirrors commit `ee34a4f2` in reverse, and carries no data dependency on the other
two. Track B (backend) adds an optional `month` query param to `GET /api/v1/insights`,
generalizing `InsightsService.pipeline()`'s month resolution without changing its default
(no-month) behavior. Track C (frontend) builds the pill tab bar on `InsightsScreen.tsx` and the
Spending tab's content, consuming Track B's new param — Track C's later tasks depend on Track B
being done first (specifically, the OpenAPI regen task).

**Tech Stack:** React Native/Expo SDK 57, TypeScript, TanStack Query, React Navigation
bottom-tabs + native-stack, `OptionPickerModal` (existing). Spring Boot, JUnit 5 + Mockito +
AssertJ, existing `GET /api/v1/insights` endpoint.

**Spec:** [docs/superpowers/specs/2026-09-12-insights-spending-tab-design.md](../specs/2026-09-12-insights-spending-tab-design.md)

## Global Constraints

- No `Co-Authored-By` or other AI-attribution trailer in any commit message (repo CLAUDE.md
  absolute rule).
- Every commit is a real, working state — run the listed verification command(s) before
  committing each task.
- OpenAPI regen (Task 9) must actually be run and its diff committed, not hand-written — this
  repo's CI blocks on `openapi.json`/generated-types drift.
- `useLargeFontScale`'s `numberOfLines={largeText ? 2 : 1}` pattern applies to any new text that
  can wrap (already used throughout `InsightsScreen.tsx`).
- Never modify `Category Movers`' existing tap-to-filter behavior, `DonutChart`/`bucketTopSlices`,
  or `InsightsExplorerService` — all reused as-is (per spec's Global Constraints, carried over
  from PR #1363's own spec).

---

## Track A: Bottom-tab swap (Goals ↔ Insights)

### Task 1: `types.ts` — move `Goals`/`Insights` between param lists

**Files:**
- Modify: `mobile/src/navigation/types.ts:34-71` (`MoreStackParamList`), `:137-162`
  (`AppTabParamList`)

**Interfaces:**
- Produces: `MoreStackParamList` now includes `Goals: undefined` (not `Insights`);
  `AppTabParamList` now includes `Insights: undefined` (not `Goals`). Every later task in Track A
  and Track C depends on this.

- [ ] **Step 1: Edit `MoreStackParamList`** — replace the `Insights: undefined;` line (currently
  line 47) with `Goals: undefined;`, inserted alphabetically-by-position where it reads naturally
  (right after `AdvancedReports`, before `Investments` — same slot `Insights` occupied):

```typescript
export type MoreStackParamList = {
  MoreHome: undefined;
  Accounts: undefined;
  CategoryReview: undefined;
  Statements: undefined;
  Budgets: undefined;
  Reports: undefined;
  AdvancedReports: undefined;
  // Demoted from a top-level tab back into this stack -- Insights now takes its old tab slot.
  // See mobile/src/navigation/AppTabs.tsx.
  Goals: undefined;
  Investments: undefined;
  Profile: undefined;
  Settings: undefined;
  GmailReview: undefined;
  Subscription: undefined;
  VerifyEmailChange: { sessionId?: string; token?: string } | undefined;
  SupportTickets: undefined;
  SupportTicketDetail: { ticketId: string };
  Referrals: undefined;
};
```

- [ ] **Step 2: Edit `AppTabParamList`** — replace `Goals: undefined;` with `Insights: undefined;`,
  and fix the stale doc comment above the type (it still says "Four tabs" and lists Insights as
  living in the More stack, both no longer true):

```typescript
/**
 * Five tabs (counting the floating "+" Import button): Home, Transactions, Import, Insights,
 * More. Insights was promoted from a `MoreStack` row to this slot, swapping with Goals (which
 * moved the other way) -- see AppTabs.tsx. Budgets/Reports/Investments/Goals live in the More
 * stack; they're report/management surfaces people open occasionally, not destinations they
 * switch between mid-task the way Insights now is.
 */
export type AppTabParamList = {
  Home: { openAddTransaction?: boolean; nonce?: number } | undefined;
  Transactions: { filters: LedgerDrillThroughFilters } | undefined;
  Import: { reimport: ReimportParams } | undefined;
  Insights: undefined;
  More: NavigatorScreenParams<MoreStackParamList> | undefined;
};
```

- [ ] **Step 3: Verify the codebase still type-checks after this isolated change (it won't fully
  — `AppTabs.tsx` etc. still reference the old shape — but this confirms the edit itself is
  syntactically valid TypeScript before touching the next file)**

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep -c "error TS"`
Expected: a non-zero count of errors, all in `AppTabs.tsx`, `MoreScreen.tsx`, `tourSteps.ts`,
`DashboardScreen.tsx` (the files Tasks 2-5 fix next) — not in `types.ts` itself.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/navigation/types.ts
git commit -m "refactor(mobile): move Goals/Insights between AppTabParamList and MoreStackParamList"
```

---

### Task 2: `AppTabs.tsx` — swap the tab, icon, and tour-target registration

**Files:**
- Modify: `mobile/src/navigation/AppTabs.tsx` (lines 21-23 imports, 66 `MoreStack.Screen`, 90-96
  `TAB_ICON`, 130-147 `registerByTab`, 178-179, 186 `Tab.Screen`/`QuickActionSheet`)
- Test: `mobile/src/navigation/AppTabs.test.tsx`

**Interfaces:**
- Consumes: `AppTabParamList`/`MoreStackParamList` from Task 1.
- Produces: `Tab.Screen name="Insights"` exists on the bottom tab bar; `GoalsScreen` is reachable
  at `More > Goals`.

- [ ] **Step 1: Update the failing test first** — `AppTabs.test.tsx` currently asserts a `'goals'`
  tour target is found directly (line ~106) and that `onAddGoal` calls
  `navigate('Goals')` (line ~149). Change both to the post-swap shape:

Replace (around line 100-114):
```typescript
describe('AppTabs tour target registration', () => {
  it('registers home/transactions/import/insights refs on a real TourTargetProvider, without throwing', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
        <TargetProbe tourKey="home" />
        <TargetProbe tourKey="transactions" />
        <TargetProbe tourKey="import" />
        <TargetProbe tourKey="insights" />
      </TourTargetProvider>
    );

    expect(screen.getByTestId('probe-home')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-transactions')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-import')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-insights')).toHaveTextContent('found');
  });
```

Replace (around line 149):
```typescript
    fireEvent.press(screen.getByLabelText('Quick actions'));
    fireEvent.press(screen.getByText('Add Goal'));
    expect(mockNavigate).toHaveBeenCalledWith('More', { screen: 'Goals' });
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd mobile && npx jest src/navigation/AppTabs.test.tsx`
Expected: FAIL — `probe-insights` not found (nothing registers it yet), `onAddGoal` still calls
`navigate('Goals')`.

- [ ] **Step 3: Update imports and `TAB_ICON`**

Replace lines 90-96:
```typescript
const TAB_ICON: Record<keyof AppTabParamList, { active: string; inactive: string }> = {
  Home: { active: 'home', inactive: 'home-outline' },
  Transactions: { active: 'swap-horizontal', inactive: 'swap-horizontal-outline' },
  Import: { active: 'add-circle', inactive: 'add-circle-outline' },
  Insights: { active: 'stats-chart', inactive: 'stats-chart-outline' },
  More: { active: 'menu', inactive: 'menu-outline' },
};
```

- [ ] **Step 4: Move `GoalsScreen` into `MoreNavigator`, drop `Insights` from it**

Replace line 66 (`<MoreStack.Screen name="Insights" ... />` and its preceding comment) with:
```typescript
      {/* Header hidden: GoalsScreen already renders its own title/top-inset (from its own prior
          promotion to a top-level tab, #1306) -- same self-contained pattern as
          Accounts/CategoryReview/GmailReview/Statements above, now that it has moved back here. */}
      <MoreStack.Screen name="Goals" component={GoalsScreen} options={{ headerShown: false }} />
```

`GoalsScreen` is already imported (line 21); `InsightsScreen`'s import (line 23) becomes unused
in `MoreNavigator` but is still needed for `Tab.Screen` below — leave the import, it's used at
Step 6.

- [ ] **Step 5: Swap `registerGoals`/`registerInsights` and `registerByTab`**

Replace lines 130-147:
```typescript
  // Tour target refs (tourSteps.ts) for the 3 tabs the tour spotlights directly -- 'More' has no
  // entry here because its own tour steps (Accounts/Budgets/Goals) target rows inside MoreScreen,
  // not the tab icon itself; see that screen's own registration.
  const registerHome = useRegisterTourTarget('home');
  const registerTransactions = useRegisterTourTarget('transactions');
  // Not wired into registerByTab/tabBarIcon below -- Import's tabBarIcon is never actually
  // rendered now that it has a custom tabBarButton (see ImportFabButton's own comment), so this
  // is attached directly inside ImportFabButton instead.
  const registerImport = useRegisterTourTarget('import');
  // Was registered inside MoreScreen.tsx (spotlighting the "Insights" row in the More menu) until
  // Insights was promoted from a MoreStack screen to its own top-level tab, swapping with Goals
  // (which moved the other way) -- now it spotlights this tab's icon directly, same as
  // Home/Transactions above.
  const registerInsights = useRegisterTourTarget('insights');
  const registerByTab: Partial<Record<keyof AppTabParamList, (node: View | null) => void>> = {
    Home: registerHome,
    Transactions: registerTransactions,
    Insights: registerInsights,
  };
```

- [ ] **Step 6: Swap the `Tab.Screen` and fix `onAddGoal`**

Replace line 178 (`<Tab.Screen name="Goals" component={GoalsScreen} />`):
```typescript
        <Tab.Screen name="Insights" component={InsightsScreen} />
```

Replace line 186 (`onAddGoal={() => navigation.navigate('Goals')}`):
```typescript
        onAddGoal={() => navigation.navigate('More', { screen: 'Goals' })}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd mobile && npx jest src/navigation/AppTabs.test.tsx`
Expected: PASS

- [ ] **Step 8: Type-check**

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep AppTabs`
Expected: no output (no errors in this file)

- [ ] **Step 9: Commit**

```bash
git add mobile/src/navigation/AppTabs.tsx mobile/src/navigation/AppTabs.test.tsx
git commit -m "feat(mobile): promote Insights to a bottom tab, move Goals into MoreStack"
```

---

### Task 3: `MoreScreen.tsx` — swap the menu row and tour-target registration

**Files:**
- Modify: `mobile/src/screens/MoreScreen.tsx:22-51`

**Interfaces:**
- Consumes: `MoreStackParamList` from Task 1.
- Produces: `Goals` reachable from the More menu, in the position it held before #1306.

- [ ] **Step 1: Update `MENU_ITEMS`** — remove the `Insights` row, add `Goals` immediately after
  `Budgets` (its exact pre-#1306 position, verified via `git show fc989ae6^:mobile/src/screens/MoreScreen.tsx`):

```typescript
const MENU_ITEMS: { label: string; route: keyof Omit<MoreStackParamList, 'MoreHome' | 'SupportTicketDetail'> }[] = [
  { label: 'Accounts', route: 'Accounts' },
  { label: 'Investments', route: 'Investments' },
  { label: 'Budgets', route: 'Budgets' },
  { label: 'Goals', route: 'Goals' },
  { label: 'Reports', route: 'Reports' },
  { label: 'Advanced Reports', route: 'AdvancedReports' },
  { label: 'Review Categories', route: 'CategoryReview' },
  { label: 'Statement History', route: 'Statements' },
  { label: 'Subscription', route: 'Subscription' },
  { label: 'Refer & Earn', route: 'Referrals' },
  { label: 'Settings', route: 'Settings' },
];
```

- [ ] **Step 2: Swap `registerInsights`/`registerGoals` in the component body**

Replace lines 40-51:
```typescript
  // Tour target refs (tourSteps.ts) for the 3 rows the mobile tour spotlights on this screen --
  // hooks can't be called inside the MENU_ITEMS.map() below, so these are registered once here
  // and looked up per row by route name. Insights used to be a 4th entry here, until it was
  // promoted to its own top-level tab (see AppTabs.tsx's own registerInsights), swapping with
  // Goals, which moved the other way and is now registered here instead.
  const registerAccounts = useRegisterTourTarget('accounts');
  const registerBudgets = useRegisterTourTarget('budgets');
  const registerGoals = useRegisterTourTarget('goals');
  const registerByRoute: Partial<Record<string, (node: View | null) => void>> = {
    Accounts: registerAccounts,
    Budgets: registerBudgets,
    Goals: registerGoals,
  };
```

- [ ] **Step 3: Type-check** (no dedicated test file exists for `MoreScreen.tsx` today — this is
  the only verification short of manual/E2E)

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep MoreScreen`
Expected: no output

- [ ] **Step 4: Commit**

```bash
git add mobile/src/screens/MoreScreen.tsx
git commit -m "feat(mobile): move Goals into the More menu, drop Insights from it"
```

---

### Task 4: `tourSteps.ts` — swap the tab each step targets

**Files:**
- Modify: `mobile/src/onboarding/tourSteps.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `TourStep['tab']` union now includes `'Insights'`, not `'Goals'`.

- [ ] **Step 1: Edit the file**

```typescript
export interface TourStep {
  key: string;
  tab: 'Home' | 'Transactions' | 'Import' | 'Insights' | 'More';
  title: string;
  body: string;
}

// Web's Sidebar shows every target as a persistent link, so its tour never navigates. Mobile's
// bottom tab bar is narrower (Home/Transactions/Import/Insights/More only -- AppTabs.tsx);
// Accounts/Budgets/Goals live as rows inside the More tab's own list screen (MoreScreen.tsx), not
// as separate top-level tabs -- Insights used to as well, until it was promoted to its own tab,
// swapping with Goals (which moved the other way). This tour therefore navigates the tab bar as
// it advances -- see the design spec's §7 addendum.
export const TOUR_STEPS: TourStep[] = [
  { key: 'home', tab: 'Home', title: 'Your Financial Command Center',
    body: 'This dashboard gives you a complete view of your finances, including spending, budgets, goals, and account balances.' },
  { key: 'accounts', tab: 'More', title: 'Accounts',
    body: 'See every linked or manually added account in one place.' },
  { key: 'import', tab: 'Import', title: 'Import Bank Statements',
    body: 'Upload your bank statements and Fynora automatically organizes your transactions. No manual entry required.' },
  { key: 'transactions', tab: 'Transactions', title: 'Every Transaction Explained',
    body: 'Search, filter, categorize, and understand every transaction in one place. See exactly where your money is going.' },
  { key: 'budgets', tab: 'More', title: 'Stay Within Budget',
    body: 'Create monthly budgets and track your progress in real time. Get notified before you overspend.' },
  { key: 'goals', tab: 'More', title: 'Achieve Your Financial Goals',
    body: "Whether it's an emergency fund, vacation, or new car, Fynora helps you stay on track." },
  { key: 'insights', tab: 'Insights', title: 'Discover Spending Patterns',
    body: 'Fynora automatically identifies trends and spending habits so you can make smarter financial decisions.' },
];
```

- [ ] **Step 2: Type-check** (no dedicated test file for this either)

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep tourSteps`
Expected: no output

- [ ] **Step 3: Commit**

```bash
git add mobile/src/onboarding/tourSteps.ts
git commit -m "feat(mobile): point the goals/insights tour steps at their new tabs"
```

---

### Task 5: `DashboardScreen.tsx` — fix the 3 `navigate('Goals')` call sites

**Files:**
- Modify: `mobile/src/screens/DashboardScreen.tsx:559, 624`
- Test: `mobile/src/screens/DashboardScreen.test.tsx:1379`

**Interfaces:**
- Consumes: `MoreStackParamList.Goals` from Task 1.

- [ ] **Step 1: Update the failing test first**

Replace line 1379:
```typescript
    ['Manage Goals', 'More', { screen: 'Goals' }],
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx -t "opens Manage Goals"`
Expected: FAIL — actual call was `navigate('Goals')`, not `navigate('More', { screen: 'Goals' })`.

- [ ] **Step 3: Fix the two call sites**

Line 559 (`FinancialNoteCard`'s `onCreateGoal`):
```typescript
        onCreateGoal={() => navigation.navigate('More', { screen: 'Goals' })}
```

Line 624 (Quick Actions grid):
```typescript
              { icon: 'flag-outline', label: 'Manage Goals', onPress: () => navigation.navigate('More', { screen: 'Goals' }) },
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx -t "opens Manage Goals"`
Expected: PASS

- [ ] **Step 5: Run the full file to check for regressions**

Run: `cd mobile && npx jest src/screens/DashboardScreen.test.tsx`
Expected: PASS (all tests, including the untouched `FinancialNoteCard.test.tsx` which only
asserts the callback prop fires, not the navigate call itself)

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/DashboardScreen.tsx mobile/src/screens/DashboardScreen.test.tsx
git commit -m "fix(mobile): route Goals navigation through More now that it moved there"
```

---

## Track B: Backend month param on `GET /api/v1/insights`

### Task 6: `InsightsService.java` — generalize `pipeline()`/`build()` to accept a month

**Files:**
- Modify: `backend/src/main/java/com/finora/service/InsightsService.java:90-105` (`build`),
  `:259-299` (`pipeline`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `InsightsService.build(UUID userId, String month)` (new overload); existing
  `build(UUID userId)` and package-private `pipeline(UUID userId)` unchanged in behavior, now
  delegating to the generalized versions with `month = null`.

- [ ] **Step 1: Write the failing tests first** — add to
  `backend/src/test/java/com/finora/service/InsightsServiceTest.java`, after the existing
  `categoryMoverSentence_reportsPercentChangeVersusPriorAverage` test (after line 185):

```java
    @Test
    void explicitMonth_reportsThatMonthInsteadOfTheNewestOne() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 6, 5), BigDecimal.valueOf(500), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(9000), dining, "Cafe")));

        var result = insightsService.build(userId, "2026-06");

        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹500 across 1 categories"));
        assertThat(result.sentences()).noneMatch(s -> s.contains("₹9,000"));
    }

    @Test
    void explicitMonth_withNoTransactionsThatMonth_reportsAQuietMonth_notTheEmptyPrompt() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(500), dining, "Cafe")));

        var result = insightsService.build(userId, "2026-05");

        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹0 across 0 categories"));
    }

    @Test
    void nullMonth_stillPicksTheNewestMonthWithData_unchangedFromBefore() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 6, 5), BigDecimal.valueOf(500), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(900), dining, "Cafe")));

        var result = insightsService.build(userId, null);

        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹900 across 1 categories"));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && mvn test -Dtest=InsightsServiceTest -q`
Expected: FAIL — `build(UUID, String)` does not exist yet (compile error).

- [ ] **Step 3: Generalize `pipeline()`** — replace lines 259-299:

```java
    Optional<Pipeline> pipeline(UUID userId) {
        return pipeline(userId, null);
    }

    /**
     * @param requestedMonth {@code "yyyy-MM"}, or {@code null} to keep today's default: the
     *        newest month with reportable expense data. A non-null value that has zero
     *        transactions is not an error -- {@link #groupByCategory} simply returns an empty
     *        map for it, producing a genuinely quiet month's worth of output, same as any other.
     */
    Optional<Pipeline> pipeline(UUID userId, String requestedMonth) {
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so findByUserId alone would
        // keep feeding these insights forever, not just during StatementImportService's 7-day
        // grace window.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        List<Transaction> all = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndAccountIdIn(userId, liveAccountIds);
        RefundNetting refunds = RefundNetting.from(all);
        // excludingInvestmentTransfers applies here (unlike DashboardService/ReportService, which
        // keep a separate category-breakdown list): every number this whole method produces --
        // the headline "total spend" sentence, the category movers, the top-merchant callout --
        // is itself framed as "spending", and an Investments-tagged SIP appearing as "your
        // biggest category" or a spend-trend mover would contradict the very point of this
        // exclusion. See RefundNetting.excludingInvestmentTransfers's own comment on the
        // narrower, budget-safe cut.
        List<Transaction> txns = RefundNetting.excludingInvestmentTransfers(
                        RefundNetting.reportable(all, transactionGraphService.ccPaymentFromTransactionIds(all))).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();

        if (txns.isEmpty()) {
            return Optional.empty();
        }

        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        List<String> months = txns.stream().map(t -> YearMonth.from(t.getTxnDate()).toString()).distinct().sorted().toList();
        String currentMonth = requestedMonth != null ? requestedMonth : months.get(months.size() - 1);
        boolean reportingMonthIsCurrent =
                currentMonth.equals(YearMonth.now(UserZone.forUser(userRepository, userId)).toString());
        // Generalized from a `months.size() > 1 ? months.subList(...) : List.of()` index-based
        // slice off the end of `months`, which silently assumed currentMonth was always the
        // newest element. Filtering by comparison to currentMonth directly makes an explicit
        // requestedMonth (which need not be the newest, or even present in `months` at all) work
        // the same way -- and produces the exact same result as before when currentMonth IS the
        // newest element, since filtering out everything >= the last element leaves everything
        // before it.
        List<String> candidatePriorMonths = months.stream().filter(m -> m.compareTo(currentMonth) < 0).toList();
        List<String> priorMonths = candidatePriorMonths.size() > PRIOR_MONTHS_WINDOW
                ? candidatePriorMonths.subList(candidatePriorMonths.size() - PRIOR_MONTHS_WINDOW, candidatePriorMonths.size())
                : candidatePriorMonths;

        List<StatementCoverageAnalyzer.CoverageGap> gaps = coverageGapsAcross(userId, liveAccountIds);

        return Optional.of(new Pipeline(currentMonth, reportingMonthIsCurrent, priorMonths, txns, categoriesById,
                refunds, gaps));
    }
```

- [ ] **Step 4: Generalize `build()`** — replace lines 90-91 (the method signature and its first
  line):

```java
    @Transactional(readOnly = true)
    public InsightsDto build(UUID userId) {
        return build(userId, null);
    }

    /** @param month {@code "yyyy-MM"}, or {@code null} for today's default (the newest month
     *  with reportable expense data) -- backs the mobile Spending tab's month picker. */
    @Transactional(readOnly = true)
    public InsightsDto build(UUID userId, String month) {
        Optional<Pipeline> maybePipeline = pipeline(userId, month);
```

(The rest of `build()`'s body, from the existing line 93 onward, is unchanged — it already reads
`currentMonth`/`priorMonths` off the `Pipeline` record rather than recomputing anything itself.)

- [ ] **Step 5: Run to verify the new tests pass**

Run: `cd backend && mvn test -Dtest=InsightsServiceTest -q`
Expected: PASS (all tests in the file, old and new — the three new ones plus every pre-existing
one, which exercises the `month == null` delegation path)

- [ ] **Step 6: Run the full backend test suite for regressions** (`pipeline()`'s signature
  changed; `InsightsExplorerService` calls it too)

Run: `cd backend && mvn test -q`
Expected: PASS, including `InsightsExplorerServiceTest` if one exists (unaffected —
`InsightsExplorerService` still calls the single-arg `pipeline(userId)` overload, unchanged).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/InsightsService.java backend/src/test/java/com/finora/service/InsightsServiceTest.java
git commit -m "feat(backend): let InsightsService.build report an explicit past month"
```

---

### Task 7: `InsightsController.java` — expose the `month` query param

**Files:**
- Modify: `backend/src/main/java/com/finora/controller/InsightsController.java`

**Interfaces:**
- Consumes: `InsightsService.build(UUID, String)` from Task 6.
- Produces: `GET /api/v1/insights?month=yyyy-MM` — a malformed `month` returns 400 via
  `GlobalExceptionHandler`'s existing global `DateTimeParseException` handler (verified: it's
  already registered for exactly this class of unguarded-month-param bug, see its own doc
  comment citing `ReportService.forMonth`), not a new validation path.

- [ ] **Step 1: Edit the controller**

```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.InsightsDto;
import com.finora.security.CurrentUser;
import com.finora.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final CurrentUser currentUser;

    public InsightsController(InsightsService insightsService, CurrentUser currentUser) {
        this.insightsService = insightsService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<InsightsDto> insights(@RequestParam(required = false) String month) {
        return ApiResponse.ok(insightsService.build(currentUser.id(), month));
    }
}
```

- [ ] **Step 2: Backend compiles and the existing test suite still passes** (no dedicated
  controller test exists for this endpoint today — `InsightsServiceTest` is the real coverage)

Run: `cd backend && mvn test -Dtest=InsightsServiceTest -q && mvn compile -q`
Expected: PASS, no compile errors

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/controller/InsightsController.java
git commit -m "feat(backend): accept an optional month query param on GET /insights"
```

---

### Task 8: OpenAPI regen — `openapi.json` + 3 clients' `generated-types.ts`

**Files:**
- Modify (generated, do not hand-edit): `backend/openapi/openapi.json`,
  `mobile/src/api/generated-types.ts`, `frontend/src/api/generated-types.ts`,
  `admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Consumes: Task 7's `@RequestParam String month` on `InsightsController.insights`.
- Produces: an updated `month` parameter entry on the `/insights` path in `openapi.json`,
  reflected in all three clients' generated types. This repo's CI blocks on this file being
  stale, so it must be committed even though nothing in this plan's mobile code imports these
  generated types directly for `insightsApi` (that wrapper is hand-written in `endpoints.ts`,
  Task 9) — the drift check compares the generated output against what's committed, not against
  what any one client actually uses.

- [ ] **Step 1: Regenerate the OpenAPI spec**

Run: `cd backend && ./scripts/generate-openapi-spec.sh`
Expected: `backend/openapi/openapi.json` changes — a `month` parameter appears on the `GET
/api/v1/insights` operation.

- [ ] **Step 2: Regenerate each client's types**

Run:
```bash
cd mobile && npm run generate:types
cd ../frontend && npm run generate:types
cd ../admin-portal && npm run generate:types
```
Expected: each `generated-types.ts` changes in the section describing the `/insights` operation.

- [ ] **Step 3: Confirm the diff is exactly this one param, nothing else drifted**

Run: `cd /Users/sid/Downloads/finora/.claude/worktrees/insights-spending-tab && git diff --stat backend/openapi/openapi.json mobile/src/api/generated-types.ts frontend/src/api/generated-types.ts admin-portal/src/api/generated-types.ts`
Expected: four files changed, each a small diff scoped to the `/insights` operation/its params —
if any other endpoint's generated output changed, stop and investigate before committing (it
would mean something else on `main` had already drifted, unrelated to this change).

- [ ] **Step 4: Commit**

```bash
git add backend/openapi/openapi.json mobile/src/api/generated-types.ts frontend/src/api/generated-types.ts admin-portal/src/api/generated-types.ts
git commit -m "chore: regenerate OpenAPI spec and client types for GET /insights month param"
```

---

## Track C: `InsightsScreen.tsx` pill tab bar + Spending content

### Task 9: `endpoints.ts` — `insightsApi.get(month?)`

**Files:**
- Modify: `mobile/src/api/endpoints.ts:811-813`

**Interfaces:**
- Consumes: Task 8's regenerated `month` param (functionally works even before Task 8, since
  this wrapper is hand-written, but committing it before the backend supports the param would
  make it a no-op — sequenced after Track B for that reason).
- Produces: `insightsApi.get(month?: string) => Promise<InsightsData>`, mirroring
  `analyticsApi.topMerchants(month)`'s exact param-passing shape.

- [ ] **Step 1: Edit**

```typescript
export const insightsApi = {
  // month is "YYYY-MM"; omitted means the current/newest reporting month, same as before this
  // param existed -- see InsightsService.build's own doc comment.
  get: (month?: string) =>
    api.get<InsightsData>('/insights', { params: month ? { month } : {} }).then((r) => r.data),
};
```

- [ ] **Step 2: Type-check** (`InsightsScreen.tsx` and `DashboardScreen.tsx` call `insightsApi.get()`
  with no args today — confirm the now-optional param doesn't break either call site)

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep -E "endpoints.ts|InsightsScreen.tsx|DashboardScreen.tsx"`
Expected: no output

- [ ] **Step 3: Commit**

```bash
git add mobile/src/api/endpoints.ts
git commit -m "feat(mobile): accept an optional month on insightsApi.get"
```

---

### Task 10: `InsightsScreen.tsx` — pill tab bar shell + scroll-reset

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`

**Interfaces:**
- Produces: `TabKey` type, `activeTab` state, a rendered pill row between the header and
  everything else, all existing Overview content now wrapped in `{activeTab === 'overview' ? (...) : null}`,
  scroll position resets to top on every tab switch.

- [ ] **Step 1: Add the `TabKey` type and tab list above the component** (after the existing
  `const OTHER_LABEL = 'Other';` on line 28):

```typescript
type TabKey = 'overview' | 'spending' | 'income' | 'recurring' | 'trends';
const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'spending', label: 'Spending' },
  { key: 'income', label: 'Income' },
  { key: 'recurring', label: 'Recurring' },
  { key: 'trends', label: 'Trends' },
];
```

- [ ] **Step 2: Add `activeTab` state and the tab-switch helper** — right after the existing
  `const scrollRef = useRef<ScrollView>(null);` / `const recurringListY = useRef(0);` pair
  (lines 90-91):

```typescript
  const [activeTab, setActiveTab] = useState<TabKey>('overview');
  // A single ScrollView holds every tab's content (swapped below, not a separate ScrollView per
  // tab) -- switching tabs doesn't reset its scroll offset on its own, so a plain pill tap would
  // otherwise leave the new tab's content showing mid-scroll.
  function switchTab(tab: TabKey) {
    setActiveTab(tab);
    scrollRef.current?.scrollTo({ y: 0, animated: false });
  }
```

- [ ] **Step 3: Render the pill row** — insert right after the closing `</View>` of the header
  block (after line 178, before the `{summary ? (` track-banner block):

```typescript
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        style={styles.tabRow}
        contentContainerStyle={styles.tabRowContent}
      >
        {TABS.map((t) => (
          <Pressable
            key={t.key}
            onPress={() => switchTab(t.key)}
            style={[styles.tabPill, activeTab === t.key ? { backgroundColor: c.primaryLight } : null]}
            accessibilityRole="button"
            accessibilityState={{ selected: activeTab === t.key }}
            accessibilityLabel={t.label}
          >
            <Text
              style={[styles.tabPillText, { color: activeTab === t.key ? c.primary : c.mutedInk }]}
              numberOfLines={largeText ? 2 : 1}
            >
              {t.label}
            </Text>
          </Pressable>
        ))}
      </ScrollView>
```

- [ ] **Step 4: Wrap all existing Overview-only sections in `activeTab === 'overview'`** — this is
  a pure two-line insertion around existing, unmodified code, not a rewrite: nothing between them
  changes in this task (Task 11 edits inside this wrapped region, but that's a separate task).

  Immediately **before** today's line 180 (`{summary ? (` — the start of the track-banner block),
  insert:
  ```typescript
      {activeTab === 'overview' ? (
        <>
  ```
  Immediately **after** today's line 474 (the bottom banner's closing `) : null}`), insert:
  ```typescript
        </>
      ) : null}
  ```
  Every line from the old 180 through 474 — track banner, "This Month at a Glance", the
  disclaimer notice, "Key Insights", "Spending by Category", the compact Recurring summary card,
  the full Recurring list, and the bottom banner — now sits between these two insertions,
  unedited. Confirm with a diff that shows only two additions and nothing else in this range:

Run: `git diff mobile/src/screens/InsightsScreen.tsx | grep -E "^\+" | grep -v "activeTab === 'overview'\|<>\|</>\|) : null}"`
Expected: no output (proves nothing inside the wrapped region was accidentally touched)

- [ ] **Step 5: Add the tab styles** — append to the `StyleSheet.create` call:

```typescript
  tabRow: { marginBottom: spacing.md },
  tabRowContent: { gap: spacing.xs, paddingRight: spacing.md },
  tabPill: {
    paddingHorizontal: spacing.md, paddingVertical: 6, borderRadius: radius.lg,
  },
  tabPillText: { fontSize: 13, fontWeight: '600' },
```

(`radius.lg` — check `theme.ts` exports it; every other `radius.*` reference in this file uses
`radius.md`, so confirm `radius.lg` exists before using it — if not, use `radius.md` instead to
stay consistent with the rest of the file.)

- [ ] **Step 6: Existing test suite still passes** (Overview content is unchanged, just wrapped —
  every existing assertion should still find its target since `activeTab` defaults to
  `'overview'`)

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx`
Expected: PASS (no assertions should need changes yet — Task 15 handles the Recurring-list-moved
assertions)

- [ ] **Step 7: Type-check**

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep InsightsScreen`
Expected: no output

- [ ] **Step 8: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx
git commit -m "feat(mobile): add the pill tab bar shell to InsightsScreen"
```

---

### Task 11: Overview — remove the full Recurring list, defer "View Recurring"'s scroll

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx` (inside the `activeTab === 'overview'` block
  from Task 10 — today's lines 376-393 compact summary card, 395-458 full list)

**Interfaces:**
- Produces: `pendingScrollToRecurring` ref, consumed by Task 13's Recurring section on Spending.

- [ ] **Step 1: Add the pending-scroll ref** next to `recurringListY`:

```typescript
  const recurringListY = useRef(0);
  // Set true by Overview's "View Recurring" link, consumed by Spending's own Recurring section
  // onLayout (Task 13) -- switching tabs re-renders before Spending's content has mounted or
  // measured, so a scrollTo call fired synchronously in this link's own onPress would land on a
  // stale/zero y. Deferring to the real onLayout event is what makes the scroll land correctly.
  const pendingScrollToRecurring = useRef(false);
```

- [ ] **Step 2: Delete the full "Recurring Payments & Subscriptions" list block** from inside
  Overview (today's lines 395-458, the `{recurringQ.isLoading ? (...) : (<View onLayout={...}><Card>...full list...</Card></View>)}`
  block) — it moves to Spending in Task 13, verbatim.

- [ ] **Step 3: Change the compact summary card's link** (today's lines 376-393) — replace the
  `onPress`:

```typescript
      {recurring.length > 0 ? (
        <Card style={styles.section}>
          <View style={styles.recurringSummaryRow}>
            <View>
              <Text style={[styles.recurringSummaryCount, { color: c.ink }]}>{recurring.length} active</Text>
              <Text style={[styles.recurringSummaryTotal, { color: c.mutedInk }]}>
                {fmtCurrency(recurring.reduce((s, r) => s + r.averageAmount, 0))} / month
              </Text>
            </View>
            <Pressable
              onPress={() => {
                pendingScrollToRecurring.current = true;
                switchTab('spending');
              }}
              accessibilityRole="button"
            >
              <Text style={[styles.viewRecurring, { color: c.primary }]}>View Recurring →</Text>
            </Pressable>
          </View>
        </Card>
      ) : null}
```

(`recurringListY` itself is now unused after this deletion — remove its declaration too, since
Task 13's version reads the layout `y` directly from the event rather than storing it in a ref.)

- [ ] **Step 4: Update the existing test for the moved list** — `InsightsScreen.test.tsx`'s
  `'renders recurring payments, movers, and the full observations behind "See all insights"'`
  test currently expects `netflix` on Overview directly; it now only appears on Spending. Replace
  it:

```typescript
  it('renders movers directly on Overview; recurring payments live under Spending', async () => {
    renderScreen();

    expect(await screen.findByText('Dining')).toBeTruthy();
    expect(screen.queryByText('netflix')).toBeNull();
    // Sentences are still collapsed by default on Overview -- unchanged.
    expect(screen.queryByText('You spent 18% less on dining this month.')).toBeNull();

    fireEvent.press(screen.getByText('See all insights'));
    expect(screen.getByText('You spent 18% less on dining this month.')).toBeTruthy();
  });
```

The `'dismisses a recurring group and removes it from the list'` test also needs to switch to
Spending first — this is fixed in Task 13 alongside the Recurring section landing there, since
that test needs the Spending tab's content to exist first.

- [ ] **Step 5: Run to verify the updated test passes** (the dismiss test will still fail here —
  expected, fixed in Task 13)

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx -t "renders movers directly on Overview"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "refactor(mobile): move Insights' full Recurring list off Overview, defer its scroll-to link"
```

---

### Task 12: Spending — Observations + month picker

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`

**Interfaces:**
- Consumes: `insightsApi.get(month?)` (Task 9), `reportsApi.availableMonths()` (existing),
  `OptionPickerModal` (existing), `monthLabelLong` (existing, in `lib/format.ts`).
- Produces: `spendingInsightsQ`, `month`/`setMonth` state, `spendingSentences`, feeds Task 14's
  Category Movers section too.

- [ ] **Step 1: Add imports** — `reportsApi` to the existing `../api/endpoints` import,
  `OptionPickerModal` as a new import, `monthLabelLong` to the existing `../lib/format` import:

```typescript
import {
  categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi, reportsApi, type RecurringItem,
} from '../api/endpoints';
import { OptionPickerModal } from '../components/OptionPickerModal';
```
```typescript
import { fmtCurrency, fmtDate, monthDateRange, monthLabel, monthLabelLong } from '../lib/format';
```

- [ ] **Step 2: Add Spending's state and queries** — after Task 10's `switchTab` function:

```typescript
  // Spending tab's month picker. `undefined` means "no explicit pick yet" -- see the query key
  // below for why that's load-bearing, not just a default value.
  const [month, setMonth] = useState<string | undefined>(undefined);
  const [monthPickerOpen, setMonthPickerOpen] = useState(false);
  const [showAllMovers, setShowAllMovers] = useState(false);

  // Bug found in review: a naive `['insights', month]` key is a DIFFERENT cache entry from
  // Overview's (and DashboardScreen's) own `['insights']` even when `month` is `undefined` --
  // that would fire a redundant network call for identical data on every Insights screen open,
  // and flash a skeleton Spending doesn't need. Sharing the literal `['insights']` key until a
  // month is actually picked means both `useQuery` calls below coalesce into the one request
  // TanStack Query already dedupes for an identical key mounted twice.
  const spendingInsightsQ = useQuery({
    queryKey: month ? ['insights', month] : ['insights'],
    queryFn: () => insightsApi.get(month),
  });
  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  // Newest first, same reasoning as AdvancedReportsScreen's own identical picker.
  const monthsNewestFirst = useMemo(() => [...(monthsQ.data ?? [])].reverse(), [monthsQ.data]);
  const monthOptions = useMemo(() => monthsNewestFirst.map(monthLabelLong), [monthsNewestFirst]);
  const labelToMonth = useMemo(() => {
    const map: Record<string, string> = {};
    monthsNewestFirst.forEach((m) => { map[monthLabelLong(m)] = m; });
    return map;
  }, [monthsNewestFirst]);
  // Before any explicit pick, show the same current reporting month Overview's own summary
  // already carries -- avoids a second source of truth for "what month is this by default".
  const selectedMonthLabel = month
    ? monthLabelLong(month)
    : summary?.reportingMonth ? monthLabelLong(summary.reportingMonth) : '';
  const spendingSentences = spendingInsightsQ.data?.sentences ?? [];
```

- [ ] **Step 3: Render the Spending tab** — add right after the `{activeTab === 'overview' ? (...) : null}`
  block from Task 10:

```typescript
      {activeTab === 'spending' ? (
        <>
          <View style={[styles.notice, { backgroundColor: c.primaryLight, borderLeftColor: c.primary }]}>
            <Text style={[styles.noticeText, { color: c.ink }]}>
              These are rule-based statistical observations from your own transaction history —
              not an AI-generated assistant.
            </Text>
          </View>

          {spendingInsightsQ.isLoading ? (
            <SkeletonCard style={styles.section} lines={5} />
          ) : (
            <Card style={styles.section}>
              <SectionHeading
                title="This Month's Observations"
                action={
                  <Pressable
                    onPress={() => setMonthPickerOpen(true)}
                    style={styles.monthPickerButton}
                    accessibilityRole="button"
                    accessibilityLabel={`Change month, currently ${selectedMonthLabel}`}
                  >
                    <Text style={[styles.monthPickerText, { color: c.ink }]} numberOfLines={1}>
                      {selectedMonthLabel}
                    </Text>
                    <Ionicons name="chevron-down" size={14} color={c.muted} />
                  </Pressable>
                }
              />
              {spendingInsightsQ.isError ? (
                <Text style={[styles.error, { color: c.danger }]}>
                  Couldn&apos;t load your insights — pull down to try again.
                </Text>
              ) : spendingSentences.length === 0 ? (
                <EmptyState message="Nothing stands out this month yet — observations appear as more transactions land." />
              ) : (
                spendingSentences.map((s, i) => (
                  <View key={i} style={[styles.observation, { borderLeftColor: c.border }]}>
                    <Text style={[styles.observationText, { color: c.ink }]}>{s}</Text>
                  </View>
                ))
              )}
            </Card>
          )}

          {/* Task 13 adds the moved Recurring Payments list here. */}
          {/* Task 14 adds the Category Movers section here. */}
        </>
      ) : null}
```

- [ ] **Step 4: Add the `OptionPickerModal` as a sibling of the outer `ScrollView`** (same
  placement `AdvancedReportsScreen.tsx` uses — outside the scroll content, gated only by
  `visible`). Today, before this task, `return (` on line 156 directly wraps a single
  `<ScrollView ...>` that closes with `</ScrollView>` on line 475, followed by `);` on line 476
  and the function's closing `}` on 477 — the `ScrollView` needs a sibling, so the outermost
  element becomes a fragment.

  Replace line 156 (`return (`) with:
```typescript
  return (
    <>
```
  Replace lines 475-476 (`    </ScrollView>` then `  );`) with:
```typescript
    </ScrollView>
    <OptionPickerModal
      visible={monthPickerOpen}
      title="Month"
      options={monthOptions}
      selected={selectedMonthLabel}
      onSelect={(label) => { setMonth(labelToMonth[label]); setMonthPickerOpen(false); }}
      onClose={() => setMonthPickerOpen(false)}
    />
  </>
  );
```

- [ ] **Step 5: Add the month-picker button styles**

```typescript
  monthPickerButton: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  monthPickerText: { fontSize: 13, fontWeight: '600' },
```

- [ ] **Step 6: Write the query-key regression test** — add to `InsightsScreen.test.tsx`:

```typescript
  it('shares one insights fetch between Overview and Spending until a month is picked', async () => {
    renderScreen();
    await screen.findByText('Dining');

    // Two useQuery observers key off ['insights'] before any month is picked (Overview's own,
    // and Spending's) -- exactly one network call, not two, because they share a cache key.
    expect(insights.get).toHaveBeenCalledTimes(1);

    fireEvent.press(screen.getByText('Spending'));
    await screen.findByText('You spent 18% less on dining this month.');

    expect(insights.get).toHaveBeenCalledTimes(1);
  });

  it('picking a month on Spending refetches insights for that month', async () => {
    reports.availableMonths = jest.fn().mockResolvedValue(['2026-06', '2026-07']);
    renderScreen();
    fireEvent.press(await screen.findByText('Spending'));
    await screen.findByText('You spent 18% less on dining this month.');

    fireEvent.press(screen.getByLabelText(/Change month/));
    fireEvent.press(screen.getByText('June 2026'));

    await waitFor(() => expect(insights.get).toHaveBeenLastCalledWith('2026-06'));
  });
```

Add the `reportsApi` mock and import to the top of the test file:
```typescript
import { categoriesApi, dashboardApi, insightsApi, onboardingApi, recurringApi, reportsApi } from '../api/endpoints';
```
```typescript
jest.mock('../api/endpoints', () => ({
  insightsApi: { get: jest.fn() },
  recurringApi: { list: jest.fn(), dismiss: jest.fn() },
  dashboardApi: { summary: jest.fn() },
  categoriesApi: { list: jest.fn() },
  reportsApi: { availableMonths: jest.fn() },
  onboardingApi: {
    getChecklist: jest.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: jest.fn().mockResolvedValue(undefined),
  },
}));
```
```typescript
const reports = reportsApi as jest.Mocked<typeof reportsApi>;
```
And in the outer `beforeEach`, default it so tests that don't care about the picker don't hang:
```typescript
    reports.availableMonths.mockReset().mockResolvedValue([]);
```

- [ ] **Step 7: Run to verify the new tests pass**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx -t "insights fetch"`
Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx -t "picking a month"`
Expected: PASS

- [ ] **Step 8: Type-check**

Run: `cd mobile && npx tsc --noEmit 2>&1 | grep InsightsScreen`
Expected: no output

- [ ] **Step 9: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add Spending tab's Observations section with a month picker"
```

---

### Task 13: Spending — move the Recurring list in, resolve the deferred scroll

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`

**Interfaces:**
- Consumes: `pendingScrollToRecurring` ref (Task 11).

- [ ] **Step 1: Add the moved Recurring block** in place of the `{/* Task 13 adds ... */}` comment
  from Task 12 — this is Task 11's deleted block, verbatim, except its `onLayout` now resolves
  the pending-scroll intent instead of just recording `y` for a same-tab scroll:

```typescript
          {recurringQ.isLoading ? (
            <SkeletonCard style={styles.section} lines={4} />
          ) : (
            <View
              onLayout={(e) => {
                if (pendingScrollToRecurring.current) {
                  pendingScrollToRecurring.current = false;
                  scrollRef.current?.scrollTo({ y: e.nativeEvent.layout.y, animated: true });
                }
              }}
            >
              <Card style={styles.section}>
                <SectionHeading title="Recurring Payments & Subscriptions" />
                {recurringQ.isError ? (
                  <Text style={[styles.error, { color: c.danger }]}>
                    Couldn&apos;t load recurring payments — pull down to try again.
                  </Text>
                ) : recurring.length === 0 ? (
                  <EmptyState message="No recurring payments detected yet — this needs at least 2 charges from the same merchant on a regular interval to spot a pattern." />
                ) : (
                  recurring.map((r) => (
                    // eslint-disable-next-line react-native-a11y/no-nested-touchables
                    <View
                      key={r.merchant}
                      style={[styles.row, { borderBottomColor: c.border }]}
                      accessible
                      accessibilityLabel={`${r.merchant}, ${r.label}. ${fmtCurrency(r.averageAmount)} on average, seen ${
                        r.occurrences
                      } times. Next expected around ${fmtDate(r.nextEstimate) ?? r.nextEstimate}`}
                      accessibilityActions={[{ name: 'dismiss', label: 'Not recurring' }]}
                      onAccessibilityAction={(e) => {
                        if (e.nativeEvent.actionName === 'dismiss') dismissRecurring.mutate(r.merchant);
                      }}
                    >
                      <View style={styles.rowMain}>
                        <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                          {r.merchant}
                        </Text>
                        <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                          {fmtCurrency(r.averageAmount)} · seen {r.occurrences}×
                        </Text>
                      </View>
                      <View style={styles.rowRight}>
                        <Text style={[styles.badge, { color: c.primary, backgroundColor: c.primaryLight, borderWidth: 1, borderColor: c.border }]}>{r.label}</Text>
                        <Text style={[styles.rowMeta, { color: c.mutedInk }]}>next ~{fmtDate(r.nextEstimate) ?? r.nextEstimate}</Text>
                      </View>
                      <Pressable
                        onPress={() => dismissRecurring.mutate(r.merchant)}
                        disabled={dismissRecurring.isPending}
                        hitSlop={10}
                        style={styles.dismissButton}
                        accessible={false}
                        testID={`dismiss-recurring-${r.merchant}`}
                      >
                        <Ionicons name="close" size={16} color={c.muted} />
                      </Pressable>
                    </View>
                  ))
                )}
              </Card>
            </View>
          )}
```

- [ ] **Step 2: Fix the dismiss test** — replace the existing
  `'dismisses a recurring group and removes it from the list'` test to switch tabs first:

```typescript
  it('dismisses a recurring group from the Spending tab and removes it from the list', async () => {
    recurring.dismiss.mockReset().mockResolvedValue(undefined);
    renderScreen();
    fireEvent.press(await screen.findByText('Spending'));
    await screen.findByText('netflix');

    fireEvent.press(screen.getByTestId('dismiss-recurring-netflix'));

    await waitFor(() => expect(recurring.dismiss).toHaveBeenCalledWith('netflix'));
    await waitFor(() => expect(screen.queryByText('netflix')).toBeNull());
  });
```

- [ ] **Step 3: Add a test for the deferred-scroll fix** — this can't assert the actual scroll
  offset (`ScrollView.scrollTo` isn't observable in `@testing-library/react-native`'s JSDOM-like
  environment), but it can prove the flow doesn't crash and lands on the right tab with the right
  content visible, which is what a real regression here would break first:

```typescript
  it('"View Recurring" on Overview switches to Spending and shows the list there', async () => {
    renderScreen();
    await screen.findByText('Dining');

    fireEvent.press(screen.getByText('View Recurring →'));

    expect(await screen.findByText('netflix')).toBeTruthy();
  });
```

- [ ] **Step 4: Run to verify all three pass**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx`
Expected: PASS (full file — this is also the point where every earlier task's test in this file
should be green together for the first time)

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): move the Recurring list onto Spending, resolve View Recurring's scroll"
```

---

### Task 14: Spending — Category Movers, uncapped with "See All"

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`

**Interfaces:**
- Consumes: `spendingInsightsQ` (Task 12), `showAllMovers` state (Task 12), `iconTokenForCategory`/
  `colorTokenForCategory`/`openTransactionsFiltered` (existing, already used by Overview's Key
  Insights movers).

- [ ] **Step 1: Compute the movers list** — add next to `spendingSentences` (Task 12, Step 2):

```typescript
  const spendingMoversAll = (spendingInsightsQ.data?.movers ?? []).filter((m) => m.pctChange !== null);
  const spendingMoversShown = showAllMovers ? spendingMoversAll : spendingMoversAll.slice(0, 5);
```

- [ ] **Step 2: Render the section** in place of the `{/* Task 14 adds ... */}` comment from
  Task 12:

```typescript
          {spendingInsightsQ.isLoading ? (
            <SkeletonCard style={styles.section} lines={4} />
          ) : (
            <Card style={styles.section}>
              <SectionHeading
                title="Category Movers vs. Recent Average"
                action={spendingMoversAll.length > 5 ? (
                  <Pressable onPress={() => setShowAllMovers((v) => !v)} accessibilityRole="button">
                    <Text style={[styles.seeAll, { color: c.primary }]}>
                      {showAllMovers ? 'Show less' : 'See All'}
                    </Text>
                  </Pressable>
                ) : undefined}
              />
              {spendingMoversAll.length === 0 ? (
                <EmptyState message="Not enough history yet to compare trends — add a few months of transactions." />
              ) : (
                spendingMoversShown.map((m) => (
                  <Pressable
                    key={m.category}
                    style={[styles.insightRow, { borderBottomColor: c.border }]}
                    accessibilityRole="button"
                    accessibilityLabel={`${m.category} spend was ${Math.abs(m.pctChange ?? 0).toFixed(0)}% ${
                      (m.pctChange ?? 0) >= 0 ? 'more' : 'lower'
                    } than your recent average, ${fmtCurrency(m.current)} versus usual ${fmtCurrency(m.priorAverage)}`}
                    accessibilityHint="Opens these transactions"
                    android_ripple={{ color: c.border }}
                    onPress={() => openTransactionsFiltered({ categoryName: m.category, label: m.category })}
                  >
                    <View style={[styles.insightIcon, { backgroundColor: colorHexFor(colorTokenForCategory(m.category)) }]}>
                      <Ionicons name={iconNameFor(iconTokenForCategory(m.category))} size={16} color="#fff" />
                    </View>
                    <View style={styles.rowMain}>
                      <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                        {m.category}
                      </Text>
                      <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                        {fmtCurrency(m.current)} vs usual {fmtCurrency(m.priorAverage)}
                      </Text>
                    </View>
                    <Text style={[styles.delta, { color: (m.pctChange ?? 0) >= 0 ? c.danger : c.success }]}>
                      {(m.pctChange ?? 0) >= 0 ? '▲' : '▼'} {Math.abs(m.pctChange ?? 0).toFixed(0)}%
                    </Text>
                  </Pressable>
                ))
              )}
            </Card>
          )}
```

- [ ] **Step 3: Write the test**

```typescript
  it('Category Movers on Spending shows the first 5 with a See All expand', async () => {
    insights.get.mockReset().mockResolvedValue({
      sentences: [],
      movers: Array.from({ length: 7 }, (_, i) => ({
        category: `Cat${i}`, current: 100, priorAverage: 50, pctChange: 100,
      })),
      coverageCaveat: null, biggestCategory: null, topMerchant: null,
    });
    renderScreen();
    fireEvent.press(await screen.findByText('Spending'));

    await screen.findByText('Cat0');
    expect(screen.queryByText('Cat6')).toBeNull();

    fireEvent.press(screen.getByText('See All'));
    expect(screen.getByText('Cat6')).toBeTruthy();
  });
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx -t "Category Movers on Spending"`
Expected: PASS

- [ ] **Step 5: Run the full file for regressions**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add Spending tab's uncapped Category Movers section"
```

---

### Task 15: Income/Recurring/Trends placeholders + refresh/refreshing fix

**Files:**
- Modify: `mobile/src/screens/InsightsScreen.tsx`

**Interfaces:**
- Produces: three placeholder tabs; `refresh()` now also invalidates `['report-months']`;
  `deriveRefreshing` includes `spendingInsightsQ` while Spending is active with a month picked.

- [ ] **Step 1: Add the three placeholder blocks** — as siblings of the `overview`/`spending`
  conditionals:

```typescript
      {activeTab === 'income' ? (
        <Card style={styles.section}>
          <EmptyState message="Income breakdown is coming soon." />
        </Card>
      ) : null}

      {activeTab === 'recurring' ? (
        <Card style={styles.section}>
          <EmptyState message="A dedicated Recurring tab is coming soon — see the Recurring Payments list under Spending for now." />
        </Card>
      ) : null}

      {activeTab === 'trends' ? (
        <Card style={styles.section}>
          <EmptyState message="Spending trends over time are coming soon." />
        </Card>
      ) : null}
```

- [ ] **Step 2: Fix `refresh()`** — add the `report-months` invalidation (same reasoning
  `AdvancedReportsScreen.tsx`'s own refresh already documents: it's the source of the month
  picker's valid range):

```typescript
  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['insights'] });
    void queryClient.invalidateQueries({ queryKey: ['recurring'] });
    void queryClient.invalidateQueries({ queryKey: ['report-months'] });
  }
```

- [ ] **Step 3: Fix `refreshing`** — bug found in review: the spinner doesn't cover
  `spendingInsightsQ` once it's a genuinely separate query (a month picked), so pulling to
  refresh on Spending with a past month selected can stop the spinner before that query's own
  refetch lands:

```typescript
  const refreshing = deriveRefreshing(
    activeTab === 'spending' && month ? [insightsQ, recurringQ, spendingInsightsQ] : [insightsQ, recurringQ],
    insightsQ.isLoading || recurringQ.isLoading,
  );
```

- [ ] **Step 4: Write the placeholder tests**

```typescript
  it.each([
    ['Income', 'Income breakdown is coming soon.'],
    ['Recurring', 'A dedicated Recurring tab is coming soon — see the Recurring Payments list under Spending for now.'],
    ['Trends', 'Spending trends over time are coming soon.'],
  ])('%s tab shows a coming-soon placeholder', async (tabLabel, message) => {
    renderScreen();
    fireEvent.press(await screen.findByText(tabLabel));
    expect(await screen.findByText(message)).toBeTruthy();
  });
```

- [ ] **Step 5: Run to verify**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx -t "coming-soon placeholder"`
Expected: PASS (3 cases)

- [ ] **Step 6: Run the full file one more time**

Run: `cd mobile && npx jest src/screens/InsightsScreen.test.tsx`
Expected: PASS

- [ ] **Step 7: Full mobile type-check and lint on every touched file**

Run: `cd mobile && npx tsc --noEmit`
Expected: PASS, zero errors

Run: `cd mobile && npx eslint src/screens/InsightsScreen.tsx src/screens/InsightsScreen.test.tsx src/navigation/AppTabs.tsx src/navigation/AppTabs.test.tsx src/navigation/types.ts src/screens/MoreScreen.tsx src/onboarding/tourSteps.ts src/screens/DashboardScreen.tsx src/screens/DashboardScreen.test.tsx src/api/endpoints.ts`
Expected: zero warnings

- [ ] **Step 8: Commit**

```bash
git add mobile/src/screens/InsightsScreen.tsx mobile/src/screens/InsightsScreen.test.tsx
git commit -m "feat(mobile): add Income/Recurring/Trends placeholders, fix refresh coverage"
```

---

## Final verification (after all 15 tasks)

- [ ] **Backend full suite**: `cd backend && mvn test -q` — PASS, no regressions anywhere
  (`pipeline()`'s signature change is the widest-blast-radius edit in this plan).
- [ ] **Mobile full suite**: `cd mobile && npx jest` — PASS.
- [ ] **Mobile type-check**: `cd mobile && npx tsc --noEmit` — zero errors.
- [ ] **Mobile lint**: `cd mobile && npx eslint src` — zero warnings.
- [ ] **Manual device verification** (iOS simulator, per this repo's mandatory
  post-implementation verification standard — a green suite is not proof of correctness here):
  - Bottom tab bar shows Insights where Goals used to be; Goals is reachable at More > Goals, in
    its old position (right after Budgets).
  - Onboarding tour: the `goals` step lands on the More menu's Goals row; the `insights` step
    lands directly on the Insights tab icon. The two consecutive `'More'` steps (budgets → goals)
    don't visibly glitch.
  - Insights screen: five pills render, Overview is selected by default and looks unchanged
    (minus the full Recurring list). Switching to Spending shows Observations (uncollapsed),
    Recurring, and Category Movers, in that order, with the shared disclaimer banner at the top.
  - Tapping "View Recurring →" on Overview lands on Spending scrolled to the Recurring section,
    not the top of the screen.
  - The month picker on Spending opens, lists real past months newest-first, and picking one
    updates the Observations/Category Movers content.
  - "See All" on Category Movers (a real account with more than 5 movers, or verified via the
    unit test's synthetic data if no such account is available) expands the full list.
  - Income/Recurring/Trends pills show their placeholder message.
  - Pull-to-refresh works from both Overview and Spending without the spinner stopping early.

## Execution note

This plan is large (15 tasks across 3 tracks). Track A (Tasks 1-5) and Track B (Tasks 6-9) have
no dependency on each other and can be executed in either order, or in parallel by two workers —
only Track C (Tasks 10-15) depends on Track B being complete (specifically Task 8's OpenAPI
regen, before Task 9's `insightsApi.get(month)` has anything real to call).
