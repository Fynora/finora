package com.finora.transactions;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ledger's search endpoint over real HTTP and real Postgres.
 *
 * <p>Nothing exercised {@code GET /api/v1/transactions} end to end before this: the service tests
 * mock the repository, and the repository test passed only NULL date bounds -- so a 500 on every
 * date-filtered request (production, 2026-09-19, PostgreSQL 42P18) was invisible to the suite. This
 * drives the query strings a client actually sends.
 */
class TransactionSearchEndpointIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Seeded(User user, UUID accountId) {}

    private Seeded seed() {
        User user = new User();
        user.setEmail("txn-search-endpoint-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Txn Search Endpoint IT");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        UUID accountId = null;
        // Two live accounts: the failing placeholder's index moves with the count, and production
        // had two.
        for (int i = 0; i < 2; i++) {
            Account a = new Account();
            a.setUserId(user.getId());
            a.setName("Account " + i);
            a.setAccountType(Account.Type.SAVINGS);
            a.setBalance(BigDecimal.TEN);
            a = accountRepository.save(a);
            if (i == 0) accountId = a.getId();
        }
        for (LocalDate d : new LocalDate[]{LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 5)}) {
            Transaction t = new Transaction();
            t.setUserId(user.getId());
            t.setAccountId(accountId);
            t.setAmount(BigDecimal.valueOf(250));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setTxnDate(d);
            t.setDescription("SWIGGY ORDER " + d);
            transactionRepository.save(t);
        }
        return new Seeded(user, accountId);
    }

    private JsonNode get(User user, String query) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transactions" + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private int count(User user, String query) throws Exception {
        return get(user, query).get("content").size();
    }

    @Test
    void noFilterReturnsEveryRow() throws Exception {
        assertThat(count(seed().user(), "")).isEqualTo(3);
    }

    @Test
    void dateFilteredRequestsSucceedAndFilter() throws Exception {
        User user = seed().user();
        assertThat(count(user, "?dateFrom=2026-07-01")).isEqualTo(2);
        assertThat(count(user, "?dateTo=2026-07-31")).isEqualTo(2);
        assertThat(count(user, "?dateFrom=2026-07-10&dateTo=2026-08-05")).isEqualTo(2);
        assertThat(count(user, "?dateFrom=2026-07-01&dateTo=2026-07-31")).isEqualTo(1);
    }

    /** Every filter at once, so no other nullable parameter has the same untyped-placeholder problem. */
    @Test
    void everyFilterAtOnceSucceeds() throws Exception {
        Seeded s = seed();
        String query = "?accountId=" + s.accountId()
                + "&type=EXPENSE&dateFrom=2026-07-01&dateTo=2026-07-31"
                + "&amountMin=100&amountMax=500&keyword=swiggy&page=0&size=20&sortField=date&sortDir=desc";
        assertThat(count(s.user(), query)).isEqualTo(1);
    }

    @Test
    void aKeywordAloneAndACategoryAloneStillSucceed() throws Exception {
        User user = seed().user();
        assertThat(count(user, "?keyword=swiggy")).isEqualTo(3);
        assertThat(count(user, "?categoryId=" + UUID.randomUUID())).isEqualTo(0);
    }

    /**
     * The exact query the Investments page's "SIPs &amp; broker transfers" section sends (web
     * InvestmentActivity, mobile InvestmentActivityCard): the Investments category, outflows only,
     * newest first, a date range, and the API's maximum page size. Also pins the response fields the
     * section reads, so renaming one server-side fails here rather than blanking the section.
     */
    @Test
    void theInvestmentsPageQuery_returnsOnlyThatCategorysOutflows_withTheFieldsTheUiReads() throws Exception {
        Seeded s = seed();
        Category investments = new Category();
        investments.setUserId(s.user().getId());
        investments.setName("Investments");
        investments = categoryRepository.save(investments);
        Category groceries = new Category();
        groceries.setUserId(s.user().getId());
        groceries.setName("Groceries");
        groceries = categoryRepository.save(groceries);

        addRow(s, investments.getId(), Transaction.Type.EXPENSE, LocalDate.of(2026, 8, 5), "SIP ONE", "3000");
        addRow(s, investments.getId(), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 5), "SIP TWO", "2000");
        addRow(s, investments.getId(), Transaction.Type.INCOME, LocalDate.of(2026, 7, 20), "REDEMPTION", "9000");
        addRow(s, groceries.getId(), Transaction.Type.EXPENSE, LocalDate.of(2026, 7, 6), "BIGBASKET", "800");
        addRow(s, investments.getId(), Transaction.Type.EXPENSE, LocalDate.of(2025, 1, 5), "OLD SIP", "1000");

        JsonNode data = get(s.user(), "?categoryId=" + investments.getId() + "&type=EXPENSE"
                + "&dateFrom=2026-06-01&dateTo=2026-09-21&page=0&size=100&sortField=date&sortDir=desc");

        JsonNode rows = data.get("content");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("description").asText()).isEqualTo("SIP ONE"); // newest first
        assertThat(rows.get(1).get("description").asText()).isEqualTo("SIP TWO");
        assertThat(rows.get(0).get("date").asText()).isEqualTo("2026-08-05");
        // A JSON number, not a string: the UI sums it, and "3000" + "2000" would concatenate.
        assertThat(rows.get(0).get("amount").isNumber()).isTrue();
        assertThat(rows.get(0).get("amount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(rows.get(0).has("reconciliationStatus")).isTrue();
        assertThat(rows.get(0).get("id").asText()).isNotBlank();
        assertThat(data.get("totalPages").asInt()).isEqualTo(1);
    }

    private void addRow(Seeded s, UUID categoryId, Transaction.Type type, LocalDate date, String description, String amount) {
        Transaction t = new Transaction();
        t.setUserId(s.user().getId());
        t.setAccountId(s.accountId());
        t.setCategoryId(categoryId);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setTxnDate(date);
        t.setDescription(description);
        transactionRepository.save(t);
    }
}
