package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Reports page's month report and per-month trend, through the real HTTP layer and a real
 * database: the JSON contract the web and mobile clients read, and the earliest/latest-date
 * queries the comparison rules depend on. Reports is free -- no entitlement is involved, so these
 * users only have the default Free subscription.
 */
class ReportControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("report-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Report IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        return user;
    }

    private UUID createAccount(User user) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Report IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setAccountHolderName("Report IT Test User");
        return accountRepository.save(account).getId();
    }

    private void persist(User user, UUID accountId, String amount, Transaction.Type type, LocalDate date, String description) {
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

    // "SALARY ..." so the flow classifier reads it as income. A credit that looks like it came from
    // a person is "money to review", not income, and would make these totals zero.
    private void income(User user, UUID accountId, String amount, LocalDate date) {
        persist(user, accountId, amount, Transaction.Type.INCOME, date, "SALARY ACME TECHNOLOGIES");
    }

    private void expense(User user, UUID accountId, String amount, LocalDate date) {
        persist(user, accountId, amount, Transaction.Type.EXPENSE, date, "Report IT purchase");
    }

    /** History from 1 Jun to `lastDay` Sep. July has 3 transactions, August has 2. */
    private User userWithFourMonths(LocalDate lastDay) {
        User user = createUser();
        UUID account = createAccount(user);
        expense(user, account, "50.00", LocalDate.of(2026, 6, 1));
        income(user, account, "1000.00", LocalDate.of(2026, 7, 2));
        expense(user, account, "300.00", LocalDate.of(2026, 7, 10));
        expense(user, account, "100.00", LocalDate.of(2026, 7, 20));
        income(user, account, "1500.00", LocalDate.of(2026, 8, 2));
        expense(user, account, "500.00", LocalDate.of(2026, 8, 12));
        expense(user, account, "70.00", lastDay);
        return user;
    }

    private ResponseEntity<String> get(String path, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void theMonthReport_carriesTheComparisonAndTheCounts_forAFreeUser() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        JsonNode report = data(get("/api/v1/reports?month=2026-08", user));

        assertThat(report.get("income").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(report.get("expense").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(report.get("transactionCount").asInt()).isEqualTo(2);
        assertThat(report.get("unresolvedInflowCount").asInt()).isZero();
        JsonNode comparison = report.get("comparison");
        assertThat(comparison.get("reliable").asBoolean()).isTrue();
        assertThat(comparison.get("gateReason").isNull()).isTrue();
        assertThat(comparison.get("previousMonth").asText()).isEqualTo("2026-07");
        assertThat(comparison.get("previousIncome").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(comparison.get("previousExpense").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(comparison.get("incomeDeltaPct").asDouble()).isEqualTo(50.0);
        assertThat(comparison.get("expenseDeltaPct").asDouble()).isEqualTo(25.0);
        assertThat(comparison.get("netDelta").decimalValue()).isEqualByComparingTo("400.00");
    }

    @Test
    void aMonthStillInProgress_isNotComparedWithAFullMonth() throws Exception {
        // The user's data ends on 26 September: September is a partial slice of a statement window.
        User user = userWithFourMonths(LocalDate.of(2026, 9, 26));

        JsonNode comparison = data(get("/api/v1/reports?month=2026-09", user)).get("comparison");

        assertThat(comparison.get("reliable").asBoolean()).isFalse();
        assertThat(comparison.get("gateReason").asText()).isEqualTo("SELECTED_MONTH_PARTIAL");
        assertThat(comparison.get("previousIncome").isNull()).isTrue();
        assertThat(comparison.get("netDelta").isNull()).isTrue();
    }

    @Test
    void theFirstMonthOfHistory_explainsWhyThereIsNothingToCompareWith() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        JsonNode comparison = data(get("/api/v1/reports?month=2026-06", user)).get("comparison");

        assertThat(comparison.get("reliable").asBoolean()).isFalse();
        assertThat(comparison.get("gateReason").asText()).isEqualTo("PRIOR_BEFORE_HISTORY");
    }

    // --- a card whose statement cycle ends on the 11th while the bank account runs to month-end ---

    private UUID createCard(User user, Account.PrimarySource source) {
        Account card = new Account();
        card.setUserId(user.getId());
        card.setName("Report IT Card");
        card.setAccountType(Account.Type.CREDIT_CARD);
        card.setBalance(BigDecimal.ZERO);
        card.setAccountHolderName("Report IT Test User");
        card.setPrimarySource(source);
        return accountRepository.save(card).getId();
    }

    private void statement(User user, UUID accountId, LocalDate start, LocalDate end, UUID supersededBy) {
        StatementImport s = new StatementImport();
        s.setUserId(user.getId());
        s.setAccountId(accountId);
        s.setFileName("card.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(new byte[] {1});
        s.setContentHash("report-it-" + UUID.randomUUID());
        s.setStatementPeriodStart(start);
        s.setStatementPeriodEnd(end);
        s.setSupersededBy(supersededBy);
        statementImportRepository.save(s);
    }

    /** Bank account to 30 Sep (four full months), plus a card active in Aug and Sep whose latest
     *  statement ends 11 Sep. */
    private User userWithACardBehind(Account.PrimarySource cardSource) {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));
        UUID card = createCard(user, cardSource);
        expense(user, card, "200.00", LocalDate.of(2026, 8, 20));
        expense(user, card, "150.00", LocalDate.of(2026, 9, 5));
        statement(user, card, LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 11), null);
        return user;
    }

    @Test
    void aCardWhoseStatementEndsOnThe11th_holdsBackSeptember_butNotAugust() throws Exception {
        User user = userWithACardBehind(Account.PrimarySource.MANUAL);

        JsonNode september = data(get("/api/v1/reports?month=2026-09", user)).get("comparison");
        assertThat(september.get("reliable").asBoolean()).isFalse();
        assertThat(september.get("gateReason").asText()).isEqualTo("ACCOUNT_STATEMENT_BEHIND");

        // The same statement (to 11 Sep) fully covers August.
        assertThat(data(get("/api/v1/reports?month=2026-08", user)).get("comparison").get("reliable").asBoolean()).isTrue();

        JsonNode trend = data(get("/api/v1/reports/trend?months=4", user));
        assertThat(trend.get(2).get("complete").asBoolean()).isTrue();   // August
        assertThat(trend.get(3).get("complete").asBoolean()).isFalse();  // September
    }

    @Test
    void theMonthReport_saysWhetherTheMonthIsWhole_soAChartCanMarkAPartialOne() throws Exception {
        User user = userWithACardBehind(Account.PrimarySource.MANUAL);

        assertThat(data(get("/api/v1/reports?month=2026-08", user)).get("complete").asBoolean()).isTrue();
        assertThat(data(get("/api/v1/reports?month=2026-09", user)).get("complete").asBoolean()).isFalse();
    }

    @Test
    void theMonthReport_carriesTheSavingsRate_andWhyItIsWithheldWhenThereIsNoIncome() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        JsonNode august = data(get("/api/v1/reports?month=2026-08", user));
        // 1500 earned, 500 spent: 1000 kept of 1500 = 66.67%.
        assertThat(august.get("savingsRatePct").decimalValue()).isEqualByComparingTo("66.67");
        assertThat(august.get("savingsRateGateReason").isNull()).isTrue();

        // June holds only a purchase: no income was counted, so there is no rate to show.
        JsonNode june = data(get("/api/v1/reports?month=2026-06", user));
        assertThat(june.get("savingsRatePct").isNull()).isTrue();
        assertThat(june.get("savingsRateGateReason").asText()).isEqualTo("NO_INCOME");
    }

    /** The report and the Dashboard build income and expense through separate code, so "same rule"
     *  is not "same number": this asks both endpoints about the same user and month. */
    private void assertReportAndDashboardAgreeOnTheSavingsRate(User user) throws Exception {
        JsonNode dashboard = data(get("/api/v1/dashboard/summary", user));
        String month = dashboard.get("reportingMonth").asText();
        JsonNode report = data(get("/api/v1/reports?month=" + month, user));

        assertThat(report.get("income").decimalValue()).isEqualByComparingTo(dashboard.get("monthlyIncome").decimalValue());
        assertThat(report.get("expense").decimalValue()).isEqualByComparingTo(dashboard.get("monthlyExpense").decimalValue());
        assertThat(report.get("savingsRateGateReason")).isEqualTo(dashboard.get("savingsRateGateReason"));
        if (dashboard.get("savingsRatePct").isNull()) {
            assertThat(report.get("savingsRatePct").isNull()).isTrue();
        } else {
            assertThat(report.get("savingsRatePct").decimalValue())
                    .isEqualByComparingTo(dashboard.get("savingsRatePct").decimalValue());
        }
    }

    @Test
    void theReportAndTheDashboard_showTheSameSavingsRate_forTheSameMonth() throws Exception {
        User user = createUser();
        UUID account = createAccount(user);
        income(user, account, "1500.00", LocalDate.of(2026, 8, 2));
        expense(user, account, "500.00", LocalDate.of(2026, 8, 12));
        income(user, account, "2000.00", LocalDate.of(2026, 9, 2));
        expense(user, account, "500.00", LocalDate.of(2026, 9, 12));
        expense(user, account, "250.00", LocalDate.of(2026, 9, 20));

        assertReportAndDashboardAgreeOnTheSavingsRate(user);
        // And it is a real figure, so the test above cannot pass by both sides being withheld.
        JsonNode report = data(get("/api/v1/reports?month=2026-09", user));
        assertThat(report.get("savingsRatePct").decimalValue()).isEqualByComparingTo("62.50");
    }

    @Test
    void theReportAndTheDashboard_withholdTheSavingsRateForTheSameReason() throws Exception {
        User user = createUser();
        UUID account = createAccount(user);
        income(user, account, "1500.00", LocalDate.of(2026, 8, 2));
        expense(user, account, "500.00", LocalDate.of(2026, 8, 12));
        income(user, account, "2000.00", LocalDate.of(2026, 9, 2));
        expense(user, account, "500.00", LocalDate.of(2026, 9, 12));
        // A credit from a person is "money to review", not income -- and here it is larger than the
        // income that was counted, so the rate is withheld rather than shown.
        Transaction fromPerson = new Transaction();
        fromPerson.setUserId(user.getId());
        fromPerson.setAccountId(account);
        fromPerson.setAmount(new BigDecimal("3000.00"));
        fromPerson.setTxnType(Transaction.Type.INCOME);
        fromPerson.setTxnDate(LocalDate.of(2026, 9, 15));
        fromPerson.setDescription("UPI-SAMPLE PERSON-sampleuser@ybl-REF1");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        fromPerson.setSource(Transaction.Source.CSV_IMPORT);
        fromPerson.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        transactionRepository.save(fromPerson);

        assertReportAndDashboardAgreeOnTheSavingsRate(user);
        JsonNode report = data(get("/api/v1/reports?month=2026-09", user));
        assertThat(report.get("savingsRateGateReason").asText()).isEqualTo("UNRESOLVED_EXCEEDS_INCOME");
        assertThat(report.get("savingsRatePct").isNull()).isTrue();
    }

    @Test
    void theMonthReport_listsTheBiggestPurchasesAndSpendPerDay_fromTheSameRowsAsTheCategories() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        JsonNode july = data(get("/api/v1/reports?month=2026-07", user));

        JsonNode biggest = july.get("biggestSpends");
        assertThat(biggest).hasSize(2);
        assertThat(biggest.get(0).get("amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(biggest.get(0).get("date").asText()).isEqualTo("2026-07-10");
        assertThat(biggest.get(0).get("label").asText()).isEqualTo("Report IT purchase");
        assertThat(biggest.get(0).get("id").asText()).isNotBlank();
        assertThat(biggest.get(1).get("amount").decimalValue()).isEqualByComparingTo("100.00");

        JsonNode days = july.get("dailySpend");
        assertThat(days).hasSize(2);
        assertThat(days.get(0).get("date").asText()).isEqualTo("2026-07-10");
        assertThat(days.get(1).get("date").asText()).isEqualTo("2026-07-20");
        BigDecimal daySum = BigDecimal.ZERO;
        for (JsonNode d : days) daySum = daySum.add(d.get("amount").decimalValue());
        BigDecimal categorySum = BigDecimal.ZERO;
        for (JsonNode c : july.get("categories")) categorySum = categorySum.add(c.get("amount").decimalValue());
        assertThat(daySum).isEqualByComparingTo(categorySum);
        assertThat(daySum).isEqualByComparingTo(july.get("expense").decimalValue());
    }

    @Test
    void aMonthWithEveryStatementReachingItsEnd_isComplete() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        assertThat(data(get("/api/v1/reports?month=2026-09", user)).get("complete").asBoolean()).isTrue();
    }

    @Test
    void aStatementThatHasBeenSupersededDoesNotCountAsCoverage() throws Exception {
        User user = userWithACardBehind(Account.PrimarySource.MANUAL);
        UUID card = accountRepository.findByUserId(user.getId()).stream()
                .filter(a -> a.getAccountType() == Account.Type.CREDIT_CARD).findFirst().orElseThrow().getId();
        // A newer-looking statement that was replaced must not make the card look fully covered.
        statement(user, card, LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 30), UUID.randomUUID());

        JsonNode september = data(get("/api/v1/reports?month=2026-09", user)).get("comparison");

        assertThat(september.get("gateReason").asText()).isEqualTo("ACCOUNT_STATEMENT_BEHIND");
    }

    @Test
    void anAccountAggregatorCard_hasNoStatementReachToBeBehindOn() throws Exception {
        User user = userWithACardBehind(Account.PrimarySource.ACCOUNT_AGGREGATOR);

        JsonNode september = data(get("/api/v1/reports?month=2026-09", user)).get("comparison");

        assertThat(september.get("reliable").asBoolean()).isTrue();
    }

    @Test
    void theTrend_isOneEntryPerMonth_oldestFirst_andEachEqualsThatMonthsReport() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        JsonNode trend = data(get("/api/v1/reports/trend?months=6", user));

        assertThat(trend).hasSize(4); // Jun-Sep: never padded back before the first transaction
        assertThat(trend.get(0).get("month").asText()).isEqualTo("2026-06");
        assertThat(trend.get(3).get("month").asText()).isEqualTo("2026-09");
        JsonNode augustPoint = trend.get(2);
        JsonNode augustReport = data(get("/api/v1/reports?month=2026-08", user));
        assertThat(augustPoint.get("income").decimalValue()).isEqualByComparingTo(augustReport.get("income").decimalValue());
        assertThat(augustPoint.get("expense").decimalValue()).isEqualByComparingTo(augustReport.get("expense").decimalValue());
        assertThat(augustPoint.get("transactionCount").asInt()).isEqualTo(augustReport.get("transactionCount").asInt());
        assertThat(augustPoint.get("complete").asBoolean()).isTrue();
    }

    @Test
    void theTrend_endsAtTheRequestedMonth_andFlagsTheRaggedLatestMonthIncomplete() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 26));

        JsonNode toAugust = data(get("/api/v1/reports/trend?month=2026-08&months=2", user));
        assertThat(toAugust).hasSize(2);
        assertThat(toAugust.get(0).get("month").asText()).isEqualTo("2026-07");
        assertThat(toAugust.get(1).get("month").asText()).isEqualTo("2026-08");

        JsonNode newest = data(get("/api/v1/reports/trend?months=1", user));
        assertThat(newest.get(0).get("month").asText()).isEqualTo("2026-09");
        assertThat(newest.get(0).get("complete").asBoolean()).isFalse();
    }

    @Test
    void aMalformedMonthOrWindow_isABadRequest_notAServerError_andMatchesTheReportEndpoint() throws Exception {
        User user = createUser();

        for (String path : new String[] {
                "/api/v1/reports/trend?month=2026-13", "/api/v1/reports/trend?month=banana",
                "/api/v1/reports/trend?months=abc", "/api/v1/reports?month=banana"}) {
            ResponseEntity<String> response = get(path, user);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).as(path).isEqualTo("INVALID_PARAMETER");
        }
    }

    @Test
    void aWindowOfZeroOrLessIsClampedToOneMonth_notRejected() throws Exception {
        User user = userWithFourMonths(LocalDate.of(2026, 9, 30));

        assertThat(data(get("/api/v1/reports/trend?months=0", user))).hasSize(1);
        assertThat(data(get("/api/v1/reports/trend?months=-3", user))).hasSize(1);
    }

    @Test
    void aUserWithNoTransactions_getsAnEmptyTrend_andNoComparison() throws Exception {
        User user = createUser();

        assertThat(data(get("/api/v1/reports/trend", user))).isEmpty();
        assertThat(data(get("/api/v1/reports?month=2026-08", user)).get("comparison").isNull()).isTrue();
    }

    @Test
    void anotherUsersTransactions_neverAppearInTheTrendOrTheComparison() throws Exception {
        userWithFourMonths(LocalDate.of(2026, 9, 30)); // someone else's data
        User empty = createUser();
        createAccount(empty);

        assertThat(data(get("/api/v1/reports/trend", empty))).isEmpty();
        JsonNode report = data(get("/api/v1/reports?month=2026-08", empty));
        assertThat(report.get("income").decimalValue()).isEqualByComparingTo("0");
        assertThat(report.get("transactionCount").asInt()).isZero();
    }
}
