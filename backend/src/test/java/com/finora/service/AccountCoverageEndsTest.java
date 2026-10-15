package com.finora.service;

import com.finora.entity.Account;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.LatestPeriodEnd;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.LatestTxnDate;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How far each account's data is known to reach -- the input to the "so far" label. Statement
 * accounts by their latest statement, linked accounts only when they are not delivering, and
 * accounts entered by hand never.
 */
class AccountCoverageEndsTest {

    private final UUID userId = UUID.randomUUID();
    private AccountRepository accounts;
    private StatementImportRepository statements;
    private AccountAggregatorLinkRepository links;
    private TransactionRepository transactions;
    private AccountCoverageService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        statements = mock(StatementImportRepository.class);
        links = mock(AccountAggregatorLinkRepository.class);
        transactions = mock(TransactionRepository.class);
        // The same 24h cadence production uses; a link counts as stale after 72h of silence.
        service = new AccountCoverageService(accounts, statements, links,
                new AccountAggregatorLinkStalenessService(24), transactions, mock(UserRepository.class));
        when(statements.findLatestPeriodEndsByAccount(any(), any())).thenReturn(List.of());
        when(links.findByAccountIdInAndStatus(any(), any())).thenReturn(List.of());
        when(transactions.findLatestTxnDatesByAccount(any(), any())).thenReturn(List.of());
    }

    private Account account(Account.PrimarySource source) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        a.setName("Account");
        a.setPrimarySource(source);
        return a;
    }

    private AccountAggregatorLink link(Account account, AccountAggregatorLink.SyncStatus status, Instant syncedAt) {
        AccountAggregatorLink l = new AccountAggregatorLink();
        l.setAccountId(account.getId());
        l.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        l.setLastSyncStatus(status);
        l.setLastSyncedAt(syncedAt);
        return l;
    }

    private void linksAre(AccountAggregatorLink... active) {
        when(links.findByAccountIdInAndStatus(any(), eq(AccountAggregatorLinkStatus.ACTIVE))).thenReturn(List.of(active));
    }

    private void newestTransaction(Account account, LocalDate date) {
        LatestTxnDate row = mock(LatestTxnDate.class);
        when(row.getAccountId()).thenReturn(account.getId());
        when(row.getLatestDate()).thenReturn(date);
        when(transactions.findLatestTxnDatesByAccount(any(), any())).thenReturn(List.of(row));
    }

    private void statementEnds(Account account, LocalDate end) {
        LatestPeriodEnd row = mock(LatestPeriodEnd.class);
        when(row.getAccountId()).thenReturn(account.getId());
        when(row.getLatestEnd()).thenReturn(end);
        when(statements.findLatestPeriodEndsByAccount(any(), any())).thenReturn(List.of(row));
    }

    @Test
    void aStatementImportedAccount_reachesAsFarAsItsLatestStatement() {
        Account card = account(Account.PrimarySource.MANUAL);
        when(accounts.findByUserId(userId)).thenReturn(List.of(card));
        statementEnds(card, LocalDate.of(2026, 9, 11));

        assertThat(service.latestCoverageEnds(userId)).isEqualTo(Map.of(card.getId(), LocalDate.of(2026, 9, 11)));
    }

    @Test
    void anAccountEnteredByHandWithNoStatement_isNeverJudged() {
        // Nothing says how far it is covered, and its newest transaction would call a quiet wallet behind.
        Account wallet = account(Account.PrimarySource.MANUAL);
        when(accounts.findByUserId(userId)).thenReturn(List.of(wallet));
        newestTransaction(wallet, LocalDate.of(2026, 9, 1));

        assertThat(service.latestCoverageEnds(userId)).isEmpty();
        verify(transactions, never()).findLatestTxnDatesByAccount(any(), any());
    }

    @Test
    void aLinkedAccountThatIsSyncingNormally_isUpToDate_andDoesNotAppear() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.SUCCESS, Instant.now().minus(2, ChronoUnit.HOURS)));

        // Listing it would label every month, because a month in progress always ends after today.
        assertThat(service.latestCoverageEnds(userId)).isEmpty();
    }

    @Test
    void aLinkedAccountWhoseLastAttemptFailed_reachesAsFarAsItsNewestTransaction() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.FAILED, Instant.now()));
        newestTransaction(linked, LocalDate.of(2026, 9, 5));

        assertThat(service.latestCoverageEnds(userId)).isEqualTo(Map.of(linked.getId(), LocalDate.of(2026, 9, 5)));
    }

    @Test
    void aLinkedAccountThatWentQuietPastItsCadence_reachesAsFarAsItsLastSuccessfulSync() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.SUCCESS, fiveDaysAgo));

        assertThat(service.latestCoverageEnds(userId))
                .isEqualTo(Map.of(linked.getId(), fiveDaysAgo.atZone(UserZone.DEFAULT).toLocalDate()));
    }

    @Test
    void aFailingLinkedAccountWithNoTransactionsAtAll_cannotBeJudged() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.FAILED, Instant.now()));

        assertThat(service.latestCoverageEnds(userId)).isEmpty();
    }

    @Test
    void aLinkedAccountsImportedStatementsAreIgnored_itIsJudgedBySyncHealthAlone() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.SUCCESS, Instant.now()));

        service.latestCoverageEnds(userId);

        // The statement query is never even asked about it.
        verify(statements, never()).findLatestPeriodEndsByAccount(any(), any());
    }

    @Test
    void onlyActiveLinksAreConsidered() {
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(linked));

        service.latestCoverageEnds(userId);

        verify(links).findByAccountIdInAndStatus(any(Collection.class), eq(AccountAggregatorLinkStatus.ACTIVE));
        verify(links, never()).findByAccountIdInAndStatus(any(), eq(AccountAggregatorLinkStatus.PAUSED));
    }

    @Test
    void aMixOfBothKinds_reportsEachByItsOwnRule() {
        Account card = account(Account.PrimarySource.MANUAL);
        Account linked = account(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        when(accounts.findByUserId(userId)).thenReturn(List.of(card, linked));
        statementEnds(card, LocalDate.of(2026, 9, 11));
        linksAre(link(linked, AccountAggregatorLink.SyncStatus.FAILED, Instant.now()));
        newestTransaction(linked, LocalDate.of(2026, 9, 5));

        assertThat(service.latestCoverageEnds(userId)).isEqualTo(Map.of(
                card.getId(), LocalDate.of(2026, 9, 11),
                linked.getId(), LocalDate.of(2026, 9, 5)));
    }

    @Test
    void aUserWithNoAccounts_hasNothingToJudge_andNothingIsQueried() {
        when(accounts.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.latestCoverageEnds(userId)).isEmpty();
        verify(statements, never()).findLatestPeriodEndsByAccount(any(), any());
        verify(links, never()).findByAccountIdInAndStatus(any(), any());
    }
}
