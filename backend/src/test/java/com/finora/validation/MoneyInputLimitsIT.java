package com.finora.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money endpoints through the real security chain, controller advice, JSON reader and
 * database, fed the inputs that broke them in live testing (2026-10-24). Before: values the columns
 * cannot hold came back as a 409 "refresh and try again"; a balance of 1E+131072 was answered with
 * the number and stored as 0.00 (the Postgres driver zeroes it); 1E-999999999 was a 500; a filter
 * of amountMin=1e999999999 was ignored; a third decimal place was rounded by the column; a typed
 * category name was cut to 80 characters; a valid income into a near-full account was a 409.
 */
class MoneyInputLimitsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("money-limits-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Money Limits IT User");
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

    /** For a query string that must reach the server exactly as written: a String path is a URI
     *  template, which would encode the "%" in "%2B" a second time. */
    private ResponseEntity<String> getExact(String pathAndQuery, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return restTemplate.exchange(java.net.URI.create(pathAndQuery), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private UUID createAccount(User user, String balance) throws Exception {
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Savings\",\"accountType\":\"SAVINGS\",\"balance\":" + balance + "}", user);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(json(created).get("data").get("id").asText());
    }

    private String txn(UUID accountId, String amount, String date, String description, String type) {
        return "{\"accountId\":\"" + accountId + "\",\"categoryName\":\"Groceries\",\"date\":\"" + date
                + "\",\"description\":\"" + description + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\"}";
    }

    private void assertBadRequestNaming(ResponseEntity<String> response, String fieldPrefix) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).startsWith(fieldPrefix);
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void anAccountBalanceTheColumnCannotHold_isA400NamingTheField_notA409() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Big\",\"accountType\":\"SAVINGS\",\"balance\":1000000000000}", createUser());
        assertBadRequestNaming(response, "balance:");
    }

    @Test
    void aBalanceThePostgresDriverWouldSendAsZero_isRefused_notStoredAsZero() throws Exception {
        User user = createUser();
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Huge\",\"accountType\":\"SAVINGS\",\"balance\":1E+131072}", user);

        assertBadRequestNaming(response, "balance:");
        assertThat(accountRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void aBalanceWithAHugeNegativeExponent_isA400_notA500() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Tiny\",\"accountType\":\"SAVINGS\",\"balance\":1E-999999999}", createUser());
        assertBadRequestNaming(response, "balance:");
    }

    @Test
    void aBalanceFinerThanOnePaisa_isRefused() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Frac\",\"accountType\":\"SAVINGS\",\"balance\":0.015}", createUser());
        assertBadRequestNaming(response, "balance:");
    }

    @Test
    void aCreditLimitTheColumnCannotHold_isA400_notA409() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Card\",\"accountType\":\"CREDIT_CARD\",\"balance\":0,\"creditLimit\":1000000000000}", createUser());
        assertBadRequestNaming(response, "creditLimit:");
    }

    @Test
    void aDueDateInAFiveDigitYear_isA400_notA409() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Card\",\"accountType\":\"CREDIT_CARD\",\"balance\":0,\"dueDate\":\"+999999999-12-31\"}", createUser());
        assertBadRequestNaming(response, "dueDate:");
    }

    @Test
    void anAccountNameWithANulCharacter_isA400_notA409() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Acc\\u0000x\",\"accountType\":\"SAVINGS\",\"balance\":1}", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("VAL_002");
    }

    @Test
    void theLargestStorableBalance_stillSaves() throws Exception {
        User user = createUser();
        UUID id = createAccount(user, "999999999999.99");
        assertThat(accountRepository.findById(id).orElseThrow().getBalance()).isEqualByComparingTo(MoneyLimits.MAX_AMOUNT);
    }

    @Test
    void aValidIncomeIntoANearFullAccount_isA400WithItsOwnCode_andChangesNothing() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "999999999999.00");
        // After the day the typed balance is known through (yesterday, in the user's zone), so it
        // moves the balance (BalanceCoverage) -- and not after tomorrow, which entry refuses
        // (TXN_013, ReportableDates): today.
        String later = LocalDate.now(com.finora.util.UserZone.of(user.getTimezone())).toString();

        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "5.00", later, "Pay", "INCOME"), user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("ACC_003");
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("999999999999.00");
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
    }

    // ------------------------------------------------------------------ budgets

    @Test
    void aBudgetLimitTheColumnCannotHold_isA400_notA409() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.PUT, "/api/v1/budgets",
                "{\"categoryName\":\"Groceries\",\"monthlyLimit\":1000000000000}", createUser());
        assertBadRequestNaming(response, "monthlyLimit:");
    }

    @Test
    void aBudgetLimitFinerThanOnePaisa_isRefused_butTrailingZerosAreNot() throws Exception {
        User user = createUser();
        assertBadRequestNaming(send(HttpMethod.PUT, "/api/v1/budgets",
                "{\"categoryName\":\"Groceries\",\"monthlyLimit\":0.015}", user), "monthlyLimit:");
        assertThat(send(HttpMethod.PUT, "/api/v1/budgets",
                "{\"categoryName\":\"Groceries\",\"monthlyLimit\":100.500}", user).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ transactions

    @Test
    void aTransactionAmountFinerThanOnePaisa_isRefused() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "0.015", "2026-10-01", "Frac", "EXPENSE"), user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).contains("2 decimal places");
    }

    @Test
    void aTransactionAmountWithAHugeNegativeExponent_isA400_notA500() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        assertBadRequestNaming(send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "1E-999999999", "2026-10-01", "Tiny", "EXPENSE"), user), "amount:");
    }

    @Test
    void aTransactionDateInAFiveDigitYear_isRefused_notSavedAsPlus10000() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        assertBadRequestNaming(send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "10", "+10000-01-01", "Far", "EXPENSE"), user), "date:");
        assertBadRequestNaming(send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "10", "+999999999-12-31", "Farther", "EXPENSE"), user), "date:");
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void aTransactionDescriptionWithANulCharacter_isA400_notA409() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "10", "2026-10-01", "Pro\\u0000be", "EXPENSE"), user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("VAL_002");
    }

    @Test
    void aTypedCategoryNameTooLongToStore_isRefused_notCutTo80() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        String body = "{\"accountId\":\"" + accountId + "\",\"categoryName\":\"" + "c".repeat(81)
                + "\",\"date\":\"2026-10-01\",\"description\":\"Cat\",\"amount\":10,\"type\":\"EXPENSE\"}";
        assertBadRequestNaming(send(HttpMethod.POST, "/api/v1/transactions", body, user), "categoryName:");
    }

    @Test
    void anAmountFilterTheDriverWouldIgnore_isA400_notAnUnfilteredList() throws Exception {
        User user = createUser();
        UUID accountId = createAccount(user, "1000");
        assertThat(send(HttpMethod.POST, "/api/v1/transactions",
                txn(accountId, "10", "2026-10-01", "Row", "EXPENSE"), user).getStatusCode()).isEqualTo(HttpStatus.OK);

        for (String value : new String[]{"1e999999999", "1E-999999999"}) {
            ResponseEntity<String> response = send(HttpMethod.GET, "/api/v1/transactions?amountMin=" + value, null, user);
            assertThat(response.getStatusCode()).as(value).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).get("message").asText()).as(value).contains("amountMin");
        }
        // An ordinary large filter still filters.
        ResponseEntity<String> ordinary = send(HttpMethod.GET, "/api/v1/transactions?amountMin=1000", null, user);
        assertThat(json(ordinary).get("data").get("content")).isEmpty();
    }

    @Test
    void aDateFilterTheDatabaseCannotHold_isA400_notA409() throws Exception {
        ResponseEntity<String> response = getExact("/api/v1/transactions?dateFrom=%2B999999999-12-31", createUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("VAL_002");
    }

    // ------------------------------------------------------------------ settings

    @Test
    void theLowBalanceThreshold_takesTrailingZeros_andNamesWhatIsWrongWithATooLargeOne() throws Exception {
        User user = createUser();
        assertThat(send(HttpMethod.PUT, "/api/v1/users/me", "{\"lowBalanceThreshold\":100.500}", user)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> tooLarge = send(HttpMethod.PUT, "/api/v1/users/me", "{\"lowBalanceThreshold\":1000000000000}", user);
        assertBadRequestNaming(tooLarge, "lowBalanceThreshold:");
        assertThat(json(tooLarge).get("message").asText()).contains("at most 999,999,999,999.99");
    }
}
