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
 * The Dashboard's "so far" note, through the real endpoints and a real database: a bank account
 * that runs to 30 Sep next to a card whose statement ends 11 Sep. The figures must still come back
 * (the note is a label, not a reason to withhold them), and the note must name the card.
 */
class DashboardStatementReachIT extends AbstractIntegrationTest {

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
        user.setEmail("dashboard-reach-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Dashboard Reach IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        return user;
    }

    private UUID createAccount(User user, String name, Account.Type type, Account.PrimarySource source) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName(name);
        account.setAccountType(type);
        account.setBalance(BigDecimal.ZERO);
        account.setAccountHolderName("Dashboard Reach IT User");
        account.setPrimarySource(source);
        return accountRepository.save(account).getId();
    }

    private void expense(User user, UUID accountId, String amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(accountId);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(date);
        t.setDescription("Dashboard reach IT purchase");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        transactionRepository.save(t);
    }

    private void statement(User user, UUID accountId, LocalDate start, LocalDate end) {
        StatementImport s = new StatementImport();
        s.setUserId(user.getId());
        s.setAccountId(accountId);
        s.setFileName("card.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(new byte[] {1});
        s.setContentHash("dashboard-reach-it-" + UUID.randomUUID());
        s.setStatementPeriodStart(start);
        s.setStatementPeriodEnd(end);
        statementImportRepository.save(s);
    }

    /** Bank to 30 Sep, plus a card active in Aug and Sep whose latest statement ends on `cardEnd`. */
    private User userWithACard(Account.PrimarySource cardSource, LocalDate cardEnd) {
        User user = createUser();
        UUID bank = createAccount(user, "Dashboard IT Bank", Account.Type.SAVINGS, Account.PrimarySource.MANUAL);
        expense(user, bank, "100.00", LocalDate.of(2026, 8, 5));
        expense(user, bank, "100.00", LocalDate.of(2026, 8, 25));
        expense(user, bank, "100.00", LocalDate.of(2026, 9, 3));
        expense(user, bank, "100.00", LocalDate.of(2026, 9, 30));
        UUID card = createAccount(user, "Dashboard IT Card", Account.Type.CREDIT_CARD, cardSource);
        expense(user, card, "250.00", LocalDate.of(2026, 8, 20));
        expense(user, card, "250.00", LocalDate.of(2026, 9, 5));
        statement(user, card, LocalDate.of(2026, 8, 12), cardEnd);
        return user;
    }

    private JsonNode data(String path, User user) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void theMonthSummary_namesTheCardWhoseStatementStopsShort_andStillReturnsTheFigures() throws Exception {
        User user = userWithACard(Account.PrimarySource.MANUAL, LocalDate.of(2026, 9, 11));

        JsonNode summary = data("/api/v1/dashboard/summary", user);

        assertThat(summary.get("reportingMonth").asText()).isEqualTo("2026-09");
        JsonNode behind = summary.get("statementBehind");
        assertThat(behind.get("accountName").asText()).isEqualTo("Dashboard IT Card");
        assertThat(behind.get("statementEnd").asText()).isEqualTo("2026-09-11");
        assertThat(behind.get("accountCount").asInt()).isEqualTo(1);
        // Labelled, not withheld: September's own spend is there.
        assertThat(summary.get("monthlyExpense").decimalValue()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void theRangeSummary_carriesTheSameNote_forARangeEndingInThatMonth() throws Exception {
        User user = userWithACard(Account.PrimarySource.MANUAL, LocalDate.of(2026, 9, 11));

        JsonNode range = data("/api/v1/dashboard/range-summary?rangeType=LAST_3_MONTHS", user);

        assertThat(range.get("statementBehind").get("accountName").asText()).isEqualTo("Dashboard IT Card");
        assertThat(range.get("expenseTotal").decimalValue()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void aCustomRangeEndingBeforeTheNewestTwoMonths_hasNoNote() throws Exception {
        User user = userWithACard(Account.PrimarySource.MANUAL, LocalDate.of(2026, 9, 11));

        JsonNode range = data("/api/v1/dashboard/range-summary?rangeType=CUSTOM&startDate=2026-01-01&endDate=2026-06-30", user);

        assertThat(range.get("statementBehind").isNull()).isTrue();
    }

    @Test
    void aCardWhoseStatementReachesMonthEnd_hasNoNote() throws Exception {
        User user = userWithACard(Account.PrimarySource.MANUAL, LocalDate.of(2026, 9, 30));

        assertThat(data("/api/v1/dashboard/summary", user).get("statementBehind").isNull()).isTrue();
    }

    @Test
    void anAccountAggregatorCard_hasNoStatementReachToBeBehindOn() throws Exception {
        User user = userWithACard(Account.PrimarySource.ACCOUNT_AGGREGATOR, LocalDate.of(2026, 9, 11));

        assertThat(data("/api/v1/dashboard/summary", user).get("statementBehind").isNull()).isTrue();
    }

    @Test
    void aUserWithNoTransactions_getsAnOrdinarySummaryWithNoNote() throws Exception {
        User user = createUser();

        assertThat(data("/api/v1/dashboard/summary", user).get("statementBehind").isNull()).isTrue();
        assertThat(data("/api/v1/dashboard/range-summary", user).get("statementBehind").isNull()).isTrue();
    }
}
