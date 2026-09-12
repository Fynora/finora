package com.finora.service;

import com.finora.dto.DashboardRangeSummaryDto;
import com.finora.dto.DashboardRangeType;
import com.finora.entity.Account;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardRangeService is the range-based counterpart to DashboardService's single-reporting-
 * month KPIs -- see its own doc comment for why it's a separate service. ReportService is mocked
 * throughout: its own income/expense computation (refund netting, deleted-account safety) is
 * ReportServiceTest's job, not this class's -- these tests are about range resolution, comparison
 * gating, and balance-snapshot lookup, the logic this class actually owns.
 */
class DashboardRangeServiceTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private NetWorthSnapshotRepository netWorthSnapshotRepository;
    private ReportService reportService;
    private DashboardRangeService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        netWorthSnapshotRepository = mock(NetWorthSnapshotRepository.class);
        reportService = mock(ReportService.class);
        service = new DashboardRangeService(accountRepository, transactionRepository, userRepository,
                netWorthSnapshotRepository, reportService);

        when(userRepository.findById(any())).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(any())).thenReturn(List.of());
        when(netWorthSnapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(reportService.forRange(any(), any(), any()))
                .thenReturn(new ReportService.RangeTotals(BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }

    private Account account() {
        Account a = new Account();
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        return a;
    }

    @Test
    void last6Months_anchorsToTheNewestMonthWithData_notTheRealCalendarMonth() {
        Account account = account();
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        // Newest transaction is in March 2026, well before "today" in any real sense -- the range
        // must anchor there, not to LocalDate.now(), mirroring ReportingPeriod's own philosophy.
        when(transactionRepository.findLatestTxnDate(eq(userId), any())).thenReturn(LocalDate.of(2026, 3, 15));
        when(transactionRepository.findEarliestTxnDate(eq(userId), any())).thenReturn(LocalDate.of(2025, 1, 5));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.startDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(dto.endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(dto.previousStartDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(dto.previousEndDate()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void noTransactionHistory_gatesComparisonAsNoHistory() {
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(null);
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(null);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.comparisonGateReason()).isEqualTo("NO_TRANSACTION_HISTORY");
        assertThat(dto.incomeDeltaPct()).isNull();
    }

    @Test
    void previousPeriodReachingBeforeAccountHistory_isGated() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        // Newest data: Aug 2026. Account history only goes back to Jun 2026 -- for LAST_6_MONTHS
        // the previous window (Nov 2025-Apr 2026) reaches back well before that, so it isn't a
        // genuine full comparison period.
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 8, 20));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 6, 1));
        when(reportService.forRange(eq(userId), any(), any()))
                .thenReturn(new ReportService.RangeTotals(BigDecimal.TEN, BigDecimal.ONE, 5));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.comparisonGateReason()).isEqualTo("PRIOR_PERIOD_BEFORE_HISTORY");
        assertThat(dto.incomeDeltaPct()).isNull();
        assertThat(dto.expenseDeltaPct()).isNull();
        assertThat(dto.netDeltaPct()).isNull();
        // The current period's own totals are still real numbers -- only the COMPARISON is gated.
        assertThat(dto.incomeTotal()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void tooFewPriorTransactions_isGated() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 8, 20));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2020, 1, 1));
        LocalDate prevStart = LocalDate.of(2025, 9, 1);
        LocalDate prevEnd = LocalDate.of(2026, 2, 28);
        when(reportService.forRange(eq(userId), eq(prevStart), eq(prevEnd)))
                .thenReturn(new ReportService.RangeTotals(BigDecimal.TEN, BigDecimal.ONE, 2));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.comparisonGateReason()).isEqualTo("TOO_FEW_PRIOR_TRANSACTIONS");
        assertThat(dto.comparisonGateMinTransactions()).isEqualTo(3);
    }

    @Test
    void enoughPriorHistory_computesRealDeltas() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 8, 20));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2020, 1, 1));
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        LocalDate prevStart = LocalDate.of(2025, 9, 1);
        LocalDate prevEnd = LocalDate.of(2026, 2, 28);
        when(reportService.forRange(userId, start, end))
                .thenReturn(new ReportService.RangeTotals(new BigDecimal("120000"), new BigDecimal("80000"), 40));
        when(reportService.forRange(userId, prevStart, prevEnd))
                .thenReturn(new ReportService.RangeTotals(new BigDecimal("100000"), new BigDecimal("80000"), 40));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.comparisonGateReason()).isNull();
        assertThat(dto.incomeTotal()).isEqualByComparingTo("120000");
        assertThat(dto.expenseTotal()).isEqualByComparingTo("80000");
        assertThat(dto.netSavingsTotal()).isEqualByComparingTo("40000");
        assertThat(dto.savingsRatePct()).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(dto.incomeDeltaPct()).isEqualTo(20.0);
        assertThat(dto.expenseDeltaPct()).isEqualTo(0.0);
        // net: (40000 - 20000) / 20000 * 100 = 100%
        assertThat(dto.netDeltaPct()).isEqualTo(100.0);
    }

    @Test
    void customRange_previousPeriodIsSameDayCountImmediatelyBefore() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2020, 1, 1));
        LocalDate start = LocalDate.of(2026, 3, 15);
        LocalDate end = LocalDate.of(2026, 8, 20); // 159 days inclusive

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.CUSTOM, start, end);

        assertThat(dto.startDate()).isEqualTo(start);
        assertThat(dto.endDate()).isEqualTo(end);
        assertThat(dto.previousEndDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(dto.previousStartDate()).isEqualTo(LocalDate.of(2025, 10, 7));
    }

    @Test
    void customRange_missingDates_throws() {
        assertThatThrownBy(() -> service.summarize(userId, DashboardRangeType.CUSTOM, null, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summarize(userId, DashboardRangeType.CUSTOM, LocalDate.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customRange_startAfterEnd_throws() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> service.summarize(userId, DashboardRangeType.CUSTOM, start, end))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balance_usesTheSnapshotAtOrBeforeRangeEnd() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 8, 20));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2020, 1, 1));
        NetWorthSnapshot snap = new NetWorthSnapshot();
        snap.setSnapshotDate(LocalDate.of(2026, 8, 29));
        snap.setNetWorth(new BigDecimal("5000"));
        when(netWorthSnapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                eq(userId), eq(LocalDate.of(2026, 8, 31)))).thenReturn(Optional.of(snap));

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.currentBalance()).isEqualByComparingTo("5000");
        assertThat(dto.currentBalanceAsOf()).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    void balance_fallsBackToLiveAccountBalance_whenNoSnapshotAndRangeEndIsTodayOrLater() {
        Account account = account();
        account.setBalance(new BigDecimal("7500"));
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        LocalDate today = LocalDate.now(com.finora.util.UserZone.DEFAULT);
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(today);
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(today.minusMonths(1));
        // No snapshot at all -- findFirstByUserIdAnd...LessThanEqual already stubbed to Optional.empty()

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_3_MONTHS, null, null);

        assertThat(dto.currentBalance()).isEqualByComparingTo("7500");
        assertThat(dto.currentBalanceAsOf()).isEqualTo(today);
    }

    /**
     * Bug fix: this used to default currentBalance to {@code BigDecimal.ZERO} in exactly this
     * case, reading as "the balance genuinely was zero on that date" -- a fabricated number, not
     * an honest "we don't know." A user picking a range whose end is in the past (their last real
     * import was months ago, say), on an account with no net-worth snapshot reaching back that
     * far, must see "no data," not a real-looking ₹0.
     */
    @Test
    void balance_noSnapshotAndRangeEndInThePast_returnsNullNotFabricatedZero() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        LocalDate longAgo = LocalDate.now(com.finora.util.UserZone.DEFAULT).minusYears(1);
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(longAgo);
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(longAgo.minusMonths(6));
        // No snapshot at all -- findFirstByUserIdAnd...LessThanEqual already stubbed to Optional.empty()

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_3_MONTHS, null, null);

        assertThat(dto.currentBalance()).isNull();
        assertThat(dto.currentBalanceAsOf()).isNull();
        assertThat(dto.currentBalanceGateReason()).isEqualTo("NO_SNAPSHOT_AT_OR_BEFORE_DATE");
        // Must not NPE computing a delta against a null current balance.
        assertThat(dto.balanceDeltaPct()).isNull();
    }

    @Test
    void balance_noPriorSnapshot_setsGateReasonAndNullDelta() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        when(transactionRepository.findLatestTxnDate(any(), any())).thenReturn(LocalDate.of(2026, 8, 20));
        when(transactionRepository.findEarliestTxnDate(any(), any())).thenReturn(LocalDate.of(2020, 1, 1));
        // Every snapshot lookup (current AND previous) stubbed empty in setUp().

        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_6_MONTHS, null, null);

        assertThat(dto.previousBalance()).isNull();
        assertThat(dto.balanceGateReason()).isEqualTo("NO_SNAPSHOT_AT_PRIOR_DATE");
        // This range's end (2026-08-31) is also in the past relative to whenever this test
        // actually runs, with no snapshot for the CURRENT period either -- must be null/gated,
        // not silently zero. This exact branch previously had no assertion at all.
        assertThat(dto.currentBalance()).isNull();
        assertThat(dto.currentBalanceGateReason()).isEqualTo("NO_SNAPSHOT_AT_OR_BEFORE_DATE");
        assertThat(dto.balanceDeltaPct()).isNull();
    }

    @Test
    void rangeType_isEchoedOnTheResponse() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account()));
        DashboardRangeSummaryDto dto = service.summarize(userId, DashboardRangeType.LAST_12_MONTHS, null, null);
        assertThat(dto.rangeType()).isEqualTo("LAST_12_MONTHS");
    }
}
