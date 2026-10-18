package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
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

/** /api/v1/people over HTTP: the session decides whose rows these are. */
class PeopleControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("people-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("People IT User");
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

    private Transaction txn(User owner, Transaction.Type type, int amount, String key) {
        Account a = new Account();
        a.setUserId(owner.getId());
        a.setName("People IT Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(a).getId();
        Transaction t = new Transaction();
        t.setUserId(owner.getId());
        t.setAccountId(accountId);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(LocalDate.of(2026, 9, 1));
        t.setDescription("UPI " + key);
        t.setCounterpartyKey(key);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    private ResponseEntity<String> post(User user, String path, String body) {
        return restTemplate.exchange("/api/v1/people" + path, HttpMethod.POST, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    @Test
    void linkAShareAndSeeItOnTheBill() throws Exception {
        User user = createUser();
        Transaction bill = txn(user, Transaction.Type.EXPENSE, 3000, "vpa:owner.b");
        Transaction credit = txn(user, Transaction.Type.INCOME, 1000, "vpa:flatmate.a");
        ResponseEntity<String> linked = post(user, "/shares",
                "{\"creditId\":\"" + credit.getId() + "\",\"billId\":\"" + bill.getId() + "\"}");
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> shares = restTemplate.exchange("/api/v1/people/bills/" + bill.getId() + "/shares",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        JsonNode data = mapper.readTree(shares.getBody()).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("amount").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void anotherUsersTransactionIs404() {
        User owner = createUser();
        Transaction bill = txn(owner, Transaction.Type.EXPENSE, 3000, "vpa:owner.b");
        Transaction credit = txn(owner, Transaction.Type.INCOME, 1000, "vpa:flatmate.a");
        ResponseEntity<String> response = post(createUser(), "/shares",
                "{\"creditId\":\"" + credit.getId() + "\",\"billId\":\"" + bill.getId() + "\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSenderNamedByAWeakKeyIs422() {
        User user = createUser();
        Transaction credit = txn(user, Transaction.Type.INCOME, 1000, "cut:abc");
        ResponseEntity<String> response = post(user, "/entries",
                "{\"txnId\":\"" + credit.getId() + "\",\"direction\":\"PAID_BACK\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void suggestionsAskAboutAShareAndForgetItOnceAcknowledged() throws Exception {
        User user = createUser();
        Transaction credit = txn(user, Transaction.Type.INCOME, 800, "vpa:friend.k");
        credit.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        credit.setTxnDate(LocalDate.now().minusDays(1));
        credit.setDescription("UPI/vpa:friend.k/dinner share");
        transactionRepository.save(credit);

        JsonNode items = suggestions(user).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("kind").asText()).isEqualTo("SHARE_CHECK");
        assertThat(items.get(0).get("txnId").asText()).isEqualTo(credit.getId().toString());

        restTemplate.exchange("/api/v1/insights/acknowledgements", HttpMethod.POST, new HttpEntity<>(
                "{\"kind\":\"SHARE_CHECK\",\"itemKey\":\"" + credit.getId() + "\"}", bearerFor(user)), String.class);
        assertThat(suggestions(user).get("items")).isEmpty();
    }

    private JsonNode suggestions(User user) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/people/suggestions", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void unauthenticatedIs401() {
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/people", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
