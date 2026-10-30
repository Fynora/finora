package com.finora.transactions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transactions through the real security chain, controller advice and database, fed what broke
 * them in live testing on a local backend (2026-10-30):
 * <ul>
 *   <li>a bulk delete naming one row twice reversed its balance effect twice -- the balance rose by
 *       the row's amount a second time -- and reported "2 transaction(s) deleted";</li>
 *   <li>adding a transaction without an account was a 500, and without a date a 409 "conflicts with
 *       a record that already exists";</li>
 *   <li>a description of spaces, or none at all, was accepted;</li>
 *   <li>a category name over the column's 80 characters was cut without a word on the single
 *       category change, bulk recategorize and the Quick sort answer;</li>
 *   <li>acknowledging a bank correction on a row that had none wrote an "acknowledged" entry into
 *       that row's correction history.</li>
 * </ul>
 */
class TransactionsBreaksIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("transactions-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Transactions IT User");
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
        return mapper.readTree(response.getBody());
    }

    private String message(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("message").asText();
    }

    private UUID account(User user) throws Exception {
        return UUID.fromString(ok(send(HttpMethod.POST, "/api/v1/accounts",
                "{\"name\":\"Bank\",\"accountType\":\"SAVINGS\",\"balance\":10000}", user)).get("data").get("id").asText());
    }

    /** Today in the user's zone: a row dated after the typed balance's day moves the balance. */
    private static String today(User user) {
        return LocalDate.now(UserZone.of(user.getTimezone())).toString();
    }

    private UUID txn(User user, UUID accountId, String date, String amount, String description, String category)
            throws Exception {
        String categoryField = category == null ? "" : ",\"categoryName\":\"" + category + "\"";
        return UUID.fromString(ok(send(HttpMethod.POST, "/api/v1/transactions",
                "{\"accountId\":\"" + accountId + "\",\"date\":\"" + date + "\",\"amount\":" + amount
                        + ",\"type\":\"EXPENSE\",\"description\":\"" + description + "\"" + categoryField + "}",
                user)).get("data").get("id").asText());
    }

    private java.math.BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    // ------------------------------------------------------------------ bulk

    @Test
    void aBulkDeleteNamingARowTwice_movesTheBalanceOnce() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID row = txn(user, acc, today(user), "250", "bulk dup", "Shopping");
        assertThat(balance(acc)).isEqualByComparingTo("9750");

        JsonNode done = ok(send(HttpMethod.POST, "/api/v1/transactions/bulk-delete",
                "{\"ids\":[\"" + row + "\",\"" + row + "\"]}", user));

        assertThat(balance(acc)).isEqualByComparingTo("10000");
        assertThat(done.get("message").asText()).isEqualTo("1 transaction(s) deleted");
    }

    @Test
    void aBulkRecategorizeNamingARowTwice_countsItOnce() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID row = txn(user, acc, "2026-09-01", "100", "SWIGGY order", "Other");

        JsonNode done = ok(send(HttpMethod.POST, "/api/v1/transactions/bulk-category",
                "{\"ids\":[\"" + row + "\",\"" + row + "\"],\"category\":\"Food & Dining\"}", user));

        assertThat(done.get("message").asText()).isEqualTo("1 transaction(s) recategorized");
    }

    // ------------------------------------------------------------------ create

    @Test
    void addingWithoutAnAccountOrADate_isA400ThatSaysWhich() throws Exception {
        User user = createUser();
        UUID acc = account(user);

        ResponseEntity<String> noAccount = send(HttpMethod.POST, "/api/v1/transactions",
                "{\"date\":\"" + today(user) + "\",\"amount\":10,\"type\":\"EXPENSE\",\"description\":\"x\"}", user);
        assertThat(noAccount.getStatusCode()).as(noAccount.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(message(noAccount)).containsIgnoringCase("account");

        ResponseEntity<String> noDate = send(HttpMethod.POST, "/api/v1/transactions",
                "{\"accountId\":\"" + acc + "\",\"amount\":10,\"type\":\"EXPENSE\",\"description\":\"x\"}", user);
        assertThat(noDate.getStatusCode()).as(noDate.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(message(noDate)).containsIgnoringCase("date");
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void aBlankOrMissingDescription_isRefused() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        for (String description : List.of(",\"description\":\"   \"", "")) {
            ResponseEntity<String> r = send(HttpMethod.POST, "/api/v1/transactions",
                    "{\"accountId\":\"" + acc + "\",\"date\":\"" + today(user) + "\",\"amount\":10,\"type\":\"EXPENSE\""
                            + description + "}", user);
            assertThat(r.getStatusCode()).as(description + " " + r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(message(r)).containsIgnoringCase("description");
        }
        UUID row = txn(user, acc, today(user), "10", "grocer", "Groceries");
        ResponseEntity<String> edit = send(HttpMethod.PUT, "/api/v1/transactions/" + row, "{\"description\":\"   \"}", user);
        assertThat(edit.getStatusCode()).as(edit.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transactionRepository.findById(row).orElseThrow().getDescription()).isEqualTo("grocer");
    }

    // ------------------------------------------------------------------ categories

    @Test
    void aCategoryNameOver80Characters_isRefused_onEveryPathThatNamesOne() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID row = txn(user, acc, "2026-09-01", "100", "SWIGGY order", "Other");
        String long81 = "C".repeat(81);

        ResponseEntity<String> single = send(HttpMethod.PATCH, "/api/v1/transactions/" + row + "/category",
                "{\"category\":\"" + long81 + "\",\"applyTo\":\"ONLY_THIS\"}", user);
        assertThat(single.getStatusCode()).as(single.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(message(single)).contains("80 characters");

        ResponseEntity<String> bulk = send(HttpMethod.POST, "/api/v1/transactions/bulk-category",
                "{\"ids\":[\"" + row + "\"],\"category\":\"" + long81 + "\"}", user);
        assertThat(bulk.getStatusCode()).as(bulk.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(message(bulk)).contains("80 characters");

        // Quick sort: a row the engine filed, so it is waiting for an answer.
        UUID waiting = txn(user, acc, "2026-09-04", "9000", "NEFT/100000000013/MYSTERY TRADERS", null);
        JsonNode question = ok(send(HttpMethod.GET, "/api/v1/transactions/quick-sort", null, user))
                .get("data").get("questions").get(0);
        ResponseEntity<String> answer = send(HttpMethod.POST, "/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + question.get("anchorTransactionId").asText() + "\",\"category\":\""
                        + long81 + "\",\"kind\":\"" + question.get("kind").asText() + "\"}", user);
        assertThat(answer.getStatusCode()).as(answer.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(message(answer)).contains("80 characters");

        assertThat(categoryRepository.findByUserId(user.getId())).noneMatch(c -> c.getName().startsWith("CCCC"));
        assertThat(transactionRepository.findById(waiting)).isPresent();
    }

    // ------------------------------------------------------------------ corrections

    @Test
    void acknowledgingACorrectionThatIsNotThere_writesNothing() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID row = txn(user, acc, today(user), "10", "grocer", "Groceries");

        ok(send(HttpMethod.POST, "/api/v1/transactions/" + row + "/acknowledge-bank-correction", null, user));

        JsonNode history = ok(send(HttpMethod.GET, "/api/v1/transactions/" + row + "/correction-history", null, user));
        assertThat(history.get("data")).isEmpty();
    }

    // Found testing the screens: both edit dialogs send the category with every save, so changing
    // only the amount pinned the engine's category as the user's choice and cleared "Needs review".
    @Test
    void anEditThatLeavesTheCategoryAlone_keepsItsSourceAndReviewFlag() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID row = txn(user, acc, today(user), "40", "sample shop", null);
        Transaction before = transactionRepository.findById(row).orElseThrow();
        before.setNeedsCategoryReview(true);
        transactionRepository.save(before);
        String current = categoryRepository.findById(before.getCategoryId()).orElseThrow().getName();

        ok(send(HttpMethod.PUT, "/api/v1/transactions/" + row,
                "{\"amount\":45,\"merchant\":\"Sample Shop\",\"categoryName\":\"" + current + "\"}", user));

        Transaction kept = transactionRepository.findById(row).orElseThrow();
        assertThat(kept.getAmount()).isEqualByComparingTo("45");
        assertThat(kept.isCategoryManuallySet()).isFalse();
        assertThat(kept.isNeedsCategoryReview()).isTrue();

        ok(send(HttpMethod.PUT, "/api/v1/transactions/" + row, "{\"categoryName\":\"Groceries\"}", user));

        Transaction chosen = transactionRepository.findById(row).orElseThrow();
        assertThat(chosen.isCategoryManuallySet()).isTrue();
        assertThat(chosen.isNeedsCategoryReview()).isFalse();
    }

    // Found testing the screens: the narration rule stripped "Transfer" from a typed entry, so every
    // screen titled it "from icici".
    @Test
    void aTypedEntryKeepsEveryWordOfItsName_whileAPastedNarrationIsStillRead() throws Exception {
        User user = createUser();
        UUID acc = account(user);
        UUID typed = txn(user, acc, today(user), "50", "Transfer from ICICI", "Other");
        UUID pasted = txn(user, acc, today(user), "60",
                "UPI/DR/100000000001/SAMPLE STORE/HDFC/samplestore@okhdfc", "Other");

        assertThat(transactionRepository.findById(typed).orElseThrow().getMerchant()).isEqualTo("transfer from icici");
        assertThat(transactionRepository.findById(pasted).orElseThrow().getMerchant()).isEqualTo("sample store");
    }
}
