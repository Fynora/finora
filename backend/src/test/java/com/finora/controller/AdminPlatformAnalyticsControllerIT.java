package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform-wide spend analytics (AdminPlatformAnalyticsService) -- proves PLATFORM_ANALYTICS_VIEW
 *  gating (V30's new permission) and runs the real per-period SQL: different customers' own private
 *  categories sharing a name are combined into one row (the scenario the name-based re-grouping
 *  exists for -- see the service's class comment), with its customers, median and comparison. */
class AdminPlatformAnalyticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-platform-analytics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Platform Analytics IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Account accountFor(User user) {
        Account a = new Account();
        a.setUserId(user.getId());
        a.setName("Test Account");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    private Transaction expense(User user, Account account, UUID categoryId, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setCategoryId(categoryId);
        t.setTxnDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    private Category categoryFor(User user, String name) {
        Category category = new Category();
        category.setUserId(user.getId());
        category.setName(name);
        return categoryRepository.save(category);
    }

    private JsonNode get(User admin, String query) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/analytics/platform" + query, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static JsonNode rowNamed(JsonNode rows, String name) {
        for (JsonNode candidate : rows) {
            if (candidate.get("name").asText().equals(name)) return candidate;
        }
        return null;
    }

    @Test
    void plainUser_isForbiddenFromViewingPlatformAnalytics() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/analytics/platform", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Runs the real path for one month. A random month long ago is used so that no other test's
     * rows (the suite shares one database) can land in the period, even on a re-run. Covers: same-name private categories combined; customers and
     * median per customer; a staff account, a duplicate and a transfer left out; a row from another
     * month left out; the unsorted share (no category, or flagged for review); and the comparison
     * with February.
     */
    @Test
    void oneMonthCombinesCustomersAndComparesWithTheMonthBefore() throws Exception {
        User admin = createUser("ADMIN");
        String name = "Test Category " + UUID.randomUUID();
        YearMonth marchMonth = YearMonth.of(1901 + java.util.concurrent.ThreadLocalRandom.current().nextInt(80), 3);
        LocalDate march = marchMonth.atDay(15);
        LocalDate february = marchMonth.minusMonths(1).atDay(10);

        User one = createUser("USER");
        User two = createUser("USER");
        User three = createUser("USER");
        Account accountOne = accountFor(one);
        Account accountTwo = accountFor(two);
        Account accountThree = accountFor(three);
        Category catOne = categoryFor(one, name);
        Category catTwo = categoryFor(two, name);
        Category catThree = categoryFor(three, name);

        Transaction first = transactionRepository.save(expense(one, accountOne, catOne.getId(), march, "100.00"));
        transactionRepository.save(expense(one, accountOne, catOne.getId(), march, "20.00"));
        transactionRepository.save(expense(two, accountTwo, catTwo.getId(), march, "50.00"));
        Transaction flagged = expense(three, accountThree, catThree.getId(), march, "30.00");
        flagged.setNeedsCategoryReview(true);
        transactionRepository.save(flagged);
        transactionRepository.save(expense(three, accountThree, null, march, "5.00"));

        // Left out: a duplicate, a linked transfer, another month, and a staff account's own spend.
        Transaction duplicate = expense(two, accountTwo, catTwo.getId(), march, "9999.00");
        duplicate.setIsDuplicateOf(first.getId());
        transactionRepository.save(duplicate);
        Transaction transfer = expense(two, accountTwo, catTwo.getId(), march, "8888.00");
        transfer.setTransfer(true);
        transactionRepository.save(transfer);
        transactionRepository.save(expense(one, accountOne, catOne.getId(), marchMonth.plusMonths(1).atDay(1), "7777.00"));
        User staff = createUser("ADMIN");
        transactionRepository.save(expense(staff, accountFor(staff), categoryFor(staff, name).getId(), march, "6666.00"));

        // February: the same three customers, so the comparison is shown.
        transactionRepository.save(expense(one, accountOne, catOne.getId(), february, "10.00"));
        transactionRepository.save(expense(two, accountTwo, catTwo.getId(), february, "10.00"));
        transactionRepository.save(expense(three, accountThree, catThree.getId(), february, "10.00"));

        JsonNode data = get(admin, "?from=" + marchMonth + "&months=1");

        assertThat(data.get("from").asText()).isEqualTo(marchMonth.atDay(1).toString());
        assertThat(data.get("to").asText()).isEqualTo(marchMonth.atEndOfMonth().toString());
        assertThat(data.get("previousFrom").asText()).isEqualTo(marchMonth.minusMonths(1).atDay(1).toString());
        assertThat(data.get("previousTo").asText()).isEqualTo(marchMonth.minusMonths(1).atEndOfMonth().toString());
        JsonNode row = rowNamed(data.get("topCategories"), name);
        assertThat(row).as("platform category row for " + name).isNotNull();
        assertThat(row.get("customers").asLong()).isEqualTo(3);
        assertThat(row.get("totalSpend").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(row.get("transactionCount").asLong()).isEqualTo(4);
        // Customer totals 120, 50, 30 -> middle is 50.
        assertThat(row.get("medianSpendPerCustomer").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(row.get("previousMedianSpendPerCustomer").decimalValue()).isEqualByComparingTo("10.00");

        JsonNode summary = data.get("summary");
        assertThat(summary.get("customersWithSpend").asLong()).isEqualTo(3);
        assertThat(summary.get("totalSpend").decimalValue()).isEqualByComparingTo("205.00");
        assertThat(summary.get("toSortSpend").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(data.get("previousSummary").get("customersWithSpend").asLong()).isEqualTo(3);
    }

    /**
     * The page must price a customer's spend the way that customer's own reports do. Every row
     * below is one AnalyticsService leaves out or nets off: a linked refund, a superseded
     * statement's row, a deleted account's row and an investment transfer in the top-line total.
     */
    @Test
    void spendIsPricedTheWayEachCustomersOwnReportsPriceIt() throws Exception {
        User admin = createUser("ADMIN");
        String name = "Priced Category " + UUID.randomUUID();
        YearMonth month = YearMonth.of(1901 + java.util.concurrent.ThreadLocalRandom.current().nextInt(80), 6);
        LocalDate day = month.atDay(15);

        User one = createUser("USER");
        User two = createUser("USER");
        User three = createUser("USER");
        Account accountOne = accountFor(one);
        Account accountTwo = accountFor(two);
        Account accountThree = accountFor(three);
        Category catOne = categoryFor(one, name);
        Category catTwo = categoryFor(two, name);
        Category catThree = categoryFor(three, name);

        // One: 1,000 with 400 refunded costs 600.
        Transaction purchase = transactionRepository.save(expense(one, accountOne, catOne.getId(), day, "1000.00"));
        Transaction refund = expense(one, accountOne, catOne.getId(), day, "400.00");
        refund.setTxnType(Transaction.Type.INCOME);
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        refund.setRefundOfTransactionId(purchase.getId());
        transactionRepository.save(refund);
        // One: an investment is not spending in the period's total.
        Transaction investment = expense(one, accountOne, null, day, "2000.00");
        investment.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        transactionRepository.save(investment);

        // Two: 500, and a row from a statement that was replaced.
        transactionRepository.save(expense(two, accountTwo, catTwo.getId(), day, "500.00"));
        Transaction superseded = expense(two, accountTwo, catTwo.getId(), day, "9000.00");
        superseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
        transactionRepository.save(superseded);

        // Three: 300, and a row on an account they have since deleted.
        transactionRepository.save(expense(three, accountThree, catThree.getId(), day, "300.00"));
        Account closed = accountFor(three);
        transactionRepository.save(expense(three, closed, catThree.getId(), day, "7000.00"));
        accountRepository.delete(closed);

        JsonNode data = get(admin, "?from=" + month + "&months=1");

        JsonNode row = rowNamed(data.get("topCategories"), name);
        assertThat(row).as("platform category row for " + name).isNotNull();
        assertThat(row.get("customers").asLong()).isEqualTo(3);
        assertThat(row.get("totalSpend").decimalValue()).isEqualByComparingTo("1400.00");
        assertThat(row.get("medianSpendPerCustomer").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(data.get("summary").get("totalSpend").decimalValue()).isEqualByComparingTo("1400.00");
    }

    @Test
    void allTimeHasNoPeriodAndNoComparison() throws Exception {
        JsonNode data = get(createUser("ADMIN"), "");

        assertThat(data.get("from").isNull()).isTrue();
        assertThat(data.get("previousSummary").isNull()).isTrue();
        assertThat(data.get("minCustomers").asInt()).isEqualTo(3);
    }

    @Test
    void aBadMonthOrMonthsIsABadRequest() {
        User admin = createUser("ADMIN");
        for (String query : new String[]{"?from=1990-03&months=0", "?from=1990-03&months=13", "?from=1990-13", "?from=March",
                "?from=0000-01", "?from=1899-12", "?from=2101-01"}) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/admin/analytics/platform" + query, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
