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
}
