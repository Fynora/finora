package com.finora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import com.finora.util.UserZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Net worth and the money screens through the real security chain, controller advice and
 * database, fed what broke them in live testing on a local backend (2026-10-25):
 * <ul>
 *   <li>one transaction typed with a future year took over every screen that reports on "the
 *       newest month with data": the Dashboard reported on that month, the Reports trend and
 *       Advanced Reports showed only empty months before it, Insights described it, and every
 *       subscription on that account left the Recurring list as ended;</li>
 *   <li>a category whose purchases were all refunded was still listed, at zero;</li>
 *   <li>the income trend left out a month inside the history that had no income, so the chart
 *       stepped from the month before it to the month after;</li>
 *   <li>a month with a five-digit year was answered with an empty report instead of a 400.</li>
 * </ul>
 * Net worth itself held in every case; its endpoints had no test of their own, so the cases that
 * were checked are kept here.
 */
class NetWorthAndReportsBreaksIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private NetWorthSnapshotRepository snapshotRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("networth-reports-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Net Worth Reports IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode ok(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private JsonNode get(User user, String path) throws Exception {
        return ok(send(HttpMethod.GET, path, null, user));
    }

    private UUID account(User user, String type, String balance) throws Exception {
        return UUID.fromString(ok(send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"" + type + "\",\"accountType\":\"" + type + "\",\"balance\":" + balance + "}", user))
                .get("id").asText());
    }

    private ResponseEntity<String> addTxn(User user, UUID accountId, String date, String amount, String type,
                                          String description, String category) {
        return send(HttpMethod.POST, "/api/v1/transactions",
                "{\"accountId\":\"" + accountId + "\",\"categoryName\":\"" + category + "\",\"date\":\"" + date
                        + "\",\"description\":\"" + description + "\",\"merchant\":\"" + description
                        + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\"}", user);
    }

    private UUID txn(User user, UUID accountId, String date, String amount, String type, String description,
                     String category) throws Exception {
        return UUID.fromString(ok(addTxn(user, accountId, date, amount, type, description, category)).get("id").asText());
    }

    /** "Today" as the server judges it for this user: their own zone. */
    private static LocalDate today(User user) {
        return LocalDate.now(UserZone.of(user.getTimezone()));
    }

    /** A row already saved with a future date, as one could be before entry refused them: written
     *  past the API, which no longer accepts it. */
    private void moveToDate(UUID txnId, LocalDate date) {
        Transaction t = transactionRepository.findById(txnId).orElseThrow();
        t.setTxnDate(date);
        transactionRepository.save(t);
    }

    private static List<String> months(JsonNode points) {
        List<String> out = new ArrayList<>();
        points.forEach(p -> out.add(p.get("month").asText()));
        return out;
    }

    private static List<String> categories(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(c -> out.add(c.get("category").asText()));
        return out;
    }

    // ------------------------------------------------------------------ future dates

    @Test
    void addingATransactionDatedPastTomorrow_isRefused_andTomorrowIsNot() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "10000");

        ResponseEntity<String> later = addTxn(user, acc, today(user).plusDays(2).toString(), "100", "EXPENSE", "cafe", "Food & Dining");
        assertThat(later.getStatusCode()).as(later.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(later.getBody()).get("errorCode").asText()).isEqualTo("TXN_013");

        ResponseEntity<String> typo = addTxn(user, acc, "2099-01-05", "999", "EXPENSE", "typo date", "Shopping");
        assertThat(typo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // One day of leeway, as Gmail receipts get: a device a timezone ahead is not a typo.
        ok(addTxn(user, acc, today(user).plusDays(1).toString(), "100", "EXPENSE", "cafe", "Food & Dining"));
    }

    @Test
    void editingATransactionIntoTheFuture_isRefused() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "10000");
        UUID id = txn(user, acc, "2026-09-03", "100", "EXPENSE", "cafe", "Food & Dining");

        ResponseEntity<String> edit = send(HttpMethod.PUT, "/api/v1/transactions/" + id,
                "{\"date\":\"2099-09-03\"}", user);

        assertThat(edit.getStatusCode()).as(edit.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(edit.getBody()).get("errorCode").asText()).isEqualTo("TXN_013");
        assertThat(transactionRepository.findById(id).orElseThrow().getTxnDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void aFutureRowAlreadySaved_isLeftOutOfTheMoneyScreens() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "100000");
        txn(user, acc, "2026-08-01", "50000", "INCOME", "SALARY ACME", "Salary");
        txn(user, acc, "2026-09-01", "50000", "INCOME", "SALARY ACME", "Salary");
        txn(user, acc, "2026-09-12", "2000", "EXPENSE", "grocer", "Groceries");
        for (String d : List.of("2026-06-05", "2026-07-05", "2026-08-05", "2026-09-05")) {
            txn(user, acc, d, "649", "EXPENSE", "Netflix", "Entertainment");
        }
        LocalDate future = today(user).plusYears(70);
        String futureMonth = YearMonth.from(future).toString();
        moveToDate(txn(user, acc, "2026-09-20", "999", "EXPENSE", "typo date", "Shopping"), future);

        JsonNode dashboard = get(user, "/api/v1/dashboard/summary");
        assertThat(dashboard.get("reportingMonth").asText()).isEqualTo("2026-09");
        assertThat(dashboard.get("monthlyExpense").decimalValue()).isEqualByComparingTo("2649");

        assertThat(get(user, "/api/v1/reports/months").toString()).doesNotContain(futureMonth);
        assertThat(months(get(user, "/api/v1/reports/trend"))).last().isEqualTo("2026-09");
        assertThat(months(get(user, "/api/v1/reports/income-trend"))).doesNotContain(futureMonth);

        JsonNode insights = get(user, "/api/v1/insights");
        assertThat(insights.toString()).doesNotContain(futureMonth).doesNotContain("typo date");

        List<String> payees = new ArrayList<>();
        get(user, "/api/v1/recurring").forEach(g -> payees.add(g.get("merchant").asText()));
        assertThat(payees).anyMatch(p -> p.toLowerCase().contains("netflix"));
    }

    @Test
    void aRefundDatedInTheFuture_doesNotCancelAPurchaseYet() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "100000");
        txn(user, acc, "2026-09-10", "3000", "EXPENSE", "AMAZON order", "Shopping");
        UUID refund = txn(user, acc, "2026-09-14", "3000", "INCOME", "AMAZON REFUND order", "Refunds");
        assertThat(transactionRepository.findById(refund).orElseThrow().getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.REFUND);
        moveToDate(refund, today(user).plusYears(70));

        assertThat(get(user, "/api/v1/reports?month=2026-09").get("expense").decimalValue()).isEqualByComparingTo("3000");
    }

    // ------------------------------------------------------------------ reports

    @Test
    void aFullyRefundedCategory_isNotListedAtZero() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "100000");
        txn(user, acc, "2026-09-01", "40000", "INCOME", "SALARY ACME", "Salary");
        txn(user, acc, "2026-09-12", "1200", "EXPENSE", "grocer", "Groceries");
        txn(user, acc, "2026-09-21", "500", "EXPENSE", "CROMA store", "Electronics");
        UUID refund = txn(user, acc, "2026-09-24", "500", "INCOME", "CROMA REFUND", "Refunds");
        assertThat(transactionRepository.findById(refund).orElseThrow().getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.REFUND);

        JsonNode report = get(user, "/api/v1/reports?month=2026-09");
        assertThat(categories(report.get("categories"))).containsExactly("Groceries");
        assertThat(report.get("expense").decimalValue()).isEqualByComparingTo("1200");

        JsonNode dashboard = get(user, "/api/v1/dashboard/summary");
        assertThat(dashboard.get("spendByCategory").has("Electronics")).as(dashboard.get("spendByCategory").toString()).isFalse();

        List<String> movers = new ArrayList<>();
        get(user, "/api/v1/insights").get("movers").forEach(m -> movers.add(m.get("category").asText()));
        assertThat(movers).doesNotContain("Electronics");
    }

    @Test
    void theIncomeTrend_showsAMonthWithNoIncomeInsideTheHistory() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "100000");
        txn(user, acc, "2026-08-01", "50000", "INCOME", "SALARY ACME", "Salary");
        txn(user, acc, "2026-10-01", "50000", "INCOME", "SALARY ACME", "Salary");

        JsonNode points = get(user, "/api/v1/reports/income-trend");

        assertThat(months(points)).containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(points.get(1).get("income").decimalValue()).isEqualByComparingTo("0");
        // The other trend already said so: the two charts agree on which months exist.
        assertThat(months(get(user, "/api/v1/reports/trend"))).containsExactly("2026-08", "2026-09", "2026-10");
    }

    @Test
    void aMonthThatCannotExist_isA400() {
        User user = createUser();
        for (String month : List.of("%2B10000-01", "-0001-01", "0000-12")) {
            for (String path : List.of("/api/v1/reports?month=", "/api/v1/reports/trend?month=", "/api/v1/insights?month=")) {
                ResponseEntity<String> r = send(HttpMethod.GET, path + month, null, user);
                assertThat(r.getStatusCode()).as(path + month + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }
        // Insights did not parse its month at all: "In 2026-13, total spend was ₹0".
        ResponseEntity<String> thirteenth = send(HttpMethod.GET, "/api/v1/insights?month=2026-13", null, user);
        assertThat(thirteenth.getStatusCode()).as(thirteenth.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ net worth (held; kept as guards)

    @Test
    void netWorth_countsACardAsOwed_andAgreesWithTheDashboard() throws Exception {
        User user = createUser();
        account(user, "SAVINGS", "50000");
        account(user, "CREDIT_CARD", "15000");
        account(user, "INVESTMENT", "20000");

        JsonNode nw = get(user, "/api/v1/networth");
        assertThat(nw.get("totalAssets").decimalValue()).isEqualByComparingTo("70000");
        assertThat(nw.get("totalLiabilities").decimalValue()).isEqualByComparingTo("15000");
        assertThat(nw.get("netWorth").decimalValue()).isEqualByComparingTo("55000");
        assertThat(get(user, "/api/v1/dashboard/summary").get("netWorth").decimalValue()).isEqualByComparingTo("55000");
    }

    @Test
    void netWorth_aCardInCreditAddsToIt_andAnOverdraftTakesFromIt() throws Exception {
        User user = createUser();
        account(user, "SAVINGS", "-500");
        account(user, "CREDIT_CARD", "-2000");

        assertThat(get(user, "/api/v1/networth").get("netWorth").decimalValue()).isEqualByComparingTo("1500");
    }

    @Test
    void netWorth_snapshotsOnOneDayKeepOneRow_withTheLatestFigure() throws Exception {
        User user = createUser();
        UUID acc = account(user, "SAVINGS", "5000");
        ok(send(HttpMethod.POST, "/api/v1/networth/snapshot", null, user));
        ok(send(HttpMethod.PUT, "/api/v1/accounts/" + acc,
                "{\"name\":\"SAVINGS\",\"accountType\":\"SAVINGS\",\"balance\":15000}", user));
        JsonNode second = ok(send(HttpMethod.POST, "/api/v1/networth/snapshot", null, user));

        assertThat(second.get("history")).hasSize(1);
        assertThat(second.get("history").get(0).get("date").asText()).isEqualTo(today(user).toString());
        assertThat(second.get("history").get(0).get("netWorth").decimalValue()).isEqualByComparingTo("15000");
        assertThat(snapshotRepository.findByUserIdOrderBySnapshotDateAsc(user.getId())).hasSize(1);
    }

    @Test
    void netWorth_aDeletedAccountLeavesIt() throws Exception {
        User user = createUser();
        account(user, "SAVINGS", "50000");
        UUID card = account(user, "CREDIT_CARD", "15000");
        ok(send(HttpMethod.DELETE, "/api/v1/accounts/" + card, null, user));

        assertThat(get(user, "/api/v1/networth").get("netWorth").decimalValue()).isEqualByComparingTo("50000");
    }
}
