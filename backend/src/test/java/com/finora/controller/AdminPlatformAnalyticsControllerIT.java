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
     * Runs the real SQL for one month. March 1990 is used so that no other IT class's rows (the
     * suite shares one database, and they date theirs today) can land in the period and push this
     * test's row out of the top 10. Covers: same-name private categories combined; customers and
     * median per customer; a staff account, a duplicate and a transfer left out; a row from another
     * month left out; the unsorted share (no category, or flagged for review); and the comparison
     * with February.
     */
    @Test
    void oneMonthCombinesCustomersAndComparesWithTheMonthBefore() throws Exception {
        User admin = createUser("ADMIN");
        String name = "Test Category " + UUID.randomUUID();
        LocalDate march = LocalDate.of(1990, 3, 15);
        LocalDate february = LocalDate.of(1990, 2, 10);

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
        transactionRepository.save(expense(one, accountOne, catOne.getId(), LocalDate.of(1990, 4, 1), "7777.00"));
        User staff = createUser("ADMIN");
        transactionRepository.save(expense(staff, accountFor(staff), categoryFor(staff, name).getId(), march, "6666.00"));

        // February: the same three customers, so the comparison is shown.
        transactionRepository.save(expense(one, accountOne, catOne.getId(), february, "10.00"));
        transactionRepository.save(expense(two, accountTwo, catTwo.getId(), february, "10.00"));
        transactionRepository.save(expense(three, accountThree, catThree.getId(), february, "10.00"));

        JsonNode data = get(admin, "?from=1990-03&months=1");

        assertThat(data.get("from").asText()).isEqualTo("1990-03-01");
        assertThat(data.get("to").asText()).isEqualTo("1990-03-31");
        assertThat(data.get("previousFrom").asText()).isEqualTo("1990-02-01");
        assertThat(data.get("previousTo").asText()).isEqualTo("1990-02-28");
        JsonNode row = rowNamed(data.get("topCategories"), name);
        assertThat(row).as("platform category row for " + name).isNotNull();
        assertThat(row.get("customers").asLong()).isEqualTo(3);
        assertThat(row.get("totalSpend").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(row.get("transactionCount").asLong()).isEqualTo(4);
        // Customer totals 120, 50, 30 -> middle is 50.
        assertThat(row.get("medianSpendPerCustomer").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(row.get("previousTotalSpend").decimalValue()).isEqualByComparingTo("30.00");

        JsonNode summary = data.get("summary");
        assertThat(summary.get("customersWithSpend").asLong()).isEqualTo(3);
        assertThat(summary.get("totalSpend").decimalValue()).isEqualByComparingTo("205.00");
        assertThat(summary.get("toSortSpend").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(data.get("previousSummary").get("customersWithSpend").asLong()).isEqualTo(3);
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
        for (String query : new String[]{"?from=1990-03&months=0", "?from=1990-03&months=13", "?from=1990-13", "?from=March"}) {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/admin/analytics/platform" + query, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
