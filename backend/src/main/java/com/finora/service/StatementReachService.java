package com.finora.service;

import com.finora.dto.StatementBehindDto;
import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Whether a month's figures are "so far" because an account's statements stop short of it, and
 * which account. The one place that asks the question for the Reports comparison and for both
 * Dashboard summaries, so they cannot disagree. The rule itself, and why it only looks at the
 * newest two months, is {@link MonthComparisonGate#behindAccountIds}.
 */
@Service
public class StatementReachService {

    private final AccountCoverageService accountCoverageService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public StatementReachService(AccountCoverageService accountCoverageService, AccountRepository accountRepository,
                                 TransactionRepository transactionRepository) {
        this.accountCoverageService = accountCoverageService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public Optional<StatementBehindDto> behindFor(UUID userId, YearMonth month, YearMonth newestDataMonth) {
        return behindFor(userId, month, newestDataMonth, accountCoverageService.latestCoverageEnds(userId));
    }

    /** For a caller judging several months, which fetches the coverage ends once and passes them
     *  in -- the same map {@link AccountCoverageService#latestCoverageEnds} returns. */
    @Transactional(readOnly = true)
    public Optional<StatementBehindDto> behindFor(UUID userId, YearMonth month, YearMonth newestDataMonth,
                                                  Map<UUID, LocalDate> latestStatementEnds) {
        // Cheap exits first, none of which pays for a query: nobody to judge, a month too old to be
        // judged, or a month after the user's newest data -- a custom Dashboard range can run into
        // the future, and a month with no data is not "so far" about anything.
        if (latestStatementEnds.isEmpty()
                || month.isAfter(newestDataMonth)
                || month.isBefore(newestDataMonth.minusMonths(MonthComparisonGate.RECENT_MONTHS - 1L))) {
            return Optional.empty();
        }
        List<UUID> activeIds = transactionRepository.findAccountIdsWithTransactionsBetween(
                userId, latestStatementEnds.keySet(), month.minusMonths(1).atDay(1), month.atEndOfMonth());
        List<UUID> behind = MonthComparisonGate.behindAccountIds(
                month, newestDataMonth, latestStatementEnds, new HashSet<>(activeIds));
        if (behind.isEmpty()) return Optional.empty();

        Map<UUID, String> names = accountRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Account::getId, a -> a.getName() == null ? "" : a.getName(), (a, b) -> a));
        // The account reaching the least far is the one that says most about how partial the month
        // is; ties broken by name so the answer does not change between calls.
        UUID worst = behind.stream()
                .min(Comparator.<UUID, LocalDate>comparing(latestStatementEnds::get)
                        .thenComparing(id -> names.getOrDefault(id, "")))
                .orElseThrow();
        return Optional.of(new StatementBehindDto(names.getOrDefault(worst, "An account"),
                latestStatementEnds.get(worst), behind.size()));
    }

    /** The first and last day of a month its figures cover: the dashboard's "20–30 Nov". */
    public record CoveredDays(LocalDate from, LocalDate through) {}

    /**
     * The days of {@code month} its figures cover, for the dashboard's "1–8 Nov". The two maps hold
     * each account with rows that month, with its earliest and latest counted row in the month (the
     * same accounts in both). {@code today} is the user's own date. Null when the month has no rows.
     * See {@link #firstCoveredDay(YearMonth, Map, Map)} and {@link #lastCoveredDay}.
     */
    @Transactional(readOnly = true)
    public CoveredDays coveredDays(UUID userId, YearMonth month, Map<UUID, LocalDate> earliestRowByAccount,
                                   Map<UUID, LocalDate> latestRowByAccount, LocalDate today) {
        if (earliestRowByAccount.isEmpty()) return null;
        Map<UUID, AccountCoverageService.PrintedSpan> spans =
                accountCoverageService.printedSpansOverlapping(userId, earliestRowByAccount.keySet(), month);
        Map<UUID, LocalDate> starts = new java.util.HashMap<>();
        Map<UUID, LocalDate> ends = new java.util.HashMap<>();
        spans.forEach((id, span) -> {
            starts.put(id, span.start());
            ends.put(id, span.end());
        });
        return new CoveredDays(firstCoveredDay(month, earliestRowByAccount, starts),
                lastCoveredDay(month, latestRowByAccount, ends, today));
    }

    /**
     * The last day of {@code month} its figures cover. Each account reaches its latest counted row,
     * or further to where its printed statements end (clipped to the month): a statement printed to
     * the 31st whose last transaction is on the 29th still covers the 30th and 31st, and "1–29 Oct"
     * would read as two days missing. The newest across the accounts, as the label has always taken
     * (the "so far" note names an account that stops earlier). Not past what is known:
     * <ul>
     *   <li>an account with no printed period (a CSV, entered by hand, linked) reaches its latest row
     *       only;</li>
     *   <li>a printed end after {@code today} is not used -- a statement printed "to the 30th" and
     *       downloaded on the 5th says nothing about the days after its rows.</li>
     * </ul>
     * Null when the month has no rows.
     */
    static LocalDate lastCoveredDay(YearMonth month, Map<UUID, LocalDate> latestRowByAccount,
                                    Map<UUID, LocalDate> latestPrintedEndByAccount, LocalDate today) {
        LocalDate monthEnd = month.atEndOfMonth();
        return latestRowByAccount.entrySet().stream().map(e -> {
            LocalDate row = e.getValue();
            LocalDate printed = latestPrintedEndByAccount.get(e.getKey());
            if (printed == null || printed.isAfter(today)) return row;
            LocalDate covered = printed.isAfter(monthEnd) ? monthEnd : printed;
            return covered.isAfter(row) ? covered : row;
        }).max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * The first day of {@code month} its figures cover. Conservative on purpose: a label
     * that claims fewer days than the figures hold is as wrong as one that claims more, so
     * <ul>
     *   <li>an account with no printed period (a CSV, entered by hand, linked) keeps the whole month:
     *       nothing says where its coverage starts;</li>
     *   <li>otherwise the earliest printed start across the accounts, clipped to the month;</li>
     *   <li>and never after a counted row -- a row dated before its statement's printed start is
     *       still in the figures.</li>
     * </ul>
     * Null when the month has no rows.
     */
    static LocalDate firstCoveredDay(YearMonth month, Map<UUID, LocalDate> earliestRowByAccount,
                                     Map<UUID, LocalDate> earliestPrintedStartByAccount) {
        if (earliestRowByAccount.isEmpty()) return null;
        LocalDate monthStart = month.atDay(1);
        if (!earliestPrintedStartByAccount.keySet().containsAll(earliestRowByAccount.keySet())) return monthStart;
        LocalDate printed = earliestRowByAccount.keySet().stream().map(earliestPrintedStartByAccount::get)
                .min(Comparator.naturalOrder()).orElse(monthStart);
        LocalDate covered = printed.isBefore(monthStart) ? monthStart : printed;
        LocalDate earliestRow = earliestRowByAccount.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        return earliestRow.isBefore(covered) ? earliestRow : covered;
    }
}
