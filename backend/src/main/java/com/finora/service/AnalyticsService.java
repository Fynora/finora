package com.finora.service;

import com.finora.dto.AnalyticsDto;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-only aggregations over already-persisted merchant/transaction/learning data -- never
 * mutates anything (see docs/financial-intelligence-engine-spec.md's Analytics Engine
 * responsibility table: "Any mutation" is explicitly listed as NOT this class's job).
 *
 * Originally implemented exactly the 3 views §6.5 described a UI for (top merchants ranked list,
 * spend trend line chart, category confidence horizontal bar) -- "only what has a real UI need"
 * (spec Milestone D). The Financial Intelligence Workspace's Analytics module (see
 * docs/team-message-financial-intelligence-workspace-kickoff.md) is that real UI need for three
 * more: topCategories, importStatistics, learningGrowth. Rule usage isn't among them -- it
 * depends on execution tracking CategoryRule doesn't have yet (Workspace Rule Management task),
 * not built speculatively here either.
 *
 * The spend-facing methods (topMerchants, merchantTrend, topCategories) filter out the same set
 * DashboardService/ReportService already exclude from totals: duplicates, transfers, and
 * REFUND-status income -- spend analytics shouldn't count money that didn't actually leave the
 * user's pocket as spend, any more than the dashboard should.
 */
@Service
public class AnalyticsService {

    private static final int TOP_MERCHANTS_LIMIT = 10;
    private static final int TREND_MONTHS = 6;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantCategoryLearningRepository learningRepository;
    private final MerchantLearningAuditRepository learningAuditRepository;
    private final CategoryRepository categoryRepository;
    private final StatementImportRepository statementImportRepository;
    private final ConfidenceEngine confidenceEngine;
    // Added so this service can answer "what month is it for THIS user" -- it previously had no
    // ZoneId access at all, which is why merchantTrend() and learningGrowth() were computing
    // months in the server's zone and in hardcoded UTC respectively. See UserZone.
    private final UserRepository userRepository;
    private final TransactionGraphService transactionGraphService;
    // Multi-Year Comparison (issue #1455): the one caller of gapsForUser -- see MultiYearCoverage.
    private final AccountCoverageService accountCoverageService;

    public AnalyticsService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                             MerchantRepository merchantRepository,
                             MerchantCategoryLearningRepository learningRepository,
                             MerchantLearningAuditRepository learningAuditRepository,
                             CategoryRepository categoryRepository, StatementImportRepository statementImportRepository,
                             ConfidenceEngine confidenceEngine, UserRepository userRepository,
                             TransactionGraphService transactionGraphService,
                             AccountCoverageService accountCoverageService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.learningRepository = learningRepository;
        this.learningAuditRepository = learningAuditRepository;
        this.categoryRepository = categoryRepository;
        this.statementImportRepository = statementImportRepository;
        this.confidenceEngine = confidenceEngine;
        this.userRepository = userRepository;
        this.transactionGraphService = transactionGraphService;
        this.accountCoverageService = accountCoverageService;
    }

    /** Top merchants by total EXPENSE spend for the given month (all-time if month is null). */
    public List<AnalyticsDto.TopMerchant> topMerchants(UUID userId, YearMonth month) {
        Map<UUID, String> merchantNames = merchantNamesFor(userId);

        RefundNetting refunds = refundsFor(userId);
        Map<UUID, List<Transaction>> byMerchant = activeExpenseTransactions(userId, month).stream()
                .filter(t -> t.getMerchantId() != null)
                .collect(Collectors.groupingBy(Transaction::getMerchantId));

        return byMerchant.entrySet().stream()
                .map(e -> new AnalyticsDto.TopMerchant(
                        e.getKey(),
                        merchantNames.getOrDefault(e.getKey(), "Unknown"),
                        e.getValue().stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(AnalyticsDto.TopMerchant::totalSpend).reversed())
                .limit(TOP_MERCHANTS_LIMIT)
                .toList();
    }

    /** Total merchant-attributed EXPENSE spend per month, for the TREND_MONTHS trailing the given
     *  anchor month (current month if null). One point per month including zero-spend months, so
     *  the line chart's x-axis doesn't silently skip a month with no purchases. */
    public List<AnalyticsDto.TrendPoint> merchantTrend(UUID userId, YearMonth anchorMonth) {
        // Bug fix: this was a bare YearMonth.now(), i.e. the SERVER's zone, not the user's. The
        // anchor is optional on the live path (AdminUserAnalyticsController passes
        // parseMonth(month) for an optional query parameter), so the null branch is reached in
        // normal use -- and for the first 5.5 hours of an IST month it names the previous month,
        // silently shifting the whole 6-month window. The same class of bug was already fixed in
        // NetWorthService, GoalService and BudgetService; this service was missed because it had
        // no ZoneId access to fix it with. See UserZone.
        YearMonth end = anchorMonth != null ? anchorMonth : YearMonth.now(UserZone.forUser(userRepository, userId));
        YearMonth start = end.minusMonths(TREND_MONTHS - 1L);

        Map<YearMonth, BigDecimal> byMonth = new HashMap<>();
        RefundNetting refunds = refundsFor(userId);
        // BH-042: this used to call activeExpenseTransactions(userId, null) -- the ALL-TIME
        // overload -- loading the user's entire expense history via findByUserId, and only
        // discarded everything outside [start, end] afterward, in memory. start/end above are
        // already the exact window this method needs, so querying them directly is the fix; the
        // refund netting above is unaffected -- refundsFor() already runs its own small, always-
        // unbounded-by-design query (see its javadoc) that isn't part of the fetch being narrowed
        // here.
        //
        // Post-merge review: the manual month.isBefore(start)/isAfter(end) filter this loop used
        // to need (when the fetch was all-time) is gone -- findByUserIdAndTxnDateBetween's own
        // [start.atDay(1), end.atEndOfMonth()] bound already guarantees every row satisfies it, so
        // the check could only ever be dead code, and dead code that LOOKS like real filtering is
        // worse than no code: if the query bound were ever narrowed by mistake, this would have
        // silently absorbed the discrepancy instead of surfacing it as a visible bug.
        for (Transaction t : activeExpenseTransactions(userId, start.atDay(1), end.atEndOfMonth())) {
            if (t.getMerchantId() == null) continue;
            YearMonth m = YearMonth.from(t.getTxnDate());
            byMonth.merge(m, refunds.reportableAmount(t), BigDecimal::add);
        }

        List<AnalyticsDto.TrendPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            points.add(new AnalyticsDto.TrendPoint(m.toString(), byMonth.getOrDefault(m, BigDecimal.ZERO)));
        }
        return points;
    }

    /** One point per category, averaging confidence across every merchant whose CURRENT top
     *  category is that one -- "how confident is the engine, on average, about merchants filed
     *  under Category X." A merchant with no learned distribution yet has no top category and
     *  contributes to nothing here. */
    public List<AnalyticsDto.CategoryConfidencePoint> categoryConfidence(UUID userId) {
        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        // One bulk query grouped in-memory, not one findByUserIdAndMerchantId call per merchant
        // -- same N+1 discipline as ReconciliationService's duplicate-detection fix earlier.
        Map<UUID, List<MerchantCategoryLearning>> pairsByMerchant = learningRepository.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(MerchantCategoryLearning::getMerchantId));

        Map<UUID, List<Integer>> confidencesByCategory = new HashMap<>();
        for (List<MerchantCategoryLearning> pairs : pairsByMerchant.values()) {
            MerchantCategoryLearning top = confidenceEngine.topCategory(pairs);
            if (top == null) continue;
            confidencesByCategory.computeIfAbsent(top.getCategoryId(), k -> new ArrayList<>()).add(top.getConfidence());
        }

        return confidencesByCategory.entrySet().stream()
                .map(e -> new AnalyticsDto.CategoryConfidencePoint(
                        categoryNames.getOrDefault(e.getKey(), "Unknown"),
                        (int) Math.round(e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0)),
                        e.getValue().size()))
                .sorted(Comparator.comparingInt(AnalyticsDto.CategoryConfidencePoint::avgConfidence).reversed())
                .toList();
    }

    /** Same shape and exclusion rules as topMerchants(), grouped by category instead --
     *  Workspace Analytics' "Top Categories" view. */
    public List<AnalyticsDto.TopCategory> topCategories(UUID userId, YearMonth month) {
        Map<UUID, String> categoryNames = new HashMap<>();
        categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

        RefundNetting refunds = refundsFor(userId);
        Map<UUID, List<Transaction>> byCategory = activeExpenseTransactions(userId, month).stream()
                .filter(t -> t.getCategoryId() != null)
                .collect(Collectors.groupingBy(Transaction::getCategoryId));

        return byCategory.entrySet().stream()
                .map(e -> new AnalyticsDto.TopCategory(
                        e.getKey(),
                        categoryNames.getOrDefault(e.getKey(), "Uncategorized"),
                        e.getValue().stream().map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(AnalyticsDto.TopCategory::totalSpend).reversed())
                .limit(TOP_MERCHANTS_LIMIT)
                .toList();
    }

    /** Aggregated over StatementImport -- no new table. lastImportedAt is null for a user who's
     *  never imported anything, not epoch-zero or some other silent stand-in. */
    public AnalyticsDto.ImportStatistics importStatistics(UUID userId) {
        // Metadata projection, not the entity-returning finder: see
        // StatementImportRepository.StatementMetadata's own doc comment for the rest of that
        // finder's removal.
        // Deleted-account leak: same reasoning as activeExpenseTransactions above -- a deleted
        // account's statements deliberately keep deleted_at unset, so the unscoped finder would
        // keep counting them here forever.
        List<UUID> liveAccountIds = liveAccountIds(userId);
        List<StatementMetadata> imports = liveAccountIds.isEmpty() ? List.of()
                : statementImportRepository.findMetadataByUserIdAndAccountIdInOrderByImportedAtDesc(userId, liveAccountIds);
        int totalTransactionsImported = imports.stream().mapToInt(StatementMetadata::getTransactionsImported).sum();
        int totalTransactionsSkipped = imports.stream().mapToInt(StatementMetadata::getTransactionsSkipped).sum();
        var lastImportedAt = imports.isEmpty() ? null : imports.get(0).getImportedAt(); // already ordered desc
        return new AnalyticsDto.ImportStatistics(imports.size(), totalTransactionsImported, totalTransactionsSkipped, lastImportedAt);
    }

    /** LEARNED vs CORRECTED counts per month, oldest first -- same x-axis convention as
     *  merchantTrend(). Unlike merchantTrend()/topMerchants(), this isn't capped to a trailing
     *  window: learning history is small (one row per confirmation, not per transaction) and a
     *  user's whole learning timeline is exactly what "growth" means here, not just recent months. */
    public List<AnalyticsDto.LearningGrowthPoint> learningGrowth(UUID userId) {
        List<MerchantLearningAudit> entries = learningAuditRepository.findByUserId(userId);
        if (entries.isEmpty()) return List.of();

        // Bug fix: months were bucketed in hardcoded UTC, so activity in the first 5.5 hours of an
        // IST month landed in the previous month's bucket -- the user sees their own actions
        // attributed to a month they didn't happen in. Same root cause as the merchantTrend fix
        // above: this service had no access to the user's zone. Resolved once here rather than
        // per entry, since every row belongs to the same user.
        ZoneId zone = UserZone.forUser(userRepository, userId);

        Map<YearMonth, long[]> byMonth = new HashMap<>(); // [0]=learned, [1]=corrected
        for (MerchantLearningAudit entry : entries) {
            if (entry.getAction() != MerchantLearningAudit.Action.LEARNED
                    && entry.getAction() != MerchantLearningAudit.Action.CORRECTED) continue;
            YearMonth m = YearMonth.from(entry.getCreatedAt().atZone(zone));
            long[] counts = byMonth.computeIfAbsent(m, k -> new long[2]);
            if (entry.getAction() == MerchantLearningAudit.Action.LEARNED) counts[0]++;
            else counts[1]++;
        }
        if (byMonth.isEmpty()) return List.of();

        YearMonth start = byMonth.keySet().stream().min(YearMonth::compareTo).orElseThrow();
        YearMonth end = byMonth.keySet().stream().max(YearMonth::compareTo).orElseThrow();

        List<AnalyticsDto.LearningGrowthPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            long[] counts = byMonth.getOrDefault(m, new long[2]);
            points.add(new AnalyticsDto.LearningGrowthPoint(m.toString(), counts[0], counts[1]));
        }
        return points;
    }

    /** Total refund-netted INCOME per calendar year (Multi-Year Comparison, issue #1455;
     *  docs/superpowers/specs/2026-09-14-multi-year-comparison-design.md §2.1). */
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

    /**
     * BH-005, third copy. The REFUND clause here was doing nothing useful and hiding that: a refund
     * leg is INCOME, so it was already excluded by the EXPENSE filter one line down, while the
     * PURCHASE it reverses stayed counted in full. Every spend figure on this page therefore
     * included money that had come back.
     *
     * <p>{@link RefundNetting} owns the rule; {@link #refundsFor} supplies the amounts. Filtering
     * and amounts have to come from the same place, which is why the netting is returned alongside
     * rather than being applied here.
     */
    /**
     * BH-042 follow-up (found in post-merge review): a single specific month is itself a bounded
     * window, so delegate to {@link #activeExpenseTransactions(UUID, LocalDate, LocalDate)} rather
     * than loading the user's entire history to keep one month of it -- the original BH-042 PR
     * left this {@code month != null} case unbounded, reasoning (correctly) only about the {@code
     * month == null} "all-time" case, which genuinely does still need every row and stays as-is.
     */
    private List<Transaction> activeExpenseTransactions(UUID userId, YearMonth month) {
        if (month != null) {
            return activeExpenseTransactions(userId, month.atDay(1), month.atEndOfMonth());
        }
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so findByUserId alone would
        // keep counting them here forever, not just during StatementImportService's 7-day grace
        // window.
        List<UUID> liveAccountIds = liveAccountIds(userId);
        List<Transaction> all = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndAccountIdIn(userId, liveAccountIds);
        return RefundNetting.reportable(all, transactionGraphService.ccPaymentFromTransactionIds(all)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();
    }

    /**
     * BH-042: date-bounded twin of {@link #activeExpenseTransactions(UUID, YearMonth)}'s all-time
     * case, used by {@link #merchantTrend} (its own [start, end] trend window) and, since the
     * follow-up above, by the single-month case of the other overload too.
     */
    private List<Transaction> activeExpenseTransactions(UUID userId, LocalDate from, LocalDate to) {
        // Deleted-account leak: same reasoning as the all-time overload above -- a deleted
        // account's transactions deliberately keep deleted_at unset, so findByUserId-rooted queries
        // alone would keep feeding this window a deleted account's rows forever.
        List<UUID> liveAccountIds = liveAccountIds(userId);
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        return RefundNetting.reportable(rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();
    }

    /** The offsets for the same user, so a refunded purchase contributes what it actually cost. */
    private RefundNetting refundsFor(UUID userId) {
        List<UUID> liveAccountIds = liveAccountIds(userId);
        if (liveAccountIds.isEmpty()) return RefundNetting.from(List.of());
        return RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusInAndAccountIdIn(
                userId, java.util.List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL),
                liveAccountIds));
    }

    /** Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
     *  account's transactions/statements deliberately keep deleted_at unset, so findByUserId-rooted
     *  queries alone would keep feeding this service a deleted account's rows forever, not just
     *  during StatementImportService's 7-day grace window. */
    private List<UUID> liveAccountIds(UUID userId) {
        return accountRepository.findByUserId(userId).stream().map(com.finora.entity.Account::getId).toList();
    }

    private Map<UUID, String> merchantNamesFor(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        merchantRepository.findByUserId(userId).forEach(m -> names.put(m.getId(), m.getCanonicalName()));
        return names;
    }
}
