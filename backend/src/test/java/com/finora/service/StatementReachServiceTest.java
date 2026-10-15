package com.finora.service;

import com.finora.dto.StatementBehindDto;
import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementReachServiceTest {

    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private final UUID userId = UUID.randomUUID();
    private AccountCoverageService coverage;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private StatementReachService service;

    private Account hdfc;
    private Account axis;

    @BeforeEach
    void setUp() {
        coverage = mock(AccountCoverageService.class);
        accounts = mock(AccountRepository.class);
        transactions = mock(TransactionRepository.class);
        service = new StatementReachService(coverage, accounts, transactions);
        hdfc = account("HDFC Card");
        axis = account("Axis Card");
        when(accounts.findByUserId(userId)).thenReturn(List.of(hdfc, axis));
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of());
    }

    private Account account(String name) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        a.setName(name);
        return a;
    }

    private void activeInWindow(Account... active) {
        when(transactions.findAccountIdsWithTransactionsBetween(eq(userId), any(), any(), any()))
                .thenReturn(java.util.Arrays.stream(active).map(Account::getId).toList());
    }

    @Test
    void noStatementBackedAccounts_meansNothingToJudge_andNoQueryIsRun() {
        assertThat(service.behindFor(userId, SEP, SEP)).isEmpty();

        verify(transactions, never()).findAccountIdsWithTransactionsBetween(any(), any(), any(), any());
    }

    @Test
    void aMonthOlderThanTheNewestTwo_isNotJudged_andNoQueryIsRun() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), LocalDate.of(2026, 5, 11)));

        assertThat(service.behindFor(userId, YearMonth.of(2026, 7), SEP)).isEmpty();

        verify(transactions, never()).findAccountIdsWithTransactionsBetween(any(), any(), any(), any());
    }

    @Test
    void anActiveAccountWhoseStatementEndsBeforeTheMonth_isReportedByNameAndDate() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), LocalDate.of(2026, 9, 11)));
        activeInWindow(hdfc);

        Optional<StatementBehindDto> behind = service.behindFor(userId, SEP, SEP);

        assertThat(behind).contains(new StatementBehindDto("HDFC Card", LocalDate.of(2026, 9, 11), 1));
    }

    @Test
    void severalAccountsBehind_reportsTheOneReachingTheLeastFar_andCountsAll() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(
                hdfc.getId(), LocalDate.of(2026, 9, 11),
                axis.getId(), LocalDate.of(2026, 8, 20)));
        activeInWindow(hdfc, axis);

        StatementBehindDto behind = service.behindFor(userId, SEP, SEP).orElseThrow();

        assertThat(behind.accountName()).isEqualTo("Axis Card");
        assertThat(behind.statementEnd()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(behind.accountCount()).isEqualTo(2);
    }

    @Test
    void whenTwoAccountsReachEquallyFar_theNameBreaksTheTie_soTheAnswerIsStable() {
        LocalDate same = LocalDate.of(2026, 9, 11);
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), same, axis.getId(), same));
        activeInWindow(hdfc, axis);

        assertThat(service.behindFor(userId, SEP, SEP).orElseThrow().accountName()).isEqualTo("Axis Card");
    }

    @Test
    void anAccountBehindButNotActive_isNotTheCause() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), LocalDate.of(2026, 9, 11)));
        activeInWindow(); // nothing in the month or the one before

        assertThat(service.behindFor(userId, SEP, SEP)).isEmpty();
    }

    @Test
    void aStatementReachingMonthEnd_isNotBehind() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), LocalDate.of(2026, 9, 30)));
        activeInWindow(hdfc);

        assertThat(service.behindFor(userId, SEP, SEP)).isEmpty();
    }

    @Test
    void theActivityWindowIsTheMonthAndTheOneBefore() {
        when(coverage.latestStatementEnds(userId)).thenReturn(Map.of(hdfc.getId(), LocalDate.of(2026, 9, 11)));
        activeInWindow(hdfc);

        service.behindFor(userId, SEP, SEP);

        verify(transactions).findAccountIdsWithTransactionsBetween(
                eq(userId), any(), eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 9, 30)));
    }

    @Test
    void aCallerJudgingSeveralMonths_canPassTheStatementEndsInOnce() {
        activeInWindow(hdfc);
        Map<UUID, LocalDate> ends = Map.of(hdfc.getId(), LocalDate.of(2026, 9, 11));

        service.behindFor(userId, SEP, SEP, ends);
        service.behindFor(userId, YearMonth.of(2026, 8), SEP, ends);

        verify(coverage, never()).latestStatementEnds(any());
    }
}
