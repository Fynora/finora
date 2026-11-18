package com.finora.service;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.AdminDtos.PlatformSpendRowDto;
import com.finora.dto.AdminDtos.PlatformSpendSummaryDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-wide spend analytics for the admin console, one period at a time.
 *
 * <p><b>Every amount comes from {@link AnalyticsService#customerSpendLines}, one customer at a
 * time</b>, so a customer's money is counted here exactly as their own reports count it: refunds
 * and shares netted off, card-bill payments, replaced statements, deleted accounts and loans left
 * out. A grouped SQL query cannot do that (the rules live in ReportRows and RefundNetting), and an
 * earlier version that tried reported several times the real spend. Deliberately not
 * {@code @Transactional}: each customer's rows are released before the next customer's are read.
 *
 * <p>Names are grouped across customers' private category and merchant rows, ignoring case, since
 * every customer has their own "Groceries" row. Rows are ranked by how many customers spent under a
 * name, then by spend, so one large spender cannot top a list on their own. A name fewer than
 * {@link #MIN_CUSTOMERS} customers spent under is left out, and a period's money figures are
 * withheld below that floor, so the page does not point at one or two people.
 */
@Service
public class AdminPlatformAnalyticsService {

    private static final int TOP_LIMIT = 10;
    static final int MIN_CUSTOMERS = 3;
    static final int MAX_MONTHS = 12;
    private static final int EARLIEST_YEAR = 1900;
    private static final int LATEST_YEAR = 2100;

    // "All time" as a date range, so one code path serves both.
    static final LocalDate ALL_TIME_FROM = LocalDate.of(EARLIEST_YEAR, 1, 1);
    static final LocalDate ALL_TIME_TO = LocalDate.of(9999, 12, 31);

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;
    private final AnalyticsService analyticsService;

    public AdminPlatformAnalyticsService(TransactionRepository transactionRepository,
                                          CategoryRepository categoryRepository,
                                          MerchantRepository merchantRepository,
                                          AnalyticsService analyticsService) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
        this.analyticsService = analyticsService;
    }

    /**
     * @param fromMonth first month of the period, or null for all time (no comparison then)
     * @param months    how many months the period covers, 1 to {@link #MAX_MONTHS}; ignored for all time
     */
    public PlatformAnalyticsDto platformAnalytics(YearMonth fromMonth, int months) {
        if (fromMonth == null) {
            Period all = read(ALL_TIME_FROM, ALL_TIME_TO);
            return new PlatformAnalyticsDto(null, null, null, null, MIN_CUSTOMERS, all.summary(), null,
                    rows(all.categories, Map.of()), rows(all.merchants, Map.of()));
        }
        if (months < 1 || months > MAX_MONTHS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "months must be between 1 and " + MAX_MONTHS);
        }
        if (fromMonth.getYear() < EARLIEST_YEAR || fromMonth.getYear() > LATEST_YEAR) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "from must be a month between " + EARLIEST_YEAR + " and " + LATEST_YEAR);
        }
        LocalDate from = fromMonth.atDay(1);
        LocalDate to = fromMonth.plusMonths(months - 1L).atEndOfMonth();
        LocalDate previousFrom = fromMonth.minusMonths(months).atDay(1);
        LocalDate previousTo = fromMonth.minusMonths(1).atEndOfMonth();

        Period current = read(from, to);
        Period previous = read(previousFrom, previousTo);
        return new PlatformAnalyticsDto(from, to, previousFrom, previousTo, MIN_CUSTOMERS,
                current.summary(), previous.summary(),
                rows(current.categories, listableMedians(previous.categories)),
                rows(current.merchants, listableMedians(previous.merchants)));
    }

    /** One name's spend in a period: each customer's total, the purchases behind them, and how the
     *  customers spelled the name. */
    private static final class NameSpend {
        final Map<UUID, BigDecimal> spendByCustomer = new HashMap<>();
        final Map<String, Integer> spellings = new HashMap<>();
        long transactions;

        void add(String spelling, UUID customer, long purchases, BigDecimal spend) {
            spendByCustomer.merge(customer, spend, BigDecimal::add);
            spellings.merge(spelling, 1, Integer::sum);
            transactions += purchases;
        }

        BigDecimal total() {
            return spendByCustomer.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The spelling most customers use; alphabetical on a tie, so the name never flickers. */
        String shownName() {
            return spellings.entrySet().stream()
                    .min(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .orElseThrow().getKey();
        }
    }

    /** One customer's spend under one category id (null for no category). */
    private static final class CategorySpend {
        BigDecimal spend = BigDecimal.ZERO;      // what the category list shows
        BigDecimal topLine = BigDecimal.ZERO;    // the same without investment transfers
        BigDecimal flagged = BigDecimal.ZERO;    // the part of topLine flagged for review
        long purchases;
        long topLinePurchases;
    }

    private static final class Period {
        final Map<String, NameSpend> categories = new HashMap<>();
        final Map<String, NameSpend> merchants = new HashMap<>();
        final Map<UUID, BigDecimal> spendByCustomer = new HashMap<>();
        BigDecimal toSort = BigDecimal.ZERO;
        long transactions;

        PlatformSpendSummaryDto summary() {
            int customers = spendByCustomer.size();
            if (customers < MIN_CUSTOMERS) {
                return new PlatformSpendSummaryDto(customers, null, null, null, transactions);
            }
            BigDecimal total = spendByCustomer.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new PlatformSpendSummaryDto(customers, total, median(spendByCustomer.values()), toSort, transactions);
        }
    }

    private Period read(LocalDate from, LocalDate to) {
        Period period = new Period();
        List<UUID> customers = transactionRepository.platformCustomersWithExpense(Transaction.Type.EXPENSE, from, to);
        Map<UUID, Map<UUID, CategorySpend>> categoriesByCustomer = new HashMap<>();
        Map<UUID, Map<UUID, BigDecimal>> merchantSpendByCustomer = new HashMap<>();
        Map<UUID, Map<UUID, Long>> merchantPurchasesByCustomer = new HashMap<>();

        for (UUID customer : customers) {
            Map<UUID, CategorySpend> categories = new HashMap<>();
            Map<UUID, BigDecimal> merchantSpend = new HashMap<>();
            Map<UUID, Long> merchantPurchases = new HashMap<>();
            for (AnalyticsService.CustomerSpendLine line : analyticsService.customerSpendLines(customer, from, to)) {
                CategorySpend c = categories.computeIfAbsent(line.categoryId(), id -> new CategorySpend());
                c.spend = c.spend.add(line.amount());
                if (line.purchase()) c.purchases++;
                if (!line.investmentTransfer()) {
                    c.topLine = c.topLine.add(line.amount());
                    if (line.needsReview()) c.flagged = c.flagged.add(line.amount());
                    if (line.purchase()) c.topLinePurchases++;
                }
                // A customer's merchant list reads purchases alone: a refund is not a purchase.
                if (line.purchase() && line.merchantId() != null) {
                    merchantSpend.merge(line.merchantId(), line.amount(), BigDecimal::add);
                    merchantPurchases.merge(line.merchantId(), 1L, Long::sum);
                }
            }
            categoriesByCustomer.put(customer, categories);
            merchantSpendByCustomer.put(customer, merchantSpend);
            merchantPurchasesByCustomer.put(customer, merchantPurchases);
        }

        Map<UUID, String> categoryNames = categoryNames(categoriesByCustomer.values());
        Map<UUID, String> merchantNames = merchantNames(merchantSpendByCustomer.values());

        categoriesByCustomer.forEach((customer, categories) -> {
            BigDecimal customerTotal = BigDecimal.ZERO;
            for (Map.Entry<UUID, CategorySpend> entry : categories.entrySet()) {
                CategorySpend c = entry.getValue();
                // A category whose refunds exceed its purchases spent nothing, as in their own reports.
                BigDecimal topLine = RefundNetting.floorAtZero(c.topLine);
                customerTotal = customerTotal.add(topLine);
                period.transactions += c.topLinePurchases;
                String name = entry.getKey() == null ? null : categoryNames.get(entry.getKey());
                // No category, or one that no longer exists: their reports call this Uncategorized.
                period.toSort = period.toSort.add(name == null ? topLine
                        : RefundNetting.floorAtZero(c.flagged).min(topLine));
                if (name != null && c.spend.signum() > 0) {
                    period.categories.computeIfAbsent(key(name), k -> new NameSpend())
                            .add(name, customer, c.purchases, c.spend);
                }
            }
            if (customerTotal.signum() > 0) period.spendByCustomer.put(customer, customerTotal);
        });

        merchantSpendByCustomer.forEach((customer, spendByMerchant) -> spendByMerchant.forEach((merchantId, spend) -> {
            String name = merchantNames.get(merchantId);
            if (name != null && spend.signum() > 0) {
                period.merchants.computeIfAbsent(key(name), k -> new NameSpend())
                        .add(name, customer, merchantPurchasesByCustomer.get(customer).get(merchantId), spend);
            }
        }));
        return period;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private Map<UUID, String> categoryNames(Collection<Map<UUID, CategorySpend>> byCustomer) {
        Set<UUID> ids = new HashSet<>();
        byCustomer.forEach(m -> ids.addAll(m.keySet()));
        ids.remove(null);
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Category c : categoryRepository.findAllById(ids)) names.put(c.getId(), c.getName().trim());
        }
        return names;
    }

    private Map<UUID, String> merchantNames(Collection<Map<UUID, BigDecimal>> byCustomer) {
        Set<UUID> ids = new HashSet<>();
        byCustomer.forEach(m -> ids.addAll(m.keySet()));
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Merchant m : merchantRepository.findAllById(ids)) names.put(m.getId(), m.getCanonicalName().trim());
        }
        return names;
    }

    /** Spend per customer by name, for names at or above the floor -- the comparison's source. */
    private static Map<String, BigDecimal> listableMedians(Map<String, NameSpend> byName) {
        Map<String, BigDecimal> out = new HashMap<>();
        byName.forEach((key, s) -> {
            if (s.spendByCustomer.size() >= MIN_CUSTOMERS) out.put(key, median(s.spendByCustomer.values()));
        });
        return out;
    }

    private static List<PlatformSpendRowDto> rows(Map<String, NameSpend> byName, Map<String, BigDecimal> previousMedian) {
        List<PlatformSpendRowDto> rows = new ArrayList<>();
        byName.forEach((key, s) -> {
            if (s.spendByCustomer.size() < MIN_CUSTOMERS) return;
            rows.add(new PlatformSpendRowDto(s.shownName(), s.spendByCustomer.size(), s.total(),
                    median(s.spendByCustomer.values()), s.transactions, previousMedian.get(key)));
        });
        return rows.stream()
                .sorted(Comparator.comparingLong(PlatformSpendRowDto::customers).reversed()
                        .thenComparing(PlatformSpendRowDto::totalSpend, Comparator.reverseOrder())
                        .thenComparing(PlatformSpendRowDto::name))
                .limit(TOP_LIMIT)
                .toList();
    }

    /** The middle value; the mean of the two middle values for an even count; null for none. */
    static BigDecimal median(Collection<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) return null;
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
