package com.finora.service;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.AdminDtos.PlatformSpendRowDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.AnalyticsService.CustomerSpendLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform-wide spend analytics. What counts as spend is AnalyticsService's job
 *  (AdminPlatformAnalyticsControllerIT runs the real path); this pins what is done with each
 *  customer's lines: names grouped across customers, customers and median, the ranking, the privacy
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
    private AnalyticsService analyticsService;
    private AdminPlatformAnalyticsService service;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    private final List<Category> categories = new ArrayList<>();
    private final List<Merchant> merchants = new ArrayList<>();
    private final Map<LocalDate, Map<UUID, List<CustomerSpendLine>>> linesByPeriod = new HashMap<>();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        analyticsService = mock(AnalyticsService.class);
        service = new AdminPlatformAnalyticsService(transactionRepository, categoryRepository, merchantRepository, analyticsService);
        when(categoryRepository.findAllById(any())).thenReturn(categories);
        when(merchantRepository.findAllById(any())).thenReturn(merchants);
        when(transactionRepository.platformCustomersWithExpense(any(), any(), any())).thenAnswer(call ->
                List.copyOf(linesByPeriod.getOrDefault(call.<LocalDate>getArgument(1), Map.of()).keySet()));
        when(analyticsService.customerSpendLines(any(), any(), any())).thenAnswer(call ->
                linesByPeriod.getOrDefault(call.<LocalDate>getArgument(1), Map.of())
                        .getOrDefault(call.<UUID>getArgument(0), List.of()));
    }

    private Category category(String name) {
        Category cat = new Category();
        ReflectionTestUtils.setField(cat, "id", UUID.randomUUID());
        cat.setName(name);
        categories.add(cat);
        return cat;
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setCanonicalName(name);
        merchants.add(m);
        return m;
    }

    private void spend(LocalDate periodStart, UUID customer, CustomerSpendLine... lines) {
        linesByPeriod.computeIfAbsent(periodStart, k -> new HashMap<>())
                .computeIfAbsent(customer, k -> new ArrayList<>()).addAll(List.of(lines));
    }

    private static CustomerSpendLine buy(Category category, String amount) {
        return new CustomerSpendLine(category == null ? null : category.getId(), null, new BigDecimal(amount), true, false, false);
    }

    private static CustomerSpendLine flagged(Category category, String amount) {
        return new CustomerSpendLine(category.getId(), null, new BigDecimal(amount), true, true, false);
    }

    private static CustomerSpendLine offset(Category category, String amount) {
        return new CustomerSpendLine(category.getId(), null, new BigDecimal(amount).negate(), false, false, false);
    }

    private static CustomerSpendLine invest(Category category, String amount) {
        return new CustomerSpendLine(category.getId(), null, new BigDecimal(amount), true, false, true);
    }

    private static CustomerSpendLine at(Merchant merchant, String amount) {
        return new CustomerSpendLine(null, merchant.getId(), new BigDecimal(amount), true, false, false);
    }

    private static PlatformSpendRowDto row(List<PlatformSpendRowDto> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sameNameAcrossCustomersPrivateRowsIsOneRowWithCustomersAndMedian() {
        Category groceriesA = category("Groceries");
        Category groceriesB = category("Groceries");
        Category groceriesC = category("Groceries");
        spend(OCT_1, a, buy(groceriesA, "60.00"), buy(groceriesA, "40.00"));
        spend(OCT_1, b, buy(groceriesB, "50.00"));
        spend(OCT_1, c, buy(groceriesC, "20.00"), flagged(groceriesC, "10.00"));

        PlatformSpendRowDto groceries = row(service.platformAnalytics(OCT, 1).topCategories(), "Groceries");

        assertThat(groceries.customers()).isEqualTo(3);
        assertThat(groceries.totalSpend()).isEqualByComparingTo("180.00");
        assertThat(groceries.transactionCount()).isEqualTo(5);
        // Customer totals 100, 50, 30 -> middle is 50.
        assertThat(groceries.medianSpendPerCustomer()).isEqualByComparingTo("50.00");
    }

    @Test
    void namesDifferingOnlyInCaseOrSpacesAreOneRowUnderTheCommonestSpelling() {
        Category one = category("Groceries");
        Category two = category("groceries ");
        Category three = category("Groceries");
        spend(OCT_1, a, buy(one, "10.00"));
        spend(OCT_1, b, buy(two, "10.00"));
        spend(OCT_1, c, buy(three, "10.00"));

        List<PlatformSpendRowDto> rows = service.platformAnalytics(OCT, 1).topCategories();

        assertThat(rows).extracting(PlatformSpendRowDto::name).containsExactly("Groceries");
        assertThat(rows.get(0).customers()).isEqualTo(3);
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
        spend(OCT_1, a, buy(rent, "500000.00"), buy(food, "10.00"));
        spend(OCT_1, b, buy(rent, "400000.00"), buy(food, "10.00"));
        spend(OCT_1, c, buy(food, "10.00"));

        List<PlatformSpendRowDto> rows = service.platformAnalytics(OCT, 1).topCategories();

        assertThat(rows).extracting(PlatformSpendRowDto::name).containsExactly("Food");
    }

    @Test
    void aCustomerWhoseRefundsCancelACategoryDidNotSpendThere() {
        Category food = category("Food");
        spend(OCT_1, a, buy(food, "10.00"));
        spend(OCT_1, b, buy(food, "10.00"));
        spend(OCT_1, c, buy(food, "50.00"), offset(food, "80.00"));

        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(result.topCategories()).isEmpty();
        assertThat(result.summary().customersWithSpend()).isEqualTo(2);
    }

    @Test
    void rankedByCustomersBeforeSpend() {
        Category big = category("Big");
        Category common = category("Common");
        spend(OCT_1, a, buy(big, "900000.00"), buy(common, "5.00"));
        spend(OCT_1, b, buy(big, "1.00"), buy(common, "5.00"));
        spend(OCT_1, c, buy(big, "1.00"), buy(common, "5.00"));
        spend(OCT_1, d, buy(common, "5.00"));

        List<PlatformSpendRowDto> rows = service.platformAnalytics(OCT, 1).topCategories();

        assertThat(rows).extracting(PlatformSpendRowDto::name).containsExactly("Common", "Big");
    }

    @Test
    void toSortCountsNoCategoryFlaggedRowsAndADeletedCategoryButNotAConfidentOther() {
        Category other = category("Other");
        Category deleted = new Category();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID()); // never returned by findAllById
        spend(OCT_1, a, buy(null, "100.00"));
        spend(OCT_1, b, flagged(other, "30.00"), buy(deleted, "5.00"));
        spend(OCT_1, c, buy(other, "7.00"));

        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(result.summary().customersWithSpend()).isEqualTo(3);
        assertThat(result.summary().totalSpend()).isEqualByComparingTo("142.00");
        assertThat(result.summary().toSortSpend()).isEqualByComparingTo("135.00");
        assertThat(result.summary().medianSpendPerCustomer()).isEqualByComparingTo("35.00");
        assertThat(result.summary().transactionCount()).isEqualTo(4);
    }

    @Test
    void investmentsStayInTheirCategoryButOutOfThePeriodTotals() {
        Category investments = category("Investments");
        Category food = category("Food");
        spend(OCT_1, a, invest(investments, "1000.00"), buy(food, "10.00"));
        spend(OCT_1, b, invest(investments, "1000.00"), buy(food, "20.00"));
        spend(OCT_1, c, invest(investments, "1000.00"), buy(food, "30.00"));

        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(row(result.topCategories(), "Investments").totalSpend()).isEqualByComparingTo("3000.00");
        assertThat(result.summary().totalSpend()).isEqualByComparingTo("60.00");
        assertThat(result.summary().medianSpendPerCustomer()).isEqualByComparingTo("20.00");
        assertThat(result.summary().transactionCount()).isEqualTo(3);
    }

    @Test
    void summaryMoneyIsWithheldBelowThreeCustomers() {
        Category food = category("Food");
        spend(OCT_1, a, buy(food, "60.00"), buy(food, "40.00"));
        spend(OCT_1, b, buy(food, "50.00"));

        var summary = service.platformAnalytics(OCT, 1).summary();

        assertThat(summary.customersWithSpend()).isEqualTo(2);
        assertThat(summary.transactionCount()).isEqualTo(3);
        assertThat(summary.totalSpend()).isNull();
        assertThat(summary.medianSpendPerCustomer()).isNull();
        assertThat(summary.toSortSpend()).isNull();
    }

    @Test
    void previousFigureIsPerCustomerFromTheMonthBeforeAndWithheldBelowTheFloor() {
        Category food = category("Food");
        Category fuel = category("Fuel");
        for (UUID customer : List.of(a, b, c)) spend(OCT_1, customer, buy(food, "10.00"), buy(fuel, "10.00"));
        spend(SEP_1, a, buy(food, "5.00"), buy(fuel, "99.00"));
        spend(SEP_1, b, buy(food, "6.00"));
        spend(SEP_1, d, buy(food, "7.00"));

        PlatformAnalyticsDto result = service.platformAnalytics(OCT, 1);

        assertThat(result.previousFrom()).isEqualTo(SEP_1);
        assertThat(result.previousTo()).isEqualTo(SEP_30);
        assertThat(row(result.topCategories(), "Food").previousMedianSpendPerCustomer()).isEqualByComparingTo("6.00");
        assertThat(row(result.topCategories(), "Fuel").previousMedianSpendPerCustomer()).isNull();
        assertThat(result.previousSummary().customersWithSpend()).isEqualTo(3); // a, b, d -- a twice is one customer
        verify(analyticsService).customerSpendLines(a, OCT_1, OCT_31);
        verify(analyticsService).customerSpendLines(a, SEP_1, SEP_30);
    }

    @Test
    void merchantsCountPurchasesGroupedByNameAcrossCustomers() {
        Merchant amazonA = merchant("Amazon");
        Merchant amazonB = merchant("AMAZON");
        Merchant amazonC = merchant("Amazon");
        spend(OCT_1, a, at(amazonA, "150.00"), at(amazonA, "50.00"));
        spend(OCT_1, b, at(amazonB, "40.00"));
        spend(OCT_1, c, at(amazonC, "60.00"),
                new CustomerSpendLine(null, amazonC.getId(), new BigDecimal("-500.00"), false, false, false));

        PlatformSpendRowDto amazon = row(service.platformAnalytics(OCT, 1).topMerchants(), "Amazon");

        assertThat(amazon.customers()).isEqualTo(3);
        assertThat(amazon.totalSpend()).isEqualByComparingTo("300.00");
        assertThat(amazon.medianSpendPerCustomer()).isEqualByComparingTo("60.00");
        assertThat(amazon.transactionCount()).isEqualTo(4);
    }

    @Test
    void threeMonthsCrossingAYearComparesWithTheThreeMonthsBefore() {
        PlatformAnalyticsDto result = service.platformAnalytics(YearMonth.of(2026, 1), 3);

        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.to()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(result.previousFrom()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(result.previousTo()).isEqualTo(LocalDate.of(2025, 12, 31));
        verify(transactionRepository).platformCustomersWithExpense(any(),
                eq(LocalDate.of(2025, 10, 1)), eq(LocalDate.of(2025, 12, 31)));
    }

    @Test
    void allTimeHasNoDatesAndNoComparison() {
        PlatformAnalyticsDto result = service.platformAnalytics(null, 1);

        assertThat(result.from()).isNull();
        assertThat(result.previousSummary()).isNull();
        assertThat(result.minCustomers()).isEqualTo(3);
        verify(transactionRepository).platformCustomersWithExpense(any(),
                eq(AdminPlatformAnalyticsService.ALL_TIME_FROM), eq(AdminPlatformAnalyticsService.ALL_TIME_TO));
    }

    @Test
    void monthsOrAYearOutOfRangeIsRefusedBeforeAnyRead() {
        assertThatThrownBy(() -> service.platformAnalytics(OCT, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.platformAnalytics(OCT, 13)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.platformAnalytics(YearMonth.of(1899, 12), 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.platformAnalytics(YearMonth.of(2101, 1), 1)).isInstanceOf(ApiException.class);
        verify(transactionRepository, never()).platformCustomersWithExpense(any(), any(), any());
        assertThat(service.platformAnalytics(OCT, 12).to()).isEqualTo(LocalDate.of(2027, 9, 30));
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
