# Multi-Year Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the four Multi-Year Comparison premium views (income progression, spending drift,
category evolution, lifestyle inflation) from issue #1455, gated on `ADVANCED_REPORTS`.

**Architecture:** A pure calendar-month completeness calculator (`MultiYearCoverage`) sits between
two existing primitives — `StatementCoverageAnalyzer`'s per-account gap detection (unioned across a
user's live accounts by a new `AccountCoverageService.gapsForUser`) and `TransactionRepository`'s
existing `findEarliestTxnDate` — and four new `AnalyticsService` methods that bucket the same
EXPENSE/INCOME transaction data every other `AnalyticsService` method already reads. Four new
`AnalyticsController` endpoints expose them behind the existing `ADVANCED_REPORTS` gate. Web and
mobile each add one new section to their existing Advanced Reports surface.

**Tech Stack:** Spring Boot / JPA (backend), React + react-chartjs-2 (web), React Native (mobile),
JUnit 5 + Mockito + AssertJ (backend tests), Vitest (web), Jest (mobile).

**Spec:** `docs/superpowers/specs/2026-09-14-multi-year-comparison-design.md`

## Global Constraints

- Gate the **comparison view only** — raw transactions/history stay fully visible and exportable
  on every plan, unchanged (spec's governing rule).
- **Never hide a year, never extrapolate/annualize** a partial year's total.
- **YoY % / trend deltas only between two years that are both `12/12` complete** (`fullYears`), or
  between two years that both fully cover the same "This Year So Far" relative window
  (`thisYearSoFar`). A year with a `coverageMonths < 12` figure is still shown with its raw total —
  it just never gets a fabricated comparison number attached.
- Reuses the existing `FeatureEntitlement.ADVANCED_REPORTS` key — no new entitlement key.
- No custom/arbitrary date-range comparison UI (spec §4.3) — "This Year So Far" is always anchored
  to the current year automatically.

---

## Task 1: `AccountCoverageService.gapsForUser` — user-level gap union

**Files:**
- Modify: `backend/src/main/java/com/finora/service/AccountCoverageService.java`
- Test: `backend/src/test/java/com/finora/service/AccountCoverageServiceTest.java`

**Interfaces:**
- Produces: `AccountCoverageService.DateRange(LocalDate start, LocalDate end)` (public record),
  `AccountCoverageService.gapsForUser(UUID userId) -> List<DateRange>`.

- [ ] **Step 1: Write the failing test**

Add to `AccountCoverageServiceTest.java` (the file already has `account(...)` and `metadata(...)`
helpers — reuse them):

```java
    @Test
    void gapsForUser_returnsGapsFromEveryLiveAccount_notJustOne() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(
                account(accountA, userId), account(accountB, userId)));

        // Account A: Jan and Mar 2026, gap in Feb.
        StatementMetadata aJan = metadata(UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null);
        StatementMetadata aMar = metadata(UUID.randomUUID(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null);
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountA))
                .thenReturn(List.of(aJan, aMar));

        // Account B: fully continuous, no gap.
        StatementMetadata bJan = metadata(UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), null, null);
        when(statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountB))
                .thenReturn(List.of(bJan));

        List<AccountCoverageService.DateRange> gaps = service.gapsForUser(userId);

        assertThat(gaps).containsExactly(
                new AccountCoverageService.DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
    }

    @Test
    void gapsForUser_returnsEmpty_whenTheUserHasNoLiveAccounts() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.gapsForUser(userId)).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountCoverageServiceTest -pl . -am -q`
Expected: FAIL — `gapsForUser` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

In `AccountCoverageService.java`, add the import `java.util.ArrayList` and `java.time.LocalDate`
(check the existing import block first — `LocalDate` may already be imported transitively; add it
explicitly if not), then add:

```java
    /** A day D, or its DateRange's start/end, using half-open-nothing inclusive bounds (matches
     *  StatementCoverageAnalyzer.CoverageGap's own gapStart/gapEnd, which are both inclusive). */
    public record DateRange(LocalDate start, LocalDate end) {}

    /** Every gap across every live account, for a caller (Multi-Year Comparison, issue #1455)
     *  that needs to know which calendar months are missing data at the USER level, not one
     *  account's own coverage page. A day only ever becomes a gap here because some live account
     *  has a genuine hole BETWEEN two of its own imported statements — an account that simply
     *  didn't exist yet contributes nothing (see StatementCoverageAnalyzer's own doc comment:
     *  gaps are only ever reported between two periods, never before the first or after the
     *  last). Not merged/deduplicated across accounts — a caller only ever asks "does any gap
     *  overlap this month," for which an unmerged list is just as correct and cheaper to build. */
    @Transactional(readOnly = true)
    public List<DateRange> gapsForUser(UUID userId) {
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(Account::getId).toList();
        List<DateRange> gaps = new ArrayList<>();
        for (UUID accountId : liveAccountIds) {
            var periods = statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountId)
                    .stream().map(AccountCoverageService::toStatementPeriod).toList();
            StatementCoverageAnalyzer.analyze(periods).gaps()
                    .forEach(g -> gaps.add(new DateRange(g.gapStart(), g.gapEnd())));
        }
        return gaps;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountCoverageServiceTest -pl . -am -q`
Expected: PASS (all tests in the file, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/AccountCoverageService.java \
        backend/src/test/java/com/finora/service/AccountCoverageServiceTest.java
git commit -m "feat(analytics): add AccountCoverageService.gapsForUser for user-level gap union"
```

---

## Task 2: `MultiYearCoverage` — pure calendar-month completeness math

**Files:**
- Create: `backend/src/main/java/com/finora/service/MultiYearCoverage.java`
- Test: `backend/src/test/java/com/finora/service/MultiYearCoverageTest.java`

**Interfaces:**
- Consumes: `AccountCoverageService.DateRange` (Task 1).
- Produces: `MultiYearCoverage.YearCoverage(int year, int coverageMonths, boolean isComplete)`,
  `MultiYearCoverage.ThisYearWindow(YearMonth windowStart, YearMonth windowEnd)`,
  `MultiYearCoverage.isComplete(YearMonth, YearMonth firstDataMonth, YearMonth currentMonth, List<DateRange>) -> boolean`,
  `MultiYearCoverage.yearCoverages(YearMonth firstDataMonth, YearMonth currentMonth, List<DateRange>) -> List<YearCoverage>`,
  `MultiYearCoverage.thisYearWindow(YearMonth firstDataMonth, YearMonth currentMonth, List<DateRange>) -> Optional<ThisYearWindow>`,
  `MultiYearCoverage.coversSameRelativeWindow(int year, ThisYearWindow, YearMonth firstDataMonth, YearMonth currentMonth, List<DateRange>) -> boolean`.

This is the highest-risk logic in the whole feature (spec §3/§4.2) — test every edge case named in
the spec, not just the happy path.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiYearCoverageTest {

    private static AccountCoverageService.DateRange gap(String start, String end) {
        return new AccountCoverageService.DateRange(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void isComplete_isFalse_forTheCurrentMonth_evenWithNoGap() {
        YearMonth current = YearMonth.of(2026, 3);
        assertThat(MultiYearCoverage.isComplete(current, YearMonth.of(2020, 1), current, List.of())).isFalse();
    }

    @Test
    void isComplete_isFalse_beforeTheUsersFirstDataMonth() {
        YearMonth firstData = YearMonth.of(2025, 6);
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2025, 5), firstData, YearMonth.of(2026, 3), List.of()))
                .isFalse();
    }

    @Test
    void isComplete_isFalse_whenAGapOverlapsTheMonth() {
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-02-10", "2026-02-20"));
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2026, 2), YearMonth.of(2020, 1), YearMonth.of(2026, 3), gaps))
                .isFalse();
    }

    @Test
    void isComplete_isTrue_forAnElapsedMonthWithNoGapAfterFirstData() {
        assertThat(MultiYearCoverage.isComplete(YearMonth.of(2026, 1), YearMonth.of(2020, 1), YearMonth.of(2026, 3), List.of()))
                .isTrue();
    }

    @Test
    void yearCoverages_marksAFullYearComplete_andAPartialYearWithItsRealCount() {
        // First data January 2025; current month March 2026 (so 2026 is inherently partial: only
        // Jan/Feb 2026 can even be "elapsed", and 2025 is a fully-elapsed candidate for 12/12).
        List<MultiYearCoverage.YearCoverage> years = MultiYearCoverage.yearCoverages(
                YearMonth.of(2025, 1), YearMonth.of(2026, 3), List.of());

        assertThat(years).containsExactly(
                new MultiYearCoverage.YearCoverage(2025, 12, true),
                new MultiYearCoverage.YearCoverage(2026, 2, false));
    }

    @Test
    void yearCoverages_countsAMidYearGapAgainstThatYearsCoverage() {
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2025-07-01", "2025-07-31"));
        List<MultiYearCoverage.YearCoverage> years = MultiYearCoverage.yearCoverages(
                YearMonth.of(2025, 1), YearMonth.of(2026, 1), gaps);

        assertThat(years).contains(new MultiYearCoverage.YearCoverage(2025, 11, false));
    }

    @Test
    void thisYearWindow_startsAtJanuary_whenTheUserHasPriorYearHistory() {
        // Current month is March 2026 (Jan/Feb complete, March itself never complete).
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2024, 6), YearMonth.of(2026, 3), List.of());

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2)));
    }

    @Test
    void thisYearWindow_startsAtTheUsersFirstDataMonth_forAMidYearJoiner() {
        // User's very first transaction was June 2026; it is now August 2026 (June, July elapsed).
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2026, 6), YearMonth.of(2026, 8), List.of());

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 6), YearMonth.of(2026, 7)));
    }

    @Test
    void thisYearWindow_stopsAtAGap_ratherThanSkippingPastIt() {
        // February's statement is missing; the window must stop at January, not skip to March.
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-02-01", "2026-02-28"));
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2020, 1), YearMonth.of(2026, 4), gaps);

        assertThat(window).contains(new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 1)));
    }

    @Test
    void thisYearWindow_isEmpty_whenNotEvenTheFirstEligibleMonthIsComplete() {
        // It's early January and January itself hasn't been imported/completed yet.
        List<AccountCoverageService.DateRange> gaps = List.of(gap("2026-01-01", "2026-01-31"));
        Optional<MultiYearCoverage.ThisYearWindow> window = MultiYearCoverage.thisYearWindow(
                YearMonth.of(2020, 1), YearMonth.of(2026, 2), gaps);

        assertThat(window).isEmpty();
    }

    @Test
    void coversSameRelativeWindow_isTrue_whenThePriorYearFullyCoversTheSameMonths() {
        MultiYearCoverage.ThisYearWindow window = new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(MultiYearCoverage.coversSameRelativeWindow(2025, window, YearMonth.of(2020, 1), YearMonth.of(2026, 3), List.of()))
                .isTrue();
    }

    @Test
    void coversSameRelativeWindow_isFalse_whenThePriorYearHasNoDataForThatWindowAtAll() {
        // User's history only starts April 2025 -- 2025's Jan-Feb never existed.
        MultiYearCoverage.ThisYearWindow window = new MultiYearCoverage.ThisYearWindow(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(MultiYearCoverage.coversSameRelativeWindow(2025, window, YearMonth.of(2025, 4), YearMonth.of(2026, 3), List.of()))
                .isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MultiYearCoverageTest -pl . -am -q`
Expected: FAIL — `MultiYearCoverage` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.finora.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure calendar-month completeness math for Multi-Year Comparison (issue #1455;
 * docs/superpowers/specs/2026-09-14-multi-year-comparison-design.md §3/§4.2). Takes the two facts
 * that actually determine whether a month counts as real, comparable data -- when the user's
 * history began, and which months some live account has a genuine statement gap in -- and turns
 * them into per-year coverage badges and the "This Year So Far" comparable window. No repository
 * access, no side effect: {@link AnalyticsService} supplies both inputs (the first via
 * TransactionRepository.findEarliestTxnDate, the second via
 * {@link AccountCoverageService#gapsForUser}).
 */
public final class MultiYearCoverage {

    private MultiYearCoverage() {}

    public record YearCoverage(int year, int coverageMonths, boolean isComplete) {}

    /** windowStart/windowEnd inclusive, both YearMonths within the current calendar year. */
    public record ThisYearWindow(YearMonth windowStart, YearMonth windowEnd) {}

    /** A month is complete iff it is on/after the user's first data month, strictly before the
     *  current month (a month that hasn't finished is never "complete" -- spec §4.2), and not
     *  inside any live account's coverage gap. */
    public static boolean isComplete(YearMonth month, YearMonth firstDataMonth, YearMonth currentMonth,
                                      List<AccountCoverageService.DateRange> gaps) {
        if (month.isBefore(firstDataMonth) || !month.isBefore(currentMonth)) return false;
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        return gaps.stream().noneMatch(g -> !g.end().isBefore(monthStart) && !g.start().isAfter(monthEnd));
    }

    /** One entry per calendar year from firstDataMonth's year through currentMonth's year, in
     *  order. */
    public static List<YearCoverage> yearCoverages(YearMonth firstDataMonth, YearMonth currentMonth,
                                                     List<AccountCoverageService.DateRange> gaps) {
        List<YearCoverage> result = new ArrayList<>();
        for (int year = firstDataMonth.getYear(); year <= currentMonth.getYear(); year++) {
            int coveredMonths = 0;
            for (int m = 1; m <= 12; m++) {
                if (isComplete(YearMonth.of(year, m), firstDataMonth, currentMonth, gaps)) coveredMonths++;
            }
            result.add(new YearCoverage(year, coveredMonths, coveredMonths == 12));
        }
        return result;
    }

    /** The current year's comparable window (spec §4.2): the later of January this year or the
     *  user's first data month, through the longest unbroken run of complete months from there.
     *  Empty when the first eligible month is not itself complete yet. */
    public static Optional<ThisYearWindow> thisYearWindow(YearMonth firstDataMonth, YearMonth currentMonth,
                                                            List<AccountCoverageService.DateRange> gaps) {
        YearMonth januaryThisYear = YearMonth.of(currentMonth.getYear(), 1);
        YearMonth windowStart = firstDataMonth.isAfter(januaryThisYear) ? firstDataMonth : januaryThisYear;

        if (!isComplete(windowStart, firstDataMonth, currentMonth, gaps)) return Optional.empty();

        YearMonth windowEnd = windowStart;
        YearMonth next = windowEnd.plusMonths(1);
        while (isComplete(next, firstDataMonth, currentMonth, gaps)) {
            windowEnd = next;
            next = next.plusMonths(1);
        }
        return Optional.of(new ThisYearWindow(windowStart, windowEnd));
    }

    /** Whether calendar year {@code year} has full, complete coverage for the SAME relative
     *  month-of-year range as {@code window} (e.g. window = June-July means checking that year's
     *  own June-July) -- spec §4.2's rule for which prior years may join the "This Year So Far"
     *  comparison. */
    public static boolean coversSameRelativeWindow(int year, ThisYearWindow window, YearMonth firstDataMonth,
                                                     YearMonth currentMonth, List<AccountCoverageService.DateRange> gaps) {
        YearMonth start = YearMonth.of(year, window.windowStart().getMonthValue());
        YearMonth end = YearMonth.of(year, window.windowEnd().getMonthValue());
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            if (!isComplete(m, firstDataMonth, currentMonth, gaps)) return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MultiYearCoverageTest -pl . -am -q`
Expected: PASS (all 12 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/MultiYearCoverage.java \
        backend/src/test/java/com/finora/service/MultiYearCoverageTest.java
git commit -m "feat(analytics): add MultiYearCoverage completeness/window calculator"
```

---

## Task 3: `AnalyticsDto` records + `multiYearIncome` / `multiYearSpend`

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/AnalyticsDto.java`
- Modify: `backend/src/main/java/com/finora/service/AnalyticsService.java`
- Modify: `backend/src/test/java/com/finora/service/AnalyticsServiceTest.java` (constructor call
  gains a new arg — see Step 1)

**Interfaces:**
- Consumes: `MultiYearCoverage.*` (Task 2), `AccountCoverageService.gapsForUser` (Task 1),
  `TransactionRepository.findEarliestTxnDate` (existing), `UserZone.forUser` (existing).
- Produces: `AnalyticsDto.MultiYearPoint(int year, int coverageMonths, boolean isComplete, BigDecimal total)`,
  `AnalyticsDto.ThisYearSoFarPoint(int year, BigDecimal total)`,
  `AnalyticsDto.ThisYearSoFar(String windowEndMonth, List<ThisYearSoFarPoint> years)`,
  `AnalyticsDto.MultiYearReport(List<MultiYearPoint> fullYears, ThisYearSoFar thisYearSoFar)`,
  `AnalyticsService.multiYearIncome(UUID userId) -> AnalyticsDto.MultiYearReport`,
  `AnalyticsService.multiYearSpend(UUID userId) -> AnalyticsDto.MultiYearReport`.
  These `sumYear`/`sumWindow`/`buildThisYearSoFar` private helpers are reused by Tasks 4 and 5 —
  keep their exact names and signatures.

- [ ] **Step 1: Write the failing test**

`AnalyticsServiceTest.setUp()` constructs `AnalyticsService` with a fixed positional arg list — add
a mocked `AccountCoverageService` as the last constructor argument (Step 3 changes the constructor
to take it):

```java
    private AccountCoverageService accountCoverageService;
```

In `setUp()`, after the existing `transactionGraphService` mock setup and before the
`analyticsService = new AnalyticsService(...)` line:

```java
        accountCoverageService = mock(AccountCoverageService.class);
        when(accountCoverageService.gapsForUser(any())).thenReturn(List.of());
```

Change the constructor call's last line to:

```java
        analyticsService = new AnalyticsService(transactionRepository, accountRepository, merchantRepository,
                learningRepository, learningAuditRepository, categoryRepository, statementImportRepository,
                new ConfidenceEngine(), userRepository, transactionGraphService, accountCoverageService);
```

Then add the new tests (the file already has an `expense(...)` helper — add an `income(...)`
twin):

```java
    private Transaction income(LocalDate date, BigDecimal amount) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(liveAccount.getId());
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.INCOME);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    @Test
    @DisplayName("multiYearIncome: a full prior year sums correctly and is marked complete")
    void multiYearIncome_sumsAFullYear_andMarksItComplete() {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any()))
                .thenReturn(LocalDate.of(2025, 1, 5));
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), any()))
                .thenReturn(List.of(income(LocalDate.of(2025, 3, 1), new BigDecimal("1000")),
                                     income(LocalDate.of(2025, 9, 1), new BigDecimal("500"))));

        // "Now" is fixed by AnalyticsServiceTest's own userRepository mock returning
        // Optional.empty(), which UserZone resolves to its documented default zone; the test only
        // needs a stable currentMonth, established via the earliest-date mock above being well in
        // the past relative to whenever this suite runs -- so assert on 2025 specifically, not on
        // "the current year".
        AnalyticsDto.MultiYearReport report = analyticsService.multiYearIncome(userId);

        AnalyticsDto.MultiYearPoint year2025 = report.fullYears().stream()
                .filter(p -> p.year() == 2025).findFirst().orElseThrow();
        assertThat(year2025.total()).isEqualByComparingTo("1500");
        assertThat(year2025.isComplete()).isTrue();
        assertThat(year2025.coverageMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("multiYearIncome: no transactions ever -> empty report, not an error")
    void multiYearIncome_returnsEmptyReport_whenThereIsNoDataAtAll() {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any())).thenReturn(null);

        AnalyticsDto.MultiYearReport report = analyticsService.multiYearIncome(userId);

        assertThat(report.fullYears()).isEmpty();
        assertThat(report.thisYearSoFar().years()).isEmpty();
        assertThat(report.thisYearSoFar().windowEndMonth()).isNull();
    }

    @Test
    @DisplayName("multiYearSpend: refund nets the expense the same way merchantTrend already does")
    void multiYearSpend_netsARefundAgainstItsPurchase() {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any()))
                .thenReturn(LocalDate.of(2025, 1, 5));
        Transaction purchase = expense(null, LocalDate.of(2025, 4, 1), new BigDecimal("500"));
        Transaction refund = new Transaction();
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        refund.setUserId(userId);
        refund.setAccountId(liveAccount.getId());
        refund.setTxnDate(LocalDate.of(2025, 4, 10));
        refund.setAmount(new BigDecimal("200"));
        refund.setTxnType(Transaction.Type.INCOME);
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        refund.setRefundOfTransactionId(purchase.getId());

        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), any()))
                .thenReturn(List.of(purchase, refund));
        when(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                eq(userId), any(), any()))
                .thenReturn(List.of(refund));

        AnalyticsDto.MultiYearReport report = analyticsService.multiYearSpend(userId);

        AnalyticsDto.MultiYearPoint year2025 = report.fullYears().stream()
                .filter(p -> p.year() == 2025).findFirst().orElseThrow();
        assertThat(year2025.total()).isEqualByComparingTo("300");
    }
```

Note: the existing `expense(...)` helper does not set `accountId` on its fixtures, and that's fine
to leave as-is — Mockito's `when(...).thenReturn(...)` stub returns the canned list whenever the
method is called with matching argument matchers (`eq(userId)`, `any()` for the date/account-id
params here), regardless of what fields the returned `Transaction` objects carry. The mock is not
a real filtering query, so there is nothing to fix here.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: FAIL — `multiYearIncome`/`multiYearSpend` don't exist, and the constructor call doesn't
match yet (compile errors).

- [ ] **Step 3: Write minimal implementation**

In `AnalyticsDto.java`, add inside the `AnalyticsDto` class (alongside the existing records):

```java
    /** Multi-Year Comparison (issue #1455). coverageMonths/isComplete come from
     *  {@link com.finora.service.MultiYearCoverage.YearCoverage}. YoY deltas are a frontend/mobile
     *  concern computed from consecutive fullYears entries -- this DTO only ever carries raw,
     *  honest totals and completeness facts, never a derived comparison number. */
    public record MultiYearPoint(int year, int coverageMonths, boolean isComplete, BigDecimal total) {}

    /** One prior year's total over the SAME relative month-of-year range as
     *  {@code ThisYearSoFar.windowEndMonth} -- only years that fully cover that range appear here
     *  (see MultiYearCoverage.coversSameRelativeWindow). */
    public record ThisYearSoFarPoint(int year, BigDecimal total) {}

    /** windowEndMonth is "YYYY-MM" (the last complete month of the comparable window), or null
     *  when no month is complete yet this year -- years is empty in that case too, never a guess. */
    public record ThisYearSoFar(String windowEndMonth, List<ThisYearSoFarPoint> years) {}

    public record MultiYearReport(List<MultiYearPoint> fullYears, ThisYearSoFar thisYearSoFar) {}
```

In `AnalyticsService.java`:

1. Add the constructor parameter and field:

```java
    private final AccountCoverageService accountCoverageService;
```

Add it as the last parameter of the constructor, and assign it in the constructor body (mirror the
existing assignment style for `transactionGraphService`).

2. Add the new public methods and private helpers (place them after `learningGrowth`, before the
   private helper section):

```java
    /** Total refund-netted INCOME per calendar year (spec §2.1). */
    public AnalyticsDto.MultiYearReport multiYearIncome(UUID userId) {
        return multiYearScalarReport(userId, this::activeIncomeTransactions);
    }

    /** Total refund-netted EXPENSE per calendar year (spec §2.2). */
    public AnalyticsDto.MultiYearReport multiYearSpend(UUID userId) {
        return multiYearScalarReport(userId, this::activeExpenseTransactions);
    }

    private interface RangeFetcher {
        List<Transaction> fetch(UUID userId, LocalDate from, LocalDate to);
    }

    private AnalyticsDto.MultiYearReport multiYearScalarReport(UUID userId, RangeFetcher fetcher) {
        List<UUID> liveAccountIds = liveAccountIds(userId);
        LocalDate earliest = liveAccountIds.isEmpty() ? null
                : transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        if (earliest == null) {
            return new AnalyticsDto.MultiYearReport(List.of(), new AnalyticsDto.ThisYearSoFar(null, List.of()));
        }
        YearMonth firstDataMonth = YearMonth.from(earliest);
        YearMonth currentMonth = YearMonth.now(UserZone.forUser(userRepository, userId));
        List<AccountCoverageService.DateRange> gaps = accountCoverageService.gapsForUser(userId);

        RefundNetting refunds = refundsFor(userId);
        Map<YearMonth, BigDecimal> byMonth = sumByMonth(
                fetcher.fetch(userId, firstDataMonth.atDay(1), currentMonth.atEndOfMonth()), refunds);

        List<AnalyticsDto.MultiYearPoint> fullYears = MultiYearCoverage.yearCoverages(firstDataMonth, currentMonth, gaps)
                .stream()
                .map(c -> new AnalyticsDto.MultiYearPoint(c.year(), c.coverageMonths(), c.isComplete(),
                        sumYear(byMonth, c.year())))
                .toList();

        AnalyticsDto.ThisYearSoFar thisYearSoFar = buildThisYearSoFar(firstDataMonth, currentMonth, gaps, byMonth);
        return new AnalyticsDto.MultiYearReport(fullYears, thisYearSoFar);
    }

    private AnalyticsDto.ThisYearSoFar buildThisYearSoFar(YearMonth firstDataMonth, YearMonth currentMonth,
            List<AccountCoverageService.DateRange> gaps, Map<YearMonth, BigDecimal> byMonth) {
        var windowOpt = MultiYearCoverage.thisYearWindow(firstDataMonth, currentMonth, gaps);
        if (windowOpt.isEmpty()) return new AnalyticsDto.ThisYearSoFar(null, List.of());
        MultiYearCoverage.ThisYearWindow window = windowOpt.get();

        List<AnalyticsDto.ThisYearSoFarPoint> years = new ArrayList<>();
        for (int year = firstDataMonth.getYear(); year <= currentMonth.getYear(); year++) {
            if (!MultiYearCoverage.coversSameRelativeWindow(year, window, firstDataMonth, currentMonth, gaps)) continue;
            years.add(new AnalyticsDto.ThisYearSoFarPoint(year, sumWindow(byMonth, year, window)));
        }
        return new AnalyticsDto.ThisYearSoFar(window.windowEnd().toString(), years);
    }

    private Map<YearMonth, BigDecimal> sumByMonth(List<Transaction> txns, RefundNetting refunds) {
        Map<YearMonth, BigDecimal> byMonth = new HashMap<>();
        for (Transaction t : txns) {
            YearMonth m = YearMonth.from(t.getTxnDate());
            byMonth.merge(m, refunds.reportableAmount(t), BigDecimal::add);
        }
        return byMonth;
    }

    private BigDecimal sumYear(Map<YearMonth, BigDecimal> byMonth, int year) {
        BigDecimal total = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            total = total.add(byMonth.getOrDefault(YearMonth.of(year, m), BigDecimal.ZERO));
        }
        return total;
    }

    private BigDecimal sumWindow(Map<YearMonth, BigDecimal> byMonth, int year, MultiYearCoverage.ThisYearWindow window) {
        BigDecimal total = BigDecimal.ZERO;
        for (int m = window.windowStart().getMonthValue(); m <= window.windowEnd().getMonthValue(); m++) {
            total = total.add(byMonth.getOrDefault(YearMonth.of(year, m), BigDecimal.ZERO));
        }
        return total;
    }

    /** INCOME twin of {@link #activeExpenseTransactions(UUID, LocalDate, LocalDate)} -- same
     *  live-account scoping and RefundNetting.reportable() dedup, filtered to INCOME instead. */
    private List<Transaction> activeIncomeTransactions(UUID userId, LocalDate from, LocalDate to) {
        List<UUID> liveAccountIds = liveAccountIds(userId);
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        return RefundNetting.reportable(rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.INCOME)
                .toList();
    }
```

Note: `activeExpenseTransactions(UUID, LocalDate, LocalDate)` already exists and matches the
`RangeFetcher` shape used above (it takes `(userId, from, to)` and returns `List<Transaction>`) —
no change needed to it, `multiYearScalarReport(userId, this::activeExpenseTransactions)` binds
directly to the existing method reference.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: PASS (all existing tests plus the new ones — confirms the constructor change didn't
break any pre-existing test).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/dto/AnalyticsDto.java \
        backend/src/main/java/com/finora/service/AnalyticsService.java \
        backend/src/test/java/com/finora/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): add multiYearIncome and multiYearSpend"
```

---

## Task 4: `multiYearLifestyleInflation`

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/AnalyticsDto.java`
- Modify: `backend/src/main/java/com/finora/service/AnalyticsService.java`
- Modify: `backend/src/test/java/com/finora/service/AnalyticsServiceTest.java`

**Interfaces:**
- Consumes: `sumYear`, `sumWindow`, `activeIncomeTransactions`, `activeExpenseTransactions`,
  `MultiYearCoverage.*` (all from Task 3).
- Produces: `AnalyticsDto.LifestyleInflationPoint(int year, int coverageMonths, boolean isComplete, BigDecimal income, BigDecimal expense, BigDecimal ratio)`,
  `AnalyticsDto.ThisYearSoFarLifestylePoint(int year, BigDecimal income, BigDecimal expense, BigDecimal ratio)`,
  `AnalyticsDto.ThisYearSoFarLifestyle(String windowEndMonth, List<ThisYearSoFarLifestylePoint> years)`,
  `AnalyticsDto.MultiYearLifestyleReport(List<LifestyleInflationPoint> fullYears, ThisYearSoFarLifestyle thisYearSoFar)`,
  `AnalyticsService.multiYearLifestyleInflation(UUID userId) -> AnalyticsDto.MultiYearLifestyleReport`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    @DisplayName("multiYearLifestyleInflation: computes expense/income ratio per full year")
    void multiYearLifestyleInflation_computesTheRatio() {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any()))
                .thenReturn(LocalDate.of(2025, 1, 5));
        Transaction spend = expense(null, LocalDate.of(2025, 3, 1), new BigDecimal("800"));
        Transaction earn = income(LocalDate.of(2025, 3, 1), new BigDecimal("1000"));
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), any()))
                .thenReturn(List.of(spend, earn));

        AnalyticsDto.MultiYearLifestyleReport report = analyticsService.multiYearLifestyleInflation(userId);

        AnalyticsDto.LifestyleInflationPoint year2025 = report.fullYears().stream()
                .filter(p -> p.year() == 2025).findFirst().orElseThrow();
        assertThat(year2025.income()).isEqualByComparingTo("1000");
        assertThat(year2025.expense()).isEqualByComparingTo("800");
        assertThat(year2025.ratio()).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("multiYearLifestyleInflation: ratio is null, not a guess, when income is zero")
    void multiYearLifestyleInflation_leavesRatioNull_whenIncomeIsZero() {
        when(transactionRepository.findEarliestTxnDate(eq(userId), any()))
                .thenReturn(LocalDate.of(2025, 1, 5));
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), any()))
                .thenReturn(List.of(expense(null, LocalDate.of(2025, 3, 1), new BigDecimal("800"))));

        AnalyticsDto.MultiYearLifestyleReport report = analyticsService.multiYearLifestyleInflation(userId);

        AnalyticsDto.LifestyleInflationPoint year2025 = report.fullYears().stream()
                .filter(p -> p.year() == 2025).findFirst().orElseThrow();
        assertThat(year2025.ratio()).isNull();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: FAIL — `multiYearLifestyleInflation` / the new DTO types don't exist.

- [ ] **Step 3: Write minimal implementation**

In `AnalyticsDto.java`:

```java
    public record LifestyleInflationPoint(int year, int coverageMonths, boolean isComplete,
                                           BigDecimal income, BigDecimal expense, BigDecimal ratio) {}

    public record ThisYearSoFarLifestylePoint(int year, BigDecimal income, BigDecimal expense, BigDecimal ratio) {}

    public record ThisYearSoFarLifestyle(String windowEndMonth, List<ThisYearSoFarLifestylePoint> years) {}

    public record MultiYearLifestyleReport(List<LifestyleInflationPoint> fullYears, ThisYearSoFarLifestyle thisYearSoFar) {}
```

In `AnalyticsService.java`:

```java
    /** (total EXPENSE / total INCOME) per calendar year (spec §2.4) -- a rising ratio means spend
     *  is eating a growing share of income, independent of whether income itself moved. Ratio is
     *  null, never a guessed number, when income is zero for that year/window (division by zero
     *  is a real "undefined," not a display bug to paper over). */
    public AnalyticsDto.MultiYearLifestyleReport multiYearLifestyleInflation(UUID userId) {
        List<UUID> liveAccountIds = liveAccountIds(userId);
        LocalDate earliest = liveAccountIds.isEmpty() ? null
                : transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        if (earliest == null) {
            return new AnalyticsDto.MultiYearLifestyleReport(List.of(),
                    new AnalyticsDto.ThisYearSoFarLifestyle(null, List.of()));
        }
        YearMonth firstDataMonth = YearMonth.from(earliest);
        YearMonth currentMonth = YearMonth.now(UserZone.forUser(userRepository, userId));
        List<AccountCoverageService.DateRange> gaps = accountCoverageService.gapsForUser(userId);

        RefundNetting refunds = refundsFor(userId);
        LocalDate from = firstDataMonth.atDay(1);
        LocalDate to = currentMonth.atEndOfMonth();
        Map<YearMonth, BigDecimal> expenseByMonth = sumByMonth(activeExpenseTransactions(userId, from, to), refunds);
        Map<YearMonth, BigDecimal> incomeByMonth = sumByMonth(activeIncomeTransactions(userId, from, to), refunds);

        List<AnalyticsDto.LifestyleInflationPoint> fullYears = MultiYearCoverage.yearCoverages(firstDataMonth, currentMonth, gaps)
                .stream()
                .map(c -> {
                    BigDecimal income = sumYear(incomeByMonth, c.year());
                    BigDecimal expense = sumYear(expenseByMonth, c.year());
                    return new AnalyticsDto.LifestyleInflationPoint(c.year(), c.coverageMonths(), c.isComplete(),
                            income, expense, ratio(expense, income));
                })
                .toList();

        var windowOpt = MultiYearCoverage.thisYearWindow(firstDataMonth, currentMonth, gaps);
        AnalyticsDto.ThisYearSoFarLifestyle thisYearSoFar;
        if (windowOpt.isEmpty()) {
            thisYearSoFar = new AnalyticsDto.ThisYearSoFarLifestyle(null, List.of());
        } else {
            MultiYearCoverage.ThisYearWindow window = windowOpt.get();
            List<AnalyticsDto.ThisYearSoFarLifestylePoint> years = new ArrayList<>();
            for (int year = firstDataMonth.getYear(); year <= currentMonth.getYear(); year++) {
                if (!MultiYearCoverage.coversSameRelativeWindow(year, window, firstDataMonth, currentMonth, gaps)) continue;
                BigDecimal income = sumWindow(incomeByMonth, year, window);
                BigDecimal expense = sumWindow(expenseByMonth, year, window);
                years.add(new AnalyticsDto.ThisYearSoFarLifestylePoint(year, income, expense, ratio(expense, income)));
            }
            thisYearSoFar = new AnalyticsDto.ThisYearSoFarLifestyle(window.windowEnd().toString(), years);
        }

        return new AnalyticsDto.MultiYearLifestyleReport(fullYears, thisYearSoFar);
    }

    private BigDecimal ratio(BigDecimal expense, BigDecimal income) {
        if (income.signum() == 0) return null;
        return expense.divide(income, 4, java.math.RoundingMode.HALF_UP);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/dto/AnalyticsDto.java \
        backend/src/main/java/com/finora/service/AnalyticsService.java \
        backend/src/test/java/com/finora/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): add multiYearLifestyleInflation"
```

---

## Task 5: `multiYearCategories`

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/AnalyticsDto.java`
- Modify: `backend/src/main/java/com/finora/service/AnalyticsService.java`
- Modify: `backend/src/test/java/com/finora/service/AnalyticsServiceTest.java`

**Interfaces:**
- Produces: `AnalyticsDto.CategoryYearBreakdown(UUID categoryId, String categoryName, BigDecimal totalSpend)`,
  `AnalyticsDto.MultiYearCategoryPoint(int year, int coverageMonths, boolean isComplete, List<CategoryYearBreakdown> categories)`,
  `AnalyticsDto.ThisYearSoFarCategoryPoint(int year, List<CategoryYearBreakdown> categories)`,
  `AnalyticsDto.ThisYearSoFarCategories(String windowEndMonth, List<ThisYearSoFarCategoryPoint> years)`,
  `AnalyticsDto.MultiYearCategoryReport(List<MultiYearCategoryPoint> fullYears, ThisYearSoFarCategories thisYearSoFar)`,
  `AnalyticsService.multiYearCategories(UUID userId) -> AnalyticsDto.MultiYearCategoryReport`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    @DisplayName("multiYearCategories: sums per category per year, same grouping topCategories uses")
    void multiYearCategories_groupsSpendByCategoryPerYear() {
        UUID foodCategoryId = UUID.randomUUID();
        Category food = new Category();
        ReflectionTestUtils.setField(food, "id", foodCategoryId);
        food.setName("Food");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(food));

        when(transactionRepository.findEarliestTxnDate(eq(userId), any()))
                .thenReturn(LocalDate.of(2025, 1, 5));
        Transaction t1 = expense(null, LocalDate.of(2025, 3, 1), new BigDecimal("300"));
        t1.setCategoryId(foodCategoryId);
        Transaction t2 = expense(null, LocalDate.of(2025, 9, 1), new BigDecimal("200"));
        t2.setCategoryId(foodCategoryId);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(
                eq(userId), any(), any(), any()))
                .thenReturn(List.of(t1, t2));

        AnalyticsDto.MultiYearCategoryReport report = analyticsService.multiYearCategories(userId);

        AnalyticsDto.MultiYearCategoryPoint year2025 = report.fullYears().stream()
                .filter(p -> p.year() == 2025).findFirst().orElseThrow();
        AnalyticsDto.CategoryYearBreakdown foodBreakdown = year2025.categories().stream()
                .filter(c -> c.categoryId().equals(foodCategoryId)).findFirst().orElseThrow();
        assertThat(foodBreakdown.totalSpend()).isEqualByComparingTo("500");
        assertThat(foodBreakdown.categoryName()).isEqualTo("Food");
    }
```

`ReflectionTestUtils.setField(food, "id", foodCategoryId)` and `t1.setCategoryId(...)` are both the
real, existing patterns this file already uses — `topCategories_ranksByTotalSpendDescending...`
(same file) builds its `Category` fixtures and calls `.setCategoryId(...)` on its `Transaction`
fixtures exactly this way.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: FAIL — `multiYearCategories` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

In `AnalyticsDto.java`:

```java
    /** Category evolution's per-year breakdown -- same category/spend pairing as TopCategory,
     *  without transactionCount (not part of this view). */
    public record CategoryYearBreakdown(UUID categoryId, String categoryName, BigDecimal totalSpend) {}

    public record MultiYearCategoryPoint(int year, int coverageMonths, boolean isComplete,
                                          List<CategoryYearBreakdown> categories) {}

    public record ThisYearSoFarCategoryPoint(int year, List<CategoryYearBreakdown> categories) {}

    public record ThisYearSoFarCategories(String windowEndMonth, List<ThisYearSoFarCategoryPoint> years) {}

    public record MultiYearCategoryReport(List<MultiYearCategoryPoint> fullYears, ThisYearSoFarCategories thisYearSoFar) {}
```

In `AnalyticsService.java`:

```java
    /** Per-category EXPENSE spend per calendar year (spec §2.3) -- same category grouping
     *  {@link #topCategories} already does for one month, bucketed by year (and by the "This Year
     *  So Far" window) instead. */
    public AnalyticsDto.MultiYearCategoryReport multiYearCategories(UUID userId) {
        List<UUID> liveAccountIds = liveAccountIds(userId);
        LocalDate earliest = liveAccountIds.isEmpty() ? null
                : transactionRepository.findEarliestTxnDate(userId, liveAccountIds);
        if (earliest == null) {
            return new AnalyticsDto.MultiYearCategoryReport(List.of(), new AnalyticsDto.ThisYearSoFarCategories(null, List.of()));
        }
        YearMonth firstDataMonth = YearMonth.from(earliest);
        YearMonth currentMonth = YearMonth.now(UserZone.forUser(userRepository, userId));
        List<AccountCoverageService.DateRange> gaps = accountCoverageService.gapsForUser(userId);

        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        RefundNetting refunds = refundsFor(userId);
        List<Transaction> txns = activeExpenseTransactions(userId, firstDataMonth.atDay(1), currentMonth.atEndOfMonth());

        // month -> categoryId -> total.
        Map<YearMonth, Map<UUID, BigDecimal>> byMonthAndCategory = new HashMap<>();
        for (Transaction t : txns) {
            if (t.getCategoryId() == null) continue;
            YearMonth m = YearMonth.from(t.getTxnDate());
            byMonthAndCategory.computeIfAbsent(m, k -> new HashMap<>())
                    .merge(t.getCategoryId(), refunds.reportableAmount(t), BigDecimal::add);
        }

        List<AnalyticsDto.MultiYearCategoryPoint> fullYears = MultiYearCoverage.yearCoverages(firstDataMonth, currentMonth, gaps)
                .stream()
                .map(c -> new AnalyticsDto.MultiYearCategoryPoint(c.year(), c.coverageMonths(), c.isComplete(),
                        categoryBreakdownForYear(byMonthAndCategory, c.year(), categoryNames)))
                .toList();

        var windowOpt = MultiYearCoverage.thisYearWindow(firstDataMonth, currentMonth, gaps);
        AnalyticsDto.ThisYearSoFarCategories thisYearSoFar;
        if (windowOpt.isEmpty()) {
            thisYearSoFar = new AnalyticsDto.ThisYearSoFarCategories(null, List.of());
        } else {
            MultiYearCoverage.ThisYearWindow window = windowOpt.get();
            List<AnalyticsDto.ThisYearSoFarCategoryPoint> years = new ArrayList<>();
            for (int year = firstDataMonth.getYear(); year <= currentMonth.getYear(); year++) {
                if (!MultiYearCoverage.coversSameRelativeWindow(year, window, firstDataMonth, currentMonth, gaps)) continue;
                years.add(new AnalyticsDto.ThisYearSoFarCategoryPoint(year,
                        categoryBreakdownForWindow(byMonthAndCategory, year, window, categoryNames)));
            }
            thisYearSoFar = new AnalyticsDto.ThisYearSoFarCategories(window.windowEnd().toString(), years);
        }

        return new AnalyticsDto.MultiYearCategoryReport(fullYears, thisYearSoFar);
    }

    private List<AnalyticsDto.CategoryYearBreakdown> categoryBreakdownForYear(
            Map<YearMonth, Map<UUID, BigDecimal>> byMonthAndCategory, int year, Map<UUID, String> categoryNames) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (int m = 1; m <= 12; m++) {
            Map<UUID, BigDecimal> monthTotals = byMonthAndCategory.get(YearMonth.of(year, m));
            if (monthTotals == null) continue;
            monthTotals.forEach((categoryId, amount) -> totals.merge(categoryId, amount, BigDecimal::add));
        }
        return toBreakdownList(totals, categoryNames);
    }

    private List<AnalyticsDto.CategoryYearBreakdown> categoryBreakdownForWindow(
            Map<YearMonth, Map<UUID, BigDecimal>> byMonthAndCategory, int year,
            MultiYearCoverage.ThisYearWindow window, Map<UUID, String> categoryNames) {
        Map<UUID, BigDecimal> totals = new HashMap<>();
        for (int m = window.windowStart().getMonthValue(); m <= window.windowEnd().getMonthValue(); m++) {
            Map<UUID, BigDecimal> monthTotals = byMonthAndCategory.get(YearMonth.of(year, m));
            if (monthTotals == null) continue;
            monthTotals.forEach((categoryId, amount) -> totals.merge(categoryId, amount, BigDecimal::add));
        }
        return toBreakdownList(totals, categoryNames);
    }

    private List<AnalyticsDto.CategoryYearBreakdown> toBreakdownList(Map<UUID, BigDecimal> totals,
                                                                       Map<UUID, String> categoryNames) {
        return totals.entrySet().stream()
                .map(e -> new AnalyticsDto.CategoryYearBreakdown(e.getKey(),
                        categoryNames.getOrDefault(e.getKey(), "Uncategorized"), e.getValue()))
                .sorted(Comparator.comparing(AnalyticsDto.CategoryYearBreakdown::totalSpend).reversed())
                .toList();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsServiceTest -pl . -am -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/dto/AnalyticsDto.java \
        backend/src/main/java/com/finora/service/AnalyticsService.java \
        backend/src/test/java/com/finora/service/AnalyticsServiceTest.java
git commit -m "feat(analytics): add multiYearCategories"
```

---

## Task 6: `AnalyticsController` endpoints + entitlement IT coverage

**Files:**
- Modify: `backend/src/main/java/com/finora/controller/AnalyticsController.java`
- Modify: `backend/src/test/java/com/finora/controller/AnalyticsControllerIT.java`

**Interfaces:**
- Consumes: `AnalyticsService.multiYearIncome/multiYearSpend/multiYearCategories/multiYearLifestyleInflation` (Tasks 3-5).
- Produces:
  ```
  GET /api/v1/analytics/multi-year/income
  GET /api/v1/analytics/multi-year/spend
  GET /api/v1/analytics/multi-year/categories
  GET /api/v1/analytics/multi-year/lifestyle-inflation
  ```
  all gated the same way as the existing five (`requireAdvancedReports()`).

- [ ] **Step 1: Write the failing test**

In `AnalyticsControllerIT.java`, add the four new paths to the existing gate-proof list:

```java
    private static final List<String> ADVANCED_REPORTS_PATHS = List.of(
            "/api/v1/analytics/top-merchants", "/api/v1/analytics/trend",
            "/api/v1/analytics/category-confidence", "/api/v1/analytics/top-categories",
            "/api/v1/analytics/learning-growth",
            "/api/v1/analytics/multi-year/income", "/api/v1/analytics/multi-year/spend",
            "/api/v1/analytics/multi-year/categories", "/api/v1/analytics/multi-year/lifestyle-inflation");
```

That list already drives this file's three existing loop-based tests —
`aFreeUser_isDeniedEveryAdvancedReportsViewWithTheEntitlementErrorCode` and
`aPlusUser_seesEveryAdvancedReportsView` both `for (String path : ADVANCED_REPORTS_PATHS)`, so the
four new paths are automatically proven fail-closed-for-Free and open-for-Plus with zero additional
test code once they're in the list. Add one targeted test on top, confirming a real payload shape
(not just a 200), using this file's own real helpers (`createUser()`, `subscriptionService.
provisionFreeSubscription(userId)` + `subscriptionService.changePlan(userId, planCode, reason,
actorId)`, `get(path, user)` which wraps `bearerFor(user)`):

```java
    @Test
    void multiYearIncome_returnsAnEmptyReport_forAUserWithNoTransactions() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PREMIUM", "test-upgrade", user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/multi-year/income", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody()).get("data");
        assertThat(body.get("fullYears")).isEmpty();
        assertThat(body.get("thisYearSoFar").get("years")).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsControllerIT -pl . -am -q`
Expected: FAIL — 404s on the four new paths (routes don't exist yet).

- [ ] **Step 3: Write minimal implementation**

In `AnalyticsController.java`, add after the existing `learningGrowth` endpoint:

```java
    @GetMapping("/multi-year/income")
    public ApiResponse<AnalyticsDto.MultiYearReport> multiYearIncome() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.multiYearIncome(currentUser.id()));
    }

    @GetMapping("/multi-year/spend")
    public ApiResponse<AnalyticsDto.MultiYearReport> multiYearSpend() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.multiYearSpend(currentUser.id()));
    }

    @GetMapping("/multi-year/categories")
    public ApiResponse<AnalyticsDto.MultiYearCategoryReport> multiYearCategories() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.multiYearCategories(currentUser.id()));
    }

    @GetMapping("/multi-year/lifestyle-inflation")
    public ApiResponse<AnalyticsDto.MultiYearLifestyleReport> multiYearLifestyleInflation() {
        requireAdvancedReports();
        return ApiResponse.ok(analyticsService.multiYearLifestyleInflation(currentUser.id()));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AnalyticsControllerIT -pl . -am -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/controller/AnalyticsController.java \
        backend/src/test/java/com/finora/controller/AnalyticsControllerIT.java
git commit -m "feat(analytics): expose Multi-Year Comparison endpoints behind ADVANCED_REPORTS"
```

---

## Task 7: Web — `analyticsApi` client + `AdvancedReports.tsx` section

**Files:**
- Modify: `frontend/src/api/endpoints.ts`
- Modify: `frontend/src/pages/AdvancedReports.tsx`
- Modify: `frontend/src/pages/AdvancedReports.test.tsx`

**Interfaces:**
- Consumes: the four new backend endpoints (Task 6).
- Produces: `analyticsApi.multiYearIncome/multiYearSpend/multiYearCategories/multiYearLifestyleInflation()`.

- [ ] **Step 1: Write the failing test**

`AdvancedReports.test.tsx` mocks each `analyticsApi` method individually inside its own
`vi.mock('../api/endpoints', () => ({ ... analyticsApi: { topMerchants: vi.fn(), ... } }))`, then
each test calls `vi.mocked(analyticsApi.someMethod).mockResolvedValue(...)`. Add the four new
methods to that mock object:

```tsx
  analyticsApi: {
    topMerchants: vi.fn(),
    topCategories: vi.fn(),
    trend: vi.fn(),
    categoryConfidence: vi.fn(),
    learningGrowth: vi.fn(),
    multiYearIncome: vi.fn(),
    multiYearSpend: vi.fn(),
    multiYearCategories: vi.fn(),
    multiYearLifestyleInflation: vi.fn(),
  },
```

Then add the test itself, reusing the file's own `entitlements(...)` helper and `renderPage()`:

```tsx
it('shows the Multi-Year Comparison section with a real, visible coverage badge for a partial year', async () => {
  vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
  vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
  vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
  vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
  vi.mocked(analyticsApi.trend).mockResolvedValue([]);
  vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
  vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);
  vi.mocked(analyticsApi.multiYearIncome).mockResolvedValue({
    fullYears: [
      { year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 },
      { year: 2026, coverageMonths: 3, isComplete: false, total: 320000 },
    ],
    thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 180000 }, { year: 2025, total: 160000 }] },
  });
  vi.mocked(analyticsApi.multiYearSpend).mockResolvedValue({
    fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, total: 900000 }],
    thisYearSoFar: { windowEndMonth: '2026-02', years: [] },
  });
  vi.mocked(analyticsApi.multiYearLifestyleInflation).mockResolvedValue({
    fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, income: 1200000, expense: 900000, ratio: 0.75 }],
    thisYearSoFar: { windowEndMonth: null, years: [] },
  });
  vi.mocked(analyticsApi.multiYearCategories).mockResolvedValue({
    fullYears: [{ year: 2025, coverageMonths: 12, isComplete: true, categories: [] }],
    thisYearSoFar: { windowEndMonth: null, years: [] },
  });

  renderPage();

  expect(await screen.findByText('Multi-Year Comparison')).toBeInTheDocument();
  // The <li> renders "2026: 3/12 months" as one combined text node (year, then the literal ": ",
  // then the conditional coverage string are adjacent JSX children with no intervening element) --
  // a regex partial match, not an exact string, is what actually matches that combined content.
  expect(await screen.findByText(/3\/12 months/)).toBeInTheDocument();
});
```

(Match the actual test file's existing render helper name — likely `renderPage()` or similar; copy
it rather than inventing a new one.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/AdvancedReports.test.tsx`
Expected: FAIL — `analyticsApi.multiYearIncome` is not a mocked function yet / the section text
isn't rendered.

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/api/endpoints.ts`, add alongside the existing `analyticsApi` interfaces/object:

```ts
export interface MultiYearPoint { year: number; coverageMonths: number; isComplete: boolean; total: number; }
export interface ThisYearSoFarPoint { year: number; total: number; }
export interface ThisYearSoFar { windowEndMonth: string | null; years: ThisYearSoFarPoint[]; }
export interface MultiYearReport { fullYears: MultiYearPoint[]; thisYearSoFar: ThisYearSoFar; }

export interface LifestyleInflationPoint {
  year: number; coverageMonths: number; isComplete: boolean;
  income: number; expense: number; ratio: number | null;
}
export interface ThisYearSoFarLifestylePoint { year: number; income: number; expense: number; ratio: number | null; }
export interface ThisYearSoFarLifestyle { windowEndMonth: string | null; years: ThisYearSoFarLifestylePoint[]; }
export interface MultiYearLifestyleReport { fullYears: LifestyleInflationPoint[]; thisYearSoFar: ThisYearSoFarLifestyle; }

export interface CategoryYearBreakdown { categoryId: string; categoryName: string; totalSpend: number; }
export interface MultiYearCategoryPoint { year: number; coverageMonths: number; isComplete: boolean; categories: CategoryYearBreakdown[]; }
export interface ThisYearSoFarCategoryPoint { year: number; categories: CategoryYearBreakdown[]; }
export interface ThisYearSoFarCategories { windowEndMonth: string | null; years: ThisYearSoFarCategoryPoint[]; }
export interface MultiYearCategoryReport { fullYears: MultiYearCategoryPoint[]; thisYearSoFar: ThisYearSoFarCategories; }
```

Then add these four methods inside the existing `analyticsApi` object (after `learningGrowth`):

```ts
  multiYearIncome: () => api.get<MultiYearReport>('/analytics/multi-year/income').then((r) => r.data),
  multiYearSpend: () => api.get<MultiYearReport>('/analytics/multi-year/spend').then((r) => r.data),
  multiYearCategories: () => api.get<MultiYearCategoryReport>('/analytics/multi-year/categories').then((r) => r.data),
  multiYearLifestyleInflation: () =>
    api.get<MultiYearLifestyleReport>('/analytics/multi-year/lifestyle-inflation').then((r) => r.data),
```

In `AdvancedReports.tsx`, inside `AdvancedReportsContent()`, add the four queries alongside the
existing ones:

```tsx
  const [comparisonMode, setComparisonMode] = useState<'full' | 'ytd'>('full');
  const multiYearIncomeQ = useQuery({ queryKey: ['multi-year-income'], queryFn: () => analyticsApi.multiYearIncome() });
  const multiYearSpendQ = useQuery({ queryKey: ['multi-year-spend'], queryFn: () => analyticsApi.multiYearSpend() });
  const multiYearLifestyleQ = useQuery({ queryKey: ['multi-year-lifestyle'], queryFn: () => analyticsApi.multiYearLifestyleInflation() });
  const multiYearCategoriesQ = useQuery({ queryKey: ['multi-year-categories'], queryFn: () => analyticsApi.multiYearCategories() });
```

Add a new card in the returned JSX, after the "Spend Trend" `FinoraCard` block:

```tsx
      <FinoraCard padding="lg">
        <SectionHeader title="Multi-Year Comparison" />
        <p className="text-xs text-muted -mt-2 mb-4">Income, spend, and how much of your income spend is eating, year over year.</p>
        <div className="flex gap-2 mb-4">
          <button
            className={`text-xs px-3 py-1 rounded ${comparisonMode === 'full' ? 'bg-primary text-white' : 'bg-card border'}`}
            onClick={() => setComparisonMode('full')}
          >
            Full Years
          </button>
          <button
            className={`text-xs px-3 py-1 rounded ${comparisonMode === 'ytd' ? 'bg-primary text-white' : 'bg-card border'}`}
            onClick={() => setComparisonMode('ytd')}
          >
            This Year So Far
          </button>
        </div>
        <ChartContainer
          height={260}
          loading={multiYearIncomeQ.isLoading || multiYearSpendQ.isLoading}
          loadingLabel="Loading multi-year comparison"
          isEmpty={(multiYearIncomeQ.data?.fullYears ?? []).length === 0}
          emptyState={
            <EmptyState icon={TrendingUpIcon} iconBg="bg-primary-light" iconColor="text-primary" title="Not enough history yet" desc="Once you have a full calendar year of data, it appears here." />
          }
        >
          <Bar
            data={{
              labels: comparisonMode === 'full'
                ? (multiYearIncomeQ.data?.fullYears ?? []).map((p) => String(p.year))
                : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).map((p) => String(p.year)),
              datasets: [
                {
                  label: 'Income',
                  data: comparisonMode === 'full'
                    ? (multiYearIncomeQ.data?.fullYears ?? []).map((p) => p.total)
                    : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).map((p) => p.total),
                  backgroundColor: colors.success,
                },
                {
                  label: 'Spend',
                  data: comparisonMode === 'full'
                    ? (multiYearSpendQ.data?.fullYears ?? []).map((p) => p.total)
                    : (multiYearSpendQ.data?.thisYearSoFar.years ?? []).map((p) => p.total),
                  backgroundColor: colors.orange,
                },
              ],
            }}
            options={{ ...baseChartOptions, scales: { y: { ticks: { callback: (v) => fmt(Number(v)) } } } }}
          />
        </ChartContainer>
        {/* Coverage is rendered as real, visible text -- not left inside the chart's own labels
            -- so a partial year's badge is unavoidable, not a hover-only or canvas-only detail
            (per the design review that shaped spec §3: "coverage should be visually unavoidable"). */}
        {comparisonMode === 'full' && (
          <ul className="text-xs text-muted mt-2 space-y-0.5">
            {(multiYearIncomeQ.data?.fullYears ?? []).map((p) => (
              <li key={p.year}>{p.year}: {p.isComplete ? 'full year' : `${p.coverageMonths}/12 months`}</li>
            ))}
          </ul>
        )}
      </FinoraCard>
```

`multiYearLifestyleQ` and `multiYearCategoriesQ` are fetched here for the frontend to have real
data available, but this task keeps their rendering to a minimal placeholder line so the task stays
reviewable on its own — a follow-up polish task can expand them into their own chart/table if
wanted. Add directly below the card above:

```tsx
      {multiYearLifestyleQ.data && multiYearLifestyleQ.data.fullYears.length > 0 && (
        <FinoraCard padding="lg">
          <SectionHeader title="Lifestyle Inflation" />
          <p className="text-xs text-muted -mt-2 mb-4">Spend as a share of income, per year — a rising number means spend is growing faster than income.</p>
          <ul className="text-sm space-y-1">
            {multiYearLifestyleQ.data.fullYears.map((p) => (
              <li key={p.year} className="flex justify-between">
                <span>{p.year}{!p.isComplete && ` (${p.coverageMonths}/12 months)`}</span>
                <span>{p.ratio === null ? '—' : `${(p.ratio * 100).toFixed(0)}%`}</span>
              </li>
            ))}
          </ul>
        </FinoraCard>
      )}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/AdvancedReports.test.tsx`
Expected: PASS.

- [ ] **Step 5: Run the full frontend suite for this file's directory to check for regressions**

Run: `cd frontend && npx vitest run src/pages/AdvancedReports.test.tsx src/api`
Expected: PASS (no other file imports the changed parts of `endpoints.ts` in a way this would
break — the new exports are additive).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/endpoints.ts frontend/src/pages/AdvancedReports.tsx frontend/src/pages/AdvancedReports.test.tsx
git commit -m "feat(web): add Multi-Year Comparison section to Advanced Reports"
```

---

## Task 8: Mobile — `analyticsApi` client + `AdvancedReportsScreen.tsx` section

**Files:**
- Modify: `mobile/src/api/endpoints.ts`
- Modify: `mobile/src/screens/AdvancedReportsScreen.tsx`
- Modify: `mobile/src/screens/AdvancedReportsScreen.test.tsx`

**Interfaces:**
- Consumes: the four new backend endpoints (Task 6), same shapes as Task 7.

- [ ] **Step 1: Write the failing test**

The test file mocks `analyticsApi` with an explicit method list
(`jest.mock('../api/endpoints', () => ({ ... analyticsApi: { topMerchants: jest.fn(), ... } }))`)
and seeds each method's resolved value in a `beforeEach`. Add the four new methods to both:

```ts
  analyticsApi: {
    topMerchants: jest.fn(), topCategories: jest.fn(), trend: jest.fn(),
    categoryConfidence: jest.fn(), learningGrowth: jest.fn(),
    multiYearIncome: jest.fn(), multiYearSpend: jest.fn(),
    multiYearCategories: jest.fn(), multiYearLifestyleInflation: jest.fn(),
  },
```

```ts
beforeEach(() => {
  jest.clearAllMocks();
  reports.availableMonths.mockResolvedValue(['2026-07', '2026-08']);
  analytics.topMerchants.mockResolvedValue([]);
  analytics.topCategories.mockResolvedValue([]);
  analytics.trend.mockResolvedValue([]);
  analytics.categoryConfidence.mockResolvedValue([]);
  analytics.learningGrowth.mockResolvedValue([]);
  analytics.multiYearIncome.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearSpend.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearCategories.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
  analytics.multiYearLifestyleInflation.mockResolvedValue({ fullYears: [], thisYearSoFar: { windowEndMonth: null, years: [] } });
});
```

Then add the test itself:

```ts
  it('shows the Multi-Year Comparison section with a coverage badge for a partial year', async () => {
    entitlements.mine.mockResolvedValue(granted());
    analytics.multiYearIncome.mockResolvedValue({
      fullYears: [
        { year: 2025, coverageMonths: 12, isComplete: true, total: 1200000 },
        { year: 2026, coverageMonths: 3, isComplete: false, total: 320000 },
      ],
      thisYearSoFar: { windowEndMonth: '2026-02', years: [{ year: 2026, total: 180000 }, { year: 2025, total: 160000 }] },
    });
    renderScreen();

    expect(await screen.findByText('Multi-Year Comparison')).toBeTruthy();
    expect(await screen.findByText(/3\/12 months/)).toBeTruthy();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npm test -- src/screens/AdvancedReportsScreen.test.tsx`
Expected: FAIL — `multiYearIncome` etc. aren't real mock functions yet, and the section isn't
rendered.

- [ ] **Step 3: Write minimal implementation**

In `mobile/src/api/endpoints.ts`, add the exact same interfaces and four `analyticsApi` methods as
Task 7 Step 3 (this file already mirrors the web one method-for-method, per its own comment
"Mirrors backend AnalyticsDto exactly" — copy those same interface/method bodies verbatim).

In `AdvancedReportsScreen.tsx`, add the state and queries alongside the existing ones (near
`topMerchantsQ`/`trendQ`):

```tsx
  const [comparisonMode, setComparisonMode] = useState<'full' | 'ytd'>('full');
  const multiYearIncomeQ = useQuery({ queryKey: ['multi-year-income'], queryFn: () => analyticsApi.multiYearIncome() });
  const multiYearSpendQ = useQuery({ queryKey: ['multi-year-spend'], queryFn: () => analyticsApi.multiYearSpend() });
```

Add the two new queries to `refresh()`'s existing `Promise.all([...])` call, alongside the other
five:

```tsx
      await Promise.all([
        monthsQ.refetch(), topMerchantsQ.refetch(), topCategoriesQ.refetch(),
        trendQ.refetch(), confidenceQ.refetch(), learningQ.refetch(),
        multiYearIncomeQ.refetch(), multiYearSpendQ.refetch(),
      ]);
```

Add a new `Card` in the JSX, right after the existing "Spend Trend" `Card` block:

```tsx
      <Card style={styles.section}>
        <SectionHeading title="Multi-Year Comparison" />
        <Text style={[styles.panelHint, { color: c.muted }]}>
          Income and spend, year over year. A partial year shows its real total, not a projection.
        </Text>
        <View style={styles.modeRow}>
          <Pressable
            onPress={() => setComparisonMode('full')}
            accessibilityRole="button"
            style={[styles.modeButton, { backgroundColor: comparisonMode === 'full' ? c.primary : c.card }]}
          >
            <Text style={{ color: comparisonMode === 'full' ? '#fff' : c.ink }}>Full Years</Text>
          </Pressable>
          <Pressable
            onPress={() => setComparisonMode('ytd')}
            accessibilityRole="button"
            style={[styles.modeButton, { backgroundColor: comparisonMode === 'ytd' ? c.primary : c.card }]}
          >
            <Text style={{ color: comparisonMode === 'ytd' ? '#fff' : c.ink }}>This Year So Far</Text>
          </Pressable>
        </View>
        {multiYearIncomeQ.isLoading || multiYearSpendQ.isLoading ? (
          <ActivityIndicator color={c.primary} style={styles.loader} />
        ) : (
          <HorizontalBarList
            rows={
              comparisonMode === 'full'
                ? (multiYearIncomeQ.data?.fullYears ?? []).map((p) => ({
                    key: `income-${p.year}`,
                    label: `${p.year} Income`,
                    sub: p.isComplete ? undefined : `${p.coverageMonths}/12 months`,
                    value: p.total,
                  })).concat((multiYearSpendQ.data?.fullYears ?? []).map((p) => ({
                    key: `spend-${p.year}`,
                    label: `${p.year} Spend`,
                    sub: p.isComplete ? undefined : `${p.coverageMonths}/12 months`,
                    value: p.total,
                  })))
                : (multiYearIncomeQ.data?.thisYearSoFar.years ?? []).map((p) => ({
                    key: `income-${p.year}`, label: `${p.year} Income (so far)`, value: p.total,
                  })).concat((multiYearSpendQ.data?.thisYearSoFar.years ?? []).map((p) => ({
                    key: `spend-${p.year}`, label: `${p.year} Spend (so far)`, value: p.total,
                  })))
            }
            valueLabel={fmtCurrency}
            emptyMessage="Once you have a full calendar year of data, it appears here."
          />
        )}
      </Card>
```

Add `modeRow` and `modeButton` to the file's `StyleSheet.create({...})` block, after the existing
`periodRow`/`periodTextCol`/`periodLabel`/`periodValue`/`periodHint` entries (`periodRow` itself is
`{ gap: spacing.xs }` with no `flexDirection` — it stacks its children vertically, which is right
for `periodTextCol` next to `periodHint` in that section but wrong for two side-by-side toggle
buttons, so this needs its own row style rather than reusing `periodRow`):

```ts
  modeRow: { flexDirection: 'row', gap: spacing.sm, marginBottom: spacing.sm },
  modeButton: { paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, borderRadius: 6 },
```

(`spacing.xs`/`spacing.sm` are both already used elsewhere in this same file's `styles` object —
`periodRow: { gap: spacing.xs }` and `upgradeHint: { marginTop: spacing.sm, ... }` — so these are
real, existing tokens, not new literals.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npm test -- src/screens/AdvancedReportsScreen.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/src/api/endpoints.ts mobile/src/screens/AdvancedReportsScreen.tsx mobile/src/screens/AdvancedReportsScreen.test.tsx
git commit -m "feat(mobile): add Multi-Year Comparison section to Advanced Reports"
```

---

## Task 9: Full-suite verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full backend suite**

Run: `cd backend && ./mvnw test -q`
Expected: PASS, zero failures — confirms the `AnalyticsService` constructor change (Task 3) and
`AccountCoverageService` addition (Task 1) didn't regress anything outside this feature's own
tests.

- [ ] **Step 2: Run the full frontend suite**

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [ ] **Step 3: Run the full mobile suite**

Run: `cd mobile && npm test`
Expected: PASS (remember `NODE_OPTIONS=--experimental-vm-modules` — use `npm test`, not a bare
`npx jest`, per this repo's own `package.json` `test` script).

- [ ] **Step 4: Type-check and lint everything touched**

Run: `cd frontend && npx tsc --noEmit && npx eslint src/api/endpoints.ts src/pages/AdvancedReports.tsx src/pages/AdvancedReports.test.tsx`
Run: `cd mobile && npx tsc --noEmit && npx eslint src/api/endpoints.ts src/screens/AdvancedReportsScreen.tsx src/screens/AdvancedReportsScreen.test.tsx`
Expected: clean on both.

- [ ] **Step 5: Manual acceptance check against the issue's own criteria**

The issue's acceptance criteria require verification against **real** multi-year account data with
a deliberate gap, not just unit fixtures — seed or find a test account with 2+ years of statement
history including one genuine missing-statement month, hit all four `/multi-year/*` endpoints
against it, and confirm by eye: the gapped month's year shows `coverageMonths < 12`, no fabricated
YoY appears for it, and "This Year So Far" either stops before the gap or excludes the affected
prior year — whichever the data makes correct. State plainly if no such account is available
rather than skipping this and calling the feature done.
