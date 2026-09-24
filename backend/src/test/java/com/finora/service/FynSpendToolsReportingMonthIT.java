package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.DashboardSummaryDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.UserZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ask Fyn's "What did I spend this month?" answered over the user's whole history: with no
 * {@code month} argument, both spend tools passed {@code null} to {@link
 * AnalyticsService#topCategories}, which means all time. A user with three months of rent at
 * 22,000 was told their rent "this month" was 66,000.
 *
 * <p>Runs against real Postgres rather than mocks because the claim under test is that Fyn and
 * the dashboard pick the same month and the same total from the same rows -- a mocked {@code
 * AnalyticsService} would only prove the tool forwards whatever the mock returns.
 *
 * <p>Dates are relative to the current calendar month in the default user zone, so the fixture
 * keeps the "three months, newest is this month" shape whenever the suite runs.
 */
class FynSpendToolsReportingMonthIT extends AbstractIntegrationTest {

    @Autowired private FynGetRecentTransactionsSummaryTool summaryTool;
    @Autowired private FynGetSpendByCategoryTool spendByCategoryTool;
    @Autowired private DashboardService dashboardService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;

    private UUID persistUser() {
        User user = new User();
        user.setEmail("fyn-reporting-month-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Fyn Reporting Month Test");
        return userRepository.save(user).getId();
    }

    private UUID persistAccount(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("100000"));
        account.setAccountHolderName("Fyn Reporting Month Test");
        return accountRepository.save(account).getId();
    }

    private UUID persistCategory(UUID userId, String name) {
        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
        return categoryRepository.save(category).getId();
    }

    private Transaction persist(UUID userId, UUID accountId, UUID categoryId, String amount, Transaction.Type type,
                                YearMonth month, int day, Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setTxnDate(month.atDay(day));
        t.setDescription("Fyn reporting-month fixture");
        t.setReconciliationStatus(status);
        return transactionRepository.save(t);
    }

    /** The demo account's shape, per month: salary in, rent 22,000 on the 2nd, a 10,000 SIP on
     *  the 3rd (an investment transfer, which the dashboard's Expenses total leaves out but its
     *  category breakdown keeps), groceries, and a one-off flight only in the middle month. */
    private UUID seedThreeMonths(YearMonth newest) {
        UUID userId = persistUser();
        UUID accountId = persistAccount(userId);
        UUID rent = persistCategory(userId, "Rent");
        UUID investments = persistCategory(userId, "Investments");
        UUID groceries = persistCategory(userId, "Groceries");
        UUID travel = persistCategory(userId, "Travel");
        UUID salary = persistCategory(userId, "Salary");
        for (int back = 2; back >= 0; back--) {
            YearMonth m = newest.minusMonths(back);
            persist(userId, accountId, salary, "90000", Transaction.Type.INCOME, m, 1,
                    Transaction.ReconciliationStatus.OK);
            persist(userId, accountId, rent, "22000", Transaction.Type.EXPENSE, m, 2,
                    Transaction.ReconciliationStatus.OK);
            persist(userId, accountId, investments, "10000", Transaction.Type.EXPENSE, m, 3,
                    Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
            persist(userId, accountId, groceries, "3240", Transaction.Type.EXPENSE, m, 5,
                    Transaction.ReconciliationStatus.OK);
        }
        persist(userId, accountId, travel, "7840", Transaction.Type.EXPENSE, newest.minusMonths(1), 12,
                Transaction.ReconciliationStatus.OK);
        return userId;
    }

    @Test
    void thisMonthSummaryCoversOnlyTheDashboardsMonth_andReportsTheDashboardsTotal() {
        YearMonth thisMonth = YearMonth.now(UserZone.DEFAULT);
        UUID userId = seedThreeMonths(thisMonth);

        DashboardSummaryDto dashboard = dashboardService.summarize(userId);
        String answer = summaryTool.execute(userId, Map.of());
        System.out.println("[fyn-reporting-month] dashboard reportingMonth=" + dashboard.reportingMonth()
                + " monthlyExpense=" + dashboard.monthlyExpense()
                + " spendByCategory=" + dashboard.spendByCategory());
        System.out.println("[fyn-reporting-month] summary tool answer=" + answer);

        assertThat(dashboard.reportingMonth()).isEqualTo(thisMonth.toString());
        assertThat(answer)
                .contains(thisMonth.toString())
                .contains("Rent: ₹22000.00 (1 txns)")
                .contains("Investments: ₹10000.00 (1 txns)")
                .contains("Groceries: ₹3240.00 (1 txns)")
                .doesNotContain("Travel")
                .doesNotContain("66000")
                .contains("Total spend: ₹" + dashboard.monthlyExpense());
        // Every category the dashboard's breakdown shows for its month, at the same amount.
        dashboard.spendByCategory().forEach((name, amount) ->
                assertThat(answer).contains(name + ": ₹" + amount + " ("));
        assertThat(dashboard.monthlyExpense()).isEqualByComparingTo("25240");
    }

    /** The total has to follow every rule the dashboard's Expenses figure follows, not just the
     *  investment-transfer one: a partly refunded purchase counts net, a transfer and a duplicate
     *  do not count, and spend with no category still counts even though no category lists it. */
    @Test
    void thisMonthTotalFollowsEveryDashboardExclusion() {
        YearMonth thisMonth = YearMonth.now(UserZone.DEFAULT);
        UUID userId = seedThreeMonths(thisMonth);
        UUID accountId = accountRepository.findByUserId(userId).get(0).getId();
        UUID shopping = persistCategory(userId, "Shopping");
        UUID groceries = categoryRepository.findByUserId(userId).stream()
                .filter(c -> c.getName().equals("Groceries")).findFirst().orElseThrow().getId();

        Transaction purchase = persist(userId, accountId, shopping, "1000", Transaction.Type.EXPENSE, thisMonth, 6,
                Transaction.ReconciliationStatus.OK);
        Transaction refund = persist(userId, accountId, shopping, "400", Transaction.Type.INCOME, thisMonth, 7,
                Transaction.ReconciliationStatus.REFUND);
        refund.setRefundOfTransactionId(purchase.getId());
        transactionRepository.save(refund);

        Transaction transfer = persist(userId, accountId, null, "5000", Transaction.Type.EXPENSE, thisMonth, 8,
                Transaction.ReconciliationStatus.TRANSFER);
        transfer.setTransfer(true);
        transactionRepository.save(transfer);

        Transaction original = transactionRepository.findByUserIdAndAccountIdIn(userId, java.util.List.of(accountId))
                .stream().filter(t -> groceries.equals(t.getCategoryId()) && YearMonth.from(t.getTxnDate()).equals(thisMonth))
                .findFirst().orElseThrow();
        Transaction duplicate = persist(userId, accountId, groceries, "3240", Transaction.Type.EXPENSE, thisMonth, 5,
                Transaction.ReconciliationStatus.DUPLICATE);
        duplicate.setIsDuplicateOf(original.getId());
        transactionRepository.save(duplicate);

        persist(userId, accountId, null, "700", Transaction.Type.EXPENSE, thisMonth, 9,
                Transaction.ReconciliationStatus.OK);

        DashboardSummaryDto dashboard = dashboardService.summarize(userId);
        String answer = summaryTool.execute(userId, Map.of());
        System.out.println("[fyn-reporting-month] exclusions dashboard monthlyExpense=" + dashboard.monthlyExpense()
                + " spendByCategory=" + dashboard.spendByCategory());
        System.out.println("[fyn-reporting-month] exclusions answer=" + answer);

        // 22,000 rent + 3,240 groceries + (1,000 - 400) shopping + 700 uncategorized.
        assertThat(dashboard.monthlyExpense()).isEqualByComparingTo("26540");
        assertThat(answer)
                .contains("Total spend: ₹" + dashboard.monthlyExpense())
                .contains("Shopping: ₹600.00 (1 txns)")
                .contains("Groceries: ₹3240.00 (1 txns)");
    }

    @Test
    void thisMonthSingleCategoryIsTheDashboardsMonth() {
        YearMonth thisMonth = YearMonth.now(UserZone.DEFAULT);
        UUID userId = seedThreeMonths(thisMonth);

        String answer = spendByCategoryTool.execute(userId, Map.of("category", "rent"));
        System.out.println("[fyn-reporting-month] spend-by-category answer=" + answer);

        assertThat(answer).contains("₹22000.00 across 1 transactions").contains(thisMonth.toString());
    }

    @Test
    void userWithNothingThisMonthGetsTheDashboardsLatestMonth_labelledAsNotCurrent() {
        YearMonth thisMonth = YearMonth.now(UserZone.DEFAULT);
        YearMonth lastMonth = thisMonth.minusMonths(1);
        UUID userId = seedThreeMonths(lastMonth);

        DashboardSummaryDto dashboard = dashboardService.summarize(userId);
        String answer = summaryTool.execute(userId, Map.of());
        System.out.println("[fyn-reporting-month] no-current-data answer=" + answer);

        assertThat(dashboard.reportingMonth()).isEqualTo(lastMonth.toString());
        assertThat(dashboard.reportingMonthIsCurrent()).isFalse();
        assertThat(answer)
                .contains(lastMonth.toString())
                .contains("no transactions for " + thisMonth)
                .contains("Rent: ₹22000.00 (1 txns)")
                .contains("Total spend: ₹" + dashboard.monthlyExpense());
    }

    @Test
    void anExplicitMonthStillWins() {
        YearMonth thisMonth = YearMonth.now(UserZone.DEFAULT);
        UUID userId = seedThreeMonths(thisMonth);
        YearMonth middle = thisMonth.minusMonths(1);

        String answer = summaryTool.execute(userId, Map.of("month", middle.toString()));

        assertThat(answer)
                .contains(middle.toString())
                .contains("Travel: ₹7840.00 (1 txns)")
                .contains("Rent: ₹22000.00 (1 txns)")
                .contains("Total spend: ₹33080.00");
    }
}
