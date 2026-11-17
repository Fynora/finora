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
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Platform-wide spend analytics for the admin console, one period at a time -- the aggregate view
 * AnalyticsController's per-user topCategories/topMerchants have no equivalent of (spec §5.7
 * scopes that controller to CurrentUser only, by design).
 *
 * Two-step aggregation rather than a single grouped query: Transaction.categoryId/merchantId are
 * plain UUID FK columns, not mapped @ManyToOne associations (see Category/Merchant entities), so
 * there's no JPQL join path from Transaction straight to a name. Step one groups by id and
 * customer in SQL (TransactionRepository.platformCategorySpendByCustomer /
 * platformMerchantSpendByCustomer); step two batch-resolves those ids to names via findAllById and
 * re-groups by NAME in Java, since categories/merchants are commonly seeded with the same names
 * across different users' own private rows (isSystem categories, same pattern
 * AdminMerchantStatsService's canonicalName grouping already relies on for merchants) -- without
 * this second pass, "platform-wide top categories" would just be whichever single user's private
 * "Groceries" row happened to have the most spend, not a real aggregate across everyone's.
 *
 * Rows are ranked by how many customers spent under a name, then by spend, so one large spender
 * cannot top a list on their own. A name fewer than {@link #MIN_CUSTOMERS} customers spent under is
 * left out, and a period's money figures are withheld below that floor, so no one customer's
 * spending can be read off the page.
 */
@Service
public class AdminPlatformAnalyticsService {

    private static final int TOP_LIMIT = 10;
    static final int MIN_CUSTOMERS = 3;
    static final int MAX_MONTHS = 12;

    // "All time" as a date range, so one query serves both; well inside Postgres's date range.
    static final LocalDate ALL_TIME_FROM = LocalDate.of(1900, 1, 1);
    static final LocalDate ALL_TIME_TO = LocalDate.of(9999, 12, 31);

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;

    public AdminPlatformAnalyticsService(TransactionRepository transactionRepository,
                                          CategoryRepository categoryRepository,
                                          MerchantRepository merchantRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
    }

    /**
     * @param fromMonth first month of the period, or null for all time (no comparison then)
     * @param months    how many months the period covers, 1 to {@link #MAX_MONTHS}; ignored for all time
     */
    @Transactional(readOnly = true)
    public PlatformAnalyticsDto platformAnalytics(YearMonth fromMonth, int months) {
        if (fromMonth == null) {
            Period all = read(ALL_TIME_FROM, ALL_TIME_TO);
            return new PlatformAnalyticsDto(null, null, null, null, MIN_CUSTOMERS, all.summary(), null,
                    all.rows(all.categories, Map.of()), all.rows(all.merchants, Map.of()));
        }
        if (months < 1 || months > MAX_MONTHS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "months must be between 1 and " + MAX_MONTHS);
        }
        LocalDate from = fromMonth.atDay(1);
        LocalDate to = fromMonth.plusMonths(months - 1L).atEndOfMonth();
        LocalDate previousFrom = fromMonth.minusMonths(months).atDay(1);
        LocalDate previousTo = fromMonth.minusMonths(1).atEndOfMonth();

        Period current = read(from, to);
        Period previous = read(previousFrom, previousTo);
        return new PlatformAnalyticsDto(from, to, previousFrom, previousTo, MIN_CUSTOMERS,
                current.summary(), previous.summary(),
                current.rows(current.categories, previous.listableSpend(previous.categories)),
                current.rows(current.merchants, previous.listableSpend(previous.merchants)));
    }

    /** One name's spend in a period: each customer's total, and the transactions behind them. */
    private static final class NameSpend {
        final Map<UUID, BigDecimal> spendByCustomer = new HashMap<>();
        long transactions;

        void add(UUID customer, long count, BigDecimal spend) {
            spendByCustomer.merge(customer, spend, BigDecimal::add);
            transactions += count;
        }

        BigDecimal total() {
            return spendByCustomer.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
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

        /** Spend per name, for names at or above the floor -- the comparison column's source. */
        Map<String, BigDecimal> listableSpend(Map<String, NameSpend> byName) {
            Map<String, BigDecimal> out = new HashMap<>();
            byName.forEach((name, s) -> {
                if (s.spendByCustomer.size() >= MIN_CUSTOMERS) out.put(name, s.total());
            });
            return out;
        }

        List<PlatformSpendRowDto> rows(Map<String, NameSpend> byName, Map<String, BigDecimal> previousSpend) {
            List<PlatformSpendRowDto> rows = new ArrayList<>();
            byName.forEach((name, s) -> {
                if (s.spendByCustomer.size() < MIN_CUSTOMERS) return;
                rows.add(new PlatformSpendRowDto(name, s.spendByCustomer.size(), s.total(),
                        median(s.spendByCustomer.values()), s.transactions, previousSpend.get(name)));
            });
            return rows.stream()
                    .sorted(Comparator.comparingLong(PlatformSpendRowDto::customers).reversed()
                            .thenComparing(PlatformSpendRowDto::totalSpend, Comparator.reverseOrder())
                            .thenComparing(PlatformSpendRowDto::name))
                    .limit(TOP_LIMIT)
                    .toList();
        }
    }

    private Period read(LocalDate from, LocalDate to) {
        Period period = new Period();

        List<Object[]> categoryRows = transactionRepository.platformCategorySpendByCustomer(
                Transaction.ReconciliationStatus.REFUND, Transaction.Type.EXPENSE, from, to);
        Map<UUID, String> categoryNames = names(categoryRows, categoryRepository::findAllById,
                Category::getId, Category::getName);
        for (Object[] row : categoryRows) {
            UUID categoryId = (UUID) row[0];
            UUID customer = (UUID) row[1];
            boolean needsReview = Boolean.TRUE.equals(row[2]);
            long count = (Long) row[3];
            BigDecimal spend = (BigDecimal) row[4];
            period.spendByCustomer.merge(customer, spend, BigDecimal::add);
            period.transactions += count;
            if (categoryId == null || needsReview) period.toSort = period.toSort.add(spend);
            if (categoryId != null) {
                String name = categoryNames.getOrDefault(categoryId, "Unknown category");
                period.categories.computeIfAbsent(name, n -> new NameSpend()).add(customer, count, spend);
            }
        }

        List<Object[]> merchantRows = transactionRepository.platformMerchantSpendByCustomer(
                Transaction.ReconciliationStatus.REFUND, Transaction.Type.EXPENSE, from, to);
        Map<UUID, String> merchantNames = names(merchantRows, merchantRepository::findAllById,
                Merchant::getId, Merchant::getCanonicalName);
        for (Object[] row : merchantRows) {
            String name = merchantNames.getOrDefault((UUID) row[0], "Unknown merchant");
            period.merchants.computeIfAbsent(name, n -> new NameSpend())
                    .add((UUID) row[1], (Long) row[2], (BigDecimal) row[3]);
        }
        return period;
    }

    private static <E> Map<UUID, String> names(List<Object[]> rows, Function<Set<UUID>, ? extends Iterable<E>> load,
                                               Function<E, UUID> id, Function<E, String> name) {
        Set<UUID> ids = new HashSet<>();
        for (Object[] row : rows) if (row[0] != null) ids.add((UUID) row[0]);
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        for (E e : load.apply(ids)) names.put(id.apply(e), name.apply(e));
        return names;
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
