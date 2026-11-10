package com.finora.service;

import com.finora.dto.CoverageDto;
import com.finora.entity.Account;
import com.finora.imports.StatementCoverageAnalyzer;
import com.finora.imports.StatementCoverageAnalyzer.StatementPeriod;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStalenessService;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.security.OwnershipGuard;
import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place {@code StatementCoverageAnalyzer} is actually invoked for Phase 1 (docs/proposals/
 * statement-continuity-and-coverage-integrity-proposal.md §5's "one function, three consumers" --
 * the user-facing and admin controllers are two of the three; Insights' own call site is Phase 3,
 * not this one). Owns ownership verification and the {@code StatementMetadata} -> {@code
 * StatementPeriod} mapping; the analyzer itself stays a pure function with no repository access.
 */
@Service
public class AccountCoverageService {

    private final AccountRepository accountRepository;
    private final StatementImportRepository statementImportRepository;
    private final AccountAggregatorLinkRepository linkRepository;
    private final AccountAggregatorLinkStalenessService linkStaleness;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public AccountCoverageService(AccountRepository accountRepository,
                                   StatementImportRepository statementImportRepository,
                                   AccountAggregatorLinkRepository linkRepository,
                                   AccountAggregatorLinkStalenessService linkStaleness,
                                   TransactionRepository transactionRepository,
                                   UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.statementImportRepository = statementImportRepository;
        this.linkRepository = linkRepository;
        this.linkStaleness = linkStaleness;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    /** @throws com.finora.exception.ApiException 404 if no such account, 403 if it belongs to
     *          someone else -- {@link OwnershipGuard} enforces both, not a hand-rolled check. */
    @Transactional(readOnly = true)
    public CoverageDto forAccount(UUID userId, UUID accountId) {
        OwnershipGuard.requireOwned(accountRepository.findById(accountId), Account::getUserId, userId, "Account");
        return buildReport(userId, accountId, true);
    }

    /** The admin path (§9: {@code GET /api/v1/admin/accounts/{accountId}/coverage}) looks up by
     *  accountId alone -- no {@code userId} is known ahead of time, and none is needed:
     *  {@code PLATFORM_DIAGNOSTICS_VIEW} already authorizes cross-user access at the controller
     *  level, the same way {@code AdminImportTraceController} looks up by reference/jobId alone.
     *  The account's own {@code userId} is read off the found row to scope the statement query. */
    @Transactional(readOnly = true)
    public CoverageDto forAccountAsAdmin(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        return buildReport(account.getUserId(), accountId, false);
    }

    /**
     * @param withUndated the user-facing view only (the app's "Your statements" months): a
     *        statement that prints no period -- a CSV export, almost always -- is counted by the first
     *        and last date of its own transactions, and its segment is marked approximate. Without
     *        it, a person importing CSVs never sees their months fill in. The admin view stays on
     *        printed periods alone, and nothing here changes the stored period (ImportService's
     *        confirm deliberately keeps that to what was printed).
     */
    private CoverageDto buildReport(UUID userId, UUID accountId, boolean withUndated) {
        List<StatementPeriod> periods = new ArrayList<>(
                statementImportRepository.findMetadataWithPeriodByUserIdAndAccountId(userId, accountId)
                        .stream()
                        .map(AccountCoverageService::toStatementPeriod)
                        .toList());
        java.util.Set<UUID> approximate = new java.util.HashSet<>();
        if (withUndated) {
            LocalDate through = ReportableDates.latestAllowed(
                    LocalDate.now(com.finora.util.UserZone.forUser(userRepository, userId)));
            for (StatementImportRepository.UndatedRange range
                    : statementImportRepository.findUndatedStatementRanges(userId, accountId, through)) {
                // No balances: an approximate range never claims a gap's balance delta.
                periods.add(new StatementPeriod(range.getStatementImportId(), range.getFirstDate(),
                        range.getLastDate(), null, null));
                approximate.add(range.getStatementImportId());
            }
        }

        var report = StatementCoverageAnalyzer.analyze(periods);
        return CoverageDto.from(accountId, report, approximate);
    }

    // Package-private (not private): FinancialMemoryCompleteness's caller (WorkspaceDashboardService)
    // reuses this same StatementMetadata -> StatementPeriod mapping rather than duplicating it --
    // both are in this package.
    static StatementPeriod toStatementPeriod(StatementMetadata m) {
        return new StatementPeriod(m.getId(), m.getStatementPeriodStart(), m.getStatementPeriodEnd(),
                m.getOpeningBalance(), m.getClosingBalance());
    }

    /** A day D, or its DateRange's start/end, using half-open-nothing inclusive bounds (matches
     *  StatementCoverageAnalyzer.CoverageGap's own gapStart/gapEnd, which are both inclusive). */
    public record DateRange(LocalDate start, LocalDate end) {}

    /**
     * How far each live account's data is known to reach, for the accounts where that can be
     * known. Two kinds of account appear, and anything else is deliberately not judged:
     *
     * <ul>
     *   <li><b>Statement-imported accounts</b> -- the newest active period end among their
     *       statements.</li>
     *   <li><b>Account Aggregator accounts that are not delivering</b> -- an ACTIVE link whose last
     *       attempt failed, or that has gone quiet past its normal cadence (the same test the rest
     *       of the app uses, {@link AccountAggregatorLinkStalenessService}). Their reach is the day of
     *       their last successful sync, or, when the last attempt failed and that day is not kept,
     *       the date of their newest transaction. A linked account that is syncing normally is
     *       simply up to date and does not appear: it has no "behind" to report, and listing it
     *       would label the whole of every month, since a month in progress always ends later than
     *       today.</li>
     *   <li><b>Accounts entered by hand</b> are never judged. Nothing says how far a hand-entered
     *       account is covered, and treating its newest transaction as its reach would call a
     *       wallet that was simply quiet "behind".</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, LocalDate> latestCoverageEnds(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, LocalDate> ends = new java.util.HashMap<>();

        List<UUID> statementBackedIds = accounts.stream()
                .filter(a -> a.getPrimarySource() != Account.PrimarySource.ACCOUNT_AGGREGATOR)
                .map(Account::getId).toList();
        if (!statementBackedIds.isEmpty()) {
            statementImportRepository.findLatestPeriodEndsByAccount(userId, statementBackedIds)
                    .forEach(row -> ends.put(row.getAccountId(), row.getLatestEnd()));
        }

        List<UUID> linkedIds = accounts.stream()
                .filter(a -> a.getPrimarySource() == Account.PrimarySource.ACCOUNT_AGGREGATOR)
                .map(Account::getId).toList();
        if (!linkedIds.isEmpty()) {
            List<AccountAggregatorLink> notDelivering = linkRepository
                    .findByAccountIdInAndStatus(linkedIds, AccountAggregatorLinkStatus.ACTIVE).stream()
                    .filter(link -> link.getLastSyncStatus() == AccountAggregatorLink.SyncStatus.FAILED
                            || linkStaleness.isStale(link))
                    .toList();
            if (!notDelivering.isEmpty()) {
                java.time.ZoneId zone = com.finora.util.UserZone.forUser(userRepository, userId);
                java.util.Map<UUID, LocalDate> newestTxn = new java.util.HashMap<>();
                // A row dated in the future proves no coverage: see ReportableDates.
                transactionRepository.findLatestTxnDatesByAccount(userId,
                                notDelivering.stream().map(AccountAggregatorLink::getAccountId).toList(),
                                ReportableDates.latestAllowed(LocalDate.now(zone)))
                        .forEach(row -> newestTxn.put(row.getAccountId(), row.getLatestDate()));
                for (AccountAggregatorLink link : notDelivering) {
                    LocalDate reach = link.getLastSyncStatus() == AccountAggregatorLink.SyncStatus.SUCCESS
                            && link.getLastSyncedAt() != null
                            ? link.getLastSyncedAt().atZone(zone).toLocalDate()
                            : newestTxn.get(link.getAccountId());
                    if (reach != null) ends.put(link.getAccountId(), reach);
                }
            }
        }
        return ends;
    }

    /**
     * Where each of these accounts' printed statement coverage of {@code month} begins (the earliest
     * active statement period overlapping it). An account whose statements print no period -- a CSV
     * export -- or that has none (entered by hand, linked) has no entry: nothing says where its
     * coverage starts.
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, LocalDate> earliestPrintedStartsOverlapping(UUID userId, java.util.Set<UUID> accountIds,
                                                                           java.time.YearMonth month) {
        if (accountIds.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, LocalDate> starts = new java.util.HashMap<>();
        statementImportRepository.findEarliestPeriodStartsOverlapping(userId, accountIds, month.atDay(1), month.atEndOfMonth())
                .forEach(row -> starts.put(row.getAccountId(), row.getEarliestStart()));
        return starts;
    }

    /** Every gap across every live account, for a caller (Multi-Year Comparison, issue #1455)
     *  that needs to know which calendar months are missing data at the USER level, not one
     *  account's own coverage page. A day only ever becomes a gap here because some live account
     *  has a genuine hole BETWEEN two of its own imported statements -- an account that simply
     *  didn't exist yet contributes nothing (see StatementCoverageAnalyzer's own doc comment:
     *  gaps are only ever reported between two periods, never before the first or after the
     *  last). Not merged/deduplicated across accounts -- a caller only ever asks "does any gap
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
}
