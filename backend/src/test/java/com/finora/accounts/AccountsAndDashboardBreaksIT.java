package com.finora.accounts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bank accounts and the Dashboard through the real security chain, controller advice and
 * database, fed what broke them in live testing on a local backend (2026-10-26):
 * <ul>
 *   <li>six account creations at once on the free plan made six accounts, past its cap of two;</li>
 *   <li>a full account number typed into the masked field was stored as typed, where the column
 *       is documented never to hold one;</li>
 *   <li>an IFSC of any shape was stored, lower case was kept, and a negative credit limit was
 *       accepted;</li>
 *   <li>an edit that changed an account's type answered "Account updated" and kept the old type;</li>
 *   <li>one rent payment early in a new month, before its salary, took the health score from
 *       100 to 75: the savings-rate part read a three-day month;</li>
 *   <li>a custom range without dates, or ending before it starts, answered "One of the request's
 *       parameters is not valid", and one reaching back before year 1 a "number too large" error.</li>
 * </ul>
 */
class AccountsAndDashboardBreaksIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("accounts-dashboard-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Accounts Dashboard IT User");
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

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> createAccount(User user, String json) {
        return send(HttpMethod.POST, "/api/v1/accounts", json, user);
    }

    private UUID account(User user, String json) throws Exception {
        return UUID.fromString(ok(createAccount(user, json)).get("id").asText());
    }

    private void txn(User user, UUID accountId, String date, String amount, String type, String description,
                     String category) throws Exception {
        ok(send(HttpMethod.POST, "/api/v1/transactions",
                "{\"accountId\":\"" + accountId + "\",\"categoryName\":\"" + category + "\",\"date\":\"" + date
                        + "\",\"description\":\"" + description + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\"}",
                user));
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void sixCreationsAtOnceOnTheFreePlan_leaveTwoAccounts() throws Exception {
        User user = createUser();
        List<Integer> codes = concurrently(6, i -> createAccount(user,
                "{\"name\":\"Bank " + i + "\",\"accountType\":\"SAVINGS\",\"balance\":1000}").getStatusCode().value());

        assertThat(codes).filteredOn(c -> c == 200).hasSize(2);
        assertThat(codes).filteredOn(c -> c != 200).containsOnly(403);
        assertThat(accountRepository.findByUserId(user.getId())).hasSize(2);
    }

    @Test
    void aFullAccountNumber_isStoredMasked_onCreateAndEdit() throws Exception {
        User user = createUser();
        UUID id = account(user, "{\"name\":\"HDFC\",\"accountType\":\"SAVINGS\",\"balance\":10,\"accountNumberMasked\":\"100000009012\"}");
        assertThat(accountRepository.findById(id).orElseThrow().getAccountNumberMasked()).isEqualTo("••••9012");

        ok(send(HttpMethod.PUT, "/api/v1/accounts/" + id,
                "{\"name\":\"HDFC\",\"accountType\":\"SAVINGS\",\"accountNumberMasked\":\"5011 2233 4455 6677\"}", user));
        assertThat(accountRepository.findById(id).orElseThrow().getAccountNumberMasked()).isEqualTo("••••6677");

        // A bank's own masked form, and a bare last four, are kept as given.
        ok(send(HttpMethod.PUT, "/api/v1/accounts/" + id,
                "{\"name\":\"HDFC\",\"accountType\":\"SAVINGS\",\"accountNumberMasked\":\"XXXXXX4587\"}", user));
        assertThat(accountRepository.findById(id).orElseThrow().getAccountNumberMasked()).isEqualTo("XXXXXX4587");
    }

    @Test
    void anIfscIsCheckedAndStoredInCapitals() throws Exception {
        User user = createUser();
        ResponseEntity<String> bad = createAccount(user, "{\"name\":\"IFSC\",\"accountType\":\"SAVINGS\",\"balance\":1,\"ifscCode\":\"not-ifsc!!\"}");
        assertThat(bad.getStatusCode()).as(bad.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(bad).get("message").asText()).contains("IFSC");

        UUID id = account(user, "{\"name\":\"IFSC\",\"accountType\":\"SAVINGS\",\"balance\":1,\"ifscCode\":\"hdfc0xxxxxx\"}");
        assertThat(accountRepository.findById(id).orElseThrow().getIfscCode()).isEqualTo("HDFC0XXXXXX");
    }

    @Test
    void aNegativeCreditLimit_isRefused() throws Exception {
        User user = createUser();
        ResponseEntity<String> r = createAccount(user,
                "{\"name\":\"Card\",\"accountType\":\"CREDIT_CARD\",\"balance\":5000,\"creditLimit\":-20000}");
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(r).get("message").asText()).containsIgnoringCase("credit limit");
    }

    @Test
    void changingAnAccountsType_isRefusedWithAReason_andTheSameTypeStillSaves() throws Exception {
        User user = createUser();
        UUID id = account(user, "{\"name\":\"HDFC\",\"accountType\":\"SAVINGS\",\"balance\":50000}");

        ResponseEntity<String> change = send(HttpMethod.PUT, "/api/v1/accounts/" + id,
                "{\"name\":\"HDFC\",\"accountType\":\"CREDIT_CARD\",\"balance\":50000}", user);
        assertThat(change.getStatusCode()).as(change.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(change).get("message").asText()).contains("type");
        assertThat(accountRepository.findById(id).orElseThrow().getAccountType()).isEqualTo(com.finora.entity.Account.Type.SAVINGS);

        // A rename sends the type it already has (Setup's Rename action), and still saves.
        JsonNode renamed = ok(send(HttpMethod.PUT, "/api/v1/accounts/" + id,
                "{\"name\":\"HDFC Salary\",\"accountType\":\"SAVINGS\"}", user));
        assertThat(renamed.get("name").asText()).isEqualTo("HDFC Salary");
    }

    // ------------------------------------------------------------------ dashboard

    @Test
    void theHealthScore_isNotPulledDownByAMonthThatHasOnlyJustStarted() throws Exception {
        User user = createUser();
        UUID acc = account(user, "{\"name\":\"Bank\",\"accountType\":\"SAVINGS\",\"balance\":200000}");
        for (String m : List.of("05", "06", "07", "08", "09")) {
            txn(user, acc, "2026-" + m + "-01", "50000", "INCOME", "SALARY ACME", "Salary");
            txn(user, acc, "2026-" + m + "-10", "12000", "EXPENSE", "rent", "Rent");
            txn(user, acc, "2026-" + m + "-15", "3000", "EXPENSE", "grocer", "Groceries");
            txn(user, acc, "2026-" + m + "-28", "2000", "EXPENSE", "cafe", "Food & Dining");
        }
        JsonNode before = ok(send(HttpMethod.GET, "/api/v1/dashboard/summary", null, user));
        assertThat(before.get("healthScore").asInt()).isEqualTo(100);

        // October's rent, three days in, before October's salary.
        txn(user, acc, "2026-10-03", "12000", "EXPENSE", "rent", "Rent");

        JsonNode after = ok(send(HttpMethod.GET, "/api/v1/dashboard/summary", null, user));
        assertThat(after.get("healthBreakdown").get("Savings Rate").asDouble()).isEqualTo(100.0);
        assertThat(after.get("healthScore").asInt()).isEqualTo(100);
    }

    @Test
    void aCustomRangeThatCannotBeShown_saysWhy() throws Exception {
        User user = createUser();
        String base = "/api/v1/dashboard/range-summary?rangeType=CUSTOM";
        record Case(String query, String says) {}
        for (Case c : List.of(
                new Case("", "start and an end date"),
                new Case("&startDate=2026-06-01", "start and an end date"),
                new Case("&startDate=2026-09-01&endDate=2026-06-01", "after its end"),
                new Case("&startDate=0001-01-01&endDate=9999-12-31", "too long"),
                new Case("&startDate=0000-06-01&endDate=0000-06-30", "9999"))) {
            ResponseEntity<String> r = send(HttpMethod.GET, base + c.query(), null, user);
            assertThat(r.getStatusCode()).as(c.query() + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(r).get("message").asText()).as(c.query()).contains(c.says());
        }
        ok(send(HttpMethod.GET, base + "&startDate=2026-07-10&endDate=2026-07-10", null, user));
    }

    private interface Indexed<T> { T call(int i) throws Exception; }

    private static List<Integer> concurrently(int n, Indexed<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit((Callable<Integer>) () -> call.call(index)));
            }
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
