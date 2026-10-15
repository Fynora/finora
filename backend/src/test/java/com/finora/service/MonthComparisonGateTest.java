package com.finora.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthComparisonGateTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 9, 30);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth JUL = YearMonth.of(2026, 7);

    private static String reason(LocalDate earliest, LocalDate latest, List<AccountCoverageService.DateRange> gaps,
                                 int selectedCount, int priorCount) {
        return MonthComparisonGate.reason(AUG, JUL, earliest, latest, gaps, false, selectedCount, priorCount);
    }

    // --- the partial-boundary rule (moved here from DashboardService unchanged) ---

    @Test
    void earliestBucketIsPartialUnlessItStartsOnTheFirst() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-01", LocalDate.of(2026, 1, 2), LAST)).isTrue();
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-01", LocalDate.of(2026, 1, 1), LAST)).isFalse();
    }

    @Test
    void latestBucketIsPartialUnlessItRunsToMonthEnd() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-09", FIRST, LocalDate.of(2026, 9, 29))).isTrue();
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-09", FIRST, LocalDate.of(2026, 9, 30))).isFalse();
        // February in a non-leap year ends on the 28th, not the 30th or 31st.
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-02", FIRST, LocalDate.of(2026, 2, 28))).isFalse();
    }

    @Test
    void aMonthInTheMiddleOfHistoryIsNeverABoundaryMonth() {
        assertThat(MonthComparisonGate.isPartialBoundaryMonth("2026-05", LocalDate.of(2026, 1, 17), LocalDate.of(2026, 9, 3)))
                .isFalse();
    }

    // --- statement gaps: inclusive bounds ---

    @Test
    void aGapTouchingAMonthsFirstOrLastDayOverlapsIt_aGapEndingTheDayBeforeDoesNot() {
        var endsOnAugFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 8, 1)));
        var endsJulThirtyFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 31)));
        var startsOnAugLast = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 5)));
        var startsSepFirst = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5)));

        assertThat(MonthComparisonGate.overlapsGap(AUG, endsOnAugFirst)).isTrue();
        assertThat(MonthComparisonGate.overlapsGap(AUG, endsJulThirtyFirst)).isFalse();
        assertThat(MonthComparisonGate.overlapsGap(AUG, startsOnAugLast)).isTrue();
        assertThat(MonthComparisonGate.overlapsGap(AUG, startsSepFirst)).isFalse();
    }

    // --- the combined decision ---

    @Test
    void comparableMonthsHaveNoReason() {
        assertThat(reason(FIRST, LAST, List.of(), 5, 5)).isNull();
    }

    @Test
    void theThreeTransactionFloorIsInclusive() {
        assertThat(reason(FIRST, LAST, List.of(), 5, 3)).isNull();
        assertThat(reason(FIRST, LAST, List.of(), 5, 2)).isEqualTo("TOO_FEW_PRIOR_TRANSACTIONS");
    }

    @Test
    void noHistoryAtAllIsTheFirstThingSaid() {
        assertThat(reason(null, null, List.of(), 0, 0)).isEqualTo("NO_TRANSACTION_HISTORY");
    }

    @Test
    void aSelectedMonthWithNoTransactionsIsNeverCompared() {
        assertThat(reason(FIRST, LAST, List.of(), 0, 9)).isEqualTo("NO_TRANSACTIONS_IN_MONTH");
    }

    @Test
    void aPriorMonthEndingBeforeHistoryBeganIsReportedAsThat_notAsTooFewTransactions() {
        assertThat(reason(LocalDate.of(2026, 8, 1), LAST, List.of(), 5, 0)).isEqualTo("PRIOR_BEFORE_HISTORY");
    }

    @Test
    void whenSeveralThingsAreWrongTheMostFundamentalIsReported() {
        // Selected month is the ragged latest bucket AND the prior is thin AND there is a gap:
        // the partial selected month is what a reader needs to hear first.
        var gap = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 9)));
        assertThat(MonthComparisonGate.reason(YearMonth.of(2026, 9), AUG, FIRST, LocalDate.of(2026, 9, 12), gap, true, 4, 1))
                .isEqualTo("SELECTED_MONTH_PARTIAL");
    }

    // --- an account whose statements stop short of the month ---

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final java.util.UUID CARD = java.util.UUID.randomUUID();
    private static final java.util.UUID BANK = java.util.UUID.randomUUID();

    private static boolean awaiting(YearMonth month, YearMonth newest, LocalDate cardEnd, java.util.Set<java.util.UUID> active) {
        var ends = new java.util.HashMap<java.util.UUID, LocalDate>();
        ends.put(BANK, LocalDate.of(2026, 9, 30));
        if (cardEnd != null) ends.put(CARD, cardEnd);
        return !MonthComparisonGate.behindAccountIds(month, newest, ends, active).isEmpty();
    }

    @Test
    void aCardWhoseStatementEndsOnThe11th_leavesTheNewestMonthAwaitingItsStatement() {
        // The case this exists for: bank to 30 Sep, card cycle ends 11 Sep. September looks
        // complete to the user-level rule, but the card's last three weeks are not in yet.
        assertThat(awaiting(SEP, SEP, LocalDate.of(2026, 9, 11), java.util.Set.of(BANK, CARD))).isTrue();
    }

    @Test
    void theMonthBefore_isNotAwaitingWhenTheCardsStatementReachesIntoTheNextMonth() {
        // The same card statement (to 11 Sep) fully covers August.
        assertThat(awaiting(AUG, SEP, LocalDate.of(2026, 9, 11), java.util.Set.of(BANK, CARD))).isFalse();
    }

    @Test
    void aCardThatMissedAWholeCycle_leavesBothRecentMonthsAwaiting() {
        LocalDate stoppedAug11 = LocalDate.of(2026, 8, 11);
        assertThat(awaiting(SEP, SEP, stoppedAug11, java.util.Set.of(BANK, CARD))).isTrue();
        assertThat(awaiting(AUG, SEP, stoppedAug11, java.util.Set.of(BANK, CARD))).isTrue();
    }

    @Test
    void aStatementReachingExactlyMonthEnd_isNotBehind_andOneDayShortIs() {
        assertThat(awaiting(SEP, SEP, LocalDate.of(2026, 9, 30), java.util.Set.of(CARD))).isFalse();
        assertThat(awaiting(SEP, SEP, LocalDate.of(2026, 9, 29), java.util.Set.of(CARD))).isTrue();
    }

    @Test
    void anAccountWithNoTransactionsInTheWindow_isNeverTheCause() {
        // A savings account that was simply quiet: nothing in the month or the one before.
        assertThat(awaiting(SEP, SEP, LocalDate.of(2026, 6, 1), java.util.Set.of(BANK))).isFalse();
    }

    @Test
    void aClosedCard_stopsBlockingOnceTheMonthIsNoLongerRecent() {
        // Final card statement ended 11 Aug and the card was closed. While September is the newest
        // month, August and September are held back; by November they are not judged at all.
        LocalDate lastStatement = LocalDate.of(2026, 8, 11);
        var cardWasActive = java.util.Set.of(CARD);
        assertThat(awaiting(AUG, SEP, lastStatement, cardWasActive)).isTrue();
        assertThat(awaiting(AUG, YearMonth.of(2026, 11), lastStatement, cardWasActive)).isFalse();
        assertThat(awaiting(SEP, YearMonth.of(2026, 11), lastStatement, cardWasActive)).isFalse();
    }

    @Test
    void aStatementBackedAccountIsTheOnlyKindJudged_anAccountWithNoStatementIsNotInTheMap() {
        // BANK is in the map; a manual-only account is not, so even if it is active it cannot count.
        var ends = java.util.Map.of(BANK, LocalDate.of(2026, 9, 30));
        assertThat(MonthComparisonGate.behindAccountIds(SEP, SEP, ends, java.util.Set.of(CARD))).isEmpty();
    }

    @Test
    void theStatementBehindReason_sitsAfterTheGapRuleAndBeforeTheThinPriorRule() {
        var gap = List.of(new AccountCoverageService.DateRange(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 9)));
        assertThat(MonthComparisonGate.reason(AUG, JUL, FIRST, LAST, List.of(), true, 5, 5)).isEqualTo("ACCOUNT_STATEMENT_BEHIND");
        assertThat(MonthComparisonGate.reason(AUG, JUL, FIRST, LAST, gap, true, 5, 5)).isEqualTo("COVERAGE_GAP");
        assertThat(MonthComparisonGate.reason(AUG, JUL, FIRST, LAST, List.of(), true, 5, 1)).isEqualTo("ACCOUNT_STATEMENT_BEHIND");
    }
}
