package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.util.CounterpartyType;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-service Analytics views (top-merchants/trend/category-confidence/top-categories/
 * learning-growth) restored to a real user, this time gated behind
 * FeatureEntitlement.ADVANCED_REPORTS -- the first FeatureEntitlement key any endpoint actually
 * enforces (EntitlementService.hasEntitlement had zero call sites before this). Proves the gate
 * fails CLOSED for Free/no-subscription users and opens for Plus/Premium, against real
 * Plan/Subscription/FeatureEntitlement rows seeded by V99__billing_entitlements.sql, not mocks.
 *
 * importStatistics is covered separately below to confirm the one pre-existing self-service view
 * is untouched by this change -- it was never part of the ADVANCED_REPORTS gate.
 */
class AnalyticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MerchantRepository merchantRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> ADVANCED_REPORTS_PATHS = List.of(
            "/api/v1/analytics/top-merchants", "/api/v1/analytics/trend",
            "/api/v1/analytics/category-confidence", "/api/v1/analytics/top-categories",
            "/api/v1/analytics/learning-growth", "/api/v1/analytics/international",
            "/api/v1/analytics/multi-year/income", "/api/v1/analytics/multi-year/spend",
            "/api/v1/analytics/multi-year/categories", "/api/v1/analytics/multi-year/lifestyle-inflation",
            "/api/v1/analytics/advanced");

    private User createUser() {
        User user = new User();
        user.setEmail("analytics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Analytics IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> get(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void aFreeUser_isDeniedEveryAdvancedReportsViewWithTheEntitlementErrorCode() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        for (String path : ADVANCED_REPORTS_PATHS) {
            ResponseEntity<String> response = get(path, user);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            JsonNode body = mapper.readTree(response.getBody());
            assertThat(body.get("errorCode").asText()).as(path).isEqualTo("ENTITLEMENT_001");
        }
    }

    @Test
    void aUserWithNoSubscriptionAtAll_isAlsoDenied_failingClosedNotOpen() {
        User user = createUser(); // deliberately skips provisionFreeSubscription

        ResponseEntity<String> response = get("/api/v1/analytics/top-merchants", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aPlusUser_seesEveryAdvancedReportsView() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        for (String path : ADVANCED_REPORTS_PATHS) {
            ResponseEntity<String> response = get(path, user);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void aPremiumUser_alsoSeesAdvancedReportsView_confirmingBothPaidTiersAreSeeded() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PREMIUM", "test-upgrade", user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/top-merchants", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void multiYearIncome_returnsAnEmptyReport_forAUserWithNoTransactions() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PREMIUM", "test-upgrade", user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/multi-year/income", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody()).get("data");
        assertThat(body.get("fullYears")).isEmpty();
        assertThat(body.get("thisYearSoFar").get("years")).isEmpty();
    }

    @Test
    void importStatisticsView_staysSelfServiceForEveryPlan_untouchedByTheAdvancedReportsGate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/merchants?view=importStatistics", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private User plusUser() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        return user;
    }

    private void persist(User user, UUID accountId, Transaction.Type type, LocalDate date, String amount, String description) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(accountId);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setTxnDate(date);
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        transactionRepository.save(t);
    }

    @Test
    void advanced_forAUserWithNoTransactions_saysThereIsNothingToShowRatherThanFailing() throws Exception {
        ResponseEntity<String> response = get("/api/v1/analytics/advanced?range=LAST_6_MONTHS", plusUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("period").get("hasData").asBoolean()).isFalse();
        assertThat(data.get("period").get("noDataReason").asText()).isEqualTo("NO_TRANSACTIONS");
        assertThat(data.get("months")).isEmpty();
        assertThat(data.get("insights")).isEmpty();
    }

    @Test
    void advanced_refusesAnUnknownRangeAndACustomRangeWithoutItsMonths() {
        User user = plusUser();

        assertThat(get("/api/v1/analytics/advanced?range=LAST_WEEK", user).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/advanced?range=CUSTOM", user).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/advanced?range=CUSTOM&startMonth=2026-09&endMonth=2026-01", user).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/advanced?range=CUSTOM&startMonth=nope&endMonth=2026-01", user).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/advanced?compare=SOMETIMES", user).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void advanced_marksAMerchantPaidToAPersonOnTheWire_soTheWebsiteNeverLooksTheNameUp() throws Exception {
        // The website's Top merchants looks each name up on Logo.dev unless the row says `person`.
        // This checks the flag survives the database, the service and the JSON, not just the service.
        User user = plusUser();
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Advanced IT Person Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setAccountHolderName("Analytics IT Test User");
        UUID accountId = accountRepository.save(account).getId();
        UUID friend = merchant(user, "Analytics IT Sample Person");
        UUID shop = merchant(user, "Analytics IT Sample Shop");
        // Whole months (first and last day) so the report has complete months to show.
        for (YearMonth m = YearMonth.of(2026, 6); !m.isAfter(YearMonth.of(2026, 9)); m = m.plusMonths(1)) {
            persistToMerchant(user, accountId, m.atDay(1), "700.00", friend, CounterpartyType.PERSON);
            persistToMerchant(user, accountId, m.atEndOfMonth(), "500.00", shop, CounterpartyType.BUSINESS);
        }

        ResponseEntity<String> response = get("/api/v1/analytics/advanced?range=LAST_3_MONTHS", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = mapper.readTree(response.getBody()).get("data").get("merchants").get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("Analytics IT Sample Person");
        assertThat(rows.get(0).get("person").isBoolean()).isTrue();
        assertThat(rows.get(0).get("person").asBoolean()).isTrue();
        assertThat(rows.get(1).get("name").asText()).isEqualTo("Analytics IT Sample Shop");
        assertThat(rows.get(1).get("person").asBoolean()).isFalse();
    }

    @Test
    void needsReviewMerchantGroups_markAGroupPaidToAPersonOnTheWire() throws Exception {
        // The mobile review screen looks a merchant group's name up on Logo.dev unless the group
        // says `person`; this checks the flag survives the database, the service and the JSON.
        User user = plusUser();
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Review IT Person Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setAccountHolderName("Analytics IT Test User");
        UUID accountId = accountRepository.save(account).getId();
        UUID friend = merchant(user, "Review IT Sample Person");
        UUID shop = merchant(user, "Review IT Sample Shop");
        for (int day = 1; day <= 2; day++) {
            persistToMerchant(user, accountId, LocalDate.of(2026, 9, day), "300.00", friend, CounterpartyType.PERSON, true);
            persistToMerchant(user, accountId, LocalDate.of(2026, 9, day), "200.00", shop, CounterpartyType.BUSINESS, true);
        }

        ResponseEntity<String> response = get("/api/v1/transactions/groups/needs-review", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.Map<String, Boolean> personByName = new java.util.HashMap<>();
        for (JsonNode g : mapper.readTree(response.getBody()).get("data")) {
            assertThat(g.get("person").isBoolean()).isTrue();
            personByName.put(g.get("merchantName").asText(), g.get("person").asBoolean());
        }
        assertThat(personByName).containsEntry("Review IT Sample Person", true)
                .containsEntry("Review IT Sample Shop", false);
    }

    private UUID merchant(User user, String name) {
        Merchant m = new Merchant();
        m.setUserId(user.getId());
        m.setCanonicalName(name);
        return merchantRepository.save(m).getId();
    }

    private void persistToMerchant(User user, UUID accountId, LocalDate date, String amount, UUID merchantId,
                                   CounterpartyType counterparty) {
        persistToMerchant(user, accountId, date, amount, merchantId, counterparty, false);
    }

    private void persistToMerchant(User user, UUID accountId, LocalDate date, String amount, UUID merchantId,
                                   CounterpartyType counterparty, boolean needsReview) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(accountId);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(date);
        t.setDescription("Advanced IT payment");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setMerchantId(merchantId);
        t.setCounterpartyType(counterparty);
        t.setNeedsCategoryReview(needsReview);
        transactionRepository.save(t);
    }

    @Test
    void advanced_returnsTheWireShapeFromRealRows_andEachMonthIsTheFigureReportsGives() throws Exception {
        User user = plusUser();
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Advanced IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setAccountHolderName("Analytics IT Test User");
        UUID accountId = accountRepository.save(account).getId();
        for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2026, 9)); m = m.plusMonths(1)) {
            persist(user, accountId, Transaction.Type.INCOME, m.atDay(1), "1000.00", "SALARY ACME TECHNOLOGIES");
            persist(user, accountId, Transaction.Type.EXPENSE, m.atDay(2), "300.00", "Advanced IT purchase");
            persist(user, accountId, Transaction.Type.EXPENSE, m.atEndOfMonth(), "100.00", "Advanced IT purchase");
        }

        ResponseEntity<String> response = get("/api/v1/analytics/advanced?range=LAST_3_MONTHS", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode period = data.get("period");
        assertThat(period.get("hasData").asBoolean()).isTrue();
        assertThat(period.get("startMonth").asText()).isEqualTo("2026-07");
        assertThat(period.get("endMonth").asText()).isEqualTo("2026-09");
        assertThat(period.get("compare").asText()).isEqualTo("PREVIOUS_PERIOD");
        assertThat(period.get("previousStartMonth").asText()).isEqualTo("2026-04");
        assertThat(period.get("compareOptions")).hasSize(2);
        assertThat(period.get("incompleteMonths")).isEmpty();
        assertThat(data.get("totals").get("income").decimalValue()).isEqualByComparingTo("3000");
        assertThat(data.get("totals").get("expense").decimalValue()).isEqualByComparingTo("1200");
        assertThat(data.get("totals").get("savingsRatePct").decimalValue()).isEqualByComparingTo("60");
        assertThat(data.get("comparison").get("reliable").asBoolean()).isTrue();
        assertThat(data.get("comparison").get("expenseDeltaPct").asDouble()).isZero();
        assertThat(data.get("months")).hasSize(3);
        assertThat(data.get("categories").get("rows").get(0).get("name").asText()).isEqualTo("Uncategorized");
        assertThat(data.get("categories").get("rows").get(0).get("monthly")).hasSize(3);
        assertThat(data.get("paymentMethods")).isNotNull();
        assertThat(data.get("insights")).isNotNull();

        for (JsonNode month : data.get("months")) {
            JsonNode report = mapper.readTree(get("/api/v1/reports?month=" + month.get("month").asText(), user).getBody()).get("data");
            assertThat(month.get("income").decimalValue()).as(month.get("month").asText() + " income")
                    .isEqualByComparingTo(report.get("income").decimalValue());
            assertThat(month.get("expense").decimalValue()).as(month.get("month").asText() + " expense")
                    .isEqualByComparingTo(report.get("expense").decimalValue());
        }
    }
}
