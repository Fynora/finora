package com.finora.service;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.AdminDtos.PlatformSpendRowDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform-wide spend analytics -- mocked-repository unit test. The SQL is TransactionRepository's
 *  job (AdminPlatformAnalyticsControllerIT runs it); this pins the Java side: re-grouping by name
 *  across customers' private rows, customers and median per customer, the ranking, the privacy
 *  floor, the unsorted share and the comparison period. */
class AdminPlatformAnalyticsServiceTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private MerchantRepository merchantRepository;
    private AdminPlatformAnalyticsService service;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        service = new AdminPlatformAnalyticsService(transactionRepository, categoryRepository, merchantRepository);
        when(transactionRepository.platformCategorySpendByCustomer(any(), any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.platformMerchantSpendByCustomer(any(), any(), any(), any())).thenReturn(List.of());
    }

    private Category category(String name) {
        Category cat = new Category();
        ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
        cat.setName(name);
        return cat;
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setCanonicalName(name);
        return m;
    }

    private static Object[] cat(Category category, UUID customer, boolean needsReview, long count, String spend) {
        return new Object[]{category == null ? null : category.getId(), customer, needsReview, count, new BigDecimal(spend)};
    }

    private static Object[] merch(Merchant merchant, UUID customer, long count, String spend) {
        return new Object[]{merchant.getId(), customer, count, new BigDecimal(spend)};
    }

    private void categories(LocalDate from, LocalDate to, List<Category> known, Object[]... rows) {
        when(transactionRepository.platformCategorySpendByCustomer(any(), any(), eq(from), eq(to)))
                .thenReturn(new ArrayList<>(List.of(rows)));
        when(categoryRepository.findAllById(any())).thenReturn(known);
    }

    private static PlatformSpendRowDto row(List<PlatformSpendRowDto> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sameNameAcrossCustomersPrivateRowsIsOneRowWithCustomersAndMedian() {
        Category groceriesA = category("Groceries");
        Category groceriesB = category("Groceries");
        categories(OCT_1, OCT_31, List.of(groceriesA, groceriesB),
                cat(groceriesA, a, false, 3, "100.00"),
                cat(groceriesB, b, false, 2, "50.00"),
                cat(groceriesB, c, false, 1, "20.00"),
                // The same customer twice (once flagged for review) is still one customer.
                cat(groceriesB, c, true, 1, "10.00"));

        PlatformSpendRowDto groceries = row(service.platformAnalytics(OCT, 1).topCategories(), "Groceries");

        assertThat(groceries.customers()).isEqualTo(3);
        assertThat(groceries.totalSpend()).isEqualByComparingTo("180.00");
        assertThat(groceries.transactionCount()).isEqualTo(7);
        // Customer totals 100, 50, 30 -> middle is 50.
        assertThat(groceries.medianSpendPerCustomer()).isEqualByComparingTo("50.00");
    }

    @Test
    void medianOfAnEvenCountIsTheMeanOfTheTwoMiddleValues() {
        assertThat(AdminPlatformAnalyticsService.median(List.of(
                new BigDecimal("10"), new BigDecimal("40"), new BigDecimal("20"), new BigDecimal("1000"))))
                .isEqualByComparingTo("30.00");
        assertThat(AdminPlatformAnalyticsService.median(List.of())).isNull();
    }

    @Test
    void aNameFewerThanThreeCustomersSpentUnderIsNotListed() {
        Category rent = category("Rent");
        Category food = category("Food");
        categories(OCT_1, OCT_31, List.of(rent, food),
                cat(rent, a, false, 1, "500000.00"),
                cat(rent, b, false, 1, "400000.00"),
                cat(food, a, false, 1, "10.00"),
                cat(food, b, false, 1, "10.00"),
                cat(food, c, false, 1, "10.00"));

        List<PlatformSpendRowDto> rows = service.platformAnalytics(OCT, 1).topCategories();

        assertThat(rows).extracting(PlatformSpendRowDto::name).containsExactly("Food");
    }

    @Test
    void rankedByCustomersBeforeSpend() {
        Category big = category("Big");
        Category common = category("Common");
        categories(OCT_1, OCT_31, List.of(big, common),
                cat(big, a, false, 1, "900000.00"),
                cat(big, b, false, 1, "1.00"),
                cat(big, c, false, 1, "1.00"),
                cat(common, a, false, 1, "5.00"),
                cat(common, b, false, 1, "5.00"),
                cat(common, c, false, 1, "5.00"),
                cat(common, d, false, 1, "5.00"));

        List<PlatformSpendRowDto> rows = service.platformAnalytics(OCT, 1).topCategories();

        assertThat(rows).extracting(PlatformSpendRowDto::name).containsExactly("Common", "Big");
    }

    @Test
    void toSortCountsNoCategoryAndFlaggedRowsButNotAConfidentOther() {
        Category other = category("Other");
        categories(OCT_1, OCT_31, List.of(other),
                cat(null, a, false, 1, "100.00"),
                cat(other, b, true, 1, "30.00"),
                cat(other, c, false, 1, "7.00"));

        var summary = service.platformAnalytics(OCT, 1).summary();

        assertThat(summary.customersWithSpend()).isEqualTo(3);
        assertThat(summary.totalSpend()).isEqualByComparingTo("137.00");
        assertThat(summary.toSortSpend()).isEqualByComparingTo("130.00");
        assertThat(summary.medianSpendPerCustomer()).isEqualByComparingTo("30.00");
        assertThat(summary.transactionCount()).isEqualTo(3);
    }

    @Test
    void summaryMoneyIsWithheldBelowThreeCustomers() {
        Category food = category("Food");
        categories(OCT_1, OCT_31, List.of(food),
                cat(food, a, false, 2, "100.00"),
                cat(food, b, false, 1, "50.00"));

        var summary = service.platformAnalytics(OCT, 1).summary();

        assertThat(summary.customersWithSpend()).isEqualTo(2);
        assertThat(summary.transactionCount()).isEqualTo(3);
        assertThat(summary.totalSpend()).isNull();
        assertThat(summary.medianSpendPerCustomer()).isNull();
        assertThat(summary.toSortSpend()).isNull();
    }

    @Test
    void previousSpendComesFromTheMonthBeforeAndIsWithheldBelowTheFloor() {
        Category food = category("Food");
        Category fuel = category("Fuel");
        when(categoryRepository.findAllById(any())).thenReturn(List.of(food, fuel));
        when(transactionRepository.platformCategorySpendByCustomer(any(), any(), eq(OCT_1), eq(OCT_31))).thenReturn(List.of(
                cat(food, a, false, 1, "10.00"), cat(food, b, false, 1, "10.00"), cat(food, c, false, 1, "10.00"),
                cat(fuel, a, false, 1, "10.00"), cat(fuel, b, false, 1, "10.00"), cat(fuel, c, false, 1, "10.00")));
        when(transactionRepository.platformCategorySpendByCustomer(any(), any(), eq(SEP_1), eq(SEP_30))).thenReturn(List.of(
                cat(food, a, false, 1, "5.00"), cat(food, b, false, 1, "5.00"), cat(food, d, false, 1, "5.00"),
                cat(fuel, a, false, 1, "99.00")));

        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(result.previousFrom()).isEqualTo(SEP_1);
        assertThat(result.previousTo()).isEqualTo(SEP_30);
        assertThat(row(result.topCategories(), "Food").previousTotalSpend()).isEqualByComparingTo("15.00");
        assertThat(row(result.topCategories(), "Fuel").previousTotalSpend()).isNull();
        assertThat(result.previousSummary().customersWithSpend()).isEqualTo(3); // a, b, d -- a twice is one customer
    }

    @Test
    void threeMonthsCrossingAYearComparesWithTheThreeMonthsBefore() {
        PlatformAnalyticsDto result = service.platformAnalytics(YearMonth.of(2026, 1), 3);

        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.to()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(result.previousFrom()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(result.previousTo()).isEqualTo(LocalDate.of(2025, 12, 31));
        verify(transactionRepository).platformCategorySpendByCustomer(any(), any(),
                eq(LocalDate.of(2025, 10, 1)), eq(LocalDate.of(2025, 12, 31)));
    }

    @Test
    void allTimeHasNoDatesAndNoComparison() {
        PlatformAnalyticsDto result = service.platformAnalytics(null, 1);

        assertThat(result.from()).isNull();
        assertThat(result.previousSummary()).isNull();
        assertThat(result.minCustomers()).isEqualTo(3);
        verify(transactionRepository).platformCategorySpendByCustomer(any(), any(),
                eq(AdminPlatformAnalyticsService.ALL_TIME_FROM), eq(AdminPlatformAnalyticsService.ALL_TIME_TO));
    }

    @Test
    void monthsOutsideOneToTwelveIsRefused() {
        assertThatThrownBy(() -> service.platformAnalytics(OCT, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.platformAnalytics(OCT, 13)).isInstanceOf(ApiException.class);
        verify(transactionRepository, never()).platformCategorySpendByCustomer(any(), any(), any(), any());
        assertThat(service.platformAnalytics(OCT, 12).to()).isEqualTo(LocalDate.of(2027, 9, 30));
    }

    @Test
    void merchantsGroupByNameAcrossCustomers() {
        Merchant amazonA = merchant("Amazon");
        Merchant amazonB = merchant("Amazon");
        when(transactionRepository.platformMerchantSpendByCustomer(any(), any(), eq(OCT_1), eq(OCT_31))).thenReturn(List.of(
                merch(amazonA, a, 4, "200.00"), merch(amazonB, b, 1, "40.00"), merch(amazonB, c, 1, "60.00")));
        when(merchantRepository.findAllById(any())).thenReturn(List.of(amazonA, amazonB));

        PlatformSpendRowDto amazon = row(service.platformAnalytics(OCT, 1).topMerchants(), "Amazon");

        assertThat(amazon.customers()).isEqualTo(3);
        assertThat(amazon.totalSpend()).isEqualByComparingTo("300.00");
        assertThat(amazon.medianSpendPerCustomer()).isEqualByComparingTo("60.00");
        assertThat(amazon.transactionCount()).isEqualTo(6);
    }

    @Test
    void noSpendAtAllIsEmptyNotAnError() {
        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(result.topCategories()).isEmpty();
        assertThat(result.topMerchants()).isEmpty();
        assertThat(result.summary().customersWithSpend()).isZero();
        assertThat(result.summary().totalSpend()).isNull();
    }
}
